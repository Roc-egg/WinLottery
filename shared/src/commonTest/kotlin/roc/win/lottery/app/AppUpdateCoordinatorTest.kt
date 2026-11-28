package roc.win.lottery.app

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import roc.win.lottery.Platform
import roc.win.lottery.data.AppRelease
import roc.win.lottery.data.AppReleaseAsset
import roc.win.lottery.data.AppReleaseQueryResult
import roc.win.lottery.data.AppReleaseRepository
import roc.win.lottery.data.AppVersion
import roc.win.lottery.persistence.AppUpdatePreferences
import roc.win.lottery.persistence.AppUpdateStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** 版本更新检查：启动节流、开关、跳过版本、失败处理和安装包选择。 */
class AppUpdateCoordinatorTest {
    /** 首次启动立即检查；发现新版本时提示并记录检查时间。 */
    @Test
    fun launchCheckPromptsNewerRelease() =
        runTest {
            val repository = FakeReleaseRepository(release("1.3.9"))
            val store = InMemoryAppUpdateStore()
            val clock = MutableTestClock()
            val updates = coordinator(repository, store, clock)

            updates.checkOnLaunch()

            assertEquals(1, repository.requests)
            val update = assertNotNull(updates.state.value.prompt)
            assertEquals("1.3.9", update.version)
            assertIs<AppUpdateStatus.Available>(updates.state.value.status)
            assertEquals(clock.nowEpochMillis, store.preferences.lastCheckedAtEpochMillis)
            assertEquals(Instant.fromEpochMilliseconds(clock.nowEpochMillis), updates.state.value.lastCheckedAt)
        }

    /** 距上次成功检查不足 24 小时不联网，超过后再检查；系统时间回拨时视为到期。 */
    @Test
    fun launchCheckRespectsInterval() =
        runTest {
            val clock = MutableTestClock()
            val repository = FakeReleaseRepository(release("1.3.8"))
            val checkedAt = clock.nowEpochMillis
            val store = InMemoryAppUpdateStore(AppUpdatePreferences(lastCheckedAtEpochMillis = checkedAt))

            clock.nowEpochMillis = checkedAt + 23.hours.inWholeMilliseconds
            coordinator(repository, store, clock).checkOnLaunch()
            assertEquals(0, repository.requests)

            clock.nowEpochMillis = checkedAt + 25.hours.inWholeMilliseconds
            coordinator(repository, store, clock).checkOnLaunch()
            assertEquals(1, repository.requests)

            clock.nowEpochMillis = store.preferences.lastCheckedAtEpochMillis!! - 1.hours.inWholeMilliseconds
            coordinator(repository, store, clock).checkOnLaunch()
            assertEquals(2, repository.requests)
        }

    /** 关闭自动检查后启动不联网，手动检查照常；开关写回本机。 */
    @Test
    fun disabledAutoCheckOnlyChecksManually() =
        runTest {
            val repository = FakeReleaseRepository(release("1.3.9"))
            val store = InMemoryAppUpdateStore()
            val updates = coordinator(repository, store)

            updates.setAutoCheckEnabled(false)
            assertEquals(false, store.preferences.autoCheckEnabled)
            assertEquals(false, updates.state.value.autoCheckEnabled)
            updates.checkOnLaunch()
            assertEquals(0, repository.requests)

            updates.checkNow()
            assertEquals(1, repository.requests)
            assertNotNull(updates.state.value.prompt)
        }

    /** 跳过的版本不再自动提示但仍显示为可升级；手动检查和更高版本照常提示。 */
    @Test
    fun skippedVersionIsNotPromptedAutomatically() =
        runTest {
            val repository = FakeReleaseRepository(release("1.3.9"))
            val store = InMemoryAppUpdateStore()
            val clock = MutableTestClock()
            val first = coordinator(repository, store, clock)
            first.checkOnLaunch()
            first.skipPromptedVersion()
            assertNull(first.state.value.prompt)
            assertEquals("1.3.9", store.preferences.skippedVersion)

            clock.nowEpochMillis += 25.hours.inWholeMilliseconds
            val second = coordinator(repository, store, clock)
            second.checkOnLaunch()
            assertNull(second.state.value.prompt)
            assertIs<AppUpdateStatus.Available>(second.state.value.status)
            second.showAvailableUpdate()
            assertNotNull(second.state.value.prompt)
            second.dismissPrompt()

            second.checkNow()
            assertNotNull(second.state.value.prompt)

            repository.result = AppReleaseQueryResult.Success(release("1.4.0"))
            clock.nowEpochMillis += 25.hours.inWholeMilliseconds
            val third = coordinator(repository, store, clock)
            third.checkOnLaunch()
            assertEquals(
                "1.4.0",
                third.state.value.prompt
                    ?.version,
            )
        }

    /** 已是最新或当前版本更高时不提示。 */
    @Test
    fun upToDateDoesNotPrompt() =
        runTest {
            listOf("1.3.8", "1.3.7").forEach { latest ->
                val updates = coordinator(FakeReleaseRepository(release(latest)), InMemoryAppUpdateStore())
                updates.checkNow()
                assertEquals(AppUpdateStatus.UpToDate, updates.state.value.status)
                assertNull(updates.state.value.prompt)
            }
        }

    /** 自动检查失败不打扰也不记录检查时间；手动检查失败显示原因。 */
    @Test
    fun failuresAreSilentOnlyForAutoCheck() =
        runTest {
            val repository = FakeReleaseRepository(AppReleaseQueryResult.Failure("无法连接 GitHub，请检查网络后再试"))
            val store = InMemoryAppUpdateStore()
            val updates = coordinator(repository, store)

            updates.checkOnLaunch()
            assertEquals(AppUpdateStatus.NotChecked, updates.state.value.status)
            assertNull(store.preferences.lastCheckedAtEpochMillis)

            updates.checkNow()
            assertEquals(
                "无法连接 GitHub，请检查网络后再试",
                assertIs<AppUpdateStatus.Failed>(updates.state.value.status).message,
            )
        }

    /** 当前版本号无法解析时不联网比较，手动检查说明原因。 */
    @Test
    fun unknownCurrentVersionNeverQueries() =
        runTest {
            val repository = FakeReleaseRepository(release("1.3.9"))
            val updates = coordinator(repository, InMemoryAppUpdateStore(), version = "1.3.8-debug")
            updates.checkOnLaunch()
            updates.checkNow()
            assertEquals(0, repository.requests)
            assertIs<AppUpdateStatus.Failed>(updates.state.value.status)
        }

    /** 有对应安装包的平台直接下载安装包，iOS 和没有安装包的平台打开发布页。 */
    @Test
    fun picksInstallerForDistribution() =
        runTest {
            val release = release("1.3.9")
            val expected =
                mapOf(
                    AppDistribution.ANDROID to "WinLottery-v1.3.9-android-arm64-v8a.apk",
                    AppDistribution.MACOS_ARM64 to "WinLottery-v1.3.9-macos-arm64.dmg",
                    AppDistribution.WINDOWS_X64 to "WinLottery-v1.3.9-windows-x64.msi",
                    AppDistribution.IOS to null,
                    AppDistribution.OTHER to null,
                )
            expected.forEach { (distribution, installer) ->
                val updates =
                    coordinator(FakeReleaseRepository(release), InMemoryAppUpdateStore(), distribution = distribution)
                updates.checkNow()
                val update = assertNotNull(updates.state.value.prompt)
                assertEquals(installer, update.installerName, distribution.name)
                assertEquals(
                    installer?.let { "$DOWNLOAD_BASE/v1.3.9/$it" } ?: release.pageUrl,
                    update.downloadUrl,
                    distribution.name,
                )
            }
            val withoutMsi = release.copy(assets = release.assets.filterNot { it.name.endsWith(".msi") })
            val windows =
                coordinator(
                    FakeReleaseRepository(withoutMsi),
                    InMemoryAppUpdateStore(),
                    distribution = AppDistribution.WINDOWS_X64,
                )
            windows.checkNow()
            assertEquals(
                release.pageUrl,
                windows.state.value.prompt
                    ?.downloadUrl,
            )
        }

    /** 本机偏好读写失败时本次运行照常检查和切换开关。 */
    @Test
    fun storeFailuresDoNotBreakChecks() =
        runTest {
            val repository = FakeReleaseRepository(release("1.3.9"))
            val updates = coordinator(repository, FailingAppUpdateStore)
            updates.checkOnLaunch()
            assertNotNull(updates.state.value.prompt)
            updates.setAutoCheckEnabled(false)
            assertEquals(false, updates.state.value.autoCheckEnabled)
            updates.skipPromptedVersion()
            assertNull(updates.state.value.prompt)
        }

    /** 会话创建即检查一次；演示容器没有发布来源，不显示也不检查更新。 */
    @Test
    fun sessionChecksOnceOnStart() =
        runTest {
            val repository = FakeReleaseRepository(release("1.3.9"))
            val session = LotteryAppSession(container(repository), this)
            advanceUntilIdle()
            assertEquals(1, repository.requests)
            assertNotNull(
                session.controller.appUpdates
                    ?.state
                    ?.value
                    ?.prompt,
            )

            val demo = LotteryAppSession(AppContainer.createDemo(TestPlatform), this)
            assertNull(demo.controller.appUpdates)
        }

    /** 发布说明去掉 Markdown 标记和通用安装说明。 */
    @Test
    fun releaseNotesAreSimplified() {
        val markdown =
            """
            ## 本版更新

            ### 新功能

            - **AI 分析结果保存在本机**：大乐透、双色球各保存最近一次结果。
            * 记录页显示 `税前奖金`。



            ### 升级说明

            - 从 1.3.7 覆盖安装。

            ## 安装说明

            Android 提供签名 APK。
            """.trimIndent()
        assertEquals(
            "新功能\n\n• AI 分析结果保存在本机：大乐透、双色球各保存最近一次结果。\n• 记录页显示 税前奖金。\n\n" +
                "升级说明\n\n• 从 1.3.7 覆盖安装。",
            releaseNotesForDisplay(markdown),
        )
        assertEquals("", releaseNotesForDisplay(""))
    }

    /** 创建使用指定依赖的更新协调器。 */
    private fun coordinator(
        repository: AppReleaseRepository,
        store: AppUpdateStore?,
        clock: MutableTestClock = MutableTestClock(),
        version: String = "1.3.8",
        distribution: AppDistribution = AppDistribution.ANDROID,
    ): AppUpdateCoordinator =
        AppUpdateCoordinator(
            buildInfo = AppBuildInfo(version = version, distribution = distribution),
            repository = repository,
            store = store,
            clock = clock,
        )

    /** 在演示容器基础上接入更新检查。 */
    private fun container(repository: AppReleaseRepository): AppContainer {
        val demo = AppContainer.createDemo(TestPlatform)
        return AppContainer(
            platform = demo.platform,
            imageAcquirer = demo.imageAcquirer,
            imageQualityAnalyzer = demo.imageQualityAnalyzer,
            ticketRecognizer = demo.ticketRecognizer,
            ticketParser = demo.ticketParser,
            drawRepository = demo.drawRepository,
            historicalDrawRepository = null,
            prizeCalculator = demo.prizeCalculator,
            appPaths = demo.appPaths,
            ticketValidator = demo.ticketValidator,
            isDemo = true,
            usesRealImageAcquisition = false,
            usesRealRecognition = false,
            usesRealDrawData = false,
            appBuildInfo = AppBuildInfo(version = "1.3.8", distribution = AppDistribution.ANDROID),
            appReleaseRepository = repository,
            appUpdateStore = InMemoryAppUpdateStore(),
        )
    }

    /** 构造与发版工作流命名一致的发布版。 */
    private fun release(version: String): AppRelease {
        val tag = "v$version"
        return AppRelease(
            version = checkNotNull(AppVersion.parse(tag)),
            tagName = tag,
            title = "WinLottery $version",
            notes = "## 本版更新\n\n- 新功能\n\n## 安装说明\n\n安装方法",
            publishedAt = Instant.parse("2026-11-30T04:22:42Z"),
            pageUrl = "https://github.com/Roc-egg/WinLottery/releases/tag/$tag",
            assets =
                listOf(
                    "android-arm64-v8a.apk",
                    "ios-arm64-unsigned.ipa",
                    "macos-arm64.dmg",
                    "windows-x64.msi",
                ).map { suffix ->
                    val name = "WinLottery-$tag-$suffix"
                    AppReleaseAsset(name = name, downloadUrl = "$DOWNLOAD_BASE/$tag/$name", sizeBytes = 1_000L)
                },
        )
    }

    /**
     * 记录请求次数、返回固定结果的发布来源。
     *
     * @property result 每次查询返回的结果。
     */
    private class FakeReleaseRepository(
        var result: AppReleaseQueryResult,
    ) : AppReleaseRepository {
        /** 用发布版构造成功结果。 */
        constructor(release: AppRelease) : this(AppReleaseQueryResult.Success(release))

        /** 已发生的查询次数。 */
        var requests = 0

        override suspend fun latestRelease(): AppReleaseQueryResult {
            requests += 1
            return result
        }
    }

    /**
     * 内存版更新偏好仓库。
     *
     * @property preferences 当前保存的偏好。
     */
    private class InMemoryAppUpdateStore(
        var preferences: AppUpdatePreferences = AppUpdatePreferences(),
    ) : AppUpdateStore {
        override fun load(): AppUpdatePreferences = preferences

        override suspend fun save(preferences: AppUpdatePreferences) {
            this.preferences = preferences
        }
    }

    /** 读写都失败的更新偏好仓库。 */
    private object FailingAppUpdateStore : AppUpdateStore {
        override fun load(): AppUpdatePreferences = error("读取失败")

        override suspend fun save(preferences: AppUpdatePreferences): Unit = error("写入失败")
    }

    /** 不提供相机的测试平台。 */
    private object TestPlatform : Platform {
        /** 测试平台名称。 */
        override val name: String = "测试平台"

        /** 测试平台不提供相机。 */
        override val supportsCamera: Boolean = false
    }

    /** 测试常量。 */
    private companion object {
        /** 发布版安装包下载地址前缀。 */
        const val DOWNLOAD_BASE = "https://github.com/Roc-egg/WinLottery/releases/download"
    }
}

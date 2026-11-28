package roc.win.lottery.app.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import roc.win.lottery.app.AppDistribution
import roc.win.lottery.app.AppScreen
import roc.win.lottery.app.AppUpdateInfo
import roc.win.lottery.app.AppUpdateState
import roc.win.lottery.app.AppUpdateStatus
import roc.win.lottery.app.theme.LotteryTheme
import roc.win.lottery.persistence.ThemePreference
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

/** 关于页的版本更新分区和新版本提示弹窗。 */
@OptIn(ExperimentalComposeUiApi::class)
class AppUpdateRenderTest {
    /** 关于页显示当前版本、自动检查开关、检查结果和隐私说明；未接入更新检查时不显示分区。 */
    @Test
    fun aboutScreenShowsUpdateSection() {
        listOf(false, true).forEach { dark ->
            val texts =
                render("about-${if (dark) "dark" else "light"}", 780, 3400, dark) {
                    AboutScreen(
                        screen = AppScreen.About(),
                        themePreference = ThemePreference.SYSTEM,
                        onThemePreferenceChange = {},
                        onBack = {},
                        onClearHistory = {},
                        appUpdate = state(AppUpdateStatus.Available(update)),
                    )
                }
            listOf(
                "版本 1.3.8",
                "版本更新",
                "启动时自动检查",
                "发现新版本 1.3.9，当前版本 1.3.8",
                "查看新版本 1.3.9",
                "检查更新",
            ).forEach { text -> assertTrue(texts.any { it == text }, "缺少：$text") }
            assertTrue(texts.any { it.startsWith("检查更新时只向 GitHub 读取") }, "缺少隐私说明")
        }

        val withoutUpdates =
            render("about-without-updates", 780, 3400, false) {
                AboutScreen(
                    screen = AppScreen.About(),
                    themePreference = ThemePreference.SYSTEM,
                    onThemePreferenceChange = {},
                    onBack = {},
                    onClearHistory = {},
                )
            }
        assertTrue(withoutUpdates.none { it == "版本更新" || it.startsWith("版本 ") })
    }

    /** 检查中、已是最新和失败分别给出对应说明。 */
    @Test
    fun aboutScreenShowsCheckResults() {
        mapOf(
            AppUpdateStatus.Checking to "正在查询 GitHub 上的最新发布版…",
            AppUpdateStatus.UpToDate to "已是最新版本 1.3.8，检查于 2026-11-27 18:30",
            AppUpdateStatus.Failed("无法连接 GitHub，请检查网络后再试") to "无法连接 GitHub，请检查网络后再试",
            AppUpdateStatus.NotChecked to "当前版本 1.3.8，上次检查于 2026-11-27 18:30",
        ).entries.forEachIndexed { index, (status, expected) ->
            val texts =
                render("about-status-$index", 780, 3400, false) {
                    AboutScreen(
                        screen = AppScreen.About(),
                        themePreference = ThemePreference.SYSTEM,
                        onThemePreferenceChange = {},
                        onBack = {},
                        onClearHistory = {},
                        appUpdate = state(status),
                    )
                }
            assertTrue(texts.any { it == expected }, "缺少：$expected")
        }
    }

    /** 弹窗显示版本、发布日期、更新内容和下载说明；没有对应安装包时改为前往发布页。 */
    @Test
    fun dialogShowsReleaseNotesAndDownloadHint() {
        listOf(false, true).forEach { dark ->
            val texts =
                render("dialog-${if (dark) "dark" else "light"}", 780, 1688, dark) {
                    AppUpdateDialog(
                        update = update,
                        currentVersion = "1.3.8",
                        onOpened = {},
                        onSkip = {},
                        onDismiss = {},
                    )
                }
            listOf(
                "发现新版本 1.3.9",
                "当前版本 1.3.8 · 发布于 2026-11-30",
                "将用浏览器下载 APK 安装包（62.6 MB），下载完成后打开即可覆盖安装。",
                "下载新版",
                "跳过此版本",
                "以后再说",
            ).forEach { text -> assertTrue(texts.any { it == text }, "缺少：$text") }
            assertTrue(texts.any { it.startsWith("新功能") }, "缺少更新内容")
        }

        val ios =
            update.copy(
                distribution = AppDistribution.IOS,
                downloadUrl = update.releasePageUrl,
                installerName = null,
                installerSizeBytes = null,
            )
        val texts =
            render("dialog-ios", 780, 1688, false) {
                AppUpdateDialog(update = ios, currentVersion = "1.3.8", onOpened = {}, onSkip = {}, onDismiss = {})
            }
        assertTrue(texts.any { it == "前往发布页" })
        assertTrue(texts.any { it.startsWith("将打开 GitHub 发布页。iOS 安装包未签名") })
    }

    /** 离屏渲染内容，保存截图并返回全部可见文字。 */
    private fun render(
        name: String,
        width: Int,
        height: Int,
        dark: Boolean,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ): List<String> =
        ImageComposeScene(width, height, Density(2f)).use { scene ->
            scene.setContent { LotteryTheme(darkTheme = dark) { content() } }
            var time = 0L
            repeat(30) {
                time += 20_000_000L
                scene.render(time).close()
            }
            val file = File("build/reports/app-update/$name.png")
            file.parentFile.mkdirs()
            scene.render(time).use { image -> file.writeBytes(checkNotNull(image.encodeToData()).bytes) }
            scene.texts()
        }

    /** 收集场景中全部文字节点。 */
    private fun ImageComposeScene.texts(): List<String> {
        val result = mutableListOf<String>()

        fun visit(node: SemanticsNode) {
            node.config
                .getOrNull(SemanticsProperties.Text)
                ?.forEach { result += it.text }
            node.children.forEach(::visit)
        }
        semanticsOwners.forEach { owner -> visit(owner.unmergedRootSemanticsNode) }
        return result
    }

    /** 构造指定检查结果的更新状态。 */
    private fun state(status: AppUpdateStatus): AppUpdateState =
        AppUpdateState(
            currentVersion = "1.3.8",
            autoCheckEnabled = true,
            lastCheckedAt = Instant.parse("2026-11-27T10:30:00Z"),
            status = status,
            prompt = null,
        )

    /** 测试使用的 Android 新版本。 */
    private val update =
        AppUpdateInfo(
            version = "1.3.9",
            notes =
                "新功能\n\n• AI 分析结果保存在本机：大乐透、双色球各保存最近一次分析结果。\n" +
                    "• 记录页直接看核对结果。\n\n升级说明\n\n• 从 1.3.8 覆盖安装，本机数据完整保留。",
            publishedAt = Instant.parse("2026-11-30T04:22:42Z"),
            downloadUrl =
                "https://github.com/Roc-egg/WinLottery/releases/download/v1.3.9/WinLottery-v1.3.9-android-arm64-v8a.apk",
            releasePageUrl = "https://github.com/Roc-egg/WinLottery/releases/tag/v1.3.9",
            installerName = "WinLottery-v1.3.9-android-arm64-v8a.apk",
            installerSizeBytes = 65_639_224L,
            distribution = AppDistribution.ANDROID,
        )
}

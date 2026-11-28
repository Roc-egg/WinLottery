package roc.win.lottery.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import roc.win.lottery.data.AppRelease
import roc.win.lottery.data.AppReleaseQueryResult
import roc.win.lottery.data.AppReleaseRepository
import roc.win.lottery.data.AppVersion
import roc.win.lottery.persistence.AppUpdatePreferences
import roc.win.lottery.persistence.AppUpdateStore
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * 当前安装包的发布渠道，决定新版本优先下载哪个安装包。
 *
 * @property assetSuffix 发布版中对应安装包文件名的结尾；为 `null` 时打开发布页。
 */
enum class AppDistribution(
    val assetSuffix: String?,
) {
    /** Android arm64-v8a 签名 APK。 */
    ANDROID("-android-arm64-v8a.apk"),

    /** iOS 未签名 IPA，需要用户自行重签，因此打开发布页阅读安装说明。 */
    IOS(null),

    /** Apple Silicon macOS DMG。 */
    MACOS_ARM64("-macos-arm64.dmg"),

    /** Windows x64 MSI。 */
    WINDOWS_X64("-windows-x64.msi"),

    /** 没有对应安装包的其他平台，例如 Intel Mac 或 Linux。 */
    OTHER(null),
}

/**
 * 当前运行的安装包信息。
 *
 * @property version 面向用户的版本号，例如 `1.3.8`。
 * @property distribution 当前安装包的发布渠道。
 */
data class AppBuildInfo(
    val version: String,
    val distribution: AppDistribution,
)

/**
 * 可以升级到的新版本。
 *
 * @property version 新版本号。
 * @property notes 去掉 Markdown 标记、只保留本版更新内容的说明。
 * @property publishedAt 发布时间；未知时为 `null`。
 * @property downloadUrl 优先打开的地址：找到对应安装包时为安装包下载地址，否则为发布页。
 * @property releasePageUrl 发布页地址。
 * @property installerName 对应安装包文件名；没有对应安装包时为 `null`。
 * @property installerSizeBytes 对应安装包字节数；未知时为 `null`。
 * @property distribution 当前安装包的发布渠道。
 */
data class AppUpdateInfo(
    val version: String,
    val notes: String,
    val publishedAt: Instant?,
    val downloadUrl: String,
    val releasePageUrl: String,
    val installerName: String?,
    val installerSizeBytes: Long?,
    val distribution: AppDistribution,
)

/** 最近一次版本检查的结果。 */
sealed interface AppUpdateStatus {
    /** 本次运行还没有检查过。 */
    data object NotChecked : AppUpdateStatus

    /** 正在向 GitHub 查询最新发布版。 */
    data object Checking : AppUpdateStatus

    /** 当前已是最新版本。 */
    data object UpToDate : AppUpdateStatus

    /**
     * 有可升级的新版本。
     *
     * @property update 新版本信息。
     */
    data class Available(
        val update: AppUpdateInfo,
    ) : AppUpdateStatus

    /**
     * 手动检查失败。
     *
     * @property message 面向用户的说明。
     */
    data class Failed(
        val message: String,
    ) : AppUpdateStatus
}

/**
 * 关于页和更新提示使用的版本更新状态。
 *
 * @property currentVersion 当前安装包版本号。
 * @property autoCheckEnabled 启动时是否自动检查。
 * @property lastCheckedAt 最近一次成功取得发布信息的时间；从未成功时为 `null`。
 * @property status 最近一次检查结果。
 * @property prompt 需要弹窗提示的新版本；为 `null` 时不提示。
 */
data class AppUpdateState(
    val currentVersion: String,
    val autoCheckEnabled: Boolean,
    val lastCheckedAt: Instant?,
    val status: AppUpdateStatus,
    val prompt: AppUpdateInfo?,
)

/**
 * 根据 GitHub 最新正式发布版判断是否有新版本。
 *
 * 启动时只在开启自动检查且距上次成功检查超过 [AUTO_CHECK_INTERVAL] 时联网一次，自动检查失败不打扰用户；
 * 用户跳过的版本不再自动提示，手动检查总是显示结果。偏好读写失败时本次运行照常工作。
 *
 * @property buildInfo 当前安装包信息。
 * @property repository 最新发布版来源。
 * @property store 本机偏好仓库；为 `null` 时设置只在本次运行有效。
 * @property clock 判断检查间隔的时钟。
 */
class AppUpdateCoordinator(
    private val buildInfo: AppBuildInfo,
    private val repository: AppReleaseRepository,
    private val store: AppUpdateStore?,
    private val clock: Clock = Clock.System,
) {
    /** 当前安装包版本；无法解析时不比较、不提示。 */
    private val currentVersion = AppVersion.parse(buildInfo.version)

    /** 最近一次读取或写入的偏好。 */
    private var preferences: AppUpdatePreferences =
        store?.let { runCatching { it.load() }.getOrNull() } ?: AppUpdatePreferences()

    /** 内部可变状态。 */
    private val mutableState =
        MutableStateFlow(
            AppUpdateState(
                currentVersion = buildInfo.version,
                autoCheckEnabled = preferences.autoCheckEnabled,
                lastCheckedAt = preferences.lastCheckedAtEpochMillis?.let(Instant::fromEpochMilliseconds),
                status = AppUpdateStatus.NotChecked,
                prompt = null,
            ),
        )

    /** 界面读取的版本更新状态。 */
    val state: StateFlow<AppUpdateState> = mutableState.asStateFlow()

    /** 保证同一时刻只有一次检查。 */
    private val checkMutex = Mutex()

    /** 串行写入偏好，保证最后写入的是最新内容。 */
    private val saveMutex = Mutex()

    /** 应用启动时调用：开启自动检查且已到检查间隔时静默检查一次。 */
    suspend fun checkOnLaunch() {
        if (!preferences.autoCheckEnabled) return
        val last = preferences.lastCheckedAtEpochMillis
        val elapsed = last?.let { clock.now() - Instant.fromEpochMilliseconds(it) }
        // 系统时间被调回过去时上次检查时间会晚于现在，此时视为已到期。
        if (elapsed != null && !elapsed.isNegative() && elapsed < AUTO_CHECK_INTERVAL) return
        check(manual = false)
    }

    /** 用户在关于页手动检查更新。 */
    suspend fun checkNow() {
        check(manual = true)
    }

    /**
     * 开启或关闭启动时自动检查。
     *
     * @param enabled 是否开启。
     */
    suspend fun setAutoCheckEnabled(enabled: Boolean) {
        if (preferences.autoCheckEnabled == enabled) return
        mutableState.update { it.copy(autoCheckEnabled = enabled) }
        savePreferences { it.copy(autoCheckEnabled = enabled) }
    }

    /** 重新弹出已发现的新版本提示。 */
    fun showAvailableUpdate() {
        val update = (mutableState.value.status as? AppUpdateStatus.Available)?.update ?: return
        mutableState.update { it.copy(prompt = update) }
    }

    /** 暂时关闭新版本提示，下次启动到期后仍会提示。 */
    fun dismissPrompt() {
        mutableState.update { it.copy(prompt = null) }
    }

    /** 跳过当前提示的版本，之后的自动检查不再提示该版本。 */
    suspend fun skipPromptedVersion() {
        val update = mutableState.value.prompt ?: return
        mutableState.update { it.copy(prompt = null) }
        savePreferences { it.copy(skippedVersion = update.version) }
    }

    /** 执行一次检查；已有检查进行中时直接返回。 */
    private suspend fun check(manual: Boolean) {
        if (!checkMutex.tryLock()) return
        val previousStatus = mutableState.value.status
        try {
            val current = currentVersion
            if (current == null) {
                if (manual) {
                    mutableState.update { it.copy(status = AppUpdateStatus.Failed("无法确定当前版本号，暂不能检查更新")) }
                }
                return
            }
            mutableState.update { it.copy(status = AppUpdateStatus.Checking) }
            when (val result = repository.latestRelease()) {
                is AppReleaseQueryResult.Failure -> {
                    mutableState.update {
                        it.copy(status = if (manual) AppUpdateStatus.Failed(result.message) else previousStatus)
                    }
                }

                is AppReleaseQueryResult.Success -> {
                    val checkedAt = clock.now()
                    val release = result.release
                    if (release.version > current) {
                        val update = release.toUpdateInfo(buildInfo.distribution)
                        val prompts = manual || preferences.skippedVersion != update.version
                        mutableState.update {
                            it.copy(
                                lastCheckedAt = checkedAt,
                                status = AppUpdateStatus.Available(update),
                                prompt = if (prompts) update else it.prompt,
                            )
                        }
                    } else {
                        mutableState.update {
                            it.copy(lastCheckedAt = checkedAt, status = AppUpdateStatus.UpToDate, prompt = null)
                        }
                    }
                    savePreferences { it.copy(lastCheckedAtEpochMillis = checkedAt.toEpochMilliseconds()) }
                }
            }
        } catch (cancelled: CancellationException) {
            mutableState.update { it.copy(status = previousStatus) }
            throw cancelled
        } finally {
            checkMutex.unlock()
        }
    }

    /** 修改并保存偏好；写入失败时本次运行仍按新值工作。 */
    private suspend fun savePreferences(change: (AppUpdatePreferences) -> AppUpdatePreferences) {
        saveMutex.withLock {
            preferences = change(preferences)
            val target = store ?: return
            runCatching { target.save(preferences) }
                .onFailure { error -> if (error is CancellationException) throw error }
        }
    }

    /** 检查间隔。 */
    companion object {
        /** 自动检查的最短间隔。 */
        val AUTO_CHECK_INTERVAL = 24.hours
    }
}

/** 把发布版转换为当前平台的新版本信息。 */
private fun AppRelease.toUpdateInfo(distribution: AppDistribution): AppUpdateInfo {
    val installer =
        distribution.assetSuffix?.let { suffix -> assets.firstOrNull { it.name.endsWith(suffix, ignoreCase = true) } }
    return AppUpdateInfo(
        version = version.toString(),
        notes = releaseNotesForDisplay(notes),
        publishedAt = publishedAt,
        downloadUrl = installer?.downloadUrl ?: pageUrl,
        releasePageUrl = pageUrl,
        installerName = installer?.name,
        installerSizeBytes = installer?.sizeBytes,
        distribution = distribution,
    )
}

/**
 * 把发布说明 Markdown 转成弹窗里的纯文本。
 *
 * 发版工作流会在 `docs/release-notes` 内容后追加通用的“安装说明”，弹窗只保留其前面的本版内容；
 * 去掉标题井号、加粗星号和行内代码反引号，列表项改用圆点。
 *
 * @param markdown 发布说明原文。
 * @return 适合直接显示的文本；没有内容时为空字符串。
 */
internal fun releaseNotesForDisplay(markdown: String): String {
    val lines = mutableListOf<String>()
    for (rawLine in markdown.replace("\r\n", "\n").lines()) {
        val line = rawLine.trimEnd()
        val heading = line.trimStart()
        if (heading.startsWith("#")) {
            val title = heading.trimStart('#').trim()
            if (title == INSTALL_SECTION_TITLE) break
            if (title == WHATS_NEW_SECTION_TITLE) continue
            lines += title
            continue
        }
        val bullet = line.trimStart()
        val text =
            if (bullet.startsWith("- ") || bullet.startsWith("* ")) {
                "• " + bullet.substring(2)
            } else {
                line
            }
        lines += text.replace("**", "").replace("`", "")
    }
    return lines
        .joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

/** 发版工作流追加的通用安装说明标题。 */
private const val INSTALL_SECTION_TITLE = "安装说明"

/** 发布说明顶层标题，弹窗已有标题，不再重复。 */
private const val WHATS_NEW_SECTION_TITLE = "本版更新"

package roc.win.lottery

import roc.win.lottery.app.AppBuildInfo
import roc.win.lottery.app.AppContainer
import roc.win.lottery.app.AppDistribution
import roc.win.lottery.app.DesktopTicketRecordFileExchange
import roc.win.lottery.data.GitHubAppReleaseRepository
import roc.win.lottery.data.HttpAiAnalysisProvider
import roc.win.lottery.data.JvmSuperLottoPdfTextExtractor
import roc.win.lottery.data.OfficialDrawRepository
import roc.win.lottery.domain.LotteryPrizeCalculator
import roc.win.lottery.domain.TicketValidator
import roc.win.lottery.persistence.createJvmAiConnectionStore
import roc.win.lottery.persistence.createJvmAppUpdateStore
import roc.win.lottery.persistence.createJvmAppearanceStore
import roc.win.lottery.persistence.createJvmLocalStorage
import roc.win.lottery.recognition.ConservativeTicketParser
import roc.win.lottery.recognition.DesktopAppPaths
import roc.win.lottery.recognition.DesktopFileImageAcquirer
import roc.win.lottery.recognition.DesktopLuminanceImageDecoder
import roc.win.lottery.recognition.DesktopPpOcrTicketRecognizer
import roc.win.lottery.recognition.ImageDimensionQualityAnalyzer
import roc.win.lottery.recognition.ImageQualityAnalyzerChain
import roc.win.lottery.recognition.PixelImageQualityAnalyzer
import java.awt.Frame
import java.io.File

/** Windows 或 macOS 桌面平台能力。 */
class JVMPlatform : Platform {
    /** 当前桌面操作系统名称。 */
    override val name: String = System.getProperty("os.name") ?: "Desktop"

    /** 后续桌面版本当前只保留系统图片导入能力。 */
    override val supportsCamera: Boolean = false
}

/** 返回桌面 JVM 平台能力。 */
actual fun getPlatform(): Platform = JVMPlatform()

/**
 * 创建复用共享业务能力的 Windows/macOS 桌面应用容器。
 *
 * @param ownerProvider 返回系统文件选择器使用的当前桌面窗口。
 * @param applicationCommand 当前桌面应用的完整启动命令，用于创建隔离 OCR 子进程。
 * @return 使用真实系统文件接口、官网数据、AI、Room 记录和本地规则的桌面容器。
 */
fun createDesktopAppContainer(
    ownerProvider: () -> Frame?,
    applicationCommand: List<String>,
): AppContainer =
    createDesktopAppContainer(
        ownerProvider = ownerProvider,
        applicationCommand = applicationCommand,
        applicationDataDirectory = resolveDesktopApplicationDataDirectory(),
    )

/** 使用可注入应用数据目录创建桌面容器，供 JVM 隔离测试复用。 */
internal fun createDesktopAppContainer(
    ownerProvider: () -> Frame?,
    applicationCommand: List<String>,
    applicationDataDirectory: File,
): AppContainer {
    val appPaths = DesktopAppPaths()
    val officialDrawRepository =
        OfficialDrawRepository(
            superLottoPdfTextExtractor = JvmSuperLottoPdfTextExtractor(),
        )
    val localStorage = createJvmLocalStorage(applicationDataDirectory)
    val buildInfo = resolveDesktopBuildInfo()
    return AppContainer(
        platform = JVMPlatform(),
        imageAcquirer = DesktopFileImageAcquirer(ownerProvider, appPaths),
        imageQualityAnalyzer =
            ImageQualityAnalyzerChain(
                listOf(
                    ImageDimensionQualityAnalyzer(),
                    PixelImageQualityAnalyzer(DesktopLuminanceImageDecoder()),
                ),
            ),
        ticketRecognizer = DesktopPpOcrTicketRecognizer(applicationCommand),
        ticketParser = ConservativeTicketParser(),
        drawRepository = officialDrawRepository,
        historicalDrawRepository = officialDrawRepository,
        aiAnalysisProvider = HttpAiAnalysisProvider(),
        aiConnectionStore = createJvmAiConnectionStore(applicationDataDirectory),
        appearanceStore = createJvmAppearanceStore(applicationDataDirectory),
        prizeCalculator = LotteryPrizeCalculator(),
        appPaths = appPaths,
        ticketValidator = TicketValidator(),
        isDemo = true,
        usesRealImageAcquisition = true,
        usesRealRecognition = true,
        usesRealDrawData = true,
        ticketRecordStore = localStorage.ticketRecordStore,
        historicalDrawStore = localStorage.historicalDrawStore,
        drawResultStore = localStorage.drawResultStore,
        aiAnalysisRecordStore = localStorage.aiAnalysisRecordStore,
        ticketRecordFileExchange = DesktopTicketRecordFileExchange(ownerProvider),
        appBuildInfo = buildInfo,
        appReleaseRepository = buildInfo?.let { GitHubAppReleaseRepository() },
        appUpdateStore = createJvmAppUpdateStore(applicationDataDirectory),
    )
}

/** 读取构建脚本注入的版本号并按系统与架构确定安装包；未注入时（例如测试）不检查更新。 */
private fun resolveDesktopBuildInfo(): AppBuildInfo? {
    val version =
        (System.getProperty(APP_VERSION_PROPERTY) ?: System.getProperty(JPACKAGE_APP_VERSION_PROPERTY))
            ?.takeIf(String::isNotBlank)
            ?: return null
    return AppBuildInfo(
        version = version,
        distribution =
            resolveDesktopDistribution(
                osName = System.getProperty(OS_NAME_PROPERTY).orEmpty(),
                osArch = System.getProperty(OS_ARCH_PROPERTY).orEmpty(),
            ),
    )
}

/** 按操作系统和处理器架构匹配发布版中的桌面安装包。 */
internal fun resolveDesktopDistribution(
    osName: String,
    osArch: String,
): AppDistribution {
    val arch = osArch.lowercase()
    return when {
        osName.startsWith(MAC_OS_PREFIX, ignoreCase = true) && arch in ARM64_ARCHITECTURES -> {
            AppDistribution.MACOS_ARM64
        }

        osName.startsWith(WINDOWS_OS_PREFIX, ignoreCase = true) && arch in X64_ARCHITECTURES -> {
            AppDistribution.WINDOWS_X64
        }

        else -> {
            AppDistribution.OTHER
        }
    }
}

/** 按当前桌面系统约定定位只供本应用使用的持久化数据目录。 */
private fun resolveDesktopApplicationDataDirectory(): File {
    val userHome =
        System
            .getProperty(USER_HOME_PROPERTY)
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?: error("无法定位当前用户目录")
    val osName = System.getProperty(OS_NAME_PROPERTY).orEmpty()
    return resolveDesktopApplicationDataDirectory(
        osName = osName,
        userHome = userHome,
        environment = ::environmentDirectory,
    )
}

/** 按显式平台信息解析应用数据目录，供 Windows、macOS 和 Unix 路径测试复用。 */
internal fun resolveDesktopApplicationDataDirectory(
    osName: String,
    userHome: File,
    environment: (String) -> File?,
): File {
    val dataRoot =
        when {
            osName.startsWith(MAC_OS_PREFIX, ignoreCase = true) -> {
                File(userHome, MAC_OS_APPLICATION_SUPPORT_DIRECTORY)
            }

            osName.startsWith(WINDOWS_OS_PREFIX, ignoreCase = true) -> {
                environment(WINDOWS_LOCAL_APP_DATA_ENVIRONMENT)
                    ?: environment(WINDOWS_APP_DATA_ENVIRONMENT)
                    ?: userHome
            }

            else -> {
                environment(XDG_DATA_HOME_ENVIRONMENT)
                    ?: File(userHome, UNIX_LOCAL_SHARE_DIRECTORY)
            }
        }
    return File(dataRoot, APPLICATION_DATA_DIRECTORY_NAME)
}

/** 读取非空环境目录，权限受限或变量缺失时返回空。 */
private fun environmentDirectory(name: String): File? =
    runCatching { System.getenv(name) }
        .getOrNull()
        ?.takeIf(String::isNotBlank)
        ?.let(::File)

/** 桌面应用数据目录常量。 */
private const val APPLICATION_DATA_DIRECTORY_NAME = "roc.win.lottery"

/** Java 当前用户目录属性名。 */
private const val USER_HOME_PROPERTY = "user.home"

/** Java 当前操作系统属性名。 */
private const val OS_NAME_PROPERTY = "os.name"

/** Java 当前处理器架构属性名。 */
private const val OS_ARCH_PROPERTY = "os.arch"

/** desktopApp 构建脚本通过 JVM 参数注入的应用版本号属性名。 */
private const val APP_VERSION_PROPERTY = "roc.win.lottery.appVersion"

/** jpackage 启动器自动设置的应用版本号属性名，作为后备。 */
private const val JPACKAGE_APP_VERSION_PROPERTY = "jpackage.app-version"

/** JVM 报告的 64 位 ARM 架构名称。 */
private val ARM64_ARCHITECTURES = setOf("aarch64", "arm64")

/** JVM 报告的 x64 架构名称。 */
private val X64_ARCHITECTURES = setOf("amd64", "x86_64")

/** macOS 系统名称前缀。 */
private const val MAC_OS_PREFIX = "Mac"

/** Windows 系统名称前缀。 */
private const val WINDOWS_OS_PREFIX = "Windows"

/** macOS 用户级应用支持目录。 */
private const val MAC_OS_APPLICATION_SUPPORT_DIRECTORY = "Library/Application Support"

/** Windows 用户级本地应用数据环境变量。 */
private const val WINDOWS_LOCAL_APP_DATA_ENVIRONMENT = "LOCALAPPDATA"

/** Windows 用户级漫游应用数据环境变量。 */
private const val WINDOWS_APP_DATA_ENVIRONMENT = "APPDATA"

/** Linux 和其他 Unix 桌面的用户数据目录环境变量。 */
private const val XDG_DATA_HOME_ENVIRONMENT = "XDG_DATA_HOME"

/** 未配置 XDG 时使用的 Unix 用户数据目录。 */
private const val UNIX_LOCAL_SHARE_DIRECTORY = ".local/share"

package roc.win.lottery

import roc.win.lottery.app.AppContainer
import roc.win.lottery.data.FakeDrawRepository
import roc.win.lottery.domain.TicketValidator
import roc.win.lottery.recognition.ConservativeTicketParser
import roc.win.lottery.recognition.DesktopAppPaths
import roc.win.lottery.recognition.DesktopFileImageAcquirer
import roc.win.lottery.recognition.DesktopOcrUnavailableRecognizer
import roc.win.lottery.recognition.ImageDimensionQualityAnalyzer
import java.awt.Frame

/** Windows 或 macOS 桌面平台能力。 */
class JVMPlatform : Platform {
    /** 当前桌面操作系统名称。 */
    override val name: String = System.getProperty("os.name") ?: "Desktop"

    /** 桌面端 V1 只支持系统图片导入。 */
    override val supportsCamera: Boolean = false
}

/** 返回桌面 JVM 平台能力。 */
actual fun getPlatform(): Platform = JVMPlatform()

/**
 * 创建已接入 Windows/macOS 文件导入、但尚未接入桌面 OCR 的 B3 容器。
 *
 * @param ownerProvider 返回系统文件选择器使用的当前桌面窗口。
 * @return 使用真实本地图片副本、明确 OCR 阻断和 Fake 开奖仓库的应用容器。
 */
fun createDesktopImportContainer(ownerProvider: () -> Frame?): AppContainer {
    val appPaths = DesktopAppPaths()
    return AppContainer(
        platform = JVMPlatform(),
        imageAcquirer = DesktopFileImageAcquirer(ownerProvider, appPaths),
        imageQualityAnalyzer = ImageDimensionQualityAnalyzer(),
        ticketRecognizer = DesktopOcrUnavailableRecognizer(),
        ticketParser = ConservativeTicketParser(),
        drawRepository = FakeDrawRepository(),
        appPaths = appPaths,
        ticketValidator = TicketValidator(),
        isDemo = true,
        usesRealImageAcquisition = true,
        usesRealRecognition = false,
    )
}

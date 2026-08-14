package roc.win.lottery

import platform.UIKit.UIDevice
import platform.UIKit.UIViewController
import roc.win.lottery.app.AppContainer
import roc.win.lottery.data.FakeDrawRepository
import roc.win.lottery.domain.TicketValidator
import roc.win.lottery.recognition.ConservativeTicketParser
import roc.win.lottery.recognition.IOSAppPaths
import roc.win.lottery.recognition.IOSPhotoPickerImageAcquirer
import roc.win.lottery.recognition.ImageDimensionQualityAnalyzer
import roc.win.lottery.recognition.VisionTicketRecognizer

/** iOS 平台能力。 */
class IOSPlatform : Platform {
    /** 当前 iOS 系统版本。 */
    override val name: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion

    /** iOS V1 支持应用内拍照。 */
    override val supportsCamera: Boolean = true
}

/** 返回 iOS 平台能力。 */
actual fun getPlatform(): Platform = IOSPlatform()

/**
 * 创建已接入 iOS PHPicker 和 Vision OCR 的 B3 PoC 容器。
 *
 * @param presenterProvider 返回当前可展示系统图片选择器的宿主控制器。
 * @return 使用真实本地识别、保守解析器和 Fake 开奖仓库的应用容器。
 */
fun createIOSRecognitionContainer(presenterProvider: () -> UIViewController?): AppContainer {
    val appPaths = IOSAppPaths()
    return AppContainer(
        platform = IOSPlatform(),
        imageAcquirer = IOSPhotoPickerImageAcquirer(presenterProvider, appPaths),
        imageQualityAnalyzer = ImageDimensionQualityAnalyzer(),
        ticketRecognizer = VisionTicketRecognizer(),
        ticketParser = ConservativeTicketParser(),
        drawRepository = FakeDrawRepository(),
        appPaths = appPaths,
        ticketValidator = TicketValidator(),
        isDemo = true,
        usesRealImageAcquisition = true,
        usesRealRecognition = true,
    )
}

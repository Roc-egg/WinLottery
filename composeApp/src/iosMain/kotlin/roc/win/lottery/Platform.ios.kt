package roc.win.lottery

import platform.Foundation.NSLog
import platform.UIKit.UIDevice
import platform.UIKit.UIViewController
import roc.win.lottery.app.AppContainer
import roc.win.lottery.app.LogOcrConfidenceDiagnostics
import roc.win.lottery.app.OcrConfidenceDiagnostics
import roc.win.lottery.data.IOSSuperLottoPdfTextExtractor
import roc.win.lottery.data.OfficialDrawRepository
import roc.win.lottery.domain.LotteryPrizeCalculator
import roc.win.lottery.domain.TicketValidator
import roc.win.lottery.recognition.ConservativeTicketParser
import roc.win.lottery.recognition.IOSAppPaths
import roc.win.lottery.recognition.IOSLuminanceImageDecoder
import roc.win.lottery.recognition.IOSPhotoPickerImageAcquirer
import roc.win.lottery.recognition.ImageDimensionQualityAnalyzer
import roc.win.lottery.recognition.ImageQualityAnalyzerChain
import roc.win.lottery.recognition.PixelImageQualityAnalyzer
import roc.win.lottery.recognition.VisionTicketRecognizer
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform as NativePlatform

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
 * 创建已接入 iOS 图片采集、本地 OCR 和真实开奖查询的移动容器。
 *
 * @param presenterProvider 返回当前可展示系统图片选择器的宿主控制器。
 * @return 使用真实本地识别、保守解析器、官网开奖仓库和本地规则引擎的应用容器。
 */
fun createIOSRecognitionContainer(presenterProvider: () -> UIViewController?): AppContainer {
    val appPaths = IOSAppPaths()
    return AppContainer(
        platform = IOSPlatform(),
        imageAcquirer = IOSPhotoPickerImageAcquirer(presenterProvider, appPaths),
        imageQualityAnalyzer =
            ImageQualityAnalyzerChain(
                listOf(
                    ImageDimensionQualityAnalyzer(),
                    PixelImageQualityAnalyzer(IOSLuminanceImageDecoder()),
                ),
            ),
        ticketRecognizer = VisionTicketRecognizer(),
        ticketParser = ConservativeTicketParser(),
        drawRepository =
            OfficialDrawRepository(
                superLottoPdfTextExtractor = IOSSuperLottoPdfTextExtractor(),
            ),
        prizeCalculator = LotteryPrizeCalculator(),
        appPaths = appPaths,
        ticketValidator = TicketValidator(),
        isDemo = true,
        usesRealImageAcquisition = true,
        usesRealRecognition = true,
        usesRealDrawData = true,
        ocrConfidenceDiagnostics = createIOSOcrConfidenceDiagnostics(),
    )
}

/** 只为 Kotlin/Native Debug 二进制创建匿名置信度日志，Release 保持完全禁用。 */
@OptIn(ExperimentalNativeApi::class)
private fun createIOSOcrConfidenceDiagnostics(): OcrConfidenceDiagnostics =
    if (NativePlatform.isDebugBinary) {
        LogOcrConfidenceDiagnostics { line -> NSLog(line.replace("%", "%%")) }
    } else {
        OcrConfidenceDiagnostics.Disabled
    }

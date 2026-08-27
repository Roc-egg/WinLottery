package roc.win.lottery

import platform.Foundation.NSLog
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIDevice
import platform.UIKit.UIViewController
import roc.win.lottery.app.AppContainer
import roc.win.lottery.app.IOSTicketRecordFileExchange
import roc.win.lottery.app.LogOcrConfidenceDiagnostics
import roc.win.lottery.app.OcrConfidenceDiagnostics
import roc.win.lottery.app.withOneShotConflictInjection
import roc.win.lottery.data.IOSSuperLottoPdfTextExtractor
import roc.win.lottery.data.OfficialDrawRepository
import roc.win.lottery.data.ResponsesAiAnalysisProvider
import roc.win.lottery.domain.LotteryPrizeCalculator
import roc.win.lottery.domain.TicketValidator
import roc.win.lottery.persistence.createIOSTicketRecordStore
import roc.win.lottery.recognition.ConservativeTicketParser
import roc.win.lottery.recognition.IOSAppPaths
import roc.win.lottery.recognition.IOSLuminanceImageDecoder
import roc.win.lottery.recognition.IOSOnnxRuntime
import roc.win.lottery.recognition.IOSPhotoPickerImageAcquirer
import roc.win.lottery.recognition.IOSPpOcrTicketRecognizer
import roc.win.lottery.recognition.ImageDimensionQualityAnalyzer
import roc.win.lottery.recognition.ImageQualityAnalyzerChain
import roc.win.lottery.recognition.PixelImageQualityAnalyzer
import roc.win.lottery.recognition.launchIOSOcrAcceptanceIfRequested
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
 * @param onnxRuntime 由 Swift 宿主提供的官方 ONNX Runtime 执行器。
 * @param presenterProvider 返回当前可展示系统图片选择器的宿主控制器。
 * @return 使用真实本地识别、保守解析器、官网开奖仓库和本地规则引擎的应用容器。
 */
fun createIOSRecognitionContainer(
    onnxRuntime: IOSOnnxRuntime,
    presenterProvider: () -> UIViewController?,
): AppContainer {
    val appPaths = IOSAppPaths()
    val isDebugBinary = isIOSDebugBinary()
    val officialDrawRepository =
        OfficialDrawRepository(
            superLottoPdfTextExtractor = IOSSuperLottoPdfTextExtractor(),
        )
    val container =
        AppContainer(
            platform = IOSPlatform(),
            imageAcquirer = IOSPhotoPickerImageAcquirer(presenterProvider, appPaths),
            imageQualityAnalyzer =
                ImageQualityAnalyzerChain(
                    listOf(
                        ImageDimensionQualityAnalyzer(),
                        PixelImageQualityAnalyzer(IOSLuminanceImageDecoder()),
                    ),
                ),
            ticketRecognizer = IOSPpOcrTicketRecognizer(onnxRuntime),
            ticketParser = ConservativeTicketParser(),
            drawRepository =
                officialDrawRepository.withOneShotConflictInjection(
                    rawIssue =
                        NSProcessInfo.processInfo.environment[DEBUG_CONFLICT_ISSUE_ENVIRONMENT] as? String,
                    isDebugEnabled = isDebugBinary,
                ),
            historicalDrawRepository = officialDrawRepository,
            aiAnalysisProvider = ResponsesAiAnalysisProvider(),
            prizeCalculator = LotteryPrizeCalculator(),
            appPaths = appPaths,
            ticketValidator = TicketValidator(),
            isDemo = true,
            usesRealImageAcquisition = true,
            usesRealRecognition = true,
            usesRealDrawData = true,
            ticketRecordStore = createIOSTicketRecordStore(),
            ticketRecordFileExchange = IOSTicketRecordFileExchange(presenterProvider),
            ocrConfidenceDiagnostics = createIOSOcrConfidenceDiagnostics(),
        )
    launchIOSMobileAnalysisPerformanceIfRequested(container, isDebugBinary)
    launchIOSOcrAcceptanceIfRequested(
        imageQualityAnalyzer = container.imageQualityAnalyzer,
        ticketRecognizer = container.ticketRecognizer,
        ticketParser = container.ticketParser,
        isDebugBinary = isDebugBinary,
    )
    return container
}

/** 只为 Kotlin/Native Debug 二进制创建匿名置信度日志，Release 保持完全禁用。 */
@OptIn(ExperimentalNativeApi::class)
private fun createIOSOcrConfidenceDiagnostics(): OcrConfidenceDiagnostics =
    if (isIOSDebugBinary()) {
        LogOcrConfidenceDiagnostics { line -> NSLog(line.replace("%", "%%")) }
    } else {
        OcrConfidenceDiagnostics.Disabled
    }

/** 返回当前 Kotlin/Native 二进制是否允许 Debug 验收能力。 */
@OptIn(ExperimentalNativeApi::class)
private fun isIOSDebugBinary(): Boolean = NativePlatform.isDebugBinary

/** iOS Debug 包一次性冲突验收使用的进程环境变量。 */
private const val DEBUG_CONFLICT_ISSUE_ENVIRONMENT = "WINLOTTERY_DEBUG_CONFLICT_ISSUE"

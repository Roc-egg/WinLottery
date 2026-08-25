@file:OptIn(kotlinx.cinterop.BetaInteropApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package roc.win.lottery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSAttributedString
import platform.Foundation.NSFileManager
import platform.Foundation.NSLog
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.UIKit.NSFontAttributeName
import platform.UIKit.NSForegroundColorAttributeName
import platform.UIKit.UIColor
import platform.UIKit.UIFont
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIRectFill
import platform.UIKit.drawAtPoint
import roc.win.lottery.app.AppContainer
import roc.win.lottery.recognition.ImageRef
import roc.win.lottery.recognition.measureMobileAnalysisPerformance

/** iOS Debug 应用内性能采样使用的后台作用域。 */
private val mobileAnalysisPerformanceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** 防止同一进程重复创建性能采样任务。 */
private var mobileAnalysisPerformanceStarted = false

/**
 * 只在 Debug 应用收到显式样本数时启动匿名 12MP 图片分析采样。
 *
 * @param container 应用实际装配的质量检查、PP-OCRv5 和保守解析依赖。
 * @param isDebugBinary 当前 Kotlin/Native 二进制是否为 Debug 构建。
 */
internal fun launchIOSMobileAnalysisPerformanceIfRequested(
    container: AppContainer,
    isDebugBinary: Boolean,
) {
    if (!isDebugBinary || mobileAnalysisPerformanceStarted) return
    val sampleCount =
        (NSProcessInfo.processInfo.environment[SAMPLE_COUNT_ENVIRONMENT_VARIABLE] as? String)
            ?.toIntOrNull()
            ?.takeIf { it in 1..MAXIMUM_SAMPLE_COUNT }
            ?: return
    mobileAnalysisPerformanceStarted = true
    val warmupCount = if (sampleCount >= REPEATED_SAMPLE_COUNT) REPEATED_WARMUP_COUNT else 0
    logAnonymousLine(MOBILE_ANALYSIS_START_MARKER)
    mobileAnalysisPerformanceScope.launch {
        val imagePath = NSTemporaryDirectory() + NSUUID().UUIDString + JPEG_EXTENSION
        try {
            check(createSyntheticImage().writeToFile(imagePath, atomically = true)) {
                "无法创建 iOS 固定 12MP JPEG"
            }
            val report =
                measureMobileAnalysisPerformance(
                    imageRef =
                        ImageRef(
                            id = SYNTHETIC_IMAGE_ID,
                            localPath = imagePath,
                            mimeType = JPEG_MIME_TYPE,
                            widthPixels = IMAGE_WIDTH_PIXELS,
                            heightPixels = IMAGE_HEIGHT_PIXELS,
                        ),
                    imageQualityAnalyzer = container.imageQualityAnalyzer,
                    ticketRecognizer = container.ticketRecognizer,
                    ticketParser = container.ticketParser,
                    warmupCount = warmupCount,
                    sampleCount = sampleCount,
                )
            logAnonymousLine(
                report.toAnonymousLine(
                    platform = IOS_PLATFORM_NAME,
                    widthPixels = IMAGE_WIDTH_PIXELS,
                    heightPixels = IMAGE_HEIGHT_PIXELS,
                    warmupCount = warmupCount,
                ),
            )
        } catch (throwable: Throwable) {
            logAnonymousLine(
                "$MOBILE_ANALYSIS_FAILURE_MARKER type=${throwable::class.simpleName ?: UNKNOWN_FAILURE_TYPE}",
            )
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(imagePath, error = null)
        }
    }
}

/** 创建不含真实票面、位置元数据或用户内容的固定 JPEG。 */
private fun createSyntheticImage() =
    run {
        val size = CGSizeMake(IMAGE_WIDTH_PIXELS.toDouble(), IMAGE_HEIGHT_PIXELS.toDouble())
        UIGraphicsBeginImageContextWithOptions(size, true, 1.0)
        try {
            UIColor.colorWithWhite(BACKGROUND_WHITE, alpha = 1.0).setFill()
            UIRectFill(
                CGRectMake(0.0, 0.0, IMAGE_WIDTH_PIXELS.toDouble(), IMAGE_HEIGHT_PIXELS.toDouble()),
            )
            val textAttributes: Map<Any?, Any?> =
                mapOf(
                    NSFontAttributeName to UIFont.systemFontOfSize(TEXT_SIZE_PIXELS),
                    NSForegroundColorAttributeName to UIColor.blackColor,
                )
            SYNTHETIC_LINES.forEachIndexed { index, text ->
                NSAttributedString
                    .create(string = text, attributes = textAttributes)
                    .drawAtPoint(CGPointMake(TEXT_LEFT, FIRST_TEXT_TOP + index * LINE_HEIGHT_PIXELS))
            }
            val image = checkNotNull(UIGraphicsGetImageFromCurrentImageContext())
            checkNotNull(UIImageJPEGRepresentation(image, JPEG_QUALITY))
        } finally {
            UIGraphicsEndImageContext()
        }
    }

/** 使用系统日志输出不含 OCR 内容的固定报告。 */
private fun logAnonymousLine(line: String) {
    NSLog(line.replace("%", "%%"))
}

/** 专项脚本传入的统计样本数环境变量。 */
private const val SAMPLE_COUNT_ENVIRONMENT_VARIABLE = "WINLOTTERY_MOBILE_ANALYSIS_SAMPLE_COUNT"

/** 专项入口使用的重复样本数。 */
private const val REPEATED_SAMPLE_COUNT = 20

/** 专项入口在统计前执行的热身次数。 */
private const val REPEATED_WARMUP_COUNT = 2

/** 防止外部参数制造过长验收。 */
private const val MAXIMUM_SAMPLE_COUNT = 100

/** 固定输入宽度。 */
private const val IMAGE_WIDTH_PIXELS = 3_000

/** 固定输入高度。 */
private const val IMAGE_HEIGHT_PIXELS = 4_000

/** 合成图片背景白度。 */
private const val BACKGROUND_WHITE = 0.88

/** 合成 JPEG 编码质量。 */
private const val JPEG_QUALITY = 0.95

/** 合成文本左边界。 */
private const val TEXT_LEFT = 420.0

/** 第一行文本上边界。 */
private const val FIRST_TEXT_TOP = 500.0

/** 合成文本字号。 */
private const val TEXT_SIZE_PIXELS = 104.0

/** 合成文本行高。 */
private const val LINE_HEIGHT_PIXELS = 320.0

/** 当前流程内的匿名图片标识。 */
private const val SYNTHETIC_IMAGE_ID = "mobile-analysis-performance"

/** JPEG 文件扩展名。 */
private const val JPEG_EXTENSION = ".jpg"

/** JPEG MIME 类型。 */
private const val JPEG_MIME_TYPE = "image/jpeg"

/** 匿名平台名称。 */
private const val IOS_PLATFORM_NAME = "ios-simulator-app"

/** 不含错误消息和票面内容的失败标记。 */
private const val MOBILE_ANALYSIS_FAILURE_MARKER = "WINLOTTERY_MOBILE_ANALYSIS_FAILURE"

/** 证明 Debug 应用已进入显式性能采样入口的固定标记。 */
private const val MOBILE_ANALYSIS_START_MARKER = "WINLOTTERY_MOBILE_ANALYSIS_STARTED"

/** 无法取得异常类型时使用的固定分类。 */
private const val UNKNOWN_FAILURE_TYPE = "unknown"

/** 合成图内只用于本地 OCR 的固定非票据文本。 */
private val SYNTHETIC_LINES =
    listOf(
        "MOBILE ANALYSIS PERFORMANCE",
        "SYNTHETIC IMAGE NOT A TICKET",
        "ISSUE 2026001",
        "01 02 03 04 05 + 06 07",
        "08 09 10 11 12 + 01 02",
        "13 14 15 16 17 + 03 04",
        "18 19 20 21 22 + 05 06",
        "TOTAL 10.00",
    )

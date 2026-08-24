@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package roc.win.lottery.recognition

import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSFileManager
import platform.Foundation.NSLog
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.writeToFile
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation

/** iOS Debug 真实票图验收使用的后台作用域。 */
private val iosOcrAcceptanceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** 防止同一 iOS Debug 进程重复启动真实票图验收。 */
private var iosOcrAcceptanceStarted = false

/**
 * 只在 iOS Debug 应用收到显式样本数时启动匿名真实票图验收。
 *
 * @param imageQualityAnalyzer 应用生产装配的质量检查链。
 * @param ticketRecognizer 应用生产装配的 PP-OCRv5 识别器。
 * @param ticketParser 应用生产装配的共享保守解析器。
 * @param isDebugBinary 当前 Kotlin/Native 二进制是否为 Debug 构建。
 */
fun launchIOSOcrAcceptanceIfRequested(
    imageQualityAnalyzer: ImageQualityAnalyzer,
    ticketRecognizer: TicketRecognizer,
    ticketParser: TicketParser,
    isDebugBinary: Boolean,
) {
    if (!isDebugBinary || iosOcrAcceptanceStarted) return
    val expectedSampleCount =
        (NSProcessInfo.processInfo.environment[SAMPLE_COUNT_ENVIRONMENT_VARIABLE] as? String)
            ?.toIntOrNull()
            ?.takeIf { it in 1..MAXIMUM_SAMPLE_COUNT }
            ?: return
    iosOcrAcceptanceStarted = true
    logAnonymousLine(OCR_ACCEPTANCE_START_MARKER)
    iosOcrAcceptanceScope.launch {
        val sampleDirectory = NSTemporaryDirectory() + SAMPLE_DIRECTORY_NAME
        try {
            val sampleNames = supportedSampleNames(sampleDirectory)
            check(sampleNames.size == expectedSampleCount) { "iOS OCR 验收样本数量不匹配" }
            val reports =
                sampleNames.mapIndexed { index, name ->
                    val normalizedPath = NSTemporaryDirectory() + NSUUID().UUIDString + JPEG_EXTENSION
                    try {
                        val imageRef =
                            normalizeSample("$sampleDirectory$PATH_SEPARATOR$name", normalizedPath, index + 1)
                        analyzeOcrAcceptanceSample(
                            imageRef = imageRef,
                            imageQualityAnalyzer = imageQualityAnalyzer,
                            ticketRecognizer = ticketRecognizer,
                            ticketParser = ticketParser,
                        ).also { report ->
                            logAnonymousLine(report.toAnonymousLine(IOS_PLATFORM_NAME, index + 1))
                        }
                    } finally {
                        NSFileManager.defaultManager.removeItemAtPath(normalizedPath, error = null)
                    }
                }
            val summary = OcrAcceptanceSummary(reports)
            logAnonymousLine(summary.toAnonymousLine(IOS_PLATFORM_NAME))
            check(reports.none { report -> report.outcome in BLOCKING_OUTCOMES }) {
                "iOS 当前探索图存在质量拒绝或 OCR 失败"
            }
        } catch (throwable: Throwable) {
            logAnonymousLine(
                "$OCR_ACCEPTANCE_FAILURE_MARKER type=${throwable::class.simpleName ?: UNKNOWN_FAILURE_TYPE}",
            )
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(sampleDirectory, error = null)
        }
    }
}

/** 返回按匿名文件名排序的直属受支持图片。 */
private fun supportedSampleNames(directory: String): List<String> =
    NSFileManager.defaultManager
        .contentsOfDirectoryAtPath(directory, error = null)
        .orEmpty()
        .mapNotNull { value -> value as? String }
        .filter { name -> name.substringAfterLast(EXTENSION_SEPARATOR, "").lowercase() in SUPPORTED_EXTENSIONS }
        .sorted()

/** 按生产导图参数应用方向、限制尺寸并重新编码为无元数据 JPEG。 */
private fun normalizeSample(
    sourcePath: String,
    destinationPath: String,
    sampleNumber: Int,
): ImageRef {
    val image = UIImage.imageWithContentsOfFile(sourcePath) ?: error("iOS 第 $sampleNumber 张验收图无法解码")
    val sourceWidth = image.size.useContents { width }
    val sourceHeight = image.size.useContents { height }
    check(sourceWidth > 0.0 && sourceHeight > 0.0) { "iOS 第 $sampleNumber 张验收图尺寸无效" }
    val scale = minOf(1.0, MAXIMUM_IMAGE_EDGE_PIXELS / maxOf(sourceWidth, sourceHeight))
    val outputWidth = sourceWidth * scale
    val outputHeight = sourceHeight * scale
    val outputSize = CGSizeMake(outputWidth, outputHeight)
    UIGraphicsBeginImageContextWithOptions(outputSize, true, 1.0)
    val encoded =
        try {
            image.drawInRect(CGRectMake(0.0, 0.0, outputWidth, outputHeight))
            val normalized = checkNotNull(UIGraphicsGetImageFromCurrentImageContext())
            checkNotNull(UIImageJPEGRepresentation(normalized, JPEG_QUALITY))
        } finally {
            UIGraphicsEndImageContext()
        }
    check(encoded.writeToFile(destinationPath, atomically = true)) {
        "iOS 第 $sampleNumber 张验收图无法重新编码"
    }
    return ImageRef(
        id = "$ANONYMOUS_IMAGE_ID_PREFIX$sampleNumber",
        localPath = destinationPath,
        mimeType = JPEG_MIME_TYPE,
        widthPixels = outputWidth.toInt(),
        heightPixels = outputHeight.toInt(),
    )
}

/** 使用系统日志输出不含 OCR 内容的固定报告。 */
private fun logAnonymousLine(line: String) {
    NSLog(line.replace("%", "%%"))
}

/** iOS 显式验收使用的样本数环境变量。 */
private const val SAMPLE_COUNT_ENVIRONMENT_VARIABLE = "WINLOTTERY_OCR_ACCEPTANCE_SAMPLE_COUNT"

/** iOS 应用临时目录中的匿名样本目录。 */
private const val SAMPLE_DIRECTORY_NAME = "WinLotteryOcrAcceptance"

/** 单张图片生产导图允许的最长边。 */
private const val MAXIMUM_IMAGE_EDGE_PIXELS = 2048.0

/** 生产私有副本 JPEG 质量。 */
private const val JPEG_QUALITY = 0.95

/** 匿名图片标识前缀。 */
private const val ANONYMOUS_IMAGE_ID_PREFIX = "ios-ocr-acceptance-"

/** JPEG 文件扩展名。 */
private const val JPEG_EXTENSION = ".jpg"

/** JPEG MIME 类型。 */
private const val JPEG_MIME_TYPE = "image/jpeg"

/** Unix 路径分隔符。 */
private const val PATH_SEPARATOR = "/"

/** 文件扩展名分隔符。 */
private const val EXTENSION_SEPARATOR = '.'

/** iOS 平台匿名名称。 */
private const val IOS_PLATFORM_NAME = "ios-simulator-app"

/** 显式验收开始固定标记。 */
private const val OCR_ACCEPTANCE_START_MARKER = "WINLOTTERY_OCR_ACCEPTANCE_STARTED"

/** 显式验收失败固定标记。 */
private const val OCR_ACCEPTANCE_FAILURE_MARKER = "WINLOTTERY_OCR_ACCEPTANCE_FAILURE"

/** 防止外部参数制造无界验收。 */
private const val MAXIMUM_SAMPLE_COUNT = 100

/** 无法取得异常类型时使用的分类。 */
private const val UNKNOWN_FAILURE_TYPE = "unknown"

/** 当前入口接受的本地图片扩展名。 */
private val SUPPORTED_EXTENSIONS = setOf("jpg", "jpeg", "png")

/** 当前探索基线不允许出现的阻断状态。 */
private val BLOCKING_OUTCOMES =
    setOf(
        OcrAcceptanceOutcome.QUALITY_REJECTED,
        OcrAcceptanceOutcome.RECOGNITION_POOR_IMAGE,
        OcrAcceptanceOutcome.RECOGNITION_FAILURE,
    )

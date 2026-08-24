package roc.win.lottery.recognition

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** 在锁定 Android 虚拟机中匿名验收本地真实票图。 */
@RunWith(AndroidJUnit4::class)
class OcrAcceptanceDeviceTest {
    /** 逐张执行生产一致的图片归一化、质量检查、PP-OCRv5 和保守解析。 */
    @Test
    fun explicitlyStagedSamplesProduceAnonymousAcceptanceReport() =
        runTest(timeout = TEST_TIMEOUT) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.context
            val expectedSampleCount =
                InstrumentationRegistry
                    .getArguments()
                    .getString(SAMPLE_COUNT_ARGUMENT)
                    ?.toIntOrNull()
                    ?.takeIf { it in 1..MAXIMUM_SAMPLE_COUNT }
                    ?: error("缺少 Android OCR 验收样本数")
            val sampleOffset =
                InstrumentationRegistry
                    .getArguments()
                    .getString(SAMPLE_OFFSET_ARGUMENT)
                    ?.toIntOrNull()
                    ?.takeIf { it >= 0 && it + expectedSampleCount <= MAXIMUM_SAMPLE_COUNT }
                    ?: 0
            val sampleDirectory = File(context.cacheDir, SAMPLE_DIRECTORY_NAME)
            val samples =
                sampleDirectory
                    .listFiles()
                    .orEmpty()
                    .filter { file -> file.isFile && file.extension.lowercase() in SUPPORTED_EXTENSIONS }
                    .sortedBy(File::getName)
            assertEquals(expectedSampleCount, samples.size, "Android OCR 验收样本数量不匹配")

            val analyzer =
                ImageQualityAnalyzerChain(
                    listOf(
                        ImageDimensionQualityAnalyzer(),
                        PixelImageQualityAnalyzer(AndroidLuminanceImageDecoder()),
                    ),
                )
            val recognizer = AndroidPpOcrTicketRecognizer(context)
            val parser = ConservativeTicketParser()
            val reports = mutableListOf<OcrAcceptanceSampleReport>()
            try {
                samples.forEachIndexed { index, source ->
                    val sampleNumber = sampleOffset + index + 1
                    val normalized = File(context.cacheDir, "$NORMALIZED_FILE_PREFIX$sampleNumber$JPEG_EXTENSION")
                    try {
                        val imageRef = normalizeSample(source, normalized, sampleNumber)
                        val report =
                            analyzeOcrAcceptanceSample(
                                imageRef = imageRef,
                                imageQualityAnalyzer = analyzer,
                                ticketRecognizer = recognizer,
                                ticketParser = parser,
                            )
                        reports += report
                        logAnonymousLine(report.toAnonymousLine(ANDROID_PLATFORM_NAME, sampleNumber))
                    } finally {
                        normalized.delete()
                    }
                    // 该批次只验收准确率结构；主动回收上一张的大数组，避免把紧密循环误当用户操作间隔。
                    Runtime.getRuntime().gc()
                }
            } finally {
                samples.forEach(File::delete)
                sampleDirectory.delete()
            }
            val summary = OcrAcceptanceSummary(reports)
            logAnonymousLine(summary.toAnonymousLine(ANDROID_PLATFORM_NAME))
            assertTrue(
                reports.none { report -> report.outcome in BLOCKING_OUTCOMES },
                "Android 当前探索图存在质量拒绝或 OCR 失败，详见匿名验收报告",
            )
        }

    /** 按生产导图参数归一化方向、限制尺寸并重新编码 JPEG。 */
    private fun normalizeSample(
        source: File,
        destination: File,
        sampleNumber: Int,
    ): ImageRef {
        val bitmap =
            decodeNormalizedBitmap(
                openStream = { runCatching { FileInputStream(source) }.getOrNull() },
                maxEdgePixels = MAXIMUM_IMAGE_EDGE_PIXELS,
            ) ?: error("Android 第 $sampleNumber 张验收图无法解码")
        return try {
            val saved =
                destination.outputStream().buffered().use { output ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
                }
            check(saved) { "Android 第 $sampleNumber 张验收图无法重新编码" }
            ImageRef(
                id = "$ANONYMOUS_IMAGE_ID_PREFIX$sampleNumber",
                localPath = destination.absolutePath,
                mimeType = JPEG_MIME_TYPE,
                widthPixels = bitmap.width,
                heightPixels = bitmap.height,
            )
        } finally {
            bitmap.recycle()
        }
    }

    /** 输出只含匿名序号、计数、分类和耗时的 Android 日志。 */
    private fun logAnonymousLine(line: String) {
        println(line)
        Log.i(LOG_TAG, line)
    }

    /** Android 真实票图验收固定参数。 */
    private companion object {
        /** Android 匿名验收日志标签。 */
        const val LOG_TAG = "WinLotteryOcrAcceptance"

        /** 专项脚本传入的样本数量参数。 */
        const val SAMPLE_COUNT_ARGUMENT = "winlottery.ocr.acceptance.samples"

        /** 分批验收时用于生成全局匿名序号的样本偏移参数。 */
        const val SAMPLE_OFFSET_ARGUMENT = "winlottery.ocr.acceptance.sampleOffset"

        /** 测试 APK 私有缓存中的匿名样本目录。 */
        const val SAMPLE_DIRECTORY_NAME = "ocr-acceptance"

        /** 生产导图允许的最长边。 */
        const val MAXIMUM_IMAGE_EDGE_PIXELS = 2048

        /** 生产私有副本 JPEG 质量。 */
        const val JPEG_QUALITY = 95

        /** 归一化临时文件名前缀。 */
        const val NORMALIZED_FILE_PREFIX = "normalized-ocr-acceptance-"

        /** 匿名图片标识前缀。 */
        const val ANONYMOUS_IMAGE_ID_PREFIX = "android-ocr-acceptance-"

        /** JPEG 扩展名。 */
        const val JPEG_EXTENSION = ".jpg"

        /** JPEG MIME 类型。 */
        const val JPEG_MIME_TYPE = "image/jpeg"

        /** Android 平台匿名名称。 */
        const val ANDROID_PLATFORM_NAME = "android-emulator"

        /** 防止外部参数制造无界测试。 */
        const val MAXIMUM_SAMPLE_COUNT = 100

        /** 当前入口接受的本地图片扩展名。 */
        val SUPPORTED_EXTENSIONS = setOf("jpg", "jpeg", "png")

        /** 当前探索基线不允许出现的阻断状态。 */
        val BLOCKING_OUTCOMES =
            setOf(
                OcrAcceptanceOutcome.QUALITY_REJECTED,
                OcrAcceptanceOutcome.RECOGNITION_POOR_IMAGE,
                OcrAcceptanceOutcome.RECOGNITION_FAILURE,
            )

        /** 13 张高分辨率票图的最大验收时长。 */
        val TEST_TIMEOUT = 10.minutes
    }
}

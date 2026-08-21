package roc.win.lottery.recognition

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** 在锁定 Android 虚拟机测试进程中执行匿名 12MP 图片分析采样。 */
@RunWith(AndroidJUnit4::class)
class MobileAnalysisPerformanceDeviceTest {
    /** 生成固定合成 JPEG，并执行质量检查、ML Kit OCR 和共享解析。 */
    @Test
    fun fixedTwelveMegapixelImageProducesAnonymousBaseline() =
        runTest(timeout = TEST_TIMEOUT) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val sampleCount =
                InstrumentationRegistry
                    .getArguments()
                    .getString(SAMPLE_COUNT_ARGUMENT)
                    ?.toIntOrNull()
                    ?.takeIf { it in 1..MAXIMUM_SAMPLE_COUNT }
                    ?: DEFAULT_SAMPLE_COUNT
            val warmupCount = if (sampleCount >= REPEATED_SAMPLE_COUNT) REPEATED_WARMUP_COUNT else 0
            val imageFile = File(context.cacheDir, SYNTHETIC_IMAGE_FILE_NAME)
            try {
                assertTrue(createSyntheticImage(imageFile), "无法创建 Android 固定 12MP JPEG")
                val report =
                    measureMobileAnalysisPerformance(
                        imageRef =
                            ImageRef(
                                id = SYNTHETIC_IMAGE_ID,
                                localPath = imageFile.absolutePath,
                                mimeType = JPEG_MIME_TYPE,
                                widthPixels = IMAGE_WIDTH_PIXELS,
                                heightPixels = IMAGE_HEIGHT_PIXELS,
                            ),
                        imageQualityAnalyzer =
                            ImageQualityAnalyzerChain(
                                listOf(
                                    ImageDimensionQualityAnalyzer(),
                                    PixelImageQualityAnalyzer(AndroidLuminanceImageDecoder()),
                                ),
                            ),
                        ticketRecognizer = MlKitChineseTicketRecognizer(context),
                        warmupCount = warmupCount,
                        sampleCount = sampleCount,
                    )
                val line =
                    report.toAnonymousLine(
                        platform = ANDROID_PLATFORM_NAME,
                        widthPixels = IMAGE_WIDTH_PIXELS,
                        heightPixels = IMAGE_HEIGHT_PIXELS,
                        warmupCount = warmupCount,
                    )
                println(line)
                Log.i(LOG_TAG, line)
            } finally {
                imageFile.delete()
            }
        }

    /** 创建不含真实票面、位置元数据或用户内容的固定 JPEG。 */
    private fun createSyntheticImage(file: File): Boolean {
        val bitmap = Bitmap.createBitmap(IMAGE_WIDTH_PIXELS, IMAGE_HEIGHT_PIXELS, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(BACKGROUND_LUMINANCE, BACKGROUND_LUMINANCE, BACKGROUND_LUMINANCE))
            val textPaint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = TEXT_SIZE_PIXELS
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                }
            SYNTHETIC_LINES.forEachIndexed { index, text ->
                canvas.drawText(text, TEXT_LEFT, FIRST_TEXT_BASELINE + index * LINE_HEIGHT_PIXELS, textPaint)
            }
            file.outputStream().buffered().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Android 固定性能采样参数。 */
    private companion object {
        /** Android 匿名日志标签。 */
        const val LOG_TAG = "WinLotteryPerformance"

        /** 专项脚本传入的统计样本数参数。 */
        const val SAMPLE_COUNT_ARGUMENT = "winlottery.performance.samples"

        /** 普通固定回归只执行一次链路冒烟。 */
        const val DEFAULT_SAMPLE_COUNT = 1

        /** 专项入口使用的重复样本数。 */
        const val REPEATED_SAMPLE_COUNT = 20

        /** 专项入口在统计前执行的热身次数。 */
        const val REPEATED_WARMUP_COUNT = 2

        /** 防止外部参数制造过长验收。 */
        const val MAXIMUM_SAMPLE_COUNT = 100

        /** 固定输入宽度。 */
        const val IMAGE_WIDTH_PIXELS = 3_000

        /** 固定输入高度。 */
        const val IMAGE_HEIGHT_PIXELS = 4_000

        /** 合成 JPEG 编码质量。 */
        const val JPEG_QUALITY = 95

        /** 合成图片背景灰度。 */
        const val BACKGROUND_LUMINANCE = 224

        /** 合成文本左边界。 */
        const val TEXT_LEFT = 420f

        /** 第一行文本基线。 */
        const val FIRST_TEXT_BASELINE = 600f

        /** 合成文本字号。 */
        const val TEXT_SIZE_PIXELS = 104f

        /** 合成文本行高。 */
        const val LINE_HEIGHT_PIXELS = 320f

        /** 私有合成图片文件名。 */
        const val SYNTHETIC_IMAGE_FILE_NAME = "mobile-analysis-performance.jpg"

        /** 当前流程内的匿名图片标识。 */
        const val SYNTHETIC_IMAGE_ID = "mobile-analysis-performance"

        /** JPEG MIME 类型。 */
        const val JPEG_MIME_TYPE = "image/jpeg"

        /** 匿名平台名称。 */
        const val ANDROID_PLATFORM_NAME = "android-emulator"

        /** 合成图内只用于本地 OCR 的固定非票据文本。 */
        val SYNTHETIC_LINES =
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

        /** 单次测试最大时长。 */
        val TEST_TIMEOUT = 10.minutes
    }
}

package roc.win.lottery.recognition

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 像素图片质量分析器的确定性测试。 */
class PixelImageQualityAnalyzerTest {
    /** 明暗交错且曝光正常的清晰样本应通过。 */
    @Test
    fun sharpWellExposedSamplePasses() =
        runTest {
            val result = analyzer(sharpCheckerboard()).analyze(image())

            assertIs<ImageQualityResult.Passed>(result)
        }

    /** 曝光正常但没有局部边缘的样本应判定为明显模糊。 */
    @Test
    fun flatSampleIsRejectedAsBlurred() =
        runTest {
            val result = analyzer(uniformSample(128)).analyze(image())

            val poorImage = assertIs<ImageQualityResult.PoorImage>(result)
            assertEquals(listOf("图片明显模糊，请保持手机稳定并重新拍摄"), poorImage.issues)
        }

    /** 严重过暗时应优先提示补充光线，不重复报告模糊。 */
    @Test
    fun darkSampleIsRejectedBeforeBlurCheck() =
        runTest {
            val result = analyzer(uniformSample(20)).analyze(image())

            val poorImage = assertIs<ImageQualityResult.PoorImage>(result)
            assertEquals(listOf("图片整体过暗，请在光线充足且均匀的环境重新拍摄"), poorImage.issues)
        }

    /** 大面积高光饱和且平均亮度过高时应提示避开强光。 */
    @Test
    fun brightSampleIsRejectedBeforeBlurCheck() =
        runTest {
            val result = analyzer(uniformSample(250)).analyze(image())

            val poorImage = assertIs<ImageQualityResult.PoorImage>(result)
            assertEquals(listOf("图片整体过亮，请避开强光和反光后重新拍摄"), poorImage.issues)
        }

    /** 平台无法解码私有图片时应返回不包含路径的失败说明。 */
    @Test
    fun decoderFailureIsReportedSafely() =
        runTest {
            val result = PixelImageQualityAnalyzer(LuminanceImageDecoder { _, _ -> null }).analyze(image())

            val failure = assertIs<ImageQualityResult.Failure>(result)
            assertEquals("无法读取图片内容，请重新拍摄或选择图片", failure.message)
            assertFalse(failure.message.contains(image().localPath))
        }

    /** 样本尺寸与亮度数组不一致时不能继续计算。 */
    @Test
    fun malformedSampleFailsInspection() =
        runTest {
            val malformed = LuminanceImageSample(SAMPLE_WIDTH, SAMPLE_HEIGHT, ByteArray(SAMPLE_PIXEL_COUNT - 1))
            val result = analyzer(malformed).analyze(image())

            val failure = assertIs<ImageQualityResult.Failure>(result)
            assertEquals("图片像素数据不完整，请重新拍摄或选择图片", failure.message)
        }

    /** 分辨率不合格时分析链不得调用更昂贵的像素解码器。 */
    @Test
    fun chainStopsBeforePixelDecoderWhenDimensionsArePoor() =
        runTest {
            var decoderCalled = false
            val chain =
                ImageQualityAnalyzerChain(
                    listOf(
                        ImageDimensionQualityAnalyzer(),
                        PixelImageQualityAnalyzer(
                            LuminanceImageDecoder { _, _ ->
                                decoderCalled = true
                                sharpCheckerboard()
                            },
                        ),
                    ),
                )

            val result = chain.analyze(image(width = 719, height = 1800))

            assertIs<ImageQualityResult.PoorImage>(result)
            assertFalse(decoderCalled)
        }

    /** 像素解码器必须取得固定分析长边，避免平台输出不同尺度。 */
    @Test
    fun decoderReceivesFixedMaximumEdge() =
        runTest {
            var receivedMaximumEdge = 0
            val analyzer =
                PixelImageQualityAnalyzer(
                    LuminanceImageDecoder { _, maximumLongEdgePixels ->
                        receivedMaximumEdge = maximumLongEdgePixels
                        sharpCheckerboard()
                    },
                )

            assertIs<ImageQualityResult.Passed>(analyzer.analyze(image()))
            assertEquals(512, receivedMaximumEdge)
        }

    /** 创建使用固定亮度样本的分析器。 */
    private fun analyzer(sample: LuminanceImageSample): PixelImageQualityAnalyzer =
        PixelImageQualityAnalyzer(LuminanceImageDecoder { _, _ -> sample })

    /** 创建不会触及真实文件的测试图片引用。 */
    private fun image(
        width: Int = 1280,
        height: Int = 720,
    ): ImageRef =
        ImageRef(
            id = "pixel-quality-test-image",
            localPath = "memory://pixel-quality-test-image.jpg",
            mimeType = "image/jpeg",
            widthPixels = width,
            heightPixels = height,
        )

    /** 创建曝光正常且具有大量稳定边缘的棋盘亮度样本。 */
    private fun sharpCheckerboard(): LuminanceImageSample =
        LuminanceImageSample(
            widthPixels = SAMPLE_WIDTH,
            heightPixels = SAMPLE_HEIGHT,
            pixels =
                ByteArray(SAMPLE_PIXEL_COUNT) { index ->
                    val x = index % SAMPLE_WIDTH
                    val y = index / SAMPLE_WIDTH
                    if ((x / CHECKER_CELL_SIZE + y / CHECKER_CELL_SIZE) % 2 == 0) {
                        DARK_CHECKER_LUMINANCE.toByte()
                    } else {
                        BRIGHT_CHECKER_LUMINANCE.toByte()
                    }
                },
        )

    /** 创建指定亮度的均匀样本。 */
    private fun uniformSample(luminance: Int): LuminanceImageSample {
        assertTrue(luminance in 0..255)
        return LuminanceImageSample(
            widthPixels = SAMPLE_WIDTH,
            heightPixels = SAMPLE_HEIGHT,
            pixels = ByteArray(SAMPLE_PIXEL_COUNT) { luminance.toByte() },
        )
    }

    /** 测试样本固定参数。 */
    private companion object {
        /** 样本宽度。 */
        const val SAMPLE_WIDTH = 32

        /** 样本高度。 */
        const val SAMPLE_HEIGHT = 32

        /** 样本总像素数。 */
        const val SAMPLE_PIXEL_COUNT = SAMPLE_WIDTH * SAMPLE_HEIGHT

        /** 棋盘单元边长。 */
        const val CHECKER_CELL_SIZE = 2

        /** 棋盘暗格亮度，保持高于暗像素判定阈值。 */
        const val DARK_CHECKER_LUMINANCE = 40

        /** 棋盘亮格亮度，保持低于高光饱和判定阈值。 */
        const val BRIGHT_CHECKER_LUMINANCE = 220
    }
}

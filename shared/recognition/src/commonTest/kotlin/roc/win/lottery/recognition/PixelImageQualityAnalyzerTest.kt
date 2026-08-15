package roc.win.lottery.recognition

import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
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
            val result = analyzer(orientedGrid(0.0)).analyze(image())

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

    /** 轻微倾斜仍应放行，避免要求用户进行没有必要的精确对齐。 */
    @Test
    fun slightlySkewedGridPasses() =
        runTest {
            val result = analyzer(orientedGrid(8.0)).analyze(image())

            assertIs<ImageQualityResult.Passed>(result)
        }

    /** 主轴明显偏离水平和垂直时应提示摆正彩票或手机。 */
    @Test
    fun clearlySkewedGridIsRejected() =
        runTest {
            val result = analyzer(orientedGrid(20.0)).analyze(image())

            val poorImage = assertIs<ImageQualityResult.PoorImage>(result)
            assertEquals(listOf("图片倾斜明显，请摆正彩票或手机后重新拍摄"), poorImage.issues)
        }

    /** 少量斜边不足以代表整张票面方向，不能据此拒绝图片。 */
    @Test
    fun sparseDiagonalEdgePasses() =
        runTest {
            val result = analyzer(sparseDiagonalLine()).analyze(image())

            assertIs<ImageQualityResult.Passed>(result)
        }

    /** 边缘方向分散时没有可靠票面主轴，必须保守放行。 */
    @Test
    fun incoherentTexturePasses() =
        runTest {
            val result = analyzer(deterministicTexture()).analyze(image())

            assertIs<ImageQualityResult.Passed>(result)
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
                                orientedGrid(0.0)
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
                        orientedGrid(0.0)
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

    /** 创建指定亮度的均匀样本。 */
    private fun uniformSample(luminance: Int): LuminanceImageSample {
        assertTrue(luminance in 0..255)
        return LuminanceImageSample(
            widthPixels = SAMPLE_WIDTH,
            heightPixels = SAMPLE_HEIGHT,
            pixels = ByteArray(SAMPLE_PIXEL_COUNT) { luminance.toByte() },
        )
    }

    /** 创建包含两组相互垂直线条的指定角度亮度样本。 */
    private fun orientedGrid(angleDegrees: Double): LuminanceImageSample {
        val angleRadians = angleDegrees / 180.0 * PI
        val angleCosine = cos(angleRadians)
        val angleSine = sin(angleRadians)
        val centerX = (ORIENTED_SAMPLE_EDGE - 1) / 2.0
        val centerY = (ORIENTED_SAMPLE_EDGE - 1) / 2.0
        return LuminanceImageSample(
            widthPixels = ORIENTED_SAMPLE_EDGE,
            heightPixels = ORIENTED_SAMPLE_EDGE,
            pixels =
                ByteArray(ORIENTED_SAMPLE_EDGE * ORIENTED_SAMPLE_EDGE) { index ->
                    val x = index % ORIENTED_SAMPLE_EDGE - centerX
                    val y = index / ORIENTED_SAMPLE_EDGE - centerY
                    val horizontalCoordinate = angleCosine * x + angleSine * y
                    val verticalCoordinate = -angleSine * x + angleCosine * y
                    val isGridLine =
                        distanceToGridLine(horizontalCoordinate) <= GRID_LINE_HALF_WIDTH ||
                            distanceToGridLine(verticalCoordinate) <= GRID_LINE_HALF_WIDTH
                    if (isGridLine) {
                        DARK_GRID_LUMINANCE.toByte()
                    } else {
                        BRIGHT_GRID_LUMINANCE.toByte()
                    }
                },
        )
    }

    /** 计算坐标到最近网格线的距离。 */
    private fun distanceToGridLine(coordinate: Double): Double {
        val offset = ((coordinate % GRID_CELL_SIZE) + GRID_CELL_SIZE) % GRID_CELL_SIZE
        return minOf(offset, GRID_CELL_SIZE - offset)
    }

    /** 创建边缘比例低于倾斜判断门槛的单条对角线。 */
    private fun sparseDiagonalLine(): LuminanceImageSample =
        LuminanceImageSample(
            widthPixels = ORIENTED_SAMPLE_EDGE,
            heightPixels = ORIENTED_SAMPLE_EDGE,
            pixels =
                ByteArray(ORIENTED_SAMPLE_EDGE * ORIENTED_SAMPLE_EDGE) { index ->
                    val x = index % ORIENTED_SAMPLE_EDGE
                    val y = index / ORIENTED_SAMPLE_EDGE
                    if (kotlin.math.abs(x - y) <= DIAGONAL_LINE_HALF_WIDTH) {
                        DARK_GRID_LUMINANCE.toByte()
                    } else {
                        TEXTURE_MIDDLE_LUMINANCE.toByte()
                    }
                },
        )

    /** 创建曝光正常、边缘丰富但方向不集中的确定性纹理。 */
    private fun deterministicTexture(): LuminanceImageSample {
        var state = TEXTURE_RANDOM_SEED
        return LuminanceImageSample(
            widthPixels = ORIENTED_SAMPLE_EDGE,
            heightPixels = ORIENTED_SAMPLE_EDGE,
            pixels =
                ByteArray(ORIENTED_SAMPLE_EDGE * ORIENTED_SAMPLE_EDGE) {
                    state = state * TEXTURE_RANDOM_MULTIPLIER + TEXTURE_RANDOM_INCREMENT
                    (TEXTURE_MINIMUM_LUMINANCE + (state ushr TEXTURE_RANDOM_SHIFT_BITS) % TEXTURE_LUMINANCE_RANGE)
                        .toByte()
                },
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

        /** 倾斜网格测试图的固定边长。 */
        const val ORIENTED_SAMPLE_EDGE = 128

        /** 倾斜网格线条间距。 */
        const val GRID_CELL_SIZE = 16.0

        /** 倾斜网格线条半宽。 */
        const val GRID_LINE_HALF_WIDTH = 1.0

        /** 单条对角线半宽。 */
        const val DIAGONAL_LINE_HALF_WIDTH = 1

        /** 无序纹理亮度下限。 */
        const val TEXTURE_MINIMUM_LUMINANCE = 64

        /** 无序纹理亮度范围。 */
        const val TEXTURE_LUMINANCE_RANGE = 128

        /** 单条对角线的中灰背景亮度。 */
        const val TEXTURE_MIDDLE_LUMINANCE = 160

        /** 确定性纹理线性同余种子。 */
        const val TEXTURE_RANDOM_SEED = 0x13579BDF

        /** 确定性纹理线性同余乘数。 */
        const val TEXTURE_RANDOM_MULTIPLIER = 1_664_525

        /** 确定性纹理线性同余增量。 */
        const val TEXTURE_RANDOM_INCREMENT = 1_013_904_223

        /** 取线性同余状态的高位作为亮度。 */
        const val TEXTURE_RANDOM_SHIFT_BITS = 16

        /** 网格线亮度，保持高于暗像素判定阈值。 */
        const val DARK_GRID_LUMINANCE = 40

        /** 网格背景亮度，保持低于高光饱和判定阈值。 */
        const val BRIGHT_GRID_LUMINANCE = 220
    }
}

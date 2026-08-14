package roc.win.lottery.recognition

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** 图片分辨率质量闸门的边界测试。 */
class ImageDimensionQualityAnalyzerTest {
    /** 恰好达到默认长短边下限时应允许进入 OCR。 */
    @Test
    fun exactDefaultBoundaryPasses() =
        runTest {
            val result = ImageDimensionQualityAnalyzer().analyze(image(width = 720, height = 1280))

            assertIs<ImageQualityResult.Passed>(result)
        }

    /** 最短边低于默认下限时应返回可操作的质量问题。 */
    @Test
    fun shortEdgeBelowBoundaryIsRejected() =
        runTest {
            val result = ImageDimensionQualityAnalyzer().analyze(image(width = 719, height = 1800))

            val poorImage = assertIs<ImageQualityResult.PoorImage>(result)
            assertEquals(listOf("图片分辨率过低，请靠近彩票并重新拍摄"), poorImage.issues)
        }

    /** 最长边低于默认下限时不能只因短边达标而放行。 */
    @Test
    fun longEdgeBelowBoundaryIsRejected() =
        runTest {
            val result = ImageDimensionQualityAnalyzer().analyze(image(width = 900, height = 1279))

            assertIs<ImageQualityResult.PoorImage>(result)
        }

    /** 缺少采集尺寸时应明确报告检查失败。 */
    @Test
    fun missingDimensionsFailInspection() =
        runTest {
            val result = ImageDimensionQualityAnalyzer().analyze(image(width = null, height = null))

            val failure = assertIs<ImageQualityResult.Failure>(result)
            assertEquals("无法读取图片尺寸，请重新拍摄或选择图片", failure.message)
        }

    /** 创建不包含真实路径或票面内容的测试图片引用。 */
    private fun image(
        width: Int?,
        height: Int?,
    ): ImageRef =
        ImageRef(
            id = "quality-test-image",
            localPath = "memory://quality-test-image.jpg",
            mimeType = "image/jpeg",
            widthPixels = width,
            heightPixels = height,
        )
}

package roc.win.lottery.recognition

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 受尺寸限制的单通道亮度样本。
 *
 * @property widthPixels 样本宽度。
 * @property heightPixels 样本高度。
 * @property pixels 按行排列的无符号 8 位亮度；通过 `ByteArray` 避免平台装箱。
 */
data class LuminanceImageSample(
    val widthPixels: Int,
    val heightPixels: Int,
    val pixels: ByteArray,
)

/** 隔离共享指标计算与平台图片解码。 */
fun interface LuminanceImageDecoder {
    /**
     * 在本地把私有图片缩小并转换为亮度样本。
     *
     * @param imageRef 应用当前流程持有的私有图片引用。
     * @param maximumLongEdgePixels 样本允许保留的最长边。
     * @return 可分析的亮度样本；文件缺失或无法解码时返回空。
     */
    suspend fun decode(
        imageRef: ImageRef,
        maximumLongEdgePixels: Int,
    ): LuminanceImageSample?
}

/**
 * 使用固定尺寸亮度样本拒绝严重曝光异常、全局明显模糊、关键内容裁切和明显画面倾斜。
 *
 * @property decoder 只在设备本地运行的平台亮度解码器。
 * @property maximumLongEdgePixels 参与指标计算的最长边，固定尺寸可保持平台阈值一致。
 * @property minimumLaplacianVariance 允许通过的拉普拉斯方差下限。
 * @property maximumDarkMeanLuminance 判定严重过暗的平均亮度上限。
 * @property minimumDarkPixelRatio 判定严重过暗所需的暗像素比例下限。
 * @property minimumBrightMeanLuminance 判定严重过曝的平均亮度下限。
 * @property minimumBrightPixelRatio 判定严重过曝所需的高光饱和像素比例下限。
 * @property maximumAbsoluteSkewDegrees 允许通过的水平或垂直主轴最大偏角。
 * @property minimumSkewEdgePixelRatio 参与倾斜判断所需的最小强边缘像素比例。
 * @property minimumSkewCoherence 参与倾斜判断所需的最小主轴方向集中度。
 */
class PixelImageQualityAnalyzer(
    private val decoder: LuminanceImageDecoder,
    private val maximumLongEdgePixels: Int = DEFAULT_MAXIMUM_LONG_EDGE_PIXELS,
    private val minimumLaplacianVariance: Double = DEFAULT_MINIMUM_LAPLACIAN_VARIANCE,
    private val maximumDarkMeanLuminance: Double = DEFAULT_MAXIMUM_DARK_MEAN_LUMINANCE,
    private val minimumDarkPixelRatio: Double = DEFAULT_MINIMUM_DARK_PIXEL_RATIO,
    private val minimumBrightMeanLuminance: Double = DEFAULT_MINIMUM_BRIGHT_MEAN_LUMINANCE,
    private val minimumBrightPixelRatio: Double = DEFAULT_MINIMUM_BRIGHT_PIXEL_RATIO,
    private val maximumAbsoluteSkewDegrees: Double = DEFAULT_MAXIMUM_ABSOLUTE_SKEW_DEGREES,
    private val minimumSkewEdgePixelRatio: Double = DEFAULT_MINIMUM_SKEW_EDGE_PIXEL_RATIO,
    private val minimumSkewCoherence: Double = DEFAULT_MINIMUM_SKEW_COHERENCE,
) : ImageQualityAnalyzer {
    init {
        require(maximumLongEdgePixels >= MINIMUM_ANALYZABLE_EDGE_PIXELS) {
            "亮度样本最长边不得小于 $MINIMUM_ANALYZABLE_EDGE_PIXELS"
        }
        require(minimumLaplacianVariance >= 0.0) { "清晰度阈值不得小于 0" }
        require(maximumDarkMeanLuminance in MINIMUM_LUMINANCE..MAXIMUM_LUMINANCE) {
            "过暗平均亮度阈值必须位于 0 至 255"
        }
        require(minimumBrightMeanLuminance in MINIMUM_LUMINANCE..MAXIMUM_LUMINANCE) {
            "过亮平均亮度阈值必须位于 0 至 255"
        }
        require(minimumDarkPixelRatio in MINIMUM_RATIO..MAXIMUM_RATIO) { "暗像素比例阈值必须位于 0 至 1" }
        require(minimumBrightPixelRatio in MINIMUM_RATIO..MAXIMUM_RATIO) { "亮像素比例阈值必须位于 0 至 1" }
        require(maximumAbsoluteSkewDegrees in MINIMUM_AXIS_ANGLE_DEGREES..MAXIMUM_AXIS_ANGLE_DEGREES) {
            "倾斜角阈值必须位于 0 至 45 度"
        }
        require(minimumSkewEdgePixelRatio in MINIMUM_RATIO..MAXIMUM_RATIO) {
            "倾斜强边缘比例阈值必须位于 0 至 1"
        }
        require(minimumSkewCoherence in MINIMUM_RATIO..MAXIMUM_RATIO) {
            "倾斜方向集中度阈值必须位于 0 至 1"
        }
    }

    /** 解码匿名亮度样本，并按曝光、清晰度、裁切、倾斜的顺序返回可操作问题。 */
    override suspend fun analyze(imageRef: ImageRef): ImageQualityResult {
        val sample =
            decoder.decode(imageRef, maximumLongEdgePixels)
                ?: return ImageQualityResult.Failure("无法读取图片内容，请重新拍摄或选择图片")
        val metrics =
            calculateMetrics(sample)
                ?: return ImageQualityResult.Failure("图片像素数据不完整，请重新拍摄或选择图片")

        if (
            metrics.meanLuminance <= maximumDarkMeanLuminance &&
            metrics.darkPixelRatio >= minimumDarkPixelRatio
        ) {
            return ImageQualityResult.PoorImage(
                listOf("图片整体过暗，请在光线充足且均匀的环境重新拍摄"),
            )
        }
        if (
            metrics.meanLuminance >= minimumBrightMeanLuminance &&
            metrics.brightPixelRatio >= minimumBrightPixelRatio
        ) {
            return ImageQualityResult.PoorImage(
                listOf("图片整体过亮，请避开强光和反光后重新拍摄"),
            )
        }
        if (metrics.laplacianVariance < minimumLaplacianVariance) {
            return ImageQualityResult.PoorImage(
                listOf("图片明显模糊，请保持手机稳定并重新拍摄"),
            )
        }
        if (metrics.hasLikelyCroppedContent) {
            return ImageQualityResult.PoorImage(
                listOf("图片内容贴近边缘且可能被裁切，请完整拍下整张彩票"),
            )
        }
        if (
            metrics.strongEdgePixelRatio >= minimumSkewEdgePixelRatio &&
            metrics.axisCoherence >= minimumSkewCoherence &&
            abs(metrics.dominantAxisAngleDegrees) > maximumAbsoluteSkewDegrees
        ) {
            return ImageQualityResult.PoorImage(
                listOf("图片倾斜明显，请摆正彩票或手机后重新拍摄"),
            )
        }
        return ImageQualityResult.Passed
    }

    /** 在线性时间内计算曝光、清晰度、边框印刷密度及水平或垂直边缘主轴。 */
    private fun calculateMetrics(sample: LuminanceImageSample): PixelImageMetrics? {
        val width = sample.widthPixels
        val height = sample.heightPixels
        val expectedPixelCount = width.toLong() * height.toLong()
        if (
            width < MINIMUM_ANALYZABLE_EDGE_PIXELS ||
            height < MINIMUM_ANALYZABLE_EDGE_PIXELS ||
            expectedPixelCount != sample.pixels.size.toLong()
        ) {
            return null
        }

        var luminanceSum = 0L
        var darkPixelCount = 0L
        var brightPixelCount = 0L
        sample.pixels.forEach { encoded ->
            val luminance = encoded.toInt() and UNSIGNED_BYTE_MASK
            luminanceSum += luminance
            if (luminance <= DARK_PIXEL_MAXIMUM) darkPixelCount += 1
            if (luminance >= BRIGHT_PIXEL_MINIMUM) brightPixelCount += 1
        }

        var laplacianSum = 0L
        var laplacianSquareSum = 0L
        var strongEdgePixelCount = 0L
        var strongEdgeWeight = 0.0
        var axisVectorX = 0.0
        var axisVectorY = 0.0
        val cropBorderWidth =
            maxOf(MINIMUM_CROP_BORDER_WIDTH_PIXELS, (minOf(width, height) * CROP_BORDER_RATIO).roundToInt())
        val cropBorders = Array(CROP_BORDER_SIDE_COUNT) { CropBorderAccumulator() }
        for (y in 1 until height - 1) {
            val rowStart = y * width
            for (x in 1 until width - 1) {
                val index = rowStart + x
                val center = sample.pixels[index].toInt() and UNSIGNED_BYTE_MASK
                val topLeft = sample.pixels[index - width - 1].toInt() and UNSIGNED_BYTE_MASK
                val top = sample.pixels[index - width].toInt() and UNSIGNED_BYTE_MASK
                val topRight = sample.pixels[index - width + 1].toInt() and UNSIGNED_BYTE_MASK
                val left = sample.pixels[index - 1].toInt() and UNSIGNED_BYTE_MASK
                val right = sample.pixels[index + 1].toInt() and UNSIGNED_BYTE_MASK
                val bottomLeft = sample.pixels[index + width - 1].toInt() and UNSIGNED_BYTE_MASK
                val bottom = sample.pixels[index + width].toInt() and UNSIGNED_BYTE_MASK
                val bottomRight = sample.pixels[index + width + 1].toInt() and UNSIGNED_BYTE_MASK
                val laplacian =
                    top +
                        bottom +
                        left +
                        right -
                        LAPLACIAN_CENTER_WEIGHT * center
                laplacianSum += laplacian
                laplacianSquareSum += laplacian.toLong() * laplacian

                val gradientX =
                    -topLeft + topRight -
                        SOBEL_CENTER_WEIGHT * left + SOBEL_CENTER_WEIGHT * right -
                        bottomLeft + bottomRight
                val gradientY =
                    -topLeft - SOBEL_CENTER_WEIGHT * top - topRight +
                        bottomLeft + SOBEL_CENTER_WEIGHT * bottom + bottomRight
                val edgeMagnitude = abs(gradientX) + abs(gradientY)
                val isStrongEdge = edgeMagnitude >= STRONG_EDGE_MAGNITUDE_THRESHOLD
                val isInkEdge =
                    isStrongEdge &&
                        minOf(topLeft, top, topRight, left, right, bottomLeft, bottom, bottomRight) <=
                        CROP_INK_LUMINANCE_MAXIMUM &&
                        maxOf(topLeft, top, topRight, left, right, bottomLeft, bottom, bottomRight) >=
                        CROP_PAPER_LUMINANCE_MINIMUM
                if (y <= cropBorderWidth) {
                    cropBorders[CROP_TOP_SIDE].record(x, width, isStrongEdge, isInkEdge)
                }
                if (y >= height - cropBorderWidth - 1) {
                    cropBorders[CROP_BOTTOM_SIDE].record(x, width, isStrongEdge, isInkEdge)
                }
                if (x <= cropBorderWidth) {
                    cropBorders[CROP_LEFT_SIDE].record(y, height, isStrongEdge, isInkEdge)
                }
                if (x >= width - cropBorderWidth - 1) {
                    cropBorders[CROP_RIGHT_SIDE].record(y, height, isStrongEdge, isInkEdge)
                }
                if (isStrongEdge) {
                    val edgeWeight = edgeMagnitude.toDouble()
                    val gradientXDouble = gradientX.toDouble()
                    val gradientYDouble = gradientY.toDouble()
                    val gradientXSquared = gradientXDouble * gradientXDouble
                    val gradientYSquared = gradientYDouble * gradientYDouble
                    val squaredMagnitude = gradientXSquared + gradientYSquared
                    val fourthPowerMagnitude = squaredMagnitude * squaredMagnitude
                    strongEdgePixelCount += 1
                    strongEdgeWeight += edgeWeight
                    // 单位梯度向量的四次幂会合并相差 90 度的边缘，且无需逐像素调用三角函数。
                    axisVectorX +=
                        (
                            gradientXSquared * gradientXSquared -
                                FOURTH_ANGLE_MIXED_SQUARE_WEIGHT * gradientXSquared * gradientYSquared +
                                gradientYSquared * gradientYSquared
                        ) / fourthPowerMagnitude * edgeWeight
                    axisVectorY +=
                        FOURTH_ANGLE_CROSS_WEIGHT *
                        gradientXDouble * gradientYDouble *
                        (gradientXSquared - gradientYSquared) /
                        fourthPowerMagnitude * edgeWeight
                }
            }
        }
        val pixelCount = sample.pixels.size.toDouble()
        val laplacianCount = ((width - 2) * (height - 2)).toDouble()
        val laplacianMean = laplacianSum / laplacianCount
        val dominantAxisAngleDegrees =
            if (strongEdgeWeight == 0.0) {
                0.0
            } else {
                atan2(axisVectorY, axisVectorX) /
                    AXIS_EQUIVALENCE_MULTIPLIER * RADIANS_TO_DEGREES
            }
        val axisCoherence =
            if (strongEdgeWeight == 0.0) {
                0.0
            } else {
                sqrt(axisVectorX * axisVectorX + axisVectorY * axisVectorY) / strongEdgeWeight
            }
        val hasLikelyCroppedContent =
            axisCoherence >= DEFAULT_MINIMUM_CROP_AXIS_COHERENCE &&
                cropBorders.any(CropBorderAccumulator::indicatesLikelyCrop)
        return PixelImageMetrics(
            meanLuminance = luminanceSum / pixelCount,
            darkPixelRatio = darkPixelCount / pixelCount,
            brightPixelRatio = brightPixelCount / pixelCount,
            laplacianVariance = laplacianSquareSum / laplacianCount - laplacianMean * laplacianMean,
            strongEdgePixelRatio = strongEdgePixelCount / pixelCount,
            dominantAxisAngleDegrees = dominantAxisAngleDegrees,
            axisCoherence = axisCoherence,
            hasLikelyCroppedContent = hasLikelyCroppedContent,
        )
    }

    /**
     * 单侧画面边框的可变统计器。
     *
     * @property segmentPixelCounts 各连续边框段的有效像素数。
     * @property segmentInkEdgeCounts 各连续边框段的黑白印刷边缘数。
     */
    private class CropBorderAccumulator {
        /** 当前侧边框带的有效像素总数。 */
        private var pixelCount = 0L

        /** 当前侧边框带的强 Sobel 边缘总数。 */
        private var strongEdgeCount = 0L

        /** 当前侧边框带的黑白印刷边缘总数。 */
        private var inkEdgeCount = 0L

        /** 各连续边框段的有效像素数。 */
        private val segmentPixelCounts = LongArray(CROP_BORDER_SEGMENT_COUNT)

        /** 各连续边框段的黑白印刷边缘数。 */
        private val segmentInkEdgeCounts = LongArray(CROP_BORDER_SEGMENT_COUNT)

        /** 记录一个边框像素及其所属连续段。 */
        fun record(
            coordinate: Int,
            fullLength: Int,
            isStrongEdge: Boolean,
            isInkEdge: Boolean,
        ) {
            val segmentIndex =
                (coordinate * CROP_BORDER_SEGMENT_COUNT / fullLength)
                    .coerceIn(0, CROP_BORDER_SEGMENT_COUNT - 1)
            pixelCount += 1
            segmentPixelCounts[segmentIndex] += 1
            if (isStrongEdge) strongEdgeCount += 1
            if (isInkEdge) {
                inkEdgeCount += 1
                segmentInkEdgeCounts[segmentIndex] += 1
            }
        }

        /** 根据同侧整体密度和连续段密度判断关键印刷内容是否可能被切断。 */
        fun indicatesLikelyCrop(): Boolean {
            if (pixelCount == 0L) return false
            val strongEdgeRatio = strongEdgeCount.toDouble() / pixelCount
            val inkEdgeRatio = inkEdgeCount.toDouble() / pixelCount
            var maximumSegmentInkEdgeRatio = 0.0
            var denseSegmentCount = 0
            segmentPixelCounts.indices.forEach { index ->
                val segmentPixelCount = segmentPixelCounts[index]
                if (segmentPixelCount > 0L) {
                    val segmentInkEdgeRatio = segmentInkEdgeCounts[index].toDouble() / segmentPixelCount
                    maximumSegmentInkEdgeRatio = maxOf(maximumSegmentInkEdgeRatio, segmentInkEdgeRatio)
                    if (segmentInkEdgeRatio >= MINIMUM_CROP_DENSE_SEGMENT_INK_EDGE_RATIO) {
                        denseSegmentCount += 1
                    }
                }
            }
            return strongEdgeRatio >= MINIMUM_CROP_STRONG_EDGE_RATIO &&
                inkEdgeRatio >= MINIMUM_CROP_INK_EDGE_RATIO &&
                maximumSegmentInkEdgeRatio >= MINIMUM_CROP_SEGMENT_INK_EDGE_RATIO &&
                denseSegmentCount >= MINIMUM_CROP_DENSE_SEGMENT_COUNT
        }
    }

    /** 像素质量分析的固定阈值。 */
    private companion object {
        /** 固定分析长边，在清晰度和移动端开销之间保持平衡。 */
        const val DEFAULT_MAXIMUM_LONG_EDGE_PIXELS = 512

        /** 13 张真实票样最低值约为 788，半径 2 的明显模糊变体最高约为 22。 */
        const val DEFAULT_MINIMUM_LAPLACIAN_VARIANCE = 75.0

        /** 严重欠曝变体均低于 29，正常样本最低约为 111。 */
        const val DEFAULT_MAXIMUM_DARK_MEAN_LUMINANCE = 40.0

        /** 结合平均亮度限制，避免少量黑色文字触发欠曝。 */
        const val DEFAULT_MINIMUM_DARK_PIXEL_RATIO = 0.35

        /** 只拒绝整体已经接近纯白的严重过曝图片。 */
        const val DEFAULT_MINIMUM_BRIGHT_MEAN_LUMINANCE = 235.0

        /** 要求至少 85% 像素进入高光饱和区，降低白色票面的误拒风险。 */
        const val DEFAULT_MINIMUM_BRIGHT_PIXEL_RATIO = 0.85

        /** 根目录探索图最大主轴偏差约 7.1 度，20 度旋转变体最小约 16 度。 */
        const val DEFAULT_MAXIMUM_ABSOLUTE_SKEW_DEGREES = 12.0

        /** 边缘不足时不推断倾斜，避免由单个物体或噪点触发。 */
        const val DEFAULT_MINIMUM_SKEW_EDGE_PIXEL_RATIO = 0.05

        /** 主轴方向不集中时不推断倾斜，避免复杂背景主导结果。 */
        const val DEFAULT_MINIMUM_SKEW_COHERENCE = 0.18

        /** 裁切判断同样要求稳定票面主轴，避免无序背景纹理触发。 */
        const val DEFAULT_MINIMUM_CROP_AXIS_COHERENCE = 0.18

        /** 只分析靠近画面四侧的 6% 区域，完整票样在该范围内均保留安全背景。 */
        const val CROP_BORDER_RATIO = 0.06

        /** 极小测试样本也至少保留一个像素宽的边框统计带。 */
        const val MINIMUM_CROP_BORDER_WIDTH_PIXELS = 1

        /** 上、下、左、右四个独立边框统计器。 */
        const val CROP_BORDER_SIDE_COUNT = 4

        /** 上边框统计器索引。 */
        const val CROP_TOP_SIDE = 0

        /** 下边框统计器索引。 */
        const val CROP_BOTTOM_SIDE = 1

        /** 左边框统计器索引。 */
        const val CROP_LEFT_SIDE = 2

        /** 右边框统计器索引。 */
        const val CROP_RIGHT_SIDE = 3

        /** 每侧分成固定段数，避免单个背景物体形成裁切结论。 */
        const val CROP_BORDER_SEGMENT_COUNT = 12

        /** 被认定为印刷黑白过渡时允许的局部暗部最高亮度。 */
        const val CROP_INK_LUMINANCE_MAXIMUM = 110

        /** 被认定为印刷黑白过渡时要求的局部纸面最低亮度。 */
        const val CROP_PAPER_LUMINANCE_MINIMUM = 175

        /** 疑似裁切侧至少需要 6% 像素属于强 Sobel 边缘。 */
        const val MINIMUM_CROP_STRONG_EDGE_RATIO = 0.06

        /** 疑似裁切侧至少需要 2% 像素属于黑白印刷边缘。 */
        const val MINIMUM_CROP_INK_EDGE_RATIO = 0.02

        /** 单个连续边框段至少需要 18% 黑白印刷边缘。 */
        const val MINIMUM_CROP_SEGMENT_INK_EDGE_RATIO = 0.18

        /** 边框段达到 8% 黑白印刷边缘时计为高密度段。 */
        const val MINIMUM_CROP_DENSE_SEGMENT_INK_EDGE_RATIO = 0.08

        /** 至少两个高密度段才能判定关键印刷内容延伸到画面边缘。 */
        const val MINIMUM_CROP_DENSE_SEGMENT_COUNT = 2

        /** 亮度样本每条边至少需要三个像素才能计算拉普拉斯。 */
        const val MINIMUM_ANALYZABLE_EDGE_PIXELS = 3

        /** 8 位无符号亮度的最低值。 */
        const val MINIMUM_LUMINANCE = 0.0

        /** 8 位无符号亮度的最高值。 */
        const val MAXIMUM_LUMINANCE = 255.0

        /** 比例的最低值。 */
        const val MINIMUM_RATIO = 0.0

        /** 比例的最高值。 */
        const val MAXIMUM_RATIO = 1.0

        /** 水平或垂直主轴允许表示的最小偏角。 */
        const val MINIMUM_AXIS_ANGLE_DEGREES = 0.0

        /** 水平或垂直主轴的最大无歧义偏角。 */
        const val MAXIMUM_AXIS_ANGLE_DEGREES = 45.0

        /** 认定暗像素的最大亮度。 */
        const val DARK_PIXEL_MAXIMUM = 30

        /** 认定高光饱和像素的最小亮度。 */
        const val BRIGHT_PIXEL_MINIMUM = 245

        /** 把有符号字节还原为无符号亮度的掩码。 */
        const val UNSIGNED_BYTE_MASK = 0xFF

        /** 四邻域离散拉普拉斯的中心权重。 */
        const val LAPLACIAN_CENTER_WEIGHT = 4

        /** Sobel 核心行列的整数权重。 */
        const val SOBEL_CENTER_WEIGHT = 2

        /** 只使用足够稳定的 Sobel 边缘估计主轴。 */
        const val STRONG_EDGE_MAGNITUDE_THRESHOLD = 160

        /** 四倍角把相差 90 度的水平和垂直边缘映射到同一方向。 */
        const val AXIS_EQUIVALENCE_MULTIPLIER = 4.0

        /** 四倍角余弦展开式的混合平方项权重。 */
        const val FOURTH_ANGLE_MIXED_SQUARE_WEIGHT = 6.0

        /** 四倍角正弦展开式的交叉项权重。 */
        const val FOURTH_ANGLE_CROSS_WEIGHT = 4.0

        /** 弧度转换为角度的比例。 */
        const val RADIANS_TO_DEGREES = 180.0 / PI
    }
}

/**
 * 不包含路径或图片内容的像素质量指标。
 *
 * @property meanLuminance 平均亮度。
 * @property darkPixelRatio 暗像素比例。
 * @property brightPixelRatio 高光饱和像素比例。
 * @property laplacianVariance 拉普拉斯方差。
 * @property strongEdgePixelRatio 强 Sobel 边缘像素比例。
 * @property dominantAxisAngleDegrees 水平或垂直主轴的带符号偏角。
 * @property axisCoherence 主轴方向集中度，范围为 0 至 1。
 * @property hasLikelyCroppedContent 是否有连续关键印刷内容延伸到画面边缘。
 */
private data class PixelImageMetrics(
    val meanLuminance: Double,
    val darkPixelRatio: Double,
    val brightPixelRatio: Double,
    val laplacianVariance: Double,
    val strongEdgePixelRatio: Double,
    val dominantAxisAngleDegrees: Double,
    val axisCoherence: Double,
    val hasLikelyCroppedContent: Boolean,
)

package roc.win.lottery.recognition

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
 * 使用固定尺寸亮度样本拒绝严重曝光异常和全局明显模糊。
 *
 * @property decoder 只在设备本地运行的平台亮度解码器。
 * @property maximumLongEdgePixels 参与指标计算的最长边，固定尺寸可保持平台阈值一致。
 * @property minimumLaplacianVariance 允许通过的拉普拉斯方差下限。
 * @property maximumDarkMeanLuminance 判定严重过暗的平均亮度上限。
 * @property minimumDarkPixelRatio 判定严重过暗所需的暗像素比例下限。
 * @property minimumBrightMeanLuminance 判定严重过曝的平均亮度下限。
 * @property minimumBrightPixelRatio 判定严重过曝所需的高光饱和像素比例下限。
 */
class PixelImageQualityAnalyzer(
    private val decoder: LuminanceImageDecoder,
    private val maximumLongEdgePixels: Int = DEFAULT_MAXIMUM_LONG_EDGE_PIXELS,
    private val minimumLaplacianVariance: Double = DEFAULT_MINIMUM_LAPLACIAN_VARIANCE,
    private val maximumDarkMeanLuminance: Double = DEFAULT_MAXIMUM_DARK_MEAN_LUMINANCE,
    private val minimumDarkPixelRatio: Double = DEFAULT_MINIMUM_DARK_PIXEL_RATIO,
    private val minimumBrightMeanLuminance: Double = DEFAULT_MINIMUM_BRIGHT_MEAN_LUMINANCE,
    private val minimumBrightPixelRatio: Double = DEFAULT_MINIMUM_BRIGHT_PIXEL_RATIO,
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
    }

    /** 解码匿名亮度样本，并按曝光优先、清晰度其次的顺序返回可操作问题。 */
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
        return ImageQualityResult.Passed
    }

    /** 在线性时间内计算平均亮度、极端像素比例和离散拉普拉斯方差。 */
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
        for (y in 1 until height - 1) {
            val rowStart = y * width
            for (x in 1 until width - 1) {
                val index = rowStart + x
                val center = sample.pixels[index].toInt() and UNSIGNED_BYTE_MASK
                val laplacian =
                    (sample.pixels[index - width].toInt() and UNSIGNED_BYTE_MASK) +
                        (sample.pixels[index + width].toInt() and UNSIGNED_BYTE_MASK) +
                        (sample.pixels[index - 1].toInt() and UNSIGNED_BYTE_MASK) +
                        (sample.pixels[index + 1].toInt() and UNSIGNED_BYTE_MASK) -
                        LAPLACIAN_CENTER_WEIGHT * center
                laplacianSum += laplacian
                laplacianSquareSum += laplacian.toLong() * laplacian
            }
        }
        val pixelCount = sample.pixels.size.toDouble()
        val laplacianCount = ((width - 2) * (height - 2)).toDouble()
        val laplacianMean = laplacianSum / laplacianCount
        return PixelImageMetrics(
            meanLuminance = luminanceSum / pixelCount,
            darkPixelRatio = darkPixelCount / pixelCount,
            brightPixelRatio = brightPixelCount / pixelCount,
            laplacianVariance = laplacianSquareSum / laplacianCount - laplacianMean * laplacianMean,
        )
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

        /** 认定暗像素的最大亮度。 */
        const val DARK_PIXEL_MAXIMUM = 30

        /** 认定高光饱和像素的最小亮度。 */
        const val BRIGHT_PIXEL_MINIMUM = 245

        /** 把有符号字节还原为无符号亮度的掩码。 */
        const val UNSIGNED_BYTE_MASK = 0xFF

        /** 四邻域离散拉普拉斯的中心权重。 */
        const val LAPLACIAN_CENTER_WEIGHT = 4
    }
}

/**
 * 不包含路径或图片内容的像素质量指标。
 *
 * @property meanLuminance 平均亮度。
 * @property darkPixelRatio 暗像素比例。
 * @property brightPixelRatio 高光饱和像素比例。
 * @property laplacianVariance 拉普拉斯方差。
 */
private data class PixelImageMetrics(
    val meanLuminance: Double,
    val darkPixelRatio: Double,
    val brightPixelRatio: Double,
    val laplacianVariance: Double,
)

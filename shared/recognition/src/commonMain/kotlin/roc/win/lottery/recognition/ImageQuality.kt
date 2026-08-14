package roc.win.lottery.recognition

/** 图片进入 OCR 前的本地质量检查结果。 */
sealed interface ImageQualityResult {
    /** 当前已实现的质量规则均已通过。 */
    data object Passed : ImageQualityResult

    /**
     * 图片质量不足，需要用户重新拍摄或选择。
     *
     * @property issues 面向用户的可操作问题列表。
     */
    data class PoorImage(
        val issues: List<String>,
    ) : ImageQualityResult

    /**
     * 无法完成图片质量检查。
     *
     * @property message 不包含图片路径或票面内容的失败说明。
     */
    data class Failure(
        val message: String,
    ) : ImageQualityResult
}

/** 隔离共享分析流程和各类本地图片质量实现。 */
fun interface ImageQualityAnalyzer {
    /**
     * 在 OCR 前检查应用私有图片，不得上传或持久化分析结果。
     *
     * @param imageRef 已完成方向归一化的临时图片引用。
     * @return 通过、质量不足或检查失败状态。
     */
    suspend fun analyze(imageRef: ImageRef): ImageQualityResult
}

/**
 * 使用归一化图片尺寸执行第一道确定性质量检查。
 *
 * @property minimumShortEdgePixels 允许进入 OCR 的最短边像素下限。
 * @property minimumLongEdgePixels 允许进入 OCR 的最长边像素下限。
 */
class ImageDimensionQualityAnalyzer(
    private val minimumShortEdgePixels: Int = DEFAULT_MINIMUM_SHORT_EDGE_PIXELS,
    private val minimumLongEdgePixels: Int = DEFAULT_MINIMUM_LONG_EDGE_PIXELS,
) : ImageQualityAnalyzer {
    init {
        require(minimumShortEdgePixels > 0) { "最短边阈值必须大于 0" }
        require(minimumLongEdgePixels >= minimumShortEdgePixels) { "最长边阈值不得小于最短边阈值" }
    }

    /** 根据采集阶段记录的归一化尺寸拒绝明显过小图片。 */
    override suspend fun analyze(imageRef: ImageRef): ImageQualityResult {
        val width = imageRef.widthPixels
        val height = imageRef.heightPixels
        if (width == null || height == null || width <= 0 || height <= 0) {
            return ImageQualityResult.Failure("无法读取图片尺寸，请重新拍摄或选择图片")
        }
        val shortEdge = minOf(width, height)
        val longEdge = maxOf(width, height)
        if (shortEdge < minimumShortEdgePixels || longEdge < minimumLongEdgePixels) {
            return ImageQualityResult.PoorImage(
                listOf("图片分辨率过低，请靠近彩票并重新拍摄"),
            )
        }
        return ImageQualityResult.Passed
    }

    /** PoC 阶段的保守尺寸下限，后续必须结合真实条件样本重新校准。 */
    private companion object {
        /** 默认最短边下限，对应 720p 图像的短边。 */
        const val DEFAULT_MINIMUM_SHORT_EDGE_PIXELS = 720

        /** 默认最长边下限，对应 720p 图像的长边。 */
        const val DEFAULT_MINIMUM_LONG_EDGE_PIXELS = 1280
    }
}

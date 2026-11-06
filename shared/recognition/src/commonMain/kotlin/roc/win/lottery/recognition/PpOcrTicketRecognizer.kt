package roc.win.lottery.recognition

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.max

/** 使用 PP-OCR 检测、方向分类与识别模型执行跨平台本地票面识别。 */
internal class PpOcrTicketRecognizer(
    /** 平台 RGB 图片解码器。 */
    private val imageDecoder: PpOcrRgbImageDecoder,
    /** 平台 ONNX Runtime 执行器。 */
    private val runtime: PpOcrOnnxRuntime,
    /** 与识别模型严格同版本的字符字典。 */
    private val characters: List<String>,
    /** 与当前模型版本绑定的字典规模和检测参数。 */
    private val config: PpOcrPipelineConfig = PpOcrPipelineConfig(),
) : ProgressiveTicketRecognizer {
    init {
        require(characters.size == config.characterCount) { "PP-OCR 字符字典不完整" }
    }

    /** 解码私有图片并依次执行检测、方向分类与 CTC 识别，同时报告阶段进度。 */
    override suspend fun recognize(
        imageRef: ImageRef,
        onProgress: (RecognitionProgress) -> Unit,
    ): RecognitionResult =
        withContext(Dispatchers.Default) {
            try {
                onProgress(RecognitionProgress(INITIAL_PROGRESS, "正在初始化本地识别"))
                val decoded =
                    imageDecoder.decode(imageRef, MAXIMUM_DECODED_LONG_EDGE)
                        ?: run {
                            onProgress(RecognitionProgress(COMPLETED_PROGRESS, "本地图片读取失败"))
                            return@withContext RecognitionResult.Failure("无法读取本地图片，请重新导入")
                        }
                onProgress(RecognitionProgress(DECODED_PROGRESS, "正在检测票面文字"))
                val page = detectUprightPage(decoded)
                if (page.quarterTurns != 0) {
                    onProgress(RecognitionProgress(DECODED_PROGRESS, "已自动转正票面方向"))
                }
                val image = page.image
                val boxes = page.boxes
                if (boxes.isEmpty()) {
                    onProgress(RecognitionProgress(COMPLETED_PROGRESS, "未检测到清晰票面文字"))
                    return@withContext RecognitionResult.PoorImage(
                        listOf("未检测到清晰票面文字，请正对彩票并避免反光"),
                    )
                }
                onProgress(RecognitionProgress(DETECTION_COMPLETED_PROGRESS, "已定位 ${boxes.size} 个文字区域"))
                val regions =
                    buildList {
                        boxes.forEachIndexed { index, box ->
                            // 页面取消后在下一个文字框前停止，不再继续占用 CPU 和推理会话。
                            ensureActive()
                            recognizeTextBox(image, box)?.let { line -> add(PpOcrRecognizedRegion(box, line)) }
                            val completedBoxCount = index + 1
                            val fraction =
                                DETECTION_COMPLETED_PROGRESS +
                                    BOX_RECOGNITION_PROGRESS_SPAN * completedBoxCount / boxes.size.toFloat()
                            onProgress(
                                RecognitionProgress(
                                    fraction = fraction,
                                    message = "正在读取票面文字 $completedBoxCount/${boxes.size}",
                                ),
                            )
                        }
                    }
                val allowSeverelyDegradedFragments = regions.isLikelyLegacyNumericTicket
                val lines =
                    PpOcrBetRowRefiner
                        .refine(
                            regions = regions,
                            recognizeMergedRow = { mergedBox ->
                                ensureActive()
                                recognizeMergedTextBoxCandidates(image = image, box = mergedBox)
                            },
                            // 多语言识别模型已覆盖英文和数字，不再用第二个模型重试同一批裁图。
                            recognizeLatinMergedRow = null,
                            allowCropConsensus = true,
                            allowSeverelyDegradedFragments = allowSeverelyDegradedFragments,
                            onRefinementProgress = { completedRows, totalRows ->
                                val rowFraction =
                                    if (totalRows == 0) {
                                        1f
                                    } else {
                                        completedRows / totalRows.toFloat()
                                    }
                                val fraction =
                                    ROW_REFINEMENT_START_PROGRESS + ROW_REFINEMENT_PROGRESS_SPAN * rowFraction
                                val message =
                                    if (totalRows == 0) {
                                        "投注号码无需二次校准"
                                    } else {
                                        "正在校准投注号码 $completedRows/$totalRows"
                                    }
                                onProgress(RecognitionProgress(fraction, message))
                            },
                        ).sortedWith(compareBy<OcrTextLine> { it.bounds.top }.thenBy { it.bounds.left })
                onProgress(RecognitionProgress(RESULT_ASSEMBLY_PROGRESS, "正在整理识别结果"))
                val result =
                    if (lines.isEmpty()) {
                        RecognitionResult.PoorImage(listOf("检测到票面区域，但文字不够清晰，请重新对焦拍摄"))
                    } else {
                        RecognitionResult.Success(
                            OcrDocument(
                                imageId = imageRef.id,
                                lines = lines,
                                engineName = config.engineName,
                                pageQuarterTurns = page.quarterTurns,
                            ),
                        )
                    }
                onProgress(RecognitionProgress(COMPLETED_PROGRESS, "票面文字识别完成"))
                result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                RecognitionResult.Failure("本地识别未能完成，请重新导入图片")
            }
        }

    /**
     * 检测文字并校正整页朝向。
     *
     * 竖向文字明显占优时，整页顺时针转 90 度后重新检测，转后横向文字更占优才采用；再抽样最长的横向文字框做方向
     * 分类，过半判为倒置时整页转 180 度后重新检测。逐框方向分类只能把单行文字转正，不能恢复整页的上下阅读顺序。
     */
    private fun detectUprightPage(source: RgbImage): UprightPage {
        var image = source
        var quarterTurns = 0
        var regions = detectTextRegions(image)
        val dominance = regions.verticalDominance()
        if (regions.count { box -> !box.isHorizontalDirection } >= MINIMUM_SIDEWAYS_BOX_COUNT &&
            dominance > SIDEWAYS_VERTICAL_DOMINANCE
        ) {
            val rotated = image.rotatedClockwise()
            val rotatedRegions = detectTextRegions(rotated)
            // 转过之后竖向占比应下降；否则原判断有误，保留原图，避免把朝上的票面转成横置。
            if (rotatedRegions.verticalDominance() < dominance) {
                image = rotated
                quarterTurns = 1
                regions = rotatedRegions
            }
        }
        var boxes = PpOcrDetectionPostProcessor.arrange(regions)
        if (looksUpsideDown(image, boxes)) {
            image = image.rotated180()
            quarterTurns += 2
            boxes = PpOcrDetectionPostProcessor.arrange(detectTextRegions(image))
        }
        return UprightPage(image, boxes, quarterTurns)
    }

    /**
     * 竖向文字相对横向文字的占比：两类文字框长边平方和之比。
     *
     * 平方加权突出整行文字，横置票面拆出的大量短碎片不会掩盖整行的方向。
     */
    private fun List<SourceTextBox>.verticalDominance(): Double {
        val (horizontal, vertical) = partition(SourceTextBox::isHorizontalDirection)

        fun List<SourceTextBox>.squaredLength() = sumOf { box -> box.longEdgeLength.toDouble() * box.longEdgeLength }
        return vertical.squaredLength() / horizontal.squaredLength().coerceAtLeast(1.0)
    }

    /** 抽样最长的横向文字框做方向分类，过半判为倒置时认为整页倒置。 */
    private fun looksUpsideDown(
        image: RgbImage,
        boxes: List<SourceTextBox>,
    ): Boolean {
        val samples =
            boxes
                .filter(SourceTextBox::isHorizontalDirection)
                .sortedByDescending(SourceTextBox::longEdgeLength)
                .take(ORIENTATION_SAMPLE_COUNT)
                .map(image::perspectiveCrop)
                .filter { crop -> crop.width >= MINIMUM_CROP_SIDE && crop.height >= MINIMUM_CROP_SIDE }
        return samples.count(::isUpsideDown) * 2 > samples.size
    }

    /** 缩放整图并执行 DB 文本检测，返回保留原始方向的文字框。 */
    private fun detectTextRegions(image: RgbImage): List<SourceTextBox> {
        val detection = config.detection
        val (inputWidth, inputHeight) = detectionInputSize(image, detection.longEdge, detection.upscaleSmallImages)
        val resized = image.resized(inputWidth, inputHeight)
        val input = resized.toNormalizedBgrNchw(IMAGENET_MEANS, IMAGENET_STANDARD_DEVIATIONS)
        val output =
            PpOcrInferenceDiagnostics.measure(PpOcrModel.DETECTION) {
                runtime.run(
                    model = PpOcrModel.DETECTION,
                    input = input,
                    inputShape = intArrayOf(1, COLOR_CHANNEL_COUNT, inputHeight, inputWidth),
                )
            }
        return PpOcrDetectionPostProcessor.extractRegions(output, image.width, image.height, detection)
    }

    /** 裁切单个旋转框，修正方向并执行文字识别。 */
    private fun recognizeTextBox(
        image: RgbImage,
        box: SourceTextBox,
    ): OcrTextLine? {
        val crop = image.perspectiveCrop(box)
        if (crop.width < MINIMUM_CROP_SIDE || crop.height < MINIMUM_CROP_SIDE) return null
        val oriented = orientCrop(box, crop)
        val decoded = recognizeLine(oriented) ?: return null
        if (decoded.confidence < MINIMUM_RECOGNITION_CONFIDENCE) return null
        return OcrTextLine(
            text = decoded.text,
            bounds = box.normalizedBounds(image.width, image.height),
            confidence = minOf(box.confidence, decoded.confidence),
        )
    }

    /** 对整条投注行分别识别原色和局部灰度增强裁图，交由结构约束判断是否一致。 */
    private fun recognizeMergedTextBoxCandidates(
        image: RgbImage,
        box: SourceTextBox,
    ): List<OcrTextLine> {
        val crop = image.perspectiveCrop(box)
        if (crop.width < MINIMUM_CROP_SIDE || crop.height < MINIMUM_CROP_SIDE) return emptyList()
        val oriented = orientCrop(box, crop)
        val variants =
            listOf(
                oriented,
                oriented.contrastStretchedGrayscale(),
            )
        return variants
            .distinctBy(RgbImage::pixels)
            .mapNotNull { variant ->
                val decoded = recognizeLine(variant) ?: return@mapNotNull null
                if (decoded.confidence < MINIMUM_RECOGNITION_CONFIDENCE) return@mapNotNull null
                OcrTextLine(
                    text = decoded.text,
                    bounds = box.normalizedBounds(image.width, image.height),
                    confidence = minOf(box.confidence, decoded.confidence),
                )
            }
    }

    /** 整页已转正，横向文字行直接使用；竖向文字行仍逐个判断是否倒置。 */
    private fun orientCrop(
        box: SourceTextBox,
        crop: RgbImage,
    ): RgbImage = if (box.isHorizontalDirection || !isUpsideDown(crop)) crop else crop.rotated180()

    /** 使用固定 160x80 分类模型判断文字行是否倒置。 */
    private fun isUpsideDown(image: RgbImage): Boolean {
        val resized = image.resized(ORIENTATION_WIDTH, ORIENTATION_HEIGHT)
        val input = resized.toNormalizedBgrNchw(IMAGENET_MEANS, IMAGENET_STANDARD_DEVIATIONS)
        val output =
            PpOcrInferenceDiagnostics.measure(PpOcrModel.ORIENTATION) {
                runtime.run(
                    model = PpOcrModel.ORIENTATION,
                    input = input,
                    inputShape = intArrayOf(1, COLOR_CHANNEL_COUNT, ORIENTATION_HEIGHT, ORIENTATION_WIDTH),
                )
            }
        if (output.values.size < ORIENTATION_CLASS_COUNT) return false
        val uprightScore = output.values[0]
        val upsideDownScore = output.values[1]
        return upsideDownScore > uprightScore && upsideDownScore >= ORIENTATION_ROTATE_THRESHOLD
    }

    /** 按官方最小宽高比生成动态宽度输入并执行 CTC 解码。 */
    private fun recognizeLine(image: RgbImage): PpOcrCtcDecoder.DecodedText? {
        val sourceRatio = image.width.toFloat() / image.height.toFloat()
        val targetRatio = max(MINIMUM_RECOGNITION_RATIO, sourceRatio)
        val targetWidth =
            ceil(RECOGNITION_HEIGHT * targetRatio)
                .toInt()
                .coerceAtMost(MAXIMUM_RECOGNITION_WIDTH)
        val resizedWidth =
            ceil(RECOGNITION_HEIGHT * sourceRatio)
                .toInt()
                .coerceIn(1, targetWidth)
        val resized = image.resized(resizedWidth, RECOGNITION_HEIGHT)
        val input = FloatArray(COLOR_CHANNEL_COUNT * RECOGNITION_HEIGHT * targetWidth)
        val normalized = resized.toNormalizedBgrNchw(HALF_MEANS, HALF_STANDARD_DEVIATIONS)
        val resizedPlane = resizedWidth * RECOGNITION_HEIGHT
        val targetPlane = targetWidth * RECOGNITION_HEIGHT
        for (channel in 0 until COLOR_CHANNEL_COUNT) {
            for (row in 0 until RECOGNITION_HEIGHT) {
                normalized.copyInto(
                    destination = input,
                    destinationOffset = channel * targetPlane + row * targetWidth,
                    startIndex = channel * resizedPlane + row * resizedWidth,
                    endIndex = channel * resizedPlane + (row + 1) * resizedWidth,
                )
            }
        }
        val output =
            PpOcrInferenceDiagnostics.measure(PpOcrModel.RECOGNITION) {
                runtime.runCtc(
                    model = PpOcrModel.RECOGNITION,
                    input = input,
                    inputShape = intArrayOf(1, COLOR_CHANNEL_COUNT, RECOGNITION_HEIGHT, targetWidth),
                )
            }
        return PpOcrCtcDecoder.decode(output, characters)
    }

    /** 判断初次单框结果是否具备旧式 A-E 行标或逐行一倍标记。 */
    private val List<PpOcrRecognizedRegion>.isLikelyLegacyNumericTicket: Boolean
        get() {
            val compactTexts = map { region -> region.line.text.filterNot(Char::isWhitespace) }
            val rowMarkerCount = compactTexts.count { text -> LEGACY_ROW_MARKER_REGEX.containsMatchIn(text) }
            val multiplierMarkerCount = compactTexts.count { text -> LEGACY_MULTIPLIER_REGEX.containsMatchIn(text) }
            val hasDoubleColorBallTitle = compactTexts.any { text -> DOUBLE_COLOR_BALL_TITLE in text }
            return rowMarkerCount >= MINIMUM_LEGACY_MARKER_COUNT ||
                multiplierMarkerCount >= MINIMUM_LEGACY_MARKER_COUNT ||
                (hasDoubleColorBallTitle && multiplierMarkerCount > 0)
        }

    /** PP-OCR 官方预处理参数与移动端保护限制。 */
    private companion object {
        /** 进入共享识别器后的初始进度。 */
        const val INITIAL_PROGRESS = 0.01f

        /** 图片完成解码后的进度。 */
        const val DECODED_PROGRESS = 0.08f

        /** 文字检测完成后的进度。 */
        const val DETECTION_COMPLETED_PROGRESS = 0.18f

        /** 单框文字识别占 OCR 总进度的比例。 */
        const val BOX_RECOGNITION_PROGRESS_SPAN = 0.42f

        /** 投注行二次校准开始时的进度。 */
        const val ROW_REFINEMENT_START_PROGRESS = 0.60f

        /** 投注行二次校准占 OCR 总进度的比例。 */
        const val ROW_REFINEMENT_PROGRESS_SPAN = 0.34f

        /** 组装最终 OCR 文档时的进度。 */
        const val RESULT_ASSEMBLY_PROGRESS = 0.98f

        /** OCR 阶段完成进度。 */
        const val COMPLETED_PROGRESS = 1f

        /** 保留给文字裁切的解码原图最长边。 */
        const val MAXIMUM_DECODED_LONG_EDGE = 2_048

        /** RGB 或 BGR 通道数量。 */
        const val COLOR_CHANNEL_COUNT = 3

        /** 方向分类输入宽度。 */
        const val ORIENTATION_WIDTH = 160

        /** 方向分类输入高度。 */
        const val ORIENTATION_HEIGHT = 80

        /** 方向分类类别数量。 */
        const val ORIENTATION_CLASS_COUNT = 2

        /** 判断整页倒置时抽样分类的最长横向文字框数量。 */
        const val ORIENTATION_SAMPLE_COUNT = 8

        /** 判断整页横置至少需要的竖向文字框数量，避免文字很少时误判。 */
        const val MINIMUM_SIDEWAYS_BOX_COUNT = 3

        /**
         * 竖向文字占比超过该值时尝试把整页转 90 度。
         *
         * 真实票样中朝上票面最高 0.34，横置票面最低 1.05；转后还要校验竖向占比下降才采用。
         */
        const val SIDEWAYS_VERTICAL_DOMINANCE = 0.5

        /** 触发 180 度旋转的最低概率。 */
        const val ORIENTATION_ROTATE_THRESHOLD = 0.8f

        /** 识别模型固定输入高度。 */
        const val RECOGNITION_HEIGHT = 48

        /** 官方默认识别宽高比 320/48。 */
        const val MINIMUM_RECOGNITION_RATIO = 320f / 48f

        /** 防止异常长框造成过高峰值内存。 */
        const val MAXIMUM_RECOGNITION_WIDTH = 1_600

        /** 小于该尺寸的检测框不进入分类和识别。 */
        const val MINIMUM_CROP_SIDE = 3

        /** 低于该平均字符概率的文字行不进入业务解析。 */
        const val MINIMUM_RECOGNITION_CONFIDENCE = 0.25f

        /** 单类旧式票面标记达到该数量后启用英文识别兜底。 */
        const val MINIMUM_LEGACY_MARKER_COUNT = 2

        /** 旧式双色球标题关键字。 */
        const val DOUBLE_COLOR_BALL_TITLE = "双色球"

        /** 匹配旧式票面 A-E 投注行标。 */
        val LEGACY_ROW_MARKER_REGEX = Regex("^[A-E](?:[.:：．。])?")

        /** 匹配单框或整行末尾的 `x1` 一倍标记及常见混淆。 */
        val LEGACY_MULTIPLIER_REGEX = Regex("[xX×][.．]?[1lI](?:[.!！。])?$")

        /** ImageNet 三通道均值，顺序对应模型 BGR 输入。 */
        val IMAGENET_MEANS = floatArrayOf(0.485f, 0.456f, 0.406f)

        /** ImageNet 三通道标准差，顺序对应模型 BGR 输入。 */
        val IMAGENET_STANDARD_DEVIATIONS = floatArrayOf(0.229f, 0.224f, 0.225f)

        /** 识别模型三通道均值。 */
        val HALF_MEANS = floatArrayOf(0.5f, 0.5f, 0.5f)

        /** 识别模型三通道标准差。 */
        val HALF_STANDARD_DEVIATIONS = floatArrayOf(0.5f, 0.5f, 0.5f)
    }
}

/**
 * 校正朝向后的整页图片与文字框。
 *
 * @property image 朝上的整页图片。
 * @property boxes 在 [image] 上检测并排好阅读顺序的文字框。
 * @property quarterTurns 相对原图顺时针旋转的 90 度次数。
 */
private class UprightPage(
    val image: RgbImage,
    val boxes: List<SourceTextBox>,
    val quarterTurns: Int,
)

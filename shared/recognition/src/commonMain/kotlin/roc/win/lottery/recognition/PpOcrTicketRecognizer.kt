package roc.win.lottery.recognition

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.max

/** 使用 PP-OCRv5 mobile 三段模型执行跨平台本地票面识别。 */
internal class PpOcrTicketRecognizer(
    /** 平台 RGB 图片解码器。 */
    private val imageDecoder: PpOcrRgbImageDecoder,
    /** 平台 ONNX Runtime 执行器。 */
    private val runtime: PpOcrOnnxRuntime,
    /** 与识别模型严格同版本的字符字典。 */
    private val characters: List<String>,
    /** 与英文识别模型严格同版本的拉丁字符字典。 */
    private val latinCharacters: List<String>,
) : ProgressiveTicketRecognizer {
    init {
        require(characters.size == PP_OCR_CHARACTER_COUNT) { "PP-OCRv5 字符字典不完整" }
        require(latinCharacters.size == PP_OCR_LATIN_CHARACTER_COUNT) { "PP-OCRv5 英文字符字典不完整" }
    }

    /** 解码私有图片并依次执行检测、方向分类与 CTC 识别，同时报告阶段进度。 */
    override suspend fun recognize(
        imageRef: ImageRef,
        onProgress: (RecognitionProgress) -> Unit,
    ): RecognitionResult =
        withContext(Dispatchers.Default) {
            try {
                onProgress(RecognitionProgress(INITIAL_PROGRESS, "正在初始化本地识别"))
                val image =
                    imageDecoder.decode(imageRef, MAXIMUM_DECODED_LONG_EDGE)
                        ?: run {
                            onProgress(RecognitionProgress(COMPLETED_PROGRESS, "本地图片读取失败"))
                            return@withContext RecognitionResult.Failure("无法读取本地图片，请重新导入")
                        }
                onProgress(RecognitionProgress(DECODED_PROGRESS, "正在检测票面文字"))
                val boxes = detectTextBoxes(image)
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
                                recognizeMergedTextBoxCandidates(
                                    image = image,
                                    box = mergedBox,
                                    model = PpOcrModel.RECOGNITION,
                                    modelCharacters = characters,
                                )
                            },
                            recognizeLatinMergedRow =
                                { mergedBox ->
                                    recognizeMergedTextBoxCandidates(
                                        image = image,
                                        box = mergedBox,
                                        model = PpOcrModel.LATIN_RECOGNITION,
                                        modelCharacters = latinCharacters,
                                    )
                                },
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
                                engineName = ENGINE_NAME,
                            ),
                        )
                    }
                onProgress(RecognitionProgress(COMPLETED_PROGRESS, "票面文字识别完成"))
                result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                RecognitionResult.Failure("PP-OCRv5 本地识别未能完成，请重新导入图片")
            }
        }

    /** 缩放整图并执行 DB 文本检测后处理。 */
    private fun detectTextBoxes(image: RgbImage): List<SourceTextBox> {
        val (inputWidth, inputHeight) = detectionInputSize(image)
        val resized = image.resized(inputWidth, inputHeight)
        val input = resized.toNormalizedBgrNchw(IMAGENET_MEANS, IMAGENET_STANDARD_DEVIATIONS)
        val output =
            runtime.run(
                model = PpOcrModel.DETECTION,
                input = input,
                inputShape = intArrayOf(1, COLOR_CHANNEL_COUNT, inputHeight, inputWidth),
            )
        return PpOcrDetectionPostProcessor.extract(output, image.width, image.height)
    }

    /** 裁切单个旋转框，修正 180 度方向并执行文字识别。 */
    private fun recognizeTextBox(
        image: RgbImage,
        box: SourceTextBox,
    ): OcrTextLine? {
        val crop = image.perspectiveCrop(box)
        if (crop.width < MINIMUM_CROP_SIDE || crop.height < MINIMUM_CROP_SIDE) return null
        val oriented = correctOrientation(crop)
        val decoded = recognizeLine(oriented, PpOcrModel.RECOGNITION, characters) ?: return null
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
        model: PpOcrModel,
        modelCharacters: List<String>,
    ): List<OcrTextLine> {
        val crop = image.perspectiveCrop(box)
        if (crop.width < MINIMUM_CROP_SIDE || crop.height < MINIMUM_CROP_SIDE) return emptyList()
        val oriented = correctOrientation(crop)
        val variants =
            listOf(
                oriented,
                oriented.contrastStretchedGrayscale(),
            )
        return variants
            .distinctBy(RgbImage::pixels)
            .mapNotNull { variant ->
                val decoded = recognizeLine(variant, model, modelCharacters) ?: return@mapNotNull null
                if (decoded.confidence < MINIMUM_RECOGNITION_CONFIDENCE) return@mapNotNull null
                OcrTextLine(
                    text = decoded.text,
                    bounds = box.normalizedBounds(image.width, image.height),
                    confidence = minOf(box.confidence, decoded.confidence),
                )
            }
    }

    /** 使用固定 160x80 分类模型纠正倒置文字行。 */
    private fun correctOrientation(image: RgbImage): RgbImage {
        val resized = image.resized(ORIENTATION_WIDTH, ORIENTATION_HEIGHT)
        val input = resized.toNormalizedBgrNchw(IMAGENET_MEANS, IMAGENET_STANDARD_DEVIATIONS)
        val output =
            runtime.run(
                model = PpOcrModel.ORIENTATION,
                input = input,
                inputShape = intArrayOf(1, COLOR_CHANNEL_COUNT, ORIENTATION_HEIGHT, ORIENTATION_WIDTH),
            )
        if (output.values.size < ORIENTATION_CLASS_COUNT) return image
        val uprightScore = output.values[0]
        val upsideDownScore = output.values[1]
        return if (upsideDownScore > uprightScore && upsideDownScore >= ORIENTATION_ROTATE_THRESHOLD) {
            image.rotated180()
        } else {
            image
        }
    }

    /** 按官方最小宽高比生成动态宽度输入并执行 CTC 解码。 */
    private fun recognizeLine(
        image: RgbImage,
        model: PpOcrModel,
        modelCharacters: List<String>,
    ): PpOcrCtcDecoder.DecodedText? {
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
            runtime.runCtc(
                model = model,
                input = input,
                inputShape = intArrayOf(1, COLOR_CHANNEL_COUNT, RECOGNITION_HEIGHT, targetWidth),
            )
        return PpOcrCtcDecoder.decode(output, modelCharacters)
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

    /** PP-OCRv5 官方预处理参数与移动端保护限制。 */
    private companion object {
        /** 引擎版本摘要。 */
        const val ENGINE_NAME = "PP-OCRv5 mobile + ONNX Runtime"

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

package roc.win.lottery.recognition

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PP-OCR 共享预处理、后处理与完整流水线测试。 */
class PpOcrCoreTest {
    /** RGB 像素必须按 BGR 平面顺序写入 NCHW 张量。 */
    @Test
    fun rgbImageUsesBgrNchwChannelOrder() {
        val image =
            RgbImage(
                width = 2,
                height = 1,
                pixels = byteArrayOf(10, 20, 30, 40, 50, 60),
            )

        val output =
            image.toNormalizedBgrNchw(
                means = floatArrayOf(0f, 0f, 0f),
                standardDeviations = floatArrayOf(1f, 1f, 1f),
            )

        assertEquals(30f / 255f, output[0], FLOAT_TOLERANCE)
        assertEquals(60f / 255f, output[1], FLOAT_TOLERANCE)
        assertEquals(20f / 255f, output[2], FLOAT_TOLERANCE)
        assertEquals(50f / 255f, output[3], FLOAT_TOLERANCE)
        assertEquals(10f / 255f, output[4], FLOAT_TOLERANCE)
        assertEquals(40f / 255f, output[5], FLOAT_TOLERANCE)
    }

    /** 旋转 180 度必须反转像素位置但保持每个像素的 RGB 顺序。 */
    @Test
    fun rotate180ReversesPixels() {
        val image =
            RgbImage(
                width = 2,
                height = 2,
                pixels =
                    byteArrayOf(
                        1,
                        2,
                        3,
                        4,
                        5,
                        6,
                        7,
                        8,
                        9,
                        10,
                        11,
                        12,
                    ),
            )

        val rotated = image.rotated180()

        assertEquals(
            listOf(10, 11, 12, 7, 8, 9, 4, 5, 6, 1, 2, 3),
            rotated.pixels.map { byte -> byte.toInt() and 0xff },
        )
    }

    /** 顺时针旋转 90 度必须交换宽高，并把左下角像素移到左上角。 */
    @Test
    fun rgbImageRotatesClockwise() {
        // 2x3 图片，像素按行编号 1..6，每个像素三通道取同一值。
        val image = RgbImage(width = 2, height = 3, pixels = ByteArray(18) { index -> (index / 3 + 1).toByte() })

        val rotated = image.rotatedClockwise()

        assertEquals(3, rotated.width)
        assertEquals(2, rotated.height)
        assertEquals(
            listOf(5, 3, 1, 6, 4, 2),
            rotated.pixels
                .toList()
                .chunked(3)
                .map { pixel -> pixel.first().toInt() },
        )
    }

    /** 校正后页面中的位置必须按旋转次数准确映射回原图。 */
    @Test
    fun boundsMapBackToSourceImage() {
        val bounds = NormalizedBounds(left = 0.1f, top = 0.2f, right = 0.4f, bottom = 0.3f)

        assertEquals(bounds, bounds.toSourceImage(0))
        assertBoundsEquals(NormalizedBounds(0.2f, 0.6f, 0.3f, 0.9f), bounds.toSourceImage(1))
        assertBoundsEquals(NormalizedBounds(0.6f, 0.7f, 0.9f, 0.8f), bounds.toSourceImage(2))
        assertBoundsEquals(NormalizedBounds(0.7f, 0.1f, 0.8f, 0.4f), bounds.toSourceImage(3))
    }

    /** 局部灰度拉伸必须消除色偏，并把有效亮度范围扩展到八位边界。 */
    @Test
    fun contrastStretchedGrayscaleExpandsLocalLuminance() {
        val image =
            RgbImage(
                width = 2,
                height = 1,
                pixels = byteArrayOf(40, 50, 60, 180.toByte(), 190.toByte(), 200.toByte()),
            )

        val stretched = image.contrastStretchedGrayscale()

        assertEquals(listOf(0, 0, 0, 255, 255, 255), stretched.pixels.map { byte -> byte.toInt() and 0xff })
    }

    /** 旋转框裁切必须保持目标尺寸和从上到下的图像方向。 */
    @Test
    fun perspectiveCropPreservesDimensionsAndDirection() {
        val pixels = ByteArray(4 * 4 * 3)
        for (pixelIndex in 0 until 16) {
            val offset = pixelIndex * 3
            if (pixelIndex / 4 < 2) {
                pixels[offset] = 0xff.toByte()
            } else {
                pixels[offset + 2] = 0xff.toByte()
            }
        }
        val image = RgbImage(width = 4, height = 4, pixels = pixels)

        val crop =
            image.perspectiveCrop(
                SourceTextBox(
                    topLeft = FloatPoint(0f, 0f),
                    topRight = FloatPoint(4f, 0f),
                    bottomRight = FloatPoint(4f, 4f),
                    bottomLeft = FloatPoint(0f, 4f),
                    confidence = 1f,
                ),
            )

        assertEquals(4, crop.width)
        assertEquals(4, crop.height)
        assertTrue(crop.channelAt(x = 0, y = 0, channel = 0) > crop.channelAt(x = 0, y = 0, channel = 2))
        assertTrue(crop.channelAt(x = 0, y = 3, channel = 2) > crop.channelAt(x = 0, y = 3, channel = 0))
    }

    /** 检测尺寸默认限制最长边为 1280，可按参数调整，并按 32 像素对齐；小图默认不放大。 */
    @Test
    fun detectionInputUsesLongEdgeLimitAndAlignment() {
        val large = RgbImage(width = 2_048, height = 1_024, pixels = ByteArray(2_048 * 1_024 * 3))
        val small = RgbImage(width = 100, height = 50, pixels = ByteArray(100 * 50 * 3))

        assertEquals(1_280 to 640, detectionInputSize(large))
        assertEquals(960 to 480, detectionInputSize(large, longEdge = 960))
        assertEquals(96 to 64, detectionInputSize(small))
        assertEquals(1_280 to 640, detectionInputSize(small, upscale = true))
    }

    /** DB 概率图中的连通文字区域必须映射为横向旋转框。 */
    @Test
    fun detectionPostProcessorExtractsConnectedTextRegion() {
        val values = FloatArray(20 * 20)
        for (y in 6..10) {
            for (x in 4..13) {
                values[y * 20 + x] = 0.9f
            }
        }

        val box =
            PpOcrDetectionPostProcessor
                .extract(
                    output = OnnxTensorData(values, intArrayOf(1, 1, 20, 20)),
                    sourceWidth = 200,
                    sourceHeight = 100,
                ).single()

        assertTrue(box.topLeft.x < box.topRight.x)
        assertTrue(box.topLeft.y < box.bottomLeft.y)
        assertTrue(box.topLeft.distanceTo(box.topRight) > box.topLeft.distanceTo(box.bottomLeft))
        assertEquals(0.9f, box.confidence, FLOAT_TOLERANCE)
    }

    /** 由单像素细桥误连的纵向文字列必须按水平投影恢复成多行框。 */
    @Test
    fun detectionPostProcessorSplitsVerticallyBridgedRows() {
        val values = FloatArray(24 * 24)
        for (y in 3..7) {
            for (x in 8..12) values[y * 24 + x] = 0.9f
        }
        for (y in 14..18) {
            for (x in 8..12) values[y * 24 + x] = 0.9f
        }
        for (y in 8..13) values[y * 24 + 10] = 0.9f

        val boxes =
            PpOcrDetectionPostProcessor.extract(
                output = OnnxTensorData(values, intArrayOf(1, 1, 24, 24)),
                sourceWidth = 240,
                sourceHeight = 240,
            )

        assertEquals(2, boxes.size)
        assertTrue(boxes[0].readingOrderKey.first < boxes[1].readingOrderKey.first)
        assertTrue(boxes.all { box -> box.topLeft.distanceTo(box.topRight) >= box.topLeft.distanceTo(box.bottomLeft) })
    }

    /** 没有投影谷的实心竖列也应依据正常文字高度切成多个横向识别框。 */
    @Test
    fun detectionPostProcessorSlicesSolidVerticalColumn() {
        val values = FloatArray(30 * 30)
        for (y in 2..5) {
            for (x in 15..24) values[y * 30 + x] = 0.9f
        }
        for (y in 8..27) {
            for (x in 2..9) values[y * 30 + x] = 0.9f
        }

        val boxes =
            PpOcrDetectionPostProcessor.extract(
                output = OnnxTensorData(values, intArrayOf(1, 1, 30, 30)),
                sourceWidth = 300,
                sourceHeight = 300,
            )

        assertTrue(boxes.size >= 3)
        assertTrue(boxes.all { box -> box.topLeft.distanceTo(box.topRight) >= box.topLeft.distanceTo(box.bottomLeft) })
    }

    /** CTC 必须折叠相邻重复、允许 blank 分隔重复，并忽略首尾空格置信度。 */
    @Test
    fun ctcDecoderHandlesBlankDuplicatesAndBoundarySpaces() {
        val characters = listOf("彩", "票")
        val output =
            ctcOutput(
                characters = characters,
                classes = intArrayOf(3, 0, 1, 1, 0, 1, 0, 3),
                scores = floatArrayOf(0.99f, 0.99f, 0.8f, 0.9f, 0.99f, 0.7f, 0.99f, 1f),
            )

        val decoded = assertNotNull(PpOcrCtcDecoder.decode(output, characters))

        assertEquals("彩彩", decoded.text)
        assertEquals(0.75f, decoded.confidence, FLOAT_TOLERANCE)
    }

    /** 只包含 blank 或模型空格类别时不能形成文字行。 */
    @Test
    fun ctcDecoderRejectsEmptyContent() {
        val characters = listOf("彩")

        assertNull(
            PpOcrCtcDecoder.decode(
                ctcOutput(
                    characters = characters,
                    classes = intArrayOf(0, 2, 0),
                    scores = floatArrayOf(1f, 1f, 1f),
                ),
                characters,
            ),
        )
    }

    /** 字典解析必须保留精确行序并拒绝非 18,383 行输入。 */
    @Test
    fun dictionaryParserPreservesAllEntries() {
        val content =
            buildString {
                repeat(PP_OCR_CHARACTER_COUNT) { index ->
                    append("字")
                    append(index)
                    append('\n')
                }
            }

        val characters = parsePpOcrCharacters(content)

        assertEquals(PP_OCR_CHARACTER_COUNT, characters.size)
        assertEquals("字0", characters.first())
        assertEquals("字${PP_OCR_CHARACTER_COUNT - 1}", characters.last())
    }

    /** 合成张量必须贯通检测、方向分类、识别和共享 OCR 文档输出。 */
    @Test
    fun recognizerRunsThreeStagePipeline() =
        runTest {
            val calls = mutableListOf<PpOcrModel>()
            val progressUpdates = mutableListOf<RecognitionProgress>()
            val characters = List(PP_OCR_CHARACTER_COUNT) { index -> if (index == 0) "彩" else "字$index" }
            val recognizer =
                PpOcrTicketRecognizer(
                    imageDecoder =
                        PpOcrRgbImageDecoder { _, _ ->
                            RgbImage(width = 32, height = 32, pixels = ByteArray(32 * 32 * 3) { 0xff.toByte() })
                        },
                    runtime =
                        PpOcrOnnxRuntime { model, _, inputShape ->
                            calls += model
                            when (model) {
                                PpOcrModel.DETECTION -> {
                                    assertEquals(listOf(1, 3, 32, 32), inputShape.toList())
                                    detectionOutput()
                                }

                                PpOcrModel.ORIENTATION -> {
                                    assertEquals(listOf(1, 3, 80, 160), inputShape.toList())
                                    OnnxTensorData(floatArrayOf(0.95f, 0.05f), intArrayOf(1, 2))
                                }

                                PpOcrModel.RECOGNITION -> {
                                    assertEquals(48, inputShape[2])
                                    ctcOutput(
                                        characters = characters,
                                        classes = intArrayOf(1, 1, 0),
                                        scores = floatArrayOf(0.95f, 0.9f, 1f),
                                    )
                                }
                            }
                        },
                    characters = characters,
                )

            val result =
                recognizer.recognize(
                    imageRef =
                        ImageRef(
                            id = "ppocr-pipeline-test",
                            localPath = "memory://ppocr-pipeline-test.jpg",
                            mimeType = "image/jpeg",
                            widthPixels = 32,
                            heightPixels = 32,
                        ),
                    onProgress = progressUpdates::add,
                )

            val success = assertIs<RecognitionResult.Success>(result)
            assertEquals(listOf(PpOcrModel.DETECTION, PpOcrModel.ORIENTATION, PpOcrModel.RECOGNITION), calls)
            assertEquals(PpOcrPipelineConfig().engineName, success.document.engineName)
            assertEquals(0, success.document.pageQuarterTurns)
            assertEquals(
                "彩",
                success.document.lines
                    .single()
                    .text,
            )
            assertEquals(1f, progressUpdates.last().fraction)
            assertTrue(progressUpdates.zipWithNext().all { (left, right) -> left.fraction <= right.fraction })
            assertTrue(progressUpdates.any { progress -> progress.message.contains("读取票面文字") })
        }

    /** 抽样文字行过半判为倒置时，整页转 180 度后重新检测，横向文字行不再逐个分类。 */
    @Test
    fun recognizerRotatesUpsideDownPage() =
        runTest {
            val (calls, recognizer) = orientationRecognizer(listOf(detectionOutput()), upsideDown = true)

            val result = recognizer.recognize(orientationImageRef()) {}

            val document = assertIs<RecognitionResult.Success>(result).document
            assertEquals(
                listOf(PpOcrModel.DETECTION, PpOcrModel.ORIENTATION, PpOcrModel.DETECTION, PpOcrModel.RECOGNITION),
                calls,
            )
            assertEquals(2, document.pageQuarterTurns)
        }

    /** 文字框大多竖向时，整页先顺时针转 90 度再检测。 */
    @Test
    fun recognizerRotatesSidewaysPage() =
        runTest {
            val (calls, recognizer) =
                orientationRecognizer(
                    listOf(verticalDetectionOutput(columnCount = 3), detectionOutput()),
                    upsideDown = false,
                )

            val result = recognizer.recognize(orientationImageRef()) {}

            val document = assertIs<RecognitionResult.Success>(result).document
            assertEquals(
                listOf(PpOcrModel.DETECTION, PpOcrModel.DETECTION, PpOcrModel.ORIENTATION, PpOcrModel.RECOGNITION),
                calls,
            )
            assertEquals(1, document.pageQuarterTurns)
        }

    /** 转 90 度后竖向占比没有下降时，判断有误，保留原图。 */
    @Test
    fun recognizerKeepsPageWhenRotationDoesNotHelp() =
        runTest {
            val (calls, recognizer) =
                orientationRecognizer(listOf(verticalDetectionOutput(columnCount = 3)), upsideDown = false)

            val result = recognizer.recognize(orientationImageRef()) {}

            assertEquals(0, assertIs<RecognitionResult.Success>(result).document.pageQuarterTurns)
            assertEquals(2, calls.count { model -> model == PpOcrModel.DETECTION })
        }

    /** 少量竖排边栏文字不足以判定整页横置。 */
    @Test
    fun recognizerKeepsPageWithFewVerticalBoxes() =
        runTest {
            val (calls, recognizer) =
                orientationRecognizer(listOf(verticalDetectionOutput(columnCount = 2)), upsideDown = false)

            val result = recognizer.recognize(orientationImageRef()) {}

            assertEquals(0, assertIs<RecognitionResult.Success>(result).document.pageQuarterTurns)
            assertEquals(PpOcrModel.DETECTION, calls.first())
            assertEquals(1, calls.count { model -> model == PpOcrModel.DETECTION })
        }

    /**
     * 创建按调用顺序返回检测输出、方向分类固定判为倒置或朝上的识别器。
     *
     * 检测调用次数超过 [detections] 时重复最后一个输出。
     */
    private fun orientationRecognizer(
        detections: List<OnnxTensorData>,
        upsideDown: Boolean,
    ): Pair<List<PpOcrModel>, PpOcrTicketRecognizer> {
        val calls = mutableListOf<PpOcrModel>()
        val characters = List(PP_OCR_CHARACTER_COUNT) { index -> if (index == 0) "彩" else "字$index" }
        var detectionCount = 0
        val recognizer =
            PpOcrTicketRecognizer(
                imageDecoder =
                    PpOcrRgbImageDecoder { _, _ ->
                        RgbImage(width = 32, height = 32, pixels = ByteArray(32 * 32 * 3) { 0xff.toByte() })
                    },
                runtime =
                    PpOcrOnnxRuntime { model, _, _ ->
                        calls += model
                        when (model) {
                            PpOcrModel.DETECTION -> {
                                detections[minOf(detectionCount++, detections.lastIndex)]
                            }

                            PpOcrModel.ORIENTATION -> {
                                if (upsideDown) {
                                    OnnxTensorData(floatArrayOf(0.05f, 0.95f), intArrayOf(1, 2))
                                } else {
                                    OnnxTensorData(floatArrayOf(0.95f, 0.05f), intArrayOf(1, 2))
                                }
                            }

                            PpOcrModel.RECOGNITION -> {
                                ctcOutput(
                                    characters = characters,
                                    classes = intArrayOf(1, 0),
                                    scores = floatArrayOf(0.95f, 1f),
                                )
                            }
                        }
                    },
                characters = characters,
            )
        return calls to recognizer
    }

    /** 朝向测试使用的图片引用。 */
    private fun orientationImageRef(): ImageRef =
        ImageRef(
            id = "ppocr-orientation-test",
            localPath = "memory://ppocr-orientation-test.jpg",
            mimeType = "image/jpeg",
            widthPixels = 32,
            heightPixels = 32,
        )

    /** 创建包含若干条竖向高概率区域的检测输出。 */
    private fun verticalDetectionOutput(columnCount: Int): OnnxTensorData {
        val values = FloatArray(32 * 32)
        repeat(columnCount) { column ->
            for (y in 3..28) {
                for (x in 2 + column * 10..5 + column * 10) {
                    values[y * 32 + x] = 0.9f
                }
            }
        }
        return OnnxTensorData(values, intArrayOf(1, 1, 32, 32))
    }

    /** 逐边比较两组归一化位置，允许浮点误差。 */
    private fun assertBoundsEquals(
        expected: NormalizedBounds,
        actual: NormalizedBounds,
    ) {
        assertEquals(expected.left, actual.left, FLOAT_TOLERANCE)
        assertEquals(expected.top, actual.top, FLOAT_TOLERANCE)
        assertEquals(expected.right, actual.right, FLOAT_TOLERANCE)
        assertEquals(expected.bottom, actual.bottom, FLOAT_TOLERANCE)
    }

    /** 创建带指定最优类别和概率的三维 CTC 输出。 */
    private fun ctcOutput(
        characters: List<String>,
        classes: IntArray,
        scores: FloatArray,
    ): OnnxTensorData {
        require(classes.size == scores.size)
        val classCount = characters.size + 2
        val values = FloatArray(classes.size * classCount)
        classes.forEachIndexed { timeStep, selectedClass ->
            values[timeStep * classCount + selectedClass] = scores[timeStep]
        }
        return OnnxTensorData(values, intArrayOf(1, classes.size, classCount))
    }

    /** 创建包含一个横向高概率区域的检测输出。 */
    private fun detectionOutput(): OnnxTensorData {
        val values = FloatArray(32 * 32)
        for (y in 10..17) {
            for (x in 4..27) {
                values[y * 32 + x] = 0.9f
            }
        }
        return OnnxTensorData(values, intArrayOf(1, 1, 32, 32))
    }

    /** 读取 RGB 图片中一个通道的无符号值。 */
    private fun RgbImage.channelAt(
        x: Int,
        y: Int,
        channel: Int,
    ): Int = pixels[(y * width + x) * 3 + channel].toInt() and 0xff

    /** 浮点断言允许的误差。 */
    private companion object {
        /** 预处理和概率平均使用的绝对误差。 */
        const val FLOAT_TOLERANCE = 0.000_01f
    }
}

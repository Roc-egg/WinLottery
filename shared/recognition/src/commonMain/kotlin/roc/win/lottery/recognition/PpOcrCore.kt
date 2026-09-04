package roc.win.lottery.recognition

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** PP-OCRv5 三端共用模型资源所在的类路径和 iOS Bundle 目录。 */
internal const val PP_OCR_RESOURCE_ROOT = "roc/win/lottery/recognition/models/ppocrv5"

/** PP-OCRv5 三段推理模型。 */
internal enum class PpOcrModel(
    /** 打包后的 ONNX 文件名。 */
    val fileName: String,
    /** ONNX 图的唯一输入名称。 */
    val inputName: String = "x",
    /** ONNX 图的唯一输出名称。 */
    val outputName: String = "fetch_name_0",
) {
    /** DB 文本区域检测模型。 */
    DETECTION("detection.onnx"),

    /** 文本行 0 度或 180 度方向分类模型。 */
    ORIENTATION("orientation.onnx"),

    /** 中文、英文与数字文字识别模型。 */
    RECOGNITION("recognition.onnx"),

    /** 针对旧式点阵拉丁字母与数字优化的英文识别模型。 */
    LATIN_RECOGNITION("recognition_latin.onnx"),
}

/** 一次 ONNX Float 张量输出。 */
internal data class OnnxTensorData(
    /** 按行优先顺序展开的 Float 元素。 */
    val values: FloatArray,
    /** 运行时返回的实际张量形状。 */
    val shape: IntArray,
)

/** 文字识别张量逐时间步归约后的 CTC 输入。 */
internal data class PpOcrCtcOutput(
    /** 每个时间步概率最高的类别下标。 */
    val classIndices: IntArray,
    /** 每个时间步最高类别的概率。 */
    val scores: FloatArray,
    /** 原始识别张量的类别总数。 */
    val classCount: Int,
) {
    init {
        require(classIndices.size == scores.size) { "CTC 类别与概率数量必须一致" }
        require(classCount > 0) { "CTC 类别总数必须大于零" }
    }
}

/** 隔离不同平台 ONNX Runtime API 的最小执行接口。 */
internal fun interface PpOcrOnnxRuntime {
    /** 执行指定 PP-OCRv5 模型，并返回唯一 Float 输出。 */
    fun run(
        model: PpOcrModel,
        input: FloatArray,
        inputShape: IntArray,
    ): OnnxTensorData

    /** 执行文字识别模型，并把完整概率张量归约为 CTC 解码真正需要的数据。 */
    fun runCtc(
        model: PpOcrModel,
        input: FloatArray,
        inputShape: IntArray,
    ): PpOcrCtcOutput = PpOcrCtcDecoder.reduce(run(model, input, inputShape))
}

/** 不带 Alpha 的行优先 RGB 图片。 */
internal data class RgbImage(
    /** 图片宽度，单位为像素。 */
    val width: Int,
    /** 图片高度，单位为像素。 */
    val height: Int,
    /** 每像素依次存放红、绿、蓝三个无符号字节。 */
    val pixels: ByteArray,
) {
    init {
        require(width > 0 && height > 0) { "RGB 图片尺寸必须大于零" }
        require(pixels.size == width * height * CHANNEL_COUNT) { "RGB 图片字节数与尺寸不匹配" }
    }

    /** 双线性缩放到指定尺寸。 */
    fun resized(
        targetWidth: Int,
        targetHeight: Int,
    ): RgbImage {
        require(targetWidth > 0 && targetHeight > 0) { "缩放尺寸必须大于零" }
        if (targetWidth == width && targetHeight == height) return this
        val target = ByteArray(targetWidth * targetHeight * CHANNEL_COUNT)
        val xScale = width.toFloat() / targetWidth.toFloat()
        val yScale = height.toFloat() / targetHeight.toFloat()
        for (targetY in 0 until targetHeight) {
            val sourceY = ((targetY + 0.5f) * yScale - 0.5f).coerceIn(0f, (height - 1).toFloat())
            val y0 = floor(sourceY).toInt()
            val y1 = min(y0 + 1, height - 1)
            val yWeight = sourceY - y0
            for (targetX in 0 until targetWidth) {
                val sourceX = ((targetX + 0.5f) * xScale - 0.5f).coerceIn(0f, (width - 1).toFloat())
                val x0 = floor(sourceX).toInt()
                val x1 = min(x0 + 1, width - 1)
                val xWeight = sourceX - x0
                val targetOffset = (targetY * targetWidth + targetX) * CHANNEL_COUNT
                for (channel in 0 until CHANNEL_COUNT) {
                    val top =
                        unsigned(x0, y0, channel) * (1f - xWeight) +
                            unsigned(x1, y0, channel) * xWeight
                    val bottom =
                        unsigned(x0, y1, channel) * (1f - xWeight) +
                            unsigned(x1, y1, channel) * xWeight
                    target[targetOffset + channel] =
                        (top * (1f - yWeight) + bottom * yWeight)
                            .roundToInt()
                            .coerceIn(0, MAX_CHANNEL_VALUE)
                            .toByte()
                }
            }
        }
        return RgbImage(targetWidth, targetHeight, target)
    }

    /** 将整张图片旋转 180 度。 */
    fun rotated180(): RgbImage {
        val target = ByteArray(pixels.size)
        val pixelCount = width * height
        for (sourcePixel in 0 until pixelCount) {
            val targetPixel = pixelCount - sourcePixel - 1
            pixels.copyInto(
                destination = target,
                destinationOffset = targetPixel * CHANNEL_COUNT,
                startIndex = sourcePixel * CHANNEL_COUNT,
                endIndex = sourcePixel * CHANNEL_COUNT + CHANNEL_COUNT,
            )
        }
        return RgbImage(width, height, target)
    }

    /** 转为灰度并按局部亮度分位拉伸对比度，突出热敏票的深色号码。 */
    fun contrastStretchedGrayscale(): RgbImage {
        val histogram = IntArray(MAX_CHANNEL_VALUE + 1)
        val pixelCount = width * height
        for (pixelIndex in 0 until pixelCount) {
            histogram[luminanceAt(pixelIndex)] += 1
        }
        val low = histogram.percentileValue(pixelCount, LOW_CONTRAST_PERCENTILE)
        val high = histogram.percentileValue(pixelCount, HIGH_CONTRAST_PERCENTILE)
        if (high - low < MINIMUM_CONTRAST_RANGE) return this
        val target = ByteArray(pixels.size)
        for (pixelIndex in 0 until pixelCount) {
            val value =
                ((luminanceAt(pixelIndex) - low) * MAX_CHANNEL_VALUE / (high - low))
                    .coerceIn(0, MAX_CHANNEL_VALUE)
                    .toByte()
            val offset = pixelIndex * CHANNEL_COUNT
            target[offset] = value
            target[offset + 1] = value
            target[offset + 2] = value
        }
        return RgbImage(width, height, target)
    }

    /** 依据四角坐标把旋转文字区域重采样成水平矩形。 */
    fun perspectiveCrop(box: SourceTextBox): RgbImage {
        val targetWidth =
            box.topLeft
                .distanceTo(box.topRight)
                .roundToInt()
                .coerceAtLeast(1)
        val targetHeight =
            box.topLeft
                .distanceTo(box.bottomLeft)
                .roundToInt()
                .coerceAtLeast(1)
        val target = ByteArray(targetWidth * targetHeight * CHANNEL_COUNT)
        for (targetY in 0 until targetHeight) {
            val vertical = (targetY + 0.5f) / targetHeight.toFloat()
            val left = box.topLeft.lerp(box.bottomLeft, vertical)
            val right = box.topRight.lerp(box.bottomRight, vertical)
            for (targetX in 0 until targetWidth) {
                val horizontal = (targetX + 0.5f) / targetWidth.toFloat()
                val source = left.lerp(right, horizontal)
                val targetOffset = (targetY * targetWidth + targetX) * CHANNEL_COUNT
                sampleBilinear(source.x, source.y, target, targetOffset)
            }
        }
        return RgbImage(targetWidth, targetHeight, target)
    }

    /** 按 BGR 通道顺序生成 PP-OCR 使用的 NCHW 标准化张量。 */
    fun toNormalizedBgrNchw(
        means: FloatArray,
        standardDeviations: FloatArray,
    ): FloatArray {
        require(means.size == CHANNEL_COUNT && standardDeviations.size == CHANNEL_COUNT) {
            "标准化参数必须包含三个通道"
        }
        val planeSize = width * height
        val output = FloatArray(planeSize * CHANNEL_COUNT)
        for (pixelIndex in 0 until planeSize) {
            val sourceOffset = pixelIndex * CHANNEL_COUNT
            for (outputChannel in 0 until CHANNEL_COUNT) {
                val sourceChannel = CHANNEL_COUNT - outputChannel - 1
                val value = (pixels[sourceOffset + sourceChannel].toInt() and BYTE_MASK) / MAX_CHANNEL_VALUE_FLOAT
                output[outputChannel * planeSize + pixelIndex] =
                    (value - means[outputChannel]) / standardDeviations[outputChannel]
            }
        }
        return output
    }

    /** 读取一个通道的无符号像素值。 */
    private fun unsigned(
        x: Int,
        y: Int,
        channel: Int,
    ): Float = (pixels[(y * width + x) * CHANNEL_COUNT + channel].toInt() and BYTE_MASK).toFloat()

    /** 使用整数 BT.601 权重计算指定像素的八位亮度。 */
    private fun luminanceAt(pixelIndex: Int): Int {
        val offset = pixelIndex * CHANNEL_COUNT
        val red = pixels[offset].toInt() and BYTE_MASK
        val green = pixels[offset + 1].toInt() and BYTE_MASK
        val blue = pixels[offset + 2].toInt() and BYTE_MASK
        return (red * RED_LUMINANCE_WEIGHT + green * GREEN_LUMINANCE_WEIGHT + blue * BLUE_LUMINANCE_WEIGHT) shr
            LUMINANCE_WEIGHT_SHIFT
    }

    /** 从亮度直方图中返回最近秩分位值。 */
    private fun IntArray.percentileValue(
        sampleCount: Int,
        percentile: Float,
    ): Int {
        val targetRank = (sampleCount * percentile).toInt().coerceIn(0, sampleCount - 1)
        var cumulative = 0
        forEachIndexed { value, count ->
            cumulative += count
            if (cumulative > targetRank) return value
        }
        return lastIndex
    }

    /** 把双线性采样结果写入目标 RGB 缓冲区。 */
    private fun sampleBilinear(
        sourceX: Float,
        sourceY: Float,
        target: ByteArray,
        targetOffset: Int,
    ) {
        val clampedX = sourceX.coerceIn(0f, (width - 1).toFloat())
        val clampedY = sourceY.coerceIn(0f, (height - 1).toFloat())
        val x0 = floor(clampedX).toInt()
        val y0 = floor(clampedY).toInt()
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val xWeight = clampedX - x0
        val yWeight = clampedY - y0
        for (channel in 0 until CHANNEL_COUNT) {
            val top = unsigned(x0, y0, channel) * (1f - xWeight) + unsigned(x1, y0, channel) * xWeight
            val bottom = unsigned(x0, y1, channel) * (1f - xWeight) + unsigned(x1, y1, channel) * xWeight
            target[targetOffset + channel] =
                (top * (1f - yWeight) + bottom * yWeight)
                    .roundToInt()
                    .coerceIn(0, MAX_CHANNEL_VALUE)
                    .toByte()
        }
    }

    /** RGB 图片固定格式。 */
    private companion object {
        /** 每个像素的通道数。 */
        const val CHANNEL_COUNT = 3

        /** Byte 转无符号整数使用的掩码。 */
        const val BYTE_MASK = 0xff

        /** 八位通道最大整数值。 */
        const val MAX_CHANNEL_VALUE = 255

        /** 八位通道最大浮点值。 */
        const val MAX_CHANNEL_VALUE_FLOAT = 255f

        /** 红色通道的整数亮度权重。 */
        const val RED_LUMINANCE_WEIGHT = 77

        /** 绿色通道的整数亮度权重。 */
        const val GREEN_LUMINANCE_WEIGHT = 150

        /** 蓝色通道的整数亮度权重。 */
        const val BLUE_LUMINANCE_WEIGHT = 29

        /** 三通道亮度权重合计为 256，对应右移位数。 */
        const val LUMINANCE_WEIGHT_SHIFT = 8

        /** 对比度拉伸使用的低亮度分位。 */
        const val LOW_CONTRAST_PERCENTILE = 0.02f

        /** 对比度拉伸使用的高亮度分位。 */
        const val HIGH_CONTRAST_PERCENTILE = 0.98f

        /** 亮度跨度低于该值时不创建无意义的增强副本。 */
        const val MINIMUM_CONTRAST_RANGE = 24
    }
}

/** 把平台私有图片解码为方向归一化的 RGB 像素。 */
internal fun interface PpOcrRgbImageDecoder {
    /** 解码图片并限制最长边，失败时返回空。 */
    suspend fun decode(
        imageRef: ImageRef,
        maximumLongEdgePixels: Int,
    ): RgbImage?
}

/** 二维浮点坐标。 */
internal data class FloatPoint(
    /** 横坐标。 */
    val x: Float,
    /** 纵坐标。 */
    val y: Float,
) {
    /** 计算到另一点的欧氏距离。 */
    fun distanceTo(other: FloatPoint): Float {
        val deltaX = other.x - x
        val deltaY = other.y - y
        return sqrt(deltaX * deltaX + deltaY * deltaY)
    }

    /** 在当前点与目标点之间执行线性插值。 */
    fun lerp(
        other: FloatPoint,
        ratio: Float,
    ): FloatPoint = FloatPoint(x + (other.x - x) * ratio, y + (other.y - y) * ratio)
}

/** 已映射回解码原图的旋转文字框。 */
internal data class SourceTextBox(
    /** 左上角。 */
    val topLeft: FloatPoint,
    /** 右上角。 */
    val topRight: FloatPoint,
    /** 右下角。 */
    val bottomRight: FloatPoint,
    /** 左下角。 */
    val bottomLeft: FloatPoint,
    /** DB 区域内的平均文字概率。 */
    val confidence: Float,
) {
    /** 包围旋转框的共享归一化坐标。 */
    fun normalizedBounds(
        imageWidth: Int,
        imageHeight: Int,
    ): NormalizedBounds {
        val points = listOf(topLeft, topRight, bottomRight, bottomLeft)
        return NormalizedBounds(
            left = points.minOf(FloatPoint::x).normalize(imageWidth),
            top = points.minOf(FloatPoint::y).normalize(imageHeight),
            right = points.maxOf(FloatPoint::x).normalize(imageWidth),
            bottom = points.maxOf(FloatPoint::y).normalize(imageHeight),
        )
    }

    /** 用上边中心位置建立稳定阅读顺序。 */
    val readingOrderKey: Pair<Float, Float>
        get() = ((topLeft.y + topRight.y) / 2f) to ((topLeft.x + bottomLeft.x) / 2f)
}

/** PP-OCRv5 DB 检测后处理。 */
internal object PpOcrDetectionPostProcessor {
    /** 从概率图提取旋转文字框并映射回原图。 */
    fun extract(
        output: OnnxTensorData,
        sourceWidth: Int,
        sourceHeight: Int,
    ): List<SourceTextBox> {
        require(output.shape.size == OUTPUT_RANK) { "检测输出必须是四维张量" }
        val mapHeight = output.shape[output.shape.size - 2]
        val mapWidth = output.shape.last()
        require(mapWidth > 0 && mapHeight > 0 && output.values.size >= mapWidth * mapHeight) {
            "检测输出尺寸无效"
        }
        val visited = BooleanArray(mapWidth * mapHeight)
        val queue = IntArray(mapWidth * mapHeight)
        val boxes = mutableListOf<SourceTextBox>()
        for (start in visited.indices) {
            if (visited[start] || output.values[start] < PIXEL_THRESHOLD) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            while (head < tail) {
                val index = queue[head++]
                val x = index % mapWidth
                val y = index / mapWidth
                for (offset in NEIGHBOR_OFFSETS) {
                    val nextX = x + offset.first
                    val nextY = y + offset.second
                    if (nextX !in 0 until mapWidth || nextY !in 0 until mapHeight) continue
                    val next = nextY * mapWidth + nextX
                    if (!visited[next] && output.values[next] >= PIXEL_THRESHOLD) {
                        visited[next] = true
                        queue[tail++] = next
                    }
                }
            }
            if (tail < MINIMUM_COMPONENT_PIXELS) continue
            splitTallComponent(queue, tail, mapWidth).forEach { component ->
                componentToBox(
                    component = component,
                    probabilities = output.values,
                    mapWidth = mapWidth,
                    mapHeight = mapHeight,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                )?.let(boxes::add)
            }
        }
        return splitVerticalColumns(boxes)
            .sortedWith(compareBy<SourceTextBox> { it.readingOrderKey.first }.thenBy { it.readingOrderKey.second })
            .take(MAXIMUM_CANDIDATES)
    }

    /** 依据正常横排文字高度，把检测器误判成竖排的多行号码列等距切回横向行框。 */
    private fun splitVerticalColumns(boxes: List<SourceTextBox>): List<SourceTextBox> {
        val typicalLineHeight =
            boxes
                .filter { box -> box.isHorizontalDirection }
                .map { box -> box.shortEdgeLength }
                .filter { length -> length >= MINIMUM_BOX_SIDE }
                .sorted()
                .medianOrNull()
                ?: return boxes
        return boxes.flatMap { box ->
            if (box.isHorizontalDirection) return@flatMap listOf(box)
            if (box.shortEdgeLength < typicalLineHeight * MINIMUM_VERTICAL_COLUMN_WIDTH_RATIO) {
                return@flatMap listOf(box)
            }
            val segmentCount =
                ceil(box.longEdgeLength / typicalLineHeight)
                    .toInt()
                    .coerceAtMost(MAXIMUM_VERTICAL_SEGMENTS)
            if (segmentCount < MINIMUM_SPLIT_BAND_COUNT) return@flatMap listOf(box)
            (0 until segmentCount).map { index ->
                box.horizontalSlice(
                    startRatio = index.toFloat() / segmentCount,
                    endRatio = (index + 1).toFloat() / segmentCount,
                )
            }
        }
    }

    /** 判断旋转框长边是否沿图片横向。 */
    private val SourceTextBox.isHorizontalDirection: Boolean
        get() = kotlin.math.abs(topRight.x - topLeft.x) >= kotlin.math.abs(topRight.y - topLeft.y)

    /** 旋转框文字方向长边长度。 */
    private val SourceTextBox.longEdgeLength: Float
        get() = topLeft.distanceTo(topRight)

    /** 旋转框文字高度方向短边长度。 */
    private val SourceTextBox.shortEdgeLength: Float
        get() = topLeft.distanceTo(bottomLeft)

    /** 沿竖向旋转框长边截取一个区间，并生成适合横排识别的轴对齐框。 */
    private fun SourceTextBox.horizontalSlice(
        startRatio: Float,
        endRatio: Float,
    ): SourceTextBox {
        val points =
            listOf(
                topLeft.lerp(topRight, startRatio),
                bottomLeft.lerp(bottomRight, startRatio),
                topLeft.lerp(topRight, endRatio),
                bottomLeft.lerp(bottomRight, endRatio),
            )
        val left = points.minOf(FloatPoint::x)
        val right = points.maxOf(FloatPoint::x)
        val top = points.minOf(FloatPoint::y)
        val bottom = points.maxOf(FloatPoint::y)
        return SourceTextBox(
            topLeft = FloatPoint(left, top),
            topRight = FloatPoint(right, top),
            bottomRight = FloatPoint(right, bottom),
            bottomLeft = FloatPoint(left, bottom),
            confidence = confidence,
        )
    }

    /** 返回有序浮点列表的中位数；空列表返回空。 */
    private fun List<Float>.medianOrNull(): Float? {
        if (isEmpty()) return null
        val middle = size / 2
        return if (size % 2 == 0) {
            (this[middle - 1] + this[middle]) / 2f
        } else {
            this[middle]
        }
    }

    /** 把由细桥误连的纵向号码列按水平投影谷切回独立文字行。 */
    private fun splitTallComponent(
        indices: IntArray,
        count: Int,
        mapWidth: Int,
    ): List<ComponentPixels> {
        var minimumX = Int.MAX_VALUE
        var maximumX = Int.MIN_VALUE
        var minimumY = Int.MAX_VALUE
        var maximumY = Int.MIN_VALUE
        for (offset in 0 until count) {
            val index = indices[offset]
            val x = index % mapWidth
            val y = index / mapWidth
            minimumX = min(minimumX, x)
            maximumX = max(maximumX, x)
            minimumY = min(minimumY, y)
            maximumY = max(maximumY, y)
        }
        val width = maximumX - minimumX + 1
        val height = maximumY - minimumY + 1
        val original = ComponentPixels(indices, count)
        if (height < width * TALL_COMPONENT_RATIO) return listOf(original)

        val rowCounts = IntArray(height)
        for (offset in 0 until count) {
            rowCounts[indices[offset] / mapWidth - minimumY] += 1
        }
        val strongRowThreshold =
            max(
                MINIMUM_STRONG_ROW_PIXELS,
                ceil((rowCounts.maxOrNull() ?: 0) * STRONG_ROW_RATIO).toInt(),
            )
        val bands = mutableListOf<IntRange>()
        var bandStart = -1
        var lastStrongRow = -1
        rowCounts.forEachIndexed { row, rowCount ->
            if (rowCount < strongRowThreshold) return@forEachIndexed
            if (bandStart >= 0 && row - lastStrongRow > MAXIMUM_WEAK_ROW_GAP + 1) {
                bands += bandStart..lastStrongRow
                bandStart = row
            } else if (bandStart < 0) {
                bandStart = row
            }
            lastStrongRow = row
        }
        if (bandStart >= 0) bands += bandStart..lastStrongRow
        if (bands.size < MINIMUM_SPLIT_BAND_COUNT) return listOf(original)

        val slices =
            bands.mapNotNull { band ->
                val pixels =
                    indices
                        .take(count)
                        .filter { index -> index / mapWidth - minimumY in band }
                        .toIntArray()
                pixels.takeIf { it.size >= MINIMUM_COMPONENT_PIXELS }?.let { ComponentPixels(it, it.size) }
            }
        return slices.takeIf { it.size >= MINIMUM_SPLIT_BAND_COUNT } ?: listOf(original)
    }

    /** 从一个连通区域像素子集计算置信度、协方差和旋转文字框。 */
    private fun componentToBox(
        component: ComponentPixels,
        probabilities: FloatArray,
        mapWidth: Int,
        mapHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int,
    ): SourceTextBox? {
        var sumX = 0.0
        var sumY = 0.0
        var sumXX = 0.0
        var sumYY = 0.0
        var sumXY = 0.0
        var probabilitySum = 0.0
        for (offset in 0 until component.count) {
            val index = component.indices[offset]
            val x = index % mapWidth
            val y = index / mapWidth
            sumX += x
            sumY += y
            sumXX += x.toDouble() * x
            sumYY += y.toDouble() * y
            sumXY += x.toDouble() * y
            probabilitySum += probabilities[index]
        }
        val confidence = (probabilitySum / component.count).toFloat()
        if (confidence < BOX_THRESHOLD) return null
        return componentToBox(
            indices = component.indices,
            count = component.count,
            mapWidth = mapWidth,
            mapHeight = mapHeight,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            covarianceXX = sumXX / component.count - (sumX / component.count) * (sumX / component.count),
            covarianceYY = sumYY / component.count - (sumY / component.count) * (sumY / component.count),
            covarianceXY = sumXY / component.count - (sumX / component.count) * (sumY / component.count),
            confidence = confidence,
        )
    }

    /** 使用连通区域主轴计算近似最小旋转矩形，并执行 DB unclip 扩张。 */
    private fun componentToBox(
        indices: IntArray,
        count: Int,
        mapWidth: Int,
        mapHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        covarianceXX: Double,
        covarianceYY: Double,
        covarianceXY: Double,
        confidence: Float,
    ): SourceTextBox? {
        var angle = 0.5 * atan2(2.0 * covarianceXY, covarianceXX - covarianceYY)
        var axisX = cos(angle).toFloat()
        var axisY = sin(angle).toFloat()
        var extents = project(indices, count, mapWidth, axisX, axisY)
        if (extents.longSize < extents.shortSize) {
            angle += PI / 2.0
            axisX = cos(angle).toFloat()
            axisY = sin(angle).toFloat()
            extents = project(indices, count, mapWidth, axisX, axisY)
        }
        if (axisX < 0f) {
            axisX = -axisX
            axisY = -axisY
            extents = project(indices, count, mapWidth, axisX, axisY)
        }
        val perpendicularX = -axisY
        val perpendicularY = axisX
        val width = extents.maxLong - extents.minLong + 1f
        val height = extents.maxShort - extents.minShort + 1f
        if (min(width, height) < MINIMUM_BOX_SIDE || width * height > mapWidth * mapHeight * MAXIMUM_AREA_RATIO) {
            return null
        }
        val area = width * height
        val perimeter = 2f * (width + height)
        val expansion = area * UNCLIP_RATIO / perimeter
        val minimumLong = extents.minLong - expansion
        val maximumLong = extents.maxLong + expansion
        val minimumShort = extents.minShort - expansion
        val maximumShort = extents.maxShort + expansion

        fun point(
            long: Float,
            short: Float,
        ): FloatPoint {
            val mapX = long * axisX + short * perpendicularX
            val mapY = long * axisY + short * perpendicularY
            return FloatPoint(
                x = (mapX * sourceWidth / mapWidth.toFloat()).coerceIn(0f, (sourceWidth - 1).toFloat()),
                y = (mapY * sourceHeight / mapHeight.toFloat()).coerceIn(0f, (sourceHeight - 1).toFloat()),
            )
        }
        return SourceTextBox(
            topLeft = point(minimumLong, minimumShort),
            topRight = point(maximumLong, minimumShort),
            bottomRight = point(maximumLong, maximumShort),
            bottomLeft = point(minimumLong, maximumShort),
            confidence = confidence,
        )
    }

    /** 把区域像素投影到主轴及其垂线。 */
    private fun project(
        indices: IntArray,
        count: Int,
        mapWidth: Int,
        axisX: Float,
        axisY: Float,
    ): ProjectedExtents {
        val perpendicularX = -axisY
        val perpendicularY = axisX
        var minimumLong = Float.POSITIVE_INFINITY
        var maximumLong = Float.NEGATIVE_INFINITY
        var minimumShort = Float.POSITIVE_INFINITY
        var maximumShort = Float.NEGATIVE_INFINITY
        for (offset in 0 until count) {
            val index = indices[offset]
            val x = (index % mapWidth).toFloat()
            val y = (index / mapWidth).toFloat()
            val long = x * axisX + y * axisY
            val short = x * perpendicularX + y * perpendicularY
            minimumLong = min(minimumLong, long)
            maximumLong = max(maximumLong, long)
            minimumShort = min(minimumShort, short)
            maximumShort = max(maximumShort, short)
        }
        return ProjectedExtents(minimumLong, maximumLong, minimumShort, maximumShort)
    }

    /** 主轴投影范围。 */
    private data class ProjectedExtents(
        /** 主轴最小投影。 */
        val minLong: Float,
        /** 主轴最大投影。 */
        val maxLong: Float,
        /** 垂直轴最小投影。 */
        val minShort: Float,
        /** 垂直轴最大投影。 */
        val maxShort: Float,
    ) {
        /** 主轴跨度。 */
        val longSize: Float
            get() = maxLong - minLong

        /** 垂直轴跨度。 */
        val shortSize: Float
            get() = maxShort - minShort
    }

    /** 一个待转换为旋转框的连通区域像素子集。 */
    private data class ComponentPixels(
        /** 概率图中的一维像素下标。 */
        val indices: IntArray,
        /** 当前子集使用的有效像素数量。 */
        val count: Int,
    )

    /** DB 官方配置及移动端保护阈值。 */
    private const val OUTPUT_RANK = 4

    /** 概率图二值化阈值。 */
    private const val PIXEL_THRESHOLD = 0.3f

    /** 候选文字框最低平均概率。 */
    private const val BOX_THRESHOLD = 0.6f

    /** 旋转框向外扩张比例。 */
    private const val UNCLIP_RATIO = 1.5f

    /** 单个连通区域至少包含的像素数。 */
    private const val MINIMUM_COMPONENT_PIXELS = 6

    /** 高度达到宽度该倍数时检查是否由多行细桥误连。 */
    private const val TALL_COMPONENT_RATIO = 1.5f

    /** 水平投影强行至少包含的概率像素数。 */
    private const val MINIMUM_STRONG_ROW_PIXELS = 2

    /** 相对最密投影行的强行阈值。 */
    private const val STRONG_ROW_RATIO = 0.2

    /** 同一文字带内部允许出现的连续弱投影行数。 */
    private const val MAXIMUM_WEAK_ROW_GAP = 1

    /** 至少切出两条有效文字带才采用投影分割。 */
    private const val MINIMUM_SPLIT_BAND_COUNT = 2

    /** 单个异常竖列最多恢复的文字行数，限制畸形框带来的推理次数。 */
    private const val MAXIMUM_VERTICAL_SEGMENTS = 20

    /** 只有达到正常文字高度宽度的竖列才可能是横排号码误连，窄边栏保持竖排。 */
    private const val MINIMUM_VERTICAL_COLUMN_WIDTH_RATIO = 1.0f

    /** 旋转框最短边下限。 */
    private const val MINIMUM_BOX_SIDE = 3f

    /** 拒绝覆盖大部分票图的异常候选。 */
    private const val MAXIMUM_AREA_RATIO = 0.65f

    /** 单图最多保留的候选文字框数量。 */
    private const val MAXIMUM_CANDIDATES = 256

    /** 八邻域连通偏移。 */
    private val NEIGHBOR_OFFSETS =
        listOf(
            -1 to -1,
            0 to -1,
            1 to -1,
            -1 to 0,
            1 to 0,
            -1 to 1,
            0 to 1,
            1 to 1,
        )
}

/** PP-OCRv5 CTC 识别后处理。 */
internal object PpOcrCtcDecoder {
    /** 从完整识别张量中只保留每个时间步概率最高的类别。 */
    fun reduce(output: OnnxTensorData): PpOcrCtcOutput {
        require(output.shape.size == OUTPUT_RANK) { "识别输出必须是三维张量" }
        val classCount = output.shape.last()
        require(classCount > 0 && output.values.size % classCount == 0) { "识别输出元素数量无效" }
        val timeSteps = output.values.size / classCount
        val classIndices = IntArray(timeSteps)
        val scores = FloatArray(timeSteps)
        for (timeStep in 0 until timeSteps) {
            val rowOffset = timeStep * classCount
            var bestClass = 0
            var bestScore = output.values[rowOffset]
            for (candidate in 1 until classCount) {
                val score = output.values[rowOffset + candidate]
                if (score > bestScore) {
                    bestClass = candidate
                    bestScore = score
                }
            }
            classIndices[timeStep] = bestClass
            scores[timeStep] = bestScore
        }
        return PpOcrCtcOutput(classIndices, scores, classCount)
    }

    /** 按 CTC 规则去除 blank 和相邻重复类别。 */
    fun decode(
        output: OnnxTensorData,
        characters: List<String>,
    ): DecodedText? = decode(reduce(output), characters)

    /** 对已经逐时间步归约的类别执行 CTC 去重、去 blank 与置信度汇总。 */
    fun decode(
        output: PpOcrCtcOutput,
        characters: List<String>,
    ): DecodedText? {
        require(output.classCount == characters.size + SPECIAL_CLASS_COUNT) { "识别类别数与字符字典不一致" }
        val tokens = mutableListOf<DecodedToken>()
        var previousClass = BLANK_CLASS_INDEX
        for (timeStep in output.classIndices.indices) {
            val bestClass = output.classIndices[timeStep]
            val bestScore = output.scores[timeStep]
            if (bestClass != BLANK_CLASS_INDEX && bestClass != previousClass) {
                val token =
                    when (bestClass) {
                        output.classCount - 1 -> " "
                        else -> characters[bestClass - 1]
                    }
                tokens += DecodedToken(token, bestScore.coerceIn(0f, 1f))
            }
            previousClass = bestClass
        }
        val firstContentIndex = tokens.indexOfFirst { token -> token.text != SPACE_TOKEN }
        val lastContentIndex = tokens.indexOfLast { token -> token.text != SPACE_TOKEN }
        if (firstContentIndex < 0 || lastContentIndex < firstContentIndex) return null
        val contentTokens = tokens.subList(firstContentIndex, lastContentIndex + 1)
        return DecodedText(
            text = contentTokens.joinToString(separator = "", transform = DecodedToken::text),
            confidence = contentTokens.map(DecodedToken::confidence).average().toFloat(),
        )
    }

    /** CTC 解码结果。 */
    data class DecodedText(
        /** 去除 blank 和重复类别后的文本。 */
        val text: String,
        /** 所有保留时间步最大概率的平均值。 */
        val confidence: Float,
    )

    /** 一个已经通过 CTC blank 和重复类别过滤的字符。 */
    private data class DecodedToken(
        /** 字典字符或模型追加的空格。 */
        val text: String,
        /** 当前时间步中该类别的概率。 */
        val confidence: Float,
    )

    /** CTC 输出固定结构。 */
    private const val OUTPUT_RANK = 3

    /** 字典之外包含开头 blank 和结尾空格两个类别。 */
    private const val SPECIAL_CLASS_COUNT = 2

    /** CTC blank 类别固定下标。 */
    private const val BLANK_CLASS_INDEX = 0

    /** 模型在字典尾部追加的空格类别内容。 */
    private const val SPACE_TOKEN = " "
}

/** 将字符字典原始文本解析为与模型类别顺序一致的列表。 */
internal fun parsePpOcrCharacters(
    content: String,
    expectedCharacterCount: Int = PP_OCR_CHARACTER_COUNT,
): List<String> {
    val normalized = content.replace("\r\n", "\n").replace('\r', '\n')
    val lines =
        normalized.split('\n').let { values ->
            if (values.lastOrNull().isNullOrEmpty()) values.dropLast(1) else values
        }
    require(lines.size == expectedCharacterCount) { "PP-OCRv5 字符字典行数不正确" }
    return lines
}

/** 按输入图像尺寸计算官方长边 960、边长 32 对齐的检测尺寸。 */
internal fun detectionInputSize(image: RgbImage): Pair<Int, Int> {
    val scale = min(1f, DETECTION_LONG_EDGE.toFloat() / max(image.width, image.height).toFloat())
    val width = alignDimension(image.width * scale, DETECTION_ALIGNMENT)
    val height = alignDimension(image.height * scale, DETECTION_ALIGNMENT)
    return width to height
}

/** 把尺寸四舍五入到模型步长的正整数倍。 */
private fun alignDimension(
    value: Float,
    alignment: Int,
): Int = max(alignment, (value / alignment).roundToInt() * alignment)

/** 将像素坐标限制并归一化。 */
private fun Float.normalize(dimension: Int): Float = (this / dimension.toFloat()).coerceIn(0f, 1f)

/** PP-OCRv5 字典固定行数。 */
internal const val PP_OCR_CHARACTER_COUNT = 18_383

/** PP-OCRv5 英文识别模型字典固定行数。 */
internal const val PP_OCR_LATIN_CHARACTER_COUNT = 436

/** 检测模型官方最长边。 */
private const val DETECTION_LONG_EDGE = 960

/** 检测模型尺寸对齐步长。 */
private const val DETECTION_ALIGNMENT = 32

package roc.win.lottery.recognition

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSMutableData
import platform.Foundation.NSNumber
import platform.Foundation.dataWithLength
import platform.Foundation.numberWithInt
import platform.UIKit.UIImage
import platform.posix.memcpy
import kotlin.math.roundToInt

/** Swift 宿主提供的 ONNX Runtime 连续张量执行接口。 */
interface IOSOnnxRuntime {
    /**
     * 执行一个打包在主 Bundle 根目录的模型。
     *
     * @param inputData Float32 输入；宿主直接用作输入张量内存，不再复制。
     * @return 推理成功时返回连续 Float 输出，失败时返回空。
     */
    fun run(
        modelFileName: String,
        inputData: NSMutableData,
        inputShape: List<NSNumber>,
    ): IOSOnnxTensorOutput?

    /**
     * 执行文字识别模型，并在宿主侧逐时间步取概率最大的类别，只回传 CTC 解码需要的结果。
     *
     * @param inputData Float32 输入；宿主直接用作输入张量内存，不再复制。
     * @return 推理成功时返回逐时间步归约结果，失败时返回空。
     */
    fun runCtc(
        modelFileName: String,
        inputData: NSMutableData,
        inputShape: List<NSNumber>,
    ): IOSOnnxCtcOutput?
}

/** iOS 宿主逐时间步归约后的文字识别输出。 */
data class IOSOnnxCtcOutput(
    /** 每个时间步概率最大的类别下标，Int32 本机字节序，按批次、时间步顺序排列。 */
    val classIndices: NSData,
    /** 对应类别的概率，Float32 本机字节序，排列与类别下标一致。 */
    val scores: NSData,
    /** 批次大小。 */
    val batchSize: Int,
    /** 每个样本的时间步数量。 */
    val timeSteps: Int,
    /** 识别模型的类别总数。 */
    val classCount: Int,
)

/** iOS ONNX Runtime 返回的连续 Float 张量。 */
data class IOSOnnxTensorOutput(
    /** Float32 原始字节，使用设备本机字节序。 */
    val data: NSData,
    /** 输出张量的实际维度。 */
    val shape: List<NSNumber>,
)

/** 使用 PP-OCR 与宿主 ONNX Runtime 执行 iOS 本地票面识别。 */
@OptIn(ExperimentalForeignApi::class)
class IOSPpOcrTicketRecognizer(
    /** 由 Swift 官方 ONNX Runtime 包实现的执行接口。 */
    runtime: IOSOnnxRuntime,
) : ProgressiveTicketRecognizer {
    /** 延迟读取 Bundle 字典并创建共享 OCR 流水线。 */
    private val delegate: PpOcrTicketRecognizer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PpOcrTicketRecognizer(
            imageDecoder = IOSPpOcrRgbImageDecoder(),
            runtime = IOSPpOcrOnnxRuntimeAdapter(runtime),
            characters = parsePpOcrCharacters(loadDictionary(PP_OCR_DICTIONARY_FILE_NAME)),
        )
    }

    /** 在默认计算调度器中执行检测、方向分类与识别推理，并转发内部阶段进度。 */
    override suspend fun recognize(
        imageRef: ImageRef,
        onProgress: (RecognitionProgress) -> Unit,
    ): RecognitionResult {
        onProgress(RecognitionProgress(0f, "正在加载本地识别模型"))
        return delegate.recognize(imageRef, onProgress)
    }

    /** 从应用主 Bundle 根目录读取与模型配套的 UTF-8 字典文件。 */
    private fun loadDictionary(fileName: String): String {
        val resourceName = fileName.substringBeforeLast(FILE_EXTENSION_SEPARATOR)
        val resourceExtension = fileName.substringAfterLast(FILE_EXTENSION_SEPARATOR)
        val path =
            NSBundle.mainBundle.pathForResource(
                name = resourceName,
                ofType = resourceExtension,
            ) ?: error("OCR 字符字典缺失")
        val data = NSFileManager.defaultManager.contentsAtPath(path) ?: error("OCR 字符字典无法读取")
        val bytes = ByteArray(data.length.toInt())
        bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), data.bytes, data.length)
        }
        return bytes.decodeToString()
    }

    /** iOS Bundle 字典资源解析常量。 */
    private companion object {
        /** 文件名和扩展名分隔符。 */
        const val FILE_EXTENSION_SEPARATOR = '.'
    }
}

/** 在 Kotlin/Native 数组与 Swift 宿主之间传递张量，输入只复制一次。 */
@OptIn(ExperimentalForeignApi::class)
private class IOSPpOcrOnnxRuntimeAdapter(
    /** Swift 宿主 ONNX Runtime。 */
    private val runtime: IOSOnnxRuntime,
) : PpOcrOnnxRuntime {
    /** 将输入复制为宿主可直接使用的 NSMutableData，再把输出复制回 FloatArray。 */
    override fun run(
        model: PpOcrModel,
        input: FloatArray,
        inputShape: IntArray,
    ): OnnxTensorData =
        autoreleasepool {
            val output =
                runtime.run(
                    modelFileName = model.fileName,
                    inputData = input.toInputData(),
                    inputShape = inputShape.toNumbers(),
                ) ?: error("iOS ONNX Runtime 执行失败")
            output.toTensorData()
        }

    /** 复制宿主返回的完整 Float 输出。 */
    private fun IOSOnnxTensorOutput.toTensorData(): OnnxTensorData {
        val output = this
        require(output.data.length.toInt() % FLOAT_BYTE_COUNT == 0) { "iOS ONNX 输出字节数无效" }
        val values = FloatArray(output.data.length.toInt() / FLOAT_BYTE_COUNT)
        values.usePinned { pinned ->
            memcpy(pinned.addressOf(0), output.data.bytes, output.data.length)
        }
        return OnnxTensorData(
            values = values,
            shape = output.shape.map { dimension -> dimension.intValue }.toIntArray(),
        )
    }

    /**
     * 由宿主在识别输出缓冲区内完成逐时间步归约，避免复制完整概率张量。
     *
     * 每次推理都在独立的自动释放池中完成，后台线程连续上百次推理时不会累积 Objective-C 临时对象。
     */
    override fun runCtc(
        model: PpOcrModel,
        input: FloatArray,
        inputShape: IntArray,
    ): PpOcrCtcOutput =
        autoreleasepool {
            val output =
                runtime.runCtc(
                    modelFileName = model.fileName,
                    inputData = input.toInputData(),
                    inputShape = inputShape.toNumbers(),
                ) ?: error("iOS ONNX Runtime 执行失败")
            output.toCtcOutput()
        }

    /** 复制宿主归约后的类别下标与概率。 */
    private fun IOSOnnxCtcOutput.toCtcOutput(): PpOcrCtcOutput {
        val output = this
        val count = output.batchSize * output.timeSteps
        require(output.batchSize == 1 && output.timeSteps >= 0 && output.classCount > 0) { "iOS 识别输出形状无效" }
        require(
            output.classIndices.length.toInt() == count * INT_BYTE_COUNT &&
                output.scores.length.toInt() == count * FLOAT_BYTE_COUNT,
        ) { "iOS 识别输出字节数无效" }
        val classIndices = IntArray(count)
        val scores = FloatArray(count)
        if (count > 0) {
            classIndices.usePinned { pinned ->
                memcpy(pinned.addressOf(0), output.classIndices.bytes, output.classIndices.length)
            }
            scores.usePinned { pinned ->
                memcpy(pinned.addressOf(0), output.scores.bytes, output.scores.length)
            }
        }
        return PpOcrCtcOutput(classIndices, scores, output.classCount)
    }

    /** 复制为宿主可直接作为输入张量内存的 NSMutableData。 */
    private fun FloatArray.toInputData(): NSMutableData {
        val byteCount = size * FLOAT_BYTE_COUNT
        val data = NSMutableData.dataWithLength(byteCount.toULong()) ?: error("无法分配 ONNX 输入")
        usePinned { pinned ->
            memcpy(data.mutableBytes, pinned.addressOf(0), byteCount.convert())
        }
        return data
    }

    /** 转为宿主接口使用的维度列表。 */
    private fun IntArray.toNumbers(): List<NSNumber> = map { dimension -> NSNumber.numberWithInt(dimension) }

    /** Float32 与 Int32 固定格式。 */
    private companion object {
        /** 单个 Float32 占用字节数。 */
        const val FLOAT_BYTE_COUNT = 4

        /** 单个 Int32 占用字节数。 */
        const val INT_BYTE_COUNT = 4
    }
}

/** 使用 CoreGraphics 将归一化 JPEG 解码为共享 RGB 图片。 */
@OptIn(ExperimentalForeignApi::class)
internal class IOSPpOcrRgbImageDecoder : PpOcrRgbImageDecoder {
    /** 缩放并绘制到 RGBA 位图，再去除 Alpha 通道。 */
    override suspend fun decode(
        imageRef: ImageRef,
        maximumLongEdgePixels: Int,
    ): RgbImage? {
        if (maximumLongEdgePixels <= 0) return null
        val cgImage = UIImage.imageWithContentsOfFile(imageRef.localPath)?.CGImage ?: return null
        val sourceWidth = CGImageGetWidth(cgImage).toInt()
        val sourceHeight = CGImageGetHeight(cgImage).toInt()
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        val scale = minOf(1.0, maximumLongEdgePixels.toDouble() / maxOf(sourceWidth, sourceHeight))
        val targetWidth = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
        val rgba = ByteArray(targetWidth * targetHeight * RGBA_CHANNEL_COUNT)
        val colorSpace = CGColorSpaceCreateDeviceRGB() ?: return null
        return try {
            rgba.usePinned { pinned ->
                val context =
                    CGBitmapContextCreate(
                        data = pinned.addressOf(0),
                        width = targetWidth.toULong(),
                        height = targetHeight.toULong(),
                        bitsPerComponent = BITS_PER_COMPONENT.toULong(),
                        bytesPerRow = (targetWidth * RGBA_CHANNEL_COUNT).toULong(),
                        space = colorSpace,
                        bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
                    ) ?: return@usePinned null
                try {
                    CGContextDrawImage(
                        context,
                        CGRectMake(0.0, 0.0, targetWidth.toDouble(), targetHeight.toDouble()),
                        cgImage,
                    )
                    RgbImage(targetWidth, targetHeight, rgba.toRgb())
                } finally {
                    CGContextRelease(context)
                }
            }
        } finally {
            CGColorSpaceRelease(colorSpace)
        }
    }

    /** 去除每像素最后一个 Alpha 字节。 */
    private fun ByteArray.toRgb(): ByteArray {
        val pixelCount = size / RGBA_CHANNEL_COUNT
        val rgb = ByteArray(pixelCount * RGB_CHANNEL_COUNT)
        for (pixelIndex in 0 until pixelCount) {
            val sourceOffset = pixelIndex * RGBA_CHANNEL_COUNT
            val targetOffset = pixelIndex * RGB_CHANNEL_COUNT
            copyInto(rgb, targetOffset, sourceOffset, sourceOffset + RGB_CHANNEL_COUNT)
        }
        return rgb
    }

    /** CoreGraphics 位图固定格式。 */
    private companion object {
        /** 每个颜色分量使用八位。 */
        const val BITS_PER_COMPONENT = 8

        /** CoreGraphics 输出每像素包含 RGBA 四个通道。 */
        const val RGBA_CHANNEL_COUNT = 4

        /** 共享图片每像素只保留 RGB 三个通道。 */
        const val RGB_CHANNEL_COUNT = 3
    }
}

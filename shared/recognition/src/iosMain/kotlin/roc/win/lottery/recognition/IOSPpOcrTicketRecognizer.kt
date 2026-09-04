package roc.win.lottery.recognition

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
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
     * 执行一个打包在主 Bundle 根目录的 PP-OCRv5 模型。
     *
     * @return 推理成功时返回连续 Float 输出，失败时返回空。
     */
    fun run(
        modelFileName: String,
        inputData: NSData,
        inputShape: List<NSNumber>,
    ): IOSOnnxTensorOutput?
}

/** iOS ONNX Runtime 返回的连续 Float 张量。 */
data class IOSOnnxTensorOutput(
    /** Float32 原始字节，使用设备本机字节序。 */
    val data: NSData,
    /** 输出张量的实际维度。 */
    val shape: List<NSNumber>,
)

/** 使用 PP-OCRv5 与宿主 ONNX Runtime 执行 iOS 本地票面识别。 */
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
            latinCharacters =
                parsePpOcrCharacters(
                    loadDictionary(PP_OCR_LATIN_DICTIONARY_FILE_NAME),
                    PP_OCR_LATIN_CHARACTER_COUNT,
                ),
        )
    }

    /** 在默认计算调度器中执行三段 PP-OCRv5 推理，并转发内部阶段进度。 */
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
            ) ?: error("PP-OCRv5 字符字典缺失")
        val data = NSFileManager.defaultManager.contentsAtPath(path) ?: error("PP-OCRv5 字符字典无法读取")
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

/** 在 Kotlin/Native FloatArray 与 Swift NSData 之间执行单次连续内存复制。 */
@OptIn(ExperimentalForeignApi::class)
private class IOSPpOcrOnnxRuntimeAdapter(
    /** Swift 宿主 ONNX Runtime。 */
    private val runtime: IOSOnnxRuntime,
) : PpOcrOnnxRuntime {
    /** 将输入复制为 NSData，调用宿主推理，再把输出复制回 FloatArray。 */
    override fun run(
        model: PpOcrModel,
        input: FloatArray,
        inputShape: IntArray,
    ): OnnxTensorData {
        val byteCount = input.size * FLOAT_BYTE_COUNT
        val inputData = NSMutableData.dataWithLength(byteCount.toULong()) ?: error("无法分配 ONNX 输入")
        input.usePinned { pinned ->
            memcpy(inputData.mutableBytes, pinned.addressOf(0), byteCount.convert())
        }
        val output =
            runtime.run(
                modelFileName = model.fileName,
                inputData = inputData,
                inputShape = inputShape.map { dimension -> NSNumber.numberWithInt(dimension) },
            ) ?: error("iOS ONNX Runtime 执行失败")
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

    /** Float32 固定格式。 */
    private companion object {
        /** 单个 Float32 占用字节数。 */
        const val FLOAT_BYTE_COUNT = 4
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

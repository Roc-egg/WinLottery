package roc.win.lottery.recognition

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/** 在已禁用遥测的桌面 OCR 工作进程中复用移动端 PP-OCRv5 流水线。 */
internal class DesktopPpOcrEngine : ProgressiveTicketRecognizer {
    /** 延迟校验模型资源并创建共享识别实现。 */
    private val delegate: PpOcrTicketRecognizer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val resources = DesktopPpOcrResources()
        PpOcrTicketRecognizer(
            imageDecoder = DesktopPpOcrRgbImageDecoder(),
            runtime = DesktopPpOcrOnnxRuntime(resources),
            characters = parsePpOcrCharacters(resources.readText(PP_OCR_DICTIONARY_FILE_NAME)),
            latinCharacters =
                parsePpOcrCharacters(
                    resources.readText(PP_OCR_LATIN_DICTIONARY_FILE_NAME),
                    PP_OCR_LATIN_CHARACTER_COUNT,
                ),
        )
    }

    /** 执行与 Android/iOS 相同的四模型识别，并转发共享阶段进度。 */
    override suspend fun recognize(
        imageRef: ImageRef,
        onProgress: (RecognitionProgress) -> Unit,
    ): RecognitionResult {
        onProgress(RecognitionProgress(0f, "正在加载本地识别模型"))
        return delegate.recognize(imageRef, onProgress)
    }
}

/** 使用 JVM ImageIO 将私有 JPEG 解码为共享 RGB 图片。 */
internal class DesktopPpOcrRgbImageDecoder : PpOcrRgbImageDecoder {
    /** 在后台线程限制最长边、铺设白色背景并生成紧凑 RGB 字节。 */
    override suspend fun decode(
        imageRef: ImageRef,
        maximumLongEdgePixels: Int,
    ): RgbImage? =
        withContext(Dispatchers.IO) {
            runCatching { decodeLocalImage(imageRef, maximumLongEdgePixels) }.getOrNull()
        }

    /** 解码本地图片并按最长边等比缩放。 */
    private fun decodeLocalImage(
        imageRef: ImageRef,
        maximumLongEdgePixels: Int,
    ): RgbImage? {
        if (maximumLongEdgePixels <= 0) return null
        val file = File(imageRef.localPath)
        if (!file.isFile) return null
        val decoded = ImageIO.read(file) ?: return null
        if (decoded.width <= 0 || decoded.height <= 0) {
            decoded.flush()
            return null
        }
        val scale = minOf(1.0, maximumLongEdgePixels.toDouble() / maxOf(decoded.width, decoded.height))
        val targetWidth = (decoded.width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (decoded.height * scale).roundToInt().coerceAtLeast(1)
        val normalized = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
        return try {
            val graphics = normalized.createGraphics()
            try {
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, targetWidth, targetHeight)
                graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR,
                )
                graphics.drawImage(decoded, 0, 0, targetWidth, targetHeight, null)
            } finally {
                graphics.dispose()
            }
            val colors = IntArray(targetWidth * targetHeight)
            normalized.getRGB(0, 0, targetWidth, targetHeight, colors, 0, targetWidth)
            val rgb = ByteArray(colors.size * RGB_CHANNEL_COUNT)
            colors.forEachIndexed { index, color ->
                val offset = index * RGB_CHANNEL_COUNT
                rgb[offset] = (color shr RED_SHIFT_BITS and COLOR_CHANNEL_MASK).toByte()
                rgb[offset + 1] = (color shr GREEN_SHIFT_BITS and COLOR_CHANNEL_MASK).toByte()
                rgb[offset + 2] = (color and COLOR_CHANNEL_MASK).toByte()
            }
            RgbImage(targetWidth, targetHeight, rgb)
        } finally {
            normalized.flush()
            decoded.flush()
        }
    }

    /** JVM ARGB 转共享 RGB 的固定参数。 */
    private companion object {
        /** 每个输出像素的通道数量。 */
        const val RGB_CHANNEL_COUNT = 3

        /** 红色通道右移位数。 */
        const val RED_SHIFT_BITS = 16

        /** 绿色通道右移位数。 */
        const val GREEN_SHIFT_BITS = 8

        /** 单个颜色通道掩码。 */
        const val COLOR_CHANNEL_MASK = 0xff
    }
}

/** 使用 ONNX Runtime Java API 执行并复用桌面工作进程内的模型 Session。 */
private class DesktopPpOcrOnnxRuntime(
    /** 提供经过长度与哈希校验的类路径模型。 */
    private val resources: DesktopPpOcrResources,
) : PpOcrOnnxRuntime {
    /** 进程级 ONNX Runtime 环境。 */
    private val environment: OrtEnvironment

    /** 每段模型只创建一个线程安全 Session。 */
    private val sessions = mutableMapOf<PpOcrModel, OrtSession>()

    init {
        DesktopOnnxRuntimePolicy.requireTelemetryDisabled()
        environment = OrtEnvironment.getEnvironment().apply { setTelemetry(false) }
    }

    /** 执行单输入、单输出 Float 模型并复制运行时输出。 */
    override fun run(
        model: PpOcrModel,
        input: FloatArray,
        inputShape: IntArray,
    ): OnnxTensorData =
        runOutput(model, input, inputShape) { output ->
            val buffer = output.floatBuffer
            val values = FloatArray(buffer.remaining())
            buffer.get(values)
            OnnxTensorData(
                values = values,
                shape =
                    output.info.shape
                        .map(Long::toInt)
                        .toIntArray(),
            )
        }

    /** 在输出缓冲区内完成逐时间步最大值归约，避免复制完整文字概率张量。 */
    override fun runCtc(
        model: PpOcrModel,
        input: FloatArray,
        inputShape: IntArray,
    ): PpOcrCtcOutput =
        runOutput(model, input, inputShape) { output ->
            val shape =
                output.info.shape
                    .map(Long::toInt)
                    .toIntArray()
            require(shape.size == CTC_OUTPUT_RANK && shape.first() == CTC_BATCH_SIZE) {
                "Desktop 识别输出必须是单批次三维张量"
            }
            val classCount = shape.last()
            val buffer = output.floatBuffer
            require(classCount > 0 && buffer.remaining() % classCount == 0) {
                "Desktop 识别输出元素数量无效"
            }
            val timeSteps = buffer.remaining() / classCount
            val classIndices = IntArray(timeSteps)
            val scores = FloatArray(timeSteps)
            val row = FloatArray(classCount)
            for (timeStep in 0 until timeSteps) {
                buffer.get(row)
                var bestClass = 0
                var bestScore = row[0]
                for (candidate in 1 until classCount) {
                    val score = row[candidate]
                    if (score > bestScore) {
                        bestClass = candidate
                        bestScore = score
                    }
                }
                classIndices[timeStep] = bestClass
                scores[timeStep] = bestScore
            }
            PpOcrCtcOutput(classIndices, scores, classCount)
        }

    /** 执行模型并在输出张量关闭前完成平台内数据转换。 */
    private fun <T> runOutput(
        model: PpOcrModel,
        input: FloatArray,
        inputShape: IntArray,
        transform: (OnnxTensor) -> T,
    ): T {
        val session = session(model)
        val shape = inputShape.map(Int::toLong).toLongArray()
        return OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), shape).use { tensor ->
            session.run(mapOf(model.inputName to tensor)).use { result ->
                val output =
                    result.get(model.outputName).orElseThrow {
                        IllegalStateException("ONNX 输出缺失")
                    } as? OnnxTensor ?: error("ONNX 输出类型错误")
                transform(output)
            }
        }
    }

    /** 按模型标识同步创建并缓存 Session。 */
    @Synchronized
    private fun session(model: PpOcrModel): OrtSession =
        sessions.getOrPut(model) {
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(INTRA_OP_THREAD_COUNT)
                options.setMemoryPatternOptimization(false)
                options.setCPUArenaAllocator(true)
                environment.createSession(resources.readModel(model), options)
            }
        }

    /** Desktop CPU Session 固定参数。 */
    private companion object {
        /** 限制单次 OCR 占用的 CPU 线程数量。 */
        const val INTRA_OP_THREAD_COUNT = 4

        /** 文字识别模型输出固定为三维张量。 */
        const val CTC_OUTPUT_RANK = 3

        /** 当前识别调用固定只传入一张裁图。 */
        const val CTC_BATCH_SIZE = 1
    }
}

/** 从类路径读取并校验 Android、iOS 和 Desktop 共用的 PP-OCRv5 资源。 */
private class DesktopPpOcrResources {
    /** 读取并校验 UTF-8 字符字典。 */
    fun readText(fileName: String): String {
        val expected = requireNotNull(PP_OCR_DICTIONARY_RESOURCE_LOCKS[fileName]) { "缺少字符字典文件锁" }
        return readLockedResource(fileName, expected).decodeToString()
    }

    /** 读取并校验指定 ONNX 模型。 */
    fun readModel(model: PpOcrModel): ByteArray {
        val expected = requireNotNull(PP_OCR_MODEL_RESOURCE_LOCKS[model]) { "缺少模型文件锁" }
        return readLockedResource(model.fileName, expected)
    }

    /** 对类路径资源执行固定长度和 SHA-256 校验。 */
    private fun readLockedResource(
        fileName: String,
        expected: PpOcrResourceLock,
    ): ByteArray {
        val resourcePath = "$PP_OCR_RESOURCE_ROOT/$fileName"
        val bytes =
            checkNotNull(javaClass.classLoader?.getResourceAsStream(resourcePath)) {
                "PP-OCRv5 资源缺失"
            }.use { input -> input.readBytes() }
        check(bytes.size.toLong() == expected.byteCount && bytes.sha256() == expected.sha256) {
            "PP-OCRv5 资源校验失败"
        }
        return bytes
    }

    /** 计算资源字节的 SHA-256。 */
    private fun ByteArray.sha256(): String =
        MessageDigest
            .getInstance(SHA_256_ALGORITHM)
            .digest(this)
            .joinToString(separator = "") { byte ->
                (byte.toInt() and BYTE_MASK).toString(HEX_RADIX).padStart(HEX_BYTE_WIDTH, '0')
            }

    /** 类路径资源校验常量。 */
    private companion object {
        /** SHA-256 算法名称。 */
        const val SHA_256_ALGORITHM = "SHA-256"

        /** Byte 转无符号整数使用的掩码。 */
        const val BYTE_MASK = 0xff

        /** 十六进制基数。 */
        const val HEX_RADIX = 16

        /** 一个字节固定使用两个十六进制字符。 */
        const val HEX_BYTE_WIDTH = 2
    }
}

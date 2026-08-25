package roc.win.lottery.recognition

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.FloatBuffer
import java.security.MessageDigest

/** 使用 PP-OCRv5 与 ONNX Runtime 执行 Android 本地票面识别。 */
class AndroidPpOcrTicketRecognizer(
    context: Context,
) : ProgressiveTicketRecognizer {
    /** 只用于访问私有模型目录和只读打包资源的应用上下文。 */
    private val applicationContext = context.applicationContext

    /** 延迟创建并在进程内复用的共享识别实现。 */
    private val delegate: PpOcrTicketRecognizer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val resources = AndroidPpOcrResources(applicationContext)
        PpOcrTicketRecognizer(
            imageDecoder = AndroidPpOcrRgbImageDecoder(),
            runtime = AndroidPpOcrOnnxRuntime(resources),
            characters = parsePpOcrCharacters(resources.readText(DICTIONARY_FILE_NAME)),
            latinCharacters =
                parsePpOcrCharacters(
                    resources.readText(LATIN_DICTIONARY_FILE_NAME),
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

    /** PP-OCRv5 字典资源文件。 */
    private companion object {
        /** 与识别模型配套的字符字典文件名。 */
        const val DICTIONARY_FILE_NAME = "characters.txt"

        /** 与英文识别模型配套的字符字典文件名。 */
        const val LATIN_DICTIONARY_FILE_NAME = "characters_latin.txt"
    }
}

/** 将 Android Bitmap 转换为共享 RGB 图片。 */
private class AndroidPpOcrRgbImageDecoder : PpOcrRgbImageDecoder {
    /** 解码、限制最长边并应用残留 EXIF 方向。 */
    override suspend fun decode(
        imageRef: ImageRef,
        maximumLongEdgePixels: Int,
    ): RgbImage? {
        val file = File(imageRef.localPath)
        if (!file.isFile) return null
        val bitmap =
            decodeNormalizedBitmap(
                openStream = { runCatching { FileInputStream(file) }.getOrNull() },
                maxEdgePixels = maximumLongEdgePixels,
            ) ?: return null
        return try {
            bitmap.toRgbImage()
        } finally {
            bitmap.recycle()
        }
    }

    /** 一次读取 Bitmap ARGB 像素并转换为紧凑 RGB 字节。 */
    private fun Bitmap.toRgbImage(): RgbImage {
        val colors = IntArray(width * height)
        getPixels(colors, 0, width, 0, 0, width, height)
        val rgb = ByteArray(colors.size * RGB_CHANNEL_COUNT)
        colors.forEachIndexed { index, color ->
            val offset = index * RGB_CHANNEL_COUNT
            rgb[offset] = (color shr RED_SHIFT and BYTE_MASK).toByte()
            rgb[offset + 1] = (color shr GREEN_SHIFT and BYTE_MASK).toByte()
            rgb[offset + 2] = (color and BYTE_MASK).toByte()
        }
        return RgbImage(width, height, rgb)
    }

    /** Android ARGB 像素格式参数。 */
    private companion object {
        /** 每个输出像素的 RGB 通道数。 */
        const val RGB_CHANNEL_COUNT = 3

        /** 红色通道右移位数。 */
        const val RED_SHIFT = 16

        /** 绿色通道右移位数。 */
        const val GREEN_SHIFT = 8

        /** 八位通道掩码。 */
        const val BYTE_MASK = 0xff
    }
}

/** 使用 ONNX Runtime Java API 执行并复用 PP-OCRv5 Session。 */
private class AndroidPpOcrOnnxRuntime(
    /** 经过哈希校验的模型资源提供器。 */
    private val resources: AndroidPpOcrResources,
) : PpOcrOnnxRuntime {
    /** 进程级 ONNX Runtime 环境。 */
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()

    /** 每段模型只创建一个线程安全 Session。 */
    private val sessions = mutableMapOf<PpOcrModel, OrtSession>()

    /** 执行单输入、单输出 Float 模型并复制运行时输出。 */
    override fun run(
        model: PpOcrModel,
        input: FloatArray,
        inputShape: IntArray,
    ): OnnxTensorData =
        runOutput(model, input, inputShape) { output ->
            val info = output.info
            val buffer = output.floatBuffer
            val values = FloatArray(buffer.remaining())
            buffer.get(values)
            OnnxTensorData(
                values = values,
                shape = info.shape.map(Long::toInt).toIntArray(),
            )
        }

    /** 在 ONNX 输出缓冲区内完成逐时间步最大值归约，避免复制完整文字概率张量。 */
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
                "Android 识别输出必须是单批次三维张量"
            }
            val classCount = shape.last()
            val buffer = output.floatBuffer
            require(classCount > 0 && buffer.remaining() % classCount == 0) { "Android 识别输出元素数量无效" }
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
                // 识别宽度持续变化，关闭形状相关的内存模式缓存，避免持有无收益的计划。
                options.setMemoryPatternOptimization(false)
                // 复用百余次推理的本地分配，减少频繁申请与释放张量内存的耗时。
                options.setCPUArenaAllocator(true)
                if (Build.HARDWARE in ANDROID_EMULATOR_HARDWARE) {
                    // ARM64 模拟器可能错误宣告 SME2 能力，禁用对应内核以避免非法指令崩溃。
                    options.addConfigEntry(DISABLE_KLEIDIAI_CONFIG_KEY, ENABLED_CONFIG_VALUE)
                }
                environment.createSession(resources.modelFile(model).absolutePath, options)
            }
        }

    /** 移动端 CPU Session 固定参数。 */
    private companion object {
        /** 四线程缩短串行模型延迟，同时限制单个 Session 占满高核心数真机。 */
        const val INTRA_OP_THREAD_COUNT = 4

        /** 文字识别模型输出固定为三维张量。 */
        const val CTC_OUTPUT_RANK = 3

        /** 当前识别调用固定只传入一张裁图。 */
        const val CTC_BATCH_SIZE = 1

        /** ONNX Runtime 禁用 KleidiAI 内核的 Session 配置项。 */
        const val DISABLE_KLEIDIAI_CONFIG_KEY = "mlas.disable_kleidiai"

        /** ONNX Runtime 布尔配置的启用值。 */
        const val ENABLED_CONFIG_VALUE = "1"

        /** Android Emulator 使用的虚拟硬件标识。 */
        val ANDROID_EMULATOR_HARDWARE = setOf("goldfish", "ranchu")
    }
}

/** 从打包资源读取字典，并把锁定模型复制到应用私有目录。 */
private class AndroidPpOcrResources(
    /** Android 应用上下文。 */
    private val context: Context,
) {
    /** 读取 UTF-8 文本资源。 */
    fun readText(fileName: String): String = open(fileName).bufferedReader(Charsets.UTF_8).use { it.readText() }

    /** 返回已核对长度与 SHA-256 的私有 ONNX 文件。 */
    @Synchronized
    fun modelFile(model: PpOcrModel): File {
        val expected = requireNotNull(MODEL_LOCKS[model]) { "缺少模型文件锁" }
        val directory = File(context.noBackupFilesDir, PRIVATE_MODEL_DIRECTORY).apply { mkdirs() }
        val destination = File(directory, model.fileName)
        if (destination.isFile && destination.length() == expected.byteCount &&
            destination.sha256() == expected.sha256
        ) {
            return destination
        }
        val temporary = File(directory, ".${model.fileName}.tmp")
        temporary.delete()
        open(model.fileName).use { input -> temporary.outputStream().buffered().use(input::copyTo) }
        check(temporary.length() == expected.byteCount && temporary.sha256() == expected.sha256) {
            temporary.delete()
            "PP-OCRv5 模型资源校验失败"
        }
        check(!destination.exists() || destination.delete()) {
            temporary.delete()
            "PP-OCRv5 旧模型无法替换"
        }
        check(temporary.renameTo(destination)) {
            temporary.delete()
            "PP-OCRv5 模型无法写入私有目录"
        }
        return destination
    }

    /** 优先从 KMP 类路径读取公共资源，并兼容 Android assets 打包形式。 */
    private fun open(fileName: String): InputStream {
        val resourcePath = "$PP_OCR_RESOURCE_ROOT/$fileName"
        return javaClass.classLoader?.getResourceAsStream(resourcePath)
            ?: runCatching { context.assets.open(resourcePath) }.getOrNull()
            ?: error("PP-OCRv5 资源缺失")
    }

    /** 流式计算文件 SHA-256，避免重新复制大模型到内存。 */
    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance(SHA_256_ALGORITHM)
        inputStream().buffered().use { input ->
            val buffer = ByteArray(HASH_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString(separator = "") { byte ->
            (byte.toInt() and BYTE_MASK).toString(HEX_RADIX).padStart(HEX_BYTE_WIDTH, '0')
        }
    }

    /** 单个模型文件的供应链锁。 */
    private data class LockedModelFile(
        /** 预期文件字节数。 */
        val byteCount: Long,
        /** 预期小写十六进制 SHA-256。 */
        val sha256: String,
    )

    /** 与仓库 model-lock.json 一致的移动模型文件锁。 */
    private companion object {
        /** 模型私有目录包含固定 bundle 标识，升级时自动隔离旧文件。 */
        const val PRIVATE_MODEL_DIRECTORY = "ppocrv5/paddleocr-3.7.0-opset17-latin-v1"

        /** SHA-256 算法名称。 */
        const val SHA_256_ALGORITHM = "SHA-256"

        /** 哈希读取缓冲区字节数。 */
        const val HASH_BUFFER_SIZE = 1024 * 1024

        /** Byte 转无符号整数使用的掩码。 */
        const val BYTE_MASK = 0xff

        /** 十六进制基数。 */
        const val HEX_RADIX = 16

        /** 一个字节固定使用两个十六进制字符。 */
        const val HEX_BYTE_WIDTH = 2

        /** 三段 ONNX 模型的长度与哈希。 */
        val MODEL_LOCKS =
            mapOf(
                PpOcrModel.DETECTION to
                    LockedModelFile(
                        4_766_440,
                        "c8d9b07063420ce5365c74e42532de48238feeeedcdb7a330b195708bc38a93f",
                    ),
                PpOcrModel.ORIENTATION to
                    LockedModelFile(
                        1_016_850,
                        "4d6027cad43b04171d6eafa20e7342fea7a657724e96d642564392b6df1e9768",
                    ),
                PpOcrModel.RECOGNITION to
                    LockedModelFile(
                        16_529_870,
                        "bcb195e3463eb9e46ef419b8a01ea4729577de5fd63c64f0a762e43bd64256e7",
                    ),
                PpOcrModel.LATIN_RECOGNITION to
                    LockedModelFile(
                        7_843_511,
                        "70b2450eed39599af6b996c27a2f1a0ef30eeb49f9f66dd3e74f28f652befc89",
                    ),
            )
    }
}

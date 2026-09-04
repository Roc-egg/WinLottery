package roc.win.lottery.recognition

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * 通过独立本地进程执行与 Android/iOS 相同的 PP-OCRv5 识别。
 *
 * @property applicationCommand 当前桌面应用的完整启动命令，不包含工作进程参数。
 * @property timeoutSeconds 单张图片识别的最长等待秒数。
 */
class DesktopPpOcrTicketRecognizer(
    private val applicationCommand: List<String>,
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) : ProgressiveTicketRecognizer {
    init {
        require(applicationCommand.isNotEmpty() && applicationCommand.all(String::isNotBlank)) {
            "桌面应用启动命令不能为空"
        }
        require(timeoutSeconds > 0) { "桌面 OCR 超时时间必须大于零" }
    }

    /** 启动受控子进程，通过匿名管道转发进度和标准 OCR 文档。 */
    override suspend fun recognize(
        imageRef: ImageRef,
        onProgress: (RecognitionProgress) -> Unit,
    ): RecognitionResult =
        coroutineScope {
            val request = createRequest(imageRef) ?: return@coroutineScope invalidImageResult()
            onProgress(RecognitionProgress(0f, "正在启动本地识别进程"))
            val process =
                try {
                    startWorkerProcess()
                } catch (_: Exception) {
                    return@coroutineScope workerFailureResult()
                }
            val response =
                async(Dispatchers.IO) {
                    try {
                        DataInputStream(process.inputStream.buffered()).use { input ->
                            DesktopOcrWorkerProtocol.readResponse(input, request.imageId, onProgress)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                }
            try {
                withContext(Dispatchers.IO) {
                    DataOutputStream(process.outputStream.buffered()).use { output ->
                        DesktopOcrWorkerProtocol.writeRequest(output, request)
                    }
                }
                val completed = waitForCompletion(process)
                if (!completed) {
                    stop(process)
                    response.cancelAndJoin()
                    return@coroutineScope RecognitionResult.Failure("PP-OCRv5 本地识别超时，请重新导入图片")
                }
                val result = response.await()
                if (process.exitValue() == SUCCESS_EXIT_CODE && result != null) {
                    result
                } else {
                    workerFailureResult()
                }
            } catch (cancelled: CancellationException) {
                stop(process)
                response.cancelAndJoin()
                throw cancelled
            } catch (_: Exception) {
                stop(process)
                response.cancelAndJoin()
                workerFailureResult()
            } finally {
                if (process.isAlive) stop(process)
            }
        }

    /** 校验图片仍是当前应用私有目录中的本次导入 JPEG。 */
    private fun createRequest(imageRef: ImageRef): DesktopOcrWorkerRequest? {
        if (imageRef.mimeType != JPEG_MIME_TYPE || !DesktopOcrWorkerProtocol.isValidImageId(imageRef.id)) {
            return null
        }
        val expected = DesktopOcrPrivateImage.resolve(imageRef.id) ?: return null
        val actual = runCatching { File(imageRef.localPath).canonicalFile }.getOrNull() ?: return null
        return DesktopOcrWorkerRequest(imageRef.id).takeIf { actual == expected }
    }

    /** 创建已关闭遥测且不继承终端输入输出的 OCR 工作进程。 */
    private fun startWorkerProcess(): Process {
        val processBuilder =
            ProcessBuilder(applicationCommand + DesktopOcrWorker.requestArgument())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
        processBuilder.environment()[DesktopOnnxRuntimePolicy.TELEMETRY_DISABLED_VARIABLE] =
            DesktopOnnxRuntimePolicy.TELEMETRY_DISABLED_VALUE
        return processBuilder.start()
    }

    /** 以短间隔检查进程状态，使页面取消能够及时终止原生推理。 */
    private suspend fun waitForCompletion(process: Process): Boolean =
        withContext(Dispatchers.IO) {
            val timeoutNanoseconds = TimeUnit.SECONDS.toNanos(timeoutSeconds)
            val startedAt = System.nanoTime()
            while (System.nanoTime() - startedAt < timeoutNanoseconds) {
                currentCoroutineContext().ensureActive()
                if (process.waitFor(WAIT_POLL_MILLISECONDS, TimeUnit.MILLISECONDS)) {
                    return@withContext true
                }
            }
            !process.isAlive
        }

    /** 超时或取消后先正常终止，仍未退出时再强制终止。 */
    private suspend fun stop(process: Process) {
        withContext(NonCancellable + Dispatchers.IO) {
            process.destroy()
            if (!process.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        }
    }

    /** 私有图片已经失效或不属于受控目录时的固定结果。 */
    private fun invalidImageResult(): RecognitionResult = RecognitionResult.Failure("私有票图已失效，请重新导入图片")

    /** 子进程启动、协议或执行失败时的固定结果。 */
    private fun workerFailureResult(): RecognitionResult =
        RecognitionResult.Failure(DesktopOcrWorkerProtocol.GENERIC_FAILURE_MESSAGE)

    /** 桌面 OCR 客户端常量。 */
    private companion object {
        /** 单张图片包含模型初始化在内的默认超时。 */
        const val DEFAULT_TIMEOUT_SECONDS = 180L

        /** 子进程终止等待秒数。 */
        const val STOP_TIMEOUT_SECONDS = 1L

        /** 等待子进程时检查协程取消的最长间隔。 */
        const val WAIT_POLL_MILLISECONDS = 100L

        /** 工作进程成功退出状态。 */
        const val SUCCESS_EXIT_CODE = 0

        /** Desktop 图片导入器固定输出的 MIME 类型。 */
        const val JPEG_MIME_TYPE = "image/jpeg"
    }
}

/** 只在受控子进程中加载模型、图片和 ONNX Runtime 的 OCR 入口。 */
object DesktopOcrWorker {
    /** 桌面宿主用于进入真实 OCR 工作进程的内部参数。 */
    private const val REQUEST_ARGUMENT = "--win-lottery-desktop-ocr-worker"

    /** 工作进程失败时输出到已丢弃标准错误的固定标识。 */
    private const val FAILURE_MESSAGE = "WINLOTTERY_DESKTOP_OCR_FAILED"

    /** 工作进程成功退出状态。 */
    private const val SUCCESS_EXIT_CODE = 0

    /** 工作进程失败退出状态。 */
    private const val FAILURE_EXIT_CODE = 2

    /** 判断本次桌面启动是否为真实 OCR 工作进程请求。 */
    fun isRequested(arguments: Array<String>): Boolean = arguments.contentEquals(arrayOf(REQUEST_ARGUMENT))

    /** 在已禁用遥测的子进程中执行一次完整 PP-OCRv5 识别。 */
    fun run(): Int =
        try {
            runBlocking {
                DesktopOnnxRuntimePolicy.requireTelemetryDisabled()
                val request =
                    DataInputStream(System.`in`.buffered()).use { input ->
                        DesktopOcrWorkerProtocol.readRequest(input)
                    }
                val imageRef = DesktopOcrPrivateImage.toImageRef(request.imageId)
                DataOutputStream(System.out.buffered()).use { output ->
                    DesktopOcrWorkerProtocol.writeResponseHeader(output)
                    val result =
                        DesktopPpOcrEngine().recognize(imageRef) { progress ->
                            DesktopOcrWorkerProtocol.writeProgress(output, progress)
                        }
                    DesktopOcrWorkerProtocol.writeResult(output, result)
                }
            }
            SUCCESS_EXIT_CODE
        } catch (_: Exception) {
            reportFailure()
        } catch (_: LinkageError) {
            reportFailure()
        }

    /** 返回真实 OCR 工作进程参数，供受控客户端构造命令。 */
    internal fun requestArgument(): String = REQUEST_ARGUMENT

    /** 输出不含异常、路径或票面内容的失败标识。 */
    private fun reportFailure(): Int {
        System.err.println(FAILURE_MESSAGE)
        return FAILURE_EXIT_CODE
    }
}

/** 只允许父子进程读取 Desktop 图片导入器创建的私有 JPEG。 */
private object DesktopOcrPrivateImage {
    /** 按随机图片标识解析并校验受控目录中的普通文件。 */
    fun resolve(imageId: String): File? {
        if (!DesktopOcrWorkerProtocol.isValidImageId(imageId)) return null
        val temporaryRoot =
            System
                .getProperty(DesktopAppPaths.JAVA_TEMPORARY_DIRECTORY_PROPERTY)
                ?.takeIf(String::isNotBlank)
                ?.let(::File)
                ?: return null
        val imageDirectory =
            runCatching { File(temporaryRoot, DesktopAppPaths.TEMPORARY_DIRECTORY_NAME).canonicalFile }
                .getOrNull()
                ?: return null
        val file =
            runCatching { File(imageDirectory, "$imageId$JPEG_EXTENSION").canonicalFile }.getOrNull()
                ?: return null
        if (file.parentFile != imageDirectory || !file.isFile || Files.isSymbolicLink(file.toPath())) {
            return null
        }
        return file.takeIf { it.length() in 1..MAXIMUM_IMAGE_BYTE_COUNT }
    }

    /** 把已验证文件转换为不携带额外元数据的轻量图片引用。 */
    fun toImageRef(imageId: String): ImageRef {
        val file = requireNotNull(resolve(imageId)) { "桌面 OCR 私有图片无效" }
        return ImageRef(
            id = imageId,
            localPath = file.absolutePath,
            mimeType = JPEG_MIME_TYPE,
            widthPixels = null,
            heightPixels = null,
        )
    }

    /** Desktop 私有图片固定边界。 */
    private const val JPEG_EXTENSION = ".jpg"

    /** Desktop 图片导入器固定输出的 MIME 类型。 */
    private const val JPEG_MIME_TYPE = "image/jpeg"

    /** 防止工作进程读取异常大的伪造图片。 */
    private const val MAXIMUM_IMAGE_BYTE_COUNT = 32L * 1024L * 1024L
}

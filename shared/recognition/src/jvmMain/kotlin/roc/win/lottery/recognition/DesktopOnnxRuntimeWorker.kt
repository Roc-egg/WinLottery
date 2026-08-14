package roc.win.lottery.recognition

import java.util.concurrent.TimeUnit

/**
 * ONNX Runtime 独立工作进程入口协议。
 *
 * 该入口只验证运行时，不接收图片路径、OCR 文本或彩票字段。
 */
object DesktopOnnxRuntimeWorker {
    /** 桌面宿主用于进入 ONNX Runtime 工作进程的内部参数。 */
    private const val REQUEST_ARGUMENT = "--win-lottery-onnx-runtime-worker"

    /** 工作进程成功响应的稳定协议标识。 */
    private const val SUCCESS_PREFIX = "WINLOTTERY_ONNX_RUNTIME_READY"

    /** 工作进程失败时输出的无敏感信息标识。 */
    private const val FAILURE_MESSAGE = "WINLOTTERY_ONNX_RUNTIME_FAILED"

    /** 工作进程成功退出状态。 */
    private const val SUCCESS_EXIT_CODE = 0

    /** 工作进程失败退出状态。 */
    private const val FAILURE_EXIT_CODE = 2

    /** 判断本次桌面启动是否为 ONNX Runtime 工作进程请求。 */
    fun isRequested(arguments: Array<String>): Boolean = arguments.contentEquals(arrayOf(REQUEST_ARGUMENT))

    /**
     * 在已禁用遥测的独立进程中加载 ONNX Runtime，并输出不含设备信息的响应。
     *
     * @return 供桌面宿主直接作为进程退出状态使用的状态码。
     */
    fun run(): Int =
        try {
            println(encode(DesktopOnnxRuntimeProbe.inspect()))
            SUCCESS_EXIT_CODE
        } catch (_: Exception) {
            reportFailure()
        } catch (_: LinkageError) {
            reportFailure()
        }

    /** 返回工作进程请求参数，供受控客户端构造子进程命令。 */
    internal fun requestArgument(): String = REQUEST_ARGUMENT

    /** 解析工作进程的唯一成功响应，拒绝额外日志和未知字段。 */
    internal fun decode(output: String): DesktopOnnxRuntimeInfo? {
        val fields = output.trim().split('\t')
        if (fields.size != 3 || fields[0] != SUCCESS_PREFIX || fields[1].isBlank()) {
            return null
        }

        val providers =
            fields[2]
                .split(',')
                .filter(String::isNotBlank)
                .toSet()
        return providers.takeIf(Set<String>::isNotEmpty)?.let {
            DesktopOnnxRuntimeInfo(version = fields[1], providerNames = it)
        }
    }

    /** 把运行时信息编码为不含路径和设备标识的单行响应。 */
    private fun encode(info: DesktopOnnxRuntimeInfo): String =
        listOf(
            SUCCESS_PREFIX,
            info.version,
            info.providerNames.sorted().joinToString(","),
        ).joinToString("\t")

    /** 输出通用失败标识，避免异常文本泄露本机路径。 */
    private fun reportFailure(): Int {
        System.err.println(FAILURE_MESSAGE)
        return FAILURE_EXIT_CODE
    }
}

/**
 * 通过独立本地进程加载 ONNX Runtime 的受控客户端。
 *
 * @property applicationCommand 当前桌面应用的完整启动命令，不包含工作进程参数。
 * @property timeoutSeconds 等待工作进程完成的秒数上限。
 */
class DesktopOnnxRuntimeWorkerClient(
    private val applicationCommand: List<String>,
    private val timeoutSeconds: Long = 15,
) {
    init {
        require(applicationCommand.isNotEmpty() && applicationCommand.all(String::isNotBlank)) {
            "桌面应用启动命令不能为空"
        }
        require(timeoutSeconds > 0) { "工作进程超时时间必须大于零" }
    }

    /**
     * 启动只在本机运行的子进程，并在其首次加载 ONNX Runtime 前禁用遥测。
     *
     * @return 子进程验证过的运行时版本和执行提供器。
     */
    fun inspect(): DesktopOnnxRuntimeInfo {
        val processBuilder =
            ProcessBuilder(applicationCommand + DesktopOnnxRuntimeWorker.requestArgument())
                .redirectErrorStream(true)
        processBuilder.environment()[DesktopOnnxRuntimePolicy.TELEMETRY_DISABLED_VARIABLE] =
            DesktopOnnxRuntimePolicy.TELEMETRY_DISABLED_VALUE

        val process = processBuilder.start()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            stop(process)
            error("ONNX Runtime 工作进程执行超时")
        }

        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        check(process.exitValue() == 0) { "ONNX Runtime 工作进程执行失败" }
        return checkNotNull(DesktopOnnxRuntimeWorker.decode(output)) {
            "ONNX Runtime 工作进程响应无效"
        }
    }

    /** 超时后先正常终止子进程，仍未退出时再强制终止。 */
    private fun stop(process: Process) {
        process.destroy()
        if (!process.waitFor(1, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(1, TimeUnit.SECONDS)
        }
    }
}

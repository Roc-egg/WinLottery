package roc.win.lottery.recognition

import ai.onnxruntime.OrtEnvironment
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 当前测试文件生成的独立 JVM 入口类名。 */
private const val WORKER_FIXTURE_MAIN_CLASS =
    "roc.win.lottery.recognition.DesktopOnnxRuntimeWorkerTestKt"

/** 供测试子进程调用的 ONNX Runtime 工作进程入口。 */
fun main(arguments: Array<String>) {
    if (!DesktopOnnxRuntimeWorker.isRequested(arguments)) {
        exitProcess(3)
    }
    exitProcess(DesktopOnnxRuntimeWorker.run())
}

/** ONNX Runtime 独立工作进程协议测试。 */
class DesktopOnnxRuntimeWorkerTest {
    /** 客户端必须为子进程注入遥测禁用变量并取得 CPU Provider。 */
    @Test
    fun clientLoadsRuntimeInTelemetryDisabledChildProcess() {
        val runtime = DesktopOnnxRuntimeWorkerClient(workerFixtureCommand()).inspect()

        assertEquals("1.29.0", runtime.version)
        assertTrue("CPU" in runtime.providerNames)
    }

    /** 缺少进程级遥测禁用变量时，工作进程必须在加载原生库前失败。 */
    @Test
    fun workerRejectsMissingTelemetryPolicy() {
        val processBuilder =
            ProcessBuilder(workerFixtureCommand() + DesktopOnnxRuntimeWorker.requestArgument())
                .redirectErrorStream(true)
        processBuilder.environment().remove(DesktopOnnxRuntimePolicy.TELEMETRY_DISABLED_VARIABLE)

        val process = processBuilder.start()
        assertTrue(process.waitFor(15, TimeUnit.SECONDS))
        assertEquals(2, process.exitValue())
        assertEquals(
            "WINLOTTERY_ONNX_RUNTIME_FAILED",
            process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().trim() },
        )
    }

    /** 协议解析必须拒绝 ONNX Runtime 警告或其他额外输出。 */
    @Test
    fun protocolRejectsUnexpectedOutput() {
        assertNull(
            DesktopOnnxRuntimeWorker.decode(
                "unexpected\nWINLOTTERY_ONNX_RUNTIME_READY\t1.29.0\tCPU",
            ),
        )
    }

    /** 构造只包含测试入口、业务类、ONNX Runtime 和 Kotlin 运行库的子进程命令。 */
    private fun workerFixtureCommand(): List<String> {
        val classPath =
            listOf(
                DesktopOnnxRuntimeWorkerTest::class.java,
                DesktopOnnxRuntimeWorker::class.java,
                OrtEnvironment::class.java,
                Unit::class.java,
            ).map { type ->
                Path
                    .of(
                        type.protectionDomain.codeSource.location
                            .toURI(),
                    ).toString()
            }.distinct()
                .joinToString(File.pathSeparator)
        return listOf(javaExecutable(), "-cp", classPath, WORKER_FIXTURE_MAIN_CLASS)
    }

    /** 返回当前测试 JDK 的跨平台 Java 可执行文件。 */
    private fun javaExecutable(): String {
        val executableName =
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                "java.exe"
            } else {
                "java"
            }
        return Path.of(System.getProperty("java.home"), "bin", executableName).toString()
    }
}

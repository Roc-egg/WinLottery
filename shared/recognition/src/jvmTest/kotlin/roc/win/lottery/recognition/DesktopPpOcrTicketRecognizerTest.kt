package roc.win.lottery.recognition

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 当前测试文件生成的独立 OCR 工作进程入口类名。 */
private const val OCR_WORKER_FIXTURE_MAIN_CLASS =
    "roc.win.lottery.recognition.DesktopPpOcrTicketRecognizerTestKt"

/** 供测试子进程调用的 Desktop OCR 工作进程入口。 */
fun main(arguments: Array<String>) {
    if (!DesktopOcrWorker.isRequested(arguments)) {
        exitProcess(3)
    }
    exitProcess(DesktopOcrWorker.run())
}

/** Desktop PP-OCR 受控子进程集成测试。 */
class DesktopPpOcrTicketRecognizerTest {
    /** 合成票面应通过匿名管道执行随包模型，并产出标准 OCR 文档。 */
    @Test
    fun workerExecutesBundledPpOcrForPrivateSyntheticImage() =
        runBlocking {
            val paths = DesktopAppPaths()
            try {
                val imageRef = DesktopOcrSyntheticVerificationImage.create()
                val progress = mutableListOf<RecognitionProgress>()

                val result =
                    DesktopPpOcrTicketRecognizer(workerFixtureCommand()).recognize(
                        imageRef = imageRef,
                        onProgress = progress::add,
                    )

                val document = assertIs<RecognitionResult.Success>(result).document
                assertEquals(imageRef.id, document.imageId)
                assertEquals("PP-OCRv5 mobile + ONNX Runtime", document.engineName)
                assertTrue(document.lines.isNotEmpty())
                assertTrue(progress.isNotEmpty())
                assertEquals(0f, progress.first().fraction)
                assertEquals(1f, progress.last().fraction)
                assertTrue(
                    progress.zipWithNext().all { (previous, next) ->
                        next.fraction >= previous.fraction
                    },
                )
            } finally {
                paths.clearTemporaryImages()
            }
        }

    /** 缺少进程级遥测禁用变量时，OCR 工作进程必须在读取图片请求前失败。 */
    @Test
    fun workerRejectsMissingTelemetryPolicy() {
        val processBuilder =
            ProcessBuilder(workerFixtureCommand() + DesktopOcrWorker.requestArgument())
                .redirectErrorStream(true)
        processBuilder.environment().remove(DesktopOnnxRuntimePolicy.TELEMETRY_DISABLED_VARIABLE)

        val process = processBuilder.start()
        process.outputStream.close()

        assertTrue(process.waitFor(15, TimeUnit.SECONDS))
        assertEquals(2, process.exitValue())
        assertEquals(
            "WINLOTTERY_DESKTOP_OCR_FAILED",
            process.inputStream.bufferedReader(Charsets.UTF_8).use { input -> input.readText().trim() },
        )
    }

    /** 客户端必须在启动子进程前拒绝目录外图片和伪造路径。 */
    @Test
    fun clientRejectsImageOutsidePrivateDirectoryBeforeStartingWorker() =
        runBlocking {
            val outside = Files.createTempFile("win-lottery-outside-", ".jpg").toFile()
            try {
                outside.writeText("不包含票面信息的边界测试")
                val result =
                    DesktopPpOcrTicketRecognizer(listOf("不存在的桌面启动器")).recognize(
                        ImageRef(
                            id = UUID.randomUUID().toString(),
                            localPath = outside.absolutePath,
                            mimeType = "image/jpeg",
                            widthPixels = null,
                            heightPixels = null,
                        ),
                    )

                assertEquals(
                    "私有票图已失效，请重新导入图片",
                    assertIs<RecognitionResult.Failure>(result).message,
                )
            } finally {
                outside.delete()
            }
        }

    /** 页面协程取消后，等待中的 OCR 子进程必须被及时终止。 */
    @Test
    fun cancellationStopsUnresponsiveWorkerPromptly() =
        runBlocking {
            val paths = DesktopAppPaths()
            try {
                val imageRef = DesktopOcrSyntheticVerificationImage.create()
                val recognition =
                    async {
                        DesktopPpOcrTicketRecognizer(
                            applicationCommand = workerFixtureCommand(HANGING_WORKER_FIXTURE_MAIN_CLASS),
                            timeoutSeconds = 30,
                        ).recognize(imageRef)
                    }

                delay(WORKER_START_DELAY_MILLISECONDS)
                withTimeout(CANCELLATION_ASSERTION_TIMEOUT_MILLISECONDS) {
                    recognition.cancelAndJoin()
                }

                assertTrue(recognition.isCancelled)
            } finally {
                paths.clearTemporaryImages()
            }
        }

    /** 构造只向子进程暴露当前测试运行时的跨平台 Java 命令。 */
    private fun workerFixtureCommand(mainClass: String = OCR_WORKER_FIXTURE_MAIN_CLASS): List<String> {
        val classPath =
            checkNotNull(System.getProperty(TEST_CLASSPATH_PROPERTY)?.takeIf(String::isNotBlank)) {
                "缺少 Desktop OCR 测试运行时类路径"
            }
        return listOf(javaExecutable(), "-cp", classPath, mainClass)
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

    /** 测试进程常量。 */
    private companion object {
        /** Gradle 注入的完整 JVM 测试运行时属性名。 */
        const val TEST_CLASSPATH_PROPERTY = "winLottery.desktopOcrTestClasspath"

        /** 故意不响应匿名管道的测试进程入口。 */
        const val HANGING_WORKER_FIXTURE_MAIN_CLASS =
            "roc.win.lottery.recognition.DesktopOcrHangingWorkerFixtureKt"

        /** 留给测试子进程完成启动的等待时间。 */
        const val WORKER_START_DELAY_MILLISECONDS = 300L

        /** 取消后客户端和工作进程必须完成回收的时间上限。 */
        const val CANCELLATION_ASSERTION_TIMEOUT_MILLISECONDS = 5_000L
    }
}

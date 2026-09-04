package roc.win.lottery

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import roc.win.lottery.recognition.DesktopOcrPackageVerifier
import roc.win.lottery.recognition.DesktopOcrWorker
import roc.win.lottery.recognition.DesktopOnnxRuntimeWorker
import roc.win.lottery.recognition.DesktopOnnxRuntimeWorkerClient
import java.awt.Dimension
import java.awt.Frame
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/** 由桌面宿主触发父子进程 ONNX Runtime 验收的内部参数。 */
private const val VERIFY_ONNX_RUNTIME_ARGUMENT = "--verify-packaged-onnx-runtime"

/** 由桌面宿主触发最终分发包真实 OCR 验收的内部参数。 */
private const val VERIFY_DESKTOP_OCR_ARGUMENT = "--verify-packaged-desktop-ocr"

/** 桌面应用在开发 JVM 中使用的入口类名。 */
private const val DESKTOP_MAIN_CLASS = "roc.win.lottery.DesktopEntryKt"

/** 启动 Windows 或 macOS 桌面应用入口，并优先处理无界面的内部工作模式。 */
fun main(arguments: Array<String>) {
    when {
        arguments.contentEquals(arrayOf(VERIFY_ONNX_RUNTIME_ARGUMENT)) -> {
            exitProcess(verifyOnnxRuntimeWorker())
        }

        arguments.contentEquals(arrayOf(VERIFY_DESKTOP_OCR_ARGUMENT)) -> {
            exitProcess(verifyDesktopOcrWorker())
        }

        DesktopOnnxRuntimeWorker.isRequested(arguments) -> {
            exitProcess(DesktopOnnxRuntimeWorker.run())
        }

        DesktopOcrWorker.isRequested(arguments) -> {
            exitProcess(DesktopOcrWorker.run())
        }

        else -> {
            startDesktopApplication(currentApplicationCommand())
        }
    }
}

/** 启动普通桌面窗口，不在主进程中加载 ONNX Runtime。 */
private fun startDesktopApplication(applicationCommand: List<String>) {
    configureMacDesktopProperties()
    var ownerWindow: Frame? = null
    val container = createDesktopAppContainer({ ownerWindow }, applicationCommand)
    val temporaryImageCleanupHook =
        Thread(
            { container.appPaths.clearTemporaryImages() },
            TEMPORARY_IMAGE_CLEANUP_THREAD_NAME,
        )
    Runtime.getRuntime().addShutdownHook(temporaryImageCleanupHook)
    application {
        Window(
            onCloseRequest = ::exitApplication,
            state =
                rememberWindowState(
                    position = WindowPosition(Alignment.Center),
                    width = DEFAULT_WINDOW_WIDTH,
                    height = DEFAULT_WINDOW_HEIGHT,
                ),
            title = "给我中",
        ) {
            DisposableEffect(window) {
                ownerWindow = window
                window.minimumSize = Dimension(MINIMUM_WINDOW_WIDTH_PIXELS, MINIMUM_WINDOW_HEIGHT_PIXELS)
                onDispose {
                    ownerWindow = null
                }
            }
            App(container)
        }
    }
}

/** 开发运行时为 macOS 设置 Dock 名称和系统菜单栏习惯。 */
private fun configureMacDesktopProperties() {
    if (!System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) return
    System.setProperty("apple.awt.application.name", "给我中")
    System.setProperty("apple.laf.useScreenMenuBar", "true")
}

/** 由当前桌面进程启动受控子进程，验证遥测禁用和 ONNX Runtime 加载。 */
private fun verifyOnnxRuntimeWorker(): Int =
    try {
        val runtime = DesktopOnnxRuntimeWorkerClient(currentApplicationCommand()).inspect()
        check("CPU" in runtime.providerNames) { "ONNX Runtime CPU Provider 不可用" }
        println("WINLOTTERY_ONNX_RUNTIME_CLIENT_OK\t${runtime.version}\tCPU")
        0
    } catch (_: Exception) {
        System.err.println("WINLOTTERY_ONNX_RUNTIME_CLIENT_FAILED")
        2
    } catch (_: LinkageError) {
        System.err.println("WINLOTTERY_ONNX_RUNTIME_CLIENT_FAILED")
        2
    }

/** 使用合成票面验证最终分发启动器、模型资源和真实隔离 OCR 子进程。 */
private fun verifyDesktopOcrWorker(): Int =
    if (DesktopOcrPackageVerifier.verify(currentApplicationCommand())) {
        println("WINLOTTERY_DESKTOP_OCR_CLIENT_OK")
        0
    } else {
        System.err.println("WINLOTTERY_DESKTOP_OCR_CLIENT_FAILED")
        2
    }

/** 返回当前分发启动器，开发环境则重建等价 Java 命令。 */
private fun currentApplicationCommand(): List<String> {
    val packagedLauncher = System.getProperty("jpackage.app-path")?.takeIf(String::isNotBlank)
    if (packagedLauncher != null) {
        return listOf(packagedLauncher)
    }

    val javaExecutable =
        Path.of(
            System.getProperty("java.home"),
            "bin",
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                "java.exe"
            } else {
                "java"
            },
        )
    if (Files.isExecutable(javaExecutable)) {
        return listOf(
            javaExecutable.toString(),
            "-cp",
            System.getProperty("java.class.path"),
            DESKTOP_MAIN_CLASS,
        )
    }

    val currentCommand =
        ProcessHandle
            .current()
            .info()
            .command()
            .orElse(null)
    return listOf(checkNotNull(currentCommand) { "无法定位当前桌面启动器" })
}

/** 桌面窗口默认宽度。 */
private val DEFAULT_WINDOW_WIDTH = 1180.dp

/** 桌面窗口默认高度。 */
private val DEFAULT_WINDOW_HEIGHT = 820.dp

/** 防止桌面布局被缩小到导航和表单不可用的最小宽度。 */
private const val MINIMUM_WINDOW_WIDTH_PIXELS = 820

/** 防止桌面布局被缩小到主要操作不可见的最小高度。 */
private const val MINIMUM_WINDOW_HEIGHT_PIXELS = 640

/** JVM 正常退出时清扫私有临时票图的线程名称。 */
private const val TEMPORARY_IMAGE_CLEANUP_THREAD_NAME = "WinLottery临时票图清扫"

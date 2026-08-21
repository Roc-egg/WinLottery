package roc.win.lottery

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import roc.win.lottery.recognition.DesktopOnnxRuntimeWorker
import roc.win.lottery.recognition.DesktopOnnxRuntimeWorkerClient
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/** 由桌面宿主触发父子进程 ONNX Runtime 验收的内部参数。 */
private const val VERIFY_ONNX_RUNTIME_ARGUMENT = "--verify-packaged-onnx-runtime"

/** 桌面应用在开发 JVM 中使用的入口类名。 */
private const val DESKTOP_MAIN_CLASS = "roc.win.lottery.DesktopEntryKt"

/** 启动 Windows 或 macOS 桌面应用入口，并优先处理无界面的内部工作模式。 */
fun main(arguments: Array<String>) {
    when {
        arguments.contentEquals(arrayOf(VERIFY_ONNX_RUNTIME_ARGUMENT)) -> {
            exitProcess(verifyOnnxRuntimeWorker())
        }

        DesktopOnnxRuntimeWorker.isRequested(arguments) -> {
            exitProcess(DesktopOnnxRuntimeWorker.run())
        }

        else -> {
            startDesktopApplication()
        }
    }
}

/** 启动普通桌面窗口，不在主进程中加载 ONNX Runtime。 */
private fun startDesktopApplication() =
    application {
        Window(
            onCloseRequest = ::exitApplication,
            state = rememberWindowState(width = 1080.dp, height = 780.dp),
            title = "给我中",
        ) {
            val container = remember(window) { createDesktopImportContainer { window } }
            App(container)
        }
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

package roc.win.lottery

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/** 启动 Windows 或 macOS 桌面应用入口。 */
fun main() =
    application {
        Window(
            onCloseRequest = ::exitApplication,
            state = rememberWindowState(width = 1080.dp, height = 780.dp),
            title = "彩票中奖测算",
        ) {
            val container = remember(window) { createDesktopImportContainer { window } }
            App(container)
        }
    }

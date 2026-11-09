package roc.win.lottery

import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController
import roc.win.lottery.recognition.IOSOnnxRuntime

/** 创建已装配 iOS 本地识别 PoC、供 SwiftUI 宿主嵌入的共享 Compose 控制器。 */
@Suppress("FunctionName")
fun MainViewController(onnxRuntime: IOSOnnxRuntime): UIViewController {
    var viewController: UIViewController? = null
    val container = createIOSRecognitionContainer(onnxRuntime) { viewController }
    viewController =
        ComposeUIViewController(
            configure = {
                // 外壳已用输入法内边距让正文避让键盘；默认的焦点上推会再把整个视图上移，两者叠加留出空白。
                onFocusBehavior = OnFocusBehavior.DoNothing
            },
        ) { App(container) }
    return requireNotNull(viewController)
}

package roc.win.lottery

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** 创建已装配 iOS 本地识别 PoC、供 SwiftUI 宿主嵌入的共享 Compose 控制器。 */
@Suppress("FunctionName")
fun MainViewController(): UIViewController {
    var viewController: UIViewController? = null
    val container = createIOSRecognitionContainer { viewController }
    viewController = ComposeUIViewController { App(container) }
    return requireNotNull(viewController)
}

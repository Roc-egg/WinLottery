import UIKit
import SwiftUI
import Shared

/// 把共享 Compose 控制器桥接到 SwiftUI。
struct ComposeView: UIViewControllerRepresentable {
    /// 创建共享 Compose 控制器。
    func makeUIViewController(context: Self.Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    /// 共享界面自行持有状态，SwiftUI 无需同步额外属性。
    func updateUIViewController(_ uiViewController: UIViewController, context: Self.Context) {}
}

/// iOS 宿主的根视图。
struct ContentView: View {
    /// 全屏展示共享 Compose 界面。
    var body: some View {
        ComposeView()
            .ignoresSafeArea()
    }
}

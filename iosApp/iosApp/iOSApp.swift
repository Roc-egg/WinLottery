import Foundation
import SwiftUI

/// iOS 应用入口。
@main
struct iOSApp: App {
    /// 创建应用，并只在 Debug 构建收到显式验收参数时制造受控崩溃。
    init() {
        #if DEBUG
            if ProcessInfo.processInfo.environment["WINLOTTERY_DEBUG_CRASH_ON_LAUNCH"] == "1" {
                fatalError("WinLotteryControlledCrashAcceptance")
            }
        #endif
    }

    /// 创建包含共享界面的主窗口。
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

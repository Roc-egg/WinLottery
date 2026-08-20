import Foundation
import SwiftUI
import UIKit

/// iOS 应用入口。
@main
struct iOSApp: App {
    #if DEBUG
        /// 保持 Debug 内存警告观察者在应用生命周期内有效。
        private let memoryWarningObserver: NSObjectProtocol
    #endif

    /// 创建应用，并只在 Debug 构建收到显式验收参数时制造受控崩溃。
    init() {
        #if DEBUG
            let memoryWarningMarker = FileManager.default.temporaryDirectory
                .appendingPathComponent("WinLotteryMemoryPressureAcceptance")
            try? FileManager.default.removeItem(at: memoryWarningMarker)
            memoryWarningObserver = NotificationCenter.default.addObserver(
                forName: UIApplication.didReceiveMemoryWarningNotification,
                object: nil,
                queue: .main
            ) { _ in
                _ = FileManager.default.createFile(
                    atPath: memoryWarningMarker.path,
                    contents: Data()
                )
            }
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

package roc.win.lottery.app.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import platform.UIKit.UIApplication
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import roc.win.lottery.persistence.ThemePreference

/**
 * iOS 覆盖应用窗口的界面风格：状态栏文字、键盘、系统相册选择器和提示框随应用深浅色切换；
 * 跟随系统时撤销覆盖，界面重新读取系统外观。
 */
@Composable
internal actual fun PlatformAppearanceEffect(
    preference: ThemePreference,
    darkTheme: Boolean,
) {
    LaunchedEffect(preference) {
        val style =
            when (preference) {
                ThemePreference.SYSTEM -> UIUserInterfaceStyle.UIUserInterfaceStyleUnspecified
                ThemePreference.LIGHT -> UIUserInterfaceStyle.UIUserInterfaceStyleLight
                ThemePreference.DARK -> UIUserInterfaceStyle.UIUserInterfaceStyleDark
            }
        // 启动首帧时窗口可能尚未挂上场景，下一帧再设置一次。
        applyToAllWindows(style)
        withFrameNanos {}
        applyToAllWindows(style)
    }
}

/** 为当前已连接场景中的全部窗口设置界面风格。 */
private fun applyToAllWindows(style: UIUserInterfaceStyle) {
    UIApplication.sharedApplication.connectedScenes.forEach { scene ->
        (scene as? UIWindowScene)?.windows?.forEach { window ->
            (window as? UIWindow)?.overrideUserInterfaceStyle = style
        }
    }
}

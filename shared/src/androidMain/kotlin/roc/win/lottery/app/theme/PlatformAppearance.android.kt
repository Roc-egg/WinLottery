package roc.win.lottery.app.theme

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import roc.win.lottery.persistence.ThemePreference

/** Android 按主题偏好重设边到边系统栏样式，状态栏与导航栏图标随应用深浅色切换。 */
@Composable
internal actual fun PlatformAppearanceEffect(
    preference: ThemePreference,
    darkTheme: Boolean,
) {
    val activity = LocalContext.current.findComponentActivity() ?: return
    DisposableEffect(activity, preference, darkTheme) {
        val statusBarStyle =
            when (preference) {
                ThemePreference.SYSTEM -> SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
                ThemePreference.LIGHT -> SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                ThemePreference.DARK -> SystemBarStyle.dark(Color.TRANSPARENT)
            }
        val navigationBarStyle =
            when (preference) {
                ThemePreference.SYSTEM -> SystemBarStyle.auto(LIGHT_NAVIGATION_SCRIM, DARK_NAVIGATION_SCRIM)
                ThemePreference.LIGHT -> SystemBarStyle.light(LIGHT_NAVIGATION_SCRIM, DARK_NAVIGATION_SCRIM)
                ThemePreference.DARK -> SystemBarStyle.dark(DARK_NAVIGATION_SCRIM)
            }
        activity.enableEdgeToEdge(statusBarStyle, navigationBarStyle)
        onDispose {}
    }
}

/** 沿上下文包装链找到承载共享界面的 Activity。 */
private tailrec fun Context.findComponentActivity(): ComponentActivity? =
    when (this) {
        is ComponentActivity -> this
        is ContextWrapper -> baseContext.findComponentActivity()
        else -> null
    }

/** 三键导航时浅色导航栏的半透明遮罩，与 androidx.activity 默认值一致。 */
private val LIGHT_NAVIGATION_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)

/** 三键导航时深色导航栏的半透明遮罩，与 androidx.activity 默认值一致。 */
private val DARK_NAVIGATION_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)

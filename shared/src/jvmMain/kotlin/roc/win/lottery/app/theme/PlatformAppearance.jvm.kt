package roc.win.lottery.app.theme

import androidx.compose.runtime.Composable
import roc.win.lottery.persistence.ThemePreference

/** 桌面窗口内容全部由共享界面绘制，不需要额外同步系统界面元素。 */
@Composable
internal actual fun PlatformAppearanceEffect(
    preference: ThemePreference,
    darkTheme: Boolean,
) = Unit

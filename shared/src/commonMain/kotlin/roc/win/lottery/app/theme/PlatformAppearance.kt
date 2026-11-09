package roc.win.lottery.app.theme

import androidx.compose.runtime.Composable
import roc.win.lottery.persistence.ThemePreference

/**
 * 让系统界面元素与应用主题保持一致。
 *
 * 用户手动选择浅色或深色时，状态栏图标、导航栏、键盘和系统弹窗也应随之切换，否则会出现深色页面上的深色状态栏文字。
 *
 * @param preference 用户选择的主题偏好。
 * @param darkTheme 当前实际使用的是否为深色外观。
 */
@Composable
internal expect fun PlatformAppearanceEffect(
    preference: ThemePreference,
    darkTheme: Boolean,
)

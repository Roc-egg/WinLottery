package roc.win.lottery.app.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 应用品牌红，用于主操作和关键状态。 */
private val BrandRed = Color(0xFFB4232D)

/** 深红色，用于按压和强调。 */
private val BrandRedDark = Color(0xFF7D1821)

/** 冷灰蓝，用于次要操作。 */
private val UtilityBlue = Color(0xFF315A70)

/** 页面背景色。 */
private val PaperBackground = Color(0xFFF7F7F5)

/** 票据表面色。 */
private val PaperSurface = Color(0xFFFFFFFF)

/** 主文字色。 */
private val Ink = Color(0xFF202124)

/** 次文字色。 */
private val MutedInk = Color(0xFF60646B)

/** 分隔线颜色。 */
private val Divider = Color(0xFFDADCE0)

/** 彩票应用的浅色配色。 */
private val LotteryColorScheme: ColorScheme =
    lightColorScheme(
        primary = BrandRed,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFFFDADC),
        onPrimaryContainer = BrandRedDark,
        secondary = UtilityBlue,
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFD5E8F0),
        onSecondaryContainer = Color(0xFF173A4A),
        background = PaperBackground,
        onBackground = Ink,
        surface = PaperSurface,
        onSurface = Ink,
        surfaceVariant = Color(0xFFEEEFEA),
        onSurfaceVariant = MutedInk,
        outline = Color(0xFF7A7D82),
        outlineVariant = Divider,
        error = Color(0xFFBA1A1A),
    )

/** 应用排版，控制面板内使用克制且可扫描的字号。 */
private val LotteryTypography =
    Typography(
        displaySmall =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 34.sp,
                lineHeight = 42.sp,
                letterSpacing = 0.sp,
            ),
        headlineMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 24.sp,
                lineHeight = 32.sp,
                letterSpacing = 0.sp,
            ),
        titleLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
                lineHeight = 28.sp,
                letterSpacing = 0.sp,
            ),
        titleMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.sp,
            ),
        bodyLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 25.sp,
                letterSpacing = 0.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                letterSpacing = 0.sp,
            ),
        labelLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.sp,
            ),
    )

/** 应用共享主题。 */
@Composable
fun LotteryTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LotteryColorScheme,
        typography = LotteryTypography,
        content = content,
    )
}

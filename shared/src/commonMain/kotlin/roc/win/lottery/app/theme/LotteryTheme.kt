package roc.win.lottery.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Material 配色之外的品牌语义色。
 *
 * @property redBall 红球、前区等主号码的主色。
 * @property redBallDeep 红球渐变的深色端。
 * @property blueBall 蓝球、后区等次号码的主色。
 * @property blueBallDeep 蓝球渐变的深色端。
 * @property heroStart 品牌主卡渐变起点。
 * @property heroEnd 品牌主卡渐变终点。
 * @property success 已就绪、已确认等正向状态色。
 * @property successContainer 正向状态的浅色容器。
 * @property onSuccessContainer 正向状态容器上的文字色。
 * @property warning 待确认、未完成等提醒色。
 * @property warningContainer 提醒状态的浅色容器。
 * @property onWarningContainer 提醒状态容器上的文字色。
 * @property hairline 卡片描边和分隔线使用的极细线色。
 * @property isDark 当前是否为深色外观。
 */
@Immutable
data class LotteryExtendedColors(
    val redBall: Color,
    val redBallDeep: Color,
    val blueBall: Color,
    val blueBallDeep: Color,
    val heroStart: Color,
    val heroEnd: Color,
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val hairline: Color,
    val isDark: Boolean,
)

/** 浅色外观的品牌语义色。 */
private val LightExtendedColors =
    LotteryExtendedColors(
        redBall = Color(0xFFE5343F),
        redBallDeep = Color(0xFFC21D2A),
        blueBall = Color(0xFF2F74E6),
        blueBallDeep = Color(0xFF1C55C2),
        heroStart = Color(0xFFEF3E4A),
        heroEnd = Color(0xFFB8162A),
        success = Color(0xFF16934A),
        successContainer = Color(0xFFE6F6EC),
        onSuccessContainer = Color(0xFF0E5E30),
        warning = Color(0xFFB66A00),
        warningContainer = Color(0xFFFFF3DD),
        onWarningContainer = Color(0xFF6F4100),
        hairline = Color(0xFFE9EBEF),
        isDark = false,
    )

/** 深色外观的品牌语义色。 */
private val DarkExtendedColors =
    LotteryExtendedColors(
        redBall = Color(0xFFEC4651),
        redBallDeep = Color(0xFFC0232F),
        blueBall = Color(0xFF3D80EE),
        blueBallDeep = Color(0xFF2158C4),
        heroStart = Color(0xFFD9303D),
        heroEnd = Color(0xFF8E1222),
        success = Color(0xFF4CCB7E),
        successContainer = Color(0xFF15301F),
        onSuccessContainer = Color(0xFFA6E9BF),
        warning = Color(0xFFF2AE45),
        warningContainer = Color(0xFF33260F),
        onWarningContainer = Color(0xFFFAD9A2),
        hairline = Color(0xFF272A31),
        isDark = true,
    )

/** 当前外观的品牌语义色。 */
val LocalLotteryColors = staticCompositionLocalOf { LightExtendedColors }

/** 彩票应用的浅色配色：冷调浅灰底、纯白卡片和明亮品牌红。 */
private val LightLotteryColorScheme: ColorScheme =
    lightColorScheme(
        primary = Color(0xFFD92B37),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFFDEBED),
        onPrimaryContainer = Color(0xFF9E1622),
        inversePrimary = Color(0xFFFF8A92),
        secondary = Color(0xFF2B6CDF),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFE8F0FD),
        onSecondaryContainer = Color(0xFF15408A),
        tertiary = Color(0xFFB7791F),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFFF2DB),
        onTertiaryContainer = Color(0xFF6B4300),
        background = Color(0xFFF4F5F7),
        onBackground = Color(0xFF15171B),
        surface = Color.White,
        onSurface = Color(0xFF15171B),
        surfaceVariant = Color(0xFFF0F1F4),
        onSurfaceVariant = Color(0xFF666C78),
        surfaceTint = Color.Transparent,
        surfaceDim = Color(0xFFE3E5E9),
        surfaceBright = Color.White,
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF8F9FA),
        surfaceContainer = Color(0xFFF2F3F5),
        surfaceContainerHigh = Color(0xFFECEEF1),
        surfaceContainerHighest = Color(0xFFE5E7EB),
        inverseSurface = Color(0xFF24272D),
        inverseOnSurface = Color(0xFFF2F3F5),
        outline = Color(0xFFC4C8D0),
        outlineVariant = Color(0xFFE6E8EC),
        error = Color(0xFFD12A2A),
        onError = Color.White,
        errorContainer = Color(0xFFFDECEC),
        onErrorContainer = Color(0xFF8C1515),
        scrim = Color.Black,
    )

/** 彩票应用的深色配色：近黑底、分层灰卡片和提亮的品牌红。 */
private val DarkLotteryColorScheme: ColorScheme =
    darkColorScheme(
        primary = Color(0xFFEF4651),
        onPrimary = Color.White,
        primaryContainer = Color(0xFF3A1519),
        onPrimaryContainer = Color(0xFFFFB3B8),
        inversePrimary = Color(0xFFD92B37),
        secondary = Color(0xFF6EA2FF),
        onSecondary = Color(0xFF06204D),
        secondaryContainer = Color(0xFF15264A),
        onSecondaryContainer = Color(0xFFC3D7FF),
        tertiary = Color(0xFFF2B65A),
        onTertiary = Color(0xFF3D2600),
        tertiaryContainer = Color(0xFF3A2A10),
        onTertiaryContainer = Color(0xFFFAD9A2),
        background = Color(0xFF0D0E11),
        onBackground = Color(0xFFECEDF0),
        surface = Color(0xFF17191D),
        onSurface = Color(0xFFECEDF0),
        surfaceVariant = Color(0xFF22252A),
        onSurfaceVariant = Color(0xFF9EA4AF),
        surfaceTint = Color.Transparent,
        surfaceDim = Color(0xFF0D0E11),
        surfaceBright = Color(0xFF2C2F35),
        surfaceContainerLowest = Color(0xFF0B0C0E),
        surfaceContainerLow = Color(0xFF141619),
        surfaceContainer = Color(0xFF1C1E23),
        surfaceContainerHigh = Color(0xFF24272C),
        surfaceContainerHighest = Color(0xFF2D3036),
        inverseSurface = Color(0xFFECEDF0),
        inverseOnSurface = Color(0xFF1C1E23),
        outline = Color(0xFF4A4F58),
        outlineVariant = Color(0xFF2A2D33),
        error = Color(0xFFFF6B6B),
        onError = Color(0xFF4A0707),
        errorContainer = Color(0xFF3B1517),
        onErrorContainer = Color(0xFFFFB4B0),
        scrim = Color.Black,
    )

/** 数字统一使用等宽数字，号码、金额和期号上下对齐。 */
private const val TABULAR_NUMBERS = "tnum"

/** 构造统一字族与等宽数字的文字样式。 */
private fun lotteryTextStyle(
    weight: FontWeight,
    size: Int,
    lineHeight: Int,
    letterSpacing: Double = 0.0,
): TextStyle =
    TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        letterSpacing = letterSpacing.sp,
        fontFeatureSettings = TABULAR_NUMBERS,
    )

/** 应用排版：大标题加粗收紧，正文舒展，标签克制。 */
private val LotteryTypography =
    Typography(
        displayLarge = lotteryTextStyle(FontWeight.Bold, 44, 52, -0.8),
        displayMedium = lotteryTextStyle(FontWeight.Bold, 38, 46, -0.6),
        displaySmall = lotteryTextStyle(FontWeight.Bold, 32, 40, -0.5),
        headlineLarge = lotteryTextStyle(FontWeight.Bold, 28, 36, -0.4),
        headlineMedium = lotteryTextStyle(FontWeight.Bold, 24, 32, -0.3),
        headlineSmall = lotteryTextStyle(FontWeight.SemiBold, 20, 28, -0.2),
        titleLarge = lotteryTextStyle(FontWeight.SemiBold, 18, 26, -0.1),
        titleMedium = lotteryTextStyle(FontWeight.SemiBold, 16, 24),
        titleSmall = lotteryTextStyle(FontWeight.SemiBold, 14, 20),
        bodyLarge = lotteryTextStyle(FontWeight.Normal, 16, 25),
        bodyMedium = lotteryTextStyle(FontWeight.Normal, 14, 21),
        bodySmall = lotteryTextStyle(FontWeight.Normal, 12, 18),
        labelLarge = lotteryTextStyle(FontWeight.SemiBold, 14, 20),
        labelMedium = lotteryTextStyle(FontWeight.Medium, 12, 16),
        labelSmall = lotteryTextStyle(FontWeight.Medium, 11, 14),
    )

/** 圆角层级：控件 12dp、内层卡片 16dp、分区卡片 20dp、主卡与弹窗 28dp。 */
private val LotteryShapes =
    Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(20.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )

/** 读取当前外观的品牌语义色。 */
object LotteryThemeTokens {
    /** 当前外观的品牌语义色。 */
    val colors: LotteryExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalLotteryColors.current
}

/**
 * 应用共享主题，默认跟随系统深浅色外观。
 *
 * @param darkTheme 是否使用深色外观。
 * @param content 主题内的界面。
 */
@Composable
fun LotteryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalLotteryColors provides if (darkTheme) DarkExtendedColors else LightExtendedColors,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkLotteryColorScheme else LightLotteryColorScheme,
            typography = LotteryTypography,
            shapes = LotteryShapes,
            content = content,
        )
    }
}

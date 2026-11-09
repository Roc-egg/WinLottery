package roc.win.lottery.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import roc.win.lottery.app.theme.LotteryThemeTokens

/** 页面内分区之间的标准垂直间距。 */
internal val SectionSpacing = 28.dp

/** 分区标题与其内容之间的标准间距。 */
internal val SectionTitleSpacing = 12.dp

/** 主操作按钮的标准高度。 */
private val ActionButtonHeight = 52.dp

/**
 * 统一的内容卡片：纯白表面、20dp 圆角和极细描边。
 *
 * @param modifier 外部布局修饰。
 * @param onClick 可选的整卡点击操作。
 * @param color 卡片底色。
 * @param shape 卡片形状。
 * @param bordered 是否绘制极细描边。
 * @param contentPadding 卡片内边距。
 * @param verticalArrangement 卡片内纵向排列方式。
 * @param content 卡片内容。
 */
@Composable
internal fun LotteryCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = MaterialTheme.colorScheme.surface,
    shape: Shape = MaterialTheme.shapes.large,
    bordered: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(12.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val border = if (bordered) BorderStroke(1.dp, LotteryThemeTokens.colors.hairline) else null
    val body: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth().padding(contentPadding),
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = color,
            border = border,
            content = body,
        )
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = color,
            border = border,
            content = body,
        )
    }
}

/**
 * 分区标题，可附带说明和右侧操作。
 *
 * @param title 分区标题。
 * @param modifier 外部布局修饰。
 * @param subtitle 可选的说明文字。
 * @param trailing 可选的右侧内容。
 */
@Composable
internal fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.let {
            Spacer(Modifier.width(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, content = it)
        }
    }
}

/**
 * 卡片内的小节标签，用于字段分组。
 *
 * @param text 标签文字。
 * @param modifier 外部布局修饰。
 */
@Composable
internal fun FieldLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 胶囊形主操作按钮。
 *
 * @param text 按钮文字。
 * @param onClick 点击操作。
 * @param modifier 外部布局修饰。
 * @param icon 可选的前置图标。
 * @param enabled 是否可用。
 * @param loading 是否显示进行中状态。
 * @param containerColor 按钮底色。
 * @param contentColor 按钮文字与图标颜色。
 */
@Composable
internal fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = ActionButtonHeight),
        enabled = enabled,
        shape = CircleShape,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = containerColor,
                contentColor = contentColor,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    ) {
        ButtonContent(text, icon, loading)
    }
}

/**
 * 胶囊形次要操作按钮，使用浅灰色填充。
 *
 * @param text 按钮文字。
 * @param onClick 点击操作。
 * @param modifier 外部布局修饰。
 * @param icon 可选的前置图标。
 * @param enabled 是否可用。
 * @param loading 是否显示进行中状态。
 * @param contentColor 按钮文字与图标颜色。
 */
@Composable
internal fun SecondaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = ActionButtonHeight),
        enabled = enabled,
        shape = CircleShape,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = contentColor,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            ),
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    ) {
        ButtonContent(text, icon, loading)
    }
}

/**
 * 胶囊形描边按钮，用于卡片内的轻量操作。
 *
 * @param text 按钮文字。
 * @param onClick 点击操作。
 * @param modifier 外部布局修饰。
 * @param icon 可选的前置图标。
 * @param enabled 是否可用。
 * @param loading 是否显示进行中状态。
 * @param contentColor 按钮文字与图标颜色。
 */
@Composable
internal fun OutlineActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 44.dp),
        enabled = enabled,
        shape = CircleShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = if (enabled) 1f else 0.5f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
    ) {
        ButtonContent(text, icon, loading)
    }
}

/**
 * 内联文字按钮，使用品牌色。
 *
 * @param text 按钮文字。
 * @param onClick 点击操作。
 * @param modifier 外部布局修饰。
 * @param enabled 是否可用。
 * @param color 文字颜色。
 */
@Composable
internal fun InlineTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.textButtonColors(contentColor = color),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** 按钮内容：可选图标、进行中指示和文字。 */
@Composable
private fun ButtonContent(
    text: String,
    icon: ImageVector?,
    loading: Boolean,
) {
    when {
        loading -> {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = LocalContentColor.current,
            )
            Spacer(Modifier.width(10.dp))
        }

        icon != null -> {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
    }
    Text(text, style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp))
}

/**
 * 圆形浅灰底图标按钮，用于顶部栏和卡片角落。
 *
 * @param icon 图标。
 * @param contentDescription 无障碍说明。
 * @param onClick 点击操作。
 * @param modifier 外部布局修饰。
 * @param enabled 是否可用。
 * @param tint 图标颜色。
 * @param containerColor 圆形底色。
 */
@Composable
internal fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(40.dp),
        enabled = enabled,
        colors =
            IconButtonDefaults.iconButtonColors(
                containerColor = containerColor,
                contentColor = tint,
                disabledContainerColor = containerColor.copy(alpha = 0.5f),
                disabledContentColor = tint.copy(alpha = 0.38f),
            ),
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(20.dp))
    }
}

/** 提示条语气。 */
internal enum class NoticeTone {
    /** 中性说明。 */
    INFO,

    /** 品牌强调。 */
    BRAND,

    /** 正向完成。 */
    SUCCESS,

    /** 需要留意。 */
    WARNING,

    /** 错误或阻断。 */
    ERROR,
}

/**
 * 带图标的提示条。
 *
 * @param message 提示正文。
 * @param modifier 外部布局修饰。
 * @param tone 提示语气。
 * @param title 可选的提示标题。
 * @param icon 前置图标；为 `null` 时使用语气默认图标。
 * @param content 正文下方的可选附加内容。
 */
@Composable
internal fun Notice(
    message: String,
    modifier: Modifier = Modifier,
    tone: NoticeTone = NoticeTone.INFO,
    title: String? = null,
    icon: ImageVector? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val colors = noticeColors(tone)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colors.container,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                icon ?: tone.defaultIcon(),
                contentDescription = null,
                modifier = Modifier.padding(top = 1.dp).size(20.dp),
                tint = colors.accent,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                title?.let {
                    Text(it, style = MaterialTheme.typography.titleSmall, color = colors.title)
                }
                Text(message, style = MaterialTheme.typography.bodyMedium, color = colors.body)
                content?.invoke(this)
            }
        }
    }
}

/**
 * 提示条的配色组合。
 *
 * @property container 容器底色。
 * @property accent 图标强调色。
 * @property title 标题颜色。
 * @property body 正文颜色。
 */
private data class NoticeColors(
    val container: Color,
    val accent: Color,
    val title: Color,
    val body: Color,
)

/** 返回指定语气的提示条配色。 */
@Composable
private fun noticeColors(tone: NoticeTone): NoticeColors {
    val scheme = MaterialTheme.colorScheme
    val extended = LotteryThemeTokens.colors
    return when (tone) {
        NoticeTone.INFO -> {
            NoticeColors(scheme.surfaceContainer, scheme.secondary, scheme.onSurface, scheme.onSurfaceVariant)
        }

        NoticeTone.BRAND -> {
            NoticeColors(scheme.primaryContainer, scheme.primary, scheme.onPrimaryContainer, scheme.onPrimaryContainer)
        }

        NoticeTone.SUCCESS -> {
            NoticeColors(
                extended.successContainer,
                extended.success,
                extended.onSuccessContainer,
                extended.onSuccessContainer,
            )
        }

        NoticeTone.WARNING -> {
            NoticeColors(
                extended.warningContainer,
                extended.warning,
                extended.onWarningContainer,
                extended.onWarningContainer,
            )
        }

        NoticeTone.ERROR -> {
            NoticeColors(scheme.errorContainer, scheme.error, scheme.onErrorContainer, scheme.onErrorContainer)
        }
    }
}

/** 返回语气对应的默认图标。 */
private fun NoticeTone.defaultIcon(): ImageVector =
    when (this) {
        NoticeTone.INFO -> LotteryIcons.Info
        NoticeTone.BRAND -> LotteryIcons.Sparkles
        NoticeTone.SUCCESS -> LotteryIcons.Success
        NoticeTone.WARNING -> LotteryIcons.Warning
        NoticeTone.ERROR -> LotteryIcons.Warning
    }

/**
 * 卡片内的键值列表，标签在左、值在右。
 *
 * @param rows 键值行。
 * @param modifier 外部布局修饰。
 */
@Composable
internal fun KeyValueCard(
    rows: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    LotteryCard(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        rows.forEachIndexed { index, (label, value) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    label,
                    modifier = Modifier.weight(0.4f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    value,
                    modifier = Modifier.weight(0.6f),
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.End,
                )
            }
            if (index != rows.lastIndex) {
                HorizontalDivider(color = LotteryThemeTokens.colors.hairline)
            }
        }
    }
}

/**
 * 分段选择器：浅灰轨道上滑出白色选中块。
 *
 * @param options 可选项。
 * @param selected 当前选中项。
 * @param label 选项文字。
 * @param onSelect 选择操作。
 * @param modifier 外部布局修饰。
 * @param enabled 是否可用。
 */
@Composable
internal fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(3.dp)
                .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = 38.dp)
                        .then(
                            if (isSelected) {
                                Modifier
                                    .shadow(2.dp, CircleShape, clip = false)
                                    .background(MaterialTheme.colorScheme.surfaceBright, CircleShape)
                            } else {
                                Modifier
                            },
                        ).clip(CircleShape)
                        .selectable(
                            selected = isSelected,
                            enabled = enabled,
                            role = Role.Tab,
                            onClick = { onSelect(option) },
                        ).padding(horizontal = 10.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = MaterialTheme.typography.labelLarge,
                    color =
                        when {
                            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            isSelected -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * 胶囊形单选标签，选中时使用品牌浅色。
 *
 * @param selected 是否选中。
 * @param onClick 点击操作。
 * @param label 标签文字。
 * @param modifier 外部布局修饰。
 * @param enabled 是否可用。
 */
@Composable
internal fun ChoicePill(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        modifier = modifier.heightIn(min = 36.dp),
        enabled = enabled,
        shape = CircleShape,
        colors =
            FilterChipDefaults.filterChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                selectedContainerColor = MaterialTheme.colorScheme.onSurface,
                selectedLabelColor = MaterialTheme.colorScheme.surface,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        border = null,
    )
}

/**
 * 胶囊形数值步进器。
 *
 * @param valueText 当前值文字。
 * @param onDecrement 减少操作。
 * @param onIncrement 增加操作。
 * @param decrementEnabled 是否允许减少。
 * @param incrementEnabled 是否允许增加。
 * @param decrementDescription 减少按钮的无障碍说明。
 * @param incrementDescription 增加按钮的无障碍说明。
 * @param modifier 外部布局修饰。
 * @param valueWidth 中间数值区宽度。
 */
@Composable
internal fun Stepper(
    valueText: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    decrementEnabled: Boolean,
    incrementEnabled: Boolean,
    decrementDescription: String,
    incrementDescription: String,
    modifier: Modifier = Modifier,
    valueWidth: Dp = 56.dp,
) {
    Row(
        modifier =
            modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepperButton(LotteryIcons.Minus, decrementDescription, decrementEnabled, onDecrement)
        Text(
            valueText,
            modifier = Modifier.width(valueWidth),
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        StepperButton(LotteryIcons.Plus, incrementDescription, incrementEnabled, onIncrement)
    }
}

/** 步进器两端的圆形按钮。 */
@Composable
private fun StepperButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(34.dp),
        colors =
            IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceBright,
                contentColor = MaterialTheme.colorScheme.onSurface,
                disabledContainerColor = Color.Transparent,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            ),
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(16.dp))
    }
}

/** 号码球样式。 */
internal enum class BallStyle {
    /** 红色实心，用于红球和前区。 */
    RED,

    /** 蓝色实心，用于蓝球和后区。 */
    BLUE,

    /** 红色浅底，用于未命中但需要区分号码区的场景。 */
    RED_SOFT,

    /** 蓝色浅底。 */
    BLUE_SOFT,

    /** 中性浅灰底。 */
    NEUTRAL,

    /** 白底描边，可点选的空号码。 */
    OUTLINE,

    /** 虚位占位。 */
    PLACEHOLDER,

    /** 不可选。 */
    DISABLED,
}

/**
 * 统一的号码球。
 *
 * @param text 球面文字。
 * @param style 号码球样式。
 * @param modifier 外部布局修饰，可附加点选语义。
 * @param size 球径。
 * @param elevated 实心球是否带彩色投影。
 * @param fontSize 可选的字号，默认按球径推导。
 */
@Composable
internal fun LotteryBall(
    text: String,
    style: BallStyle,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    elevated: Boolean = true,
    fontSize: TextUnit = TextUnit.Unspecified,
) {
    val extended = LotteryThemeTokens.colors
    val scheme = MaterialTheme.colorScheme
    val (fill, textColor) =
        when (style) {
            BallStyle.RED -> {
                Brush.linearGradient(
                    listOf(extended.redBall, extended.redBallDeep),
                    start = Offset.Zero,
                    end = Offset.Infinite,
                ) to Color.White
            }

            BallStyle.BLUE -> {
                Brush.linearGradient(
                    listOf(extended.blueBall, extended.blueBallDeep),
                    start = Offset.Zero,
                    end = Offset.Infinite,
                ) to Color.White
            }

            BallStyle.RED_SOFT -> {
                Brush.linearGradient(listOf(scheme.primaryContainer, scheme.primaryContainer)) to
                    scheme.onPrimaryContainer
            }

            BallStyle.BLUE_SOFT -> {
                Brush.linearGradient(listOf(scheme.secondaryContainer, scheme.secondaryContainer)) to
                    scheme.onSecondaryContainer
            }

            BallStyle.NEUTRAL -> {
                Brush.linearGradient(listOf(scheme.surfaceContainerHigh, scheme.surfaceContainerHigh)) to
                    scheme.onSurface
            }

            BallStyle.OUTLINE -> {
                Brush.linearGradient(listOf(scheme.surface, scheme.surface)) to scheme.onSurface
            }

            BallStyle.PLACEHOLDER -> {
                Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)) to scheme.onSurfaceVariant
            }

            BallStyle.DISABLED -> {
                Brush.linearGradient(listOf(scheme.surfaceContainer, scheme.surfaceContainer)) to
                    scheme.onSurfaceVariant.copy(alpha = 0.45f)
            }
        }
    val isSolid = style == BallStyle.RED || style == BallStyle.BLUE
    val shadowColor = if (style == BallStyle.RED) extended.redBall else extended.blueBall
    val borderColor =
        when (style) {
            BallStyle.OUTLINE -> scheme.outlineVariant
            BallStyle.PLACEHOLDER -> scheme.outline
            else -> null
        }
    Box(
        modifier =
            modifier
                .size(size)
                .then(
                    if (isSolid && elevated && !extended.isDark) {
                        Modifier.shadow(
                            elevation = 3.dp,
                            shape = CircleShape,
                            ambientColor = shadowColor.copy(alpha = 0.35f),
                            spotColor = shadowColor.copy(alpha = 0.45f),
                        )
                    } else {
                        Modifier
                    },
                ).clip(CircleShape)
                .background(fill)
                .then(if (borderColor != null) Modifier.border(1.dp, borderColor, CircleShape) else Modifier)
                .then(
                    if (isSolid) {
                        Modifier.drawBehind {
                            // 左上角一抹高光，让实心球有轻微立体感。
                            drawCircle(
                                brush =
                                    Brush.radialGradient(
                                        listOf(Color.White.copy(alpha = 0.28f), Color.Transparent),
                                        center = Offset(this.size.width * 0.32f, this.size.height * 0.26f),
                                        radius = this.size.minDimension * 0.55f,
                                    ),
                            )
                        }
                    } else {
                        Modifier
                    },
                ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style =
                MaterialTheme.typography.labelLarge.copy(
                    fontSize =
                        if (fontSize ==
                            TextUnit.Unspecified
                        ) {
                            (size.value * 0.4f).coerceIn(10f, 17f).sp
                        } else {
                            fontSize
                        },
                    fontWeight = if (isSolid) FontWeight.Bold else FontWeight.SemiBold,
                    lineHeight = TextUnit.Unspecified,
                ),
            color = textColor,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** 状态标签语气。 */
internal enum class TagTone {
    /** 中性灰。 */
    NEUTRAL,

    /** 品牌红。 */
    BRAND,

    /** 信息蓝。 */
    INFO,

    /** 正向绿。 */
    SUCCESS,

    /** 提醒橙。 */
    WARNING,
}

/**
 * 小号胶囊状态标签。
 *
 * @param text 标签文字。
 * @param tone 标签语气。
 * @param modifier 外部布局修饰。
 */
@Composable
internal fun StatusTag(
    text: String,
    tone: TagTone = TagTone.NEUTRAL,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val extended = LotteryThemeTokens.colors
    val (container, content) =
        when (tone) {
            TagTone.NEUTRAL -> scheme.surfaceContainerHigh to scheme.onSurfaceVariant
            TagTone.BRAND -> scheme.primaryContainer to scheme.onPrimaryContainer
            TagTone.INFO -> scheme.secondaryContainer to scheme.onSecondaryContainer
            TagTone.SUCCESS -> extended.successContainer to extended.onSuccessContainer
            TagTone.WARNING -> extended.warningContainer to extended.onWarningContainer
        }
    Text(
        text,
        modifier =
            modifier
                .clip(CircleShape)
                .background(container)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = content,
        maxLines = 1,
    )
}

/**
 * 圆角方形图标底座。
 *
 * @param icon 图标。
 * @param modifier 外部布局修饰。
 * @param tint 图标颜色。
 * @param container 底座颜色。
 * @param size 底座边长。
 */
@Composable
internal fun IconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    size: Dp = 44.dp,
) {
    Box(
        modifier =
            modifier
                .size(size)
                .clip(RoundedCornerShape(size * 0.3f))
                .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(size * 0.48f), tint = tint)
    }
}

/**
 * 圆角输入框的统一配色：浅灰填充、聚焦时品牌色描边。
 */
@Composable
internal fun lotteryTextFieldColors(): TextFieldColors =
    OutlinedTextFieldDefaults.colors(
        focusedContainerColor = MaterialTheme.colorScheme.surface,
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        errorContainerColor = MaterialTheme.colorScheme.surface,
        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        focusedBorderColor = MaterialTheme.colorScheme.primary,
    )

/**
 * 页面级说明：一级页面大标题下方的引导文字。
 *
 * @param text 说明文字。
 * @param modifier 外部布局修饰。
 */
@Composable
internal fun PageLead(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 竖向分组卡片中的一行：左侧标题说明、右侧控件。
 *
 * @param title 行标题。
 * @param modifier 外部布局修饰。
 * @param detail 可选的说明。
 * @param control 右侧控件。
 */
@Composable
internal fun SettingLine(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    control: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        control()
    }
}

/** 卡片内分组之间的极细分隔线。 */
@Composable
internal fun HairlineDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, color = LotteryThemeTokens.colors.hairline)
}

/** 页面底部的小字免责说明。 */
@Composable
internal fun FootnoteText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        modifier = modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 一注号码的球形展示：主号码与次号码之间留出分组间隙，球径随可用宽度缩放，排不下时自动换行。
 *
 * @param primaryNumbers 主号码。
 * @param secondaryNumbers 次号码，没有时只展示主号码。
 * @param modifier 外部布局修饰。
 * @param primaryStyle 主号码样式。
 * @param secondaryStyle 次号码样式。
 * @param maxBallSize 最大球径。
 * @param minBallSize 最小球径。
 * @param spacing 球间距。
 * @param styleFor 可选的逐个号码样式，用于标出命中号码。
 */
@Composable
internal fun NumberBallLine(
    primaryNumbers: List<Int>,
    secondaryNumbers: List<Int>,
    modifier: Modifier = Modifier,
    primaryStyle: BallStyle = BallStyle.RED,
    secondaryStyle: BallStyle = BallStyle.BLUE,
    maxBallSize: Dp = 38.dp,
    minBallSize: Dp = 26.dp,
    spacing: Dp = 6.dp,
    styleFor: ((isPrimary: Boolean, number: Int) -> BallStyle)? = null,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val groupGap = if (secondaryNumbers.isEmpty()) 0.dp else spacing * 2
        val slotCount = (primaryNumbers.size + secondaryNumbers.size).coerceAtLeast(1)
        val ballSize =
            ((maxWidth - groupGap - spacing * (slotCount - 1)) / slotCount).coerceIn(minBallSize, maxBallSize)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            primaryNumbers.forEach { number ->
                LotteryBall(
                    text = number.toString().padStart(2, '0'),
                    style = styleFor?.invoke(true, number) ?: primaryStyle,
                    size = ballSize,
                )
            }
            if (secondaryNumbers.isNotEmpty()) {
                Spacer(Modifier.width(groupGap - spacing).height(ballSize))
                secondaryNumbers.forEach { number ->
                    LotteryBall(
                        text = number.toString().padStart(2, '0'),
                        style = styleFor?.invoke(false, number) ?: secondaryStyle,
                        size = ballSize,
                    )
                }
            }
        }
    }
}

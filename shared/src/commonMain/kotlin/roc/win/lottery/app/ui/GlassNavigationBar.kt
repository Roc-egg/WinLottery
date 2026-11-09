package roc.win.lottery.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import roc.win.lottery.app.MainDestination
import roc.win.lottery.app.theme.LotteryThemeTokens

/**
 * 浮动导航栏覆盖在正文底部的高度，含栏高、底部间距和系统导航区。
 *
 * 外壳负责的滚动正文会自动留白；页面自带惰性列表或固定布局时，需要把它加到列表底部留白或正文底部边距。
 */
internal val LocalMainBarInset = staticCompositionLocalOf { 0.dp }

/** 浮动导航栏高度。 */
internal val GlassBarHeight = 64.dp

/** 浮动导航栏与屏幕底部安全区之间的间距。 */
internal val GlassBarBottomMargin = 10.dp

/** 浮动导航栏与屏幕左右边缘的间距。 */
private val GlassBarHorizontalMargin = 16.dp

/** 浮动导航栏在宽屏上的最大宽度。 */
private val GlassBarMaxWidth = 440.dp

/** 背景模糊半径。 */
private val GlassBlurRadius = 24.dp

/** 选中胶囊与导航栏边缘的间距。 */
private val GlassIndicatorInset = 4.dp

/**
 * 玻璃导航栏读取的正文图层。
 *
 * 正文每次重绘都会录制到同一个图层，导航栏把该图层平移到自身位置后模糊绘制，得到“透过玻璃看到下方内容”的效果。
 */
@Stable
internal class GlassBackdrop {
    /** 正文录制图层；正文尚未绘制时为 `null`。 */
    internal var layer: GraphicsLayer? = null

    /** 正文在根布局中的位置。 */
    internal var sourcePositionInRoot by mutableStateOf(Offset.Zero)

    /** 正文重绘次数，导航栏读取它以便在正文变化后同步重绘。 */
    internal var contentVersion by mutableIntStateOf(0)
}

/** 创建跨重组保持的正文图层持有者。 */
@Composable
internal fun rememberGlassBackdrop(): GlassBackdrop = remember { GlassBackdrop() }

/**
 * 把当前元素的绘制内容录制为玻璃导航栏的背景，同时照常显示。
 *
 * @param backdrop 与导航栏共享的正文图层持有者。
 */
@Composable
internal fun Modifier.glassBackdropSource(backdrop: GlassBackdrop): Modifier {
    val layer = rememberGraphicsLayer()
    backdrop.layer = layer
    return this
        .onGloballyPositioned { coordinates -> backdrop.sourcePositionInRoot = coordinates.positionInRoot() }
        .drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
            // 不登记读取，避免正文因写入自身观察的状态而循环重绘；只通知导航栏下一帧重绘。
            Snapshot.withoutReadObservation { backdrop.contentVersion += 1 }
        }
}

/** 上一次选中的导航下标；各一级页面各自创建外壳，借此让选中胶囊从旧位置滑向新位置。 */
private var lastSelectedGlassIndex = -1

/**
 * 液态玻璃风格的浮动底部导航：胶囊外形、背景模糊、高光描边和会拉伸回弹的选中胶囊。
 *
 * @param backdrop 正文图层持有者。
 * @param selectedDestination 当前选中的一级目的地。
 * @param destinations 当前平台可进入的一级目的地。
 * @param onDestinationSelected 一级目的地切换操作。
 * @param modifier 外部布局修饰，由外壳负责对齐和避让系统导航区。
 */
@Composable
internal fun FloatingGlassNavigationBar(
    backdrop: GlassBackdrop,
    selectedDestination: MainDestination,
    destinations: List<MainDestination>,
    onDestinationSelected: (MainDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = LotteryThemeTokens.colors.isDark
    val usesBlur = supportsBackdropBlur
    var barPositionInRoot by remember { mutableStateOf(Offset.Zero) }
    val glassTint =
        when {
            isDark && usesBlur -> Color(0xFF1D1F24).copy(alpha = 0.62f)
            isDark -> Color(0xFF1D1F24).copy(alpha = 0.96f)
            usesBlur -> Color.White.copy(alpha = 0.6f)
            else -> Color.White.copy(alpha = 0.95f)
        }
    val rim =
        Brush.verticalGradient(
            if (isDark) {
                listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.05f))
            } else {
                listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.35f))
            },
        )
    val sheen =
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = if (isDark) 0.07f else 0.45f),
            0.55f to Color.White.copy(alpha = 0f),
        )
    Box(
        modifier =
            modifier
                .padding(horizontal = GlassBarHorizontalMargin)
                .widthIn(max = GlassBarMaxWidth)
                .fillMaxWidth()
                .height(GlassBarHeight)
                .shadow(
                    elevation = 20.dp,
                    shape = CircleShape,
                    ambientColor = Color.Black.copy(alpha = if (isDark) 0.45f else 0.10f),
                    spotColor = Color.Black.copy(alpha = if (isDark) 0.55f else 0.16f),
                ).onGloballyPositioned { coordinates -> barPositionInRoot = coordinates.positionInRoot() },
    ) {
        if (usesBlur) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val radius = GlassBlurRadius.toPx()
                        renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
                        shape = CircleShape
                        clip = true
                    }.drawBehind {
                        // 读取版本号，正文重绘后导航栏随之重绘。
                        backdrop.contentVersion
                        val layer = backdrop.layer ?: return@drawBehind
                        val offset = backdrop.sourcePositionInRoot - barPositionInRoot
                        translate(offset.x, offset.y) { drawLayer(layer) }
                    },
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(glassTint)
                .background(sheen)
                .border(1.dp, rim, CircleShape),
        )
        GlassNavigationItems(
            selectedDestination = selectedDestination,
            destinations = destinations,
            onDestinationSelected = onDestinationSelected,
            isDark = isDark,
        )
    }
}

/** 绘制选中胶囊和各导航项；胶囊前沿先到、后沿跟随，形成液体般的拉伸回弹。 */
@Composable
private fun GlassNavigationItems(
    selectedDestination: MainDestination,
    destinations: List<MainDestination>,
    onDestinationSelected: (MainDestination) -> Unit,
    isDark: Boolean,
) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(GlassIndicatorInset)) {
        val count = destinations.size.coerceAtLeast(1)
        val slotWidth = maxWidth / count
        val selectedIndex = destinations.indexOf(selectedDestination).coerceAtLeast(0)
        val startIndex = lastSelectedGlassIndex.takeIf { it in destinations.indices } ?: selectedIndex
        val left = remember { Animatable(slotWidth * startIndex, Dp.VectorConverter) }
        val right = remember { Animatable(slotWidth * (startIndex + 1), Dp.VectorConverter) }
        LaunchedEffect(selectedIndex, slotWidth) {
            lastSelectedGlassIndex = selectedIndex
            val targetLeft = slotWidth * selectedIndex
            val targetRight = slotWidth * (selectedIndex + 1)
            val movingRight = targetLeft > left.value
            val leading = spring<Dp>(dampingRatio = 0.68f, stiffness = 700f)
            val trailing = spring<Dp>(dampingRatio = 0.8f, stiffness = 260f)
            launch { left.animateTo(targetLeft, if (movingRight) trailing else leading) }
            launch { right.animateTo(targetRight, if (movingRight) leading else trailing) }
        }
        Box(
            Modifier
                .offset(x = left.value)
                .width((right.value - left.value).coerceAtLeast(0.dp))
                .fillMaxHeight()
                .clip(CircleShape)
                .background(if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.06f))
                .border(
                    1.dp,
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = if (isDark) 0.16f else 0.7f),
                            Color.White.copy(alpha = 0f),
                        ),
                    ),
                    CircleShape,
                ),
        )
        Row(modifier = Modifier.fillMaxSize().selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
            destinations.forEach { destination ->
                GlassNavigationItem(
                    destination = destination,
                    selected = destination == selectedDestination,
                    onClick = { onDestinationSelected(destination) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 单个导航项：按下时图标轻微收缩，选中时图标与文字着品牌色。 */
@Composable
private fun GlassNavigationItem(
    destination: MainDestination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.86f else 1f, spring(dampingRatio = 0.5f, stiffness = 600f))
    val color by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
    )
    Column(
        modifier =
            modifier
                .fillMaxHeight()
                .clip(CircleShape)
                .selectable(
                    selected = selected,
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Tab,
                    onClick = onClick,
                ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            destination.icon(),
            contentDescription = null,
            modifier =
                Modifier.size(22.dp).graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
            tint = color,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            destination.label(),
            style =
                MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.5.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                ),
            color = color,
            maxLines = 1,
        )
    }
}

package roc.win.lottery.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import roc.win.lottery.app.MainDestination
import roc.win.lottery.app.theme.LotteryThemeTokens

/** 宽度达到该阈值时，一级导航从底栏切换为侧栏。 */
private val WIDE_NAVIGATION_THRESHOLD = 840.dp

/** 高度低于该阈值时使用紧凑顶部导航，避免五项侧栏在手机横屏中被裁切。 */
private val SHORT_NAVIGATION_HEIGHT_THRESHOLD = 600.dp

/** 一级页面在宽屏中允许使用的最大正文宽度。 */
private val MAX_CONTENT_WIDTH = 960.dp

/**
 * 提供四端一致的顶部栏、响应式一级导航、正文宽度和安全区域。
 *
 * @param title 顶部栏标题。
 * @param navigationIcon 可选的返回图标。
 * @param onNavigate 返回操作。
 * @param actionIcon 可选的右侧操作图标。
 * @param actionDescription 右侧图标的无障碍说明。
 * @param onAction 右侧操作。
 * @param actions 可选的自定义操作区；普通顶部栏位于右侧，紧凑顶部栏位于中部。
 * @param mainDestination 一级页面当前选中的目的地；次级流程保持为 `null`。
 * @param availableMainDestinations 当前平台可进入的一级目的地。
 * @param onMainDestinationSelected 一级目的地切换操作。
 * @param scrollableContent 是否由外壳提供纵向滚动；惰性列表页面应设为 `false`。
 * @param compactChrome 是否使用适合手机横屏的紧凑顶部导航与正文边距。
 * @param immersiveContent 是否仅保留安全区域，将整个视口交给带有自身导航的工作台。
 * @param content 页面正文。
 */
@Composable
fun AppShell(
    title: String,
    navigationIcon: ImageVector? = null,
    onNavigate: () -> Unit = {},
    actionIcon: ImageVector? = null,
    actionDescription: String = "",
    onAction: () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    mainDestination: MainDestination? = null,
    availableMainDestinations: List<MainDestination> = MainDestination.entries,
    onMainDestinationSelected: (MainDestination) -> Unit = {},
    scrollableContent: Boolean = true,
    compactChrome: Boolean = false,
    immersiveContent: Boolean = false,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
        ) {
            // 导航形态只按窗口尺寸决定，不扣除输入法高度：竖屏手机弹出键盘后可用高度会低于紧凑阈值，
            // 外壳一旦切换布局就会重建正文，输入框随即失焦、键盘收起，高度恢复后又切回原布局。
            val usesSideNavigation =
                mainDestination != null && maxWidth >= WIDE_NAVIGATION_THRESHOLD
            val usesCompactNavigation =
                mainDestination != null &&
                    (compactChrome || maxHeight < SHORT_NAVIGATION_HEIGHT_THRESHOLD)
            // 键盘只让正文内容区避让，顶部栏和底部导航保持原位，底部导航直接被键盘遮住。
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    immersiveContent -> {
                        // 安全绘制区域已包含输入法区域。
                        Box(Modifier.fillMaxSize().safeDrawingPadding()) { content() }
                    }

                    usesCompactNavigation -> {
                        CompactMainAppShellBody(
                            title = title,
                            selectedDestination = checkNotNull(mainDestination),
                            destinations = availableMainDestinations,
                            onDestinationSelected = onMainDestinationSelected,
                            actions = actions,
                            content = content,
                        )
                    }

                    usesSideNavigation -> {
                        Row(modifier = Modifier.fillMaxSize()) {
                            MainNavigationRail(
                                selectedDestination = checkNotNull(mainDestination),
                                destinations = availableMainDestinations,
                                onDestinationSelected = onMainDestinationSelected,
                            )
                            VerticalDivider(color = LotteryThemeTokens.colors.hairline)
                            AppShellBody(
                                modifier = Modifier.weight(1f),
                                title = title,
                                navigationIcon = navigationIcon,
                                onNavigate = onNavigate,
                                actionIcon = actionIcon,
                                actionDescription = actionDescription,
                                onAction = onAction,
                                actions = actions,
                                scrollableContent = scrollableContent,
                                horizontalPadding = 40.dp,
                                content = content,
                            )
                        }
                    }

                    else -> {
                        val density = LocalDensity.current
                        var navigationBarHeight by remember { mutableStateOf(0.dp) }
                        Column(modifier = Modifier.fillMaxSize()) {
                            AppShellBody(
                                modifier = Modifier.weight(1f),
                                title = title,
                                navigationIcon = navigationIcon,
                                onNavigate = onNavigate,
                                actionIcon = actionIcon,
                                actionDescription = actionDescription,
                                onAction = onAction,
                                actions = actions,
                                scrollableContent = scrollableContent,
                                horizontalPadding = 20.dp,
                                bottomNavigationHeight = if (mainDestination != null) navigationBarHeight else 0.dp,
                                content = content,
                            )
                            mainDestination?.let { selectedDestination ->
                                MainNavigationBar(
                                    modifier =
                                        Modifier.onSizeChanged { size ->
                                            navigationBarHeight = with(density) { size.height.toDp() }
                                        },
                                    selectedDestination = selectedDestination,
                                    destinations = availableMainDestinations,
                                    onDestinationSelected = onMainDestinationSelected,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 绘制手机横屏专用的紧凑一级外壳，把导航收进顶部栏以释放图表空间。
 *
 * @param title 当前一级页面标题。
 * @param selectedDestination 当前选中的一级目的地。
 * @param destinations 当前平台可进入的一级目的地。
 * @param onDestinationSelected 一级目的地切换操作。
 * @param actions 紧凑顶部栏中央的自定义操作区。
 * @param content 页面正文。
 */
@Composable
private fun CompactMainAppShellBody(
    title: String,
    selectedDestination: MainDestination,
    destinations: List<MainDestination>,
    onDestinationSelected: (MainDestination) -> Unit,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                modifier = Modifier.padding(end = 8.dp),
                style = MaterialTheme.typography.titleLarge,
            )
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
            Row(
                modifier =
                    Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                destinations.forEach { destination ->
                    val isSelected = destination == selectedDestination
                    IconButton(
                        onClick = { onDestinationSelected(destination) },
                        modifier = Modifier.size(40.dp).semantics { selected = isSelected },
                        colors =
                            IconButtonDefaults.iconButtonColors(
                                containerColor =
                                    if (isSelected) MaterialTheme.colorScheme.surfaceBright else Color.Transparent,
                                contentColor =
                                    if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            ),
                    ) {
                        Icon(
                            imageVector = destination.icon(),
                            contentDescription = destination.label(),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
        HairlineDivider()
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.background)
                    .imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                modifier =
                    Modifier
                        .widthIn(max = MAX_CONTENT_WIDTH)
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
            ) {
                content()
            }
        }
    }
}

/**
 * 绘制除一级导航之外的顶部栏和正文。
 *
 * 一级页面使用左对齐大标题，次级流程使用圆形返回按钮和居中标题；正文滚动离开顶部后才显示极细分隔线。
 * 只有正文内容区避让输入法；[bottomNavigationHeight] 是正文下方底部导航的高度，键盘先遮住底部导航，
 * 再遮住正文，因此正文只需让出超过底部导航的那部分键盘高度。
 */
@Composable
private fun AppShellBody(
    modifier: Modifier,
    title: String,
    navigationIcon: ImageVector?,
    onNavigate: () -> Unit,
    actionIcon: ImageVector?,
    actionDescription: String,
    onAction: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    scrollableContent: Boolean,
    horizontalPadding: Dp,
    bottomNavigationHeight: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    val scrollState = rememberScrollState()
    val showsDivider = scrollableContent && scrollState.value > 0
    val trailing: @Composable RowScope.() -> Unit = {
        actions()
        actionIcon?.let { icon ->
            CircleIconButton(icon = icon, contentDescription = actionDescription, onClick = onAction)
        }
    }
    Column(modifier = modifier) {
        if (navigationIcon == null) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .padding(start = horizontalPadding, end = horizontalPadding - 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineMedium,
                    maxLines = 1,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    content = trailing,
                )
            }
        } else {
            Box(
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.padding(horizontal = 96.dp),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
                Box(modifier = Modifier.align(Alignment.CenterStart)) {
                    CircleIconButton(icon = navigationIcon, contentDescription = "返回", onClick = onNavigate)
                }
                Row(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    content = trailing,
                )
            }
        }
        HairlineDivider(Modifier.alpha(if (showsDivider) 1f else 0f))
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.background)
                    .consumeWindowInsets(PaddingValues(bottom = bottomNavigationHeight))
                    .imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (scrollableContent) {
                Column(
                    modifier =
                        Modifier
                            .widthIn(max = MAX_CONTENT_WIDTH)
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                            .padding(horizontal = horizontalPadding),
                    verticalArrangement = Arrangement.Top,
                ) {
                    Spacer(Modifier.height(if (navigationIcon == null) 4.dp else 12.dp))
                    content()
                    Spacer(Modifier.height(40.dp))
                }
            } else {
                Box(
                    modifier =
                        Modifier
                            .widthIn(max = MAX_CONTENT_WIDTH)
                            .fillMaxSize()
                            .padding(horizontal = horizontalPadding),
                ) {
                    content()
                }
            }
        }
    }
}

/** 绘制移动宽度的底部一级导航：白色底栏、顶部极细线和品牌浅色胶囊指示器。 */
@Composable
private fun MainNavigationBar(
    modifier: Modifier,
    selectedDestination: MainDestination,
    destinations: List<MainDestination>,
    onDestinationSelected: (MainDestination) -> Unit,
) {
    // 外壳已让出系统导航区，这里把白色底栏延伸进手势条区域，避免底部露出页面灰底。
    val bottomInset = WindowInsets.navigationBars.getBottom(LocalDensity.current).toFloat()
    val barColor = MaterialTheme.colorScheme.surface
    Column(
        modifier =
            modifier.drawBehind {
                if (bottomInset > 0f) {
                    drawRect(barColor, topLeft = Offset(0f, size.height), size = Size(size.width, bottomInset))
                }
            },
    ) {
        HairlineDivider()
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            windowInsets = WindowInsets(0, 0, 0, 0),
            modifier = Modifier.height(68.dp),
        ) {
            destinations.forEach { destination ->
                NavigationBarItem(
                    selected = destination == selectedDestination,
                    onClick = { onDestinationSelected(destination) },
                    icon = {
                        Icon(
                            destination.icon(),
                            contentDescription = destination.label(),
                            modifier = Modifier.size(22.dp),
                        )
                    },
                    label = { Text(destination.label(), style = MaterialTheme.typography.labelMedium) },
                    colors = navigationItemColors(),
                )
            }
        }
    }
}

/** 底部导航项的统一配色。 */
@Composable
private fun navigationItemColors() =
    NavigationBarItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.primary,
        selectedTextColor = MaterialTheme.colorScheme.primary,
        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )

/** 绘制宽屏的侧边一级导航，顶部展示品牌标识。 */
@Composable
private fun MainNavigationRail(
    selectedDestination: MainDestination,
    destinations: List<MainDestination>,
    onDestinationSelected: (MainDestination) -> Unit,
) {
    NavigationRail(
        modifier = Modifier.fillMaxHeight().width(88.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        windowInsets = WindowInsets(0, 0, 0, 0),
        header = {
            Column(
                modifier = Modifier.padding(top = 20.dp, bottom = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BrandMark(size = 40.dp)
                Text("给我中", style = MaterialTheme.typography.labelLarge)
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            destinations.forEach { destination ->
                NavigationRailItem(
                    selected = destination == selectedDestination,
                    onClick = { onDestinationSelected(destination) },
                    icon = {
                        Icon(
                            destination.icon(),
                            contentDescription = destination.label(),
                            modifier = Modifier.size(22.dp),
                        )
                    },
                    label = { Text(destination.label(), style = MaterialTheme.typography.labelMedium) },
                    alwaysShowLabel = true,
                    colors =
                        NavigationRailItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                )
            }
        }
    }
}

/**
 * 品牌标识：红色渐变圆角方块中的“中”字。
 *
 * @param size 标识边长。
 */
@Composable
internal fun BrandMark(size: Dp) {
    val colors = LotteryThemeTokens.colors
    Box(
        modifier =
            Modifier
                .size(size)
                .clip(RoundedCornerShape(size * 0.28f))
                .background(Brush.linearGradient(listOf(colors.heroStart, colors.heroEnd))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "中",
            style = MaterialTheme.typography.titleLarge.copy(fontSize = (size.value * 0.5f).sp),
            color = Color.White,
        )
    }
}

/** 返回一级目的地的简体中文标签。 */
internal fun MainDestination.label(): String =
    when (this) {
        MainDestination.VERIFICATION -> "核对"
        MainDestination.NUMBER_PICKER -> "选号"
        MainDestination.TRENDS -> "走势"
        MainDestination.AI_ANALYSIS -> "AI"
        MainDestination.RECORDS -> "记录"
    }

/** 返回一级目的地对应的 Lucide 图标。 */
private fun MainDestination.icon(): ImageVector =
    when (this) {
        MainDestination.VERIFICATION -> LotteryIcons.Verification
        MainDestination.NUMBER_PICKER -> LotteryIcons.NumberPicker
        MainDestination.TRENDS -> LotteryIcons.Trends
        MainDestination.AI_ANALYSIS -> LotteryIcons.AiAnalysis
        MainDestination.RECORDS -> LotteryIcons.History
    }

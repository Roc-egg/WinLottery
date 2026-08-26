package roc.win.lottery.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import roc.win.lottery.app.MainDestination

/** 宽度达到该阈值时，一级导航从底栏切换为侧栏。 */
private val WIDE_NAVIGATION_THRESHOLD = 840.dp

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
                    .navigationBarsPadding()
                    .imePadding(),
        ) {
            val usesSideNavigation =
                mainDestination != null && maxWidth >= WIDE_NAVIGATION_THRESHOLD
            if (compactChrome && mainDestination != null) {
                CompactMainAppShellBody(
                    title = title,
                    selectedDestination = mainDestination,
                    destinations = availableMainDestinations,
                    onDestinationSelected = onMainDestinationSelected,
                    actions = actions,
                    content = content,
                )
            } else if (usesSideNavigation) {
                Row(modifier = Modifier.fillMaxSize()) {
                    MainNavigationRail(
                        selectedDestination = checkNotNull(mainDestination),
                        destinations = availableMainDestinations,
                        onDestinationSelected = onMainDestinationSelected,
                    )
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
                        horizontalPadding = 32.dp,
                        content = content,
                    )
                }
            } else {
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
                        content = content,
                    )
                    mainDestination?.let { selectedDestination ->
                        MainNavigationBar(
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
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                modifier = Modifier.padding(end = 8.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
            destinations.forEach { destination ->
                val isSelected = destination == selectedDestination
                IconButton(
                    onClick = { onDestinationSelected(destination) },
                    modifier = Modifier.semantics { selected = isSelected },
                ) {
                    Icon(
                        imageVector = destination.icon(),
                        contentDescription = destination.label(),
                        tint =
                            if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.background),
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

/** 绘制除一级导航之外的顶部栏和正文。 */
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
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.widthIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                navigationIcon?.let { icon ->
                    IconButton(onClick = onNavigate) {
                        Icon(icon, contentDescription = "返回")
                    }
                }
            }
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
            )
            Box(modifier = Modifier.widthIn(min = 48.dp), contentAlignment = Alignment.CenterEnd) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    actions()
                    actionIcon?.let { icon ->
                        IconButton(onClick = onAction) {
                            Icon(icon, contentDescription = actionDescription)
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (scrollableContent) {
                Column(
                    modifier =
                        Modifier
                            .widthIn(max = MAX_CONTENT_WIDTH)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = horizontalPadding),
                    verticalArrangement = Arrangement.Top,
                ) {
                    Spacer(Modifier.height(24.dp))
                    content()
                    Spacer(Modifier.height(32.dp))
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

/** 绘制移动宽度的底部一级导航。 */
@Composable
private fun MainNavigationBar(
    selectedDestination: MainDestination,
    destinations: List<MainDestination>,
    onDestinationSelected: (MainDestination) -> Unit,
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        destinations.forEach { destination ->
            NavigationBarItem(
                selected = destination == selectedDestination,
                onClick = { onDestinationSelected(destination) },
                icon = {
                    Icon(destination.icon(), contentDescription = destination.label())
                },
                label = { Text(destination.label()) },
            )
        }
    }
}

/** 绘制宽屏的侧边一级导航。 */
@Composable
private fun MainNavigationRail(
    selectedDestination: MainDestination,
    destinations: List<MainDestination>,
    onDestinationSelected: (MainDestination) -> Unit,
) {
    NavigationRail(
        modifier = Modifier.fillMaxHeight(),
        containerColor = MaterialTheme.colorScheme.surface,
        header = {
            Column(
                modifier = Modifier.padding(top = 16.dp, bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    LotteryIcons.Verification,
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text("给我中", style = MaterialTheme.typography.labelMedium)
            }
        },
    ) {
        destinations.forEach { destination ->
            NavigationRailItem(
                selected = destination == selectedDestination,
                onClick = { onDestinationSelected(destination) },
                icon = {
                    Icon(destination.icon(), contentDescription = destination.label())
                },
                label = { Text(destination.label()) },
                alwaysShowLabel = true,
            )
        }
    }
}

/** 返回一级目的地的简体中文标签。 */
private fun MainDestination.label(): String =
    when (this) {
        MainDestination.VERIFICATION -> "核对"
        MainDestination.NUMBER_PICKER -> "选号"
        MainDestination.TRENDS -> "走势"
        MainDestination.RECORDS -> "记录"
    }

/** 返回一级目的地对应的 Lucide 图标。 */
private fun MainDestination.icon(): ImageVector =
    when (this) {
        MainDestination.VERIFICATION -> LotteryIcons.Verification
        MainDestination.NUMBER_PICKER -> LotteryIcons.NumberPicker
        MainDestination.TRENDS -> LotteryIcons.Trends
        MainDestination.RECORDS -> LotteryIcons.History
    }

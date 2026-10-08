package roc.win.lottery.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import roc.win.lottery.app.MainDestination
import roc.win.lottery.app.TrendChartAction
import roc.win.lottery.app.TrendChartContent
import roc.win.lottery.app.TrendChartState
import roc.win.lottery.app.TrendWorkspaceView
import roc.win.lottery.app.trendNumberZones
import roc.win.lottery.domain.LotteryTrendArea
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.TrendSampleSize
import roc.win.lottery.domain.trendAreaSpec
import kotlin.time.Instant

/** 数据工作台的三个互补视图。 */
internal enum class TrendDataView {
    /** 号码分布、遗漏和相邻期连线。 */
    NUMBERS,

    /** 和值、跨度、奇偶、大小、分区与重号。 */
    SHAPES,

    /** 当前样本内逐号统计。 */
    STATISTICS,
}

/**
 * 手机优先的全屏走势工作台；全屏只隐藏应用外壳，不强制旋转设备。
 *
 * @param chart 官方数据与共享配置。
 * @param availableMainDestinations 当前平台可进入的一级页面。
 * @param onMainDestinationSelected 切换一级页面。
 * @param onAction 切换彩种、区域、样本或数学研究。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TrendScreen(
    chart: TrendChartState,
    availableMainDestinations: List<MainDestination>,
    onMainDestinationSelected: (MainDestination) -> Unit,
    onAction: (TrendChartAction) -> Unit,
) {
    var fullscreen by rememberSaveable { mutableStateOf(true) }
    var dataView by rememberSaveable { mutableStateOf(TrendDataView.NUMBERS) }
    var compactPreference by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var showOmissions by rememberSaveable { mutableStateOf(true) }
    var showLines by rememberSaveable { mutableStateOf(true) }
    var selectedZone by rememberSaveable(chart.lotteryType, chart.area) { mutableStateOf(-1) }
    var selectedNumber by rememberSaveable(chart.lotteryType, chart.area) { mutableStateOf<Int?>(null) }
    var showSource by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val horizontalState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val snapshot = chart.snapshot
    val isBasic = chart.view == TrendWorkspaceView.BASIC_TREND

    // 滚动状态放在外壳之外，全屏切换和旋转不丢失当前浏览位置。
    LaunchedEffect(snapshot?.lotteryType, snapshot?.area, snapshot?.requestedSampleSize, snapshot?.lastIssue) {
        // 新样本等待下一次布局再定位，避免重组期间强制重新测量旧的惰性列表。
        snapshot?.let { listState.requestScrollToItem(it.rows.lastIndex) }
    }
    LaunchedEffect(chart.lotteryType, chart.area, selectedZone) { horizontalState.scrollTo(0) }
    if (fullscreen && isBasic) BackHandler { fullscreen = false }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val shortLandscape = landscape && maxHeight < 500.dp
        val compact = compactPreference ?: shortLandscape
        AppShell(
            title = "走势",
            mainDestination = MainDestination.TRENDS,
            availableMainDestinations = availableMainDestinations,
            onMainDestinationSelected = onMainDestinationSelected,
            scrollableContent = false,
            compactChrome = landscape,
            immersiveContent = fullscreen && isBasic,
            actions = {
                TextButton(onClick = {
                    onAction(
                        TrendChartAction.ChangeView(
                            if (isBasic) TrendWorkspaceView.MATHEMATICAL_RESEARCH else TrendWorkspaceView.BASIC_TREND,
                        ),
                    )
                }) { Text(if (isBasic) "数学研究" else "基本走势") }
            },
        ) {
            if (!isBasic) {
                MathematicalResearchWorkspace(
                    chart = chart,
                    isLandscape = landscape,
                    onLotteryTypeSelected = { onAction(TrendChartAction.ChangeLotteryType(it)) },
                    onRetry = { onAction(TrendChartAction.Retry) },
                )
            } else {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    TrendToolbar(
                        chart,
                        fullscreen,
                        availableMainDestinations,
                        onMainDestinationSelected,
                        onAction,
                        onFullscreen = { fullscreen = !fullscreen },
                        inlineTools = {
                            if (shortLandscape) {
                                TrendDataViewTabs(dataView, { dataView = it }, inline = true)
                                TrendDisplayMenu(
                                    view = dataView,
                                    compact = compact,
                                    omissions = showOmissions,
                                    lines = showLines,
                                    onCompact = { compactPreference = it },
                                    onOmissions = { showOmissions = it },
                                    onLines = { showLines = it },
                                )
                            }
                        },
                    )
                    if (!shortLandscape) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TrendDataViewTabs(dataView, { dataView = it }, inline = false)
                            TrendDisplayMenu(
                                view = dataView,
                                compact = compact,
                                omissions = showOmissions,
                                lines = showLines,
                                onCompact = { compactPreference = it },
                                onOmissions = { showOmissions = it },
                                onLines = { showLines = it },
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (snapshot == null) {
                        TrendLoadStatus(Modifier.weight(1f), chart.content) { onAction(TrendChartAction.Retry) }
                    } else {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when (dataView) {
                                TrendDataView.NUMBERS -> {
                                    TrendMatrix(
                                        snapshot = snapshot,
                                        listState = listState,
                                        horizontalState = horizontalState,
                                        compact = compact,
                                        showOmissions = showOmissions,
                                        showLines = showLines,
                                        selectedZone = selectedZone,
                                        onZoneSelected = { selectedZone = it },
                                        selectedNumber = selectedNumber,
                                        onNumberSelected = { selectedNumber = if (selectedNumber == it) null else it },
                                        shortLandscape = shortLandscape,
                                    )
                                }

                                TrendDataView.SHAPES -> {
                                    TrendShapeTable(snapshot, compact)
                                }

                                TrendDataView.STATISTICS -> {
                                    TrendStatisticsList(snapshot, compact) { number ->
                                        selectedNumber = number
                                        selectedZone =
                                            if (snapshot.area == LotteryTrendArea.PRIMARY) {
                                                trendNumberZones(snapshot.lotteryType, snapshot.area).indexOfFirst {
                                                    number in
                                                        it.numbers
                                                }
                                            } else {
                                                -1
                                            }
                                        dataView = TrendDataView.NUMBERS
                                        listState.requestScrollToItem(snapshot.rows.lastIndex)
                                    }
                                }
                            }
                        }
                        if (dataView == TrendDataView.NUMBERS) {
                            TrendSelectionBar(chart, selectedNumber, { selectedNumber = null }) {
                                coroutineScope.launch { listState.animateScrollToItem(snapshot.rows.lastIndex) }
                            }
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                showSource = true
                            }.padding(horizontal = 10.dp, vertical = if (shortLandscape) 3.dp else 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val missing = snapshot?.issueGaps?.sumOf { it.missingCount } ?: 0
                        Text(
                            if (missing >
                                0
                            ) {
                                "样本缺 $missing 期 · 统计有缺口"
                            } else {
                                "官方历史开奖 · ${snapshot?.actualSampleCount ?: chart.sampleSize.count}期"
                            },
                            Modifier.weight(1f),
                            fontSize = 10.sp,
                            color =
                                if (missing >
                                    0
                                ) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                        Text("历史不预测未来", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Icon(LotteryIcons.Info, "数据来源与统计口径", Modifier.padding(start = 5.dp).size(14.dp))
                    }
                }
            }
        }
    }
    if (showSource) TrendSourceDialog(chart) { showSource = false }
}

/** 在单行中放置导航、彩种、区域、期数与全屏入口；窄屏和大字号允许筛选横向浏览。 */
@Composable
private fun TrendToolbar(
    chart: TrendChartState,
    fullscreen: Boolean,
    destinations: List<MainDestination>,
    onDestination: (MainDestination) -> Unit,
    onAction: (TrendChartAction) -> Unit,
    onFullscreen: () -> Unit,
    inlineTools: @Composable RowScope.() -> Unit,
) {
    var navigationOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            TrendToolButton(LotteryIcons.Navigation, "走势导航") { navigationOpen = true }
            DropdownMenu(navigationOpen, { navigationOpen = false }) {
                DropdownMenuItem(text = { Text("数学研究") }, leadingIcon = { Icon(LotteryIcons.Trends, null) }, onClick = {
                    navigationOpen = false
                    onAction(TrendChartAction.ChangeView(TrendWorkspaceView.MATHEMATICAL_RESEARCH))
                })
                HorizontalDivider()
                destinations.forEach { destination ->
                    DropdownMenuItem(text = { Text(destination.label()) }, onClick = {
                        navigationOpen = false
                        onDestination(destination)
                    })
                }
            }
        }
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrendChoiceMenu(chart.lotteryType.trendDisplayName(), "彩种", LotteryType.entries, {
                it.trendDisplayName()
            }, chart.lotteryType) {
                onAction(TrendChartAction.ChangeLotteryType(it))
            }
            Row {
                LotteryTrendArea.entries.forEach { area ->
                    TrendTab(area.displayName(chart.lotteryType), chart.area == area, Modifier.width(44.dp)) {
                        onAction(TrendChartAction.ChangeArea(area))
                    }
                }
            }
            TrendChoiceMenu("${chart.sampleSize.count}期", "样本期数", TrendSampleSize.entries, {
                "最近 ${it.count} 期"
            }, chart.sampleSize) {
                onAction(TrendChartAction.ChangeSampleSize(it))
            }
            inlineTools()
        }
        TrendToolButton(
            if (fullscreen) LotteryIcons.ExitFullscreen else LotteryIcons.Fullscreen,
            if (fullscreen) "退出全屏" else "全屏看走势",
            onClick = onFullscreen,
        )
    }
}

/** 横屏将数据页签并入工具栏，竖屏均分一行触控区域。 */
@Composable
private fun RowScope.TrendDataViewTabs(
    selected: TrendDataView,
    onSelect: (TrendDataView) -> Unit,
    inline: Boolean,
) {
    TrendDataView.entries.forEach { view ->
        TrendTab(
            label =
                when (view) {
                    TrendDataView.NUMBERS -> "号码走势"
                    TrendDataView.SHAPES -> "开奖形态"
                    TrendDataView.STATISTICS -> "号码统计"
                },
            selected = selected == view,
            modifier = if (inline) Modifier.width(78.dp) else Modifier.weight(1f),
            onClick = { onSelect(view) },
        )
    }
}

/** 带当前选中标记的紧凑选项菜单。 */
@Composable
private fun <T> TrendChoiceMenu(
    label: String,
    description: String,
    options: List<T>,
    text: (T) -> String,
    selected: T,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { open = true },
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier =
                Modifier.semantics {
                    contentDescription =
                        description
                },
        ) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Icon(LotteryIcons.Expand, null, Modifier.padding(start = 2.dp).size(14.dp))
        }
        DropdownMenu(open, { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(text(option)) }, trailingIcon = {
                    if (option ==
                        selected
                    ) {
                        Icon(LotteryIcons.Done, null)
                    }
                }, onClick = {
                    open = false
                    onSelect(option)
                })
            }
        }
    }
}

/** 下划线页签保持固定高度，选中状态不改变布局。 */
@Composable
internal fun TrendTab(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier.height(44.dp).clickable(role = androidx.compose.ui.semantics.Role.Tab, onClick = onClick).semantics {
            this.selected =
                selected
        },
    ) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                label,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(
                    2.dp,
                ).background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
    }
}

/** 图标按钮在鼠标悬停或长按时显示中文提示，并保留移动端触控尺寸。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrendToolButton(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = {
        PlainTooltip { Text(description) }
    }, state = rememberTooltipState()) {
        IconButton(onClick, modifier.size(44.dp)) { Icon(icon, description, Modifier.size(20.dp)) }
    }
}

/** 将低频显示选项收进菜单，避免持续挤占表格高度。 */
@Composable
private fun TrendDisplayMenu(
    view: TrendDataView,
    compact: Boolean,
    omissions: Boolean,
    lines: Boolean,
    onCompact: (Boolean) -> Unit,
    onOmissions: (Boolean) -> Unit,
    onLines: (Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TrendToolButton(LotteryIcons.DisplaySettings, "走势显示设置") { open = true }
        DropdownMenu(open, { open = false }) {
            TrendCheckboxOption("紧凑显示", compact, onCompact)
            if (view == TrendDataView.NUMBERS) {
                TrendCheckboxOption("显示遗漏", omissions, onOmissions)
                TrendCheckboxOption("显示连线", lines, onLines)
            }
        }
    }
}

/** 整行可点选的二元显示设置。 */
@Composable
private fun TrendCheckboxOption(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = { Checkbox(checked, null) },
        onClick = { onChange(!checked) },
    )
}

/** 在页底显示被追踪号码的统计，未追踪时显示最新一期本区域开奖。 */
@Composable
private fun TrendSelectionBar(
    chart: TrendChartState,
    selectedNumber: Int?,
    onClear: () -> Unit,
    onLatest: () -> Unit,
) {
    val snapshot = requireNotNull(chart.snapshot)
    val statistic = snapshot.statistics.firstOrNull { it.number == selectedNumber }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(start = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (statistic != null) {
                Text(
                    "${statistic.number.twoDigits()}号",
                    color = trendHitColor(chart.lotteryType, chart.area),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
                Text(
                    "出现 ${statistic.hitCount}  /  当前遗漏 ${statistic.currentOmission}  /  最大 ${statistic.maxOmission}",
                    fontSize = 12.sp,
                )
            } else {
                Text(
                    "最新 ${snapshot.lastIssue.value}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    snapshot.rows
                        .last()
                        .cells
                        .filter {
                            it.isHit
                        }.joinToString("  ") {
                            it.number.twoDigits()
                        },
                    color = trendHitColor(chart.lotteryType, chart.area),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
            }
        }
        if (statistic != null) TrendToolButton(LotteryIcons.Clear, "取消号码追踪", onClick = onClear)
        TrendToolButton(LotteryIcons.Latest, "定位最新一期", onClick = onLatest)
    }
}

/** 加载失败时保留所有筛选与明确重试操作，不显示演示数据。 */
@Composable
private fun TrendLoadStatus(
    modifier: Modifier,
    content: TrendChartContent,
    onRetry: () -> Unit,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (content is TrendChartContent.Failed) {
                Text("历史开奖暂时不可用", color = MaterialTheme.colorScheme.error)
                Text(content.message, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                Button(onRetry) {
                    Icon(LotteryIcons.Refresh, null)
                    Spacer(Modifier.width(8.dp))
                    Text("重试")
                }
            } else {
                CircularProgressIndicator(Modifier.size(32.dp))
                Text("正在加载官方历史开奖", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** 显示可追溯数据来源、缺期情况和不会被误读为预测的统计边界。 */
@Composable
private fun TrendSourceDialog(
    chart: TrendChartState,
    onDismiss: () -> Unit,
) {
    val ready = chart.content as? TrendChartContent.Ready
    val spec = chart.lotteryType.trendAreaSpec(chart.area)
    val smallUpperBound = spec.numberRange.last / 2
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("数据与统计口径") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(ready?.sourceName ?: "官方历史开奖尚未就绪")
                ready?.let {
                    val fetchedTime =
                        Instant
                            .fromEpochMilliseconds(it.fetchedAtEpochMillis)
                            .toLocalDateTime(TimeZone.of("Asia/Shanghai"))
                    val range = "${it.snapshot.firstIssue.value} 至 ${it.snapshot.lastIssue.value}"
                    Text("$range，共 ${it.snapshot.actualSampleCount} 期")
                    Text("加载时间：${fetchedTime.date} ${fetchedTime.time}（北京时间）")
                    val missing = it.snapshot.issueGaps.sumOf { gap -> gap.missingCount }
                    Text(if (missing == 0) "样本内未发现同年度期号缺口。" else "样本缺少 $missing 期；遗漏按已有记录计算，不能视为完整连续遗漏。")
                }
                Text("出现次数、当前遗漏和最大遗漏均限于所选样本；样本前的连续遗漏未知。")
                Text(
                    "当前区域：小号 01 至 ${smallUpperBound.twoDigits()}，" +
                        "大号 ${(smallUpperBound + 1).twoDigits()} 至 ${spec.numberRange.last.twoDigits()}。" +
                        "重号与前一期比较，缺期和样本首期不计算。",
                )
                Text("历史分布不代表未来规律，不构成购彩建议。")
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("关闭") } },
    )
}

/** 保留数学研究使用的彩种分段选择器。 */
@Composable
internal fun LotteryTypeTrendSelector(
    modifier: Modifier,
    selected: LotteryType,
    onSelected: (LotteryType) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier) {
        LotteryType.entries.forEachIndexed { index, type ->
            SegmentedButton(
                selected = selected == type,
                onClick = { onSelected(type) },
                shape = SegmentedButtonDefaults.itemShape(index, LotteryType.entries.size),
                label = { Text(type.trendDisplayName()) },
            )
        }
    }
}

/** 返回区域对应的高对比命中颜色。 */
internal fun trendHitColor(
    lotteryType: LotteryType,
    area: LotteryTrendArea,
): Color =
    when {
        lotteryType == LotteryType.DOUBLE_COLOR_BALL && area == LotteryTrendArea.PRIMARY -> Color(0xFFC83C3C)
        lotteryType == LotteryType.SUPER_LOTTO && area == LotteryTrendArea.SECONDARY -> Color(0xFFAC7600)
        else -> Color(0xFF2875B7)
    }

/** 命中球统一使用白色文字，深黄色后区也保持足够对比度。 */
internal fun trendHitContentColor(
    lotteryType: LotteryType,
    area: LotteryTrendArea,
): Color = Color.White

/** 彩种中文名称。 */
internal fun LotteryType.trendDisplayName(): String = if (this == LotteryType.SUPER_LOTTO) "大乐透" else "双色球"

/** 当前彩种号码区域的中文名称。 */
internal fun LotteryTrendArea.displayName(lotteryType: LotteryType): String =
    when (lotteryType) {
        LotteryType.SUPER_LOTTO -> if (this == LotteryTrendArea.PRIMARY) "前区" else "后区"
        LotteryType.DOUBLE_COLOR_BALL -> if (this == LotteryTrendArea.PRIMARY) "红球" else "蓝球"
    }

/** 号码固定两位，统计值不调用此格式化函数。 */
internal fun Int.twoDigits(): String = toString().padStart(2, '0')

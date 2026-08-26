package roc.win.lottery.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import roc.win.lottery.app.MainDestination
import roc.win.lottery.app.TrendChartAction
import roc.win.lottery.app.TrendChartContent
import roc.win.lottery.app.TrendChartState
import roc.win.lottery.domain.LotteryTrendArea
import roc.win.lottery.domain.LotteryTrendSnapshot
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.TrendDrawRow
import roc.win.lottery.domain.TrendNumberStatistics
import roc.win.lottery.domain.TrendSampleSize
import kotlin.time.Instant

/** 竖屏固定期号列宽度。 */
private val PORTRAIT_ISSUE_COLUMN_WIDTH = 80.dp

/** 竖屏号码矩阵单元格宽度。 */
private val PORTRAIT_CELL_WIDTH = 32.dp

/** 横屏固定期号列宽度。 */
private val LANDSCAPE_ISSUE_COLUMN_WIDTH = 64.dp

/** 横屏号码格允许使用的最小宽度。 */
private val LANDSCAPE_MIN_CELL_WIDTH = 14.dp

/** 横屏号码格允许使用的最大宽度。 */
private val LANDSCAPE_MAX_CELL_WIDTH = 32.dp

/** 允许判断完整矩阵已经铺满可用宽度的浮点误差。 */
private val TREND_LAYOUT_TOLERANCE = 0.5.dp

/** 官方历史开奖加载时间使用的北京时间。 */
private val CHINA_TIME_ZONE = TimeZone.of("Asia/Shanghai")

/** 完整加载时间文本长度。 */
private const val FETCHED_TIME_TEXT_LENGTH = 16

/** 横屏时间文本省略年份和连字符的前缀长度。 */
private const val COMPACT_FETCHED_TIME_PREFIX_LENGTH = 5

/** 大乐透前区命中颜色，对齐体彩常见蓝色语义。 */
private val SuperLottoPrimaryBlue = Color(0xFF2F75B5)

/** 大乐透后区命中颜色，对齐体彩常见黄色语义。 */
private val SuperLottoSecondaryAmber = Color(0xFFD99A00)

/** 双色球红球命中颜色。 */
private val DoubleColorBallPrimaryRed = Color(0xFFC83C3C)

/** 双色球蓝球命中颜色。 */
private val DoubleColorBallSecondaryBlue = Color(0xFF2875B7)

/** 大乐透前区表格底色。 */
private val SuperLottoPrimaryTint = Color(0xFFEAF3FA)

/** 大乐透后区表格底色。 */
private val SuperLottoSecondaryTint = Color(0xFFFFF5D9)

/** 双色球红球表格底色。 */
private val DoubleColorBallPrimaryTint = Color(0xFFFFEEEE)

/** 双色球蓝球表格底色。 */
private val DoubleColorBallSecondaryTint = Color(0xFFEAF3FA)

/**
 * 当前视口下号码矩阵使用的稳定尺寸。
 *
 * @property issueColumnWidth 固定期号列宽度。
 * @property cellWidth 单个号码格宽度。
 * @property zoneHeaderHeight 分区表头高度。
 * @property numberHeaderHeight 号码表头高度。
 * @property drawRowHeight 单期开奖行高度。
 * @property statisticRowHeight 单行统计高度。
 * @property hitBallSize 命中号码圆点直径。
 * @property showsAllNumbers 当前宽度是否无需横向滚动即可展示全部号码。
 */
private data class TrendTableLayout(
    val issueColumnWidth: Dp,
    val cellWidth: Dp,
    val zoneHeaderHeight: Dp,
    val numberHeaderHeight: Dp,
    val drawRowHeight: Dp,
    val statisticRowHeight: Dp,
    val hitBallSize: Dp,
    val showsAllNumbers: Boolean,
)

/**
 * 官方常见走势图中的一个连续号码分区。
 *
 * @property label 分区名称。
 * @property firstNumber 分区首个号码。
 * @property lastNumber 分区末尾号码。
 */
private data class TrendNumberZone(
    val label: String,
    val firstNumber: Int,
    val lastNumber: Int,
) {
    /** 当前分区包含的号码数量。 */
    val numberCount: Int
        get() = lastNumber - firstNumber + 1
}

/**
 * V1.3 跨平台基本走势图一级工作区。
 *
 * @param chart 当前会话中的走势图配置与快照。
 * @param availableMainDestinations 当前平台可进入的一级目的地。
 * @param onMainDestinationSelected 一级目的地切换操作。
 * @param onAction 修改彩种、号码区域或样本范围。
 */
@Composable
fun TrendScreen(
    chart: TrendChartState,
    availableMainDestinations: List<MainDestination>,
    onMainDestinationSelected: (MainDestination) -> Unit,
    onAction: (TrendChartAction) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isLandscape = maxWidth > maxHeight
        AppShell(
            title = "走势",
            mainDestination = MainDestination.TRENDS,
            availableMainDestinations = availableMainDestinations,
            onMainDestinationSelected = onMainDestinationSelected,
            scrollableContent = false,
            compactChrome = isLandscape,
        ) {
            TrendWorkspace(
                chart = chart,
                isLandscape = isLandscape,
                onAction = onAction,
            )
        }
    }
}

/**
 * 根据可用正文宽度构建竖屏浏览或横屏专注走势图。
 *
 * @param chart 当前走势图配置与快照。
 * @param isLandscape 当前是否为横屏视口。
 * @param onAction 修改走势图配置。
 */
@Composable
private fun TrendWorkspace(
    chart: TrendChartState,
    isLandscape: Boolean,
    onAction: (TrendChartAction) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val ready = chart.content as? TrendChartContent.Ready
        if (ready == null) {
            Column(modifier = Modifier.fillMaxSize()) {
                TrendControls(chart = chart, isLandscape = isLandscape, onAction = onAction)
                TrendLoadStatus(
                    modifier = Modifier.weight(1f),
                    content = chart.content,
                    isLandscape = isLandscape,
                    onRetry = { onAction(TrendChartAction.Retry) },
                )
            }
            return@BoxWithConstraints
        }
        val snapshot = ready.snapshot
        val horizontalScrollState = rememberScrollState()
        val layout = trendTableLayout(maxWidth, snapshot.statistics.size, isLandscape)
        LaunchedEffect(isLandscape, snapshot.lotteryType, snapshot.area) {
            horizontalScrollState.scrollTo(0)
        }
        if (isLandscape) {
            LandscapeTrendContent(
                chart = chart,
                layout = layout,
                horizontalScrollState = horizontalScrollState,
                onAction = onAction,
            )
        } else {
            PortraitTrendContent(
                chart = chart,
                layout = layout,
                horizontalScrollState = horizontalScrollState,
                onAction = onAction,
            )
        }
    }
}

/**
 * 在保留筛选控件的同时展示官方历史开奖加载或失败状态。
 *
 * @param modifier 加载状态区域布局修饰符。
 * @param content 当前加载状态。
 * @param isLandscape 当前是否为横屏视口。
 * @param onRetry 用户明确发起重试的操作。
 */
@Composable
private fun TrendLoadStatus(
    modifier: Modifier,
    content: TrendChartContent,
    isLandscape: Boolean,
    onRetry: () -> Unit,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = if (isLandscape) 12.dp else 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (content) {
                TrendChartContent.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.size(if (isLandscape) 28.dp else 40.dp))
                    Spacer(Modifier.height(if (isLandscape) 10.dp else 18.dp))
                    Text("正在加载官方历史开奖", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "以最新已发布期为起点，向前读取 500 期",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                is TrendChartContent.Failed -> {
                    Text(
                        "历史开奖暂时不可用",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        content.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(if (isLandscape) 10.dp else 18.dp))
                    Button(onClick = onRetry, shape = MaterialTheme.shapes.small) {
                        Icon(LotteryIcons.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("重试")
                    }
                }

                is TrendChartContent.Ready -> {
                    return@Column
                }
            }
        }
    }
}

/**
 * 竖屏使用紧凑筛选区并继续支持矩阵横向浏览。
 *
 * @param chart 当前走势图配置与快照。
 * @param layout 当前表格稳定尺寸。
 * @param horizontalScrollState 所有号码行共享的横向位置。
 * @param onAction 修改走势图配置。
 */
@Composable
private fun PortraitTrendContent(
    chart: TrendChartState,
    layout: TrendTableLayout,
    horizontalScrollState: ScrollState,
    onAction: (TrendChartAction) -> Unit,
) {
    val snapshot = requireNotNull(chart.snapshot)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
    ) {
        item(key = "configuration") {
            TrendControls(chart = chart, isLandscape = false, onAction = onAction)
            Spacer(Modifier.height(8.dp))
            TrendSnapshotStatus(chart = chart, isLandscape = false)
            Spacer(Modifier.height(10.dp))
        }
        item(key = "table-header") {
            TrendTableHeader(
                snapshot = snapshot,
                layout = layout,
                horizontalScrollState = horizontalScrollState,
            )
        }
        item(key = "statistics") {
            TrendStatisticsTable(
                lotteryType = snapshot.lotteryType,
                area = snapshot.area,
                statistics = snapshot.statistics,
                layout = layout,
                horizontalScrollState = horizontalScrollState,
            )
        }
        trendDrawItems(
            snapshot = snapshot,
            layout = layout,
            horizontalScrollState = horizontalScrollState,
        )
        item(key = "responsible-use") { ResponsibleTrendNotice() }
    }
}

/**
 * 横屏固定分区与号码表头，只纵向滚动开奖号和统计行。
 *
 * @param chart 当前走势图配置与快照。
 * @param layout 当前表格稳定尺寸。
 * @param horizontalScrollState 所有号码行共享的横向位置。
 * @param onAction 修改走势图配置。
 */
@Composable
private fun LandscapeTrendContent(
    chart: TrendChartState,
    layout: TrendTableLayout,
    horizontalScrollState: ScrollState,
    onAction: (TrendChartAction) -> Unit,
) {
    val snapshot = requireNotNull(chart.snapshot)
    val lastNumber =
        snapshot.statistics
            .last()
            .number
            .toTwoDigits()
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription =
                        if (layout.showsAllNumbers) {
                            "横屏完整号码矩阵，01至${lastNumber}全部可见"
                        } else {
                            "横屏号码矩阵，01至${lastNumber}需要横向浏览"
                        }
                },
    ) {
        TrendControls(chart = chart, isLandscape = true, onAction = onAction)
        TrendSnapshotStatus(chart = chart, isLandscape = true)
        TrendTableHeader(
            snapshot = snapshot,
            layout = layout,
            horizontalScrollState = horizontalScrollState,
        )
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            item(key = "statistics") {
                TrendStatisticsTable(
                    lotteryType = snapshot.lotteryType,
                    area = snapshot.area,
                    statistics = snapshot.statistics,
                    layout = layout,
                    horizontalScrollState = horizontalScrollState,
                )
            }
            trendDrawItems(
                snapshot = snapshot,
                layout = layout,
                horizontalScrollState = horizontalScrollState,
            )
            item(key = "responsible-use") { ResponsibleTrendNotice() }
        }
    }
}

/**
 * 向惰性列表追加全部开奖行，供横竖屏共享同一绘制逻辑。
 *
 * @param snapshot 当前走势快照。
 * @param layout 当前表格稳定尺寸。
 * @param horizontalScrollState 所有号码行共享的横向位置。
 */
private fun LazyListScope.trendDrawItems(
    snapshot: LotteryTrendSnapshot,
    layout: TrendTableLayout,
    horizontalScrollState: ScrollState,
) {
    itemsIndexed(
        items = snapshot.rows,
        key = { _, row -> row.issue.value },
    ) { index, row ->
        TrendTableDrawRow(
            lotteryType = snapshot.lotteryType,
            area = snapshot.area,
            row = row,
            previousRow = snapshot.rows.getOrNull(index - 1),
            nextRow = snapshot.rows.getOrNull(index + 1),
            rowIndex = index,
            layout = layout,
            horizontalScrollState = horizontalScrollState,
        )
    }
}

/**
 * 绘制紧凑的彩种、区域和样本筛选区。
 *
 * @param chart 当前走势图配置与快照。
 * @param isLandscape 当前是否为横屏视口。
 * @param onAction 修改走势图配置。
 */
@Composable
private fun TrendControls(
    chart: TrendChartState,
    isLandscape: Boolean,
    onAction: (TrendChartAction) -> Unit,
) {
    val lotterySelector: @Composable () -> Unit = {
        LotteryTypeTrendSelector(
            modifier = Modifier.fillMaxWidth(),
            selected = chart.lotteryType,
            onSelected = { onAction(TrendChartAction.ChangeLotteryType(it)) },
        )
    }
    val areaSelector: @Composable () -> Unit = {
        TrendAreaSelector(
            modifier = Modifier.fillMaxWidth(),
            lotteryType = chart.lotteryType,
            selected = chart.area,
            onSelected = { onAction(TrendChartAction.ChangeArea(it)) },
        )
    }
    val sampleSelector: @Composable () -> Unit = {
        TrendSampleSizeSelector(
            modifier = Modifier.fillMaxWidth(),
            selected = chart.sampleSize,
            onSelected = { onAction(TrendChartAction.ChangeSampleSize(it)) },
        )
    }
    if (isLandscape) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrendControlGroup("彩种", Modifier.weight(1.15f), lotterySelector)
            TrendControlGroup("区域", Modifier.weight(1f), areaSelector)
            TrendControlGroup("期数", Modifier.weight(1.25f), sampleSelector)
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TrendControlGroup("彩种", Modifier.fillMaxWidth(), lotterySelector)
            TrendControlGroup("区域", Modifier.fillMaxWidth(), areaSelector)
            TrendControlGroup("期数", Modifier.fillMaxWidth(), sampleSelector)
        }
    }
}

/**
 * 为一组紧凑筛选控件提供固定标签列。
 *
 * @param label 筛选组标签。
 * @param modifier 当前筛选组布局修饰符。
 * @param content 筛选控件内容。
 */
@Composable
private fun TrendControlGroup(
    label: String,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.width(44.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(modifier = Modifier.weight(1f)) { content() }
    }
}

/**
 * 使用分段控件选择当前走势彩种。
 *
 * @param modifier 当前控件布局修饰符。
 * @param selected 当前彩种。
 * @param onSelected 彩种切换操作。
 */
@Composable
private fun LotteryTypeTrendSelector(
    modifier: Modifier,
    selected: LotteryType,
    onSelected: (LotteryType) -> Unit,
) {
    val options = listOf(LotteryType.SUPER_LOTTO, LotteryType.DOUBLE_COLOR_BALL)
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        options.forEachIndexed { index, lotteryType ->
            SegmentedButton(
                selected = lotteryType == selected,
                onClick = { onSelected(lotteryType) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                label = { Text(lotteryType.displayName()) },
            )
        }
    }
}

/**
 * 使用分段控件选择当前号码区域。
 *
 * @param modifier 当前控件布局修饰符。
 * @param lotteryType 当前彩种。
 * @param selected 当前号码区域。
 * @param onSelected 号码区域切换操作。
 */
@Composable
private fun TrendAreaSelector(
    modifier: Modifier,
    lotteryType: LotteryType,
    selected: LotteryTrendArea,
    onSelected: (LotteryTrendArea) -> Unit,
) {
    val options = listOf(LotteryTrendArea.PRIMARY, LotteryTrendArea.SECONDARY)
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        options.forEachIndexed { index, area ->
            SegmentedButton(
                selected = area == selected,
                onClick = { onSelected(area) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                label = { Text(area.displayName(lotteryType)) },
            )
        }
    }
}

/**
 * 使用单选筛选项选择冻结的最近样本范围。
 *
 * @param modifier 当前控件布局修饰符。
 * @param selected 当前样本范围。
 * @param onSelected 样本范围切换操作。
 */
@Composable
private fun TrendSampleSizeSelector(
    modifier: Modifier,
    selected: TrendSampleSize,
    onSelected: (TrendSampleSize) -> Unit,
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    LazyRow(
        modifier = modifier,
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(items = TrendSampleSize.entries, key = { _, item -> item.count }) {
            index,
            sampleSize,
            ->
            FilterChip(
                selected = sampleSize == selected,
                onClick = {
                    onSelected(sampleSize)
                    coroutineScope.launch { listState.animateScrollToItem(index) }
                },
                label = { Text("${sampleSize.count} 期") },
            )
        }
    }
}

/**
 * 使用紧凑状态条展示官方来源、样本范围、连续性和加载时间。
 *
 * @param chart 当前走势图配置与快照。
 * @param isLandscape 当前是否为横屏视口。
 */
@Composable
private fun TrendSnapshotStatus(
    chart: TrendChartState,
    isLandscape: Boolean,
) {
    val ready = chart.content as? TrendChartContent.Ready ?: return
    val snapshot = ready.snapshot
    val missingIssueCount = snapshot.issueGaps.sumOf { it.missingCount }
    val title = "${snapshot.lotteryType.displayName()} · ${snapshot.area.displayName(snapshot.lotteryType)}"
    val range =
        "${snapshot.actualSampleCount} 期 · ${snapshot.firstIssue.value} 至 ${snapshot.lastIssue.value}"
    val continuity =
        if (missingIssueCount == 0) "样本内期号连续" else "样本内缺少 $missingIssueCount 期"
    val source =
        "官方历史开奖 · ${ready.sourceName} · ${formatTrendFetchedTime(ready.fetchedAtEpochMillis, isLandscape)}"
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
    ) {
        if (isLandscape) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val summary =
                    if (missingIssueCount == 0) {
                        "$title · $range"
                    } else {
                        "$title · $range · $continuity"
                    }
                Text(
                    summary,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (missingIssueCount == 0) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    maxLines = 1,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    source,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        } else {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    range,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    continuity,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (missingIssueCount == 0) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                )
                Text(
                    source,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 将会话抓取时间格式化为北京时间。 */
private fun formatTrendFetchedTime(
    epochMillis: Long,
    isCompact: Boolean,
): String {
    val dateTime = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(CHINA_TIME_ZONE)
    val value = dateTime.toString().replace('T', ' ').take(FETCHED_TIME_TEXT_LENGTH)
    return if (isCompact) value.drop(COMPACT_FETCHED_TIME_PREFIX_LENGTH) else value
}

/** 展示走势图必须保留的理性使用边界。 */
@Composable
private fun ResponsibleTrendNotice() {
    Text(
        "历史分布不代表未来规律，不构成购彩建议。",
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 根据方向和可用宽度返回不会发生布局跳动的表格尺寸。 */
private fun trendTableLayout(
    availableWidth: Dp,
    numberCount: Int,
    isLandscape: Boolean,
): TrendTableLayout {
    require(numberCount > 0)
    val issueColumnWidth =
        if (isLandscape) LANDSCAPE_ISSUE_COLUMN_WIDTH else PORTRAIT_ISSUE_COLUMN_WIDTH
    val matrixWidth =
        if (availableWidth > issueColumnWidth) availableWidth - issueColumnWidth else 0.dp
    val cellWidth =
        if (isLandscape) {
            (matrixWidth / numberCount.toFloat()).coerceIn(
                LANDSCAPE_MIN_CELL_WIDTH,
                LANDSCAPE_MAX_CELL_WIDTH,
            )
        } else {
            PORTRAIT_CELL_WIDTH
        }
    val showsAllNumbers =
        cellWidth * numberCount <= matrixWidth + TREND_LAYOUT_TOLERANCE
    val hitBallSize =
        if (isLandscape) {
            minOf(22.dp, cellWidth - 2.dp).coerceAtLeast(12.dp)
        } else {
            26.dp
        }
    return TrendTableLayout(
        issueColumnWidth = issueColumnWidth,
        cellWidth = cellWidth,
        zoneHeaderHeight = if (isLandscape) 22.dp else 24.dp,
        numberHeaderHeight = if (isLandscape) 28.dp else 34.dp,
        drawRowHeight = if (isLandscape) 28.dp else 36.dp,
        statisticRowHeight = if (isLandscape) 32.dp else 42.dp,
        hitBallSize = hitBallSize,
        showsAllNumbers = showsAllNumbers,
    )
}

/** 绘制固定期号列与可同步横向滚动的号码表头。 */
@Composable
private fun TrendTableHeader(
    snapshot: LotteryTrendSnapshot,
    layout: TrendTableLayout,
    horizontalScrollState: ScrollState,
) {
    val areaTint = trendAreaTint(snapshot.lotteryType, snapshot.area)
    TrendZoneHeader(
        lotteryType = snapshot.lotteryType,
        area = snapshot.area,
        lastNumber = snapshot.statistics.last().number,
        areaTint = areaTint,
        layout = layout,
        horizontalScrollState = horizontalScrollState,
    )
    FixedLeadingTrendRow(
        height = layout.numberHeaderHeight,
        issueColumnWidth = layout.issueColumnWidth,
        leadingText = "期号",
        leadingDescription = "期号列",
        leadingBackground = MaterialTheme.colorScheme.surfaceVariant,
        description =
            "期号与${snapshot.statistics.first().number.toTwoDigits()}至" +
                "${snapshot.statistics.last().number.toTwoDigits()}号码列",
        horizontalScrollState = horizontalScrollState,
    ) {
        Row(modifier = Modifier.width(layout.cellWidth * snapshot.statistics.size)) {
            val zones =
                trendNumberZones(
                    lotteryType = snapshot.lotteryType,
                    area = snapshot.area,
                    lastNumber = snapshot.statistics.last().number,
                )
            snapshot.statistics.forEach { statistic ->
                val zoneIndex = zones.indexOfFirst { statistic.number <= it.lastNumber }
                TrendHeaderCell(
                    number = statistic.number,
                    cellWidth = layout.cellWidth,
                    background = areaTint.copy(alpha = if (zoneIndex % 2 == 0) 0.92f else 0.68f),
                )
            }
        }
    }
}

/**
 * 绘制一区、二区、三区等官方常见号码分区表头。
 *
 * @param lotteryType 当前彩种。
 * @param area 当前号码区域。
 * @param lastNumber 当前区域末尾号码。
 * @param areaTint 当前号码区域底色。
 * @param layout 当前表格稳定尺寸。
 * @param horizontalScrollState 所有号码行共享的横向位置。
 */
@Composable
private fun TrendZoneHeader(
    lotteryType: LotteryType,
    area: LotteryTrendArea,
    lastNumber: Int,
    areaTint: Color,
    layout: TrendTableLayout,
    horizontalScrollState: ScrollState,
) {
    val zones = trendNumberZones(lotteryType, area, lastNumber)
    FixedLeadingTrendRow(
        height = layout.zoneHeaderHeight,
        issueColumnWidth = layout.issueColumnWidth,
        leadingText = "分区",
        leadingDescription = "号码分区",
        leadingBackground = MaterialTheme.colorScheme.surfaceVariant,
        description = zones.joinToString("，") { "${it.label}${it.firstNumber}至${it.lastNumber}" },
        horizontalScrollState = horizontalScrollState,
    ) {
        Row(modifier = Modifier.width(layout.cellWidth * lastNumber)) {
            zones.forEachIndexed { index, zone ->
                Box(
                    modifier =
                        Modifier
                            .width(layout.cellWidth * zone.numberCount)
                            .fillMaxHeight()
                            .background(areaTint.copy(alpha = if (index % 2 == 0) 0.98f else 0.72f))
                            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                            .clearAndSetSemantics {
                                contentDescription =
                                    "${zone.label} ${zone.firstNumber.toTwoDigits()}至" +
                                    zone.lastNumber.toTwoDigits()
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        zone.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * 绘制一个表头号码格。
 *
 * @param number 当前列号码。
 * @param cellWidth 当前号码格宽度。
 * @param background 当前分区底色。
 */
@Composable
private fun TrendHeaderCell(
    number: Int,
    cellWidth: Dp,
    background: Color,
) {
    Box(
        modifier =
            Modifier
                .width(cellWidth)
                .fillMaxHeight()
                .background(background)
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                .clearAndSetSemantics {
                    contentDescription = "号码 ${number.toTwoDigits()}"
                },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            number.toTwoDigits(),
            style = trendGridTextStyle(FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/** 绘制单期开奖走势行和相邻期同排序位置的低饱和连线。 */
@Composable
private fun TrendTableDrawRow(
    lotteryType: LotteryType,
    area: LotteryTrendArea,
    row: TrendDrawRow,
    previousRow: TrendDrawRow?,
    nextRow: TrendDrawRow?,
    rowIndex: Int,
    layout: TrendTableLayout,
    horizontalScrollState: ScrollState,
) {
    val hitColor = trendHitColor(lotteryType, area)
    val hitContentColor = trendHitContentColor(lotteryType, area)
    val areaTint = trendAreaTint(lotteryType, area)
    val rowBackground = areaTint.copy(alpha = if (rowIndex % 2 == 0) 0.72f else 0.46f)
    val hitNumbers = row.cells.filter { it.isHit }.map { it.number }
    FixedLeadingTrendRow(
        height = layout.drawRowHeight,
        issueColumnWidth = layout.issueColumnWidth,
        leadingText = row.issue.value,
        leadingDescription = "期号 ${row.issue.value}",
        leadingBackground =
            if (rowIndex % 2 == 0) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            },
        description = "第 ${row.issue.value} 期，命中 ${hitNumbers.joinToString("、") { it.toTwoDigits() }}",
        horizontalScrollState = horizontalScrollState,
    ) {
        TrendNumberGridRow(
            row = row,
            previousRow = previousRow,
            nextRow = nextRow,
            background = rowBackground,
            hitColor = hitColor,
            hitContentColor = hitContentColor,
            layout = layout,
            zones = trendNumberZones(lotteryType, area, row.cells.last().number),
        )
    }
}

/** 绘制号码格、遗漏数字、网格与相邻期连线。 */
@Composable
private fun TrendNumberGridRow(
    row: TrendDrawRow,
    previousRow: TrendDrawRow?,
    nextRow: TrendDrawRow?,
    background: Color,
    hitColor: Color,
    hitContentColor: Color,
    layout: TrendTableLayout,
    zones: List<TrendNumberZone>,
) {
    val cellCount = row.cells.size
    val currentHitIndices = row.hitIndices()
    val previousHitIndices = previousRow?.hitIndices().orEmpty()
    val nextHitIndices = nextRow?.hitIndices().orEmpty()
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier =
            Modifier
                .width(layout.cellWidth * cellCount)
                .fillMaxHeight()
                .background(background),
    ) {
        Canvas(modifier = Modifier.fillMaxSize().clearAndSetSemantics {}) {
            val cellWidth = layout.cellWidth.toPx()
            for (index in 0..cellCount) {
                val x = index * cellWidth
                drawLine(
                    color = gridColor,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 0.5.dp.toPx(),
                )
            }
            drawLine(
                color = gridColor,
                start = Offset(0f, 0f),
                end = Offset(size.width, 0f),
                strokeWidth = 0.5.dp.toPx(),
            )
            zones.dropLast(1).forEach { zone ->
                val x = zone.lastNumber * cellWidth
                drawLine(
                    color = gridColor.copy(alpha = 0.95f),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1.5.dp.toPx(),
                )
            }
            drawLine(
                color = gridColor,
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
                strokeWidth = 0.5.dp.toPx(),
            )
            previousHitIndices.zip(currentHitIndices).forEach { (previous, current) ->
                drawLine(
                    color = hitColor.copy(alpha = 0.28f),
                    start = Offset((previous + 0.5f) * cellWidth, 0f),
                    end = Offset((current + 0.5f) * cellWidth, size.height / 2f),
                    strokeWidth = 1.5.dp.toPx(),
                )
            }
            currentHitIndices.zip(nextHitIndices).forEach { (current, next) ->
                drawLine(
                    color = hitColor.copy(alpha = 0.28f),
                    start = Offset((current + 0.5f) * cellWidth, size.height / 2f),
                    end = Offset((next + 0.5f) * cellWidth, size.height),
                    strokeWidth = 1.5.dp.toPx(),
                )
            }
        }
        Row(modifier = Modifier.fillMaxSize()) {
            row.cells.forEach { cell ->
                Box(
                    modifier =
                        Modifier
                            .width(layout.cellWidth)
                            .fillMaxHeight()
                            .clearAndSetSemantics {},
                    contentAlignment = Alignment.Center,
                ) {
                    if (cell.isHit) {
                        Surface(
                            modifier = Modifier.size(layout.hitBallSize),
                            shape = CircleShape,
                            color = hitColor,
                            contentColor = hitContentColor,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    cell.number.toTwoDigits(),
                                    style = trendGridTextStyle(FontWeight.Bold),
                                    maxLines = 1,
                                )
                            }
                        }
                    } else {
                        Text(
                            cell.omission.toString(),
                            style = trendGridTextStyle(FontWeight.Normal),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** 绘制出现次数、当前遗漏和最大遗漏三行统计。 */
@Composable
private fun TrendStatisticsTable(
    lotteryType: LotteryType,
    area: LotteryTrendArea,
    statistics: List<TrendNumberStatistics>,
    layout: TrendTableLayout,
    horizontalScrollState: ScrollState,
) {
    val background = trendAreaTint(lotteryType, area)
    StatisticTrendRow(
        visibleLabel = "出现\n次数",
        accessibilityLabel = "出现次数",
        values = statistics.map { it.hitCount },
        background = background,
        layout = layout,
        horizontalScrollState = horizontalScrollState,
    )
    StatisticTrendRow(
        visibleLabel = "当前\n遗漏",
        accessibilityLabel = "当前遗漏",
        values = statistics.map { it.currentOmission },
        background = background,
        layout = layout,
        horizontalScrollState = horizontalScrollState,
    )
    StatisticTrendRow(
        visibleLabel = "最大\n遗漏",
        accessibilityLabel = "最大遗漏",
        values = statistics.map { it.maxOmission },
        background = background,
        layout = layout,
        horizontalScrollState = horizontalScrollState,
    )
}

/** 绘制一行固定标签和完整号码统计值。 */
@Composable
private fun StatisticTrendRow(
    visibleLabel: String,
    accessibilityLabel: String,
    values: List<Int>,
    background: Color,
    layout: TrendTableLayout,
    horizontalScrollState: ScrollState,
) {
    FixedLeadingTrendRow(
        height = layout.statisticRowHeight,
        issueColumnWidth = layout.issueColumnWidth,
        leadingText = visibleLabel,
        leadingDescription = accessibilityLabel,
        leadingBackground = MaterialTheme.colorScheme.surfaceVariant,
        description =
            "$accessibilityLabel，" +
                values.mapIndexed { index, value -> "${(index + 1).toTwoDigits()}号$value" }.joinToString("，"),
        horizontalScrollState = horizontalScrollState,
    ) {
        Row(modifier = Modifier.width(layout.cellWidth * values.size)) {
            values.forEach { value ->
                Box(
                    modifier =
                        Modifier
                            .width(layout.cellWidth)
                            .fillMaxHeight()
                            .background(background)
                            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                            .clearAndSetSemantics {},
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        value.toString(),
                        style = trendGridTextStyle(FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 绘制固定左列与共享滚动位置的右侧表格区域。 */
@Composable
private fun FixedLeadingTrendRow(
    height: Dp,
    issueColumnWidth: Dp,
    leadingText: String,
    leadingDescription: String,
    leadingBackground: Color,
    description: String,
    horizontalScrollState: ScrollState,
    content: @Composable () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = description },
    ) {
        Box(
            modifier =
                Modifier
                    .width(issueColumnWidth)
                    .fillMaxHeight()
                    .background(leadingBackground)
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                    .clearAndSetSemantics { contentDescription = leadingDescription },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                leadingText,
                style = trendGridTextStyle(FontWeight.SemiBold),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
            )
        }
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .horizontalScroll(horizontalScrollState),
        ) {
            content()
        }
    }
}

/** 返回不随辅助字号膨胀、能稳定容纳两位数字的网格文字样式。 */
@Composable
private fun trendGridTextStyle(fontWeight: FontWeight): TextStyle {
    val fontScale = LocalDensity.current.fontScale
    return MaterialTheme.typography.labelLarge.copy(
        fontSize = 9.sp / fontScale,
        lineHeight = 11.sp / fontScale,
        letterSpacing = 0.sp,
        fontWeight = fontWeight,
    )
}

/** 返回当前号码区域的官方常见连续分区。 */
private fun trendNumberZones(
    lotteryType: LotteryType,
    area: LotteryTrendArea,
    lastNumber: Int,
): List<TrendNumberZone> =
    when {
        area == LotteryTrendArea.SECONDARY -> {
            listOf(
                TrendNumberZone(
                    label = area.displayName(lotteryType),
                    firstNumber = 1,
                    lastNumber = lastNumber,
                ),
            )
        }

        lotteryType == LotteryType.SUPER_LOTTO -> {
            listOf(
                TrendNumberZone("一区", 1, 12),
                TrendNumberZone("二区", 13, 24),
                TrendNumberZone("三区", 25, lastNumber),
            )
        }

        else -> {
            listOf(
                TrendNumberZone("一区", 1, 11),
                TrendNumberZone("二区", 12, 22),
                TrendNumberZone("三区", 23, lastNumber),
            )
        }
    }

/** 返回彩种和号码区域对应的命中颜色。 */
private fun trendHitColor(
    lotteryType: LotteryType,
    area: LotteryTrendArea,
): Color =
    when (lotteryType) {
        LotteryType.SUPER_LOTTO -> {
            when (area) {
                LotteryTrendArea.PRIMARY -> SuperLottoPrimaryBlue
                LotteryTrendArea.SECONDARY -> SuperLottoSecondaryAmber
            }
        }

        LotteryType.DOUBLE_COLOR_BALL -> {
            when (area) {
                LotteryTrendArea.PRIMARY -> DoubleColorBallPrimaryRed
                LotteryTrendArea.SECONDARY -> DoubleColorBallSecondaryBlue
            }
        }
    }

/** 返回命中圆点上的高对比文字颜色。 */
private fun trendHitContentColor(
    lotteryType: LotteryType,
    area: LotteryTrendArea,
): Color =
    if (lotteryType == LotteryType.SUPER_LOTTO && area == LotteryTrendArea.SECONDARY) {
        Color(0xFF332400)
    } else {
        Color.White
    }

/** 返回彩种和号码区域对应的低饱和表格底色。 */
private fun trendAreaTint(
    lotteryType: LotteryType,
    area: LotteryTrendArea,
): Color =
    when (lotteryType) {
        LotteryType.SUPER_LOTTO -> {
            when (area) {
                LotteryTrendArea.PRIMARY -> SuperLottoPrimaryTint
                LotteryTrendArea.SECONDARY -> SuperLottoSecondaryTint
            }
        }

        LotteryType.DOUBLE_COLOR_BALL -> {
            when (area) {
                LotteryTrendArea.PRIMARY -> DoubleColorBallPrimaryTint
                LotteryTrendArea.SECONDARY -> DoubleColorBallSecondaryTint
            }
        }
    }

/** 返回彩种中文名称。 */
private fun LotteryType.displayName(): String =
    when (this) {
        LotteryType.SUPER_LOTTO -> "大乐透"
        LotteryType.DOUBLE_COLOR_BALL -> "双色球"
    }

/** 返回当前彩种下号码区域的官方常用名称。 */
private fun LotteryTrendArea.displayName(lotteryType: LotteryType): String =
    when (lotteryType) {
        LotteryType.SUPER_LOTTO -> {
            when (this) {
                LotteryTrendArea.PRIMARY -> "前区"
                LotteryTrendArea.SECONDARY -> "后区"
            }
        }

        LotteryType.DOUBLE_COLOR_BALL -> {
            when (this) {
                LotteryTrendArea.PRIMARY -> "红球"
                LotteryTrendArea.SECONDARY -> "蓝球"
            }
        }
    }

/** 返回当前行按号码升序排列的命中列索引。 */
private fun TrendDrawRow.hitIndices(): List<Int> =
    cells.mapIndexedNotNull { index, cell -> index.takeIf { cell.isHit } }

/** 把号码格式化为固定两位文本。 */
private fun Int.toTwoDigits(): String = toString().padStart(2, '0')

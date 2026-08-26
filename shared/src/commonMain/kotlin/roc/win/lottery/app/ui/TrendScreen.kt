package roc.win.lottery.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import roc.win.lottery.app.MainDestination
import roc.win.lottery.app.TrendChartAction
import roc.win.lottery.app.TrendChartState
import roc.win.lottery.domain.LotteryTrendArea
import roc.win.lottery.domain.LotteryTrendSnapshot
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.TrendDrawRow
import roc.win.lottery.domain.TrendNumberStatistics
import roc.win.lottery.domain.TrendSampleSize

/** 固定期号列宽度，兼容七位期号与最大辅助字号。 */
private val ISSUE_COLUMN_WIDTH = 92.dp

/** 号码矩阵单元格宽度。 */
private val TREND_CELL_WIDTH = 38.dp

/** 号码矩阵表头高度。 */
private val TREND_HEADER_HEIGHT = 40.dp

/** 单期开奖行高度。 */
private val TREND_DRAW_ROW_HEIGHT = 42.dp

/** 底部统计行高度。 */
private val TREND_STATISTIC_ROW_HEIGHT = 48.dp

/** 命中号码圆点直径。 */
private val HIT_BALL_SIZE = 30.dp

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

/** 演示数据状态条底色。 */
private val DemonstrationNoticeBackground = Color(0xFFFFF1C7)

/** 演示数据状态条前景色。 */
private val DemonstrationNoticeForeground = Color(0xFF5F4300)

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
    val horizontalScrollState = rememberScrollState()
    val snapshot = chart.snapshot

    AppShell(
        title = "走势",
        mainDestination = MainDestination.TRENDS,
        availableMainDestinations = availableMainDestinations,
        onMainDestinationSelected = onMainDestinationSelected,
        scrollableContent = false,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 24.dp, bottom = 32.dp),
        ) {
            item(key = "configuration") {
                TrendConfiguration(
                    chart = chart,
                    onAction = onAction,
                )
            }
            item(key = "summary") {
                Spacer(Modifier.height(24.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(20.dp))
                TrendSnapshotSummary(snapshot)
                Spacer(Modifier.height(16.dp))
            }
            item(key = "table-header") {
                TrendTableHeader(
                    snapshot = snapshot,
                    horizontalScrollState = horizontalScrollState,
                )
            }
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
                    horizontalScrollState = horizontalScrollState,
                )
            }
            item(key = "statistics") {
                TrendStatisticsTable(
                    lotteryType = snapshot.lotteryType,
                    area = snapshot.area,
                    statistics = snapshot.statistics,
                    horizontalScrollState = horizontalScrollState,
                )
            }
            item(key = "responsible-use") {
                Spacer(Modifier.height(20.dp))
                Text(
                    "历史分布不代表未来规律，不构成购彩建议。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 展示页面标题、数据状态与三组走势筛选控件。 */
@Composable
private fun TrendConfiguration(
    chart: TrendChartState,
    onAction: (TrendChartAction) -> Unit,
) {
    Text("基本走势", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        "开奖号码、遗漏值与样本统计",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (chart.isDemonstration) {
        Spacer(Modifier.height(16.dp))
        DemonstrationNotice()
    }
    Spacer(Modifier.height(24.dp))
    Text("彩种", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(10.dp))
    LotteryTypeTrendSelector(
        selected = chart.lotteryType,
        onSelected = { onAction(TrendChartAction.ChangeLotteryType(it)) },
    )
    Spacer(Modifier.height(20.dp))
    Text("号码区域", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(10.dp))
    TrendAreaSelector(
        lotteryType = chart.lotteryType,
        selected = chart.area,
        onSelected = { onAction(TrendChartAction.ChangeArea(it)) },
    )
    Spacer(Modifier.height(20.dp))
    Text("最近期数", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    TrendSampleSizeSelector(
        selected = chart.sampleSize,
        onSelected = { onAction(TrendChartAction.ChangeSampleSize(it)) },
    )
}

/** 明确标记当前页面不是实时官方数据。 */
@Composable
private fun DemonstrationNotice() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = DemonstrationNoticeBackground,
        contentColor = DemonstrationNoticeForeground,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = LotteryIcons.Info,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "受控演示快照，非实时官方开奖，不参与预测。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** 使用分段控件选择当前走势彩种。 */
@Composable
private fun LotteryTypeTrendSelector(
    selected: LotteryType,
    onSelected: (LotteryType) -> Unit,
) {
    val options = listOf(LotteryType.SUPER_LOTTO, LotteryType.DOUBLE_COLOR_BALL)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
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

/** 使用分段控件选择当前号码区域。 */
@Composable
private fun TrendAreaSelector(
    lotteryType: LotteryType,
    selected: LotteryTrendArea,
    onSelected: (LotteryTrendArea) -> Unit,
) {
    val options = listOf(LotteryTrendArea.PRIMARY, LotteryTrendArea.SECONDARY)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
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

/** 使用单选筛选项选择冻结的最近样本范围。 */
@Composable
private fun TrendSampleSizeSelector(
    selected: TrendSampleSize,
    onSelected: (TrendSampleSize) -> Unit,
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
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

/** 展示实际样本数、起止期号和缺期状态。 */
@Composable
private fun TrendSnapshotSummary(snapshot: LotteryTrendSnapshot) {
    val missingIssueCount = snapshot.issueGaps.sumOf { it.missingCount }
    Text(
        "${snapshot.lotteryType.displayName()} · ${snapshot.area.displayName(snapshot.lotteryType)}",
        style = MaterialTheme.typography.titleLarge,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "${snapshot.actualSampleCount} 期 · ${snapshot.firstIssue.value} 至 ${snapshot.lastIssue.value}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(2.dp))
    Text(
        if (missingIssueCount == 0) "样本内期号连续" else "样本内缺少 $missingIssueCount 期",
        style = MaterialTheme.typography.bodyMedium,
        color =
            if (missingIssueCount == 0) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
    )
}

/** 绘制固定期号列与可同步横向滚动的号码表头。 */
@Composable
private fun TrendTableHeader(
    snapshot: LotteryTrendSnapshot,
    horizontalScrollState: androidx.compose.foundation.ScrollState,
) {
    val areaTint = trendAreaTint(snapshot.lotteryType, snapshot.area)
    FixedLeadingTrendRow(
        height = TREND_HEADER_HEIGHT,
        leadingText = "期号",
        leadingDescription = "期号列",
        leadingBackground = MaterialTheme.colorScheme.surfaceVariant,
        description =
            "期号与${snapshot.statistics.first().number.toTwoDigits()}至" +
                "${snapshot.statistics.last().number.toTwoDigits()}号码列",
        horizontalScrollState = horizontalScrollState,
    ) {
        Row(modifier = Modifier.width(TREND_CELL_WIDTH * snapshot.statistics.size)) {
            snapshot.statistics.forEach { statistic ->
                TrendHeaderCell(
                    number = statistic.number,
                    background = areaTint,
                )
            }
        }
    }
}

/** 绘制一个表头号码格。 */
@Composable
private fun TrendHeaderCell(
    number: Int,
    background: Color,
) {
    Box(
        modifier =
            Modifier
                .width(TREND_CELL_WIDTH)
                .fillMaxHeight()
                .background(background)
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                .clearAndSetSemantics {},
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
    horizontalScrollState: androidx.compose.foundation.ScrollState,
) {
    val hitColor = trendHitColor(lotteryType, area)
    val hitContentColor = trendHitContentColor(lotteryType, area)
    val areaTint = trendAreaTint(lotteryType, area)
    val rowBackground = areaTint.copy(alpha = if (rowIndex % 2 == 0) 0.72f else 0.46f)
    val hitNumbers = row.cells.filter { it.isHit }.map { it.number }
    FixedLeadingTrendRow(
        height = TREND_DRAW_ROW_HEIGHT,
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
) {
    val cellCount = row.cells.size
    val currentHitIndices = row.hitIndices()
    val previousHitIndices = previousRow?.hitIndices().orEmpty()
    val nextHitIndices = nextRow?.hitIndices().orEmpty()
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier =
            Modifier
                .width(TREND_CELL_WIDTH * cellCount)
                .fillMaxHeight()
                .background(background),
    ) {
        Canvas(modifier = Modifier.fillMaxSize().clearAndSetSemantics {}) {
            val cellWidth = TREND_CELL_WIDTH.toPx()
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
                            .width(TREND_CELL_WIDTH)
                            .fillMaxHeight()
                            .clearAndSetSemantics {},
                    contentAlignment = Alignment.Center,
                ) {
                    if (cell.isHit) {
                        Surface(
                            modifier = Modifier.size(HIT_BALL_SIZE),
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
    horizontalScrollState: androidx.compose.foundation.ScrollState,
) {
    val background = trendAreaTint(lotteryType, area)
    StatisticTrendRow(
        visibleLabel = "出现\n次数",
        accessibilityLabel = "出现次数",
        values = statistics.map { it.hitCount },
        background = background,
        horizontalScrollState = horizontalScrollState,
    )
    StatisticTrendRow(
        visibleLabel = "当前\n遗漏",
        accessibilityLabel = "当前遗漏",
        values = statistics.map { it.currentOmission },
        background = background,
        horizontalScrollState = horizontalScrollState,
    )
    StatisticTrendRow(
        visibleLabel = "最大\n遗漏",
        accessibilityLabel = "最大遗漏",
        values = statistics.map { it.maxOmission },
        background = background,
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
    horizontalScrollState: androidx.compose.foundation.ScrollState,
) {
    FixedLeadingTrendRow(
        height = TREND_STATISTIC_ROW_HEIGHT,
        leadingText = visibleLabel,
        leadingDescription = accessibilityLabel,
        leadingBackground = MaterialTheme.colorScheme.surfaceVariant,
        description =
            "$accessibilityLabel，" +
                values.mapIndexed { index, value -> "${(index + 1).toTwoDigits()}号$value" }.joinToString("，"),
        horizontalScrollState = horizontalScrollState,
    ) {
        Row(modifier = Modifier.width(TREND_CELL_WIDTH * values.size)) {
            values.forEach { value ->
                Box(
                    modifier =
                        Modifier
                            .width(TREND_CELL_WIDTH)
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
    leadingText: String,
    leadingDescription: String,
    leadingBackground: Color,
    description: String,
    horizontalScrollState: androidx.compose.foundation.ScrollState,
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
                    .width(ISSUE_COLUMN_WIDTH)
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

/** 返回适合两位数字与最大辅助字号的固定网格文字样式。 */
@Composable
private fun trendGridTextStyle(fontWeight: FontWeight): TextStyle =
    MaterialTheme.typography.labelLarge.copy(
        fontSize = 10.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.sp,
        fontWeight = fontWeight,
    )

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

package roc.win.lottery.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import roc.win.lottery.app.TrendStatisticOrder
import roc.win.lottery.app.drawShapes
import roc.win.lottery.app.orderedBy
import roc.win.lottery.domain.LotteryTrendArea
import roc.win.lottery.domain.LotteryTrendSnapshot

/** 开奖形态逐期比较，固定期号与表头；统计始终基于完整号码区域。 */
@Composable
internal fun TrendShapeTable(
    snapshot: LotteryTrendSnapshot,
    compact: Boolean,
) {
    val shapes = remember(snapshot) { snapshot.drawShapes() }
    val listState = rememberLazyListState()
    val horizontalState = rememberScrollState()
    val color = trendHitColor(snapshot.lotteryType, snapshot.area)
    val metrics =
        listOf("和值", "跨度", "奇:偶", "大:小", "重号") +
            if (snapshot.area == LotteryTrendArea.PRIMARY) listOf("三区比") else emptyList()
    val headers = metrics + "开奖号"
    val numberWidth = if (snapshot.area == LotteryTrendArea.PRIMARY) 154.dp else 64.dp
    LaunchedEffect(snapshot.lotteryType, snapshot.area, snapshot.requestedSampleSize, snapshot.lastIssue) {
        listState.requestScrollToItem(shapes.lastIndex)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val metricWidth = ((maxWidth - 64.dp) / metrics.size).coerceIn(42.dp, 66.dp)
        val widths = metrics.map { metricWidth } + numberWidth
        Column(Modifier.fillMaxSize()) {
            TrendFixedRow(
                issueWidth = 64.dp,
                height = 36.dp,
                horizontalState = horizontalState,
                leading = { Text("期号", style = trendMatrixTextStyle(compact)) },
            ) {
                Row {
                    headers.forEachIndexed { index, label ->
                        TrendInsightCell(label, widths[index], compact, emphasized = true)
                    }
                }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState) {
                itemsIndexed(shapes, key = { _, shape -> shape.row.issue.value }) { index, shape ->
                    val count = shape.numbers.size
                    val values =
                        listOf(
                            shape.sum.toString(),
                            shape.span.toString(),
                            "${shape.oddCount}:${count - shape.oddCount}",
                            "${shape.bigCount}:${count - shape.bigCount}",
                            shape.repeatCount?.toString() ?: "--",
                        ) +
                            (
                                if (snapshot.area ==
                                    LotteryTrendArea.PRIMARY
                                ) {
                                    listOf(shape.zoneCounts.joinToString(":"))
                                } else {
                                    emptyList()
                                }
                            ) +
                            shape.numbers.joinToString(" ") { it.twoDigits() }
                    TrendFixedRow(
                        64.dp,
                        if (compact) 28.dp else 38.dp,
                        horizontalState,
                        background =
                            if (index % 2 ==
                                0
                            ) {
                                MaterialTheme.colorScheme.surface
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
                            },
                        leading = { Text(shape.row.issue.value, style = trendMatrixTextStyle(compact)) },
                    ) {
                        Row(
                            Modifier.semantics {
                                contentDescription =
                                    "第 ${shape.row.issue.value} 期，" +
                                    headers.zip(values).joinToString("，") { "${it.first} ${it.second}" }
                            },
                        ) {
                            values.forEachIndexed { cell, text ->
                                TrendInsightCell(
                                    text,
                                    widths[cell],
                                    compact,
                                    color =
                                        if (cell ==
                                            0 ||
                                            cell == values.lastIndex
                                        ) {
                                            color
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    emphasized =
                                        cell == 0 || cell == values.lastIndex,
                                )
                            }
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().height(40.dp).padding(start = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${snapshot.area.displayName(snapshot.lotteryType)} · ${snapshot.actualSampleCount}期",
                    Modifier.weight(1f),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                TrendToolButton(
                    LotteryIcons.Latest,
                    "定位最新形态",
                ) { scope.launch { listState.animateScrollToItem(shapes.lastIndex) } }
            }
        }
    }
}

/** 稳定尺寸的形态表单元格。 */
@Composable
private fun TrendInsightCell(
    text: String,
    width: Dp,
    compact: Boolean,
    color: Color = MaterialTheme.colorScheme.onSurface,
    emphasized: Boolean = false,
) {
    Box(Modifier.width(width).fillMaxHeight(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style =
                trendMatrixTextStyle(
                    compact,
                ).copy(fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal),
            color = color,
            maxLines = 1,
        )
    }
}

/** 样本号码统计以可排序列表呈现，频次条只是已开奖样本占比，不表示下一期概率。 */
@Composable
internal fun TrendStatisticsList(
    snapshot: LotteryTrendSnapshot,
    compact: Boolean,
    onTrackNumber: (Int) -> Unit,
) {
    var order by rememberSaveable { mutableStateOf(TrendStatisticOrder.NUMBER) }
    val statistics = remember(snapshot.statistics, order) { snapshot.statistics.orderedBy(order) }
    val listState = rememberLazyListState()
    val color = trendHitColor(snapshot.lotteryType, snapshot.area)
    LaunchedEffect(
        order,
        snapshot.lotteryType,
        snapshot.area,
        snapshot.requestedSampleSize,
    ) { listState.requestScrollToItem(0) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            TrendStatisticOrder.entries.forEach { item ->
                TrendTab(
                    when (item) {
                        TrendStatisticOrder.NUMBER -> "号码"
                        TrendStatisticOrder.HITS -> "出现最多"
                        TrendStatisticOrder.OMISSION -> "当前遗漏"
                        TrendStatisticOrder.MAX_OMISSION -> "最大遗漏"
                    },
                    order == item,
                    Modifier.width(
                        if (item ==
                            TrendStatisticOrder.NUMBER
                        ) {
                            64.dp
                        } else {
                            88.dp
                        },
                    ),
                ) { order = item }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(
                    34.dp,
                ).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrendStatisticValue("号码", Modifier.width(54.dp))
            TrendStatisticValue("出现 / ${snapshot.actualSampleCount}期", Modifier.weight(1.3f))
            TrendStatisticValue("当前遗漏", Modifier.weight(1f))
            TrendStatisticValue("最大遗漏", Modifier.weight(1f))
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState) {
            itemsIndexed(statistics, key = { _, item -> item.number }) { index, statistic ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(if (compact) 34.dp else 44.dp)
                        .background(
                            if (index % 2 ==
                                0
                            ) {
                                MaterialTheme.colorScheme.surface
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
                            },
                        ).clickable { onTrackNumber(statistic.number) }
                        .semantics {
                            contentDescription = "追踪 ${statistic.number.twoDigits()}号，出现${statistic.hitCount}次，" +
                                "当前遗漏${statistic.currentOmission}期，最大遗漏${statistic.maxOmission}期"
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TrendStatisticValue(statistic.number.twoDigits(), Modifier.width(54.dp), color)
                    Box(
                        Modifier.weight(1.3f).fillMaxHeight().padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(
                                    3.dp,
                                ).align(Alignment.BottomStart)
                                .padding(bottom = 1.dp)
                                .background(color.copy(alpha = 0.06f)),
                        )
                        Box(
                            Modifier
                                .fillMaxWidth(
                                    statistic.hitCount.toFloat() / snapshot.actualSampleCount,
                                ).height(3.dp)
                                .align(Alignment.BottomStart)
                                .background(color.copy(alpha = 0.6f)),
                        )
                        Text(
                            statistic.hitCount.toString(),
                            style = trendMatrixTextStyle(false),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    TrendStatisticValue(
                        statistic.currentOmission.toString(),
                        Modifier.weight(1f),
                        if (order ==
                            TrendStatisticOrder.OMISSION
                        ) {
                            color
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    TrendStatisticValue(
                        statistic.maxOmission.toString(),
                        Modifier.weight(1f),
                        if (order ==
                            TrendStatisticOrder.MAX_OMISSION
                        ) {
                            color
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}

/** 统计列文本在固定轨道中居中，支持标题换行。 */
@Composable
private fun TrendStatisticValue(
    text: String,
    modifier: Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            text,
            style = trendMatrixTextStyle(false),
            color = color,
            maxLines = 2,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

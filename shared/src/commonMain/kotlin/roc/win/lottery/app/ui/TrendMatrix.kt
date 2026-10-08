package roc.win.lottery.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import roc.win.lottery.app.trendNumberZones
import roc.win.lottery.domain.LotteryTrendSnapshot
import roc.win.lottery.domain.TrendDrawRow

/**
 * 矩阵稳定尺寸；数字保持可读下限，窄屏通过分区或横向浏览展开。
 *
 * @property issueWidth 固定期号列宽度。
 * @property cellWidth 每个号码列宽度。
 * @property rowHeight 每期开奖行高度。
 */
internal data class TrendMatrixLayout(
    val issueWidth: Dp,
    val cellWidth: Dp,
    val rowHeight: Dp,
)

/** 在可完整展开时铺满视口，否则保留可读格宽，不将 35 列压成微小数字。 */
internal fun trendMatrixLayout(
    width: Dp,
    numberCount: Int,
    compact: Boolean,
): TrendMatrixLayout {
    require(numberCount > 0)
    val issueWidth = 64.dp
    val fitted = ((width - issueWidth).coerceAtLeast(0.dp) / numberCount)
    val minimumWidth = if (compact || width >= 600.dp || numberCount <= 16) 20.dp else 26.dp
    val cellWidth = fitted.coerceIn(minimumWidth, 40.dp)
    return TrendMatrixLayout(issueWidth, cellWidth, if (compact) 26.dp else 34.dp)
}

/**
 * 固定号码表头与期号列的双向走势矩阵。
 *
 * @param snapshot 未裁切的完整区域快照。
 * @param listState 外部持有的纵向位置。
 * @param horizontalState 表头与数据行共享的横向位置。
 * @param compact 是否使用紧凑行高与字号。
 * @param showOmissions 是否绘制未开出号码的遗漏数字。
 * @param showLines 是否绘制相邻期同排序位置的连线。
 * @param selectedZone 分区索引，负数表示全区。
 * @param onZoneSelected 分区切换操作。
 * @param selectedNumber 当前追踪号码。
 * @param onNumberSelected 号码追踪切换操作。
 * @param shortLandscape 是否在手机横屏中省略无需分区的导航行。
 */
@Composable
internal fun TrendMatrix(
    snapshot: LotteryTrendSnapshot,
    listState: LazyListState,
    horizontalState: ScrollState,
    compact: Boolean,
    showOmissions: Boolean,
    showLines: Boolean,
    selectedZone: Int,
    onZoneSelected: (Int) -> Unit,
    selectedNumber: Int?,
    onNumberSelected: (Int) -> Unit,
    shortLandscape: Boolean,
) {
    val zones = remember(snapshot.lotteryType, snapshot.area) { trendNumberZones(snapshot.lotteryType, snapshot.area) }
    val numbers =
        zones.getOrNull(selectedZone)?.numbers
            ?: (snapshot.statistics.first().number..snapshot.statistics.last().number)
    val color = trendHitColor(snapshot.lotteryType, snapshot.area)
    val measurer = rememberTextMeasurer(cacheSize = 600)
    val gridStyle = trendMatrixTextStyle(compact)
    val maximumOmission = remember(snapshot) { snapshot.statistics.maxOf { it.maxOmission } }
    val omissionGlyphs =
        remember(measurer, gridStyle, maximumOmission) {
            (0..maximumOmission).associateWith { measurer.measure(it.toString(), gridStyle) }
        }
    val hitGlyphs =
        remember(measurer, gridStyle, snapshot.statistics.size) {
            snapshot.statistics.associate {
                it.number to
                    measurer.measure(it.number.twoDigits(), gridStyle.copy(fontWeight = FontWeight.Bold))
            }
        }
    val gapEnds = remember(snapshot.issueGaps) { snapshot.issueGaps.map { it.nextIssue }.toSet() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val availableWidth = maxWidth
        val layout = trendMatrixLayout(availableWidth, numbers.count(), compact)
        Column(
            Modifier.fillMaxSize().semantics {
                contentDescription =
                    "号码走势矩阵，${numbers.first.twoDigits()}至${numbers.last.twoDigits()}"
            },
        ) {
            if (zones.size > 1 &&
                (
                    !shortLandscape || selectedZone >= 0 ||
                        layout.cellWidth * numbers.count() > availableWidth - layout.issueWidth
                )
            ) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TrendRangeTab("全区", selectedZone < 0, Modifier.width(layout.issueWidth)) { onZoneSelected(-1) }
                    zones.forEachIndexed { index, zone ->
                        TrendRangeTab(
                            "${zone.label} ${zone.numbers.first.twoDigits()}-${zone.numbers.last.twoDigits()}",
                            selectedZone == index,
                        ) { onZoneSelected(index) }
                    }
                }
            }
            TrendFixedRow(layout.issueWidth, 36.dp, horizontalState, leading = {
                Text("期号", style = gridStyle.copy(fontWeight = FontWeight.SemiBold))
            }) {
                Row(Modifier.width(layout.cellWidth * numbers.count()).fillMaxHeight()) {
                    numbers.forEach { number ->
                        val isSelected = number == selectedNumber
                        Box(
                            Modifier
                                .width(layout.cellWidth)
                                .fillMaxHeight()
                                .background(
                                    if (isSelected) {
                                        color
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(
                                            alpha = 0.6f,
                                        )
                                    },
                                ).clickable { onNumberSelected(number) }
                                .semantics {
                                    contentDescription = "追踪号码 ${number.twoDigits()}"
                                    selected = isSelected
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                number.twoDigits(),
                                style = gridStyle.copy(fontWeight = FontWeight.Bold),
                                color = if (isSelected) Color.White else color,
                            )
                        }
                    }
                }
            }
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f).semantics { contentDescription = "开奖走势数据" },
                state = listState,
            ) {
                itemsIndexed(snapshot.rows, key = { _, row -> row.issue.value }) { index, row ->
                    val isLatest = index == snapshot.rows.lastIndex
                    val background =
                        if (isLatest) {
                            color.copy(alpha = 0.08f)
                        } else if (index % 2 ==
                            0
                        ) {
                            MaterialTheme.colorScheme.surface
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
                        }
                    TrendFixedRow(layout.issueWidth, layout.rowHeight, horizontalState, background, leading = {
                        Text(
                            row.issue.value,
                            style = gridStyle.copy(fontWeight = if (isLatest) FontWeight.Bold else FontWeight.Medium),
                            color = if (isLatest) color else MaterialTheme.colorScheme.onSurface,
                        )
                    }) {
                        val hits = row.cells.filter { it.isHit }.map { it.number }
                        val tracked = row.cells.firstOrNull { it.number == selectedNumber }
                        Box(
                            Modifier.width(layout.cellWidth * numbers.count()).fillMaxHeight().semantics {
                                contentDescription =
                                    "第 ${row.issue.value} 期，命中 ${hits.joinToString("、") { it.twoDigits() }}" +
                                    (
                                        tracked?.let {
                                            "，${it.number.twoDigits()}号${if (it.isHit) "开出" else "遗漏${it.omission}期"}"
                                        }
                                            ?: ""
                                    )
                            },
                        ) {
                            TrendDrawCanvas(
                                row,
                                snapshot.rows.getOrNull(index - 1)?.takeUnless { row.issue in gapEnds },
                                snapshot.rows.getOrNull(index + 1)?.takeUnless { it.issue in gapEnds },
                                numbers,
                                layout,
                                color,
                                selectedNumber,
                                showOmissions,
                                showLines,
                                omissionGlyphs,
                                hitGlyphs,
                                zones.dropLast(1).map { it.numbers.last },
                            )
                        }
                    }
                }
            }
            if (layout.cellWidth * numbers.count() > availableWidth - layout.issueWidth) {
                val trackColor = MaterialTheme.colorScheme.outlineVariant
                Canvas(Modifier.fillMaxWidth().height(3.dp).padding(start = layout.issueWidth)) {
                    drawRect(trackColor.copy(alpha = 0.4f))
                    val total = horizontalState.maxValue + size.width
                    val thumb = size.width * size.width / total
                    val start = horizontalState.value / total * size.width
                    drawRect(color.copy(alpha = 0.65f), Offset(start, 0f), Size(thumb, size.height))
                }
            }
        }
    }
}

/** 分区导航保留具体号码边界，不依赖用户记忆一区、二区定义。 */
@Composable
private fun TrendRangeTab(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(
                36.dp,
            ).clickable(
                onClick = onClick,
            ).background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent,
            ).padding(horizontal = 9.dp)
            .semantics {
                this.selected =
                    selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** 表头与所有数据行共用一个横向位置，左侧标签始终固定。 */
@Composable
internal fun TrendFixedRow(
    issueWidth: Dp,
    height: Dp,
    horizontalState: ScrollState,
    background: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    leading: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(height).background(background)) {
        Box(Modifier.width(issueWidth).fillMaxHeight(), contentAlignment = Alignment.Center) { leading() }
        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(horizontalState)) { content() }
    }
}

/**
 * 每行一次画布绘制，字形复用，避免 500 期样本创建大量号码文本节点。
 * 连线在行边界使用两期横坐标的中点，保证两半线段连续。
 */
@Composable
private fun TrendDrawCanvas(
    row: TrendDrawRow,
    previous: TrendDrawRow?,
    next: TrendDrawRow?,
    numbers: IntRange,
    layout: TrendMatrixLayout,
    hitColor: Color,
    selectedNumber: Int?,
    omissions: Boolean,
    lines: Boolean,
    omissionGlyphs: Map<Int, TextLayoutResult>,
    hitGlyphs: Map<Int, TextLayoutResult>,
    zoneEnds: List<Int>,
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val omissionColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.66f)
    Canvas(Modifier.fillMaxSize().clipToBounds()) {
        val width = layout.cellWidth.toPx()

        fun center(number: Int): Float = (number - numbers.first + 0.5f) * width
        if (selectedNumber != null && selectedNumber in numbers) {
            drawRect(
                hitColor.copy(alpha = 0.10f),
                Offset((selectedNumber - numbers.first) * width, 0f),
                Size(width, size.height),
            )
        }
        for (index in 0..numbers.count()) {
            drawLine(gridColor, Offset(index * width, 0f), Offset(index * width, size.height), 0.5.dp.toPx())
        }
        drawLine(gridColor, Offset(0f, size.height), Offset(size.width, size.height), 0.5.dp.toPx())
        zoneEnds.filter { it in numbers }.forEach { number ->
            val x = (number - numbers.first + 1) * width
            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx())
        }
        val currentHits = row.cells.filter { it.isHit }.map { it.number }
        if (lines) {
            previous?.cells?.filter { it.isHit }?.map { it.number }?.zip(currentHits)?.forEach { (before, now) ->
                drawLine(
                    hitColor.copy(alpha = 0.30f),
                    Offset((center(before) + center(now)) / 2f, 0f),
                    Offset(
                        center(now),
                        size.height / 2f,
                    ),
                    1.2.dp.toPx(),
                )
            }
            currentHits
                .zip(
                    next
                        ?.cells
                        ?.filter { it.isHit }
                        ?.map { it.number }
                        .orEmpty(),
                ).forEach { (now, after) ->
                    drawLine(
                        hitColor.copy(alpha = 0.30f),
                        Offset(center(now), size.height / 2f),
                        Offset((center(now) + center(after)) / 2f, size.height),
                        1.2.dp.toPx(),
                    )
                }
        }
        row.cells.filter { it.number in numbers }.forEach { cell ->
            val x = center(cell.number)
            if (cell.isHit) {
                drawCircle(
                    hitColor,
                    minOf(width - 3.dp.toPx(), size.height - 8.dp.toPx()) / 2f,
                    Offset(
                        x,
                        size.height / 2f,
                    ),
                )
                drawCenteredGlyph(checkNotNull(hitGlyphs[cell.number]), x, Color.White, width - 3.dp.toPx())
            } else if (omissions) {
                drawCenteredGlyph(
                    checkNotNull(omissionGlyphs[cell.omission]),
                    x,
                    if (cell.number ==
                        selectedNumber
                    ) {
                        hitColor
                    } else {
                        omissionColor
                    },
                    width - 2.dp.toPx(),
                )
            }
        }
    }
}

/** 以固定格中心绘制预先测量的文字，三位遗漏值也不会被裁切。 */
private fun DrawScope.drawCenteredGlyph(
    glyph: TextLayoutResult,
    x: Float,
    color: Color,
    maximumWidth: Float,
) {
    val fit = minOf(1f, maximumWidth / glyph.size.width)
    withTransform({ scale(fit, fit, pivot = Offset(x, size.height / 2f)) }) {
        drawText(glyph, color, Offset(x - glyph.size.width / 2f, (size.height - glyph.size.height) / 2f))
    }
}

/** 矩阵是固定格式数据图，以显式密度档位调整字号，不随视口缩到不可读。 */
@Composable
internal fun trendMatrixTextStyle(compact: Boolean): TextStyle {
    val scale = LocalDensity.current.fontScale
    return TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = (if (compact) 10.sp else 12.sp) / scale,
        lineHeight =
            16.sp / scale,
        letterSpacing = 0.sp,
    )
}

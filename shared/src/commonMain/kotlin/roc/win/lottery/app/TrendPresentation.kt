package roc.win.lottery.app

import roc.win.lottery.domain.LotteryTrendArea
import roc.win.lottery.domain.LotteryTrendSnapshot
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.TrendDrawRow
import roc.win.lottery.domain.TrendNumberStatistics
import roc.win.lottery.domain.trendAreaSpec

/**
 * 走势表中可单独展开的连续号码分区。
 *
 * @property label 分区名称。
 * @property numbers 分区内完整号码范围。
 */
internal data class TrendNumberZone(
    val label: String,
    val numbers: IntRange,
)

/** 返回与原有走势表一致的三区边界；次号码区域不再细分。 */
internal fun trendNumberZones(
    lotteryType: LotteryType,
    area: LotteryTrendArea,
): List<TrendNumberZone> {
    val range = lotteryType.trendAreaSpec(area).numberRange
    if (area == LotteryTrendArea.SECONDARY) return listOf(TrendNumberZone("全区", range))
    return if (lotteryType == LotteryType.SUPER_LOTTO) {
        listOf(TrendNumberZone("一区", 1..12), TrendNumberZone("二区", 13..24), TrendNumberZone("三区", 25..35))
    } else {
        listOf(TrendNumberZone("一区", 1..11), TrendNumberZone("二区", 12..22), TrendNumberZone("三区", 23..33))
    }
}

/**
 * 从完整号码区域计算的当期开奖形态，不受界面分区裁切影响。
 *
 * @property row 原始开奖行。
 * @property numbers 本区域按升序排列的开奖号。
 * @property sum 和值。
 * @property span 最大号与最小号之差。
 * @property oddCount 奇数个数。
 * @property bigCount 大号个数，分界取区域最大号码的一半向下取整。
 * @property zoneCounts 三区内的号码个数。
 * @property repeatCount 与完整前一期重复的号码数；样本首期或缺期时未知。
 */
internal data class TrendDrawShape(
    val row: TrendDrawRow,
    val numbers: List<Int>,
    val sum: Int,
    val span: Int,
    val oddCount: Int,
    val bigCount: Int,
    val zoneCounts: List<Int>,
    val repeatCount: Int?,
)

/** 计算逐期形态；有已知缺期时不把相邻记录误认为连续开奖。 */
internal fun LotteryTrendSnapshot.drawShapes(): List<TrendDrawShape> {
    val zones = trendNumberZones(lotteryType, area)
    val smallUpperBound = lotteryType.trendAreaSpec(area).numberRange.last / 2
    val gapEnds = issueGaps.map { it.nextIssue }.toSet()
    return rows.mapIndexed { index, row ->
        val numbers = row.cells.filter { it.isHit }.map { it.number }
        val previousNumbers =
            rows
                .getOrNull(index - 1)
                ?.cells
                ?.filter { it.isHit }
                ?.map { it.number }
        TrendDrawShape(
            row = row,
            numbers = numbers,
            sum = numbers.sum(),
            span = numbers.last() - numbers.first(),
            oddCount = numbers.count { it % 2 != 0 },
            bigCount = numbers.count { it > smallUpperBound },
            zoneCounts = zones.map { zone -> numbers.count { it in zone.numbers } },
            repeatCount =
                previousNumbers?.takeUnless { row.issue in gapEnds }?.let { previous ->
                    numbers.count {
                        it in
                            previous
                    }
                },
        )
    }
}

/** 号码统计表的稳定排序方式。 */
internal enum class TrendStatisticOrder {
    /** 按号码升序。 */
    NUMBER,

    /** 按样本内出现次数降序。 */
    HITS,

    /** 按当前遗漏降序。 */
    OMISSION,

    /** 按样本内最大遗漏降序。 */
    MAX_OMISSION,
}

/** 同一统计值始终以号码升序打破并列，避免切换时位置不稳定。 */
internal fun List<TrendNumberStatistics>.orderedBy(order: TrendStatisticOrder): List<TrendNumberStatistics> =
    when (order) {
        TrendStatisticOrder.NUMBER -> {
            sortedBy { it.number }
        }

        TrendStatisticOrder.HITS -> {
            sortedWith(
                compareByDescending<TrendNumberStatistics> { it.hitCount }.thenBy { it.number },
            )
        }

        TrendStatisticOrder.OMISSION -> {
            sortedWith(
                compareByDescending<TrendNumberStatistics> {
                    it.currentOmission
                }.thenBy { it.number },
            )
        }

        TrendStatisticOrder.MAX_OMISSION -> {
            sortedWith(
                compareByDescending<TrendNumberStatistics> {
                    it.maxOmission
                }.thenBy { it.number },
            )
        }
    }

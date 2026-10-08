package roc.win.lottery.app

import androidx.compose.ui.unit.dp
import roc.win.lottery.app.ui.trendMatrixLayout
import roc.win.lottery.domain.HistoricalDraw
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryTrendArea
import roc.win.lottery.domain.LotteryTrendCalculationResult
import roc.win.lottery.domain.LotteryTrendCalculator
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.TrendNumberStatistics
import roc.win.lottery.domain.TrendSampleSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 展示计算与移动表格尺寸的确定性回归。 */
class TrendPresentationTest {
    /** 双色球三区与大小边界不能沿用大乐透规则。 */
    @Test
    fun doubleColorBallShapeUsesItsOwnBoundaries() {
        val type = LotteryType.DOUBLE_COLOR_BALL
        val draws =
            listOf(
                HistoricalDraw(type, Issue("2026101"), "2026-08-30", listOf(1, 11, 16, 17, 22, 33), listOf(8)),
                HistoricalDraw(type, Issue("2026102"), "2026-09-01", listOf(1, 12, 17, 23, 24, 33), listOf(9)),
            )
        val snapshot =
            (
                LotteryTrendCalculator().calculate(
                    type,
                    LotteryTrendArea.PRIMARY,
                    TrendSampleSize.LAST_50,
                    draws,
                ) as LotteryTrendCalculationResult.Success
            ).snapshot
        val shapes = snapshot.drawShapes()
        assertEquals(100, shapes.first().sum)
        assertEquals(32, shapes.first().span)
        assertEquals(4, shapes.first().oddCount)
        assertEquals(3, shapes.first().bigCount)
        assertEquals(listOf(2, 3, 1), shapes.first().zoneCounts)
        assertNull(shapes.first().repeatCount)
        assertEquals(3, shapes.last().repeatCount)
        assertEquals(listOf(1, 2, 3), shapes.last().zoneCounts)
    }

    /** 缺期时重号未知；次区域也应使用自身的大小边界和跨度。 */
    @Test
    fun gapDoesNotInventPreviousDrawRepeats() {
        val type = LotteryType.SUPER_LOTTO
        val draws =
            listOf(
                HistoricalDraw(type, Issue("26101"), "2026-08-30", listOf(1, 12, 17, 18, 35), listOf(6, 7)),
                HistoricalDraw(type, Issue("26103"), "2026-09-02", listOf(1, 12, 17, 18, 35), listOf(6, 7)),
            )
        val primary =
            (
                LotteryTrendCalculator().calculate(
                    type,
                    LotteryTrendArea.PRIMARY,
                    TrendSampleSize.LAST_50,
                    draws,
                ) as LotteryTrendCalculationResult.Success
            ).snapshot.drawShapes()
        assertEquals(2, primary.last().bigCount)
        assertEquals(listOf(2, 2, 1), primary.last().zoneCounts)
        assertNull(primary.last().repeatCount)
        val secondary =
            (
                LotteryTrendCalculator().calculate(
                    type,
                    LotteryTrendArea.SECONDARY,
                    TrendSampleSize.LAST_50,
                    draws,
                ) as LotteryTrendCalculationResult.Success
            ).snapshot.drawShapes()
        assertEquals(13, secondary.last().sum)
        assertEquals(1, secondary.last().span)
        assertEquals(1, secondary.last().bigCount)
        assertEquals(listOf(2), secondary.last().zoneCounts)
        assertNull(secondary.last().repeatCount)
    }

    /** 并列频次或遗漏按号码稳定排序，不产生隐式推荐顺序。 */
    @Test
    fun statisticsSortHasStableTies() {
        val values =
            listOf(
                TrendNumberStatistics(3, 10, 3, 8),
                TrendNumberStatistics(2, 10, 8, 12),
                TrendNumberStatistics(1, 2, 8, 20),
            )
        assertEquals(listOf(1, 2, 3), values.orderedBy(TrendStatisticOrder.NUMBER).map { it.number })
        assertEquals(listOf(2, 3, 1), values.orderedBy(TrendStatisticOrder.HITS).map { it.number })
        assertEquals(listOf(1, 2, 3), values.orderedBy(TrendStatisticOrder.OMISSION).map { it.number })
        assertEquals(listOf(1, 2, 3), values.orderedBy(TrendStatisticOrder.MAX_OMISSION).map { it.number })
    }

    /** 手机横屏能完整容纳 35 列，竖屏仍保持可读格宽，分区不会超出视口。 */
    @Test
    fun matrixBalancesFullWidthAndReadableCells() {
        val landscape = trendMatrixLayout(844.dp, 35, compact = false)
        assertTrue(landscape.issueWidth + landscape.cellWidth * 35 <= 844.01.dp)
        val portrait = trendMatrixLayout(390.dp, 35, compact = false)
        assertEquals(26.dp, portrait.cellWidth)
        val zone = trendMatrixLayout(390.dp, 12, compact = false)
        assertTrue(zone.issueWidth + zone.cellWidth * 12 <= 390.01.dp)
        assertEquals(34.dp, zone.rowHeight)
        val blueArea = trendMatrixLayout(390.dp, 16, compact = false)
        assertTrue(blueArea.issueWidth + blueArea.cellWidth * 16 <= 390.01.dp)
        val narrowZone = trendMatrixLayout(320.dp, 12, compact = false)
        assertTrue(narrowZone.issueWidth + narrowZone.cellWidth * 12 <= 320.01.dp)
        assertEquals(26.dp, trendMatrixLayout(390.dp, 12, compact = true).rowHeight)
    }
}

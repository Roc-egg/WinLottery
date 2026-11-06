package roc.win.lottery.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 单式与复式注数规则测试。 */
class BetCombinationsTest {
    /** 组合数应精确，越界参数返回零。 */
    @Test
    fun combinationsAreExact() {
        assertEquals(1L, BetCombinations.combinations(5, 5))
        assertEquals(21L, BetCombinations.combinations(7, 5))
        assertEquals(28L, BetCombinations.combinations(8, 6))
        assertEquals(324_632L, BetCombinations.combinations(35, 5))
        assertEquals(1_107_568L, BetCombinations.combinations(33, 6))
        assertEquals(1L, BetCombinations.combinations(3, 0))
        assertEquals(0L, BetCombinations.combinations(3, 4))
        assertEquals(0L, BetCombinations.combinations(3, -1))
        assertEquals(0L, BetCombinations.combinations(-1, 0))
    }

    /** 与真实票样同结构的复式：大乐透 7+3 为 63 注，双色球 8+2 为 56 注。 */
    @Test
    fun realCompoundTicketsExpandToExpectedBetCount() {
        assertEquals(63L, BetCombinations.betCount(LotteryType.SUPER_LOTTO, 7, 3))
        assertEquals(56L, BetCombinations.betCount(LotteryType.DOUBLE_COLOR_BALL, 8, 2))
    }

    /** 单式为一注，任一区号码不足单式要求时没有可投注组合。 */
    @Test
    fun singleAndIncompleteLines() {
        assertEquals(1L, BetCombinations.betCount(LotteryType.SUPER_LOTTO, 5, 2))
        assertEquals(1L, BetCombinations.betCount(LotteryType.DOUBLE_COLOR_BALL, 6, 1))
        assertEquals(0L, BetCombinations.betCount(LotteryType.SUPER_LOTTO, 4, 3))
        assertEquals(0L, BetCombinations.betCount(LotteryType.DOUBLE_COLOR_BALL, 7, 0))
    }

    /** 任一区多于单式数量才是复式。 */
    @Test
    fun compoundDetection() {
        assertFalse(line(listOf(1, 2, 3, 4, 5), listOf(1, 2)).isCompound(LotteryType.SUPER_LOTTO))
        assertTrue(line(listOf(1, 2, 3, 4, 5), listOf(1, 2, 3)).isCompound(LotteryType.SUPER_LOTTO))
        assertTrue(line(listOf(1, 2, 3, 4, 5, 6, 7), listOf(1)).isCompound(LotteryType.DOUBLE_COLOR_BALL))
        assertEquals(3L, line(listOf(1, 2, 3, 4, 5), listOf(1, 2, 3)).betCount(LotteryType.SUPER_LOTTO))
    }

    /** 创建一行人工确认投注。 */
    private fun line(
        primary: List<Int>,
        secondary: List<Int>,
    ): BetLine =
        BetLine(
            primaryNumbers = ConfirmedValue(primary, TicketFieldOrigin.USER),
            secondaryNumbers = ConfirmedValue(secondary, TicketFieldOrigin.USER),
            isAdditional = ConfirmedValue(false, TicketFieldOrigin.USER),
            originalText = "测试投注行",
        )
}

package roc.win.lottery.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 单式与复式票金额计算测试。 */
class TicketAmountCalculatorTest {
    /** 大乐透追加、多注和倍数必须同时进入理论金额。 */
    @Test
    fun calculatesSuperLottoAdditionalAmount() {
        val amount =
            TicketAmountCalculator.calculate(
                lotteryType = LotteryType.SUPER_LOTTO,
                betCount = 2,
                additionalBetCount = 2,
                multiplier = 3,
                periodCount = 1,
            )

        assertEquals(1_800L, amount)
    }

    /** 双色球基本投注不应包含追加金额。 */
    @Test
    fun calculatesDoubleColorBallAmount() {
        val amount =
            TicketAmountCalculator.calculate(
                lotteryType = LotteryType.DOUBLE_COLOR_BALL,
                betCount = 2,
                additionalBetCount = 0,
                multiplier = 3,
                periodCount = 1,
            )

        assertEquals(1_200L, amount)
    }

    /** 复式票按展开后的单式注数计价，追加按同样注数加价。 */
    @Test
    fun calculatesCompoundAmountFromExpandedBetCount() {
        val superLottoBetCount = BetCombinations.betCount(LotteryType.SUPER_LOTTO, 7, 3)
        val doubleColorBallBetCount = BetCombinations.betCount(LotteryType.DOUBLE_COLOR_BALL, 8, 2)

        assertEquals(
            12_600L,
            TicketAmountCalculator.calculate(
                lotteryType = LotteryType.SUPER_LOTTO,
                betCount = superLottoBetCount,
                additionalBetCount = 0,
                multiplier = 1,
                periodCount = 1,
            ),
        )
        assertEquals(
            18_900L,
            TicketAmountCalculator.calculate(
                lotteryType = LotteryType.SUPER_LOTTO,
                betCount = superLottoBetCount,
                additionalBetCount = superLottoBetCount,
                multiplier = 1,
                periodCount = 1,
            ),
        )
        assertEquals(
            11_200L,
            TicketAmountCalculator.calculate(
                lotteryType = LotteryType.DOUBLE_COLOR_BALL,
                betCount = doubleColorBallBetCount,
                additionalBetCount = 0,
                multiplier = 1,
                periodCount = 1,
            ),
        )
    }

    /** 双色球追加和非法计数必须拒绝，不得静默计算。 */
    @Test
    fun rejectsInvalidAmountInputs() {
        assertNull(
            TicketAmountCalculator.calculate(
                lotteryType = LotteryType.DOUBLE_COLOR_BALL,
                betCount = 1,
                additionalBetCount = 1,
                multiplier = 1,
                periodCount = 1,
            ),
        )
        assertNull(
            TicketAmountCalculator.calculate(
                lotteryType = LotteryType.SUPER_LOTTO,
                betCount = 1,
                additionalBetCount = 2,
                multiplier = 1,
                periodCount = 1,
            ),
        )
    }
}

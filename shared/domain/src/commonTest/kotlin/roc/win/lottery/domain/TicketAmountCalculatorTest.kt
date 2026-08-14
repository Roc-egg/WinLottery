package roc.win.lottery.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** V1 单式票金额计算测试。 */
class TicketAmountCalculatorTest {
    /** 大乐透追加、多注和倍数必须同时进入理论金额。 */
    @Test
    fun calculatesSuperLottoAdditionalAmount() {
        val amount =
            TicketAmountCalculator.calculate(
                lotteryType = LotteryType.SUPER_LOTTO,
                betLineCount = 2,
                additionalLineCount = 2,
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
                betLineCount = 2,
                additionalLineCount = 0,
                multiplier = 3,
                periodCount = 1,
            )

        assertEquals(1_200L, amount)
    }

    /** 双色球追加和非法计数必须拒绝，不得静默计算。 */
    @Test
    fun rejectsInvalidAmountInputs() {
        assertNull(
            TicketAmountCalculator.calculate(
                lotteryType = LotteryType.DOUBLE_COLOR_BALL,
                betLineCount = 1,
                additionalLineCount = 1,
                multiplier = 1,
                periodCount = 1,
            ),
        )
        assertNull(
            TicketAmountCalculator.calculate(
                lotteryType = LotteryType.SUPER_LOTTO,
                betLineCount = 1,
                additionalLineCount = 2,
                multiplier = 1,
                periodCount = 1,
            ),
        )
    }
}

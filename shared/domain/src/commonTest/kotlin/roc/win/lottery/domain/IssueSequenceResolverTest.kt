package roc.win.lottery.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** 多期期号安全展开测试。 */
class IssueSequenceResolverTest {
    /** 被测期号展开器。 */
    private val resolver = IssueSequenceResolver()

    /** 大乐透十期票应从票面起始期号连续展开并保留五位格式。 */
    @Test
    fun superLottoTenPeriodsExpandInOrder() {
        val result = resolver.resolve(LotteryType.SUPER_LOTTO, Issue("26090"), 10)

        val issues = assertIs<IssueSequenceResult.Success>(result).issues.map { it.value }
        assertEquals((90..99).map { "26${it.toString().padStart(3, '0')}" }, issues)
    }

    /** 双色球多期票应保留四位年份前缀和三位期次序号。 */
    @Test
    fun doubleColorBallPeriodsKeepSevenDigitFormat() {
        val result = resolver.resolve(LotteryType.DOUBLE_COLOR_BALL, Issue("2026091"), 3)

        assertEquals(
            listOf("2026091", "2026092", "2026093"),
            assertIs<IssueSequenceResult.Success>(result).issues.map { it.value },
        )
    }

    /** 靠近年度换期边界的多期票必须等待官方期次日历能力。 */
    @Test
    fun unverifiedYearBoundaryIsRejected() {
        val result = resolver.resolve(LotteryType.SUPER_LOTTO, Issue("26119"), 3)

        assertIs<IssueSequenceResult.Unsupported>(result)
    }

    /** 超过移动首版上限的多期票不得触发批量官网查询。 */
    @Test
    fun tooManyPeriodsAreRejected() {
        val result = resolver.resolve(LotteryType.SUPER_LOTTO, Issue("26090"), 21)

        assertIs<IssueSequenceResult.Unsupported>(result)
    }
}

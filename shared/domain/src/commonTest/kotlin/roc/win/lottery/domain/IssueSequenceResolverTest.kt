package roc.win.lottery.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** 多期期号展开与跨年度测试。 */
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

    /** 年度后段的多期票不再受固定序号上限限制，未开奖期次按本年度顺延。 */
    @Test
    fun lateYearPeriodsAreNotCappedAtFixedOrdinal() {
        val result = resolver.resolve(LotteryType.SUPER_LOTTO, Issue("26115"), 10)

        assertEquals(
            (115..124).map { "26$it" },
            assertIs<IssueSequenceResult.Success>(result).issues.map { it.value },
        )
    }

    /** 官网已开奖期号跨年度时按官网实际期号展开，剩余期次从新年度继续顺延。 */
    @Test
    fun officialDrawnIssuesCrossYearBoundary() {
        val result =
            resolver.resolve(
                lotteryType = LotteryType.DOUBLE_COLOR_BALL,
                firstIssue = Issue("2025150"),
                periodCount = 5,
                drawnIssues = listOf(Issue("2025150"), Issue("2025151"), Issue("2026001")),
            )

        assertEquals(
            listOf("2025150", "2025151", "2026001", "2026002", "2026003"),
            assertIs<IssueSequenceResult.Success>(result).issues.map { it.value },
        )
    }

    /** 官网期号列表不是从起始期号开始时不得采用，回退为本年度顺延。 */
    @Test
    fun drawnIssuesNotStartingAtFirstIssueAreIgnored() {
        val result =
            resolver.resolve(
                lotteryType = LotteryType.SUPER_LOTTO,
                firstIssue = Issue("26150"),
                periodCount = 3,
                drawnIssues = listOf(Issue("27001")),
            )

        assertEquals(
            listOf("26150", "26151", "26152"),
            assertIs<IssueSequenceResult.Success>(result).issues.map { it.value },
        )
    }

    /** 超过移动首版上限的多期票不得触发批量官网查询。 */
    @Test
    fun tooManyPeriodsAreRejected() {
        val result = resolver.resolve(LotteryType.SUPER_LOTTO, Issue("26090"), 21)

        assertIs<IssueSequenceResult.Unsupported>(result)
    }
}

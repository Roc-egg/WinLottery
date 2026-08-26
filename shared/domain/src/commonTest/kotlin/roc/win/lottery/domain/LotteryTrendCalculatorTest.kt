package roc.win.lottery.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** V1.3 历史开奖基本走势领域契约测试。 */
class LotteryTrendCalculatorTest {
    /** 已知样本必须生成正确的命中格、遗漏值和号码统计。 */
    @Test
    fun calculatesHitsAndOmissionsFromSelectedSample() {
        val snapshot =
            success(
                listOf(
                    superLottoDraw("26001", primary = listOf(1, 2, 3, 4, 5)),
                    superLottoDraw("26002", primary = listOf(2, 3, 4, 5, 6)),
                    superLottoDraw("26003", primary = listOf(1, 3, 4, 5, 7)),
                ),
            )

        assertEquals(listOf("26001", "26002", "26003"), snapshot.rows.map { it.issue.value })
        assertEquals(listOf(true, false, true), snapshot.rows.map { it.cells[0].isHit })
        assertEquals(listOf(0, 1, 0), snapshot.rows.map { it.cells[0].omission })
        assertEquals(
            TrendNumberStatistics(number = 1, hitCount = 2, currentOmission = 0, maxOmission = 1),
            snapshot.statistics[0],
        )
        assertEquals(
            TrendNumberStatistics(number = 35, hitCount = 0, currentOmission = 3, maxOmission = 3),
            snapshot.statistics.last(),
        )
    }

    /** 乱序输入必须先按期号排序，并只保留用户选择的最近期数。 */
    @Test
    fun sortsDrawsAndKeepsOnlyLatestRequestedRange() {
        val draws =
            (1..12)
                .map { ordinal -> superLottoDraw("26${ordinal.toString().padStart(3, '0')}") }
                .reversed()

        val snapshot = success(draws)

        assertEquals(10, snapshot.actualSampleCount)
        assertEquals("26003", snapshot.firstIssue.value)
        assertEquals("26012", snapshot.lastIssue.value)
    }

    /** 次号码区域必须使用对应彩种的独立范围与遗漏统计。 */
    @Test
    fun calculatesDoubleColorBallSecondaryArea() {
        val result =
            calculator.calculate(
                lotteryType = LotteryType.DOUBLE_COLOR_BALL,
                area = LotteryTrendArea.SECONDARY,
                sampleSize = TrendSampleSize.LAST_10,
                draws =
                    listOf(
                        doubleColorBallDraw("2026001", secondary = listOf(1)),
                        doubleColorBallDraw("2026002", secondary = listOf(2)),
                        doubleColorBallDraw("2026003", secondary = listOf(1)),
                    ),
            )
        val snapshot = assertIs<LotteryTrendCalculationResult.Success>(result).snapshot

        assertEquals(16, snapshot.statistics.size)
        assertEquals(2, snapshot.statistics.first().hitCount)
        assertEquals(0, snapshot.statistics.first().currentOmission)
        assertEquals(1, snapshot.statistics[1].currentOmission)
    }

    /** 同年度期号空档必须显式报告，跨年度不得凭空推断缺期。 */
    @Test
    fun reportsOnlyProvableSameYearIssueGaps() {
        val sameYear =
            success(
                listOf(
                    superLottoDraw("26001"),
                    superLottoDraw("26004"),
                ),
            )
        val crossYear =
            success(
                listOf(
                    superLottoDraw("26999"),
                    superLottoDraw("27001"),
                ),
            )

        assertEquals(
            TrendIssueGap(Issue("26001"), Issue("26004"), missingCount = 2),
            sameYear.issueGaps.single(),
        )
        assertTrue(crossYear.issueGaps.isEmpty())
    }

    /** 主次号码越界、重复或未升序时必须失败关闭。 */
    @Test
    fun rejectsInvalidNumberAreas() {
        val result =
            calculator.calculate(
                lotteryType = LotteryType.SUPER_LOTTO,
                area = LotteryTrendArea.PRIMARY,
                sampleSize = TrendSampleSize.LAST_10,
                draws =
                    listOf(
                        superLottoDraw(
                            issue = "26001",
                            primary = listOf(1, 2, 2, 4, 36),
                            secondary = listOf(2, 1),
                        ),
                    ),
            )
        val codes =
            assertIs<LotteryTrendCalculationResult.InvalidData>(result).problems.map { it.code }

        assertEquals(
            listOf(
                TrendDataProblemCode.INVALID_PRIMARY_NUMBERS,
                TrendDataProblemCode.INVALID_SECONDARY_NUMBERS,
            ),
            codes,
        )
    }

    /** 输入混入其他彩种时不得忽略或转换。 */
    @Test
    fun rejectsMixedLotteryTypes() {
        val result =
            calculator.calculate(
                lotteryType = LotteryType.SUPER_LOTTO,
                area = LotteryTrendArea.PRIMARY,
                sampleSize = TrendSampleSize.LAST_10,
                draws = listOf(superLottoDraw("26001"), doubleColorBallDraw("2026002")),
            )

        assertEquals(
            TrendDataProblemCode.MIXED_LOTTERY,
            assertIs<LotteryTrendCalculationResult.InvalidData>(result).problems.single().code,
        )
    }

    /** 完全相同的重复记录与同一期内容冲突必须使用不同稳定编码。 */
    @Test
    fun distinguishesDuplicateAndConflictingIssues() {
        val draw = superLottoDraw("26001")
        val duplicate = calculateInvalid(listOf(draw, draw))
        val conflicting =
            calculateInvalid(
                listOf(draw, superLottoDraw("26001", primary = listOf(2, 3, 4, 5, 6))),
            )

        assertEquals(TrendDataProblemCode.DUPLICATE_ISSUE, duplicate.problems.single().code)
        assertEquals(TrendDataProblemCode.CONFLICTING_ISSUE, conflicting.problems.single().code)
    }

    /** 空输入和非法期号必须返回可区分的问题，不得构造空快照。 */
    @Test
    fun rejectsEmptyDataAndInvalidIssues() {
        val empty = calculateInvalid(emptyList())
        val invalidIssue = calculateInvalid(listOf(superLottoDraw("26000")))

        assertEquals(TrendDataProblemCode.EMPTY_DATA, empty.problems.single().code)
        assertEquals(TrendDataProblemCode.INVALID_ISSUE, invalidIssue.problems.single().code)
    }

    /** 四个彩种区域的号码范围和每期开奖数量必须保持冻结口径。 */
    @Test
    fun exposesFrozenAreaSpecifications() {
        assertEquals(
            LotteryTrendAreaSpec(LotteryTrendArea.PRIMARY, 1..35, drawnNumberCount = 5),
            LotteryType.SUPER_LOTTO.trendAreaSpec(LotteryTrendArea.PRIMARY),
        )
        assertEquals(
            LotteryTrendAreaSpec(LotteryTrendArea.SECONDARY, 1..12, drawnNumberCount = 2),
            LotteryType.SUPER_LOTTO.trendAreaSpec(LotteryTrendArea.SECONDARY),
        )
        assertEquals(
            LotteryTrendAreaSpec(LotteryTrendArea.PRIMARY, 1..33, drawnNumberCount = 6),
            LotteryType.DOUBLE_COLOR_BALL.trendAreaSpec(LotteryTrendArea.PRIMARY),
        )
        assertEquals(
            LotteryTrendAreaSpec(LotteryTrendArea.SECONDARY, 1..16, drawnNumberCount = 1),
            LotteryType.DOUBLE_COLOR_BALL.trendAreaSpec(LotteryTrendArea.SECONDARY),
        )
    }

    /** 从合法结果中提取走势图快照。 */
    private fun success(draws: List<HistoricalDraw>): LotteryTrendSnapshot =
        assertIs<LotteryTrendCalculationResult.Success>(
            calculator.calculate(
                lotteryType = LotteryType.SUPER_LOTTO,
                area = LotteryTrendArea.PRIMARY,
                sampleSize = TrendSampleSize.LAST_10,
                draws = draws,
            ),
        ).snapshot

    /** 从非法结果中提取问题列表。 */
    private fun calculateInvalid(draws: List<HistoricalDraw>): LotteryTrendCalculationResult.InvalidData =
        assertIs(
            calculator.calculate(
                lotteryType = LotteryType.SUPER_LOTTO,
                area = LotteryTrendArea.PRIMARY,
                sampleSize = TrendSampleSize.LAST_10,
                draws = draws,
            ),
        )

    /** 创建大乐透历史开奖测试记录。 */
    private fun superLottoDraw(
        issue: String,
        primary: List<Int> = listOf(1, 2, 3, 4, 5),
        secondary: List<Int> = listOf(1, 2),
    ): HistoricalDraw =
        HistoricalDraw(
            lotteryType = LotteryType.SUPER_LOTTO,
            issue = Issue(issue),
            drawDate = "2026-01-01",
            primaryNumbers = primary,
            secondaryNumbers = secondary,
        )

    /** 创建双色球历史开奖测试记录。 */
    private fun doubleColorBallDraw(
        issue: String,
        primary: List<Int> = listOf(1, 2, 3, 4, 5, 6),
        secondary: List<Int> = listOf(1),
    ): HistoricalDraw =
        HistoricalDraw(
            lotteryType = LotteryType.DOUBLE_COLOR_BALL,
            issue = Issue(issue),
            drawDate = "2026-01-01",
            primaryNumbers = primary,
            secondaryNumbers = secondary,
        )

    /** 被测领域计算器。 */
    private val calculator = LotteryTrendCalculator()
}

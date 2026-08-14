package roc.win.lottery.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** B2 单式、多注、倍投、追加与失败保护测试。 */
class LotteryPrizeCalculatorTest {
    /** 被测中奖计算器。 */
    private val calculator = LotteryPrizeCalculator()

    /** 大乐透多注票应逐行保序，并只给一、二等奖叠加追加奖金。 */
    @Test
    fun superLottoMultipleLinesMultiplierAndAdditionalAreSummed() {
        val ticket =
            ticket(
                lotteryType = LotteryType.SUPER_LOTTO,
                issue = "26091",
                lines =
                    listOf(
                        line(listOf(1, 2, 3, 4, 5), listOf(1, 2), additional = true),
                        line(listOf(1, 2, 3, 4, 6), listOf(1, 2), additional = true),
                    ),
                multiplier = 2,
                paidAmountFen = 1_200L,
            )
        val draw = superLottoDraw()

        val result = calculator.calculate(ticket, draw)

        assertEquals(PrizeCheckStatus.WIN, result.status)
        assertEquals(4_933_200L, result.estimatedPrizeFen)
        assertEquals(listOf(PrizeTierCodes.FIRST, PrizeTierCodes.THIRD), result.lineResults.map { it.prizeTierCode })
        assertEquals(listOf(3_600_000L, 1_333_200L), result.lineResults.map { it.estimatedPrizeFen })
    }

    /** 重复投注行是两注真实投注，汇总时不得去重。 */
    @Test
    fun duplicatedBetLinesAreBothCounted() {
        val winningLine = line(listOf(1, 2, 3, 4, 5), listOf(1, 2), additional = false)
        val ticket =
            ticket(
                lotteryType = LotteryType.SUPER_LOTTO,
                issue = "26091",
                lines = listOf(winningLine, winningLine),
                multiplier = 1,
                paidAmountFen = 400L,
            )

        val result = calculator.calculate(ticket, superLottoDraw())

        assertEquals(2_000_000L, result.estimatedPrizeFen)
        assertEquals(2, result.lineResults.size)
    }

    /** 一等奖追加金额缺失时仍可确认中奖和奖级，但不能给出确定金额。 */
    @Test
    fun missingAdditionalPayoutKeepsWinningTierWithoutAmount() {
        val ticket =
            ticket(
                lotteryType = LotteryType.SUPER_LOTTO,
                issue = "26091",
                lines = listOf(line(listOf(1, 2, 3, 4, 5), listOf(1, 2), additional = true)),
                multiplier = 1,
                paidAmountFen = 300L,
            )
        val draw =
            superLottoDraw().copy(
                prizeTiers =
                    superLottoDraw().prizeTiers.map {
                        if (it.code == PrizeTierCodes.FIRST) it.copy(additionalPrizeFen = null) else it
                    },
            )

        val result = calculator.calculate(ticket, draw)

        assertEquals(PrizeCheckStatus.WIN, result.status)
        assertEquals(PrizeTierCodes.FIRST, result.lineResults.single().prizeTierCode)
        assertNull(result.lineResults.single().estimatedPrizeFen)
        assertNull(result.estimatedPrizeFen)
        assertTrue(result.message.orEmpty().contains("待官方数据确认"))
    }

    /** `FINAL_NUMBERS` 只能确认奖级，不能泄漏尚未最终确认的金额。 */
    @Test
    fun finalNumbersDoesNotExposePayout() {
        val ticket =
            ticket(
                lotteryType = LotteryType.DOUBLE_COLOR_BALL,
                issue = "2026091",
                lines = listOf(line(listOf(1, 2, 3, 4, 5, 8), listOf(7))),
                multiplier = 1,
                paidAmountFen = 200L,
            )

        val result = calculator.calculate(ticket, doubleColorBallDraw().copy(status = DrawStatus.FINAL_NUMBERS))

        assertEquals(PrizeCheckStatus.WIN, result.status)
        assertEquals(PrizeTierCodes.THIRD, result.lineResults.single().prizeTierCode)
        assertNull(result.estimatedPrizeFen)
    }

    /** 双色球固定奖也必须按票面倍数汇总。 */
    @Test
    fun doubleColorBallFixedPrizeUsesMultiplier() {
        val ticket =
            ticket(
                lotteryType = LotteryType.DOUBLE_COLOR_BALL,
                issue = "2026091",
                lines = listOf(line(listOf(1, 2, 3, 4, 5, 8), listOf(7))),
                multiplier = 3,
                paidAmountFen = 600L,
            )

        val result = calculator.calculate(ticket, doubleColorBallDraw())

        assertEquals(PrizeCheckStatus.WIN, result.status)
        assertEquals(900_000L, result.estimatedPrizeFen)
    }

    /** 特别规定末期的三红零蓝必须命中福运奖并使用当期实际金额。 */
    @Test
    fun fortunePrizeUsesCurrentDrawAmount() {
        val ticket =
            ticket(
                lotteryType = LotteryType.DOUBLE_COLOR_BALL,
                issue = "2026075",
                lines = listOf(line(listOf(1, 2, 3, 8, 9, 10), listOf(8))),
                multiplier = 2,
                paidAmountFen = 400L,
            )
        val draw =
            doubleColorBallDraw(issue = "2026075").copy(
                policy = DrawPolicy.DOUBLE_COLOR_BALL_FORTUNE,
                prizeTiers = doubleColorBallDraw().prizeTiers + tier(PrizeTierCodes.FORTUNE, 500L),
            )

        val result = calculator.calculate(ticket, draw)

        assertEquals(PrizeCheckStatus.WIN, result.status)
        assertEquals(PrizeTierCodes.FORTUNE, result.lineResults.single().prizeTierCode)
        assertEquals(1_000L, result.estimatedPrizeFen)
    }

    /** 特别规定已知区间缺少特别政策证据时必须阻断测算。 */
    @Test
    fun missingKnownFortunePolicyNeedsManualReview() {
        val ticket =
            ticket(
                lotteryType = LotteryType.DOUBLE_COLOR_BALL,
                issue = "2026014",
                lines = listOf(line(listOf(1, 2, 3, 8, 9, 10), listOf(8))),
                multiplier = 1,
                paidAmountFen = 200L,
            )

        val result = calculator.calculate(ticket, doubleColorBallDraw(issue = "2026014"))

        assertEquals(PrizeCheckStatus.NEEDS_MANUAL_REVIEW, result.status)
        assertTrue(result.message.orEmpty().contains("特别规定"))
    }

    /** 完整号码证据下所有行均未中奖时才允许输出未中奖。 */
    @Test
    fun completeNumbersCanProduceNoWin() {
        val ticket =
            ticket(
                lotteryType = LotteryType.SUPER_LOTTO,
                issue = "26091",
                lines = listOf(line(listOf(6, 7, 8, 9, 10), listOf(3, 4))),
                multiplier = 1,
                paidAmountFen = 200L,
            )

        val result = calculator.calculate(ticket, superLottoDraw().copy(status = DrawStatus.FINAL_NUMBERS))

        assertEquals(PrizeCheckStatus.NO_WIN, result.status)
        assertEquals(0L, result.estimatedPrizeFen)
        assertNull(result.lineResults.single().prizeTierCode)
    }

    /** 发布中状态绝不能输出未中奖。 */
    @Test
    fun publishingDrawNeedsManualReview() {
        val ticket =
            ticket(
                lotteryType = LotteryType.SUPER_LOTTO,
                issue = "26091",
                lines = listOf(line(listOf(6, 7, 8, 9, 10), listOf(3, 4))),
                multiplier = 1,
                paidAmountFen = 200L,
            )

        val result = calculator.calculate(ticket, superLottoDraw().copy(status = DrawStatus.PUBLISHING))

        assertEquals(PrizeCheckStatus.NEEDS_MANUAL_REVIEW, result.status)
        assertTrue(result.lineResults.isEmpty())
    }

    /** 缺少官方辅助核对证据时即使号码完整也不能输出未中奖。 */
    @Test
    fun missingSupportingEvidenceNeedsManualReview() {
        val ticket =
            ticket(
                lotteryType = LotteryType.SUPER_LOTTO,
                issue = "26091",
                lines = listOf(line(listOf(6, 7, 8, 9, 10), listOf(3, 4))),
                multiplier = 1,
                paidAmountFen = 200L,
            )

        val result = calculator.calculate(ticket, superLottoDraw().copy(supportingEvidence = emptyList()))

        assertEquals(PrizeCheckStatus.NEEDS_MANUAL_REVIEW, result.status)
        assertTrue(result.message.orEmpty().contains("辅助核对"))
    }

    /** 已确认特别规定区间之外不能擅自套用福运奖政策。 */
    @Test
    fun fortunePolicyOutsideConfirmedRangeNeedsManualReview() {
        val ticket =
            ticket(
                lotteryType = LotteryType.DOUBLE_COLOR_BALL,
                issue = "2026091",
                lines = listOf(line(listOf(1, 2, 3, 8, 9, 10), listOf(8))),
                multiplier = 1,
                paidAmountFen = 200L,
            )

        val result =
            calculator.calculate(
                ticket,
                doubleColorBallDraw().copy(policy = DrawPolicy.DOUBLE_COLOR_BALL_FORTUNE),
            )

        assertEquals(PrizeCheckStatus.NEEDS_MANUAL_REVIEW, result.status)
        assertTrue(result.message.orEmpty().contains("特别规定"))
    }

    /** 旧规则期号必须明确返回不支持，不能套用当前规则。 */
    @Test
    fun oldIssueIsRuleUnsupported() {
        val ticket =
            ticket(
                lotteryType = LotteryType.SUPER_LOTTO,
                issue = "26013",
                lines = listOf(line(listOf(1, 2, 3, 4, 5), listOf(1, 2))),
                multiplier = 1,
                paidAmountFen = 200L,
            )

        val result = calculator.calculate(ticket, superLottoDraw().copy(issue = Issue("26013")))

        assertEquals(PrizeCheckStatus.RULE_UNSUPPORTED, result.status)
        assertTrue(result.lineResults.isEmpty())
    }

    /** 彩票期号和开奖结果不一致时必须阻断。 */
    @Test
    fun mismatchedDrawNeedsManualReview() {
        val ticket =
            ticket(
                lotteryType = LotteryType.SUPER_LOTTO,
                issue = "26090",
                lines = listOf(line(listOf(1, 2, 3, 4, 5), listOf(1, 2))),
                multiplier = 1,
                paidAmountFen = 200L,
            )

        val result = calculator.calculate(ticket, superLottoDraw())

        assertEquals(PrizeCheckStatus.NEEDS_MANUAL_REVIEW, result.status)
        assertTrue(result.message.orEmpty().contains("期号不一致"))
    }

    /** 创建大乐透最终开奖模型。 */
    private fun superLottoDraw(): DrawResult =
        drawResult(
            lotteryType = LotteryType.SUPER_LOTTO,
            issue = "26091",
            primary = listOf(1, 2, 3, 4, 5),
            secondary = listOf(1, 2),
            ruleVersion = RuleVersion.DLT_2026_01,
            tiers =
                listOf(
                    tier(PrizeTierCodes.FIRST, 1_000_000L, 800_000L),
                    tier(PrizeTierCodes.SECOND, 500_000L, 400_000L),
                    tier(PrizeTierCodes.THIRD, 666_600L),
                    tier(PrizeTierCodes.FOURTH, 38_000L),
                    tier(PrizeTierCodes.FIFTH, 20_000L),
                    tier(PrizeTierCodes.SIXTH, 1_800L),
                    tier(PrizeTierCodes.SEVENTH, 700L),
                ),
        )

    /** 创建双色球最终开奖模型。 */
    private fun doubleColorBallDraw(issue: String = "2026091"): DrawResult =
        drawResult(
            lotteryType = LotteryType.DOUBLE_COLOR_BALL,
            issue = issue,
            primary = listOf(1, 2, 3, 4, 5, 6),
            secondary = listOf(7),
            ruleVersion = RuleVersion.SSQ_2026_01,
            tiers =
                listOf(
                    tier(PrizeTierCodes.FIRST, 1_000_000_000L),
                    tier(PrizeTierCodes.SECOND, 30_000_000L),
                    tier(PrizeTierCodes.THIRD, 300_000L),
                    tier(PrizeTierCodes.FOURTH, 20_000L),
                    tier(PrizeTierCodes.FIFTH, 1_000L),
                    tier(PrizeTierCodes.SIXTH, 500L),
                ),
        )

    /** 创建指定字段的统一开奖模型。 */
    private fun drawResult(
        lotteryType: LotteryType,
        issue: String,
        primary: List<Int>,
        secondary: List<Int>,
        ruleVersion: RuleVersion,
        tiers: List<PrizeTier>,
    ): DrawResult =
        DrawResult(
            lotteryType = lotteryType,
            issue = Issue(issue),
            drawDate = "2026-08-12",
            primaryNumbers = primary,
            secondaryNumbers = secondary,
            status = DrawStatus.FINAL_PAYOUT,
            revision = 1,
            ruleVersion = ruleVersion.code,
            policy = DrawPolicy.STANDARD,
            prizeTiers = tiers,
            evidence = SourceEvidence("测试主源", "https://example.invalid/main", 1L, "hash"),
            supportingEvidence =
                listOf(SourceEvidence("测试辅助源", "https://example.invalid/detail", 1L, "hash")),
        )

    /** 创建一个统一奖级。 */
    private fun tier(
        code: String,
        singlePrizeFen: Long,
        additionalPrizeFen: Long? = null,
    ): PrizeTier =
        PrizeTier(
            code = code,
            displayName = code,
            singlePrizeFen = singlePrizeFen,
            additionalPrizeFen = additionalPrizeFen,
        )

    /** 创建字段严格一致的已确认彩票。 */
    private fun ticket(
        lotteryType: LotteryType,
        issue: String,
        lines: List<BetLine>,
        multiplier: Int,
        paidAmountFen: Long,
    ): ConfirmedTicket =
        ConfirmedTicket(
            lotteryType = confirmed(lotteryType),
            issue = confirmed(Issue(issue)),
            betLines = lines,
            multiplier = confirmed(multiplier),
            periodCount = confirmed(1),
            paidAmountFen = confirmed(paidAmountFen),
        )

    /** 创建一行已确认单式投注。 */
    private fun line(
        primary: List<Int>,
        secondary: List<Int>,
        additional: Boolean = false,
    ): BetLine =
        BetLine(
            primaryNumbers = confirmed(primary),
            secondaryNumbers = confirmed(secondary),
            isAdditional = confirmed(additional),
            originalText = "测试投注行",
        )

    /** 创建人工确认字段。 */
    private fun <T> confirmed(value: T): ConfirmedValue<T> = ConfirmedValue(value, TicketFieldOrigin.USER)
}

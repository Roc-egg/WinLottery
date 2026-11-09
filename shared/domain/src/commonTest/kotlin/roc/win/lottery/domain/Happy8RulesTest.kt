package roc.win.lottery.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 快乐8 单式的注数、校验、中奖矩阵与计奖测试。 */
class Happy8RulesTest {
    /** 选一至选十的中奖命中数与官网 39 个奖级一致，含选七至选十的“中零”。 */
    @Test
    fun winningMatrixMatchesOfficialTiers() {
        assertEquals(39, LotteryPrizeRules.HAPPY_8_PRIZE_TIER_CODES.size)
        assertEquals("X10Z10", LotteryPrizeRules.HAPPY_8_PRIZE_TIER_CODES.first())
        assertEquals("X1Z1", LotteryPrizeRules.HAPPY_8_PRIZE_TIER_CODES.last())
        assertEquals("X10Z0", LotteryPrizeRules.findHappy8PrizeTierCode(10, 0))
        assertNull(LotteryPrizeRules.findHappy8PrizeTierCode(10, 4))
        assertEquals("X7Z0", LotteryPrizeRules.findHappy8PrizeTierCode(7, 0))
        assertNull(LotteryPrizeRules.findHappy8PrizeTierCode(6, 0))
        assertEquals("X5Z3", LotteryPrizeRules.findHappy8PrizeTierCode(5, 3))
        assertNull(LotteryPrizeRules.findHappy8PrizeTierCode(5, 2))
        assertEquals("X4Z2", LotteryPrizeRules.findHappy8PrizeTierCode(4, 2))
        assertEquals("X1Z1", LotteryPrizeRules.findHappy8PrizeTierCode(1, 1))
        assertNull(LotteryPrizeRules.findHappy8PrizeTierCode(1, 0))
        assertNull(LotteryPrizeRules.findHappy8PrizeTierCode(11, 5))
    }

    /** 奖级编码换算为中文名称。 */
    @Test
    fun tierCodesHaveChineseNames() {
        assertEquals("选五中三", PrizeTierCodes.happy8DisplayName("X5Z3"))
        assertEquals("选十中零", PrizeTierCodes.happy8DisplayName("X10Z0"))
        assertEquals("选十中十", PrizeTierCodes.happy8DisplayName("X10Z10"))
        assertNull(PrizeTierCodes.happy8DisplayName(PrizeTierCodes.FIRST))
        assertNull(PrizeTierCodes.happy8DisplayName("X3Z4"))
        assertEquals("选五", PrizeTierCodes.happy8PlayName(5))
        assertNull(PrizeTierCodes.happy8PlayName(0))
    }

    /** 单式一行为一注，号码个数不在选一至选十或带次号码时不成注，也不算复式。 */
    @Test
    fun singleLineIsOneBet() {
        assertEquals(1L, BetCombinations.betCount(LotteryType.HAPPY_8, 5, 0))
        assertEquals(1L, BetCombinations.betCount(LotteryType.HAPPY_8, 10, 0))
        assertEquals(0L, BetCombinations.betCount(LotteryType.HAPPY_8, 11, 0))
        assertEquals(0L, BetCombinations.betCount(LotteryType.HAPPY_8, 5, 1))
        assertFalse(line(1, 2, 3, 4, 5, 6, 7, 8, 9, 10).isCompound(LotteryType.HAPPY_8))
    }

    /** 两注选五、单倍单期 4 元合法；各注个数不同、超过十个、超范围、带次号码或追加均不合法。 */
    @Test
    fun validatorEnforcesHappy8Rules() {
        val validator = TicketValidator()
        assertEquals(TicketValidationStatus.VALID, validator.validate(ticket(listOf(fiveA, fiveB))).status)

        val mixed = validator.validate(ticket(listOf(fiveA, line(1, 2, 3)), paidAmountFen = 400L))
        assertEquals(TicketValidationStatus.INVALID, mixed.status)
        assertTrue(mixed.problems.any { it.field == "betLines" })

        val tooMany = validator.validate(ticket(listOf(line(*(1..11).toList().toIntArray()))))
        assertTrue(tooMany.problems.any { it.field == "betLines[0].primaryNumbers" && "选一至选十" in it.message })

        assertEquals(
            TicketValidationStatus.INVALID,
            validator.validate(ticket(listOf(line(1, 2, 3, 4, 81)), paidAmountFen = 200L)).status,
        )
        assertEquals(
            TicketValidationStatus.INVALID,
            validator.validate(ticket(listOf(fiveA.copy(secondaryNumbers = confirmed(listOf(3)))), 200L)).status,
        )
        assertEquals(
            TicketValidationStatus.INVALID,
            validator.validate(ticket(listOf(fiveA.copy(isAdditional = confirmed(true))), 200L)).status,
        )
        assertEquals(
            TicketValidationStatus.INVALID,
            validator.validate(ticket(listOf(fiveA, fiveB), paidAmountFen = 600L)).status,
        )
    }

    /** 期号从 2024001 起由已核对的中奖矩阵覆盖。 */
    @Test
    fun ruleVersionCoversVerifiedIssues() {
        val selector = RuleVersionSelector()
        assertEquals(RuleVersion.KL8_2024_01, selector.select(LotteryType.HAPPY_8, Issue("2024001")))
        assertEquals(RuleVersion.KL8_2024_01, selector.select(LotteryType.HAPPY_8, Issue("2026294")))
        assertNull(selector.select(LotteryType.HAPPY_8, Issue("2023350")))
        assertNull(selector.select(LotteryType.HAPPY_8, Issue("26294")))
    }

    /** 每注按选几和命中数取当期官网金额，计入倍数；不中奖的注为零。 */
    @Test
    fun calculatesPrizeFromPublishedTierAmounts() {
        val draw = draw(drawnNumbers = (1..20).toList())
        // 命中 3、5、7；未命中 41、60。
        val hitThree = line(3, 5, 7, 41, 60)
        // 只命中 1、2。
        val hitTwo = line(1, 2, 41, 60, 77)
        val result =
            LotteryPrizeCalculator().calculate(
                ticket(listOf(hitThree, hitTwo), paidAmountFen = 800L, multiplier = 2),
                draw,
            )

        assertEquals(PrizeCheckStatus.WIN, result.status)
        assertEquals("X5Z3", result.lineResults[0].prizeTierCode)
        assertEquals(3, result.lineResults[0].primaryHitCount)
        assertEquals(600L, result.lineResults[0].estimatedPrizeFen)
        assertNull(result.lineResults[1].prizeTierCode)
        assertEquals(600L, result.estimatedPrizeFen)

        val tenMissAll = line(41, 42, 43, 44, 45, 46, 47, 48, 49, 50)
        val zeroHit = LotteryPrizeCalculator().calculate(ticket(listOf(tenMissAll), paidAmountFen = 200L), draw)
        assertEquals("X10Z0", zeroHit.lineResults.single().prizeTierCode)
        assertEquals(200L, zeroHit.estimatedPrizeFen)

        val noWin = LotteryPrizeCalculator().calculate(ticket(listOf(hitTwo), paidAmountFen = 200L), draw)
        assertEquals(PrizeCheckStatus.NO_WIN, noWin.status)
    }

    /** 多期票按年度内序号顺延，快乐8 期号前四位为年份。 */
    @Test
    fun multiPeriodIssuesFollowYearlyOrdinal() {
        val result =
            IssueSequenceResolver().resolve(LotteryType.HAPPY_8, Issue("2026298"), periodCount = 3)
        assertEquals(
            listOf("2026298", "2026299", "2026300"),
            (result as IssueSequenceResult.Success).issues.map { it.value },
        )
    }

    /** 创建快乐8 单式投注行。 */
    private fun line(vararg numbers: Int): BetLine =
        BetLine(confirmed(numbers.toList()), confirmed(emptyList()), confirmed(false), "")

    /** 创建快乐8 单期票。 */
    private fun ticket(
        lines: List<BetLine>,
        paidAmountFen: Long = 200L * lines.size,
        multiplier: Int = 1,
    ): ConfirmedTicket =
        ConfirmedTicket(
            lotteryType = confirmed(LotteryType.HAPPY_8),
            issue = confirmed(Issue("2026294")),
            betLines = lines,
            multiplier = confirmed(multiplier),
            periodCount = confirmed(1),
            paidAmountFen = confirmed(paidAmountFen),
        )

    /** 创建金额已发布的快乐8 开奖，奖级金额为虚构值。 */
    private fun draw(drawnNumbers: List<Int>): DrawResult =
        DrawResult(
            lotteryType = LotteryType.HAPPY_8,
            issue = Issue("2026294"),
            drawDate = "2026-11-04",
            primaryNumbers = drawnNumbers,
            secondaryNumbers = emptyList(),
            status = DrawStatus.FINAL_PAYOUT,
            revision = 1,
            ruleVersion = RuleVersion.KL8_2024_01.code,
            policy = DrawPolicy.STANDARD,
            prizeTiers =
                LotteryPrizeRules.HAPPY_8_PRIZE_TIER_CODES.map { code ->
                    PrizeTier(
                        code = code,
                        displayName = checkNotNull(PrizeTierCodes.happy8DisplayName(code)),
                        singlePrizeFen = if (code == "X5Z3") 300L else 200L,
                        additionalPrizeFen = null,
                        winnerCount = 1L,
                    )
                },
            evidence = SourceEvidence("测试主源", "https://example.invalid/main", 1L, "hash"),
            supportingEvidence = emptyList(),
        )

    /** 包装用户确认值。 */
    private fun <T> confirmed(value: T): ConfirmedValue<T> = ConfirmedValue(value, TicketFieldOrigin.USER)

    /** 第一注选五。 */
    private val fiveA = line(3, 11, 25, 40, 77)

    /** 第二注选五。 */
    private val fiveB = line(5, 19, 33, 58, 80)
}

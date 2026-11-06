package roc.win.lottery.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** B2 单式、多注、复式、倍投、追加与无法比对场景测试。 */
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

    /** 合成十期票在第 26096 期命中 2+1 时应得到七等奖 7 元。 */
    @Test
    fun issue26096HighPoolSeventhPrizeIsSevenYuan() {
        val ticket =
            ticket(
                lotteryType = LotteryType.SUPER_LOTTO,
                issue = "26096",
                lines = listOf(line(listOf(8, 9, 20, 21, 22), listOf(1, 4))),
                multiplier = 1,
                periodCount = 10,
                paidAmountFen = 2_000L,
            )
        val draw =
            drawResult(
                lotteryType = LotteryType.SUPER_LOTTO,
                issue = "26096",
                primary = listOf(8, 9, 10, 11, 25),
                secondary = listOf(4, 12),
                ruleVersion = RuleVersion.DLT_2026_01,
                tiers =
                    listOf(
                        tier(PrizeTierCodes.FIRST, 656_195_200L, 524_956_100L),
                        tier(PrizeTierCodes.SECOND, 5_789_300L, 4_631_500L),
                        tier(PrizeTierCodes.THIRD, 666_600L),
                        tier(PrizeTierCodes.FOURTH, 38_000L),
                        tier(PrizeTierCodes.FIFTH, 20_000L),
                        tier(PrizeTierCodes.SIXTH, 1_800L),
                        tier(PrizeTierCodes.SEVENTH, 700L),
                    ),
            )

        val result = calculator.calculate(ticket, draw)

        assertEquals(PrizeCheckStatus.WIN, result.status)
        assertEquals(700L, result.estimatedPrizeFen)
        assertEquals(PrizeTierCodes.SEVENTH, result.lineResults.single().prizeTierCode)
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
                status = DrawStatus.FINAL_NUMBERS,
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

    /** 官方数据多出未知奖级时不影响已命中奖级的结论和金额。 */
    @Test
    fun unknownExtraTierDoesNotBlockWinningResult() {
        val ticket =
            ticket(
                lotteryType = LotteryType.SUPER_LOTTO,
                issue = "26091",
                lines = listOf(line(listOf(1, 2, 3, 4, 5), listOf(1, 2))),
                multiplier = 1,
                paidAmountFen = 200L,
            )
        val draw =
            superLottoDraw().copy(
                prizeTiers = superLottoDraw().prizeTiers + tier("UNKNOWN", 100L),
            )

        val result = calculator.calculate(ticket, draw)

        assertEquals(PrizeCheckStatus.WIN, result.status)
        assertEquals(PrizeTierCodes.FIRST, result.lineResults.single().prizeTierCode)
        assertEquals(1_000_000L, result.estimatedPrizeFen)
    }

    /** 固定奖金额与内置档位不一致时仍按官方当期金额给出结论。 */
    @Test
    fun officialFixedPrizeOutsideKnownBandsStillProducesResult() {
        val ticket =
            ticket(
                lotteryType = LotteryType.SUPER_LOTTO,
                issue = "26091",
                lines = listOf(line(listOf(1, 2, 3, 4, 5), listOf(3, 4))),
                multiplier = 1,
                paidAmountFen = 200L,
            )
        val draw =
            superLottoDraw().copy(
                prizeTiers =
                    superLottoDraw().prizeTiers.map { tier ->
                        if (tier.code == PrizeTierCodes.THIRD) tier.copy(singlePrizeFen = 500_000L) else tier
                    },
            )

        val result = calculator.calculate(ticket, draw)

        assertEquals(PrizeCheckStatus.WIN, result.status)
        assertEquals(PrizeTierCodes.THIRD, result.lineResults.single().prizeTierCode)
        assertEquals(500_000L, result.estimatedPrizeFen)
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

    /** 已知特别规定期间即使官方数据缺少政策标记也要判出福运奖，金额待定。 */
    @Test
    fun knownFortunePeriodAlwaysEnablesFortunePrize() {
        val ticket =
            ticket(
                lotteryType = LotteryType.DOUBLE_COLOR_BALL,
                issue = "2026014",
                lines = listOf(line(listOf(1, 2, 3, 8, 9, 10), listOf(8))),
                multiplier = 1,
                paidAmountFen = 200L,
            )

        val result = calculator.calculate(ticket, doubleColorBallDraw(issue = "2026014"))

        assertEquals(PrizeCheckStatus.WIN, result.status)
        assertEquals(PrizeTierCodes.FORTUNE, result.lineResults.single().prizeTierCode)
        assertNull(result.estimatedPrizeFen)
        assertTrue(result.message.orEmpty().contains("待官方数据确认"))
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
    fun publishingDrawIsNotCompared() {
        val ticket =
            ticket(
                lotteryType = LotteryType.SUPER_LOTTO,
                issue = "26091",
                lines = listOf(line(listOf(6, 7, 8, 9, 10), listOf(3, 4))),
                multiplier = 1,
                paidAmountFen = 200L,
            )

        val result = calculator.calculate(ticket, superLottoDraw().copy(status = DrawStatus.PUBLISHING))

        assertEquals(PrizeCheckStatus.NOT_CALCULATED, result.status)
        assertTrue(result.lineResults.isEmpty())
    }

    /** 只取得主官方数据面时也应直接给出比对结论。 */
    @Test
    fun missingSupportingEvidenceStillProducesConclusion() {
        val ticket =
            ticket(
                lotteryType = LotteryType.SUPER_LOTTO,
                issue = "26091",
                lines = listOf(line(listOf(6, 7, 8, 9, 10), listOf(3, 4))),
                multiplier = 1,
                paidAmountFen = 200L,
            )

        val result = calculator.calculate(ticket, superLottoDraw().copy(supportingEvidence = emptyList()))

        assertEquals(PrizeCheckStatus.NO_WIN, result.status)
        assertEquals(0L, result.estimatedPrizeFen)
    }

    /** 官方数据在已知区间之外给出福运奖政策时按官方政策判奖。 */
    @Test
    fun officialFortunePolicyOutsideKnownRangeIsTrusted() {
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

        assertEquals(PrizeCheckStatus.WIN, result.status)
        assertEquals(PrizeTierCodes.FORTUNE, result.lineResults.single().prizeTierCode)
        assertNull(result.estimatedPrizeFen)
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

    /** 彩票期号和开奖结果不一致时不能比对。 */
    @Test
    fun mismatchedDrawIsNotCompared() {
        val ticket =
            ticket(
                lotteryType = LotteryType.SUPER_LOTTO,
                issue = "26090",
                lines = listOf(line(listOf(1, 2, 3, 4, 5), listOf(1, 2))),
                multiplier = 1,
                paidAmountFen = 200L,
            )

        val result = calculator.calculate(ticket, superLottoDraw())

        assertEquals(PrizeCheckStatus.NOT_CALCULATED, result.status)
        assertTrue(result.message.orEmpty().contains("期号不一致"))
    }

    /**
     * 多期票逐期测算时沿用整票期数和金额，不能因为从当前期往后展开超出年度中段上限而阻断结论。
     */
    @Test
    fun laterPeriodOfMultiPeriodTicketIsCompared() {
        val ticket =
            ticket(
                lotteryType = LotteryType.DOUBLE_COLOR_BALL,
                issue = "2026118",
                lines = listOf(line(listOf(1, 2, 3, 4, 5, 8), listOf(7))),
                multiplier = 1,
                periodCount = 7,
                paidAmountFen = 1_400L,
            )

        val result = calculator.calculate(ticket, doubleColorBallDraw(issue = "2026118"))

        assertEquals(PrizeCheckStatus.WIN, result.status)
        assertEquals(PrizeTierCodes.THIRD, result.lineResults.single().prizeTierCode)
        assertEquals(300_000L, result.estimatedPrizeFen)
    }

    /** 重复奖级只让对应金额待定，不阻断中奖结论。 */
    @Test
    fun duplicatedTierKeepsWinningTierWithoutAmount() {
        val ticket =
            ticket(
                lotteryType = LotteryType.DOUBLE_COLOR_BALL,
                issue = "2026091",
                lines = listOf(line(listOf(1, 2, 3, 4, 5, 8), listOf(7))),
                multiplier = 1,
                paidAmountFen = 200L,
            )
        val draw =
            doubleColorBallDraw().copy(
                prizeTiers = doubleColorBallDraw().prizeTiers + tier(PrizeTierCodes.THIRD, 300_000L),
            )

        val result = calculator.calculate(ticket, draw)

        assertEquals(PrizeCheckStatus.WIN, result.status)
        assertEquals(PrizeTierCodes.THIRD, result.lineResults.single().prizeTierCode)
        assertNull(result.estimatedPrizeFen)
    }

    /** 大乐透 7+3 复式全中前区和两个后区时，63 注应按组合数分别落入六个奖级。 */
    @Test
    fun superLottoCompoundLineCountsEveryTier() {
        val ticket =
            ticket(
                lotteryType = LotteryType.SUPER_LOTTO,
                issue = "26091",
                lines = listOf(line(listOf(1, 2, 3, 4, 5, 6, 7), listOf(1, 2, 3))),
                multiplier = 1,
                paidAmountFen = 12_600L,
            )

        val result = calculator.calculate(ticket, superLottoDraw())

        val lineResult = result.lineResults.single()
        assertEquals(PrizeCheckStatus.WIN, result.status)
        assertEquals(63L, lineResult.betCount)
        assertEquals(5, lineResult.primaryHitCount)
        assertEquals(2, lineResult.secondaryHitCount)
        assertEquals(PrizeTierCodes.FIRST, lineResult.prizeTierCode)
        assertEquals(
            listOf(
                PrizeTierWin(PrizeTierCodes.FIRST, 1L, 1_000_000L),
                PrizeTierWin(PrizeTierCodes.SECOND, 2L, 1_000_000L),
                PrizeTierWin(PrizeTierCodes.THIRD, 10L, 6_666_000L),
                PrizeTierWin(PrizeTierCodes.FOURTH, 20L, 760_000L),
                PrizeTierWin(PrizeTierCodes.FIFTH, 10L, 200_000L),
                PrizeTierWin(PrizeTierCodes.SIXTH, 20L, 36_000L),
            ),
            lineResult.tierWins,
        )
        assertEquals(9_662_000L, lineResult.estimatedPrizeFen)
        assertEquals(9_662_000L, result.estimatedPrizeFen)
    }

    /** 双色球 8+2 复式应把同一奖级的不同命中组合合并计数，未中奖组合不计入。 */
    @Test
    fun doubleColorBallCompoundLineMergesSameTier() {
        val ticket =
            ticket(
                lotteryType = LotteryType.DOUBLE_COLOR_BALL,
                issue = "2026091",
                lines = listOf(line(listOf(1, 2, 3, 4, 10, 11, 12, 13), listOf(7, 8))),
                multiplier = 2,
                paidAmountFen = 22_400L,
            )

        val result = calculator.calculate(ticket, doubleColorBallDraw())

        val lineResult = result.lineResults.single()
        assertEquals(56L, lineResult.betCount)
        assertEquals(
            listOf(
                PrizeTierWin(PrizeTierCodes.FOURTH, 6L, 240_000L),
                PrizeTierWin(PrizeTierCodes.FIFTH, 22L, 44_000L),
                PrizeTierWin(PrizeTierCodes.SIXTH, 6L, 6_000L),
            ),
            lineResult.tierWins,
        )
        assertEquals(290_000L, result.estimatedPrizeFen)
    }

    /** 复式未命中任何奖级时整票未中奖，金额为零。 */
    @Test
    fun compoundLineWithoutPrizeIsNoWin() {
        val ticket =
            ticket(
                lotteryType = LotteryType.SUPER_LOTTO,
                issue = "26091",
                lines = listOf(line(listOf(1, 20, 21, 22, 23, 24), listOf(5, 6, 7))),
                multiplier = 1,
                paidAmountFen = 3_600L,
            )

        val result = calculator.calculate(ticket, superLottoDraw())

        val lineResult = result.lineResults.single()
        assertEquals(PrizeCheckStatus.NO_WIN, result.status)
        assertEquals(0L, result.estimatedPrizeFen)
        assertEquals(18L, lineResult.betCount)
        assertTrue(lineResult.tierWins.isEmpty())
    }

    /** 某个奖级金额缺失时复式行仍给出全部中奖注数，只把缺失奖级和合计金额留空。 */
    @Test
    fun compoundLineWithMissingTierAmountKeepsCounts() {
        val ticket =
            ticket(
                lotteryType = LotteryType.SUPER_LOTTO,
                issue = "26091",
                lines = listOf(line(listOf(1, 2, 3, 4, 6, 7), listOf(1, 2))),
                multiplier = 1,
                paidAmountFen = 1_200L,
            )
        val draw =
            superLottoDraw().copy(
                prizeTiers = superLottoDraw().prizeTiers.filterNot { it.code == PrizeTierCodes.FIFTH },
            )

        val result = calculator.calculate(ticket, draw)

        val lineResult = result.lineResults.single()
        assertEquals(PrizeCheckStatus.WIN, result.status)
        assertEquals(
            listOf(
                PrizeTierWin(PrizeTierCodes.THIRD, 2L, 1_333_200L),
                PrizeTierWin(PrizeTierCodes.FIFTH, 4L, null),
            ),
            lineResult.tierWins,
        )
        assertNull(lineResult.estimatedPrizeFen)
        assertNull(result.estimatedPrizeFen)
    }

    /** 组合数统计必须与把复式逐注展开后的单式结果完全一致，含追加和福运奖。 */
    @Test
    fun compoundCountingMatchesExpandedSingleBets() {
        val cases =
            listOf(
                Triple(
                    LotteryType.SUPER_LOTTO,
                    superLottoDraw(),
                    listOf(
                        line(listOf(1, 2, 3, 9, 10, 11, 12, 13), listOf(1, 2, 5, 6), additional = true),
                        line(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9), listOf(2, 9), additional = true),
                        line(listOf(3, 4, 20, 21, 22, 23), listOf(1, 2, 3)),
                    ),
                ),
                Triple(
                    LotteryType.DOUBLE_COLOR_BALL,
                    doubleColorBallDraw(),
                    listOf(
                        line(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), listOf(7, 8, 9)),
                        line(listOf(1, 2, 3, 20, 21, 22, 23), listOf(1, 2, 7)),
                    ),
                ),
                Triple(
                    LotteryType.DOUBLE_COLOR_BALL,
                    doubleColorBallDraw(issue = "2026050").copy(
                        policy = DrawPolicy.DOUBLE_COLOR_BALL_FORTUNE,
                        prizeTiers = doubleColorBallDraw().prizeTiers + tier(PrizeTierCodes.FORTUNE, 500L),
                    ),
                    listOf(line(listOf(1, 2, 3, 4, 20, 21, 22, 23, 24), listOf(7, 8))),
                ),
            )
        cases.forEach { (lotteryType, draw, lines) ->
            val compound =
                calculator.calculate(
                    ticket(lotteryType, draw.issue.value, lines, multiplier = 3, paidAmountFen = 0L),
                    draw,
                )
            val expandedLines = lines.flatMap { expand(lotteryType, it) }
            val expanded =
                calculator.calculate(
                    ticket(lotteryType, draw.issue.value, expandedLines, multiplier = 3, paidAmountFen = 0L),
                    draw,
                )

            assertEquals(expanded.status, compound.status)
            assertEquals(expanded.estimatedPrizeFen, compound.estimatedPrizeFen)
            assertEquals(expandedLines.size.toLong(), compound.lineResults.sumOf { it.betCount })
            assertEquals(expanded.lineResults.tierCounts(), compound.lineResults.tierCounts())
            assertEquals(
                expanded.lineResults.sumOf { checkNotNull(it.estimatedPrizeFen) },
                compound.lineResults.sumOf { checkNotNull(it.estimatedPrizeFen) },
            )
        }
    }

    /** 按奖级汇总全部行的中奖注数。 */
    private fun List<BetLinePrizeResult>.tierCounts(): Map<String, Long> =
        flatMap { it.tierWins }
            .groupBy { it.prizeTierCode }
            .mapValues { (_, wins) -> wins.sumOf { it.winningBetCount } }

    /** 把复式行逐注展开为全部单式组合。 */
    private fun expand(
        lotteryType: LotteryType,
        compound: BetLine,
    ): List<BetLine> =
        subsets(compound.primaryNumbers.value, BetCombinations.primaryCount(lotteryType)).flatMap { primary ->
            subsets(compound.secondaryNumbers.value, BetCombinations.secondaryCount(lotteryType)).map { secondary ->
                line(primary, secondary, compound.isAdditional.value)
            }
        }

    /** 按升序列出全部 `size` 元子集。 */
    private fun subsets(
        numbers: List<Int>,
        size: Int,
    ): List<List<Int>> {
        if (size == 0) return listOf(emptyList())
        if (numbers.size < size) return emptyList()
        val head = numbers.first()
        val tail = numbers.drop(1)
        return subsets(tail, size - 1).map { listOf(head) + it } + subsets(tail, size)
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
        periodCount: Int = 1,
        paidAmountFen: Long,
    ): ConfirmedTicket =
        ConfirmedTicket(
            lotteryType = confirmed(lotteryType),
            issue = confirmed(Issue(issue)),
            betLines = lines,
            multiplier = confirmed(multiplier),
            periodCount = confirmed(periodCount),
            paidAmountFen = confirmed(paidAmountFen),
        )

    /** 创建一行已确认单式或复式投注。 */
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

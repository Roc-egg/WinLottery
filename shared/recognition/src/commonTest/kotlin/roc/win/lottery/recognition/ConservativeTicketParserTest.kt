package roc.win.lottery.recognition

import roc.win.lottery.domain.LotteryType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 基于真实票样结构最小化后的保守票面解析测试。 */
class ConservativeTicketParserTest {
    /** 大乐透单期多注基本票应生成等待人工确认的草稿。 */
    @Test
    fun singlePeriodSuperLottoBasicTicketIsReadyForReview() {
        val result =
            parser.parse(
                document(
                    "体彩 超级大乐透",
                    "第 26999期",
                    "单式票 1倍 合计10元",
                    "① 01 07 14 22 35 + 03 11",
                    "② 02 08 15 23 34 + 04 10",
                    "③ 03 09 16 24 33 + 05 09",
                    "④ 04 10 17 25 32 + 06 08",
                    "⑤ 05 11 18 26 31 + 07 12",
                ),
            )

        val review = assertIs<TicketParseResult.ReadyForReview>(result)
        val draft = review.draft
        assertEquals(LotteryType.SUPER_LOTTO, draft.lotteryType)
        assertEquals("26999", draft.issue)
        assertEquals(5, draft.betLines.size)
        assertEquals(listOf(1, 7, 14, 22, 35), draft.betLines.first().primaryNumbers)
        assertEquals(listOf(3, 11), draft.betLines.first().secondaryNumbers)
        assertFalse(draft.betLines.first().isAdditional ?: true)
        assertEquals(1, draft.multiplier)
        assertEquals(1, draft.periodCount)
        assertEquals(1_000L, draft.paidAmountFen)
        assertEquals(
            listOf(
                TicketFieldReference.LotteryType,
                TicketFieldReference.Issue,
                TicketFieldReference.BetLine(0),
                TicketFieldReference.BetLine(1),
                TicketFieldReference.BetLine(2),
                TicketFieldReference.BetLine(3),
                TicketFieldReference.BetLine(4),
                TicketFieldReference.Multiplier,
                TicketFieldReference.Additional,
                TicketFieldReference.PaidAmount,
            ),
            review.fieldRegions.map { it.field },
        )
        assertTrue(review.fieldRegions.all { it.rawConfidence == TEST_CONFIDENCE })
    }

    /** 同一视觉行的分散 OCR 片段应按横向位置合并后解析大乐透追加票。 */
    @Test
    fun splitOcrFragmentsAreMergedForAdditionalTicket() {
        val result =
            parser.parse(
                OcrDocument(
                    imageId = TEST_IMAGE_ID,
                    engineName = TEST_ENGINE_NAME,
                    lines =
                        listOf(
                            line(
                                "超级大乐透",
                                left = 0.05f,
                                top = 0.02f,
                                width = 0.80f,
                                height = 0.02f,
                                confidence = 0.20f,
                            ),
                            line("大乐透", left = 0.35f, top = 0.08f),
                            line("体彩", left = 0.10f, top = 0.08f),
                            line("第26999期", left = 0.10f, top = 0.18f, confidence = 0.91f),
                            line("单式票", left = 0.10f, top = 0.28f, confidence = 0.88f),
                            line("追加投注", left = 0.35f, top = 0.28f, confidence = 0.77f),
                            line("1期1倍", left = 0.62f, top = 0.28f, confidence = null),
                            line("合计3元", left = 0.78f, top = 0.28f, confidence = 0.66f),
                            line("① 01 07 14 22 35", left = 0.10f, top = 0.38f, confidence = 0.62f),
                            line("+ 03 11", left = 0.70f, top = 0.38f, confidence = 0.48f),
                        ),
                ),
            )

        val review = assertIs<TicketParseResult.ReadyForReview>(result)
        val draft = review.draft
        assertTrue(draft.betLines.single().isAdditional == true)
        assertEquals(300L, draft.paidAmountFen)
        val betRegion = review.fieldRegions.single { it.field == TicketFieldReference.BetLine(0) }
        assertEquals(0.10f, betRegion.bounds.left)
        assertEquals(0.88f, betRegion.bounds.right)
        assertEquals(0.48f, betRegion.rawConfidence)
        assertEquals(
            0.91f,
            review.fieldRegions.single { it.field == TicketFieldReference.Issue }.rawConfidence,
        )
        val lotteryTypeRegion = review.fieldRegions.single { it.field == TicketFieldReference.LotteryType }
        assertEquals(0.35f, lotteryTypeRegion.bounds.left)
        assertEquals(0.53f, lotteryTypeRegion.bounds.right)
        assertEquals(TEST_CONFIDENCE, lotteryTypeRegion.rawConfidence)
        assertNull(review.fieldRegions.single { it.field == TicketFieldReference.Multiplier }.rawConfidence)
        assertNull(review.fieldRegions.single { it.field == TicketFieldReference.Additional }.rawConfidence)
        assertNull(review.fieldRegions.single { it.field == TicketFieldReference.PeriodCount }.rawConfidence)
        assertNull(review.fieldRegions.single { it.field == TicketFieldReference.PaidAmount }.rawConfidence)
    }

    /** 标题和摘要单位受损且加号漏识别时，应依据发行机构、坐标间隔和金额关系保留多期草稿。 */
    @Test
    fun damagedSportsLotteryOcrWithMissingPlusKeepsMultiPeriodDraft() {
        val result =
            parser.parse(
                OcrDocument(
                    imageId = TEST_IMAGE_ID,
                    engineName = TEST_ENGINE_NAME,
                    lines =
                        listOf(
                            line(
                                "体彩 超级东迭",
                                left = 0.18f,
                                top = 0.08f,
                                width = 0.46f,
                                confidence = 0.82f,
                            ),
                            line("第26999期", left = 0.16f, top = 0.18f),
                            line("单式票", left = 0.12f, top = 0.28f, width = 0.12f),
                            line("10}1f%", left = 0.38f, top = 0.28f, width = 0.11f),
                            line("合计20元", left = 0.66f, top = 0.28f, width = 0.15f),
                            line(
                                "① 01 07 14 22 35",
                                left = 0.12f,
                                top = 0.38f,
                                width = 0.40f,
                                confidence = 0.65f,
                            ),
                            line("03", left = 0.68f, top = 0.38f, width = 0.04f, confidence = 0.55f),
                            line("11", left = 0.77f, top = 0.38f, width = 0.04f, confidence = 0.60f),
                        ),
                ),
            )

        val review = assertIs<TicketParseResult.ReadyForReview>(result)
        val draft = review.draft
        assertEquals(LotteryType.SUPER_LOTTO, draft.lotteryType)
        assertEquals("26999", draft.issue)
        assertEquals(listOf(1, 7, 14, 22, 35), draft.betLines.single().primaryNumbers)
        assertEquals(listOf(3, 11), draft.betLines.single().secondaryNumbers)
        assertFalse(draft.betLines.single().isAdditional ?: true)
        assertEquals(1, draft.multiplier)
        assertEquals(10, draft.periodCount)
        assertEquals(2_000L, draft.paidAmountFen)
        assertEquals(
            0.55f,
            review.fieldRegions.single { it.field == TicketFieldReference.LotteryType }.rawConfidence,
        )
        assertEquals(
            TEST_CONFIDENCE,
            review.fieldRegions.single { it.field == TicketFieldReference.PeriodCount }.rawConfidence,
        )
    }

    /** 期、倍单位被误成连续数字时，只在金额关系能够排除其他解释后恢复字段。 */
    @Test
    fun concatenatedSummaryDigitsUseExactAmountToRecoverUniqueValues() {
        val result =
            parser.parse(
                document(
                    "超级大乐透",
                    "第26999期",
                    "单式票 1011 % 合计20元",
                    "① 01 07 14 22 35 + 03 11",
                ),
            )

        val draft = assertIs<TicketParseResult.ReadyForReview>(result).draft
        assertEquals(10, draft.periodCount)
        assertEquals(1, draft.multiplier)
    }

    /** 受损数字串存在两种同额解释时必须保持未知，不能凭排列偏好猜测。 */
    @Test
    fun ambiguousConcatenatedSummaryDigitsRemainUnknown() {
        val result =
            parser.parse(
                document(
                    "超级大乐透",
                    "第26999期",
                    "单式票 1010 % 合计20元",
                    "① 01 07 14 22 35 + 03 11",
                ),
            )

        val correction = assertIs<TicketParseResult.NeedsCorrection>(result)
        assertNull(assertNotNull(correction.draft).multiplier)
    }

    /** 多个明确期数互相冲突时应保留彩种与期数证据，但不得继续生成草稿。 */
    @Test
    fun conflictingPeriodCountsKeepEvidenceWithoutDraft() {
        val result =
            parser.parse(
                document(
                    "超级大乐透",
                    "第26999期",
                    "单式票 2期1倍 合计4元",
                    "单式票 3期1倍 总计6元",
                    "① 01 07 14 22 35 + 03 11",
                ),
            )

        val correction = assertIs<TicketParseResult.NeedsCorrection>(result)
        assertTrue(correction.message.contains("互相冲突的投注期数"))
        assertNull(correction.draft)
        assertEquals(
            listOf(TicketFieldReference.LotteryType, TicketFieldReference.PeriodCount),
            correction.fieldRegions.map { it.field },
        )
        assertTrue(correction.fieldRegions.all { it.rawConfidence == TEST_CONFIDENCE })
    }

    /** 受损摘要即使能由金额唯一解释，也不能覆盖票面已有的明确倍数冲突。 */
    @Test
    fun damagedSummaryDoesNotOverrideConflictingMultiplierEvidence() {
        val result =
            parser.parse(
                document(
                    "超级大乐透",
                    "第26999期",
                    "单式票 1011 % 合计20元",
                    "1倍",
                    "2倍",
                    "① 01 07 14 22 35 + 03 11",
                ),
            )

        val correction = assertIs<TicketParseResult.NeedsCorrection>(result)
        assertNull(assertNotNull(correction.draft).multiplier)
    }

    /** 侧边纵排发行机构文字跨越多注号码时，不得把这些号码错误合并成一行。 */
    @Test
    fun verticalIssuerTextDoesNotMergeMultipleBetRows() {
        val result =
            parser.parse(
                OcrDocument(
                    imageId = TEST_IMAGE_ID,
                    engineName = TEST_ENGINE_NAME,
                    lines =
                        listOf(
                            line("体彩超级东遷", left = 0.206f, top = 0.122f, width = 0.516f, height = 0.103f),
                            line("第 26999期", left = 0.177f, top = 0.207f, width = 0.143f, height = 0.038f),
                            line("单式票", left = 0.177f, top = 0.306f, width = 0.084f, height = 0.034f),
                            line("10}1f%", left = 0.407f, top = 0.314f, width = 0.093f, height = 0.031f),
                            line("合计100元", left = 0.637f, top = 0.320f, width = 0.117f, height = 0.027f),
                            line(") 03 11 24 31 34", left = 0.224f, top = 0.342f, width = 0.312f, height = 0.034f),
                            line("02 10", left = 0.650f, top = 0.350f, width = 0.097f, height = 0.029f),
                            line("长中国体育彩票", left = 0.800f, top = 0.366f, width = 0.034f, height = 0.196f),
                            line("2 05 13 18 29 35", left = 0.197f, top = 0.378f, width = 0.339f, height = 0.032f),
                            line("04 09", left = 0.639f, top = 0.386f, width = 0.097f, height = 0.023f),
                            line("06 12 19 27 33", left = 0.259f, top = 0.411f, width = 0.277f, height = 0.033f),
                            line("03", left = 0.639f, top = 0.416f, width = 0.036f, height = 0.028f),
                            line("08", left = 0.715f, top = 0.415f, width = 0.032f, height = 0.028f),
                            line("4 07 15 22 28 34", left = 0.196f, top = 0.446f, width = 0.340f, height = 0.030f),
                            line("01", left = 0.639f, top = 0.450f, width = 0.039f, height = 0.026f),
                            line("11", left = 0.701f, top = 0.449f, width = 0.038f, height = 0.025f),
                            line("9 08 16 23 30 35", left = 0.196f, top = 0.481f, width = 0.340f, height = 0.030f),
                            line("05", left = 0.640f, top = 0.484f, width = 0.039f, height = 0.023f),
                            line("12", left = 0.702f, top = 0.484f, width = 0.036f, height = 0.023f),
                        ),
                ),
            )

        val review = assertIs<TicketParseResult.ReadyForReview>(result)
        val draft = review.draft
        assertEquals(LotteryType.SUPER_LOTTO, draft.lotteryType)
        assertEquals("26999", draft.issue)
        assertEquals(5, draft.betLines.size)
        assertEquals(listOf(3, 11, 24, 31, 34), draft.betLines[0].primaryNumbers)
        assertEquals(listOf(2, 10), draft.betLines[0].secondaryNumbers)
        assertEquals(listOf(8, 16, 23, 30, 35), draft.betLines[4].primaryNumbers)
        assertEquals(listOf(5, 12), draft.betLines[4].secondaryNumbers)
        assertEquals(1, draft.multiplier)
        assertEquals(10, draft.periodCount)
        assertEquals(10_000L, draft.paidAmountFen)
    }

    /** 加号缺失且没有明显主次区间隔时不得仅凭七个号码猜测票型。 */
    @Test
    fun missingPlusWithoutPositionGapRequiresCorrection() {
        val result =
            parser.parse(
                document(
                    "中国体育彩票",
                    "玩法：超级东迭-单式",
                    "第26999期",
                    "单式票 1倍 合计2元",
                    "① 01 07 14 22 35 03 11",
                ),
            )

        assertIs<TicketParseResult.NeedsCorrection>(result)
    }

    /** 双色球单式多注版式应读取每行统一倍数并保留票面顺序。 */
    @Test
    fun doubleColorBallRowsWithPerLineMultiplierAreParsed() {
        val result =
            parser.parse(
                document(
                    "中国福利彩票",
                    "玩法：双色球-单式",
                    "A. 01 06 11 18 25 33 + 02 x1",
                    "B. 03 08 13 20 27 31 + 05 x1",
                    "C. 04 09 14 21 28 30 + 07 x1",
                    "D. 05 10 15 22 29 32 + 09 x1",
                    "E. 02 07 12 19 24 26 + 11 x1",
                    "开奖期：2026999 26-12-31",
                    "销售期：2026999-42",
                    "合计：10元",
                ),
            )

        val draft = assertIs<TicketParseResult.ReadyForReview>(result).draft
        assertEquals(LotteryType.DOUBLE_COLOR_BALL, draft.lotteryType)
        assertEquals("2026999", draft.issue)
        assertEquals(5, draft.betLines.size)
        assertEquals(listOf(1, 6, 11, 18, 25, 33), draft.betLines.first().primaryNumbers)
        assertEquals(listOf(2), draft.betLines.first().secondaryNumbers)
        assertFalse(draft.betLines.first().isAdditional ?: true)
        assertEquals(1, draft.multiplier)
        assertEquals(1_000L, draft.paidAmountFen)
    }

    /** 双色球两位数行倍数不能被误解析为蓝球号码。 */
    @Test
    fun doubleDigitRowMultiplierIsNotParsedAsBlueNumber() {
        val result =
            parser.parse(
                document(
                    "玩法：双色球-单式",
                    "A. 01 06 11 18 25 33 + 02 x10",
                    "开奖期：2026999",
                    "合计：20元",
                ),
            )

        val draft = assertIs<TicketParseResult.ReadyForReview>(result).draft
        assertEquals(listOf(2), draft.betLines.single().secondaryNumbers)
        assertEquals(10, draft.multiplier)
        assertEquals(2_000L, draft.paidAmountFen)
    }

    /** 双色球标题损坏时，福利彩票机构与唯一合法 `6+1` 结构可联合确定彩种。 */
    @Test
    fun welfareIssuerAndStrictSixPlusOneStructureRecoverDamagedTitle() {
        val result =
            parser.parse(
                document(
                    "中国福利彩票",
                    "玩法：双球-单式",
                    "A. 01 06 11 18 25 33 + 02 x1",
                    "开奖期：2026999",
                    "合计：2元",
                ),
            )

        val draft = assertIs<TicketParseResult.ReadyForReview>(result).draft
        assertEquals(LotteryType.DOUBLE_COLOR_BALL, draft.lotteryType)
    }

    /** 大乐透标题损坏时，中国体育彩票机构与唯一合法 `5+2` 结构可联合确定彩种。 */
    @Test
    fun sportsIssuerAndStrictFivePlusTwoStructureRecoverDamagedTitle() {
        val result =
            parser.parse(
                document(
                    "中国体育彩票",
                    "玩法：超级大乐投-单式",
                    "第26999期",
                    "单式票 1倍 合计2元",
                    "① 01 07 14 22 35 + 03 11",
                ),
            )

        val draft = assertIs<TicketParseResult.ReadyForReview>(result).draft
        assertEquals(LotteryType.SUPER_LOTTO, draft.lotteryType)
    }

    /** 只有合法号码结构而没有标题或发行机构时不得推断彩种。 */
    @Test
    fun numberStructureWithoutIssuerRequiresCorrection() {
        val result =
            parser.parse(
                document(
                    "玩法：双球-单式",
                    "A. 01 06 11 18 25 33 + 02 x1",
                    "开奖期：2026999",
                    "合计：2元",
                ),
            )

        assertIs<TicketParseResult.NeedsCorrection>(result)
    }

    /** 发行机构与号码结构指向不同彩种时不得选择任意一方。 */
    @Test
    fun issuerAndNumberStructureConflictRequiresCorrection() {
        val result =
            parser.parse(
                document(
                    "中国福利彩票",
                    "玩法：大乐投-单式",
                    "① 01 07 14 22 35 + 03 11",
                ),
            )

        val correction = assertIs<TicketParseResult.NeedsCorrection>(result)
        assertTrue(correction.message.contains("不一致"))
    }

    /** 同时出现福利彩票和体育彩票机构时，即使号码结构唯一也必须人工核对。 */
    @Test
    fun conflictingIssuersRequireCorrection() {
        val result =
            parser.parse(
                document(
                    "中国福利彩票",
                    "中国体育彩票",
                    "玩法：双球-单式",
                    "A. 01 06 11 18 25 33 + 02 x1",
                ),
            )

        val correction = assertIs<TicketParseResult.NeedsCorrection>(result)
        assertTrue(correction.message.contains("发行机构"))
    }

    /** 标题缺失时混合出现 `6+1` 与 `5+2` 号码行不得推断彩种。 */
    @Test
    fun mixedBetStructuresRequireCorrection() {
        val result =
            parser.parse(
                document(
                    "中国福利彩票",
                    "玩法：双球-单式",
                    "A. 01 06 11 18 25 33 + 02 x1",
                    "B. 01 07 14 22 35 + 03 11",
                ),
            )

        val correction = assertIs<TicketParseResult.NeedsCorrection>(result)
        assertTrue(correction.message.contains("结构"))
    }

    /** 标题缺失时残缺的号码行不能与发行机构组成有效证据。 */
    @Test
    fun incompleteBetStructureRequiresCorrection() {
        val result =
            parser.parse(
                document(
                    "中国福利彩票",
                    "玩法：双球-单式",
                    "A. 01 06 11 18 25 + 02 x1",
                ),
            )

        val correction = assertIs<TicketParseResult.NeedsCorrection>(result)
        assertTrue(correction.message.contains("结构"))
    }

    /** 精确标题存在但号码行不完整时，只保留安全非号码证据且不得伪造默认单期期数区域。 */
    @Test
    fun incompleteBetRowsKeepSafeNonBetEvidenceWithoutPeriodRegion() {
        val result =
            parser.parse(
                OcrDocument(
                    imageId = TEST_IMAGE_ID,
                    engineName = TEST_ENGINE_NAME,
                    lines =
                        listOf(
                            line("玩法：双色球-单式", 0.15f, 0.05f, 0.36f, confidence = 0.91f),
                            line("中国福利彩票", 0.10f, 0.12f, 0.70f, confidence = 0.80f),
                            line("开奖期：2026999", 0.10f, 0.19f, 0.25f, confidence = 0.88f),
                            line("A. 01 06 11 18 25 33 + 02 x1", 0.10f, 0.26f, 0.70f),
                            line("B. 02 08 15 23 34 + 04 x1", 0.10f, 0.33f, 0.65f),
                            line("合计：2元", 0.65f, 0.40f, 0.20f, confidence = 0.77f),
                        ),
                ),
            )

        val correction = assertIs<TicketParseResult.NeedsCorrection>(result)
        assertTrue(correction.message.contains("投注号码不完整"))
        assertNull(correction.draft)
        assertEquals(
            listOf(
                TicketFieldReference.LotteryType,
                TicketFieldReference.Issue,
                TicketFieldReference.PaidAmount,
            ),
            correction.fieldRegions.map { it.field },
        )
        val lotteryTypeRegion = correction.fieldRegions.first()
        assertEquals(NormalizedBounds(0.15f, 0.05f, 0.51f, 0.08f), lotteryTypeRegion.bounds)
        assertEquals(0.91f, lotteryTypeRegion.rawConfidence)
        assertEquals(0.88f, correction.fieldRegions[1].rawConfidence)
        assertEquals(0.77f, correction.fieldRegions[2].rawConfidence)
    }

    /** 明确出现多期投注且字段完整时应直接进入人工确认。 */
    @Test
    fun explicitMultiplePeriodsKeepRecognizedDraft() {
        val result =
            parser.parse(
                document(
                    "超级大乐透",
                    "第26999期",
                    "单式票 10期1倍 合计20元",
                    "① 01 07 14 22 35 + 03 11",
                ),
            )

        val review = assertIs<TicketParseResult.ReadyForReview>(result)
        assertEquals(10, review.draft.periodCount)
        assertEquals(
            TEST_CONFIDENCE,
            review.fieldRegions.single { it.field == TicketFieldReference.PeriodCount }.rawConfidence,
        )
    }

    /** Vision 把一倍识别成字母 l 时仍应保留已明确识别的十期期数。 */
    @Test
    fun letterOneConfusionDoesNotHideRecognizedPeriodCount() {
        val result =
            parser.parse(
                document(
                    "超级大乐透",
                    "第26999期",
                    "单式票 10期l倍 合计20元",
                    "① 01 07 14 22 35 + 03 11",
                ),
            )

        val correction = assertIs<TicketParseResult.NeedsCorrection>(result)
        val draft = assertNotNull(correction.draft)
        assertEquals(10, draft.periodCount)
        assertNull(draft.multiplier)
    }

    /** 补打票即使没有明确期数也必须优先阻断。 */
    @Test
    fun reprintedTicketIsUnsupported() {
        val result =
            parser.parse(
                document(
                    "超级大乐透",
                    "第26999期",
                    "单式票 1倍 合计10元",
                    "本彩票是多期投注兑奖后的补打票",
                ),
            )

        val unsupported = assertIs<TicketParseResult.Unsupported>(result)
        assertTrue(unsupported.message.contains("补打票"))
    }

    /** 号码中的字母 O 不得被静默替换为数字零。 */
    @Test
    fun letterInNumberRequiresCorrection() {
        val result =
            parser.parse(
                document(
                    "超级大乐透",
                    "第26999期",
                    "单式票 1倍 合计2元",
                    "① O1 07 14 22 35 + 03 11",
                ),
            )

        val correction = assertIs<TicketParseResult.NeedsCorrection>(result)
        assertTrue(correction.message.contains("号码"))
    }

    /** 投注号码越界时不得通过排序或裁剪修复。 */
    @Test
    fun outOfRangeNumberRequiresCorrection() {
        val result =
            parser.parse(
                document(
                    "超级大乐透",
                    "第26999期",
                    "单式票 1倍 合计2元",
                    "① 01 07 14 22 36 + 03 11",
                ),
            )

        val correction = assertIs<TicketParseResult.NeedsCorrection>(result)
        assertTrue(correction.message.contains("越界"))
    }

    /** 玩法标题明确为复式时应阻断，不能按号码数量尝试降级为单式。 */
    @Test
    fun complexPlayIsUnsupported() {
        val result =
            parser.parse(
                document(
                    "玩法：双色球-复式",
                    "开奖期：2026999",
                    "合计：20元",
                ),
            )

        val unsupported = assertIs<TicketParseResult.Unsupported>(result)
        assertTrue(unsupported.message.contains("复式"))
    }

    /** 广告中的“复式”文字不能把明确单式票误判成复杂玩法。 */
    @Test
    fun promotionalComplexPlayTextDoesNotBlockSingleTicket() {
        val result =
            parser.parse(
                document(
                    "超级大乐透",
                    "第26999期",
                    "单式票 1倍 合计2元",
                    "① 01 07 14 22 35 + 03 11",
                    "选复式有优势",
                ),
            )

        assertIs<TicketParseResult.ReadyForReview>(result)
    }

    /** 期号缺失但票型和投注结构完整时应携带草稿进入人工校正。 */
    @Test
    fun missingIssueProvidesRecoverableDraft() {
        val result =
            parser.parse(
                document(
                    "超级大乐透",
                    "单式票 1倍 合计2元",
                    "① 01 07 14 22 35 + 03 11",
                ),
            )

        val correction = assertIs<TicketParseResult.NeedsCorrection>(result)
        val draft = assertNotNull(correction.draft)
        assertTrue(correction.message.contains("期号"))
        assertEquals("", draft.issue)
        assertEquals(1, draft.betLines.size)
        assertEquals(200L, draft.paidAmountFen)
    }

    /** 金额缺失但其他关键字段可靠时应保留已解析字段供用户补充。 */
    @Test
    fun missingAmountProvidesRecoverableDraft() {
        val result =
            parser.parse(
                document(
                    "超级大乐透",
                    "第26999期",
                    "单式票 1倍",
                    "① 01 07 14 22 35 + 03 11",
                ),
            )

        val correction = assertIs<TicketParseResult.NeedsCorrection>(result)
        val draft = assertNotNull(correction.draft)
        assertTrue(correction.message.contains("金额"))
        assertEquals("26999", draft.issue)
        assertNull(draft.paidAmountFen)
        assertFalse(correction.fieldRegions.any { it.field == TicketFieldReference.PaidAmount })
        assertEquals(
            TEST_CONFIDENCE,
            correction.fieldRegions.single { it.field == TicketFieldReference.Issue }.rawConfidence,
        )
        assertTrue(correction.fieldRegions.any { it.field == TicketFieldReference.BetLine(0) })
    }

    /** 金额与号码、倍数不一致时应保留票面值供人工核对，不能反推并覆盖。 */
    @Test
    fun inconsistentAmountRequiresCorrection() {
        val result =
            parser.parse(
                document(
                    "超级大乐透",
                    "第26999期",
                    "单式票 1倍 合计20元",
                    "① 01 07 14 22 35 + 03 11",
                ),
            )

        val correction = assertIs<TicketParseResult.NeedsCorrection>(result)
        assertTrue(correction.message.contains("金额"))
        assertEquals(2_000L, assertNotNull(correction.draft).paidAmountFen)
    }

    /** 多个期号可能来自不同彩票，只提供候选并转为空白原图辅助录入。 */
    @Test
    fun conflictingIssuesProvideExplicitCandidates() {
        val result =
            parser.parse(
                document(
                    "超级大乐透",
                    "第26999期 第26998期",
                    "单式票 1倍 合计2元",
                    "① 01 07 14 22 35 + 03 11",
                ),
            )

        val correction = assertIs<TicketParseResult.NeedsCorrection>(result)
        assertTrue(correction.message.contains("多个开奖期号"))
        assertNull(correction.draft)
        assertEquals(
            listOf(TicketFieldCandidate.Issue("26999"), TicketFieldCandidate.Issue("26998")),
            correction.fieldCandidates,
        )
        assertEquals(
            TEST_CONFIDENCE,
            correction.fieldRegions.single { it.field == TicketFieldReference.Issue }.rawConfidence,
        )
    }

    /** 多个合计金额不得按理论金额猜测，只提供候选并转为空白原图辅助录入。 */
    @Test
    fun conflictingAmountsProvideExplicitCandidates() {
        val result =
            parser.parse(
                document(
                    "超级大乐透",
                    "第26999期",
                    "单式票 1倍 合计2元",
                    "总计4元",
                    "① 01 07 14 22 35 + 03 11",
                ),
            )

        val correction = assertIs<TicketParseResult.NeedsCorrection>(result)
        assertTrue(correction.message.contains("多个票面合计金额"))
        assertNull(correction.draft)
        assertEquals(
            listOf(TicketFieldCandidate.PaidAmount(200L), TicketFieldCandidate.PaidAmount(400L)),
            correction.fieldCandidates,
        )
        assertEquals(
            TEST_CONFIDENCE,
            correction.fieldRegions.single { it.field == TicketFieldReference.PaidAmount }.rawConfidence,
        )
    }

    /** 倍数无法确定时应保留空值进入校正，但明确的单式票仍可确定为基本投注。 */
    @Test
    fun missingMultiplierProvidesUnconfirmedDraft() {
        val result =
            parser.parse(
                document(
                    "超级大乐透",
                    "第26999期",
                    "单式票 合计2元",
                    "① 01 07 14 22 35 + 03 11",
                ),
            )

        val correction = assertIs<TicketParseResult.NeedsCorrection>(result)
        assertTrue(correction.message.contains("倍数"))
        val draft = assertNotNull(correction.draft)
        assertNull(draft.multiplier)
        assertFalse(draft.betLines.single().isAdditional ?: true)
    }

    /** 多个倍数候选应保留空值和候选区域进入校正。 */
    @Test
    fun conflictingMultipliersProvideUnconfirmedDraft() {
        val result =
            parser.parse(
                document(
                    "超级大乐透",
                    "第26999期",
                    "单式票 1倍 合计2元",
                    "2倍",
                    "① 01 07 14 22 35 + 03 11",
                ),
            )

        val correction = assertIs<TicketParseResult.NeedsCorrection>(result)
        assertTrue(correction.message.contains("倍数"))
        val draft = assertNotNull(correction.draft)
        assertNull(draft.multiplier)
        assertFalse(draft.betLines.single().isAdditional ?: true)
        assertTrue(correction.fieldRegions.any { it.field == TicketFieldReference.Multiplier })
    }

    /** 大乐透追加属性存在冲突时应保留空值进入校正，不能默认成基本投注。 */
    @Test
    fun conflictingAdditionalStateDoesNotProvideDraft() {
        val result =
            parser.parse(
                document(
                    "超级大乐透",
                    "第26999期",
                    "单式票 1倍 合计3元",
                    "追加投注 1倍",
                    "① 01 07 14 22 35 + 03 11",
                ),
            )

        val correction = assertIs<TicketParseResult.NeedsCorrection>(result)
        assertTrue(correction.message.contains("追加属性"))
        val draft = assertNotNull(correction.draft)
        assertNull(draft.betLines.single().isAdditional)
        assertEquals(1, draft.multiplier)
        assertTrue(correction.fieldRegions.any { it.field == TicketFieldReference.Additional })
    }

    /** 同一号码区域非升序时必须回到人工核对，不能静默重排。 */
    @Test
    fun unsortedNumbersRequireCorrection() {
        val result =
            parser.parse(
                document(
                    "超级大乐透",
                    "第26999期",
                    "单式票 1倍 合计2元",
                    "① 07 01 14 22 35 + 03 11",
                ),
            )

        val correction = assertIs<TicketParseResult.NeedsCorrection>(result)
        assertTrue(correction.message.contains("顺序"))
    }

    /** 用固定纵向间隔生成一份不含真实流水号和二维码内容的 OCR 文档。 */
    private fun document(vararg texts: String): OcrDocument =
        OcrDocument(
            imageId = TEST_IMAGE_ID,
            engineName = TEST_ENGINE_NAME,
            lines = texts.mapIndexed { index, text -> line(text, left = 0.10f, top = 0.05f + index * 0.07f) },
        )

    /** 创建一条带稳定归一化位置的 OCR 行。 */
    private fun line(
        text: String,
        left: Float,
        top: Float,
        width: Float = 0.18f,
        height: Float = 0.03f,
        confidence: Float? = TEST_CONFIDENCE,
    ): OcrTextLine =
        OcrTextLine(
            text = text,
            bounds =
                NormalizedBounds(
                    left = left,
                    top = top,
                    right = (left + width).coerceAtMost(0.98f),
                    bottom = (top + height).coerceAtMost(0.98f),
                ),
            confidence = confidence,
        )

    /** 测试共用的解析器和脱敏 OCR 元数据。 */
    private companion object {
        /** 待测试的保守解析器。 */
        val parser = ConservativeTicketParser()

        /** 不对应真实图片文件名的测试标识。 */
        const val TEST_IMAGE_ID = "ticket-parser-fixture"

        /** 不对应生产引擎版本的测试名称。 */
        const val TEST_ENGINE_NAME = "脱敏最小夹具"

        /** 测试行使用的固定原始置信度。 */
        const val TEST_CONFIDENCE = 0.9f
    }
}

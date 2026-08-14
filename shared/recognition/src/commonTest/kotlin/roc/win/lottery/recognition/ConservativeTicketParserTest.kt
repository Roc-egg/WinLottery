package roc.win.lottery.recognition

import roc.win.lottery.domain.LotteryType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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

        val draft = assertIs<TicketParseResult.ReadyForReview>(result).draft
        assertEquals(LotteryType.SUPER_LOTTO, draft.lotteryType)
        assertEquals("26999", draft.issue)
        assertEquals(5, draft.betLines.size)
        assertEquals(listOf(1, 7, 14, 22, 35), draft.betLines.first().primaryNumbers)
        assertEquals(listOf(3, 11), draft.betLines.first().secondaryNumbers)
        assertFalse(draft.betLines.first().isAdditional ?: true)
        assertEquals(1, draft.multiplier)
        assertEquals(1, draft.periodCount)
        assertEquals(1_000L, draft.paidAmountFen)
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
                            line("大乐透", left = 0.35f, top = 0.08f),
                            line("体彩", left = 0.10f, top = 0.08f),
                            line("第26999期", left = 0.10f, top = 0.18f),
                            line("单式票", left = 0.10f, top = 0.28f),
                            line("追加投注", left = 0.35f, top = 0.28f),
                            line("1期1倍", left = 0.62f, top = 0.28f),
                            line("合计3元", left = 0.78f, top = 0.28f),
                            line("① 01 07 14 22 35", left = 0.10f, top = 0.38f),
                            line("+ 03 11", left = 0.70f, top = 0.38f),
                        ),
                ),
            )

        val draft = assertIs<TicketParseResult.ReadyForReview>(result).draft
        assertTrue(draft.betLines.single().isAdditional == true)
        assertEquals(300L, draft.paidAmountFen)
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

    /** 明确出现多期投注时应在号码解析前阻断。 */
    @Test
    fun explicitMultiplePeriodsAreUnsupported() {
        val result =
            parser.parse(
                document(
                    "超级大乐透",
                    "第26999期",
                    "单式票 10期1倍 合计100元",
                ),
            )

        val unsupported = assertIs<TicketParseResult.Unsupported>(result)
        assertTrue(unsupported.message.contains("10期"))
    }

    /** Vision 把一倍识别成字母 l 时仍应先识别并阻断十期投注。 */
    @Test
    fun letterOneConfusionDoesNotHideMultiplePeriods() {
        val result =
            parser.parse(
                document(
                    "超级大乐透",
                    "第26999期",
                    "追加投注10期l倍",
                ),
            )

        val unsupported = assertIs<TicketParseResult.Unsupported>(result)
        assertTrue(unsupported.message.contains("10期"))
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

    /** 金额与号码、倍数不一致时应要求核对，不能反推并覆盖票面字段。 */
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
    ): OcrTextLine =
        OcrTextLine(
            text = text,
            bounds =
                NormalizedBounds(
                    left = left,
                    top = top,
                    right = (left + 0.18f).coerceAtMost(0.98f),
                    bottom =
                        top + 0.03f,
                ),
            confidence = TEST_CONFIDENCE,
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

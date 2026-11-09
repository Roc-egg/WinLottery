package roc.win.lottery.recognition

import roc.win.lottery.domain.LotteryType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 快乐8 单式票的识别解析测试；号码为虚构值，版式按福彩终端票面整理。 */
class Happy8TicketParserTest {
    /** 玩法标题、注序字母行、行尾倍数、开奖期和合计金额齐全时直接进入核对。 */
    @Test
    fun singleTicketIsReadyForReview() {
        val result =
            parser.parse(
                document(
                    "中国福利彩票",
                    "元法：中彩快乐8-选五单式(赠票)",
                    "1234-ABCD-5678-EF01-2345/12345678/0A1B2",
                    "A.03 11 25 40 77 (1)",
                    "B.19 05 33 58 80 (1)",
                    "开奖期:2026123 26-05-03",
                    "合计:4元",
                    "销售期:2026123-5",
                    "深圳市福利彩票发行中心承销",
                ),
            )

        val review = assertIs<TicketParseResult.ReadyForReview>(result)
        val draft = review.draft
        assertEquals(LotteryType.HAPPY_8, draft.lotteryType)
        assertEquals("2026123", draft.issue)
        assertEquals(
            listOf(listOf(3, 11, 25, 40, 77), listOf(5, 19, 33, 58, 80)),
            draft.betLines.map { it.primaryNumbers },
        )
        assertTrue(draft.betLines.all { it.secondaryNumbers.isEmpty() && it.isAdditional == false })
        assertEquals(1, draft.multiplier)
        assertEquals(1, draft.periodCount)
        assertEquals(400L, draft.paidAmountFen)
        assertEquals(
            listOf(
                TicketFieldReference.LotteryType,
                TicketFieldReference.Issue,
                TicketFieldReference.BetLine(0),
                TicketFieldReference.BetLine(1),
                TicketFieldReference.Multiplier,
                TicketFieldReference.PaidAmount,
            ),
            review.fieldRegions.map { it.field },
        )
    }

    /** 行尾括号倍数读不到时，由合计金额按每注 2 元反推倍数。 */
    @Test
    fun multiplierIsDerivedFromAmountWhenMarksAreMissing() {
        val draft =
            assertIs<TicketParseResult.ReadyForReview>(
                parser.parse(
                    document(
                        "玩法：中彩快乐8-选三单式",
                        "A.08 16 64",
                        "B.02 47 71",
                        "C.10 29 55",
                        "开奖期:2026124 26-05-04",
                        "合计:18元",
                    ),
                ),
            ).draft

        assertEquals(3, draft.betLines.size)
        assertEquals(3, draft.multiplier)
        assertEquals(1_800L, draft.paidAmountFen)
    }

    /** 号码个数与玩法不符或金额对不上时保留草稿并提示人工核对。 */
    @Test
    fun inconsistentLinesNeedCorrection() {
        val wrongCount =
            assertIs<TicketParseResult.NeedsCorrection>(
                parser.parse(
                    document(
                        "玩法：中彩快乐8-选五单式",
                        "A.03 11 25 40 (1)",
                        "B.05 19 33 58 80 (1)",
                        "开奖期:2026123 26-05-03",
                        "合计:4元",
                    ),
                ),
            )
        assertTrue("第 1 注" in wrongCount.message, wrongCount.message)
        assertEquals(2, wrongCount.draft?.betLines?.size)

        val wrongAmount =
            assertIs<TicketParseResult.NeedsCorrection>(
                parser.parse(
                    document(
                        "玩法：中彩快乐8-选五单式",
                        "A.03 11 25 40 77 (1)",
                        "B.05 19 33 58 80 (1)",
                        "开奖期:2026123 26-05-03",
                        "合计:6元",
                    ),
                ),
            )
        assertTrue("金额" in wrongAmount.message, wrongAmount.message)
    }

    /** 复式和胆拖需要另存选几，当前版本直接阻断。 */
    @Test
    fun compoundAndBankerTicketsAreUnsupported() {
        listOf("玩法：中彩快乐8-选五复式", "玩法：中彩快乐8-选五胆拖").forEach { title ->
            val result =
                parser.parse(
                    document(title, "A.03 11 25 40 77 79 (1)", "开奖期:2026123 26-05-03", "合计:12元"),
                )
            assertIs<TicketParseResult.Unsupported>(result, title)
        }
    }

    /** 双色球票底部的“快乐8选二选三…”广告不能把彩种识别成快乐8。 */
    @Test
    fun happy8AdvertisementOnOtherTicketIsIgnored() {
        val result =
            parser.parse(
                document(
                    "中国福利彩票",
                    "玩法：双色球-单式",
                    "A. 01 06 11 18 25 33 + 02 x1",
                    "开奖期：2026999 26-12-31",
                    "合计：2元",
                    "快乐8选二选三选五选六全中赠票大促销",
                ),
            )

        assertEquals(
            LotteryType.DOUBLE_COLOR_BALL,
            assertIs<TicketParseResult.ReadyForReview>(result).draft.lotteryType,
        )
    }

    /** 用固定纵向间隔生成 OCR 文档。 */
    private fun document(vararg texts: String): OcrDocument =
        OcrDocument(
            imageId = "happy8-parser-fixture",
            engineName = "脱敏最小夹具",
            lines =
                texts.mapIndexed { index, text ->
                    val top = 0.05f + index * 0.07f
                    OcrTextLine(
                        text = text,
                        bounds = NormalizedBounds(left = 0.10f, top = top, right = 0.80f, bottom = top + 0.03f),
                        confidence = 0.9f,
                    )
                },
        )

    /** 被测解析器。 */
    private val parser = ConservativeTicketParser()
}

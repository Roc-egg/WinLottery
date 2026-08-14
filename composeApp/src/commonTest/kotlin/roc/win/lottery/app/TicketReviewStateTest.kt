package roc.win.lottery.app

import roc.win.lottery.domain.BetLineDraft
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.TicketDraft
import roc.win.lottery.domain.TicketFieldOrigin
import roc.win.lottery.domain.TicketValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 移动端共用票面校正状态测试。 */
class TicketReviewStateTest {
    /** 未修改字段生成票据后必须保留 OCR 来源。 */
    @Test
    fun unchangedFieldsKeepOcrOrigin() {
        val evaluation = TicketReviewState.fromDraft(superLottoDraft()).evaluate(validator)

        assertTrue(evaluation.canConfirm)
        val ticket = assertNotNull(evaluation.ticket)
        assertEquals(TicketFieldOrigin.OCR, ticket.lotteryType.origin)
        assertEquals(TicketFieldOrigin.OCR, ticket.issue.origin)
        assertEquals(
            TicketFieldOrigin.OCR,
            ticket.betLines
                .single()
                .primaryNumbers.origin,
        )
        assertEquals(TicketFieldOrigin.OCR, ticket.paidAmountFen.origin)
    }

    /** 用户修改期号和号码后必须生成合法票据并记录用户来源。 */
    @Test
    fun userCorrectionsAreTrackedAndValidated() {
        val state =
            TicketReviewState
                .fromDraft(superLottoDraft())
                .applyAction(TicketReviewAction.ChangeIssue("26092"))
                .applyAction(TicketReviewAction.ToggleNumber(0, TicketNumberArea.PRIMARY, 2))
                .applyAction(TicketReviewAction.ToggleNumber(0, TicketNumberArea.PRIMARY, 3))

        val evaluation = state.evaluate(validator)

        assertTrue(evaluation.canConfirm)
        val ticket = assertNotNull(evaluation.ticket)
        assertEquals(TicketFieldOrigin.USER, ticket.issue.origin)
        assertEquals(
            TicketFieldOrigin.USER,
            ticket.betLines
                .single()
                .primaryNumbers.origin,
        )
        assertEquals(
            listOf(3, 7, 14, 21, 33),
            ticket.betLines
                .single()
                .primaryNumbers.value,
        )
    }

    /** 倍数变化造成金额不一致时必须阻断，使用推导金额后才能恢复。 */
    @Test
    fun calculatedAmountRepairsMultiplierMismatch() {
        val mismatched =
            TicketReviewState
                .fromDraft(superLottoDraft())
                .applyAction(TicketReviewAction.ChangeMultiplier(2))

        val invalidEvaluation = mismatched.evaluate(validator)
        assertFalse(invalidEvaluation.canConfirm)
        assertTrue(invalidEvaluation.problems.any { it.field == "paidAmountFen" })

        val repaired = mismatched.applyAction(TicketReviewAction.UseCalculatedAmount)
        val repairedEvaluation = repaired.evaluate(validator)

        assertTrue(repairedEvaluation.canConfirm)
        val ticket = assertNotNull(repairedEvaluation.ticket)
        assertEquals(600L, ticket.paidAmountFen.value)
        assertEquals(TicketFieldOrigin.DERIVED, ticket.paidAmountFen.origin)
    }

    /** 不完整金额输入必须形成明确问题，而不是构造默认金额。 */
    @Test
    fun incompleteAmountCannotBeConfirmed() {
        val evaluation =
            TicketReviewState
                .fromDraft(superLottoDraft())
                .applyAction(TicketReviewAction.ChangePaidAmount(""))
                .evaluate(validator)

        assertFalse(evaluation.canConfirm)
        assertTrue(evaluation.problems.any { it.field == "paidAmountFen" })
    }

    /** 切换到双色球后应能修正号码和金额并重新得到合法票据。 */
    @Test
    fun lotteryTypeChangeCanBeFullyCorrected() {
        val state =
            TicketReviewState
                .fromDraft(superLottoDraft())
                .applyAction(TicketReviewAction.ChangeLotteryType(LotteryType.DOUBLE_COLOR_BALL))
                .applyAction(TicketReviewAction.ChangeIssue("2026091"))
                .applyAction(TicketReviewAction.ToggleNumber(0, TicketNumberArea.PRIMARY, 1))
                .applyAction(TicketReviewAction.ToggleNumber(0, TicketNumberArea.SECONDARY, 9))
                .applyAction(TicketReviewAction.UseCalculatedAmount)

        val evaluation = state.evaluate(validator)

        assertTrue(evaluation.canConfirm)
        val ticket = assertNotNull(evaluation.ticket)
        assertEquals(LotteryType.DOUBLE_COLOR_BALL, ticket.lotteryType.value)
        assertEquals(
            listOf(1, 2, 7, 14, 21, 33),
            ticket.betLines
                .single()
                .primaryNumbers.value,
        )
        assertEquals(
            listOf(4),
            ticket.betLines
                .single()
                .secondaryNumbers.value,
        )
        assertFalse(
            ticket.betLines
                .single()
                .isAdditional.value,
        )
        assertEquals(200L, ticket.paidAmountFen.value)
    }

    /** 创建一张合法的大乐透追加草稿。 */
    private fun superLottoDraft(): TicketDraft =
        TicketDraft(
            lotteryType = LotteryType.SUPER_LOTTO,
            issue = "26091",
            betLines =
                listOf(
                    BetLineDraft(
                        primaryNumbers = listOf(2, 7, 14, 21, 33),
                        secondaryNumbers = listOf(4, 9),
                        isAdditional = true,
                        originalText = "02 07 14 21 33 + 04 09",
                    ),
                ),
            multiplier = 1,
            periodCount = 1,
            paidAmountFen = 300L,
        )

    /** 被测领域校验器。 */
    private companion object {
        /** 所有测试共用的无状态校验器。 */
        val validator = TicketValidator()
    }
}

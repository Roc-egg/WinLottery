package roc.win.lottery.persistence

import roc.win.lottery.domain.BetLine
import roc.win.lottery.domain.ConfirmedTicket
import roc.win.lottery.domain.ConfirmedValue
import roc.win.lottery.domain.DrawPolicy
import roc.win.lottery.domain.DrawResult
import roc.win.lottery.domain.DrawStatus
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.PrizeTier
import roc.win.lottery.domain.PrizeTierCodes
import roc.win.lottery.domain.SourceEvidence
import roc.win.lottery.domain.TicketFieldOrigin

/** 生成一期规则版本已覆盖、字段完整的大乐透或双色球测试开奖。 */
internal fun testDrawResult(
    lotteryType: LotteryType = LotteryType.SUPER_LOTTO,
    issue: String = if (lotteryType == LotteryType.SUPER_LOTTO) "26090" else "2026090",
    status: DrawStatus = DrawStatus.FINAL_PAYOUT,
    contentSha256: String = "a".repeat(64),
    revision: Int = 1,
): DrawResult =
    DrawResult(
        lotteryType = lotteryType,
        issue = Issue(issue),
        drawDate = "2026-08-12",
        primaryNumbers =
            if (lotteryType ==
                LotteryType.SUPER_LOTTO
            ) {
                listOf(2, 7, 14, 21, 33)
            } else {
                listOf(1, 5, 9, 17, 25, 33)
            },
        secondaryNumbers = if (lotteryType == LotteryType.SUPER_LOTTO) listOf(4, 9) else listOf(12),
        status = status,
        revision = revision,
        ruleVersion = if (lotteryType == LotteryType.SUPER_LOTTO) "DLT_2026_01" else "SSQ_2026_01",
        policy = DrawPolicy.STANDARD,
        prizeTiers =
            listOf(
                PrizeTier(PrizeTierCodes.FIRST, "一等奖", null, null, 0L, null, "---", null),
                PrizeTier(PrizeTierCodes.FIFTH, "五等奖", 30_000L, null, 12_345L, null, "300", null),
            ),
        evidence =
            SourceEvidence(
                sourceName = "测试官网",
                sourceUrl = "https://example.invalid/draw",
                fetchedAtEpochMillis = 1_787_689_800_000L,
                contentSha256 = contentSha256,
            ),
        supportingEvidence =
            listOf(
                SourceEvidence(
                    sourceName = "测试辅助数据面",
                    sourceUrl = "https://example.invalid/support",
                    fetchedAtEpochMillis = 1_787_689_800_000L,
                    contentSha256 = "b".repeat(64),
                ),
            ),
    )

/** 创建一张合法的大乐透单期票。 */
internal fun testTicket(): ConfirmedTicket =
    ConfirmedTicket(
        lotteryType = ConfirmedValue(LotteryType.SUPER_LOTTO, TicketFieldOrigin.USER),
        issue = ConfirmedValue(Issue("26091"), TicketFieldOrigin.USER),
        betLines =
            listOf(
                BetLine(
                    primaryNumbers = ConfirmedValue(listOf(2, 7, 14, 21, 33), TicketFieldOrigin.USER),
                    secondaryNumbers = ConfirmedValue(listOf(4, 9), TicketFieldOrigin.USER),
                    isAdditional = ConfirmedValue(false, TicketFieldOrigin.USER),
                    originalText = "",
                ),
            ),
        multiplier = ConfirmedValue(1, TicketFieldOrigin.USER),
        periodCount = ConfirmedValue(1, TicketFieldOrigin.DERIVED),
        paidAmountFen = ConfirmedValue(200L, TicketFieldOrigin.DERIVED),
    )

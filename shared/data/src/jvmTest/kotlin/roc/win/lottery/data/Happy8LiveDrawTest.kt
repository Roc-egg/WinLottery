package roc.win.lottery.data

import kotlinx.coroutines.test.runTest
import roc.win.lottery.domain.BetLine
import roc.win.lottery.domain.ConfirmedTicket
import roc.win.lottery.domain.ConfirmedValue
import roc.win.lottery.domain.DrawStatus
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryPrizeCalculator
import roc.win.lottery.domain.LotteryPrizeRules
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.PrizeCheckStatus
import roc.win.lottery.domain.PrizeTierCodes
import roc.win.lottery.domain.TicketFieldOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * 显式启用时联网核对快乐8 官网单期查询、交叉核对和多期期号查询。
 *
 * 默认不联网；只断言规范化字段，不保存或输出官网原始响应。
 */
class Happy8LiveDrawTest {
    /** 已开奖期次可得到 20 个号码、39 个奖级和辅助证据，选五中五按当期金额计奖。 */
    @Test
    fun explicitlyEnabledHappy8DrawMatchesOfficialSources() =
        runTest(timeout = 2.minutes) {
            if (System.getenv(ENABLE_ENVIRONMENT_VARIABLE) != "1") return@runTest
            OfficialDrawRepository().use { repository ->
                val result = repository.getDraw(LotteryType.HAPPY_8, Issue(ISSUE))
                val draw = assertIs<DrawQueryResult.Success>(result, result.toString()).drawResult
                assertEquals(DrawStatus.FINAL_PAYOUT, draw.status)
                assertEquals(20, draw.primaryNumbers.size)
                assertEquals(draw.primaryNumbers.sorted(), draw.primaryNumbers)
                assertTrue(draw.secondaryNumbers.isEmpty())
                assertEquals(LotteryPrizeRules.HAPPY_8_PRIZE_TIER_CODES, draw.prizeTiers.map { it.code })
                assertEquals(1, draw.supportingEvidence.size)

                val picks = draw.primaryNumbers.take(5)
                val ticket =
                    ConfirmedTicket(
                        lotteryType = confirmed(LotteryType.HAPPY_8),
                        issue = confirmed(Issue(ISSUE)),
                        betLines =
                            listOf(
                                BetLine(confirmed(picks), confirmed(emptyList()), confirmed(false), ""),
                            ),
                        multiplier = confirmed(1),
                        periodCount = confirmed(1),
                        paidAmountFen = confirmed(200L),
                    )
                val prize = LotteryPrizeCalculator().calculate(ticket, draw)
                assertEquals(PrizeCheckStatus.WIN, prize.status)
                assertEquals(PrizeTierCodes.happy8(5, 5), prize.lineResults.single().prizeTierCode)
                val tier = assertNotNull(draw.prizeTiers.firstOrNull { it.code == PrizeTierCodes.happy8(5, 5) })
                assertEquals(tier.singlePrizeFen, prize.estimatedPrizeFen)

                val issues = repository.getDrawnIssues(LotteryType.HAPPY_8, Issue(ISSUE), 3)
                assertEquals(listOf("2026294", "2026295", "2026296"), issues?.map { it.value })
            }
        }

    /** 构造用户确认来源的字段。 */
    private fun <T> confirmed(value: T): ConfirmedValue<T> = ConfirmedValue(value, TicketFieldOrigin.USER)

    /** 联网开关与固定期号。 */
    private companion object {
        /** 显式启用快乐8 联网核对的环境变量。 */
        const val ENABLE_ENVIRONMENT_VARIABLE = "WINLOTTERY_LIVE_HAPPY_8"

        /** 已开奖的固定期号。 */
        const val ISSUE = "2026294"
    }
}

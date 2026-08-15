package roc.win.lottery.data

import kotlinx.coroutines.test.runTest
import roc.win.lottery.domain.BetLine
import roc.win.lottery.domain.ConfirmedTicket
import roc.win.lottery.domain.ConfirmedValue
import roc.win.lottery.domain.DrawResult
import roc.win.lottery.domain.DrawStatus
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryPrizeCalculator
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.PrizeCheckStatus
import roc.win.lottery.domain.PrizeTierCodes
import roc.win.lottery.domain.TicketFieldOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 只在开发者显式启用时访问官网的低频单期 smoke 测试。 */
class OfficialDrawRepositoryLiveSmokeTest {
    /**
     * 分别查询一个大乐透和双色球期号，并验证规范化双源证据。
     *
     * 测试不打印、不缓存也不持久化官网原始响应；默认直接返回，不阻断离线 CI。
     */
    @Test
    fun explicitlyEnabledSingleIssueQueriesReturnVerifiedEvidence() =
        runTest {
            if (System.getenv(ENABLE_ENVIRONMENT_VARIABLE) != ENABLED_VALUE) return@runTest
            val superLottoIssue = requiredEnvironmentVariable(SUPER_LOTTO_ISSUE_ENVIRONMENT_VARIABLE)
            val doubleColorBallIssue = requiredEnvironmentVariable(DOUBLE_COLOR_BALL_ISSUE_ENVIRONMENT_VARIABLE)
            val repository = OfficialDrawRepository()

            val superLotto =
                repository
                    .getDraw(LotteryType.SUPER_LOTTO, Issue(superLottoIssue))
                    .requireSuccess("大乐透", superLottoIssue)
            val doubleColorBall =
                repository
                    .getDraw(LotteryType.DOUBLE_COLOR_BALL, Issue(doubleColorBallIssue))
                    .requireSuccess("双色球", doubleColorBallIssue)

            assertTrue(superLotto.status in VERIFIED_STATUSES)
            assertTrue(doubleColorBall.status in VERIFIED_STATUSES)
            listOf(superLotto, doubleColorBall).forEach { draw ->
                assertTrue(draw.evidence.contentSha256.matches(SHA_256_PATTERN))
                assertTrue(draw.supportingEvidence.isNotEmpty())
                assertTrue(draw.supportingEvidence.all { it.contentSha256.matches(SHA_256_PATTERN) })

                val prizeCheck = LotteryPrizeCalculator().calculate(winningTicket(draw), draw)
                assertEquals(PrizeCheckStatus.WIN, prizeCheck.status)
                assertEquals(PrizeTierCodes.FIRST, prizeCheck.lineResults.single().prizeTierCode)
            }
        }

    /** 取得成功结果；失败时只报告规范化状态和安全说明，不泄露官网正文。 */
    private fun DrawQueryResult.requireSuccess(
        lotteryName: String,
        issue: String,
    ): DrawResult =
        when (this) {
            is DrawQueryResult.Success -> drawResult
            is DrawQueryResult.Unavailable -> error("$lotteryName $issue 查询未就绪：$status，$message")
        }

    /** 使用规范化开奖号码在内存构造一张单倍基本投注头奖票。 */
    private fun winningTicket(draw: DrawResult): ConfirmedTicket =
        ConfirmedTicket(
            lotteryType = confirmed(draw.lotteryType),
            issue = confirmed(draw.issue),
            betLines =
                listOf(
                    BetLine(
                        primaryNumbers = confirmed(draw.primaryNumbers),
                        secondaryNumbers = confirmed(draw.secondaryNumbers),
                        isAdditional = confirmed(false),
                        originalText = "",
                    ),
                ),
            multiplier = confirmed(1),
            periodCount = confirmed(1),
            paidAmountFen = confirmed(SINGLE_BET_AMOUNT_FEN),
        )

    /** 创建标记为测试内存输入的已确认字段。 */
    private fun <T> confirmed(value: T): ConfirmedValue<T> = ConfirmedValue(value, TicketFieldOrigin.USER)

    /** 读取启用 smoke 时必须显式提供的期号变量。 */
    private fun requiredEnvironmentVariable(name: String): String =
        requireNotNull(System.getenv(name)) { "启用官网 smoke 时必须提供 $name" }

    /** Smoke 测试环境变量和断言常量。 */
    private companion object {
        /** 显式启用官网 smoke 的环境变量。 */
        const val ENABLE_ENVIRONMENT_VARIABLE = "WINLOTTERY_LIVE_SMOKE"

        /** smoke 开关的启用值。 */
        const val ENABLED_VALUE = "1"

        /** 大乐透 smoke 目标期号变量。 */
        const val SUPER_LOTTO_ISSUE_ENVIRONMENT_VARIABLE = "WINLOTTERY_DLT_ISSUE"

        /** 双色球 smoke 目标期号变量。 */
        const val DOUBLE_COLOR_BALL_ISSUE_ENVIRONMENT_VARIABLE = "WINLOTTERY_SSQ_ISSUE"

        /** 可以通过 smoke 的统一开奖状态。 */
        val VERIFIED_STATUSES = setOf(DrawStatus.FINAL_NUMBERS, DrawStatus.FINAL_PAYOUT)

        /** 小写十六进制 SHA-256 格式。 */
        val SHA_256_PATTERN = Regex("^[0-9a-f]{64}$")

        /** 两种彩票单倍基本投注金额，单位为分。 */
        const val SINGLE_BET_AMOUNT_FEN = 200L
    }
}

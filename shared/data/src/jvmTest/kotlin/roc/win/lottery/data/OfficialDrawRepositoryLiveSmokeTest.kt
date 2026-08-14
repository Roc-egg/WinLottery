package roc.win.lottery.data

import kotlinx.coroutines.test.runTest
import roc.win.lottery.domain.DrawStatus
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryType
import kotlin.test.Test
import kotlin.test.assertIs
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
                assertIs<DrawQueryResult.Success>(
                    repository.getDraw(LotteryType.SUPER_LOTTO, Issue(superLottoIssue)),
                ).drawResult
            val doubleColorBall =
                assertIs<DrawQueryResult.Success>(
                    repository.getDraw(LotteryType.DOUBLE_COLOR_BALL, Issue(doubleColorBallIssue)),
                ).drawResult

            assertTrue(superLotto.status in VERIFIED_STATUSES)
            assertTrue(doubleColorBall.status in VERIFIED_STATUSES)
            listOf(superLotto, doubleColorBall).forEach { draw ->
                assertTrue(draw.evidence.contentSha256.matches(SHA_256_PATTERN))
                assertTrue(draw.supportingEvidence.isNotEmpty())
                assertTrue(draw.supportingEvidence.all { it.contentSha256.matches(SHA_256_PATTERN) })
            }
        }

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
    }
}

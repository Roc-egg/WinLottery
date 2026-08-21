@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package roc.win.lottery.data

import kotlinx.coroutines.test.runTest
import platform.Foundation.NSProcessInfo
import roc.win.lottery.domain.DrawStatus
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** iOS 显式启用的双色球最新已发布期官网联网验收。 */
class IOSDoubleColorBallLiveTest {
    /** 使用 Darwin 系统网络栈验证目标期的主、详情 JSON 和双证据闭环。 */
    @Test
    fun explicitlyEnabledLatestIssueUsesOfficialNetwork() =
        runTest(timeout = LIVE_TEST_TIMEOUT) {
            val environment = NSProcessInfo.processInfo.environment
            if (environment[ENABLE_ENVIRONMENT_VARIABLE] as? String != ENABLED_VALUE) return@runTest
            check((environment[ACCEPTANCE_PROXY_PORT_ENVIRONMENT_VARIABLE] as? String).isNullOrBlank()) {
                "iOS 官网联网验收必须移除本地代理配置"
            }
            val client = createPlatformHttpClient()
            try {
                val repository = OfficialDrawRepository(httpClient = client)
                val targetIssue =
                    (environment[TARGET_ISSUE_ENVIRONMENT_VARIABLE] as? String)
                        ?.takeIf { it.isNotBlank() }
                        ?: DEFAULT_TARGET_ISSUE
                val result = repository.getDraw(LotteryType.DOUBLE_COLOR_BALL, Issue(targetIssue))
                val draw =
                    assertIs<DrawQueryResult.Success>(
                        result,
                        "iOS Darwin 网络栈未能形成双色球 $targetIssue 双证据：$result",
                    ).drawResult
                assertEquals(targetIssue, draw.issue.value)
                assertTrue(draw.status in VERIFIED_STATUSES)
                assertEquals(1, draw.supportingEvidence.size)
            } finally {
                client.close()
            }
        }

    /** 真实验收参数。 */
    private companion object {
        /** 显式启用 iOS 双色球联网闭环的环境变量。 */
        const val ENABLE_ENVIRONMENT_VARIABLE = "WINLOTTERY_IOS_LIVE_SSQ"

        /** 可选的双色球目标期号环境变量。 */
        const val TARGET_ISSUE_ENVIRONMENT_VARIABLE = "WINLOTTERY_IOS_SSQ_ISSUE"

        /** 联网验收必须保持为空的本地代理端口环境变量。 */
        const val ACCEPTANCE_PROXY_PORT_ENVIRONMENT_VARIABLE = "WINLOTTERY_IOS_ACCEPTANCE_PROXY_PORT"

        /** 验收变量启用值。 */
        const val ENABLED_VALUE = "1"

        /** 未传入目标期号时使用的最新已发布期。 */
        const val DEFAULT_TARGET_ISSUE = "2026096"

        /** 可以通过 smoke 的统一开奖状态。 */
        val VERIFIED_STATUSES = setOf(DrawStatus.FINAL_NUMBERS, DrawStatus.FINAL_PAYOUT)

        /** 真实验收最大时长。 */
        val LIVE_TEST_TIMEOUT = 1.minutes
    }
}

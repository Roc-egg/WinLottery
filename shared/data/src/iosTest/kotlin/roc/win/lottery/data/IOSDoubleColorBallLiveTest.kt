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
    /** 使用 Darwin 系统网络栈验证新核验两期的主、详情 JSON 和双证据闭环。 */
    @Test
    fun explicitlyEnabledLatestIssuesUseOfficialNetwork() =
        runTest(timeout = LIVE_TEST_TIMEOUT) {
            val environment = NSProcessInfo.processInfo.environment
            if (environment[ENABLE_ENVIRONMENT_VARIABLE] as? String != ENABLED_VALUE) return@runTest
            check((environment[ACCEPTANCE_PROXY_PORT_ENVIRONMENT_VARIABLE] as? String).isNullOrBlank()) {
                "iOS 官网联网验收必须移除本地代理配置"
            }
            val client = createPlatformHttpClient()
            try {
                val repository = OfficialDrawRepository(httpClient = client)
                TARGET_ISSUES.forEach { issue ->
                    val draw =
                        assertIs<DrawQueryResult.Success>(
                            repository.getDraw(LotteryType.DOUBLE_COLOR_BALL, Issue(issue)),
                            "iOS Darwin 网络栈未能形成双色球 $issue 双证据",
                        ).drawResult
                    assertEquals(issue, draw.issue.value)
                    assertTrue(draw.status in VERIFIED_STATUSES)
                    assertEquals(1, draw.supportingEvidence.size)
                }
            } finally {
                client.close()
            }
        }

    /** 真实验收参数。 */
    private companion object {
        /** 显式启用 iOS 双色球双期联网闭环的环境变量。 */
        const val ENABLE_ENVIRONMENT_VARIABLE = "WINLOTTERY_IOS_LIVE_SSQ"

        /** 联网验收必须保持为空的本地代理端口环境变量。 */
        const val ACCEPTANCE_PROXY_PORT_ENVIRONMENT_VARIABLE = "WINLOTTERY_IOS_ACCEPTANCE_PROXY_PORT"

        /** 验收变量启用值。 */
        const val ENABLED_VALUE = "1"

        /** 本轮逐期核验并放行的双色球期号。 */
        val TARGET_ISSUES = listOf("2026094", "2026095")

        /** 可以通过 smoke 的统一开奖状态。 */
        val VERIFIED_STATUSES = setOf(DrawStatus.FINAL_NUMBERS, DrawStatus.FINAL_PAYOUT)

        /** 双期真实验收最大时长。 */
        val LIVE_TEST_TIMEOUT = 1.minutes
    }
}

package roc.win.lottery.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import roc.win.lottery.domain.DrawStatus
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryType
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Android 显式启用的双色球最新已发布期官网联网验收。 */
@RunWith(AndroidJUnit4::class)
class AndroidDoubleColorBallLiveTest {
    /** 使用 Android 系统网络栈验证主、详情 JSON 和双证据闭环。 */
    @Test
    fun explicitlyEnabledLatestIssueUsesOfficialNetwork() =
        runBlocking {
            val arguments = InstrumentationRegistry.getArguments()
            if (arguments.getString(ENABLE_ARGUMENT) != ENABLED_VALUE) return@runBlocking
            val targetIssue = arguments.getString(TARGET_ISSUE_ARGUMENT) ?: DEFAULT_TARGET_ISSUE
            val client = createPlatformHttpClient()
            try {
                val repository = OfficialDrawRepository(httpClient = client)
                val draw =
                    assertIs<DrawQueryResult.Success>(
                        repository.getDraw(LotteryType.DOUBLE_COLOR_BALL, Issue(targetIssue)),
                        "Android 官网链路未能形成双色球 $targetIssue 双证据",
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
        /** 显式启用联网验收的 instrumentation 参数。 */
        const val ENABLE_ARGUMENT = "winlotteryLiveSsq"

        /** 验收参数启用值。 */
        const val ENABLED_VALUE = "1"

        /** 可选的双色球目标期号 instrumentation 参数。 */
        const val TARGET_ISSUE_ARGUMENT = "winlotterySsqIssue"

        /** 未传入目标期号时使用的最新已发布期。 */
        const val DEFAULT_TARGET_ISSUE = "2026096"

        /** 可以通过真实验收的统一开奖状态。 */
        val VERIFIED_STATUSES = setOf(DrawStatus.FINAL_NUMBERS, DrawStatus.FINAL_PAYOUT)
    }
}

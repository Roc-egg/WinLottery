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

/** Android 显式启用的大乐透历史 PDF 真实闭环验收。 */
@RunWith(AndroidJUnit4::class)
class AndroidHistoricalSuperLottoLiveTest {
    /** 使用公开历史期验证主 JSON、聚合回退、PDFBox 文本层和双证据结果。 */
    @Test
    fun explicitlyEnabledHistoricalIssueUsesOfficialPdf() =
        runBlocking {
            val arguments = InstrumentationRegistry.getArguments()
            if (arguments.getString(ENABLE_ARGUMENT) != ENABLED_VALUE) return@runBlocking
            val targetIssue = arguments.getString(TARGET_ISSUE_ARGUMENT) ?: DEFAULT_TARGET_ISSUE
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val client = createPlatformHttpClient()
            try {
                val repository =
                    OfficialDrawRepository(
                        httpClient = client,
                        superLottoPdfTextExtractor = AndroidSuperLottoPdfTextExtractor(context),
                    )
                val result = repository.getDraw(LotteryType.SUPER_LOTTO, Issue(targetIssue))
                val draw =
                    assertIs<DrawQueryResult.Success>(
                        result,
                        "Android 官网链路未能形成大乐透双证据：$result",
                    ).drawResult

                assertTrue(draw.status in VERIFIED_STATUSES)
                assertEquals(targetIssue, draw.issue.value)
                assertTrue(draw.supportingEvidence.isNotEmpty())
            } finally {
                client.close()
            }
        }

    /** 真实验收参数。 */
    private companion object {
        /** 显式启用联网验收的 instrumentation 参数。 */
        const val ENABLE_ARGUMENT = "winlotteryLivePdf"

        /** 验收参数启用值。 */
        const val ENABLED_VALUE = "1"

        /** 可选的大乐透目标期号 instrumentation 参数。 */
        const val TARGET_ISSUE_ARGUMENT = "winlotteryDltIssue"

        /** 未传入目标期号时使用的已对账公开历史期。 */
        const val DEFAULT_TARGET_ISSUE = "26090"

        /** 可以通过真实验收的统一开奖状态。 */
        val VERIFIED_STATUSES = setOf(DrawStatus.FINAL_NUMBERS, DrawStatus.FINAL_PAYOUT)
    }
}

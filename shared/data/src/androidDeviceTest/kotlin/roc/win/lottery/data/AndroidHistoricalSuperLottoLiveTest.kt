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

/** Android 显式启用的大乐透历史 PDF 真实闭环验收。 */
@RunWith(AndroidJUnit4::class)
class AndroidHistoricalSuperLottoLiveTest {
    /** 使用公开历史期验证主 JSON、聚合回退、PDFBox 文本层和双证据结果。 */
    @Test
    fun explicitlyEnabledHistoricalIssueUsesOfficialPdf() =
        runBlocking {
            val arguments = InstrumentationRegistry.getArguments()
            if (arguments.getString(ENABLE_ARGUMENT) != ENABLED_VALUE) return@runBlocking
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val client = createPlatformHttpClient()
            try {
                val repository =
                    OfficialDrawRepository(
                        httpClient = client,
                        superLottoPdfTextExtractor = AndroidSuperLottoPdfTextExtractor(context),
                    )
                val result = repository.getDraw(LotteryType.SUPER_LOTTO, Issue(TARGET_ISSUE))
                val draw = assertIs<DrawQueryResult.Success>(result).drawResult

                assertEquals(DrawStatus.FINAL_NUMBERS, draw.status)
                assertEquals(TARGET_ISSUE, draw.issue.value)
                assertEquals("中国体彩网开奖公告 PDF", draw.supportingEvidence.single().sourceName)
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

        /** 已纳入 B2 机器对账的公开历史期。 */
        const val TARGET_ISSUE = "26090"
    }
}

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package roc.win.lottery.data

import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSProcessInfo
import roc.win.lottery.domain.DrawStatus
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** iOS 显式启用的大乐透官网联网与 PDFKit 文本层验收。 */
class IOSHistoricalSuperLottoLiveTest {
    /** 使用 Darwin 系统网络栈验证主 JSON、聚合回退、官方 PDF 和双证据闭环。 */
    @Test
    fun explicitlyEnabledHistoricalIssueUsesOfficialNetwork() =
        runTest(timeout = LIVE_TEST_TIMEOUT) {
            val environment = NSProcessInfo.processInfo.environment
            if (environment[LIVE_DRAW_ENABLE_ENVIRONMENT_VARIABLE] as? String != ENABLED_VALUE) return@runTest
            check((environment[ACCEPTANCE_PROXY_PORT_ENVIRONMENT_VARIABLE] as? String).isNullOrBlank()) {
                "iOS 官网联网验收必须移除本地代理配置"
            }
            val targetIssue = environment[TARGET_ISSUE_ENVIRONMENT_VARIABLE] as? String ?: DEFAULT_TARGET_ISSUE
            val client = createPlatformHttpClient()
            try {
                val repository =
                    OfficialDrawRepository(
                        httpClient = client,
                        superLottoPdfTextExtractor = IOSSuperLottoPdfTextExtractor(),
                    )
                val result = repository.getDraw(LotteryType.SUPER_LOTTO, Issue(targetIssue))
                val draw =
                    assertIs<DrawQueryResult.Success>(
                        result,
                        "iOS Darwin 网络栈未能形成大乐透历史期双证据：$result",
                    ).drawResult

                assertTrue(draw.status in VERIFIED_STATUSES)
                assertEquals(targetIssue, draw.issue.value)
                assertTrue(draw.supportingEvidence.isNotEmpty())
            } finally {
                client.close()
            }
        }

    /** 使用本机临时公开公告验证 PDFKit 结构、文本层和共享语义解析。 */
    @Test
    fun explicitlyEnabledHistoricalIssueUsesOfficialPdf() =
        runTest(timeout = LIVE_TEST_TIMEOUT) {
            val environment = NSProcessInfo.processInfo.environment
            if (environment[ENABLE_ENVIRONMENT_VARIABLE] as? String != ENABLED_VALUE) return@runTest
            val rawPath = environment[PDF_PATH_ENVIRONMENT_VARIABLE] as? String
            check(!rawPath.isNullOrBlank()) { "iOS 真实 PDF 验收缺少临时文件路径" }
            val targetIssue = environment[TARGET_ISSUE_ENVIRONMENT_VARIABLE] as? String ?: DEFAULT_TARGET_ISSUE
            val pdfBytes = FileSystem.SYSTEM.read(rawPath.toPath()) { readByteArray() }
            val result =
                SuperLottoAnnouncementParser.parse(
                    pdfBytes = pdfBytes,
                    targetIssue = targetIssue,
                    sourceUrl = SuperLottoAnnouncementParser.expectedPdfUrl(targetIssue),
                    textExtractor = IOSSuperLottoPdfTextExtractor(),
                )
            val snapshot =
                assertIs<SourceParseResult.Success<SupportingDrawSnapshot>>(
                    result,
                    "iOS PDFKit 未能形成安全公告快照：$result",
                ).value

            assertEquals(targetIssue, snapshot.issue)
            assertEquals("中国体彩网开奖公告 PDF", snapshot.evidence.sourceName)
        }

    /** 真实验收参数。 */
    private companion object {
        /** 显式启用 iOS 官网历史期开奖闭环的环境变量。 */
        const val LIVE_DRAW_ENABLE_ENVIRONMENT_VARIABLE = "WINLOTTERY_IOS_LIVE_DRAW"

        /** 联网验收必须保持为空的本地代理端口环境变量。 */
        const val ACCEPTANCE_PROXY_PORT_ENVIRONMENT_VARIABLE = "WINLOTTERY_IOS_ACCEPTANCE_PROXY_PORT"

        /** 显式启用真实 PDF 验收的环境变量。 */
        const val ENABLE_ENVIRONMENT_VARIABLE = "WINLOTTERY_IOS_LIVE_PDF"

        /** 验收变量启用值。 */
        const val ENABLED_VALUE = "1"

        /** 本机已下载公开 PDF 的临时绝对路径环境变量。 */
        const val PDF_PATH_ENVIRONMENT_VARIABLE = "WINLOTTERY_IOS_LIVE_PDF_PATH"

        /** 可选的大乐透目标期号环境变量。 */
        const val TARGET_ISSUE_ENVIRONMENT_VARIABLE = "WINLOTTERY_IOS_DLT_ISSUE"

        /** 未传入目标期号时使用的已对账公开历史期。 */
        const val DEFAULT_TARGET_ISSUE = "26090"

        /** 可以通过真实验收的统一开奖状态。 */
        val VERIFIED_STATUSES = setOf(DrawStatus.FINAL_NUMBERS, DrawStatus.FINAL_PAYOUT)

        /** 单期真实验收最大时长。 */
        val LIVE_TEST_TIMEOUT = 1.minutes
    }
}

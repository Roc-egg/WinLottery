package roc.win.lottery.app

import kotlinx.coroutines.test.runTest
import roc.win.lottery.Platform
import roc.win.lottery.domain.BetLine
import roc.win.lottery.domain.ConfirmedTicket
import roc.win.lottery.domain.ConfirmedValue
import roc.win.lottery.domain.DrawPolicy
import roc.win.lottery.domain.DrawResult
import roc.win.lottery.domain.DrawStatus
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryPrizeCalculator
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.PrizeTier
import roc.win.lottery.domain.PrizeTierCodes
import roc.win.lottery.domain.RuleVersion
import roc.win.lottery.domain.SourceEvidence
import roc.win.lottery.domain.TicketFieldOrigin
import roc.win.lottery.persistence.StoredTicketRecord
import roc.win.lottery.persistence.TicketAcquisitionSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 记录页核对概况：只用本机已保存的官方开奖现算，未开奖、奖金待定都算未全部核对。 */
class TicketRecordSummaryTest {
    /** 没有任何本机开奖时全部期次未核对。 */
    @Test
    fun noLocalDrawMeansNothingVerified() {
        val summary = summarize(ticket(periodCount = 2), emptyMap())
        assertEquals(TicketRecordDrawSummary(2, 0, 0, 0L, 0), summary)
        assertEquals(2, summary.remainingPeriodCount)
        assertFalse(summary.isComplete)
    }

    /** 多期票只开出一部分时统计已开奖和中奖期数，剩余期次未核对。 */
    @Test
    fun partialDrawsCountWinsAndRemaining() {
        val summary =
            summarize(
                ticket(periodCount = 3),
                mapOf(
                    Issue("26090") to draw("26090", primary = listOf(2, 7, 14, 21, 33), secondary = listOf(4, 9)),
                    Issue("26091") to draw("26091", primary = listOf(1, 3, 5, 8, 11), secondary = listOf(6, 12)),
                ),
            )
        assertEquals(TicketRecordDrawSummary(3, 2, 1, 1_800L, 0), summary)
        assertEquals(1, summary.remainingPeriodCount)
        assertFalse(summary.isComplete)
    }

    /** 全部期次开奖且中奖金额确认时视为全部核对完。 */
    @Test
    fun allDrawsWithConfirmedPrizeAreComplete() {
        val summary =
            summarize(
                ticket(periodCount = 1),
                mapOf(Issue("26090") to draw("26090", primary = listOf(2, 7, 14, 21, 33), secondary = listOf(4, 9))),
            )
        assertEquals(TicketRecordDrawSummary(1, 1, 1, 1_800L, 0), summary)
        assertTrue(summary.isComplete)
    }

    /** 中奖但奖金尚待官方确认时计入待确认，不算全部核对完。 */
    @Test
    fun pendingPrizeIsNotComplete() {
        val summary =
            summarize(
                ticket(periodCount = 1),
                mapOf(
                    Issue("26090") to
                        draw(
                            "26090",
                            primary = listOf(2, 7, 14, 21, 33),
                            secondary = listOf(4, 9),
                            status = DrawStatus.FINAL_NUMBERS,
                        ),
                ),
            )
        assertEquals(TicketRecordDrawSummary(1, 1, 1, 0L, 1), summary)
        assertFalse(summary.isComplete)
    }

    /** 控制器只读本机已保存的开奖，按本年度顺延对上多期票的期号。 */
    @Test
    fun controllerSummarizesFromLocalDrawsOnly() =
        runTest {
            val store =
                InMemoryDrawResultStore(
                    listOf(draw("26090", primary = listOf(2, 7, 14, 21, 33), secondary = listOf(4, 9))),
                )
            val controller = controller(store)
            val multi = record("multi", ticket(periodCount = 2))
            val unqueried = record("unqueried", ticket(issue = "26100", periodCount = 1))

            val summaries = controller.summarizeTicketRecords(listOf(multi, unqueried))

            assertEquals(TicketRecordDrawSummary(2, 1, 1, 1_800L, 0), summaries.getValue("multi"))
            assertEquals(TicketRecordDrawSummary(1, 0, 0, 0L, 0), summaries.getValue("unqueried"))
            assertEquals(0, store.saveCount)
        }

    /** 用真实规则引擎现算。 */
    private fun summarize(
        ticket: ConfirmedTicket,
        draws: Map<Issue, DrawResult>,
    ): TicketRecordDrawSummary {
        val issues = (0 until ticket.periodCount.value).map { offset -> Issue((26090 + offset).toString()) }
        return summarizeTicketDraws(ticket, issues, draws, LotteryPrizeCalculator())
    }

    /** 前区 02 07 15 21 30、后区 04 10 的大乐透单式票；对上测试开奖为六等奖。 */
    private fun ticket(
        issue: String = "26090",
        periodCount: Int,
    ): ConfirmedTicket =
        ConfirmedTicket(
            lotteryType = ConfirmedValue(LotteryType.SUPER_LOTTO, TicketFieldOrigin.USER),
            issue = ConfirmedValue(Issue(issue), TicketFieldOrigin.USER),
            betLines =
                listOf(
                    BetLine(
                        primaryNumbers = ConfirmedValue(listOf(2, 7, 15, 21, 30), TicketFieldOrigin.USER),
                        secondaryNumbers = ConfirmedValue(listOf(4, 10), TicketFieldOrigin.USER),
                        isAdditional = ConfirmedValue(false, TicketFieldOrigin.USER),
                        originalText = "",
                    ),
                ),
            multiplier = ConfirmedValue(1, TicketFieldOrigin.USER),
            periodCount = ConfirmedValue(periodCount, TicketFieldOrigin.USER),
            paidAmountFen = ConfirmedValue(200L * periodCount, TicketFieldOrigin.DERIVED),
        )

    /** 构造一期大乐透开奖，六等奖金额已公布。 */
    private fun draw(
        issue: String,
        primary: List<Int>,
        secondary: List<Int>,
        status: DrawStatus = DrawStatus.FINAL_PAYOUT,
    ): DrawResult =
        DrawResult(
            lotteryType = LotteryType.SUPER_LOTTO,
            issue = Issue(issue),
            drawDate = "2026-08-12",
            primaryNumbers = primary,
            secondaryNumbers = secondary,
            status = status,
            revision = 1,
            ruleVersion = RuleVersion.DLT_2026_01.code,
            policy = DrawPolicy.STANDARD,
            prizeTiers =
                listOf(
                    PrizeTier(PrizeTierCodes.SIXTH, "六等奖", 1_800L, null),
                    PrizeTier(PrizeTierCodes.SEVENTH, "七等奖", 500L, null),
                ),
            evidence = SourceEvidence("测试主源", "https://example.invalid/main", 1L, "a".repeat(64)),
            supportingEvidence = emptyList(),
        )

    /** 包装为本机记录。 */
    private fun record(
        id: String,
        ticket: ConfirmedTicket,
    ): StoredTicketRecord = StoredTicketRecord(id, id, ticket, TicketAcquisitionSource.MANUAL_ENTRY, 1L, 1L, 1)

    /** 接入本机开奖缓存、使用真实开奖数据口径的控制器。 */
    private fun controller(store: InMemoryDrawResultStore): LotteryAppController {
        val demo =
            AppContainer.createDemo(
                object : Platform {
                    /** 测试平台名称。 */
                    override val name: String = "测试平台"

                    /** 测试平台不提供相机。 */
                    override val supportsCamera: Boolean = false
                },
            )
        return LotteryAppController(
            AppContainer(
                platform = demo.platform,
                imageAcquirer = demo.imageAcquirer,
                imageQualityAnalyzer = demo.imageQualityAnalyzer,
                ticketRecognizer = demo.ticketRecognizer,
                ticketParser = demo.ticketParser,
                drawRepository = demo.drawRepository,
                historicalDrawRepository = null,
                prizeCalculator = demo.prizeCalculator,
                appPaths = demo.appPaths,
                ticketValidator = demo.ticketValidator,
                isDemo = false,
                usesRealImageAcquisition = false,
                usesRealRecognition = false,
                usesRealDrawData = true,
                drawResultStore = store,
            ),
        )
    }
}

package roc.win.lottery.app

import roc.win.lottery.domain.BetLine
import roc.win.lottery.domain.ConfirmedTicket
import roc.win.lottery.domain.ConfirmedValue
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.TicketFieldOrigin
import roc.win.lottery.persistence.CURRENT_TICKET_RECORD_DATA_VERSION
import roc.win.lottery.persistence.StoredTicketRecord
import roc.win.lottery.persistence.TicketAcquisitionSource
import kotlin.test.Test
import kotlin.test.assertEquals

/** 本机票据列表的稳定筛选契约测试。 */
class TicketRecordListTest {
    /** 彩种筛选必须只保留目标玩法，并保持仓库原始倒序。 */
    @Test
    fun lotteryFilterPreservesRepositoryOrder() {
        val records =
            listOf(
                record("第一张大乐透", LotteryType.SUPER_LOTTO, "26091", 1),
                record("双色球", LotteryType.DOUBLE_COLOR_BALL, "2026092", 2),
                record("第二张大乐透", LotteryType.SUPER_LOTTO, "26090", 3),
            )

        val filtered = filterTicketRecords(records, TicketRecordFilter.SUPER_LOTTO, "")

        assertEquals(listOf("第一张大乐透", "第二张大乐透"), filtered.map { it.displayName })
    }

    /** 搜索应忽略首尾空白，并匹配名称或完整起始期号。 */
    @Test
    fun searchMatchesDisplayNameOrIssue() {
        val records =
            listOf(
                record("Alpha 收藏", LotteryType.SUPER_LOTTO, "26091", 1),
                record("周末票", LotteryType.DOUBLE_COLOR_BALL, "2026092", 2),
            )

        assertEquals(
            listOf("Alpha 收藏"),
            filterTicketRecords(records, TicketRecordFilter.ALL, " alpha ").map { it.displayName },
        )
        assertEquals(
            listOf("周末票"),
            filterTicketRecords(records, TicketRecordFilter.ALL, "6092").map { it.displayName },
        )
    }

    /** 创建一条满足当前领域形状的筛选测试记录。 */
    private fun record(
        displayName: String,
        lotteryType: LotteryType,
        issue: String,
        sequence: Int,
    ): StoredTicketRecord =
        StoredTicketRecord(
            id = "20000000-0000-4000-8000-${sequence.toString().padStart(12, '0')}",
            displayName = displayName,
            ticket = ticket(lotteryType, issue),
            acquisitionSource = TicketAcquisitionSource.MANUAL_ENTRY,
            createdAtEpochMillis = sequence.toLong(),
            updatedAtEpochMillis = sequence.toLong(),
            dataVersion = CURRENT_TICKET_RECORD_DATA_VERSION,
        )

    /** 创建指定彩种的合法单式单期票。 */
    private fun ticket(
        lotteryType: LotteryType,
        issue: String,
    ): ConfirmedTicket {
        val line =
            when (lotteryType) {
                LotteryType.SUPER_LOTTO -> betLine(listOf(2, 7, 14, 21, 33), listOf(4, 9))
                LotteryType.DOUBLE_COLOR_BALL -> betLine(listOf(1, 7, 14, 21, 28, 33), listOf(9))
            }
        return ConfirmedTicket(
            lotteryType = confirmed(lotteryType),
            issue = confirmed(Issue(issue)),
            betLines = listOf(line),
            multiplier = confirmed(1),
            periodCount = confirmed(1),
            paidAmountFen = confirmed(200L),
        )
    }

    /** 创建一行不追加的合法单式投注。 */
    private fun betLine(
        primaryNumbers: List<Int>,
        secondaryNumbers: List<Int>,
    ): BetLine =
        BetLine(
            primaryNumbers = confirmed(primaryNumbers),
            secondaryNumbers = confirmed(secondaryNumbers),
            isAdditional = confirmed(false),
            originalText = "",
        )

    /** 创建由用户确认的字段。 */
    private fun <T> confirmed(value: T): ConfirmedValue<T> = ConfirmedValue(value, TicketFieldOrigin.USER)
}

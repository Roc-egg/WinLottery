package roc.win.lottery.app

import roc.win.lottery.domain.LotteryType
import roc.win.lottery.persistence.StoredTicketRecord

/** 本机票据列表支持的彩种筛选。 */
enum class TicketRecordFilter {
    /** 不限制彩种。 */
    ALL,

    /** 只显示超级大乐透。 */
    SUPER_LOTTO,

    /** 只显示双色球。 */
    DOUBLE_COLOR_BALL,
}

/**
 * 按彩种、名称和起始期号筛选本机记录，并保持仓库提供的稳定顺序。
 *
 * @param records 已按创建时间倒序排列的合法记录。
 * @param filter 当前彩种筛选。
 * @param searchQuery 名称或起始期号搜索文本。
 * @return 保持原相对顺序的匹配记录。
 */
fun filterTicketRecords(
    records: List<StoredTicketRecord>,
    filter: TicketRecordFilter,
    searchQuery: String,
): List<StoredTicketRecord> {
    val normalizedQuery = searchQuery.trim()
    return records.filter { record ->
        val matchesLottery =
            when (filter) {
                TicketRecordFilter.ALL -> {
                    true
                }

                TicketRecordFilter.SUPER_LOTTO -> {
                    record.ticket.lotteryType.value == LotteryType.SUPER_LOTTO
                }

                TicketRecordFilter.DOUBLE_COLOR_BALL -> {
                    record.ticket.lotteryType.value == LotteryType.DOUBLE_COLOR_BALL
                }
            }
        val matchesQuery =
            normalizedQuery.isEmpty() ||
                record.displayName.contains(normalizedQuery, ignoreCase = true) ||
                record.ticket.issue.value.value
                    .contains(normalizedQuery, ignoreCase = true)
        matchesLottery && matchesQuery
    }
}

package roc.win.lottery.app

import roc.win.lottery.domain.ConfirmedTicket
import roc.win.lottery.domain.DrawResult
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.PrizeCalculator
import roc.win.lottery.domain.PrizeCheckStatus
import roc.win.lottery.persistence.StoredTicketRecord

/** 本机票据列表支持的彩种筛选。 */
enum class TicketRecordFilter {
    /** 不限制彩种。 */
    ALL,

    /** 只显示超级大乐透。 */
    SUPER_LOTTO,

    /** 只显示双色球。 */
    DOUBLE_COLOR_BALL,

    /** 只显示快乐8。 */
    HAPPY_8,
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

                TicketRecordFilter.HAPPY_8 -> {
                    record.ticket.lotteryType.value == LotteryType.HAPPY_8
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

/**
 * 一条本机记录在本机已保存官方开奖范围内的核对概况。
 *
 * 每次展示时用当前规则引擎现算，不保存；只统计已形成中奖或未中奖结论的期次。
 *
 * @property periodCount 票面投注期数。
 * @property drawnPeriodCount 本机已有官方开奖且完成比对的期数。
 * @property winningPeriodCount 其中中奖的期数。
 * @property confirmedPrizeFen 奖金已确认的中奖期次税前奖金合计，单位为分。
 * @property pendingPrizePeriodCount 已中奖但奖金仍待官方确认的期数。
 */
data class TicketRecordDrawSummary(
    val periodCount: Int,
    val drawnPeriodCount: Int,
    val winningPeriodCount: Int,
    val confirmedPrizeFen: Long,
    val pendingPrizePeriodCount: Int,
) {
    /** 全部期次都已比对，且中奖期次的奖金都已确认。 */
    val isComplete: Boolean
        get() = drawnPeriodCount == periodCount && pendingPrizePeriodCount == 0

    /** 尚未比对的期数。 */
    val remainingPeriodCount: Int
        get() = (periodCount - drawnPeriodCount).coerceAtLeast(0)
}

/**
 * 用已取得的官方开奖逐期现算一张票的核对概况。
 *
 * @param ticket 已确认的票据。
 * @param issues 票据各期的期号，按开奖先后排列；无法展开时为空，全部期次计为未核对。
 * @param draws 本机已保存的号码已确认开奖，按期号索引。
 * @param calculator 当前规则引擎。
 * @return 本票的核对概况。
 */
internal fun summarizeTicketDraws(
    ticket: ConfirmedTicket,
    issues: List<Issue>,
    draws: Map<Issue, DrawResult>,
    calculator: PrizeCalculator,
): TicketRecordDrawSummary {
    var drawn = 0
    var winning = 0
    var pending = 0
    var prizeFen = 0L
    issues.forEach { issue ->
        val draw = draws[issue] ?: return@forEach
        val result = calculator.calculate(ticket.copy(issue = ticket.issue.copy(value = issue)), draw)
        when (result.status) {
            PrizeCheckStatus.NO_WIN -> {
                drawn += 1
            }

            PrizeCheckStatus.WIN -> {
                drawn += 1
                winning += 1
                val amount = result.estimatedPrizeFen
                if (amount == null || amount < 0L || prizeFen > Long.MAX_VALUE - amount) {
                    pending += 1
                } else {
                    prizeFen += amount
                }
            }

            PrizeCheckStatus.RULE_UNSUPPORTED, PrizeCheckStatus.NOT_CALCULATED -> {
                Unit
            }
        }
    }
    return TicketRecordDrawSummary(
        periodCount = ticket.periodCount.value,
        drawnPeriodCount = drawn,
        winningPeriodCount = winning,
        confirmedPrizeFen = prizeFen,
        pendingPrizePeriodCount = pending,
    )
}

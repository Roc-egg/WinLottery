package roc.win.lottery.persistence

/**
 * 应用本机结构化存储：结构化票据、官方历史开奖和已开奖查询结果共用同一个 Room 数据库，但使用互不关联的独立表。
 *
 * 共用数据库统一由 [ticketRecordStore] 的 `close()` 关闭；其他仓库不单独持有生命周期。
 */
class WinLotteryLocalStorage internal constructor(
    database: WinLotteryDatabase,
) {
    /** 结构化票据仓库，负责关闭共用数据库。 */
    val ticketRecordStore: TicketRecordStore = RoomTicketRecordStore(database)

    /** 本机官方历史开奖缓存。 */
    val historicalDrawStore: HistoricalDrawStore = RoomHistoricalDrawStore(database)

    /** 查询开奖时取得的已开奖官方结果缓存。 */
    val drawResultStore: DrawResultStore = RoomDrawResultStore(database)
}

package roc.win.lottery.persistence

import androidx.room3.Room

/** 为 JVM 持久化回归创建指定路径的 Room 仓库。 */
internal fun createJvmTicketRecordStore(databasePath: String): TicketRecordStore {
    require(databasePath.isNotBlank()) { "JVM 数据库路径不能为空" }
    val builder =
        Room.databaseBuilder<WinLotteryDatabase>(
            name = databasePath,
            factory = WinLotteryDatabaseConstructor::initialize,
        )
    return RoomTicketRecordStore(buildWinLotteryDatabase(builder))
}

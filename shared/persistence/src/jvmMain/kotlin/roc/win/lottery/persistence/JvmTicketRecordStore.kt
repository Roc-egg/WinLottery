package roc.win.lottery.persistence

import androidx.room3.Room
import java.io.File

/** 在 JVM 应用数据目录中创建结构化票据 Room 仓库。 */
fun createJvmTicketRecordStore(databaseDirectory: File): TicketRecordStore {
    val directory = databaseDirectory.absoluteFile
    check(directory.isDirectory || directory.mkdirs()) { "无法创建 JVM 数据库目录" }
    return createJvmTicketRecordStore(File(directory, WIN_LOTTERY_DATABASE_FILE_NAME).absolutePath)
}

/** 为 JVM 持久化回归创建指定文件路径的 Room 仓库。 */
internal fun createJvmTicketRecordStore(databasePath: String): TicketRecordStore {
    require(databasePath.isNotBlank()) { "JVM 数据库路径不能为空" }
    val builder =
        Room.databaseBuilder<WinLotteryDatabase>(
            name = databasePath,
            factory = WinLotteryDatabaseConstructor::initialize,
        )
    return RoomTicketRecordStore(buildWinLotteryDatabase(builder))
}

package roc.win.lottery.persistence

import androidx.room3.Room
import java.io.File

/** 在 JVM 应用数据目录中创建结构化票据 Room 仓库。 */
fun createJvmTicketRecordStore(databaseDirectory: File): TicketRecordStore =
    createJvmLocalStorage(databaseDirectory).ticketRecordStore

/** 在 JVM 应用数据目录中创建票据和官方历史开奖共用的本机存储。 */
fun createJvmLocalStorage(databaseDirectory: File): WinLotteryLocalStorage {
    val directory = databaseDirectory.absoluteFile
    check(directory.isDirectory || directory.mkdirs()) { "无法创建 JVM 数据库目录" }
    return createJvmLocalStorage(File(directory, WIN_LOTTERY_DATABASE_FILE_NAME).absolutePath)
}

/** 为 JVM 持久化回归创建指定文件路径的 Room 仓库。 */
internal fun createJvmTicketRecordStore(databasePath: String): TicketRecordStore =
    createJvmLocalStorage(databasePath).ticketRecordStore

/** 为 JVM 持久化回归创建指定文件路径的本机存储。 */
internal fun createJvmLocalStorage(databasePath: String): WinLotteryLocalStorage {
    require(databasePath.isNotBlank()) { "JVM 数据库路径不能为空" }
    val builder =
        Room.databaseBuilder<WinLotteryDatabase>(
            name = databasePath,
            factory = WinLotteryDatabaseConstructor::initialize,
        )
    return WinLotteryLocalStorage(buildWinLotteryDatabase(builder))
}

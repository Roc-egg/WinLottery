package roc.win.lottery.persistence

import androidx.room3.ColumnTypeConverters
import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

/** V1.1 结构化票据 Room 数据库。 */
@Database(
    entities = [TicketRecordEntity::class, TicketBetLineEntity::class],
    version = WIN_LOTTERY_DATABASE_VERSION,
    exportSchema = true,
)
@ConstructedBy(WinLotteryDatabaseConstructor::class)
@ColumnTypeConverters(IntListConverter::class)
internal abstract class WinLotteryDatabase : RoomDatabase() {
    /** 获取票据记录的数据访问对象。 */
    abstract fun ticketRecordDao(): TicketRecordDao
}

/** 由 Room KSP 为每个平台生成数据库构造实现。 */
@Suppress("KotlinNoActualForExpect")
internal expect object WinLotteryDatabaseConstructor : RoomDatabaseConstructor<WinLotteryDatabase> {
    /** 实例化当前平台生成的 Room 数据库实现。 */
    override fun initialize(): WinLotteryDatabase
}

/** 使用同一捆绑 SQLite 驱动和协程调度配置创建数据库。 */
internal fun buildWinLotteryDatabase(builder: RoomDatabase.Builder<WinLotteryDatabase>): WinLotteryDatabase =
    builder
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.Default)
        .build()

/** 活动数据库固定文件名。 */
internal const val WIN_LOTTERY_DATABASE_FILE_NAME = "win-lottery-v1.db"

/** 当前 Room 模式版本。 */
internal const val WIN_LOTTERY_DATABASE_VERSION = 1

package roc.win.lottery.persistence

import androidx.room3.ColumnTypeConverters
import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

/** 结构化票据、本机官方历史开奖和已开奖查询结果共用的 Room 数据库，各类数据使用互不关联的独立表。 */
@Database(
    entities = [
        TicketRecordEntity::class,
        TicketBetLineEntity::class,
        HistoricalDrawSetEntity::class,
        HistoricalDrawEntity::class,
        DrawResultEntity::class,
    ],
    version = WIN_LOTTERY_DATABASE_VERSION,
    exportSchema = true,
)
@ConstructedBy(WinLotteryDatabaseConstructor::class)
@ColumnTypeConverters(IntListConverter::class)
internal abstract class WinLotteryDatabase : RoomDatabase() {
    /** 获取票据记录的数据访问对象。 */
    abstract fun ticketRecordDao(): TicketRecordDao

    /** 获取本机官方历史开奖的数据访问对象。 */
    abstract fun historicalDrawDao(): HistoricalDrawDao

    /** 获取已开奖查询结果的数据访问对象。 */
    abstract fun drawResultDao(): DrawResultDao
}

/** 由 Room KSP 为每个平台生成数据库构造实现。 */
@Suppress("KotlinNoActualForExpect")
internal expect object WinLotteryDatabaseConstructor : RoomDatabaseConstructor<WinLotteryDatabase> {
    /** 实例化当前平台生成的 Room 数据库实现。 */
    override fun initialize(): WinLotteryDatabase
}

/** 使用同一捆绑 SQLite 驱动、协程调度和显式迁移创建数据库；不允许破坏性回退清空票据。 */
internal fun buildWinLotteryDatabase(builder: RoomDatabase.Builder<WinLotteryDatabase>): WinLotteryDatabase =
    builder
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.Default)
        .addMigrations(*WIN_LOTTERY_DATABASE_MIGRATIONS.toTypedArray())
        .build()

/** 活动数据库固定文件名；文件名与模式版本无关，改名会让既有数据成为孤儿文件。 */
internal const val WIN_LOTTERY_DATABASE_FILE_NAME = "win-lottery-v1.db"

/** 当前 Room 模式版本；每次递增都必须提供显式迁移、模式快照和升级测试。 */
internal const val WIN_LOTTERY_DATABASE_VERSION = 3

package roc.win.lottery.persistence

import android.content.Context
import androidx.room3.Room

/**
 * 创建 Android 应用私有数据库中的结构化票据仓库。
 *
 * @param context 任意应用上下文可达的 Android 上下文。
 */
fun createAndroidTicketRecordStore(context: Context): TicketRecordStore {
    val applicationContext = context.applicationContext
    val builder =
        Room.databaseBuilder<WinLotteryDatabase>(
            context = applicationContext,
            name = WIN_LOTTERY_DATABASE_FILE_NAME,
            factory = WinLotteryDatabaseConstructor::initialize,
        )
    return RoomTicketRecordStore(buildWinLotteryDatabase(builder))
}

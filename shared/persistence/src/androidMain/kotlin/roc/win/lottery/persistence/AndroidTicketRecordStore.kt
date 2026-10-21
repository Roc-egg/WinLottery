package roc.win.lottery.persistence

import android.content.Context
import androidx.room3.Room

/**
 * 创建 Android 应用私有数据库中的结构化票据仓库。
 *
 * @param context 任意应用上下文可达的 Android 上下文。
 */
fun createAndroidTicketRecordStore(context: Context): TicketRecordStore =
    createAndroidLocalStorage(context).ticketRecordStore

/**
 * 创建 Android 应用私有数据库中的本机存储，票据和官方历史开奖共用同一数据库。
 *
 * @param context 任意应用上下文可达的 Android 上下文。
 */
fun createAndroidLocalStorage(context: Context): WinLotteryLocalStorage {
    val applicationContext = context.applicationContext
    val builder =
        Room.databaseBuilder<WinLotteryDatabase>(
            context = applicationContext,
            name = WIN_LOTTERY_DATABASE_FILE_NAME,
            factory = WinLotteryDatabaseConstructor::initialize,
        )
    return WinLotteryLocalStorage(buildWinLotteryDatabase(builder))
}

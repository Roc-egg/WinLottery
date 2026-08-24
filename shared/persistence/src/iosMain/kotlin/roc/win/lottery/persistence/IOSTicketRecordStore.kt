package roc.win.lottery.persistence

import androidx.room3.Room
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask

/** 创建 iOS Application Support 目录中且排除 iCloud 备份的结构化票据仓库。 */
@OptIn(ExperimentalForeignApi::class)
fun createIOSTicketRecordStore(): TicketRecordStore {
    val databaseDirectory = prepareDatabaseDirectory()
    val databasePath = "$databaseDirectory/$WIN_LOTTERY_DATABASE_FILE_NAME"
    val builder =
        Room.databaseBuilder<WinLotteryDatabase>(
            name = databasePath,
            factory = WinLotteryDatabaseConstructor::initialize,
        )
    return RoomTicketRecordStore(buildWinLotteryDatabase(builder))
}

/** 创建数据库目录并把整个目录排除在系统云备份之外。 */
@OptIn(ExperimentalForeignApi::class)
private fun prepareDatabaseDirectory(): String {
    val manager = NSFileManager.defaultManager
    val applicationSupport =
        requireNotNull(
            manager.URLForDirectory(
                directory = NSApplicationSupportDirectory,
                inDomain = NSUserDomainMask,
                appropriateForURL = null,
                create = true,
                error = null,
            ),
        ) { "无法读取 iOS Application Support 目录" }
    val directoryUrl =
        requireNotNull(applicationSupport.URLByAppendingPathComponent(DATABASE_DIRECTORY_NAME, isDirectory = true)) {
            "无法创建 iOS 数据库目录 URL"
        }
    check(
        manager.createDirectoryAtURL(
            url = directoryUrl,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        ),
    ) { "无法创建 iOS 数据库目录" }
    check(
        directoryUrl.setResourceValue(
            value = true,
            forKey = NSURLIsExcludedFromBackupKey,
            error = null,
        ),
    ) { "无法把 iOS 数据库排除在 iCloud 备份之外" }
    return requireNotNull(directoryUrl.path) { "iOS 数据库目录没有有效路径" }
}

/** iOS Application Support 下的数据库子目录名称。 */
private const val DATABASE_DIRECTORY_NAME = "WinLottery"

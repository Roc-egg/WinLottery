package roc.win.lottery.recognition

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL

/** 管理 iOS 应用沙箱临时目录中的票图副本。 */
@OptIn(ExperimentalForeignApi::class)
class IOSAppPaths : AppPaths {
    /** iOS 临时目录内专用于当前分析流程的私有子目录。 */
    override val temporaryImageDirectory: String =
        NSTemporaryDirectory().trimEnd(PATH_SEPARATOR) + PATH_SEPARATOR + TEMPORARY_DIRECTORY_NAME

    init {
        prepareDirectory()
    }

    /** 清理 iOS 临时目录中的全部票图副本。 */
    override fun clearTemporaryImages() {
        prepareDirectory()
    }

    /** 只删除本实现临时目录直属的图片，拒绝处理目录外路径。 */
    override suspend fun deleteTemporaryImage(imageRef: ImageRef): Boolean =
        withContext(Dispatchers.Default) {
            val target = standardizedPath(imageRef.localPath) ?: return@withContext false
            val directory = standardizedPath(temporaryImageDirectory) ?: return@withContext false
            if (target.substringBeforeLast(PATH_SEPARATOR, EMPTY_PATH) != directory) return@withContext false
            val manager = NSFileManager.defaultManager
            !manager.fileExistsAtPath(target) || manager.removeItemAtPath(target, error = null)
        }

    /** 创建临时目录，并清理由上次异常退出遗留的票图。 */
    private fun prepareDirectory() {
        val manager = NSFileManager.defaultManager
        manager.createDirectoryAtPath(
            path = temporaryImageDirectory,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        val children = manager.contentsOfDirectoryAtPath(temporaryImageDirectory, error = null).orEmpty()
        children.forEach { name ->
            val childPath = "$temporaryImageDirectory$PATH_SEPARATOR$name"
            manager.removeItemAtPath(childPath, error = null)
        }
    }

    /** 把路径标准化后用于目录边界判断。 */
    private fun standardizedPath(path: String): String? = NSURL.fileURLWithPath(path).URLByStandardizingPath?.path

    /** iOS 临时路径常量。 */
    private companion object {
        /** 临时票图目录名称。 */
        const val TEMPORARY_DIRECTORY_NAME = "WinLotteryTicketImages"

        /** Unix 路径分隔符。 */
        const val PATH_SEPARATOR = '/'

        /** 找不到父路径时使用的空值。 */
        const val EMPTY_PATH = ""
    }
}

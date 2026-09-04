package roc.win.lottery.recognition

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 管理 Windows 和 macOS 应用临时目录中的票图副本。 */
class DesktopAppPaths(
    /** 系统临时根目录；测试可注入隔离目录。 */
    rootDirectory: File = File(System.getProperty(JAVA_TEMPORARY_DIRECTORY_PROPERTY)),
) : AppPaths {
    /** 临时根目录内专用于当前分析流程的私有子目录。 */
    private val imageDirectory = File(rootDirectory, TEMPORARY_DIRECTORY_NAME)

    /** 不进入用户原图目录的票图临时目录。 */
    override val temporaryImageDirectory: String
        get() = imageDirectory.absolutePath

    init {
        prepareDirectory()
    }

    /** 清理桌面临时目录中的全部票图副本。 */
    override fun clearTemporaryImages() {
        prepareDirectory()
    }

    /** 只删除本实现临时目录直属的图片，拒绝处理目录外路径。 */
    override suspend fun deleteTemporaryImage(imageRef: ImageRef): Boolean =
        withContext(Dispatchers.IO) {
            val target = runCatching { File(imageRef.localPath).canonicalFile }.getOrNull() ?: return@withContext false
            val directory = runCatching { imageDirectory.canonicalFile }.getOrNull() ?: return@withContext false
            if (target.parentFile != directory) return@withContext false
            !target.exists() || target.delete()
        }

    /** 创建临时目录，并清理由上次异常退出遗留的票图。 */
    private fun prepareDirectory() {
        if (!imageDirectory.exists()) {
            imageDirectory.mkdirs()
        }
        imageDirectory.listFiles().orEmpty().forEach { file ->
            if (file.isFile) file.delete()
        }
    }

    /** Desktop 临时路径常量，供同一模块的受控 OCR 工作进程复用。 */
    internal companion object {
        /** Java 系统临时目录属性名。 */
        const val JAVA_TEMPORARY_DIRECTORY_PROPERTY = "java.io.tmpdir"

        /** 临时票图目录名称。 */
        const val TEMPORARY_DIRECTORY_NAME = "WinLotteryTicketImages"
    }
}

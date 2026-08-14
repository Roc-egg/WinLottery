package roc.win.lottery.recognition

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** 使用 Windows 或 macOS 系统文件选择器导入彩票图片。 */
class DesktopFileImageAcquirer(
    /** 返回系统文件选择器使用的当前桌面窗口。 */
    private val ownerProvider: () -> Frame?,
    /** 临时票图路径管理器。 */
    private val appPaths: DesktopAppPaths,
) : ImageAcquirer {
    /** Windows 和 macOS 的 V1 不提供应用内拍照。 */
    override val supportsCamera: Boolean = false

    /** 负责图片解码、方向归一化和私有重编码。 */
    private val imageImporter = DesktopImageImporter(appPaths)

    /** 启动系统文件选择器，并返回去除元数据后的私有 JPEG 副本。 */
    override suspend fun acquire(source: ImageAcquisitionSource): ImageAcquisitionResult {
        if (source == ImageAcquisitionSource.CAMERA) {
            return ImageAcquisitionResult.Unavailable("桌面端 V1 仅支持导入图片")
        }
        val selectedFile = selectImage() ?: return ImageAcquisitionResult.Cancelled
        return withContext(Dispatchers.IO) {
            imageImporter.importPrivateCopy(selectedFile)
        }
    }

    /** 在桌面主线程展示仅允许单选 JPG、JPEG 或 PNG 的系统文件选择器。 */
    private suspend fun selectImage(): File? =
        withContext(Dispatchers.Main) {
            val dialog = FileDialog(ownerProvider(), FILE_DIALOG_TITLE, FileDialog.LOAD)
            try {
                dialog.isMultipleMode = false
                dialog.filenameFilter = java.io.FilenameFilter { _, name -> isSupportedFileName(name) }
                dialog.isVisible = true
                val directory = dialog.directory ?: return@withContext null
                val fileName = dialog.file ?: return@withContext null
                File(directory, fileName).takeIf { it.isFile && isSupportedFileName(it.name) }
            } finally {
                dialog.dispose()
            }
        }

    /** 判断文件名是否属于 V1 明确支持的图片扩展名。 */
    private fun isSupportedFileName(fileName: String): Boolean =
        fileName.substringAfterLast(EXTENSION_SEPARATOR, EMPTY_EXTENSION).lowercase() in SUPPORTED_EXTENSIONS

    /** 桌面系统文件选择器常量。 */
    private companion object {
        /** 系统文件选择器标题。 */
        const val FILE_DIALOG_TITLE = "选择彩票图片"

        /** 扩展名分隔符。 */
        const val EXTENSION_SEPARATOR = '.'

        /** 文件名不存在扩展名时使用的空值。 */
        const val EMPTY_EXTENSION = ""

        /** V1 桌面导入支持的图片扩展名。 */
        val SUPPORTED_EXTENSIONS = setOf("jpg", "jpeg", "png")
    }
}

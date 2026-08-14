package roc.win.lottery.recognition

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 管理 Android 应用缓存中的票图临时文件。 */
class AndroidAppPaths(
    context: Context,
) : AppPaths {
    /** 应用缓存内专用于当前分析流程的私有目录。 */
    private val imageDirectory = File(context.cacheDir, TEMPORARY_DIRECTORY_NAME)

    /** CameraX 拍照完成到去元数据重编码之间使用的私有暂存目录。 */
    private val cameraCaptureDirectory = File(context.cacheDir, CAMERA_CAPTURE_DIRECTORY_NAME)

    /** 不进入相册和系统媒体库的临时图片目录。 */
    override val temporaryImageDirectory: String
        get() = imageDirectory.absolutePath

    init {
        prepareDirectory()
        prepareCameraCaptureDirectory()
    }

    /** 只删除本实现临时目录直属的图片，拒绝处理目录外路径。 */
    override suspend fun deleteTemporaryImage(imageRef: ImageRef): Boolean =
        withContext(Dispatchers.IO) {
            val target = runCatching { File(imageRef.localPath).canonicalFile }.getOrNull() ?: return@withContext false
            val directory = runCatching { imageDirectory.canonicalFile }.getOrNull() ?: return@withContext false
            if (target.parentFile != directory) return@withContext false
            !target.exists() || target.delete()
        }

    /** 创建私有目录，并清理由上次异常退出遗留的临时票图。 */
    private fun prepareDirectory() {
        if (!imageDirectory.exists()) {
            imageDirectory.mkdirs()
        }
        File(imageDirectory, NO_MEDIA_FILE_NAME).runCatching { createNewFile() }
        imageDirectory.listFiles().orEmpty().forEach { file ->
            if (file.isFile && file.name != NO_MEDIA_FILE_NAME) {
                file.delete()
            }
        }
    }

    /** 创建 CameraX 暂存目录，并清理由上次异常退出遗留的原始拍照文件。 */
    private fun prepareCameraCaptureDirectory() {
        if (!cameraCaptureDirectory.exists()) {
            cameraCaptureDirectory.mkdirs()
        }
        cameraCaptureDirectory.listFiles().orEmpty().forEach { file ->
            if (file.isFile) file.delete()
        }
    }

    /** Android 临时票图目录常量。 */
    private companion object {
        /** 缓存目录名称。 */
        const val TEMPORARY_DIRECTORY_NAME = "ticket-images"

        /** CameraX 私有暂存目录名称。 */
        const val CAMERA_CAPTURE_DIRECTORY_NAME = ANDROID_CAMERA_CAPTURE_DIRECTORY_NAME

        /** 阻止媒体扫描的标记文件名。 */
        const val NO_MEDIA_FILE_NAME = ".nomedia"
    }
}

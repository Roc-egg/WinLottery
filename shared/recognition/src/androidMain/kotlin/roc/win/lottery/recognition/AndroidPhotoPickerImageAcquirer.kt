package roc.win.lottery.recognition

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume

/** 使用 Android CameraX 或系统 Photo Picker 取得图片并生成去除 EXIF 的私有副本。 */
class AndroidPhotoPickerImageAcquirer(
    /** 注册系统选择器并读取所选内容的 Activity。 */
    private val activity: ComponentActivity,
    /** 临时票图路径管理器。 */
    private val appPaths: AndroidAppPaths,
) : ImageAcquirer {
    /** 当前设备是否声明任意相机能力。 */
    override val supportsCamera: Boolean =
        activity.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)

    /** 等待系统图片选择器结果的单个请求。 */
    private var pendingPickerSelection: CancellableContinuation<Uri?>? = null

    /** 等待 CameraX 拍照 Activity 结果的单个请求。 */
    private var pendingCameraCapture: CancellableContinuation<ActivityResult>? = null

    /** 必须在 Activity 进入启动状态前完成注册的系统图片选择器。 */
    private val photoPicker =
        activity.registerForActivityResult(PickVisualMedia()) { uri ->
            pendingPickerSelection?.let { continuation ->
                pendingPickerSelection = null
                if (continuation.isActive) continuation.resume(uri)
            }
        }

    /** 必须在 Activity 进入启动状态前完成注册的 CameraX 拍照结果启动器。 */
    private val cameraCaptureLauncher =
        activity.registerForActivityResult(StartActivityForResult()) { result ->
            val continuation = pendingCameraCapture
            pendingCameraCapture = null
            if (continuation?.isActive == true) {
                continuation.resume(result)
            } else {
                deleteReturnedCameraCapture(result)
            }
        }

    /** 启动系统选图；成功时返回已归一方向且不含原 EXIF 的 JPEG 临时文件。 */
    override suspend fun acquire(source: ImageAcquisitionSource): ImageAcquisitionResult {
        if (pendingPickerSelection != null || pendingCameraCapture != null) {
            return ImageAcquisitionResult.Failure("已有图片选择操作正在进行")
        }

        return when (source) {
            ImageAcquisitionSource.CAMERA -> acquireFromCamera()
            ImageAcquisitionSource.SYSTEM_PICKER -> acquireFromPhotoPicker()
        }
    }

    /** 通过 CameraX 取得拍照暂存文件，并在重新编码后无条件删除原始暂存文件。 */
    private suspend fun acquireFromCamera(): ImageAcquisitionResult {
        if (!supportsCamera) return ImageAcquisitionResult.Unavailable("当前设备没有可用相机")
        val result = captureImage()
        mapCameraFailureResult(
            resultCode = result.resultCode,
            errorMessage = result.data?.getStringExtra(ANDROID_CAMERA_ERROR_EXTRA),
        )?.let { failure -> return failure }
        val captureFile = validatedCameraCapture(result) ?: return ImageAcquisitionResult.Failure("无法读取拍照结果")
        return withContext(Dispatchers.IO) {
            try {
                importPrivateCopy(Uri.fromFile(captureFile))
            } finally {
                captureFile.delete()
            }
        }
    }

    /** 通过系统 Photo Picker 导入一张图片。 */
    private suspend fun acquireFromPhotoPicker(): ImageAcquisitionResult {
        val uri = selectImage() ?: return ImageAcquisitionResult.Cancelled
        return withContext(Dispatchers.IO) { importPrivateCopy(uri) }
    }

    /** 把 Activity Result 回调转换为可取消的挂起调用。 */
    private suspend fun selectImage(): Uri? =
        suspendCancellableCoroutine { continuation ->
            pendingPickerSelection = continuation
            continuation.invokeOnCancellation {
                if (pendingPickerSelection === continuation) pendingPickerSelection = null
            }
            runCatching {
                photoPicker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
            }.onFailure {
                pendingPickerSelection = null
                if (continuation.isActive) continuation.resume(null)
            }
        }

    /** 启动全屏 CameraX 拍照 Activity，并把启动异常编码为明确失败结果。 */
    private suspend fun captureImage(): ActivityResult =
        suspendCancellableCoroutine { continuation ->
            pendingCameraCapture = continuation
            continuation.invokeOnCancellation {
                if (pendingCameraCapture === continuation) pendingCameraCapture = null
            }
            runCatching {
                cameraCaptureLauncher.launch(Intent(activity, AndroidCameraCaptureActivity::class.java))
            }.onFailure {
                pendingCameraCapture = null
                if (continuation.isActive) {
                    continuation.resume(ActivityResult(CAMERA_LAUNCH_FAILED_RESULT_CODE, null))
                }
            }
        }

    /** 验证拍照结果只能引用应用私有 CameraX 暂存目录中的直属 JPEG 文件。 */
    private fun validatedCameraCapture(result: ActivityResult): File? {
        val rawPath = result.data?.getStringExtra(ANDROID_CAMERA_PATH_EXTRA) ?: return null
        val directory = File(activity.cacheDir, ANDROID_CAMERA_CAPTURE_DIRECTORY_NAME)
        return validateDirectCameraJpeg(rawPath, directory)
    }

    /** 没有等待者接收拍照结果时，仍清理可能已经生成的私有暂存文件。 */
    private fun deleteReturnedCameraCapture(result: ActivityResult) {
        validatedCameraCapture(result)?.delete()
    }

    /** 解码系统内容、应用 EXIF 方向并重新编码，避免原始定位信息进入临时副本。 */
    private fun importPrivateCopy(uri: Uri): ImageAcquisitionResult {
        val resolver = activity.contentResolver
        var output: File? = null
        return try {
            val normalized =
                decodeNormalizedBitmap(
                    openStream = { resolver.openInputStream(uri) },
                    maxEdgePixels = MAX_IMAGE_EDGE_PIXELS,
                ) ?: return ImageAcquisitionResult.Failure("无法解码所选图片")
            output = File(appPaths.temporaryImageDirectory, "${UUID.randomUUID()}$JPEG_EXTENSION")
            val outputWidth = normalized.width
            val outputHeight = normalized.height
            val saved =
                output.outputStream().buffered().use { stream ->
                    normalized.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
                }
            normalized.recycle()
            if (!saved) {
                output.delete()
                ImageAcquisitionResult.Failure("无法创建私有临时图片")
            } else {
                ImageAcquisitionResult.Success(
                    ImageRef(
                        id = output.nameWithoutExtension,
                        localPath = output.absolutePath,
                        mimeType = JPEG_MIME_TYPE,
                        widthPixels = outputWidth,
                        heightPixels = outputHeight,
                    ),
                )
            }
        } catch (_: SecurityException) {
            output?.delete()
            ImageAcquisitionResult.Failure("没有读取所选图片的权限")
        } catch (_: IOException) {
            output?.delete()
            ImageAcquisitionResult.Failure("读取或复制图片时发生错误")
        } catch (_: RuntimeException) {
            output?.delete()
            ImageAcquisitionResult.Failure("所选图片无法处理")
        }
    }

    /** Android 导图处理常量。 */
    private companion object {
        /** OCR PoC 保留的最长图片边。 */
        const val MAX_IMAGE_EDGE_PIXELS = 4096

        /** 私有副本 JPEG 编码质量。 */
        const val JPEG_QUALITY = 95

        /** 私有副本文件扩展名。 */
        const val JPEG_EXTENSION = ".jpg"

        /** 私有副本 MIME 类型。 */
        const val JPEG_MIME_TYPE = "image/jpeg"
    }
}

/** CameraX Activity 启动异常使用的内部结果码。 */
internal const val CAMERA_LAUNCH_FAILED_RESULT_CODE = Activity.RESULT_FIRST_USER

/**
 * 把相机 Activity 的取消或错误结果映射为采集结果。
 *
 * @return 成功结果返回空；取消或错误返回对应的采集结果。
 */
internal fun mapCameraFailureResult(
    resultCode: Int,
    errorMessage: String?,
): ImageAcquisitionResult? =
    when {
        resultCode == Activity.RESULT_OK -> null
        resultCode == CAMERA_LAUNCH_FAILED_RESULT_CODE -> ImageAcquisitionResult.Failure("无法启动应用内相机")
        errorMessage.isNullOrBlank() -> ImageAcquisitionResult.Cancelled
        else -> ImageAcquisitionResult.Failure(errorMessage)
    }

/** 只接受指定私有目录下的直属 JPEG 文件，避免信任 Activity 回传的任意路径。 */
internal fun validateDirectCameraJpeg(
    rawPath: String?,
    directory: File,
): File? {
    if (rawPath.isNullOrBlank()) return null
    val target = runCatching { File(rawPath).canonicalFile }.getOrNull() ?: return null
    val canonicalDirectory = runCatching { directory.canonicalFile }.getOrNull() ?: return null
    return target.takeIf { file ->
        file.parentFile == canonicalDirectory && file.isFile &&
            file.extension.equals(CAMERA_JPEG_EXTENSION, ignoreCase = true)
    }
}

/** CameraX 暂存文件允许的扩展名。 */
private const val CAMERA_JPEG_EXTENSION = "jpg"

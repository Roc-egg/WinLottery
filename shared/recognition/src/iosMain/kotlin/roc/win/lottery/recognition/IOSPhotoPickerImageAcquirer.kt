package roc.win.lottery.recognition

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.NSItemProvider
import platform.Foundation.NSUUID
import platform.Foundation.writeToFile
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIModalPresentationFullScreen
import platform.UIKit.UIViewController
import platform.UIKit.setModalInPresentation
import platform.darwin.NSObject
import kotlin.coroutines.resume

/** 公共图片类型标识。 */
private const val IMAGE_TYPE_IDENTIFIER = "public.image"

/** OCR PoC 保留的最长图片边。 */
private const val MAX_IMAGE_EDGE_PIXELS = 4096.0

/** 私有副本 JPEG 编码质量。 */
private const val JPEG_QUALITY = 0.95

/** 私有副本文件扩展名。 */
private const val JPEG_EXTENSION = ".jpg"

/** 私有副本 MIME 类型。 */
private const val JPEG_MIME_TYPE = "image/jpeg"

/** Unix 路径分隔符。 */
private const val PATH_SEPARATOR = "/"

/** 使用 iOS PHPicker 导入图片并生成去除元数据、归一方向的私有 JPEG 副本。 */
@OptIn(ExperimentalForeignApi::class)
class IOSPhotoPickerImageAcquirer(
    /** 返回当前可用于展示系统选择器的 Compose 宿主控制器。 */
    private val presenterProvider: () -> UIViewController?,
    /** 临时票图路径管理器。 */
    private val appPaths: IOSAppPaths,
) : ImageAcquirer {
    /** 当前设备是否提供 AVFoundation 视频采集设备。 */
    override val supportsCamera: Boolean = isIOSCameraAvailable()

    /** 等待系统选择结果的单个请求。 */
    private var pendingSelection: CancellableContinuation<SelectedImage?>? = null

    /** 等待应用内相机结果的单个请求。 */
    private var pendingCameraCapture: CancellableContinuation<IOSCameraCaptureResult>? = null

    /** 由采集器强引用的系统选择器代理，避免选择过程中被释放。 */
    private val pickerDelegate = IOSPhotoPickerDelegate(::handlePickerResult)

    /** 启动系统选图；成功时返回重新渲染并编码后的 JPEG 临时文件。 */
    override suspend fun acquire(source: ImageAcquisitionSource): ImageAcquisitionResult {
        if (pendingSelection != null || pendingCameraCapture != null) {
            return ImageAcquisitionResult.Failure("已有图片选择操作正在进行")
        }

        val selected =
            when (source) {
                ImageAcquisitionSource.CAMERA -> {
                    acquireCameraData()
                }

                ImageAcquisitionSource.SYSTEM_PICKER -> {
                    selectImage()?.data?.let(SelectedImageResult::Success)
                }
            }
        when (selected) {
            null,
            SelectedImageResult.Cancelled,
            -> return ImageAcquisitionResult.Cancelled

            is SelectedImageResult.Failure -> return ImageAcquisitionResult.Failure(selected.message)

            is SelectedImageResult.Success -> Unit
        }
        val encodedImage =
            withContext(Dispatchers.Main) {
                renderPrivateCopy(selected.data)
            } ?: return ImageAcquisitionResult.Failure("无法创建私有临时图片")
        return withContext(Dispatchers.Default) {
            writePrivateCopy(encodedImage)
        }
    }

    /** 接收 PHPicker 结果，并异步读取第一张图片的数据。 */
    private fun handlePickerResult(
        picker: PHPickerViewController,
        didFinishPicking: List<*>,
    ) {
        picker.dismissViewControllerAnimated(true, completion = null)
        val result = didFinishPicking.firstOrNull() as? PHPickerResult
        if (result == null) {
            resumeSelection(null)
            return
        }
        loadImageData(result.itemProvider)
    }

    /** 展示单选图片的系统 PHPicker，并等待代理回调。 */
    private suspend fun selectImage(): SelectedImage? =
        suspendCancellableCoroutine { continuation ->
            val presenter = presenterProvider()
            if (presenter == null) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            pendingSelection = continuation
            continuation.invokeOnCancellation {
                if (pendingSelection === continuation) pendingSelection = null
            }
            val configuration =
                PHPickerConfiguration().apply {
                    filter = PHPickerFilter.imagesFilter
                    selectionLimit = 1
                }
            val picker = PHPickerViewController(configuration)
            picker.delegate = pickerDelegate
            presenter.presentViewController(picker, animated = true, completion = null)
        }

    /** 展示全屏 AVFoundation 相机，并把它的结果转换为统一的内存图片状态。 */
    private suspend fun acquireCameraData(): SelectedImageResult {
        if (!supportsCamera) return SelectedImageResult.Failure("当前设备没有可用相机")
        val result = captureImage()
        return when (result) {
            IOSCameraCaptureResult.Cancelled -> SelectedImageResult.Cancelled
            is IOSCameraCaptureResult.Failure -> SelectedImageResult.Failure(result.message)
            is IOSCameraCaptureResult.Success -> SelectedImageResult.Success(result.data)
        }
    }

    /** 展示应用内相机并等待取消、失败或成功成片。 */
    private suspend fun captureImage(): IOSCameraCaptureResult =
        suspendCancellableCoroutine { continuation ->
            val presenter = presenterProvider()
            if (presenter == null) {
                continuation.resume(IOSCameraCaptureResult.Failure("无法展示应用内相机"))
                return@suspendCancellableCoroutine
            }
            pendingCameraCapture = continuation
            continuation.invokeOnCancellation {
                if (pendingCameraCapture === continuation) pendingCameraCapture = null
            }
            val camera =
                IOSCameraCaptureViewController { result ->
                    val pending = pendingCameraCapture
                    pendingCameraCapture = null
                    if (pending?.isActive == true) pending.resume(result)
                }
            camera.modalPresentationStyle = UIModalPresentationFullScreen
            camera.setModalInPresentation(true)
            presenter.presentViewController(camera, animated = true, completion = null)
        }

    /** 从选中结果读取图片数据，不请求整个照片库权限。 */
    private fun loadImageData(provider: NSItemProvider) {
        provider.loadDataRepresentationForTypeIdentifier(IMAGE_TYPE_IDENTIFIER) { data, _ ->
            if (data == null) {
                resumeSelection(null)
            } else {
                resumeSelection(SelectedImage(data))
            }
        }
    }

    /** 只恢复仍有效的当前选择请求。 */
    private fun resumeSelection(selectedImage: SelectedImage?) {
        pendingSelection?.let { continuation ->
            pendingSelection = null
            if (continuation.isActive) continuation.resume(selectedImage)
        }
    }

    /** 在主线程重新渲染 UIImage 以应用方向，并编码为不含原始 EXIF 的 JPEG。 */
    private fun renderPrivateCopy(data: NSData): EncodedImage? {
        val image = UIImage.imageWithData(data) ?: return null
        val sourceWidth = image.size.useContents { width }
        val sourceHeight = image.size.useContents { height }
        if (sourceWidth <= 0.0 || sourceHeight <= 0.0) return null
        val scale = minOf(1.0, MAX_IMAGE_EDGE_PIXELS / maxOf(sourceWidth, sourceHeight))
        val outputWidth = sourceWidth * scale
        val outputHeight = sourceHeight * scale
        val outputSize = CGSizeMake(outputWidth, outputHeight)
        UIGraphicsBeginImageContextWithOptions(outputSize, true, 1.0)
        image.drawInRect(CGRectMake(0.0, 0.0, outputWidth, outputHeight))
        val normalized = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()
        if (normalized == null) return null
        val jpegData =
            UIImageJPEGRepresentation(normalized, JPEG_QUALITY)
                ?: return null
        return EncodedImage(
            data = jpegData,
            widthPixels = outputWidth.toInt(),
            heightPixels = outputHeight.toInt(),
        )
    }

    /** 在后台线程把编码结果写入应用私有临时目录。 */
    private fun writePrivateCopy(encodedImage: EncodedImage): ImageAcquisitionResult {
        val fileName = NSUUID().UUIDString + JPEG_EXTENSION
        val outputPath = appPaths.temporaryImageDirectory + PATH_SEPARATOR + fileName
        if (!encodedImage.data.writeToFile(outputPath, atomically = true)) {
            return ImageAcquisitionResult.Failure("无法写入私有临时图片")
        }
        return ImageAcquisitionResult.Success(
            ImageRef(
                id = fileName.substringBeforeLast(JPEG_EXTENSION),
                localPath = outputPath,
                mimeType = JPEG_MIME_TYPE,
                widthPixels = encodedImage.widthPixels,
                heightPixels = encodedImage.heightPixels,
            ),
        )
    }

    /** 已去除原始元数据的内存 JPEG 及其像素尺寸。 */
    private data class EncodedImage(
        /** 重新编码后的 JPEG 数据。 */
        val data: NSData,
        /** 输出图片宽度，单位为像素。 */
        val widthPixels: Int,
        /** 输出图片高度，单位为像素。 */
        val heightPixels: Int,
    )

    /** 系统选择器返回的内存图片数据，仅在创建私有副本期间持有。 */
    private data class SelectedImage(
        /** 原始图片数据，不进入共享状态或日志。 */
        val data: NSData,
    )

    /** 系统选图或应用内拍照产生的统一内存结果。 */
    private sealed interface SelectedImageResult {
        /** 已取得尚未重绘的图片数据。 */
        data class Success(
            /** 仅在去元数据重绘前短暂持有的图片数据。 */
            val data: NSData,
        ) : SelectedImageResult

        /** 用户主动取消。 */
        data object Cancelled : SelectedImageResult

        /** 权限、设备或采集流程失败。 */
        data class Failure(
            /** 不包含底层异常细节的提示。 */
            val message: String,
        ) : SelectedImageResult
    }
}

/** 只负责把 PHPicker 的 Objective-C 代理回调转交给 Kotlin 采集器。 */
private class IOSPhotoPickerDelegate(
    /** 系统选择器完成后的回调。 */
    private val onFinished: (PHPickerViewController, List<*>) -> Unit,
) : NSObject(),
    PHPickerViewControllerDelegateProtocol {
    /** 转交选图或取消结果。 */
    override fun picker(
        picker: PHPickerViewController,
        didFinishPicking: List<*>,
    ) {
        onFinished(picker, didFinishPicking)
    }
}

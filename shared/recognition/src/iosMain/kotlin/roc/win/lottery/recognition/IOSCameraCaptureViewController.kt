package roc.win.lottery.recognition

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceDiscoverySession
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureDevicePositionBack
import platform.AVFoundation.AVCaptureDeviceRotationCoordinator
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInWideAngleCamera
import platform.AVFoundation.AVCaptureFlashModeAuto
import platform.AVFoundation.AVCaptureFlashModeOff
import platform.AVFoundation.AVCaptureFlashModeOn
import platform.AVFoundation.AVCapturePhoto
import platform.AVFoundation.AVCapturePhotoCaptureDelegateProtocol
import platform.AVFoundation.AVCapturePhotoOutput
import platform.AVFoundation.AVCapturePhotoSettings
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionPresetPhoto
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.fileDataRepresentation
import platform.AVFoundation.hasFlash
import platform.AVFoundation.isFlashModeSupported
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreGraphics.CGRectGetHeight
import platform.CoreGraphics.CGRectGetWidth
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.UIKit.NSTextAlignmentCenter
import platform.UIKit.UIAction
import platform.UIKit.UIButton
import platform.UIKit.UIButtonTypeSystem
import platform.UIKit.UIColor
import platform.UIKit.UIControlStateNormal
import platform.UIKit.UIFont
import platform.UIKit.UIImage
import platform.UIKit.UIImageView
import platform.UIKit.UILabel
import platform.UIKit.UIViewContentMode.UIViewContentModeScaleAspectFit
import platform.UIKit.UIViewController
import platform.UIKit.setAccessibilityLabel
import platform.UIKit.setFrame
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.WeakReference

/** iOS 应用内相机的内存结果，不把原始拍照文件写入磁盘。 */
internal sealed interface IOSCameraCaptureResult {
    /**
     * 用户确认使用当前成片。
     *
     * @property data AVFoundation 返回的原始成片数据，仅用于随后重绘去元数据。
     */
    data class Success(
        val data: NSData,
    ) : IOSCameraCaptureResult

    /** 用户主动关闭了相机。 */
    data object Cancelled : IOSCameraCaptureResult

    /**
     * 相机权限、设备或拍摄流程失败。
     *
     * @property message 不包含底层错误细节的用户提示。
     */
    data class Failure(
        val message: String,
    ) : IOSCameraCaptureResult
}

/** 当前设备是否提供 AVFoundation 视频采集设备。 */
internal fun isIOSCameraAvailable(): Boolean = backCameraDevice() != null

/** 只选择设备背面的内建广角相机，避免默认设备在不同硬件上指向前置镜头。 */
private fun backCameraDevice(): AVCaptureDevice? =
    AVCaptureDeviceDiscoverySession
        .discoverySessionWithDeviceTypes(
            deviceTypes = listOfNotNull(AVCaptureDeviceTypeBuiltInWideAngleCamera),
            mediaType = AVMediaTypeVideo,
            position = AVCaptureDevicePositionBack,
        ).devices
        .firstOrNull() as? AVCaptureDevice

/** 使用 AVFoundation 提供全屏取景、闪光灯、拍照、重拍和确认。 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
internal class IOSCameraCaptureViewController(
    /** 相机页面结束后的单次结果回调。 */
    private val onFinished: (IOSCameraCaptureResult) -> Unit,
) : UIViewController(nibName = null, bundle = null),
    AVCapturePhotoCaptureDelegateProtocol {
    /** 相机输入和照片输出所在的采集会话。 */
    private val captureSession = AVCaptureSession()

    /** 输出静态照片的 AVFoundation 组件。 */
    private val photoOutput = AVCapturePhotoOutput()

    /** 串行执行相机会话配置和启停，避免阻塞主线程。 */
    private val sessionQueue = dispatch_queue_create("roc.win.lottery.camera.session", null)

    /** 显示实时后置相机画面的图层。 */
    private val previewLayer = AVCaptureVideoPreviewLayer.layerWithSession(captureSession)

    /** 显示用户刚拍摄的真实成片。 */
    private val reviewImageView = UIImageView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0))

    /** 页面中央显示启动、拍摄和错误状态。 */
    private val statusLabel = UILabel(frame = CGRectMake(0.0, 0.0, 0.0, 0.0))

    /** 关闭相机按钮。 */
    private val closeButton = iconButton("xmark", "关闭相机") { finish(IOSCameraCaptureResult.Cancelled) }

    /** 闪光灯模式按钮。 */
    private val flashButton = textButton("闪光灯 自动", "切换闪光灯模式") { cycleFlashMode() }

    /** 拍照快门按钮。 */
    private val shutterButton = shutterButton { takePicture() }

    /** 删除当前成片并恢复实时取景的按钮。 */
    private val retakeButton = textButton("重拍", "重新拍摄彩票") { retakePicture() }

    /** 确认使用当前成片的按钮。 */
    private val usePhotoButton = textButton("使用照片", "使用当前照片") { confirmPicture() }

    /** 当前会话使用的后置相机设备。 */
    private var cameraDevice: AVCaptureDevice? = null

    /** 根据设备姿态分别提供水平取景和水平成片所需的旋转角。 */
    private var rotationCoordinator: AVCaptureDeviceRotationCoordinator? = null

    /** 用户确认前仅在内存中持有的当前原始成片数据。 */
    private var capturedPhotoData: NSData? = null

    /** 当前闪光灯模式，初始由系统自动判断。 */
    private var flashMode: Long = AVCaptureFlashModeAuto

    /** 防止取消、失败和确认路径重复恢复同一个挂起调用。 */
    private var resultDelivered: Boolean = false

    /** 创建全屏相机视图并开始检查相机权限。 */
    override fun viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor.blackColor
        previewLayer.setVideoGravity(AVLayerVideoGravityResizeAspectFill)
        view.layer.addSublayer(previewLayer)
        configureViews()
        requestCameraAccess()
    }

    /** 根据当前安全区域和窗口尺寸稳定摆放相机控件。 */
    override fun viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        val bounds = view.bounds
        val width = CGRectGetWidth(bounds)
        val height = CGRectGetHeight(bounds)
        val safeInsets = view.safeAreaInsets
        val safeTop = safeInsets.useContents { top }
        val safeBottom = safeInsets.useContents { bottom }
        previewLayer.setFrame(bounds)
        synchronizePreviewRotation()
        reviewImageView.setFrame(bounds)
        closeButton.setFrame(CGRectMake(16.0, safeTop + 12.0, 48.0, 48.0))
        flashButton.setFrame(CGRectMake(width - 156.0, safeTop + 14.0, 140.0, 44.0))
        statusLabel.setFrame(CGRectMake(24.0, height / 2.0 - 36.0, width - 48.0, 72.0))
        shutterButton.setFrame(CGRectMake(width / 2.0 - 38.0, height - safeBottom - 100.0, 76.0, 76.0))
        val actionWidth = (width - 64.0) / 2.0
        val actionY = height - safeBottom - 78.0
        retakeButton.setFrame(CGRectMake(24.0, actionY, actionWidth, 54.0))
        usePhotoButton.setFrame(CGRectMake(40.0 + actionWidth, actionY, actionWidth, 54.0))
    }

    /** 页面离开时在相机队列停止采集，释放硬件占用。 */
    override fun viewWillDisappear(animated: Boolean) {
        super.viewWillDisappear(animated)
        dispatch_async(sessionQueue) {
            if (captureSession.running) captureSession.stopRunning()
        }
    }

    /** 接收 AVFoundation 已处理成片，并在主线程进入拍后核对。 */
    override fun captureOutput(
        output: AVCapturePhotoOutput,
        didFinishProcessingPhoto: AVCapturePhoto,
        error: NSError?,
    ) {
        val data = if (error == null) didFinishProcessingPhoto.fileDataRepresentation() else null
        dispatch_async(dispatch_get_main_queue()) {
            if (data == null) {
                showCaptureError("拍照失败，请重试")
            } else {
                showReview(data)
            }
        }
    }

    /** 创建拍后预览、状态和全部相机操作控件。 */
    private fun configureViews() {
        reviewImageView.apply {
            backgroundColor = UIColor.blackColor
            contentMode = UIViewContentModeScaleAspectFit
            hidden = true
        }
        statusLabel.apply {
            text = "正在启动相机"
            textColor = UIColor.whiteColor
            font = UIFont.systemFontOfSize(17.0)
            textAlignment = NSTextAlignmentCenter
            numberOfLines = 0
        }
        shutterButton.enabled = false
        retakeButton.hidden = true
        usePhotoButton.hidden = true
        view.addSubview(reviewImageView)
        view.addSubview(closeButton)
        view.addSubview(flashButton)
        view.addSubview(statusLabel)
        view.addSubview(shutterButton)
        view.addSubview(retakeButton)
        view.addSubview(usePhotoButton)
    }

    /** 根据系统授权状态直接配置相机、请求权限或返回明确失败。 */
    private fun requestCameraAccess() {
        when (AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)) {
            AVAuthorizationStatusAuthorized -> {
                configureCamera()
            }

            AVAuthorizationStatusNotDetermined -> {
                AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
                    dispatch_async(dispatch_get_main_queue()) {
                        if (granted) {
                            configureCamera()
                        } else {
                            finish(IOSCameraCaptureResult.Failure("未获得相机权限，无法拍摄彩票"))
                        }
                    }
                }
            }

            else -> {
                finish(IOSCameraCaptureResult.Failure("未获得相机权限，无法拍摄彩票"))
            }
        }
    }

    /** 在串行队列装配后置相机输入和照片输出，并启动实时取景。 */
    private fun configureCamera() {
        dispatch_async(sessionQueue) {
            val device = backCameraDevice()
            val input = device?.let { AVCaptureDeviceInput.deviceInputWithDevice(it, null) }
            if (device == null || input == null) {
                finishOnMain(IOSCameraCaptureResult.Failure("当前设备没有可用相机"))
                return@dispatch_async
            }
            captureSession.beginConfiguration()
            captureSession.sessionPreset = AVCaptureSessionPresetPhoto
            val canConfigure = captureSession.canAddInput(input) && captureSession.canAddOutput(photoOutput)
            if (canConfigure) {
                captureSession.addInput(input)
                captureSession.addOutput(photoOutput)
            }
            captureSession.commitConfiguration()
            if (!canConfigure) {
                finishOnMain(IOSCameraCaptureResult.Failure("无法启动设备相机"))
                return@dispatch_async
            }
            cameraDevice = device
            captureSession.startRunning()
            dispatch_async(dispatch_get_main_queue()) { showCameraReady(device) }
        }
    }

    /** 相机会话启动后隐藏状态提示并启用可用操作。 */
    private fun showCameraReady(device: AVCaptureDevice) {
        if (resultDelivered) return
        rotationCoordinator = AVCaptureDeviceRotationCoordinator(device, previewLayer)
        synchronizePreviewRotation()
        statusLabel.hidden = true
        shutterButton.enabled = true
        flashButton.enabled = device.hasFlash()
        flashButton.alpha = if (device.hasFlash()) 1.0 else 0.45
    }

    /** 使用当前闪光灯模式请求一张静态照片。 */
    private fun takePicture() {
        if (capturedPhotoData != null || !shutterButton.enabled) return
        shutterButton.enabled = false
        statusLabel.apply {
            text = "正在拍摄"
            hidden = false
        }
        val settings = AVCapturePhotoSettings.photoSettings()
        val device = cameraDevice
        if (device?.hasFlash() == true && device.isFlashModeSupported(flashMode)) {
            settings.flashMode = flashMode
        }
        synchronizeCaptureRotation()
        photoOutput.capturePhotoWithSettings(settings, this)
    }

    /** 使用旋转协调器的实时取景角度更新预览连接。 */
    private fun synchronizePreviewRotation() {
        val connection = previewLayer.connection ?: return
        val angle = rotationCoordinator?.videoRotationAngleForHorizonLevelPreview ?: return
        if (connection.isVideoRotationAngleSupported(angle)) connection.videoRotationAngle = angle
    }

    /** 在每次拍照前使用独立的成片角度更新照片输出连接。 */
    private fun synchronizeCaptureRotation() {
        val connection = photoOutput.connectionWithMediaType(AVMediaTypeVideo) ?: return
        val angle = rotationCoordinator?.videoRotationAngleForHorizonLevelCapture ?: return
        if (connection.isVideoRotationAngleSupported(angle)) connection.videoRotationAngle = angle
    }

    /** 显示从 AVFoundation 成片数据解码出的真实拍后预览。 */
    private fun showReview(data: NSData) {
        val image = UIImage.imageWithData(data)
        if (image == null) {
            showCaptureError("无法预览拍照结果，请重试")
            return
        }
        capturedPhotoData = data
        reviewImageView.image = image
        reviewImageView.hidden = false
        previewLayer.setHidden(true)
        statusLabel.hidden = true
        shutterButton.hidden = true
        flashButton.hidden = true
        retakeButton.hidden = false
        usePhotoButton.hidden = false
        dispatch_async(sessionQueue) {
            if (captureSession.running) captureSession.stopRunning()
        }
    }

    /** 清除内存成片并恢复实时取景。 */
    private fun retakePicture() {
        capturedPhotoData = null
        reviewImageView.image = null
        reviewImageView.hidden = true
        previewLayer.setHidden(false)
        retakeButton.hidden = true
        usePhotoButton.hidden = true
        flashButton.hidden = false
        shutterButton.apply {
            hidden = false
            enabled = true
        }
        dispatch_async(sessionQueue) {
            if (!captureSession.running) captureSession.startRunning()
        }
    }

    /** 把当前内存成片交给采集器执行统一重绘和去元数据编码。 */
    private fun confirmPicture() {
        val data = capturedPhotoData
        if (data == null) {
            showCaptureError("拍照结果已失效，请重拍")
            retakePicture()
            return
        }
        capturedPhotoData = null
        finish(IOSCameraCaptureResult.Success(data))
    }

    /** 拍摄失败后显示错误并允许再次按下快门。 */
    private fun showCaptureError(message: String) {
        capturedPhotoData = null
        statusLabel.apply {
            text = message
            hidden = false
        }
        shutterButton.enabled = true
    }

    /** 在关闭、自动和开启之间循环切换闪光灯模式。 */
    private fun cycleFlashMode() {
        flashMode =
            when (flashMode) {
                AVCaptureFlashModeOff -> AVCaptureFlashModeAuto
                AVCaptureFlashModeAuto -> AVCaptureFlashModeOn
                else -> AVCaptureFlashModeOff
            }
        val label =
            when (flashMode) {
                AVCaptureFlashModeOff -> "关闭"
                AVCaptureFlashModeOn -> "开启"
                else -> "自动"
            }
        flashButton.setTitle("闪光灯 $label", forState = UIControlStateNormal)
        flashButton.setAccessibilityLabel("切换闪光灯模式，当前$label")
    }

    /** 保证从任意回调线程切回主线程结束页面。 */
    private fun finishOnMain(result: IOSCameraCaptureResult) {
        dispatch_async(dispatch_get_main_queue()) { finish(result) }
    }

    /** 只交付一次结果，关闭页面后再恢复采集器挂起调用。 */
    private fun finish(result: IOSCameraCaptureResult) {
        if (resultDelivered) return
        resultDelivered = true
        capturedPhotoData = null
        rotationCoordinator = null
        dismissViewControllerAnimated(true) { onFinished(result) }
    }

    /** 创建带系统符号的圆形工具按钮。 */
    private fun iconButton(
        systemName: String,
        accessibilityText: String,
        handler: IOSCameraCaptureViewController.() -> Unit,
    ): UIButton {
        val controller = WeakReference(this)
        return UIButton
            .buttonWithType(
                UIButtonTypeSystem,
                primaryAction = UIAction.actionWithHandler { controller.value?.handler() },
            ).apply {
                setImage(UIImage.systemImageNamed(systemName), forState = UIControlStateNormal)
                tintColor = UIColor.whiteColor
                backgroundColor = UIColor.colorWithWhite(0.1, alpha = 0.7)
                layer.cornerRadius = 24.0
                setAccessibilityLabel(accessibilityText)
            }
    }

    /** 创建拍后操作和闪光灯使用的深色文字按钮。 */
    private fun textButton(
        title: String,
        accessibilityText: String,
        handler: IOSCameraCaptureViewController.() -> Unit,
    ): UIButton {
        val controller = WeakReference(this)
        return UIButton
            .buttonWithType(
                UIButtonTypeSystem,
                primaryAction = UIAction.actionWithHandler { controller.value?.handler() },
            ).apply {
                setTitle(title, forState = UIControlStateNormal)
                setTitleColor(UIColor.whiteColor, forState = UIControlStateNormal)
                titleLabel?.font = UIFont.systemFontOfSize(17.0)
                backgroundColor = UIColor.colorWithWhite(0.1, alpha = 0.78)
                layer.cornerRadius = 8.0
                setAccessibilityLabel(accessibilityText)
            }
    }

    /** 创建尺寸稳定的白色圆形快门按钮。 */
    private fun shutterButton(handler: IOSCameraCaptureViewController.() -> Unit): UIButton {
        val controller = WeakReference(this)
        return UIButton
            .buttonWithType(
                UIButtonTypeSystem,
                primaryAction = UIAction.actionWithHandler { controller.value?.handler() },
            ).apply {
                backgroundColor = UIColor.whiteColor
                layer.cornerRadius = 38.0
                setAccessibilityLabel("拍摄彩票")
            }
    }
}

package roc.win.lottery.recognition

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** 全屏 CameraX 取景、拍照预览、重拍和确认页面。 */
class AndroidCameraCaptureActivity : ComponentActivity() {
    /** CameraX 实时取景控件。 */
    private lateinit var previewView: PreviewView

    /** 拍照后显示真实成片缩略图的控件。 */
    private lateinit var reviewImageView: ImageView

    /** 页面中央的状态文字。 */
    private lateinit var statusText: TextView

    /** 拍照快门按钮。 */
    private lateinit var shutterButton: View

    /** 闪光灯模式按钮。 */
    private lateinit var flashButton: Button

    /** 重拍和使用照片按钮容器。 */
    private lateinit var reviewActions: LinearLayout

    /** CameraX 拍照用例。 */
    private var imageCapture: ImageCapture? = null

    /** 当前绑定的后置相机。 */
    private var camera: Camera? = null

    /** 当前拍照生成、尚未交给调用方的私有暂存文件。 */
    private var currentCaptureFile: File? = null

    /** 当前拍后页面展示的真实成片缩略位图。 */
    private var reviewBitmap: Bitmap? = null

    /** 当前暂存文件是否已经随成功结果交给调用方。 */
    private var captureHandedOff: Boolean = false

    /** 当前闪光灯模式。 */
    private var flashMode: Int = ImageCapture.FLASH_MODE_AUTO

    /** 避免在主线程解码高像素拍照结果的单线程执行器。 */
    private val reviewDecodeExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /** 相机权限请求器。 */
    private val cameraPermissionLauncher =
        registerForActivityResult(RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                finishWithError("未获得相机权限，无法拍摄彩票")
            }
        }

    /** 创建取景页面、拦截返回操作并请求必要相机权限。 */
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        setContentView(createCameraContent())
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                /** 把系统返回键视为用户取消，并清理尚未使用的暂存文件。 */
                override fun handleOnBackPressed() {
                    cancelCapture()
                }
            },
        )
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /** Activity 销毁时清理没有交给调用方的原始拍照暂存文件。 */
    override fun onDestroy() {
        if (!captureHandedOff) currentCaptureFile?.delete()
        clearReviewBitmap()
        reviewDecodeExecutor.shutdown()
        super.onDestroy()
    }

    /** 创建全屏取景、顶部工具和底部拍照控件。 */
    private fun createCameraContent(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewView =
            PreviewView(this).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        root.addView(previewView, matchParentLayoutParams())

        reviewImageView =
            ImageView(this).apply {
                visibility = View.GONE
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.BLACK)
            }
        root.addView(reviewImageView, matchParentLayoutParams())

        val closeButton =
            ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                contentDescription = "关闭相机"
                setColorFilter(Color.WHITE)
                background = transparentCircleBackground()
                setOnClickListener { cancelCapture() }
            }
        root.addView(closeButton, anchoredLayoutParams(56, 56, Gravity.TOP or Gravity.START, 16, 20))

        flashButton =
            Button(this).apply {
                text = "闪光灯 自动"
                contentDescription = "切换闪光灯模式，当前自动"
                setTextColor(Color.WHITE)
                background = roundedDarkBackground()
                setOnClickListener { cycleFlashMode() }
            }
        root.addView(flashButton, anchoredLayoutParams(128, 48, Gravity.TOP or Gravity.END, 16, 24))

        statusText =
            TextView(this).apply {
                text = "正在启动相机"
                setTextColor(Color.WHITE)
                textSize = 16f
                gravity = Gravity.CENTER
            }
        root.addView(statusText, anchoredLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 52, Gravity.CENTER, 32, 0))

        shutterButton =
            View(this).apply {
                contentDescription = "拍摄彩票"
                isEnabled = false
                background = shutterBackground()
                setOnClickListener { takePicture() }
            }
        root.addView(shutterButton, anchoredLayoutParams(76, 76, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, 32))

        reviewActions =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                visibility = View.GONE
                addView(
                    actionButton("重拍") { retakePicture() },
                    LinearLayout.LayoutParams(0, 52.dp, 1f).apply { marginEnd = 8.dp },
                )
                addView(
                    actionButton("使用照片") { confirmPicture() },
                    LinearLayout.LayoutParams(0, 52.dp, 1f).apply { marginStart = 8.dp },
                )
            }
        root.addView(
            reviewActions,
            anchoredLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 52, Gravity.BOTTOM, 24, 34),
        )
        return root
    }

    /** 异步取得 CameraProvider，并绑定后置取景和拍照用例。 */
    private fun startCamera() {
        statusText.text = "正在启动相机"
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                runCatching {
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                    val capture =
                        ImageCapture
                            .Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .setFlashMode(flashMode)
                            .setTargetRotation(previewView.display.rotation)
                            .build()
                    provider.unbindAll()
                    camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                    imageCapture = capture
                    flashButton.isEnabled = camera?.cameraInfo?.hasFlashUnit() == true
                    statusText.visibility = View.GONE
                    shutterButton.isEnabled = true
                }.onFailure {
                    finishWithError("无法启动设备相机")
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    /** 拍摄一张 JPEG 到应用私有暂存目录。 */
    private fun takePicture() {
        val capture = imageCapture ?: return
        shutterButton.isEnabled = false
        statusText.apply {
            text = "正在拍摄"
            visibility = View.VISIBLE
        }
        val directory = File(cacheDir, ANDROID_CAMERA_CAPTURE_DIRECTORY_NAME).apply { mkdirs() }
        currentCaptureFile?.delete()
        val output = File(directory, "${UUID.randomUUID()}.jpg")
        currentCaptureFile = output
        val options = ImageCapture.OutputFileOptions.Builder(output).build()
        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                /** 保存成功后在后台解码真实 JPEG，并等待用户重拍或确认。 */
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    statusText.text = "正在生成预览"
                    decodeReviewBitmap(output)
                }

                /** 拍照失败时删除半成品并允许再次拍摄。 */
                override fun onError(exception: ImageCaptureException) {
                    output.delete()
                    currentCaptureFile = null
                    showCaptureError("拍照失败，请重试")
                }
            },
        )
    }

    /** 删除当前拍照结果并恢复实时取景。 */
    private fun retakePicture() {
        currentCaptureFile?.delete()
        currentCaptureFile = null
        clearReviewBitmap()
        reviewImageView.visibility = View.GONE
        previewView.visibility = View.VISIBLE
        reviewActions.visibility = View.GONE
        flashButton.visibility = View.VISIBLE
        shutterButton.apply {
            visibility = View.VISIBLE
            isEnabled = true
        }
    }

    /** 把当前私有暂存路径交给调用方，后续仍必须执行去元数据重编码。 */
    private fun confirmPicture() {
        val captureFile = currentCaptureFile?.takeIf { it.isFile }
        if (captureFile == null) {
            showCaptureError("拍照结果已失效，请重拍")
            retakePicture()
            return
        }
        captureHandedOff = true
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(ANDROID_CAMERA_PATH_EXTRA, captureFile.absolutePath),
        )
        finish()
    }

    /** 取消当前拍照流程，并删除所有尚未使用的暂存文件。 */
    private fun cancelCapture() {
        currentCaptureFile?.delete()
        currentCaptureFile = null
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    /** 返回明确错误并结束拍照页面。 */
    private fun finishWithError(message: String) {
        currentCaptureFile?.delete()
        currentCaptureFile = null
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(ANDROID_CAMERA_ERROR_EXTRA, message))
        finish()
    }

    /** 显示短暂错误状态并恢复快门。 */
    private fun showCaptureError(message: String) {
        statusText.apply {
            text = message
            visibility = View.VISIBLE
        }
        shutterButton.isEnabled = true
    }

    /** 在后台从成片 JPEG 解码并归一 EXIF 方向，避免展示与后续 OCR 输入不一致。 */
    private fun decodeReviewBitmap(output: File) {
        runCatching {
            reviewDecodeExecutor.execute {
                val bitmap =
                    runCatching {
                        if (!output.isFile) return@runCatching null
                        decodeNormalizedBitmap(
                            openStream = { output.inputStream() },
                            maxEdgePixels = REVIEW_MAX_EDGE_PIXELS,
                        )
                    }.getOrNull()
                runOnUiThread { showDecodedReview(output, bitmap) }
            }
        }.onFailure {
            output.delete()
            currentCaptureFile = null
            if (!isFinishing && !isDestroyed) showCaptureError("无法预览拍照结果，请重试")
        }
    }

    /** 在主线程接管已解码位图，并拒绝已取消或已失效的异步结果。 */
    private fun showDecodedReview(
        output: File,
        bitmap: Bitmap?,
    ) {
        if (isFinishing || isDestroyed || currentCaptureFile != output) {
            bitmap?.recycle()
            return
        }
        if (bitmap == null || !output.isFile) {
            output.delete()
            currentCaptureFile = null
            showCaptureError("无法预览拍照结果，请重试")
            return
        }
        clearReviewBitmap()
        reviewBitmap = bitmap
        reviewImageView.setImageBitmap(bitmap)
        reviewImageView.visibility = View.VISIBLE
        previewView.visibility = View.INVISIBLE
        shutterButton.visibility = View.GONE
        flashButton.visibility = View.GONE
        statusText.visibility = View.GONE
        reviewActions.visibility = View.VISIBLE
    }

    /** 断开 ImageView 引用并回收当前拍后预览位图。 */
    private fun clearReviewBitmap() {
        reviewImageView.setImageDrawable(null)
        reviewBitmap?.recycle()
        reviewBitmap = null
    }

    /** 在关闭、自动和开启之间循环切换闪光灯模式。 */
    private fun cycleFlashMode() {
        flashMode =
            when (flashMode) {
                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                else -> ImageCapture.FLASH_MODE_OFF
            }
        imageCapture?.flashMode = flashMode
        val label =
            when (flashMode) {
                ImageCapture.FLASH_MODE_OFF -> "关闭"
                ImageCapture.FLASH_MODE_ON -> "开启"
                else -> "自动"
            }
        flashButton.text = "闪光灯 $label"
        flashButton.contentDescription = "切换闪光灯模式，当前$label"
    }

    /** 创建页面操作按钮。 */
    private fun actionButton(
        label: String,
        action: () -> Unit,
    ): Button =
        Button(this).apply {
            text = label
            textSize = 17f
            setTextColor(Color.WHITE)
            background = roundedDarkBackground()
            setOnClickListener { action() }
        }

    /** 创建半透明圆形工具按钮背景。 */
    private fun transparentCircleBackground(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x66000000)
        }

    /** 创建拍照快门的稳定白色圆形背景。 */
    private fun shutterBackground(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setStroke(5.dp, 0x99ffffff.toInt())
        }

    /** 创建拍后操作和闪光灯按钮使用的深色圆角背景。 */
    private fun roundedDarkBackground(): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = 8.dp.toFloat()
            setColor(0xaa202020.toInt())
        }

    /** 创建填满父容器的布局参数。 */
    private fun matchParentLayoutParams(): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    /** 创建带锚点和边距的 FrameLayout 参数。 */
    private fun anchoredLayoutParams(
        width: Int,
        height: Int,
        gravity: Int,
        horizontalMargin: Int,
        verticalMargin: Int,
    ): FrameLayout.LayoutParams =
        FrameLayout
            .LayoutParams(
                if (width > 0) width.dp else width,
                if (height > 0) height.dp else height,
                gravity,
            ).apply {
                leftMargin = horizontalMargin.dp
                rightMargin = horizontalMargin.dp
                topMargin = verticalMargin.dp
                bottomMargin = verticalMargin.dp
            }

    /** 把密度无关像素转换为当前设备像素。 */
    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    /** Android 拍后预览常量。 */
    private companion object {
        /** 拍后预览保留的最长边，兼顾屏幕清晰度和内存占用。 */
        const val REVIEW_MAX_EDGE_PIXELS = 2048
    }
}

/** CameraX 原始暂存目录名称。 */
internal const val ANDROID_CAMERA_CAPTURE_DIRECTORY_NAME = "camera-captures"

/** CameraX 成功结果携带的私有暂存路径键。 */
internal const val ANDROID_CAMERA_PATH_EXTRA = "roc.win.lottery.camera.PATH"

/** CameraX 失败结果携带的通用错误说明键。 */
internal const val ANDROID_CAMERA_ERROR_EXTRA = "roc.win.lottery.camera.ERROR"

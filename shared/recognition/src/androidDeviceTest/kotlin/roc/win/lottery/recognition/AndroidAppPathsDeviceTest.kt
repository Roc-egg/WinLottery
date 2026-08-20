package roc.win.lottery.recognition

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 在 Android 虚拟机真实应用缓存中验证临时票图的目录边界和初始化清扫。 */
@RunWith(AndroidJUnit4::class)
class AndroidAppPathsDeviceTest {
    /** 重新初始化路径管理器时应删除遗留票图和 CameraX 原始暂存文件。 */
    @Test
    fun initializationRemovesAbandonedImageFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val initialPaths = AndroidAppPaths(context)
        val ticketImage = File(initialPaths.temporaryImageDirectory, "abandoned-ticket.jpg")
        val cameraDirectory = File(context.cacheDir, ANDROID_CAMERA_CAPTURE_DIRECTORY_NAME).apply { mkdirs() }
        val cameraCapture = File(cameraDirectory, "abandoned-camera.jpg")
        ticketImage.writeText("受控测试票图")
        cameraCapture.writeText("受控测试成片")

        AndroidAppPaths(context)

        assertFalse(ticketImage.exists())
        assertFalse(cameraCapture.exists())
        assertTrue(File(initialPaths.temporaryImageDirectory, ".nomedia").isFile)
    }

    /** 删除接口只允许处理临时票图目录的直属文件，目录外文件必须保留。 */
    @Test
    fun deletionRejectsFileOutsideTemporaryDirectory() =
        runTest {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val paths = AndroidAppPaths(context)
            val inside = File(paths.temporaryImageDirectory, "inside.jpg").apply { writeText("目录内") }
            val outside = File(context.cacheDir, "outside.jpg").apply { writeText("目录外") }
            try {
                assertTrue(paths.deleteTemporaryImage(imageRef(inside)))
                assertFalse(inside.exists())
                assertFalse(paths.deleteTemporaryImage(imageRef(outside)))
                assertTrue(outside.isFile)
            } finally {
                inside.delete()
                outside.delete()
            }
        }

    /** 创建只用于文件删除边界测试的匿名图片引用。 */
    private fun imageRef(file: File): ImageRef =
        ImageRef(
            id = "device-test-image",
            localPath = file.absolutePath,
            mimeType = "image/jpeg",
            widthPixels = 1,
            heightPixels = 1,
        )
}

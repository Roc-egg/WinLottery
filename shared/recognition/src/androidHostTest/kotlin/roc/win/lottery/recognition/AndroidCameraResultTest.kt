package roc.win.lottery.recognition

import android.app.Activity
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

/** 验证 Android CameraX 结果映射和私有暂存路径边界。 */
class AndroidCameraResultTest {
    /** 成功结果不应被提前映射为取消或失败。 */
    @Test
    fun successfulResultHasNoFailureMapping() {
        assertNull(mapCameraFailureResult(Activity.RESULT_OK, null))
    }

    /** 用户关闭相机且没有错误说明时应返回取消。 */
    @Test
    fun cancelledResultMapsToCancelled() {
        assertSame(ImageAcquisitionResult.Cancelled, mapCameraFailureResult(Activity.RESULT_CANCELED, null))
        assertSame(ImageAcquisitionResult.Cancelled, mapCameraFailureResult(Activity.RESULT_CANCELED, "  "))
    }

    /** 相机页返回错误说明时应保留对用户有用的失败信息。 */
    @Test
    fun explicitCameraErrorMapsToFailure() {
        val failure =
            assertIs<ImageAcquisitionResult.Failure>(
                mapCameraFailureResult(Activity.RESULT_CANCELED, "未获得相机权限，无法拍摄彩票"),
            )

        assertEquals("未获得相机权限，无法拍摄彩票", failure.message)
    }

    /** Activity 无法启动时应返回固定且不泄露异常细节的失败信息。 */
    @Test
    fun launchFailureMapsToSafeFailure() {
        val failure =
            assertIs<ImageAcquisitionResult.Failure>(
                mapCameraFailureResult(CAMERA_LAUNCH_FAILED_RESULT_CODE, "底层异常"),
            )

        assertEquals("无法启动应用内相机", failure.message)
    }

    /** 只接受相机暂存目录直属且真实存在的 JPEG 文件。 */
    @Test
    fun directJpegIsAccepted() =
        withCameraDirectory { directory ->
            val lowerCase = File(directory, "capture.jpg").apply { writeBytes(byteArrayOf(1)) }
            val upperCase = File(directory, "capture.JPG").apply { writeBytes(byteArrayOf(2)) }

            assertEquals(lowerCase.canonicalFile, validateDirectCameraJpeg(lowerCase.absolutePath, directory))
            assertEquals(upperCase.canonicalFile, validateDirectCameraJpeg(upperCase.absolutePath, directory))
        }

    /** 目录外文件和包含父目录跳转的路径都必须被拒绝。 */
    @Test
    fun outsidePathIsRejected() =
        withCameraDirectory { directory ->
            val outside = File(directory.parentFile, "outside.jpg").apply { writeBytes(byteArrayOf(1)) }

            assertNull(validateDirectCameraJpeg(outside.absolutePath, directory))
            assertNull(validateDirectCameraJpeg(File(directory, "../outside.jpg").path, directory))
        }

    /** 暂存目录的嵌套文件必须被拒绝，避免扩大 Activity 结果的可信范围。 */
    @Test
    fun nestedPathIsRejected() =
        withCameraDirectory { directory ->
            val nested =
                File(directory, "nested/capture.jpg").apply {
                    requireNotNull(parentFile).mkdirs()
                    writeBytes(byteArrayOf(1))
                }

            assertNull(validateDirectCameraJpeg(nested.absolutePath, directory))
        }

    /** 错误扩展名、缺失文件和空路径都必须被拒绝。 */
    @Test
    fun invalidFileIsRejected() =
        withCameraDirectory { directory ->
            val png = File(directory, "capture.png").apply { writeBytes(byteArrayOf(1)) }

            assertNull(validateDirectCameraJpeg(png.absolutePath, directory))
            assertNull(validateDirectCameraJpeg(File(directory, "missing.jpg").absolutePath, directory))
            assertNull(validateDirectCameraJpeg(null, directory))
            assertNull(validateDirectCameraJpeg("", directory))
        }

    /** 创建并在断言结束后清理独立的测试暂存目录。 */
    private fun withCameraDirectory(block: (File) -> Unit) {
        val root = createTempDirectory("winlottery-camera-test").toFile()
        val directory = File(root, ANDROID_CAMERA_CAPTURE_DIRECTORY_NAME).apply { mkdirs() }
        try {
            block(directory)
        } finally {
            root.deleteRecursively()
        }
    }
}

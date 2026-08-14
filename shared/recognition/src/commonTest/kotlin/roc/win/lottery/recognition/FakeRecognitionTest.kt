package roc.win.lottery.recognition

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** B1 平台能力 Fake 测试。 */
class FakeRecognitionTest {
    /** 桌面 Fake 必须拒绝应用内拍照。 */
    @Test
    fun desktopCameraIsUnavailable() =
        runTest {
            val acquirer = FakeImageAcquirer(supportsCamera = false)

            val result = acquirer.acquire(ImageAcquisitionSource.CAMERA)

            assertIs<ImageAcquisitionResult.Unavailable>(result)
        }

    /** 系统导图应生成轻量引用而非把图片字节放入状态。 */
    @Test
    fun systemPickerReturnsImageReference() =
        runTest {
            val result = FakeImageAcquirer(supportsCamera = false).acquire(ImageAcquisitionSource.SYSTEM_PICKER)

            val success = assertIs<ImageAcquisitionResult.Success>(result)
            assertEquals("image/jpeg", success.imageRef.mimeType)
            assertTrue(success.imageRef.localPath.startsWith("memory://"))
        }

    /** Fake OCR 输出必须明确标记引擎身份。 */
    @Test
    fun fakeRecognizerIsExplicitlyIdentified() =
        runTest {
            val imageRef = ImageRef("id", "memory://image", "image/jpeg", null, null)

            val result = FakeTicketRecognizer().recognize(imageRef)

            val success = assertIs<RecognitionResult.Success>(result)
            assertTrue(success.document.engineName.contains("Fake"))
        }
}

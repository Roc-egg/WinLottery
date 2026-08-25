package roc.win.lottery.app.ui

import kotlinx.coroutines.test.runTest
import roc.win.lottery.recognition.ImageRef
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertNull

/** Android 私有票图预览解码测试。 */
class TicketPreviewDecoderAndroidTest {
    /** 不存在的私有图片应安全返回空结果。 */
    @Test
    fun missingPrivateImageIsRejected() =
        runTest {
            val missingFile =
                File(
                    System.getProperty("java.io.tmpdir"),
                    "missing-ticket-preview-${UUID.randomUUID()}.png",
                )
            val bitmap =
                decodeTicketPreview(
                    imageRef = ImageRef("android-preview", missingFile.absolutePath, "image/png", 1, 1),
                    maxEdgePixels = 100,
                )

            assertNull(bitmap)
        }
}

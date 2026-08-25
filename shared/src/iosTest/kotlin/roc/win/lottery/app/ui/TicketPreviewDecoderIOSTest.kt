package roc.win.lottery.app.ui

import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import roc.win.lottery.recognition.ImageRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** iOS 私有票图预览解码测试。 */
class TicketPreviewDecoderIOSTest {
    /** 有效 JPEG 临时文件应通过 Skia 缩略解码为 Compose 位图。 */
    @Test
    fun privateImageFileIsDecoded() =
        runTest {
            val path = (NSTemporaryDirectory() + NSUUID().UUIDString + ".jpg").toPath()
            try {
                FileSystem.SYSTEM.write(path) { write(createTestJpeg(width = 200, height = 100)) }
                val bitmap =
                    decodeTicketPreview(
                        imageRef = ImageRef("ios-preview", path.toString(), "image/jpeg", 200, 100),
                        maxEdgePixels = 100,
                    )

                assertNotNull(bitmap)
                assertEquals(100, bitmap.width)
                assertEquals(50, bitmap.height)
            } finally {
                FileSystem.SYSTEM.delete(path, mustExist = false)
            }
        }

    /** 使用当前 iOS Skia 运行时生成一张不含外部数据的有效 JPEG。 */
    private fun createTestJpeg(
        width: Int,
        height: Int,
    ): ByteArray {
        val surface = Surface.makeRasterN32Premul(width, height)
        try {
            surface.canvas.clear(0xFFFFFFFF.toInt())
            val image = surface.makeImageSnapshot()
            try {
                val data = checkNotNull(image.encodeToData(EncodedImageFormat.JPEG, quality = 90))
                try {
                    return data.bytes
                } finally {
                    data.close()
                }
            } finally {
                image.close()
            }
        } finally {
            surface.close()
        }
    }
}

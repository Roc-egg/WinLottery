package roc.win.lottery.recognition

import kotlinx.coroutines.test.runTest
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Desktop PP-OCR RGB 解码边界测试。 */
class DesktopPpOcrRgbImageDecoderTest {
    /** 解码器应保留 RGB 顺序，并把透明像素铺为白色。 */
    @Test
    fun decoderProducesNormalizedRgbPixels() =
        runTest {
            val root = Files.createTempDirectory("win-lottery-rgb-decoder-").toFile()
            try {
                val file = root.resolve("sample.png")
                val image = BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB)
                image.setRGB(0, 0, Color(255, 0, 0, 255).rgb)
                image.setRGB(1, 0, Color(0, 0, 255, 0).rgb)
                assertTrue(ImageIO.write(image, "png", file))
                image.flush()

                val decoded =
                    DesktopPpOcrRgbImageDecoder().decode(
                        imageRef = imageRef(file.absolutePath),
                        maximumLongEdgePixels = 2,
                    )

                val rgb = assertNotNull(decoded)
                assertEquals(2, rgb.width)
                assertEquals(1, rgb.height)
                assertEquals(
                    listOf(255, 0, 0, 255, 255, 255),
                    rgb.pixels.map { channel -> channel.toInt() and 0xff },
                )
            } finally {
                root.deleteRecursively()
            }
        }

    /** 超长图片应等比缩小，非法上限和不存在的文件应安全失败。 */
    @Test
    fun decoderScalesLongEdgeAndRejectsInvalidInput() =
        runTest {
            val root = Files.createTempDirectory("win-lottery-rgb-scale-").toFile()
            try {
                val file = root.resolve("wide.jpg")
                val image = BufferedImage(8, 4, BufferedImage.TYPE_INT_RGB)
                assertTrue(ImageIO.write(image, "jpeg", file))
                image.flush()

                val decoded =
                    DesktopPpOcrRgbImageDecoder().decode(
                        imageRef = imageRef(file.absolutePath),
                        maximumLongEdgePixels = 4,
                    )

                val rgb = assertNotNull(decoded)
                assertEquals(4, rgb.width)
                assertEquals(2, rgb.height)
                assertNull(
                    DesktopPpOcrRgbImageDecoder().decode(
                        imageRef = imageRef(file.absolutePath),
                        maximumLongEdgePixels = 0,
                    ),
                )
                assertNull(
                    DesktopPpOcrRgbImageDecoder().decode(
                        imageRef = imageRef(root.resolve("missing.jpg").absolutePath),
                        maximumLongEdgePixels = 4,
                    ),
                )
            } finally {
                root.deleteRecursively()
            }
        }

    /** 创建不携带业务内容的测试图片引用。 */
    private fun imageRef(path: String): ImageRef =
        ImageRef(
            id = "00000000-0000-0000-0000-000000000000",
            localPath = path,
            mimeType = "image/png",
            widthPixels = null,
            heightPixels = null,
        )
}

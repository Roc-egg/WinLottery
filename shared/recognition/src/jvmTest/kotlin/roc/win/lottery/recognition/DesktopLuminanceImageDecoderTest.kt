package roc.win.lottery.recognition

import kotlinx.coroutines.test.runTest
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** 桌面亮度图片解码测试。 */
class DesktopLuminanceImageDecoderTest {
    /** 解码器应限制最长边，并使用与移动端一致的整数亮度权重。 */
    @Test
    fun decoderScalesImageAndProducesStableLuminance() =
        runTest {
            val root = Files.createTempDirectory("win-lottery-luminance-").toFile()
            try {
                val file = root.resolve("sample.png")
                val image = BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB)
                val graphics = image.createGraphics()
                graphics.color = Color(255, 0, 0)
                graphics.fillRect(0, 0, image.width, image.height)
                graphics.dispose()
                ImageIO.write(image, "png", file)
                image.flush()

                val sample =
                    DesktopLuminanceImageDecoder().decode(
                        imageRef =
                            ImageRef(
                                id = "sample",
                                localPath = file.absolutePath,
                                mimeType = "image/png",
                                widthPixels = 4,
                                heightPixels = 2,
                            ),
                        maximumLongEdgePixels = 2,
                    )

                val decoded = assertNotNull(sample)
                assertEquals(2, decoded.widthPixels)
                assertEquals(1, decoded.heightPixels)
                assertEquals(listOf(77, 77), decoded.pixels.map { it.toInt() and 0xFF })
            } finally {
                root.deleteRecursively()
            }
        }
}

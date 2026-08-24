package roc.win.lottery.recognition

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.writeToFile
import platform.UIKit.UIColor
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIRectFill
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** iOS PP-OCR RGB 图片解码器的 Simulator 测试。 */
@OptIn(ExperimentalForeignApi::class)
class IOSPpOcrRgbImageDecoderTest {
    /** 解码后的首行必须仍对应图片顶部，避免把文字上下镜像后送入模型。 */
    @Test
    fun decodedRgbKeepsTopToBottomRowOrder() =
        runTest {
            val path = NSTemporaryDirectory() + NSUUID().UUIDString + ".png"
            try {
                assertTrue(createTwoColorPng().writeToFile(path, atomically = true))

                val image =
                    IOSPpOcrRgbImageDecoder().decode(
                        imageRef = ImageRef("ios-rgb-orientation", path, "image/png", IMAGE_WIDTH, IMAGE_HEIGHT),
                        maximumLongEdgePixels = IMAGE_WIDTH,
                    )

                assertNotNull(image)
                assertEquals(IMAGE_WIDTH, image.width)
                assertEquals(IMAGE_HEIGHT, image.height)
                assertPixelIsRed(image, x = IMAGE_WIDTH / 2, y = TOP_SAMPLE_ROW)
                assertPixelIsBlue(image, x = IMAGE_WIDTH / 2, y = BOTTOM_SAMPLE_ROW)
            } finally {
                NSFileManager.defaultManager.removeItemAtPath(path, error = null)
            }
        }

    /** 创建上半红、下半蓝的无损图片，用于确认 CoreGraphics 行顺序。 */
    private fun createTwoColorPng() =
        run {
            val size = CGSizeMake(IMAGE_WIDTH.toDouble(), IMAGE_HEIGHT.toDouble())
            UIGraphicsBeginImageContextWithOptions(size, true, 1.0)
            try {
                UIColor.redColor.setFill()
                UIRectFill(CGRectMake(0.0, 0.0, IMAGE_WIDTH.toDouble(), HALF_HEIGHT.toDouble()))
                UIColor.blueColor.setFill()
                UIRectFill(
                    CGRectMake(
                        0.0,
                        HALF_HEIGHT.toDouble(),
                        IMAGE_WIDTH.toDouble(),
                        HALF_HEIGHT.toDouble(),
                    ),
                )
                val image = checkNotNull(UIGraphicsGetImageFromCurrentImageContext())
                checkNotNull(UIImagePNGRepresentation(image))
            } finally {
                UIGraphicsEndImageContext()
            }
        }

    /** 断言目标像素以红色通道为主。 */
    private fun assertPixelIsRed(
        image: RgbImage,
        x: Int,
        y: Int,
    ) {
        val offset = (y * image.width + x) * RGB_CHANNEL_COUNT
        assertTrue(image.pixels[offset].unsigned() > STRONG_CHANNEL_MINIMUM)
        assertTrue(image.pixels[offset + BLUE_CHANNEL_OFFSET].unsigned() < WEAK_CHANNEL_MAXIMUM)
    }

    /** 断言目标像素以蓝色通道为主。 */
    private fun assertPixelIsBlue(
        image: RgbImage,
        x: Int,
        y: Int,
    ) {
        val offset = (y * image.width + x) * RGB_CHANNEL_COUNT
        assertTrue(image.pixels[offset].unsigned() < WEAK_CHANNEL_MAXIMUM)
        assertTrue(image.pixels[offset + BLUE_CHANNEL_OFFSET].unsigned() > STRONG_CHANNEL_MINIMUM)
    }

    /** 把有符号字节恢复为零到 255 的通道值。 */
    private fun Byte.unsigned(): Int = toInt() and BYTE_MASK

    /** 测试图片和 RGB 像素格式常量。 */
    private companion object {
        /** 测试图片宽度。 */
        const val IMAGE_WIDTH = 20

        /** 测试图片高度。 */
        const val IMAGE_HEIGHT = 20

        /** 图片上下分区高度。 */
        const val HALF_HEIGHT = IMAGE_HEIGHT / 2

        /** 上半区内部采样行。 */
        const val TOP_SAMPLE_ROW = 2

        /** 下半区内部采样行。 */
        const val BOTTOM_SAMPLE_ROW = 17

        /** 每个像素的 RGB 通道数。 */
        const val RGB_CHANNEL_COUNT = 3

        /** 蓝色通道相对像素起点的偏移。 */
        const val BLUE_CHANNEL_OFFSET = 2

        /** 纯色主通道最低允许值。 */
        const val STRONG_CHANNEL_MINIMUM = 240

        /** 纯色非主通道最高允许值。 */
        const val WEAK_CHANNEL_MAXIMUM = 15

        /** 无符号字节掩码。 */
        const val BYTE_MASK = 0xff
    }
}

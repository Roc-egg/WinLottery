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
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIRectFill
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** iOS 亮度图片解码器的 Simulator 测试。 */
@OptIn(ExperimentalForeignApi::class)
class IOSLuminanceImageDecoderTest {
    /** 有效 JPEG 应缩放到指定长边，并输出每像素一个亮度字节。 */
    @Test
    fun validPrivateJpegIsDecodedAndScaled() =
        runTest {
            val path = NSTemporaryDirectory() + NSUUID().UUIDString + ".jpg"
            try {
                assertTrue(createGrayJpeg().writeToFile(path, atomically = true))

                val sample =
                    IOSLuminanceImageDecoder().decode(
                        imageRef = ImageRef("ios-luminance", path, "image/jpeg", 200, 100),
                        maximumLongEdgePixels = 100,
                    )

                assertNotNull(sample)
                assertEquals(100, sample.widthPixels)
                assertEquals(50, sample.heightPixels)
                assertEquals(5_000, sample.pixels.size)
                val average = sample.pixels.sumOf { it.toInt() and 0xFF } / sample.pixels.size.toDouble()
                assertTrue(average in 120.0..136.0)
            } finally {
                NSFileManager.defaultManager.removeItemAtPath(path, error = null)
            }
        }

    /** 使用 UIKit 创建不包含外部数据的中灰色 JPEG。 */
    private fun createGrayJpeg() =
        run {
            val size = CGSizeMake(200.0, 100.0)
            UIGraphicsBeginImageContextWithOptions(size, true, 1.0)
            try {
                UIColor.colorWithWhite(0.5, alpha = 1.0).setFill()
                UIRectFill(CGRectMake(0.0, 0.0, 200.0, 100.0))
                val image = checkNotNull(UIGraphicsGetImageFromCurrentImageContext())
                checkNotNull(UIImageJPEGRepresentation(image, 0.95))
            } finally {
                UIGraphicsEndImageContext()
            }
        }
}

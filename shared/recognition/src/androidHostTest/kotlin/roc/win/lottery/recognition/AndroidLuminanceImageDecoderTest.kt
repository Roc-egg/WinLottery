package roc.win.lottery.recognition

import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertNull

/** Android 亮度图片解码器的 Host 安全失败测试。 */
class AndroidLuminanceImageDecoderTest {
    /** 不存在的私有图片必须返回空，不泄露路径或抛出平台异常。 */
    @Test
    fun missingPrivateImageReturnsNull() =
        runTest {
            val missingFile =
                File(
                    System.getProperty("java.io.tmpdir"),
                    "missing-luminance-image-${UUID.randomUUID()}.jpg",
                )

            val sample =
                AndroidLuminanceImageDecoder().decode(
                    imageRef =
                        ImageRef(
                            id = "missing-android-luminance-image",
                            localPath = missingFile.absolutePath,
                            mimeType = "image/jpeg",
                            widthPixels = 1280,
                            heightPixels = 720,
                        ),
                    maximumLongEdgePixels = 512,
                )

            assertNull(sample)
        }
}

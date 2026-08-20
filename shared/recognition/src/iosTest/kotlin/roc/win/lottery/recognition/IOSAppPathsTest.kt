package roc.win.lottery.recognition

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 在 iOS Simulator 真实临时目录中验证票图目录边界和初始化清扫。 */
@OptIn(ExperimentalForeignApi::class)
class IOSAppPathsTest {
    /** 重新初始化路径管理器时应删除上次异常退出遗留的临时票图。 */
    @Test
    fun initializationRemovesAbandonedImageFiles() {
        val initialPaths = IOSAppPaths()
        val abandonedImage = "${initialPaths.temporaryImageDirectory}/abandoned-ticket.jpg"
        assertTrue(fileManager.createFileAtPath(abandonedImage, contents = null, attributes = null))

        IOSAppPaths()

        assertFalse(fileManager.fileExistsAtPath(abandonedImage))
        assertTrue(fileManager.fileExistsAtPath(initialPaths.temporaryImageDirectory))
    }

    /** 删除接口只允许处理临时票图目录的直属文件，目录外文件必须保留。 */
    @Test
    fun deletionRejectsFileOutsideTemporaryDirectory() =
        runTest {
            val paths = IOSAppPaths()
            val inside = "${paths.temporaryImageDirectory}/inside.jpg"
            val outside = "${NSTemporaryDirectory()}winlottery-outside.jpg"
            assertTrue(fileManager.createFileAtPath(inside, contents = null, attributes = null))
            assertTrue(fileManager.createFileAtPath(outside, contents = null, attributes = null))
            try {
                assertTrue(paths.deleteTemporaryImage(imageRef(inside)))
                assertFalse(fileManager.fileExistsAtPath(inside))
                assertFalse(paths.deleteTemporaryImage(imageRef(outside)))
                assertTrue(fileManager.fileExistsAtPath(outside))
            } finally {
                fileManager.removeItemAtPath(inside, error = null)
                fileManager.removeItemAtPath(outside, error = null)
            }
        }

    /** 创建只用于文件删除边界测试的匿名图片引用。 */
    private fun imageRef(path: String): ImageRef =
        ImageRef(
            id = "simulator-test-image",
            localPath = path,
            mimeType = "image/jpeg",
            widthPixels = 1,
            heightPixels = 1,
        )

    /** iOS 临时文件操作入口。 */
    private val fileManager: NSFileManager
        get() = NSFileManager.defaultManager
}

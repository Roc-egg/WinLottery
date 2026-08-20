@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package roc.win.lottery.recognition

import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes

/** iOS 显式启用的本地探索图像素质量回归。 */
class IOSPixelImageQualityLiveTest {
    /** 匿名遍历指定目录直属图片，确保新增质量规则不会拒绝当前探索基线。 */
    @Test
    fun explicitlyEnabledExplorationSamplesRemainAccepted() =
        runTest(timeout = LIVE_TEST_TIMEOUT) {
            val environment = NSProcessInfo.processInfo.environment
            val rawDirectory = environment[SAMPLE_DIRECTORY_ENVIRONMENT_VARIABLE] as? String ?: return@runTest
            val directory = rawDirectory.trimEnd(PATH_SEPARATOR)
            val sampleNames =
                NSFileManager.defaultManager
                    .contentsOfDirectoryAtPath(directory, error = null)
                    .orEmpty()
                    .mapNotNull { it as? String }
                    .filter(::isSupportedImageName)
                    .sorted()
            assertEquals(EXPECTED_SAMPLE_COUNT, sampleNames.size, "iOS 探索图目录样本数量发生变化")

            val analyzer = PixelImageQualityAnalyzer(IOSLuminanceImageDecoder())
            sampleNames.forEachIndexed { index, name ->
                val result =
                    analyzer.analyze(
                        ImageRef(
                            id = "ios-quality-sample-${index + 1}",
                            localPath = "$directory$PATH_SEPARATOR$name",
                            mimeType = mimeTypeForName(name),
                            widthPixels = null,
                            heightPixels = null,
                        ),
                    )
                assertIs<ImageQualityResult.Passed>(
                    result,
                    "iOS 当前探索图第 ${index + 1} 张被像素质量闸门拒绝：$result",
                )
            }
        }

    /** 判断直属文件名是否属于当前移动入口支持的图片类型。 */
    private fun isSupportedImageName(name: String): Boolean =
        name.substringAfterLast(EXTENSION_SEPARATOR, missingDelimiterValue = "").lowercase() in SUPPORTED_EXTENSIONS

    /** 根据受支持扩展名返回归一化 MIME 类型。 */
    private fun mimeTypeForName(name: String): String =
        when (name.substringAfterLast(EXTENSION_SEPARATOR).lowercase()) {
            PNG_EXTENSION -> "image/png"
            else -> "image/jpeg"
        }

    /** 显式探索图回归参数。 */
    private companion object {
        /** 本地探索图目录环境变量。 */
        const val SAMPLE_DIRECTORY_ENVIRONMENT_VARIABLE = "WINLOTTERY_IOS_QUALITY_SAMPLE_DIR"

        /** 当前根目录直属探索图数量。 */
        const val EXPECTED_SAMPLE_COUNT = 13

        /** 路径分隔符。 */
        const val PATH_SEPARATOR = '/'

        /** 文件扩展名分隔符。 */
        const val EXTENSION_SEPARATOR = '.'

        /** PNG 扩展名。 */
        const val PNG_EXTENSION = "png"

        /** 当前移动入口支持的质量回归扩展名。 */
        val SUPPORTED_EXTENSIONS = setOf("jpg", "jpeg", PNG_EXTENSION)

        /** 全部探索图本地解码与分析的最大时长。 */
        val LIVE_TEST_TIMEOUT = 1.minutes
    }
}

package roc.win.lottery.recognition

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/** 移动端 PP-OCRv5 模型与字典资源锁定测试。 */
class PpOcrMobileResourcesTest {
    /** 四段 ONNX 和两份字符字典必须保持已验证的字节数与 SHA-256。 */
    @Test
    fun bundledResourcesMatchModelLock() {
        RESOURCE_LOCKS.forEach { (fileName, expected) ->
            val bytes = resourceBytes(fileName)

            assertEquals(expected.byteCount, bytes.size, "$fileName 字节数不匹配")
            assertEquals(expected.sha256, bytes.sha256(), "$fileName SHA-256 不匹配")
        }
    }

    /** 实际字典必须保持模型类别依赖的行数和首尾字符。 */
    @Test
    fun bundledDictionaryMatchesModelClasses() {
        val characters = parsePpOcrCharacters(resourceBytes("characters.txt").decodeToString())

        assertEquals(PP_OCR_CHARACTER_COUNT, characters.size)
        assertEquals("\u3000", characters.first())
        assertEquals("\uD83D\uDD67", characters.last())
    }

    /** 英文识别字典必须保持模型类别依赖的行数和首尾字符。 */
    @Test
    fun bundledLatinDictionaryMatchesModelClasses() {
        val characters =
            parsePpOcrCharacters(
                resourceBytes("characters_latin.txt").decodeToString(),
                PP_OCR_LATIN_CHARACTER_COUNT,
            )

        assertEquals(PP_OCR_LATIN_CHARACTER_COUNT, characters.size)
        assertEquals("0", characters.first())
        assertEquals("☺", characters.last())
    }

    /** 从测试类路径读取一个共享移动模型资源。 */
    private fun resourceBytes(fileName: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("$RESOURCE_ROOT/$fileName")) {
            "$fileName 测试资源缺失"
        }.use { input -> input.readBytes() }

    /** 计算资源字节的 SHA-256。 */
    private fun ByteArray.sha256(): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(this)
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }

    /** 单个资源文件的预期锁。 */
    private data class ResourceLock(
        /** 预期字节数。 */
        val byteCount: Int,
        /** 预期小写十六进制 SHA-256。 */
        val sha256: String,
    )

    /** 与 `model-lock.json` 保持一致的移动资源锁。 */
    private companion object {
        /** 移动模型在类路径中的绝对目录。 */
        const val RESOURCE_ROOT = "/roc/win/lottery/recognition/models/ppocrv5"

        /** 三段模型和字典的长度与哈希。 */
        val RESOURCE_LOCKS =
            mapOf(
                "detection.onnx" to
                    ResourceLock(
                        4_766_440,
                        "c8d9b07063420ce5365c74e42532de48238feeeedcdb7a330b195708bc38a93f",
                    ),
                "orientation.onnx" to
                    ResourceLock(
                        1_016_850,
                        "4d6027cad43b04171d6eafa20e7342fea7a657724e96d642564392b6df1e9768",
                    ),
                "recognition.onnx" to
                    ResourceLock(
                        16_529_870,
                        "bcb195e3463eb9e46ef419b8a01ea4729577de5fd63c64f0a762e43bd64256e7",
                    ),
                "characters.txt" to
                    ResourceLock(
                        74_012,
                        "d1979e9f794c464c0d2e0b70a7fe14dd978e9dc644c0e71f14158cdf8342af1b",
                    ),
                "recognition_latin.onnx" to
                    ResourceLock(
                        7_843_511,
                        "70b2450eed39599af6b996c27a2f1a0ef30eeb49f9f66dd3e74f28f652befc89",
                    ),
                "characters_latin.txt" to
                    ResourceLock(
                        1_416,
                        "e025a66d31f327ba0c232e03f407ae8d105e1e709e7ccb3f408aa778c24e70d6",
                    ),
            )
    }
}

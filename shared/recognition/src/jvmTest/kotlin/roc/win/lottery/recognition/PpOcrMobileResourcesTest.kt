package roc.win.lottery.recognition

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/** 三端共用 PP-OCRv5 模型与字典资源锁定测试。 */
class PpOcrMobileResourcesTest {
    /** 四段 ONNX 和两份字符字典必须保持已验证的字节数与 SHA-256。 */
    @Test
    fun bundledResourcesMatchModelLock() {
        sharedResourceLocks().forEach { (fileName, expected) ->
            val bytes = resourceBytes(fileName)

            assertEquals(expected.byteCount, bytes.size.toLong(), "$fileName 字节数不匹配")
            assertEquals(expected.sha256, bytes.sha256(), "$fileName SHA-256 不匹配")
        }
    }

    /** 实际字典必须保持模型类别依赖的行数和首尾字符。 */
    @Test
    fun bundledDictionaryMatchesModelClasses() {
        val characters =
            parsePpOcrCharacters(
                resourceBytes(PP_OCR_DICTIONARY_FILE_NAME).decodeToString(),
            )

        assertEquals(PP_OCR_CHARACTER_COUNT, characters.size)
        assertEquals("\u3000", characters.first())
        assertEquals("\uD83D\uDD67", characters.last())
    }

    /** 英文识别字典必须保持模型类别依赖的行数和首尾字符。 */
    @Test
    fun bundledLatinDictionaryMatchesModelClasses() {
        val characters =
            parsePpOcrCharacters(
                resourceBytes(PP_OCR_LATIN_DICTIONARY_FILE_NAME).decodeToString(),
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

    /** 按实际资源文件名合并四段模型和两份字典公共锁。 */
    private fun sharedResourceLocks(): Map<String, PpOcrResourceLock> =
        buildMap {
            PP_OCR_MODEL_RESOURCE_LOCKS.forEach { (model, lock) ->
                put(model.fileName, lock)
            }
            putAll(PP_OCR_DICTIONARY_RESOURCE_LOCKS)
        }

    /** 共用资源在测试类路径中的固定目录。 */
    private companion object {
        /** PP-OCRv5 模型在类路径中的绝对目录。 */
        const val RESOURCE_ROOT = "/roc/win/lottery/recognition/models/ppocrv5"
    }
}

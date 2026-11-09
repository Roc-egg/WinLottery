package roc.win.lottery.persistence

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 桌面本机 AI 连接配置仓库测试。 */
class JvmAiConnectionStoreTest {
    /** 每个测试独立的应用数据目录。 */
    private val directory: File = createTempDirectory("winlottery-ai-connection").toFile()

    /** 删除测试目录。 */
    @AfterTest
    fun deleteDirectory() {
        directory.deleteRecursively()
    }

    /** 桌面只保存服务商、接口格式、Base URL 和模型，文件中不出现密钥，且仅当前用户可读写。 */
    @Test
    fun savesConnectionWithoutApiKey() =
        runTest {
            val store = createJvmAiConnectionStore(directory)
            store.save(
                SavedAiConnection(
                    "测试服务",
                    "https://api.example.test/v1",
                    "model-v1",
                    "secret-key",
                    "ANTHROPIC_MESSAGES",
                ),
            )

            val file = File(directory, "ai-connection.json")
            assertFalse(store.savesApiKey)
            assertFalse(file.readText().contains("secret-key"))
            assertEquals(
                SavedAiConnection("测试服务", "https://api.example.test/v1", "model-v1", "", "ANTHROPIC_MESSAGES"),
                createJvmAiConnectionStore(directory).load(),
            )
            runCatching { Files.getPosixFilePermissions(file.toPath()) }.getOrNull()?.let { permissions ->
                assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), permissions)
            }
        }

    /** 清除或保存空配置都会删除文件；损坏的文件被丢弃而不是抛错。 */
    @Test
    fun clearsAndDiscardsCorruptedFile() =
        runTest {
            val store = createJvmAiConnectionStore(directory)
            val file = File(directory, "ai-connection.json")
            store.save(SavedAiConnection(providerName = "测试服务"))
            store.clear()
            assertFalse(file.exists())

            store.save(SavedAiConnection(model = "model-v1"))
            store.save(SavedAiConnection(apiKey = "only-key"))
            assertFalse(file.exists())

            file.writeText("{不是合法 JSON")
            assertNull(store.load())
            assertFalse(file.exists())
        }

    /** 编码可往返，调试文本不包含密钥。 */
    @Test
    fun encodingRoundTripsAndHidesKeyInToString() {
        val connection = SavedAiConnection("测试服务", "https://api.example.test/v1/responses", "model-v1", "secret-key")

        assertEquals(connection, decodeSavedAiConnection(connection.encode()))
        assertFalse(connection.toString().contains("secret-key"))
        assertTrue(connection.toString().contains("已隐藏"))
        assertNull(decodeSavedAiConnection("[]"))
    }

    /** 早期版本保存的配置没有接口格式，地址存放在 endpointUrl 键下，仍能读取。 */
    @Test
    fun decodesLegacyConnectionWithoutFormat() {
        val legacy =
            """{"providerName":"测试服务","endpointUrl":"https://api.example.test/v1/responses",""" +
                """"model":"model-v1","apiKey":"secret-key"}"""

        val decoded = decodeSavedAiConnection(legacy)

        assertEquals("https://api.example.test/v1/responses", decoded?.baseUrl)
        assertEquals("", decoded?.apiFormat)
        assertTrue(SavedAiConnection(baseUrl = "x").encode().contains("\"endpointUrl\""))
    }
}

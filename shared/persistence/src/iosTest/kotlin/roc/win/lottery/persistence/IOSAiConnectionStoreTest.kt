package roc.win.lottery.persistence

import kotlinx.coroutines.test.runTest
import platform.Security.errSecNotAvailable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * iOS 钥匙串 AI 连接配置仓库测试，使用独立服务名，不影响应用条目。
 *
 * Gradle 以独立模式启动的模拟器没有钥匙串服务，此时测试直接返回；测试程序已嵌入模拟器应用身份，
 * 在已启动的模拟器上执行 `xcrun simctl spawn <设备> test.kexe` 可完整验证钥匙串读写。
 */
class IOSAiConnectionStoreTest {
    /** 保存、覆盖、读取和清除都直接作用于模拟器钥匙串。 */
    @Test
    fun keychainRoundTrip() =
        runTest {
            val store = IOSAiConnectionStore(service = TEST_SERVICE)
            val initialClear = runCatching { store.clear() }
            if (initialClear.exceptionOrNull()?.message?.endsWith("：$errSecNotAvailable") == true) return@runTest
            initialClear.getOrThrow()
            assertNull(store.load())

            store.save(SavedAiConnection("测试服务", "https://api.example.test/v1/responses", "model-v1", "secret-key"))
            store.save(SavedAiConnection("测试服务", "https://api.example.test/v1/responses", "model-v2", "secret-key-2"))

            assertTrue(store.savesApiKey)
            assertEquals(
                SavedAiConnection("测试服务", "https://api.example.test/v1/responses", "model-v2", "secret-key-2"),
                IOSAiConnectionStore(service = TEST_SERVICE).load(),
            )
            store.save(SavedAiConnection())
            assertNull(store.load())
        }

    /** 测试固定值。 */
    private companion object {
        /** 与应用条目隔离的钥匙串服务名。 */
        const val TEST_SERVICE = "roc.win.lottery.ai-connection.test"
    }
}

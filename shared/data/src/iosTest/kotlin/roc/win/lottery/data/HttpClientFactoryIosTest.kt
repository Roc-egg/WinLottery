package roc.win.lottery.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** iOS Debug 验收代理配置测试。 */
class HttpClientFactoryIosTest {
    /** Release 二进制必须忽略验收环境变量。 */
    @Test
    fun releaseBinaryIgnoresAcceptanceProxy() {
        assertNull(buildIosAcceptanceProxyUrl(rawPort = "18443", isDebugBinary = false))
    }

    /** 非纯数字、越界或带空白的端口不得启用代理。 */
    @Test
    fun invalidAcceptanceProxyPortsAreRejected() {
        listOf(null, "", "0", "65536", "+443", " 443", "443 ", "12a3", "123456")
            .forEach { rawPort ->
                assertNull(buildIosAcceptanceProxyUrl(rawPort = rawPort, isDebugBinary = true))
            }
    }

    /** 合法端口只能生成固定回环地址。 */
    @Test
    fun validAcceptanceProxyUsesLoopbackHost() {
        assertEquals(
            "http://127.0.0.1:18443",
            buildIosAcceptanceProxyUrl(rawPort = "18443", isDebugBinary = true).toString(),
        )
    }
}

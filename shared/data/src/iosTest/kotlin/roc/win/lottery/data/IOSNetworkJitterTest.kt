@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package roc.win.lottery.data

import kotlinx.coroutines.test.runTest
import platform.Foundation.NSProcessInfo
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/** iOS 当前 Simulator 的受控网络抖动专项。 */
class IOSNetworkJitterTest {
    /** 使用 Darwin 真实网络栈验证一次重试、连接中断和延迟响应。 */
    @Test
    fun explicitlyEnabledJitterScenariosUsePlatformNetworkStack() =
        runTest(timeout = TEST_TIMEOUT) {
            val environment = NSProcessInfo.processInfo.environment
            if (environment[ENABLE_ENVIRONMENT] as? String != ENABLED_VALUE) return@runTest
            check((environment[ACCEPTANCE_PROXY_PORT_ENVIRONMENT] as? String).isNullOrBlank()) {
                "iOS 网络抖动专项必须移除本地代理配置"
            }
            val baseUrl =
                requireNotNull(environment[BASE_URL_ENVIRONMENT] as? String) {
                    "启用 iOS 网络抖动专项时必须提供本地服务地址"
                }
            val token =
                requireNotNull(environment[TOKEN_ENVIRONMENT] as? String) {
                    "启用 iOS 网络抖动专项时必须提供匿名会话标识"
                }
            val client = createPlatformHttpClient()
            try {
                verifyNetworkJitterPolicy(client = client, baseUrl = baseUrl, token = token)
            } finally {
                client.close()
            }
        }

    /** iOS 测试进程环境变量。 */
    private companion object {
        /** 显式启用专项的环境变量。 */
        const val ENABLE_ENVIRONMENT = "WINLOTTERY_IOS_NETWORK_JITTER"

        /** 本地服务根地址环境变量。 */
        const val BASE_URL_ENVIRONMENT = "WINLOTTERY_IOS_NETWORK_JITTER_BASE_URL"

        /** 匿名会话标识环境变量。 */
        const val TOKEN_ENVIRONMENT = "WINLOTTERY_IOS_NETWORK_JITTER_TOKEN"

        /** 必须保持为空的既有验收代理环境变量。 */
        const val ACCEPTANCE_PROXY_PORT_ENVIRONMENT = "WINLOTTERY_IOS_ACCEPTANCE_PROXY_PORT"

        /** 专项启用值。 */
        const val ENABLED_VALUE = "1"

        /** 单项测试最大执行时长。 */
        val TEST_TIMEOUT = 1.minutes
    }
}

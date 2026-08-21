package roc.win.lottery.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Android 当前虚拟机的受控网络抖动专项。 */
@RunWith(AndroidJUnit4::class)
class AndroidNetworkJitterTest {
    /** 使用 OkHttp 真实网络栈验证一次重试、连接中断和延迟响应。 */
    @Test
    fun explicitlyEnabledJitterScenariosUsePlatformNetworkStack() =
        runBlocking {
            val arguments = InstrumentationRegistry.getArguments()
            if (arguments.getString(ENABLE_ARGUMENT) != ENABLED_VALUE) return@runBlocking
            val baseUrl =
                requireNotNull(arguments.getString(BASE_URL_ARGUMENT)) {
                    "启用 Android 网络抖动专项时必须提供本地服务地址"
                }
            val token =
                requireNotNull(arguments.getString(TOKEN_ARGUMENT)) {
                    "启用 Android 网络抖动专项时必须提供匿名会话标识"
                }
            val client = createPlatformHttpClient()
            try {
                verifyNetworkJitterPolicy(client = client, baseUrl = baseUrl, token = token)
            } finally {
                client.close()
            }
        }

    /** Android instrumentation 参数。 */
    private companion object {
        /** 显式启用专项的参数名。 */
        const val ENABLE_ARGUMENT = "winlotteryNetworkJitter"

        /** 本地服务根地址参数名。 */
        const val BASE_URL_ARGUMENT = "winlotteryNetworkJitterBaseUrl"

        /** 匿名会话标识参数名。 */
        const val TOKEN_ARGUMENT = "winlotteryNetworkJitterToken"

        /** 专项启用值。 */
        const val ENABLED_VALUE = "1"
    }
}

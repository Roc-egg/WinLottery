package roc.win.lottery.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.http.Url
import platform.Foundation.NSProcessInfo
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

/** 使用 iOS Darwin 引擎创建统一配置的客户端。 */
actual fun createPlatformHttpClient(): HttpClient = createDarwinHttpClient(isAiClient = false)

/** 使用 iOS Darwin 引擎创建禁止重定向和重试的 AI 客户端。 */
internal actual fun createPlatformAiHttpClient(): HttpClient = createDarwinHttpClient(isAiClient = true)

/** 创建共用验收代理边界的 Darwin 客户端。 */
private fun createDarwinHttpClient(isAiClient: Boolean): HttpClient {
    val debugBinary = isDebugBinary()
    val acceptanceProxyUrl =
        buildIosAcceptanceProxyUrl(
            rawPort =
                if (debugBinary) {
                    NSProcessInfo.processInfo.environment[ACCEPTANCE_PROXY_PORT_ENVIRONMENT] as? String
                } else {
                    null
                },
            isDebugBinary = debugBinary,
        )
    return HttpClient(Darwin) {
        if (isAiClient) {
            configureAiHttpClient()
        } else {
            configureLotteryHttpClient()
        }
        engine {
            acceptanceProxyUrl?.let { proxyUrl ->
                configureSession {
                    connectionProxyDictionary =
                        mapOf<Any?, Any?>(
                            HTTP_PROXY_ENABLED_KEY to ENABLED_PROXY_VALUE,
                            HTTP_PROXY_HOST_KEY to proxyUrl.host,
                            HTTP_PROXY_PORT_KEY to proxyUrl.port,
                            HTTPS_PROXY_ENABLED_KEY to ENABLED_PROXY_VALUE,
                            HTTPS_PROXY_HOST_KEY to proxyUrl.host,
                            HTTPS_PROXY_PORT_KEY to proxyUrl.port,
                        )
                }
            }
        }
    }
}

/**
 * 为 iOS 验收构造固定回环地址代理，Release 或非法端口一律忽略。
 *
 * @param rawPort 环境变量中的代理端口。
 * @param isDebugBinary 当前是否为 Debug 二进制。
 * @return 仅指向本机的合法 HTTP 代理地址，未启用时返回空。
 */
internal fun buildIosAcceptanceProxyUrl(
    rawPort: String?,
    isDebugBinary: Boolean,
): Url? {
    if (!isDebugBinary || rawPort.isNullOrEmpty()) return null
    if (rawPort.length > MAX_PORT_DIGITS || rawPort.any { it !in '0'..'9' }) return null
    val port = rawPort.toIntOrNull()?.takeIf { it in MIN_PROXY_PORT..MAX_PROXY_PORT } ?: return null
    return Url("http://$LOOPBACK_HOST:$port")
}

/** 返回当前 Kotlin/Native 二进制是否为 Debug 构建。 */
@OptIn(ExperimentalNativeApi::class)
private fun isDebugBinary(): Boolean = Platform.isDebugBinary

/** iOS 验收代理端口环境变量。 */
private const val ACCEPTANCE_PROXY_PORT_ENVIRONMENT = "WINLOTTERY_IOS_ACCEPTANCE_PROXY_PORT"

/** 验收代理固定回环主机。 */
private const val LOOPBACK_HOST = "127.0.0.1"

/** TCP 端口最小合法值。 */
private const val MIN_PROXY_PORT = 1

/** TCP 端口最大合法值。 */
private const val MAX_PROXY_PORT = 65_535

/** TCP 端口的最大十进制位数。 */
private const val MAX_PORT_DIGITS = 5

/** Darwin HTTP 代理启用键。 */
private const val HTTP_PROXY_ENABLED_KEY = "HTTPEnable"

/** Darwin HTTP 代理主机键。 */
private const val HTTP_PROXY_HOST_KEY = "HTTPProxy"

/** Darwin HTTP 代理端口键。 */
private const val HTTP_PROXY_PORT_KEY = "HTTPPort"

/** Darwin HTTPS 代理启用键。 */
private const val HTTPS_PROXY_ENABLED_KEY = "HTTPSEnable"

/** Darwin HTTPS 代理主机键。 */
private const val HTTPS_PROXY_HOST_KEY = "HTTPSProxy"

/** Darwin HTTPS 代理端口键。 */
private const val HTTPS_PROXY_PORT_KEY = "HTTPSPort"

/** Darwin 代理字典中的启用值。 */
private const val ENABLED_PROXY_VALUE = 1

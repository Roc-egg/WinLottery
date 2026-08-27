package roc.win.lottery.data

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import roc.win.lottery.domain.AiAnalysisProtocol

/** 创建使用当前平台系统 TLS 和网络栈的 HTTP 客户端。 */
expect fun createPlatformHttpClient(): HttpClient

/** 创建不重定向、不重试且不记录请求内容的 AI 专用 HTTP 客户端。 */
internal expect fun createPlatformAiHttpClient(): HttpClient

/** 为各平台引擎安装统一且保守的客户端策略。 */
internal fun HttpClientConfig<*>.configureLotteryHttpClient() {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            },
        )
    }
    install(HttpTimeout) {
        connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
        requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
        socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS
    }
    install(HttpRequestRetry) {
        maxRetries = MAX_RETRIES
        retryIf { request, response ->
            request.method == HttpMethod.Get && response.status.value >= SERVER_ERROR_STATUS
        }
        retryOnExceptionIf { request, _ -> request.method == HttpMethod.Get }
        exponentialDelay()
    }
}

/** 为 AI POST 请求安装冻结的超时和禁止重定向策略。 */
internal fun HttpClientConfig<*>.configureAiHttpClient() {
    followRedirects = false
    install(HttpTimeout) {
        connectTimeoutMillis = AI_CONNECT_TIMEOUT_MILLIS
        requestTimeoutMillis = AI_REQUEST_TIMEOUT_MILLIS
        socketTimeoutMillis = AI_REQUEST_TIMEOUT_MILLIS
    }
}

/** 网络超时和重试的 B1 基线。 */
private object NetworkPolicy {
    /** 建连超时毫秒数。 */
    const val CONNECT_TIMEOUT_MILLIS = 10_000L

    /** 整体请求超时毫秒数。 */
    const val REQUEST_TIMEOUT_MILLIS = 15_000L

    /** 套接字读写超时毫秒数。 */
    const val SOCKET_TIMEOUT_MILLIS = 15_000L

    /** GET 瞬时错误最多重试次数。 */
    const val MAX_RETRIES = 1

    /** 服务端错误状态码起点。 */
    const val SERVER_ERROR_STATUS = 500
}

/** 建连超时毫秒数。 */
private const val CONNECT_TIMEOUT_MILLIS = NetworkPolicy.CONNECT_TIMEOUT_MILLIS

/** 整体请求超时毫秒数。 */
private const val REQUEST_TIMEOUT_MILLIS = NetworkPolicy.REQUEST_TIMEOUT_MILLIS

/** 套接字读写超时毫秒数。 */
private const val SOCKET_TIMEOUT_MILLIS = NetworkPolicy.SOCKET_TIMEOUT_MILLIS

/** GET 瞬时错误最多重试次数。 */
private const val MAX_RETRIES = NetworkPolicy.MAX_RETRIES

/** 服务端错误状态码起点。 */
private const val SERVER_ERROR_STATUS = NetworkPolicy.SERVER_ERROR_STATUS

/** AI 请求建连超时毫秒数。 */
private const val AI_CONNECT_TIMEOUT_MILLIS = 10_000L

/** AI 请求和套接字超时毫秒数。 */
private const val AI_REQUEST_TIMEOUT_MILLIS = AiAnalysisProtocol.REQUEST_TIMEOUT_SECONDS * 1_000L

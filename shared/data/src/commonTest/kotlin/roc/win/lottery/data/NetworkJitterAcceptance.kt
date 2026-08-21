package roc.win.lottery.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * 受控本地服务返回的请求计数。
 *
 * @property retry 503 场景的请求次数。
 * @property drop 连接中断场景的请求次数。
 * @property delay 固定延迟场景的请求次数。
 */
@Serializable
internal data class NetworkJitterRequestCounts(
    val retry: Int,
    val drop: Int,
    val delay: Int,
)

/**
 * 使用平台真实 HTTP 引擎验证有限重试和延迟响应。
 *
 * @param client 已安装正式网络策略的平台客户端。
 * @param baseUrl 当前虚拟机可访问的本地服务根地址。
 * @param token 隔离当前平台请求计数的匿名会话标识。
 */
internal suspend fun verifyNetworkJitterPolicy(
    client: HttpClient,
    baseUrl: String,
    token: String,
) {
    val normalizedBaseUrl = baseUrl.trimEnd('/')

    val retryResponse = client.get("$normalizedBaseUrl/retry/$token")
    assertEquals(HttpStatusCode.OK, retryResponse.status, "503 后的一次 GET 重试应成功")

    val dropResponse = client.get("$normalizedBaseUrl/drop/$token")
    assertEquals(HttpStatusCode.OK, dropResponse.status, "连接中断后的一次 GET 重试应成功")

    val delayStart = TimeSource.Monotonic.markNow()
    val delayResponse = client.get("$normalizedBaseUrl/delay/$token")
    val delayMillis = delayStart.elapsedNow().inWholeMilliseconds
    assertEquals(HttpStatusCode.OK, delayResponse.status, "延迟响应应在正式超时内成功")
    assertTrue(delayMillis >= MINIMUM_OBSERVED_DELAY_MILLIS, "未观察到受控延迟：${delayMillis}ms")

    val counts = client.get("$normalizedBaseUrl/stats/$token").body<NetworkJitterRequestCounts>()
    assertEquals(NetworkJitterRequestCounts(retry = 2, drop = 2, delay = 1), counts)
}

/** 为调度误差预留余量后的最小可观察延迟。 */
private const val MINIMUM_OBSERVED_DELAY_MILLIS = 700L

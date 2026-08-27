package roc.win.lottery.data

import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.io.readByteArray
import roc.win.lottery.domain.AiAnalysisOutputValidator
import roc.win.lottery.domain.AiAnalysisProtocol
import roc.win.lottery.domain.AiAnalysisRequest

/**
 * 使用 Responses 风格严格结构输出协议的首个可配置 AI Provider。
 *
 * @property httpClient 禁止重定向、POST 重试和请求日志的专用客户端。
 * @property outputValidator 所有成功响应都必须再次经过的本地彩票规则校验器。
 */
class ResponsesAiAnalysisProvider internal constructor(
    private val httpClient: HttpClient,
    private val outputValidator: AiAnalysisOutputValidator,
) : AiAnalysisProvider {
    /** 创建只使用禁止重定向、重试和日志的生产网络客户端。 */
    constructor() : this(
        httpClient = createPlatformAiHttpClient(),
        outputValidator = AiAnalysisOutputValidator(),
    )

    /** 生成与实际 POST 请求体完全一致的无密钥确认预览。 */
    override fun preview(
        configuration: AiProviderConfiguration,
        request: AiAnalysisRequest,
    ): AiProviderPreviewResult {
        val prepared = ResponsesAiRequestBuilder.build(configuration, request)
        if (prepared.preview.requestBodyByteCount > AiAnalysisProtocol.MAX_REQUEST_BODY_BYTES) {
            return AiProviderPreviewResult.Invalid(
                "AI 请求体超过 ${AiAnalysisProtocol.MAX_REQUEST_BODY_BYTES} 字节上限",
            )
        }
        return AiProviderPreviewResult.Success(prepared.preview)
    }

    /** 只向用户确认的 HTTPS 地址发送一次请求，并对所有异常失败关闭。 */
    override suspend fun analyze(
        configuration: AiProviderConfiguration,
        request: AiAnalysisRequest,
    ): AiProviderResult {
        val prepared = ResponsesAiRequestBuilder.build(configuration, request)
        if (prepared.preview.requestBodyByteCount > AiAnalysisProtocol.MAX_REQUEST_BODY_BYTES) {
            return failure(
                AiProviderFailureCode.REQUEST_TOO_LARGE,
                "AI 请求体超过 ${AiAnalysisProtocol.MAX_REQUEST_BODY_BYTES} 字节上限",
            )
        }
        return try {
            val response =
                httpClient.post(configuration.endpointUrl) {
                    header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                    header(HttpHeaders.Authorization, "Bearer ${configuration.apiKey}")
                    header(HttpHeaders.CacheControl, "no-store")
                    header(HttpHeaders.UserAgent, USER_AGENT)
                    contentType(ContentType.Application.Json)
                    timeout {
                        requestTimeoutMillis = AI_REQUEST_TIMEOUT_MILLIS
                        socketTimeoutMillis = AI_REQUEST_TIMEOUT_MILLIS
                    }
                    setBody(prepared.body)
                }
            if (response.request.url.toString() != configuration.endpointUrl) {
                return failure(AiProviderFailureCode.REDIRECT_BLOCKED, "AI 服务尝试跳转到未确认地址")
            }
            if (response.status.value in REDIRECT_STATUS_RANGE) {
                return failure(AiProviderFailureCode.REDIRECT_BLOCKED, "AI 服务尝试跳转到未确认地址")
            }
            if (!response.status.isSuccess()) {
                return mapHttpFailure(response.status)
            }
            val contentType =
                response.headers[HttpHeaders.ContentType]
                    ?.substringBefore(';')
                    ?.trim()
                    ?.lowercase()
            if (contentType != ContentType.Application.Json.toString()) {
                return failure(AiProviderFailureCode.INVALID_RESPONSE, "AI 服务响应类型不是 JSON")
            }
            val declaredLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declaredLength != null && declaredLength > MAX_RESPONSE_BYTES) {
                return failure(AiProviderFailureCode.RESPONSE_TOO_LARGE, "AI 服务响应超过本地读取上限")
            }
            val bytes =
                response
                    .bodyAsChannel()
                    .readRemaining(MAX_RESPONSE_BYTES.toLong() + 1L)
                    .readByteArray()
            if (bytes.size > MAX_RESPONSE_BYTES) {
                return failure(AiProviderFailureCode.RESPONSE_TOO_LARGE, "AI 服务响应超过本地读取上限")
            }
            val rawResponse = bytes.decodeToString(throwOnInvalidSequence = true)
            ResponsesAiResponseParser.parse(rawResponse, request, outputValidator)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: HttpRequestTimeoutException) {
            failure(AiProviderFailureCode.TIMEOUT, "AI 服务请求超时，未自动重试")
        } catch (_: ConnectTimeoutException) {
            failure(AiProviderFailureCode.TIMEOUT, "连接 AI 服务超时，未自动重试")
        } catch (_: SocketTimeoutException) {
            failure(AiProviderFailureCode.TIMEOUT, "读取 AI 服务响应超时，未自动重试")
        } catch (_: IOException) {
            failure(AiProviderFailureCode.NETWORK_UNAVAILABLE, "当前无法连接 AI 服务，未自动重试")
        } catch (_: CharacterCodingException) {
            failure(AiProviderFailureCode.INVALID_RESPONSE, "AI 服务响应不是合法 UTF-8")
        } catch (_: Exception) {
            failure(AiProviderFailureCode.INVALID_RESPONSE, "AI 服务请求失败或响应结构异常")
        }
    }

    /** 将 HTTP 状态映射为不包含服务端响应正文的稳定失败。 */
    private fun mapHttpFailure(status: HttpStatusCode): AiProviderResult.Failure =
        when (status.value) {
            HttpStatusCode.Unauthorized.value,
            HttpStatusCode.Forbidden.value,
            -> {
                failure(AiProviderFailureCode.AUTHENTICATION_FAILED, "AI 密钥无效或账户无权使用该模型")
            }

            HttpStatusCode.RequestTimeout.value -> {
                failure(AiProviderFailureCode.TIMEOUT, "AI 服务请求超时，未自动重试")
            }

            HttpStatusCode.TooManyRequests.value -> {
                failure(AiProviderFailureCode.RATE_LIMITED, "AI 服务当前限流，请稍后由你主动重试")
            }

            in CLIENT_ERROR_STATUS_RANGE -> {
                failure(AiProviderFailureCode.REQUEST_REJECTED, "AI 服务拒绝当前请求结构或参数")
            }

            in SERVER_ERROR_STATUS_RANGE -> {
                failure(AiProviderFailureCode.SERVICE_UNAVAILABLE, "AI 服务暂时不可用，未自动重试")
            }

            else -> {
                failure(AiProviderFailureCode.SERVICE_UNAVAILABLE, "AI 服务返回 HTTP ${status.value}")
            }
        }

    /** 构造稳定失败结果。 */
    private fun failure(
        code: AiProviderFailureCode,
        message: String,
    ): AiProviderResult.Failure =
        AiProviderResult.Failure(
            code = code,
            message = message,
        )

    /** Responses 网络边界常量。 */
    private companion object {
        /** 不包含设备标识的固定客户端标识。 */
        const val USER_AGENT = "WinLottery/1.4 personal-use"

        /** 完整响应允许读取的最大字节数。 */
        const val MAX_RESPONSE_BYTES = 64 * 1024

        /** 单次 AI 请求和套接字等待毫秒数。 */
        const val AI_REQUEST_TIMEOUT_MILLIS = AiAnalysisProtocol.REQUEST_TIMEOUT_SECONDS * 1_000L

        /** HTTP 重定向状态范围。 */
        val REDIRECT_STATUS_RANGE = 300..399

        /** HTTP 客户端错误状态范围。 */
        val CLIENT_ERROR_STATUS_RANGE = 400..499

        /** HTTP 服务端错误状态范围。 */
        val SERVER_ERROR_STATUS_RANGE = 500..599
    }
}

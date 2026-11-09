package roc.win.lottery.data

import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import roc.win.lottery.domain.AiAnalysisOutputValidator
import roc.win.lottery.domain.AiAnalysisProtocol
import roc.win.lottery.domain.AiAnalysisRequest

/**
 * 按用户选择的接口格式（Anthropic Messages、OpenAI Chat Completions、OpenAI Responses）请求的可配置 AI Provider。
 *
 * @property httpClient 禁止重定向、POST 重试和请求日志的专用客户端。
 * @property outputValidator 所有成功响应都必须再次经过的本地彩票规则校验器。
 */
class HttpAiAnalysisProvider internal constructor(
    private val httpClient: HttpClient,
    private val outputValidator: AiAnalysisOutputValidator,
) : AiAnalysisProvider,
    AutoCloseable {
    /** 创建只使用禁止重定向、重试和日志的生产网络客户端。 */
    constructor() : this(
        httpClient = createPlatformAiHttpClient(),
        outputValidator = AiAnalysisOutputValidator(),
    )

    /** 应用会话结束时关闭 AI 专用网络客户端。 */
    override fun close() {
        httpClient.close()
    }

    /** 生成与实际 POST 请求体完全一致的无密钥确认预览。 */
    override fun preview(
        configuration: AiProviderConfiguration,
        request: AiAnalysisRequest,
    ): AiProviderPreviewResult {
        val prepared = AiRequestBuilder.build(configuration, request)
        if (prepared.preview.requestBodyByteCount > AiAnalysisProtocol.MAX_REQUEST_BODY_BYTES) {
            return AiProviderPreviewResult.Invalid(
                "AI 请求体超过 ${AiAnalysisProtocol.MAX_REQUEST_BODY_BYTES} 字节上限",
            )
        }
        return AiProviderPreviewResult.Success(prepared.preview)
    }

    /** 只向用户确认的地址发送一次请求，并对所有异常失败关闭。 */
    override suspend fun analyze(
        configuration: AiProviderConfiguration,
        request: AiAnalysisRequest,
    ): AiProviderResult {
        val prepared = AiRequestBuilder.build(configuration, request)
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
                    authenticate(configuration.apiFormat, configuration.apiKey)
                    header(HttpHeaders.CacheControl, "no-store")
                    header(HttpHeaders.UserAgent, USER_AGENT)
                    contentType(ContentType.Application.Json)
                    // 非流式请求在模型生成完毕前没有数据返回，超时随输出上限放宽。
                    val timeoutMillis = AiAnalysisProtocol.requestTimeoutSeconds(request.maxOutputTokens) * 1_000L
                    timeout {
                        requestTimeoutMillis = timeoutMillis
                        socketTimeoutMillis = timeoutMillis
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
                return mapHttpFailure(response, configuration.apiKey)
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
            parseAiResponse(configuration.apiFormat, rawResponse, request, outputValidator)
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

    /** 用户主动触发时 GET 一次同协议、同主机的模型列表，只解析模型标识，不自动重试。 */
    override suspend fun listModels(request: AiModelCatalogRequest): AiModelCatalogResult =
        try {
            val response =
                httpClient.get(request.modelsUrl) {
                    header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                    authenticate(request.apiFormat, request.apiKey)
                    header(HttpHeaders.CacheControl, "no-store")
                    header(HttpHeaders.UserAgent, USER_AGENT)
                    timeout {
                        requestTimeoutMillis = MODEL_CATALOG_TIMEOUT_MILLIS
                        socketTimeoutMillis = MODEL_CATALOG_TIMEOUT_MILLIS
                    }
                }
            when {
                response.request.url.toString() != request.modelsUrl ||
                    response.status.value in REDIRECT_STATUS_RANGE -> {
                    catalogFailure(AiProviderFailureCode.REDIRECT_BLOCKED, "AI 服务尝试跳转到未确认地址")
                }

                response.status == HttpStatusCode.NotFound -> {
                    catalogFailure(AiProviderFailureCode.REQUEST_REJECTED, "该服务没有提供模型列表接口，请手动填写模型")
                }

                !response.status.isSuccess() -> {
                    mapHttpFailure(response, request.apiKey).let { failure ->
                        catalogFailure(failure.code, failure.message)
                    }
                }

                response.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { it > MAX_MODEL_CATALOG_BYTES } ==
                    true -> {
                    catalogFailure(AiProviderFailureCode.RESPONSE_TOO_LARGE, "模型列表超过本地读取上限")
                }

                else -> {
                    val bytes =
                        response
                            .bodyAsChannel()
                            .readRemaining(MAX_MODEL_CATALOG_BYTES.toLong() + 1L)
                            .readByteArray()
                    if (bytes.size > MAX_MODEL_CATALOG_BYTES) {
                        catalogFailure(AiProviderFailureCode.RESPONSE_TOO_LARGE, "模型列表超过本地读取上限")
                    } else {
                        val models = parseModelCatalog(bytes.decodeToString(throwOnInvalidSequence = true))
                        if (models.isEmpty()) {
                            catalogFailure(AiProviderFailureCode.INVALID_RESPONSE, "服务商没有返回可用的模型")
                        } else {
                            AiModelCatalogResult.Success(models)
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: HttpRequestTimeoutException) {
            catalogFailure(AiProviderFailureCode.TIMEOUT, "获取模型列表超时，未自动重试")
        } catch (_: ConnectTimeoutException) {
            catalogFailure(AiProviderFailureCode.TIMEOUT, "连接 AI 服务超时，未自动重试")
        } catch (_: SocketTimeoutException) {
            catalogFailure(AiProviderFailureCode.TIMEOUT, "读取模型列表超时，未自动重试")
        } catch (_: IOException) {
            catalogFailure(AiProviderFailureCode.NETWORK_UNAVAILABLE, "当前无法连接 AI 服务，未自动重试")
        } catch (_: Exception) {
            catalogFailure(AiProviderFailureCode.INVALID_RESPONSE, "模型列表响应结构异常")
        }

    /** 按接口格式添加鉴权请求头：Anthropic 使用 `x-api-key` 与版本头，OpenAI 格式使用 Bearer 令牌。 */
    private fun HttpRequestBuilder.authenticate(
        apiFormat: AiApiFormat,
        apiKey: String,
    ) {
        when (apiFormat) {
            AiApiFormat.ANTHROPIC_MESSAGES -> {
                header(ANTHROPIC_API_KEY_HEADER, apiKey)
                header(ANTHROPIC_VERSION_HEADER, ANTHROPIC_VERSION)
            }

            AiApiFormat.OPENAI_CHAT_COMPLETIONS,
            AiApiFormat.OPENAI_RESPONSES,
            -> {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }
        }
    }

    /**
     * 将 HTTP 失败映射为稳定失败编码，并在说明中附上状态码和服务商返回的简短错误说明。
     *
     * 错误正文最多读取 [MAX_ERROR_BODY_BYTES] 字节，说明中出现的密钥会被替换；读取失败时只给出状态码。
     */
    private suspend fun mapHttpFailure(
        response: HttpResponse,
        apiKey: String,
    ): AiProviderResult.Failure {
        val status = response.status.value
        val (code, summary) =
            when (status) {
                HttpStatusCode.Unauthorized.value,
                HttpStatusCode.Forbidden.value,
                -> {
                    AiProviderFailureCode.AUTHENTICATION_FAILED to "AI 密钥无效或账户无权使用该模型"
                }

                HttpStatusCode.NotFound.value -> {
                    AiProviderFailureCode.REQUEST_REJECTED to "接口地址或模型不存在，请检查 Base URL、接口格式和模型"
                }

                HttpStatusCode.RequestTimeout.value -> {
                    AiProviderFailureCode.TIMEOUT to "AI 服务请求超时，未自动重试"
                }

                HttpStatusCode.TooManyRequests.value -> {
                    AiProviderFailureCode.RATE_LIMITED to "AI 服务当前限流，请稍后由你主动重试"
                }

                in CLIENT_ERROR_STATUS_RANGE -> {
                    AiProviderFailureCode.REQUEST_REJECTED to "AI 服务拒绝当前请求结构或参数"
                }

                in SERVER_ERROR_STATUS_RANGE -> {
                    AiProviderFailureCode.SERVICE_UNAVAILABLE to "AI 服务暂时不可用，未自动重试"
                }

                else -> {
                    AiProviderFailureCode.SERVICE_UNAVAILABLE to "AI 服务返回异常状态"
                }
            }
        val detail =
            try {
                val bytes =
                    response
                        .bodyAsChannel()
                        .readRemaining(MAX_ERROR_BODY_BYTES.toLong())
                        .readByteArray()
                providerErrorDetail(bytes.decodeToString(), apiKey)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        val message =
            buildString {
                append(summary)
                append("（HTTP ")
                append(status)
                append('）')
                detail?.let { text ->
                    append("。服务商说明：")
                    append(text)
                }
            }
        return failure(code, message)
    }

    /** 构造模型列表失败结果。 */
    private fun catalogFailure(
        code: AiProviderFailureCode,
        message: String,
    ): AiModelCatalogResult.Failure = AiModelCatalogResult.Failure(code, message)

    /** 构造稳定失败结果。 */
    private fun failure(
        code: AiProviderFailureCode,
        message: String,
    ): AiProviderResult.Failure =
        AiProviderResult.Failure(
            code = code,
            message = message,
        )

    /** AI 网络边界常量。 */
    private companion object {
        /** 不包含设备标识的固定客户端标识。 */
        const val USER_AGENT = "WinLottery/1.4 personal-use"

        /** 完整响应允许读取的最大字节数；部分服务会在响应中附带完整思考过程，按最大输出上限留足余量。 */
        const val MAX_RESPONSE_BYTES = 1024 * 1024

        /** 模型列表请求和套接字等待毫秒数。 */
        const val MODEL_CATALOG_TIMEOUT_MILLIS = 20_000L

        /** 模型列表允许读取的最大字节数；部分聚合服务的列表带有完整元数据，体积较大。 */
        const val MAX_MODEL_CATALOG_BYTES = 4 * 1024 * 1024

        /** 错误响应最多读取的字节数，只用于提取简短说明。 */
        const val MAX_ERROR_BODY_BYTES = 16 * 1024

        /** Anthropic 密钥请求头。 */
        const val ANTHROPIC_API_KEY_HEADER = "x-api-key"

        /** Anthropic API 版本请求头。 */
        const val ANTHROPIC_VERSION_HEADER = "anthropic-version"

        /** Anthropic Messages API 版本。 */
        const val ANTHROPIC_VERSION = "2023-06-01"

        /** HTTP 重定向状态范围。 */
        val REDIRECT_STATUS_RANGE = 300..399

        /** HTTP 客户端错误状态范围。 */
        val CLIENT_ERROR_STATUS_RANGE = 400..499

        /** HTTP 服务端错误状态范围。 */
        val SERVER_ERROR_STATUS_RANGE = 500..599
    }
}

/**
 * 从模型列表响应中提取模型标识。
 *
 * 兼容 OpenAI 风格的 `{"data":[{"id":…}]}`、部分服务的 `{"models":[{"id"|"name":…}]}` 和顶层数组；
 * 只保留能直接通过模型字段校验的标识，去重后按字母排序。结构不符时抛出异常。
 */
internal fun parseModelCatalog(text: String): List<String> {
    val root = Json.parseToJsonElement(text)
    val entries =
        when (root) {
            is JsonArray -> root
            is JsonObject -> (root["data"] ?: root["models"]) as? JsonArray ?: error("模型列表缺少 data 数组")
            else -> error("模型列表不是 JSON 对象或数组")
        }
    return entries
        .mapNotNull { entry ->
            val value =
                when (entry) {
                    is JsonObject -> (entry["id"] ?: entry["name"]) as? JsonPrimitive
                    is JsonPrimitive -> entry
                    else -> null
                }
            value?.takeIf { it.isString }?.content?.trim()
        }.filter(AI_MODEL_ID_PATTERN::matches)
        .distinct()
        .sorted()
}

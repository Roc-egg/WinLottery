package roc.win.lottery.data

import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.authority
import io.ktor.http.encodedPath
import roc.win.lottery.domain.AiAnalysisRequest
import roc.win.lottery.domain.AiAnalysisResult

/**
 * AI 服务接口格式，决定请求路径、鉴权请求头、请求体和响应解析方式。
 *
 * @property displayName 连接配置下拉框展示的名称。
 * @property baseUrlExample Base URL 输入框的示例地址。
 */
enum class AiApiFormat(
    val displayName: String,
    val baseUrlExample: String,
) {
    /** Anthropic Messages：请求 `{Base URL}/v1/messages`，以 `x-api-key` 鉴权，按提示词约束 JSON 输出。 */
    ANTHROPIC_MESSAGES("Anthropic Messages", "https://api.anthropic.com"),

    /** OpenAI Chat Completions：请求 `{Base URL}/chat/completions`，要求 JSON 对象输出，兼容服务最多。 */
    OPENAI_CHAT_COMPLETIONS("OpenAI Chat Completions", "https://api.openai.com/v1"),

    /** OpenAI Responses：请求 `{Base URL}/responses`，使用严格 JSON Schema 结构输出。 */
    OPENAI_RESPONSES("OpenAI Responses", "https://api.openai.com/v1"),
}

/** AI 服务连接配置问题的稳定编码。 */
enum class AiProviderConfigurationProblemCode {
    /** 服务商展示名称为空或过长。 */
    INVALID_PROVIDER_NAME,

    /** Base URL 不是结构完整的绝对 URL。 */
    INVALID_ENDPOINT,

    /** Base URL 既不是 HTTP 也不是 HTTPS。 */
    UNSUPPORTED_SCHEME,

    /** 模型标识为空、过长或包含不允许的字符。 */
    INVALID_MODEL,

    /** 密钥为空、过长或包含空白控制字符。 */
    INVALID_API_KEY,
}

/**
 * 已通过本地校验的用户自备 AI 服务配置。
 *
 * 密钥只对数据模块内部可见，`toString` 始终输出脱敏占位符。
 *
 * @property displayName 用户确认页展示的服务商名称。
 * @property apiFormat 请求和响应使用的接口格式。
 * @property endpointUrl 由 Base URL 和接口格式推导出的实际请求地址。
 * @property endpointHost 实际接收密钥和数据的主机与显式端口。
 * @property usesHttps 是否使用加密的 HTTPS 连接；为 `false` 时密钥和数据以明文传输。
 * @property model 用户指定的模型标识。
 * @property apiKey 用户的服务商密钥。
 */
class AiProviderConfiguration internal constructor(
    val displayName: String,
    val apiFormat: AiApiFormat,
    val endpointUrl: String,
    val endpointHost: String,
    val usesHttps: Boolean,
    val model: String,
    internal val apiKey: String,
) {
    /** 返回不包含密钥的调试文本。 */
    override fun toString(): String =
        "AiProviderConfiguration(displayName=$displayName, apiFormat=$apiFormat, endpointHost=$endpointHost, " +
            "model=$model, apiKey=<已隐藏>)"
}

/** AI 服务连接配置校验结果。 */
sealed interface AiProviderConfigurationResult {
    /**
     * 配置可以用于生成确认预览和单次请求。
     *
     * @property configuration 已规范化且密钥不会出现在调试文本中的配置。
     */
    data class Success(
        val configuration: AiProviderConfiguration,
    ) : AiProviderConfigurationResult

    /**
     * 配置不满足安全边界。
     *
     * @property code 稳定问题编码。
     * @property message 面向用户的简体中文说明。
     */
    data class Invalid(
        val code: AiProviderConfigurationProblemCode,
        val message: String,
    ) : AiProviderConfigurationResult
}

/**
 * 已通过本地校验、可以拉取模型列表的连接。
 *
 * 密钥只对数据模块内部可见，`toString` 始终输出脱敏占位符。
 *
 * @property apiFormat 决定鉴权请求头的接口格式。
 * @property modelsUrl 由 Base URL 和接口格式推导出的模型列表地址，与分析请求同协议、同主机。
 * @property endpointHost 实际接收密钥的主机与显式端口。
 * @property usesHttps 是否使用加密的 HTTPS 连接。
 * @property apiKey 用户的服务商密钥。
 */
class AiModelCatalogRequest internal constructor(
    val apiFormat: AiApiFormat,
    val modelsUrl: String,
    val endpointHost: String,
    val usesHttps: Boolean,
    internal val apiKey: String,
) {
    /** 返回不包含密钥的调试文本。 */
    override fun toString(): String = "AiModelCatalogRequest(modelsUrl=$modelsUrl, apiKey=<已隐藏>)"
}

/** 模型列表连接校验结果。 */
sealed interface AiModelCatalogRequestResult {
    /**
     * 可以发起模型列表请求。
     *
     * @property request 已推导模型列表地址的连接。
     */
    data class Success(
        val request: AiModelCatalogRequest,
    ) : AiModelCatalogRequestResult

    /**
     * 地址或密钥不满足安全边界。
     *
     * @property code 稳定问题编码。
     * @property message 面向用户的简体中文说明。
     */
    data class Invalid(
        val code: AiProviderConfigurationProblemCode,
        val message: String,
    ) : AiModelCatalogRequestResult
}

/** 拉取服务商模型列表的结果。 */
sealed interface AiModelCatalogResult {
    /**
     * 已读取到可直接填入模型字段的标识。
     *
     * @property models 去重后按字母排序、符合模型标识格式的列表，至少包含一项。
     */
    data class Success(
        val models: List<String>,
    ) : AiModelCatalogResult

    /**
     * 请求失败或服务商没有返回可用模型。
     *
     * @property code 稳定失败编码。
     * @property message 面向用户的简体中文说明。
     */
    data class Failure(
        val code: AiProviderFailureCode,
        val message: String,
    ) : AiModelCatalogResult
}

/** 校验用户填写的 AI 服务连接信息，并按接口格式由 Base URL 推导实际请求地址。 */
class AiProviderConfigurationValidator {
    /**
     * 校验并规范化服务商名称、接口格式、HTTP 或 HTTPS Base URL、模型和密钥。
     *
     * @param displayName 用户填写的服务商展示名称。
     * @param apiFormat 用户选择的接口格式。
     * @param baseUrl 用户填写的 Base URL；误填完整接口地址时会去掉接口路径。
     * @param model 用户填写的模型标识。
     * @param apiKey 用户提供的密钥。
     * @return 可用配置或单一明确问题。
     */
    fun validate(
        displayName: String,
        apiFormat: AiApiFormat,
        baseUrl: String,
        model: String,
        apiKey: String,
    ): AiProviderConfigurationResult {
        val normalizedName = displayName.trim()
        if (
            normalizedName.isEmpty() ||
            normalizedName.length > MAX_PROVIDER_NAME_LENGTH ||
            normalizedName.any(Char::isISOControl)
        ) {
            return invalid(AiProviderConfigurationProblemCode.INVALID_PROVIDER_NAME, "AI 服务商名称为空或过长")
        }

        val endpoints =
            when (val endpoint = resolveEndpoints(apiFormat, baseUrl)) {
                is EndpointCheck.Valid -> endpoint

                is EndpointCheck.Invalid -> return AiProviderConfigurationResult.Invalid(
                    endpoint.code,
                    endpoint.message,
                )
            }

        val normalizedModel = model.trim()
        if (!AI_MODEL_ID_PATTERN.matches(normalizedModel)) {
            return invalid(
                AiProviderConfigurationProblemCode.INVALID_MODEL,
                "AI 模型标识为空、过长或包含不允许的字符",
            )
        }
        if (!isValidApiKey(apiKey)) {
            return invalid(AiProviderConfigurationProblemCode.INVALID_API_KEY, INVALID_API_KEY_MESSAGE)
        }

        return AiProviderConfigurationResult.Success(
            configuration =
                AiProviderConfiguration(
                    displayName = normalizedName,
                    apiFormat = apiFormat,
                    endpointUrl = endpoints.requestUrl,
                    endpointHost = endpoints.url.authority,
                    usesHttps = endpoints.url.protocol == URLProtocol.HTTPS,
                    model = normalizedModel,
                    apiKey = apiKey,
                ),
        )
    }

    /**
     * 校验 Base URL 和密钥，并按接口格式推导同协议、同主机的模型列表地址。
     *
     * @param apiFormat 用户选择的接口格式。
     * @param baseUrl 用户填写的 Base URL。
     * @param apiKey 用户提供的密钥。
     * @return 可发起模型列表请求的连接或单一明确问题。
     */
    fun validateModelCatalog(
        apiFormat: AiApiFormat,
        baseUrl: String,
        apiKey: String,
    ): AiModelCatalogRequestResult {
        val endpoints =
            when (val endpoint = resolveEndpoints(apiFormat, baseUrl)) {
                is EndpointCheck.Valid -> endpoint
                is EndpointCheck.Invalid -> return AiModelCatalogRequestResult.Invalid(endpoint.code, endpoint.message)
            }
        if (!isValidApiKey(apiKey)) {
            return AiModelCatalogRequestResult.Invalid(
                AiProviderConfigurationProblemCode.INVALID_API_KEY,
                INVALID_API_KEY_MESSAGE,
            )
        }
        return AiModelCatalogRequestResult.Success(
            AiModelCatalogRequest(
                apiFormat = apiFormat,
                modelsUrl = endpoints.modelsUrl,
                endpointHost = endpoints.url.authority,
                usesHttps = endpoints.url.protocol == URLProtocol.HTTPS,
                apiKey = apiKey,
            ),
        )
    }

    /**
     * 返回当前 Base URL 和接口格式实际会请求的地址，供输入框下方展示；Base URL 不合法时为 `null`。
     *
     * @param apiFormat 用户选择的接口格式。
     * @param baseUrl 用户填写的 Base URL。
     */
    fun requestUrlOrNull(
        apiFormat: AiApiFormat,
        baseUrl: String,
    ): String? = (resolveEndpoints(apiFormat, baseUrl) as? EndpointCheck.Valid)?.requestUrl

    /**
     * 解析 Base URL 并推导分析请求与模型列表地址。
     *
     * 末尾已带 `/chat/completions`、`/responses`、`/messages` 或 `/models` 时先去掉，兼容直接粘贴完整接口地址；
     * OpenAI 格式的 Base URL 没有路径时补 `/v1`；Anthropic 格式的 Base URL 末尾带 `/v1` 时不重复追加。
     */
    private fun resolveEndpoints(
        apiFormat: AiApiFormat,
        baseUrl: String,
    ): EndpointCheck {
        val parsed =
            when (val endpoint = parseEndpoint(baseUrl)) {
                is ParsedEndpoint.Valid -> endpoint.url
                is ParsedEndpoint.Invalid -> return EndpointCheck.Invalid(endpoint.code, endpoint.message)
            }
        var basePath = URLBuilder(parsed).encodedPath.trimEnd('/')
        ENDPOINT_PATH_SUFFIXES.firstOrNull(basePath::endsWith)?.let { suffix ->
            basePath = basePath.removeSuffix(suffix)
        }
        val requestPath: String
        val modelsPath: String
        when (apiFormat) {
            AiApiFormat.ANTHROPIC_MESSAGES -> {
                val root = basePath.removeSuffix(VERSION_PATH)
                requestPath = "$root$VERSION_PATH$MESSAGES_PATH"
                modelsPath = "$root$VERSION_PATH$MODELS_PATH"
            }

            AiApiFormat.OPENAI_CHAT_COMPLETIONS,
            AiApiFormat.OPENAI_RESPONSES,
            -> {
                val root = basePath.ifEmpty { VERSION_PATH }
                val operationPath =
                    if (apiFormat == AiApiFormat.OPENAI_RESPONSES) RESPONSES_PATH else CHAT_COMPLETIONS_PATH
                requestPath = "$root$operationPath"
                modelsPath = "$root$MODELS_PATH"
            }
        }
        val requestUrl = URLBuilder(parsed).apply { encodedPath = requestPath }.buildString()
        val modelsUrl =
            URLBuilder(parsed)
                .apply {
                    encodedPath = modelsPath
                    if (apiFormat == AiApiFormat.ANTHROPIC_MESSAGES) {
                        // Anthropic 模型列表默认每页 20 项，一次取满上限，避免列表不全。
                        parameters.append("limit", ANTHROPIC_MODEL_PAGE_LIMIT)
                    }
                }.buildString()
        return EndpointCheck.Valid(url = parsed, requestUrl = requestUrl, modelsUrl = modelsUrl)
    }

    /** 解析并检查不含账号、查询参数或片段的完整 HTTP 或 HTTPS 地址。 */
    private fun parseEndpoint(baseUrl: String): ParsedEndpoint {
        val normalizedEndpoint = baseUrl.trim()
        if (normalizedEndpoint.length > MAX_ENDPOINT_LENGTH) {
            return ParsedEndpoint.Invalid(
                AiProviderConfigurationProblemCode.INVALID_ENDPOINT,
                "Base URL 为空、过长或不是完整的 HTTP 或 HTTPS 地址",
            )
        }
        val parsedEndpoint = runCatching { Url(normalizedEndpoint) }.getOrNull()
        if (parsedEndpoint != null && parsedEndpoint.protocol !in SUPPORTED_PROTOCOLS) {
            return ParsedEndpoint.Invalid(
                AiProviderConfigurationProblemCode.UNSUPPORTED_SCHEME,
                "Base URL 必须使用 HTTP 或 HTTPS",
            )
        }
        if (
            parsedEndpoint == null ||
            !normalizedEndpoint.contains(URL_SCHEME_SEPARATOR) ||
            parsedEndpoint.host.isBlank() ||
            parsedEndpoint.user != null ||
            parsedEndpoint.password != null ||
            parsedEndpoint.fragment.isNotEmpty() ||
            parsedEndpoint.parameters.entries().isNotEmpty() ||
            parsedEndpoint.trailingQuery
        ) {
            return ParsedEndpoint.Invalid(
                AiProviderConfigurationProblemCode.INVALID_ENDPOINT,
                "Base URL 必须是不含账号、查询参数或片段的完整 HTTP 或 HTTPS 地址",
            )
        }
        return ParsedEndpoint.Valid(parsedEndpoint)
    }

    /** 密钥非空、不过长且不含空白或控制字符。 */
    private fun isValidApiKey(apiKey: String): Boolean =
        apiKey.isNotEmpty() &&
            apiKey.length <= MAX_API_KEY_LENGTH &&
            apiKey.none { character -> character.isWhitespace() || character.isISOControl() }

    /** 构造单一配置问题。 */
    private fun invalid(
        code: AiProviderConfigurationProblemCode,
        message: String,
    ): AiProviderConfigurationResult.Invalid =
        AiProviderConfigurationResult.Invalid(
            code = code,
            message = message,
        )

    /** Base URL 解析结果。 */
    private sealed interface ParsedEndpoint {
        /**
         * 地址合法。
         *
         * @property url 解析后的地址。
         */
        data class Valid(
            val url: Url,
        ) : ParsedEndpoint

        /**
         * 地址不合法。
         *
         * @property code 稳定问题编码。
         * @property message 面向用户的简体中文说明。
         */
        data class Invalid(
            val code: AiProviderConfigurationProblemCode,
            val message: String,
        ) : ParsedEndpoint
    }

    /** 接口地址推导结果。 */
    private sealed interface EndpointCheck {
        /**
         * 已推导出同协议、同主机的接口地址。
         *
         * @property url 解析后的 Base URL。
         * @property requestUrl 分析请求地址。
         * @property modelsUrl 模型列表地址。
         */
        data class Valid(
            val url: Url,
            val requestUrl: String,
            val modelsUrl: String,
        ) : EndpointCheck

        /**
         * Base URL 不合法。
         *
         * @property code 稳定问题编码。
         * @property message 面向用户的简体中文说明。
         */
        data class Invalid(
            val code: AiProviderConfigurationProblemCode,
            val message: String,
        ) : EndpointCheck
    }

    /** 配置文本边界常量。 */
    private companion object {
        /** 服务商展示名称最大字符数。 */
        const val MAX_PROVIDER_NAME_LENGTH = 60

        /** 密钥最大字符数。 */
        const val MAX_API_KEY_LENGTH = 4096

        /** 完整服务地址最大字符数。 */
        const val MAX_ENDPOINT_LENGTH = 2048

        /** 允许的服务地址协议。 */
        val SUPPORTED_PROTOCOLS = setOf(URLProtocol.HTTP, URLProtocol.HTTPS)

        /** 用户必须显式填写协议，避免把不带协议的文本当作默认地址。 */
        const val URL_SCHEME_SEPARATOR = "://"

        /** API 版本路径。 */
        const val VERSION_PATH = "/v1"

        /** OpenAI Responses 接口路径。 */
        const val RESPONSES_PATH = "/responses"

        /** OpenAI Chat Completions 接口路径。 */
        const val CHAT_COMPLETIONS_PATH = "/chat/completions"

        /** Anthropic Messages 接口路径。 */
        const val MESSAGES_PATH = "/messages"

        /** 模型列表接口路径。 */
        const val MODELS_PATH = "/models"

        /** 用户误把完整接口地址填为 Base URL 时需要去掉的路径结尾。 */
        val ENDPOINT_PATH_SUFFIXES = listOf(CHAT_COMPLETIONS_PATH, RESPONSES_PATH, MESSAGES_PATH, MODELS_PATH)

        /** Anthropic 模型列表单页上限。 */
        const val ANTHROPIC_MODEL_PAGE_LIMIT = "1000"

        /** 密钥不合法时的统一说明。 */
        const val INVALID_API_KEY_MESSAGE = "AI 密钥为空、过长或包含空白字符"
    }
}

/** 模型标识允许的保守字符集合；模型列表只保留可直接通过校验的标识。 */
internal val AI_MODEL_ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$")

/**
 * 用户确认前可见且不包含密钥或完整请求体的 AI 请求预览。
 *
 * @property providerName 实际服务商展示名称。
 * @property apiFormat 实际使用的接口格式。
 * @property endpointHost 实际接收密钥和历史数据的主机。
 * @property usesHttps 是否使用加密的 HTTPS 连接；为 `false` 时确认页必须提示明文传输风险。
 * @property model 实际请求模型。
 * @property snapshotId 本次历史开奖快照标识。
 * @property sampleCount 实际发送的历史期数。
 * @property firstIssue 最早发送期号。
 * @property lastIssue 最晚发送期号。
 * @property templateName 用户选择的分析模板名称。
 * @property candidateCount 用户要求的候选注数。
 * @property sentFields 实际业务数据字段的简体中文列表。
 * @property requestBodyByteCount 完整模型请求体的 UTF-8 字节数。
 * @property maxOutputTokens 模型输出 token 上限。
 * @property requestFingerprint 服务配置和完整请求体形成的无密钥 SHA-256 标识。
 */
data class AiProviderRequestPreview(
    val providerName: String,
    val apiFormat: AiApiFormat,
    val endpointHost: String,
    val usesHttps: Boolean,
    val model: String,
    val snapshotId: String,
    val sampleCount: Int,
    val firstIssue: String,
    val lastIssue: String,
    val templateName: String,
    val candidateCount: Int,
    val sentFields: List<String>,
    val requestBodyByteCount: Int,
    val maxOutputTokens: Int,
    val requestFingerprint: String,
)

/** AI 请求预览构建结果。 */
sealed interface AiProviderPreviewResult {
    /**
     * 已形成可以交给用户确认的精确预览。
     *
     * @property preview 不含密钥和完整请求体的预览。
     */
    data class Success(
        val preview: AiProviderRequestPreview,
    ) : AiProviderPreviewResult

    /**
     * 请求在联网前已违反本地边界。
     *
     * @property message 面向用户的简体中文阻断说明。
     */
    data class Invalid(
        val message: String,
    ) : AiProviderPreviewResult
}

/** AI Provider 请求失败时的稳定编码。 */
enum class AiProviderFailureCode {
    /** 完整请求体超过本地冻结上限。 */
    REQUEST_TOO_LARGE,

    /** 当前设备无法连接用户选择的服务。 */
    NETWORK_UNAVAILABLE,

    /** 建连、请求或套接字等待超过冻结上限。 */
    TIMEOUT,

    /** 密钥无效或账户无权使用目标模型。 */
    AUTHENTICATION_FAILED,

    /** 服务商拒绝当前请求结构或参数。 */
    REQUEST_REJECTED,

    /** 服务商对当前账户执行限流。 */
    RATE_LIMITED,

    /** 服务商暂时不可用。 */
    SERVICE_UNAVAILABLE,

    /** 服务端要求跳转到未确认地址，客户端已阻止。 */
    REDIRECT_BLOCKED,

    /** 模型明确拒绝生成结果。 */
    REFUSED,

    /** 模型输出达到 token 上限而被截断。 */
    TRUNCATED,

    /** 模型输出被内容过滤器中止。 */
    CONTENT_FILTERED,

    /** 响应体超过本地读取上限。 */
    RESPONSE_TOO_LARGE,

    /** 服务响应或模型 JSON 不符合约定结构。 */
    INVALID_RESPONSE,

    /** JSON 可解析，但号码或回显字段未通过本地领域校验。 */
    INVALID_MODEL_OUTPUT,
}

/** AI Provider 单次调用结果。 */
sealed interface AiProviderResult {
    /**
     * 模型响应已经通过协议解析和本地彩票规则校验。
     *
     * @property analysis 可进入当前会话界面的 AI 分析结果。
     */
    data class Success(
        val analysis: AiAnalysisResult,
    ) : AiProviderResult

    /**
     * 单次请求已经失败关闭且不会自动重试。
     *
     * @property code 稳定失败编码。
     * @property message 面向用户的简体中文说明。
     */
    data class Failure(
        val code: AiProviderFailureCode,
        val message: String,
    ) : AiProviderResult
}

/** 由共享状态依赖的提供方无关 AI 分析接口。 */
interface AiAnalysisProvider {
    /**
     * 在联网前生成与实际请求完全一致的无密钥预览。
     *
     * @param configuration 已通过安全校验的用户会话配置。
     * @param request 已通过领域校验的历史分析请求。
     * @return 可确认预览或本地阻断状态。
     */
    fun preview(
        configuration: AiProviderConfiguration,
        request: AiAnalysisRequest,
    ): AiProviderPreviewResult

    /**
     * 向用户确认的服务商发送一次请求，不自动重试或切换服务。
     *
     * @param configuration 与确认预览相同的用户会话配置。
     * @param request 与确认预览相同的历史分析请求。
     * @return 已验证分析或明确失败状态。
     */
    suspend fun analyze(
        configuration: AiProviderConfiguration,
        request: AiAnalysisRequest,
    ): AiProviderResult

    /**
     * 由用户主动触发，读取服务商提供的模型列表，不自动重试。
     *
     * @param request 已校验并推导出模型列表地址的连接。
     * @return 可选模型或明确失败状态；未实现的服务商返回失败。
     */
    suspend fun listModels(request: AiModelCatalogRequest): AiModelCatalogResult =
        AiModelCatalogResult.Failure(AiProviderFailureCode.REQUEST_REJECTED, "当前服务不支持获取模型列表")
}

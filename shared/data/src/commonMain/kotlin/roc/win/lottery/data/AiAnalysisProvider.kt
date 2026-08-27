package roc.win.lottery.data

import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.authority
import roc.win.lottery.domain.AiAnalysisRequest
import roc.win.lottery.domain.AiAnalysisResult

/** AI 服务连接配置问题的稳定编码。 */
enum class AiProviderConfigurationProblemCode {
    /** 服务商展示名称为空或过长。 */
    INVALID_PROVIDER_NAME,

    /** 服务地址不是结构完整的绝对 URL。 */
    INVALID_ENDPOINT,

    /** 服务地址没有使用 HTTPS。 */
    INSECURE_ENDPOINT,

    /** 模型标识为空、过长或包含不允许的字符。 */
    INVALID_MODEL,

    /** 会话密钥为空、过长或包含空白控制字符。 */
    INVALID_API_KEY,
}

/**
 * 已通过本地校验的用户自备 AI 服务配置。
 *
 * 密钥只对数据模块内部可见，`toString` 始终输出脱敏占位符。
 *
 * @property displayName 用户确认页展示的服务商名称。
 * @property endpointUrl 实际接收请求的完整 HTTPS 地址。
 * @property endpointHost 实际接收密钥和数据的 HTTPS 主机与显式端口。
 * @property model 用户指定的模型标识。
 * @property apiKey 仅在本次进程会话中使用的用户密钥。
 */
class AiProviderConfiguration internal constructor(
    val displayName: String,
    val endpointUrl: String,
    val endpointHost: String,
    val model: String,
    internal val apiKey: String,
) {
    /** 返回不包含会话密钥的调试文本。 */
    override fun toString(): String =
        "AiProviderConfiguration(displayName=$displayName, endpointHost=$endpointHost, model=$model, apiKey=<已隐藏>)"
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

/** 校验用户在当前会话填写的 AI 服务连接信息。 */
class AiProviderConfigurationValidator {
    /**
     * 校验并规范化服务商名称、HTTPS 地址、模型和密钥。
     *
     * @param displayName 用户填写的服务商展示名称。
     * @param endpointUrl 用户填写的完整 Responses 风格接口地址。
     * @param model 用户填写的模型标识。
     * @param apiKey 用户本次会话提供的密钥。
     * @return 可用配置或单一明确问题。
     */
    fun validate(
        displayName: String,
        endpointUrl: String,
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

        val normalizedEndpoint = endpointUrl.trim()
        if (normalizedEndpoint.length > MAX_ENDPOINT_LENGTH) {
            return invalid(
                AiProviderConfigurationProblemCode.INVALID_ENDPOINT,
                "AI 服务地址为空、过长或不是完整 HTTPS 地址",
            )
        }
        val parsedEndpoint = runCatching { Url(normalizedEndpoint) }.getOrNull()
        if (
            parsedEndpoint == null ||
            parsedEndpoint.host.isBlank() ||
            parsedEndpoint.user != null ||
            parsedEndpoint.password != null ||
            parsedEndpoint.fragment.isNotEmpty() ||
            parsedEndpoint.parameters.entries().isNotEmpty() ||
            parsedEndpoint.trailingQuery
        ) {
            return invalid(
                AiProviderConfigurationProblemCode.INVALID_ENDPOINT,
                "AI 服务地址必须是不含账号、查询参数或片段的完整 HTTPS 地址",
            )
        }
        if (parsedEndpoint.protocol != URLProtocol.HTTPS) {
            return invalid(AiProviderConfigurationProblemCode.INSECURE_ENDPOINT, "AI 服务地址必须使用 HTTPS")
        }

        val normalizedModel = model.trim()
        if (!MODEL_PATTERN.matches(normalizedModel)) {
            return invalid(
                AiProviderConfigurationProblemCode.INVALID_MODEL,
                "AI 模型标识为空、过长或包含不允许的字符",
            )
        }
        if (
            apiKey.isEmpty() ||
            apiKey.length > MAX_API_KEY_LENGTH ||
            apiKey.any { character -> character.isWhitespace() || character.isISOControl() }
        ) {
            return invalid(
                AiProviderConfigurationProblemCode.INVALID_API_KEY,
                "AI 会话密钥为空、过长或包含空白字符",
            )
        }

        return AiProviderConfigurationResult.Success(
            configuration =
                AiProviderConfiguration(
                    displayName = normalizedName,
                    endpointUrl = parsedEndpoint.toString(),
                    endpointHost = parsedEndpoint.authority,
                    model = normalizedModel,
                    apiKey = apiKey,
                ),
        )
    }

    /** 构造单一配置问题。 */
    private fun invalid(
        code: AiProviderConfigurationProblemCode,
        message: String,
    ): AiProviderConfigurationResult.Invalid =
        AiProviderConfigurationResult.Invalid(
            code = code,
            message = message,
        )

    /** 配置文本边界常量。 */
    private companion object {
        /** 服务商展示名称最大字符数。 */
        const val MAX_PROVIDER_NAME_LENGTH = 60

        /** 会话密钥最大字符数。 */
        const val MAX_API_KEY_LENGTH = 4096

        /** 完整 HTTPS 服务地址最大字符数。 */
        const val MAX_ENDPOINT_LENGTH = 2048

        /** Responses 风格模型标识允许的保守字符集合。 */
        val MODEL_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$")
    }
}

/**
 * 用户确认前可见且不包含密钥或完整请求体的 AI 请求预览。
 *
 * @property providerName 实际服务商展示名称。
 * @property endpointHost 实际接收密钥和历史数据的主机。
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
    val endpointHost: String,
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

    /** 服务响应或模型 JSON 不符合 Responses 严格结构。 */
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
}

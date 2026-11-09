package roc.win.lottery.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import roc.win.lottery.domain.AiAnalysisOutputValidator
import roc.win.lottery.domain.AiAnalysisRequest
import roc.win.lottery.domain.AiAnalysisValidationResult
import roc.win.lottery.domain.UntrustedAiAnalysisCandidate
import roc.win.lottery.domain.UntrustedAiAnalysisOutput

/**
 * 按接口格式解析服务端原始响应，并在返回前执行领域二次校验。
 *
 * @param apiFormat 本次请求使用的接口格式。
 * @param rawResponse 服务端返回的受限大小 UTF-8 JSON。
 * @param request 与本次响应绑定的领域请求。
 * @param outputValidator 独立的本地彩票输出校验器。
 * @return 已验证结果或失败关闭状态。
 */
internal fun parseAiResponse(
    apiFormat: AiApiFormat,
    rawResponse: String,
    request: AiAnalysisRequest,
    outputValidator: AiAnalysisOutputValidator,
): AiProviderResult =
    when (apiFormat) {
        AiApiFormat.OPENAI_RESPONSES -> {
            ResponsesAiResponseParser.parse(rawResponse, request, outputValidator)
        }

        AiApiFormat.OPENAI_CHAT_COMPLETIONS -> {
            ChatCompletionsAiResponseParser.parse(
                rawResponse,
                request,
                outputValidator,
            )
        }

        AiApiFormat.ANTHROPIC_MESSAGES -> {
            AnthropicMessagesAiResponseParser.parse(rawResponse, request, outputValidator)
        }
    }

/** 解析 OpenAI Responses 原始响应。 */
internal object ResponsesAiResponseParser {
    /**
     * 解析完成状态、拒答、截断、内容过滤和严格模型 JSON。
     *
     * @param rawResponse 服务端返回的受限大小 UTF-8 JSON。
     * @param request 与本次响应绑定的领域请求。
     * @param outputValidator 独立的本地彩票输出校验器。
     * @return 已验证结果或失败关闭状态。
     */
    fun parse(
        rawResponse: String,
        request: AiAnalysisRequest,
        outputValidator: AiAnalysisOutputValidator,
    ): AiProviderResult {
        val root = parseRoot(rawResponse) ?: return invalidResponse()
        if (root.hasError()) return serviceFailure()
        when (stringValue(root, "status")) {
            "completed" -> Unit
            "incomplete" -> return incompleteFailure(root, request)
            else -> return invalidResponse()
        }

        val output = root["output"] as? JsonArray ?: return invalidResponse()
        val outputObjects = output.map { item -> item as? JsonObject ?: return invalidResponse() }
        if (
            outputObjects.any { item ->
                val type = stringValue(item, "type")
                type != "message" && type != "reasoning"
            }
        ) {
            return invalidResponse()
        }
        val messages = outputObjects.filter { item -> stringValue(item, "type") == "message" }
        if (messages.size != 1) return invalidResponse()
        val message = messages.single()
        if (stringValue(message, "role") != "assistant" || stringValue(message, "status") != "completed") {
            return invalidResponse()
        }
        val content = message["content"] as? JsonArray ?: return invalidResponse()
        val contentObjects = content.map { item -> item as? JsonObject ?: return invalidResponse() }
        if (contentObjects.any { item -> stringValue(item, "type") == "refusal" }) {
            return refusedFailure()
        }
        if (contentObjects.any { item -> stringValue(item, "type") != "output_text" }) {
            return invalidResponse()
        }
        val outputTexts = contentObjects.mapNotNull { item -> stringValue(item, "text") }
        if (outputTexts.size != 1 || outputTexts.size != contentObjects.size) return invalidResponse()
        return AiModelOutputDecoder.decode(outputTexts.single(), request, outputValidator, lenient = false)
    }

    /** 将 Responses 未完成原因映射为截断、内容过滤或非法响应。 */
    private fun incompleteFailure(
        root: JsonObject,
        request: AiAnalysisRequest,
    ): AiProviderResult.Failure {
        val details = root["incomplete_details"] as? JsonObject
        return when (details?.let { stringValue(it, "reason") }) {
            "max_output_tokens" -> {
                truncatedFailure(request, root.intAt("usage", "output_tokens_details", "reasoning_tokens"))
            }

            "content_filter" -> {
                contentFilteredFailure()
            }

            else -> {
                invalidResponse()
            }
        }
    }
}

/** 解析 OpenAI Chat Completions 原始响应。 */
internal object ChatCompletionsAiResponseParser {
    /**
     * 读取第一项候选的结束原因、拒答和消息正文，再按 JSON 解析模型输出。
     *
     * @param rawResponse 服务端返回的受限大小 UTF-8 JSON。
     * @param request 与本次响应绑定的领域请求。
     * @param outputValidator 独立的本地彩票输出校验器。
     * @return 已验证结果或失败关闭状态。
     */
    fun parse(
        rawResponse: String,
        request: AiAnalysisRequest,
        outputValidator: AiAnalysisOutputValidator,
    ): AiProviderResult {
        val root = parseRoot(rawResponse) ?: return invalidResponse()
        if (root.hasError()) return serviceFailure()
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return invalidResponse()
        val message = choice["message"] as? JsonObject ?: return invalidResponse()
        if (!stringValue(message, "refusal").isNullOrBlank()) return refusedFailure()
        when (stringValue(choice, "finish_reason")) {
            "length" -> {
                return truncatedFailure(request, root.intAt("usage", "completion_tokens_details", "reasoning_tokens"))
            }

            "content_filter" -> {
                return contentFilteredFailure()
            }

            "tool_calls", "function_call" -> {
                return invalidResponse()
            }
        }
        val text =
            when (val content = message["content"]) {
                is JsonPrimitive -> content.takeIf { it.isString }?.content

                // 少数兼容服务把正文拆成 `[{"type":"text","text":…}]`。
                is JsonArray -> content.joinTextParts(textType = "text")

                else -> null
            }
        if (text.isNullOrBlank()) return invalidResponse()
        return AiModelOutputDecoder.decode(text, request, outputValidator, lenient = true)
    }
}

/** 解析 Anthropic Messages 原始响应。 */
internal object AnthropicMessagesAiResponseParser {
    /**
     * 读取停止原因并拼接文本块，忽略思考块，再按 JSON 解析模型输出。
     *
     * @param rawResponse 服务端返回的受限大小 UTF-8 JSON。
     * @param request 与本次响应绑定的领域请求。
     * @param outputValidator 独立的本地彩票输出校验器。
     * @return 已验证结果或失败关闭状态。
     */
    fun parse(
        rawResponse: String,
        request: AiAnalysisRequest,
        outputValidator: AiAnalysisOutputValidator,
    ): AiProviderResult {
        val root = parseRoot(rawResponse) ?: return invalidResponse()
        if (root.hasError() || stringValue(root, "type") == "error") return serviceFailure()
        when (stringValue(root, "stop_reason")) {
            "max_tokens", "model_context_window_exceeded" -> return truncatedFailure(request, reasoningTokens = null)
            "refusal" -> return refusedFailure()
            "tool_use", "pause_turn" -> return invalidResponse()
        }
        val content = root["content"] as? JsonArray ?: return invalidResponse()
        val blocks = content.map { item -> item as? JsonObject ?: return invalidResponse() }
        if (blocks.any { block -> stringValue(block, "type") !in ANTHROPIC_ALLOWED_BLOCK_TYPES }) {
            return invalidResponse()
        }
        val text = content.joinTextParts(textType = "text")
        if (text.isNullOrBlank()) return invalidResponse()
        return AiModelOutputDecoder.decode(text, request, outputValidator, lenient = true)
    }

    /** 允许出现的内容块；思考块只被忽略，不进入结果。 */
    private val ANTHROPIC_ALLOWED_BLOCK_TYPES = setOf("text", "thinking", "redacted_thinking")
}

/** 把模型输出文本解码为严格 DTO，并交给本地彩票规则校验。 */
internal object AiModelOutputDecoder {
    /**
     * 解码并校验模型输出。
     *
     * @param outputText 模型输出正文。
     * @param request 与本次响应绑定的领域请求。
     * @param outputValidator 独立的本地彩票输出校验器。
     * @param lenient 是否去掉思考标签和 Markdown 代码块外壳；只用于没有严格 Schema 约束的格式。
     * @return 已验证结果或失败关闭状态。
     */
    fun decode(
        outputText: String,
        request: AiAnalysisRequest,
        outputValidator: AiAnalysisOutputValidator,
        lenient: Boolean,
    ): AiProviderResult {
        val jsonText = if (lenient) unwrapModelJson(outputText) else outputText
        val decoded =
            try {
                StrictOutputJson.decodeFromString<AiOutputDto>(jsonText)
            } catch (_: SerializationException) {
                return failure(AiProviderFailureCode.INVALID_RESPONSE, "模型输出不是约定结构的 JSON")
            } catch (_: IllegalArgumentException) {
                return failure(AiProviderFailureCode.INVALID_RESPONSE, "模型输出不是约定结构的 JSON")
            }
        val untrusted =
            UntrustedAiAnalysisOutput(
                schemaVersion = decoded.schemaVersion,
                lotteryType = decoded.lotteryType,
                snapshotId = decoded.snapshotId,
                summary = decoded.summary,
                candidates =
                    decoded.candidates.map { candidate ->
                        UntrustedAiAnalysisCandidate(
                            primaryNumbers = candidate.primaryNumbers,
                            secondaryNumbers = candidate.secondaryNumbers,
                            reason = candidate.reason,
                        )
                    },
            )
        return when (val validation = outputValidator.validate(request, untrusted)) {
            is AiAnalysisValidationResult.Success -> {
                AiProviderResult.Success(validation.result)
            }

            is AiAnalysisValidationResult.Invalid -> {
                failure(
                    AiProviderFailureCode.INVALID_MODEL_OUTPUT,
                    validation.problems.firstOrNull()?.message ?: "模型输出未通过本地彩票规则校验",
                )
            }
        }
    }

    /** 去掉推理模型的 `<think>` 段和 Markdown 代码块外壳，只保留 JSON 正文。 */
    private fun unwrapModelJson(text: String): String {
        var value = text.trim()
        if (value.startsWith(THINK_OPEN_TAG)) {
            val end = value.indexOf(THINK_CLOSE_TAG)
            if (end >= 0) value = value.substring(end + THINK_CLOSE_TAG.length).trim()
        }
        if (value.startsWith(CODE_FENCE)) {
            value =
                value
                    .substringAfter('\n', missingDelimiterValue = "")
                    .trim()
                    .removeSuffix(CODE_FENCE)
                    .trim()
        }
        return value
    }

    /** 推理段开始标签。 */
    private const val THINK_OPEN_TAG = "<think>"

    /** 推理段结束标签。 */
    private const val THINK_CLOSE_TAG = "</think>"

    /** Markdown 代码块标记。 */
    private const val CODE_FENCE = "```"
}

/**
 * 从服务商错误响应中提取可展示的简短说明。
 *
 * 依次读取 `error.message`、字符串 `error`、`message`、`detail` 和 `msg`；非 JSON 时使用纯文本，HTML 页面不展示。
 * 结果去掉控制字符、合并空白、替换出现的密钥并截断，提取不到时为 `null`。
 *
 * @param body 受限大小的错误响应正文。
 * @param apiKey 需要从说明中隐藏的密钥。
 */
internal fun providerErrorDetail(
    body: String,
    apiKey: String,
): String? {
    val root = runCatching { AiProtocolJson.parseToJsonElement(body) }.getOrNull()
    val raw =
        when (root) {
            is JsonObject -> {
                val error = root["error"]
                (error as? JsonObject)?.let { stringValue(it, "message") }
                    ?: (error as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: stringValue(root, "message")
                    ?: stringValue(root, "detail")
                    ?: stringValue(root, "msg")
            }

            is JsonArray -> {
                null
            }

            // 解析器会把不带引号的单个词当作字面量，按纯文本处理。
            is JsonPrimitive -> {
                if (root.isString) root.content else body
            }

            null -> {
                body
            }
        }?.takeUnless { text -> text.trimStart().startsWith("<") }
    val cleaned =
        raw
            ?.let { text -> if (apiKey.isEmpty()) text else text.replace(apiKey, "<已隐藏>") }
            ?.map { character -> if (character.isISOControl()) ' ' else character }
            ?.joinToString(separator = "")
            ?.replace(WHITESPACE_RUN, " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
    return if (cleaned.length > MAX_ERROR_DETAIL_LENGTH) {
        cleaned.take(MAX_ERROR_DETAIL_LENGTH) + "…"
    } else {
        cleaned
    }
}

/** 错误说明最多展示的字符数。 */
private const val MAX_ERROR_DETAIL_LENGTH = 200

/** 连续空白。 */
private val WHITESPACE_RUN = Regex("\\s+")

/** 解析顶层 JSON 对象；不是对象时为 `null`。 */
private fun parseRoot(rawResponse: String): JsonObject? =
    runCatching { AiProtocolJson.parseToJsonElement(rawResponse) as? JsonObject }.getOrNull()

/** 成功状态码的响应体中是否带有非空错误字段。 */
private fun JsonObject.hasError(): Boolean = this["error"] != null && this["error"] !is JsonNull

/** 拼接内容块数组中指定类型的文本；没有文本块时为 `null`。 */
private fun JsonArray.joinTextParts(textType: String): String? =
    mapNotNull { part ->
        (part as? JsonObject)
            ?.takeIf { stringValue(it, "type") == textType }
            ?.let { stringValue(it, "text") }
    }.takeIf { it.isNotEmpty() }
        ?.joinToString(separator = "")

/** 只接受 JSON 字符串字段。 */
private fun stringValue(
    objectValue: JsonObject,
    field: String,
): String? =
    (objectValue[field] as? JsonPrimitive)
        ?.takeIf { value -> value.isString }
        ?.content

/** 构造通用非法响应。 */
private fun invalidResponse(): AiProviderResult.Failure =
    failure(AiProviderFailureCode.INVALID_RESPONSE, "AI 服务响应不完整或不符合约定结构")

/** 成功状态码却带有错误字段。 */
private fun serviceFailure(): AiProviderResult.Failure =
    failure(AiProviderFailureCode.SERVICE_UNAVAILABLE, "AI 服务返回失败状态")

/** 模型明确拒答。 */
private fun refusedFailure(): AiProviderResult.Failure = failure(AiProviderFailureCode.REFUSED, "模型拒绝生成本次分析")

/**
 * 输出达到 token 上限：说明当前上限和思考消耗，并提示调高上限。
 *
 * @param request 本次请求，提供输出上限。
 * @param reasoningTokens 服务商报告的思考 token 数；未报告时为 `null`。
 */
private fun truncatedFailure(
    request: AiAnalysisRequest,
    reasoningTokens: Int?,
): AiProviderResult.Failure =
    failure(
        AiProviderFailureCode.TRUNCATED,
        buildString {
            append("模型输出达到 ${request.maxOutputTokens} token 上限，结果未完成")
            if (reasoningTokens != null && reasoningTokens > 0) append("（其中思考用了 $reasoningTokens token）")
            append("。推理模型的思考也计入上限，可在连接配置中调高“输出上限”后重试")
        },
    )

/** 沿对象路径读取整数；任一层缺失或类型不符时为 `null`。 */
private fun JsonObject.intAt(vararg path: String): Int? {
    var current: JsonObject = this
    path.dropLast(1).forEach { field -> current = current[field] as? JsonObject ?: return null }
    return (current[path.last()] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toIntOrNull()
}

/** 输出被内容过滤。 */
private fun contentFilteredFailure(): AiProviderResult.Failure =
    failure(AiProviderFailureCode.CONTENT_FILTERED, "模型输出被内容过滤器中止")

/** 构造稳定失败结果。 */
private fun failure(
    code: AiProviderFailureCode,
    message: String,
): AiProviderResult.Failure =
    AiProviderResult.Failure(
        code = code,
        message = message,
    )

/** 严格模型输出 DTO。 */
@Serializable
private data class AiOutputDto(
    /** 输出格式版本。 */
    val schemaVersion: Int,
    /** 彩种枚举名称。 */
    val lotteryType: String,
    /** 历史快照 SHA-256。 */
    val snapshotId: String,
    /** 历史分析摘要。 */
    val summary: String,
    /** 模型候选列表。 */
    val candidates: List<AiCandidateDto>,
)

/** 严格模型候选 DTO。 */
@Serializable
private data class AiCandidateDto(
    /** 模型返回的主号码。 */
    val primaryNumbers: List<Int>,
    /** 模型返回的次号码。 */
    val secondaryNumbers: List<Int>,
    /** 模型返回的历史数据说明。 */
    val reason: String,
)

/** 协议 JSON 解析器，不接受宽松 JSON。 */
internal val AiProtocolJson =
    Json {
        isLenient = false
        explicitNulls = false
    }

/** 模型输出 JSON 解析器，缺字段和额外字段均失败关闭。 */
private val StrictOutputJson =
    Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = false
        coerceInputValues = false
    }

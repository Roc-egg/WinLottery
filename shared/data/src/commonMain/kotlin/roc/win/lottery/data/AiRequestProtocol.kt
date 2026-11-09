package roc.win.lottery.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.ByteString.Companion.encodeUtf8
import roc.win.lottery.domain.AiAnalysisProtocol
import roc.win.lottery.domain.AiAnalysisRequest
import roc.win.lottery.domain.LotteryTrendArea
import roc.win.lottery.domain.trendAreaSpec

/**
 * 已生成但尚未联网的 AI 分析请求。
 *
 * @property body 不含密钥的完整 JSON 请求体。
 * @property preview 可交给用户确认的无密钥摘要。
 */
internal data class PreparedAiRequest(
    val body: String,
    val preview: AiProviderRequestPreview,
)

/**
 * 按接口格式生成请求体。
 *
 * OpenAI Responses 使用 `text.format` 严格 JSON Schema；Chat Completions 要求 JSON 对象并在提示词中给出 Schema，
 * 兼容不支持 JSON Schema 的服务；Anthropic Messages 只在提示词中给出 Schema。三种格式的输出都要再经过本地严格解析和彩票规则校验。
 */
internal object AiRequestBuilder {
    /**
     * 为当前配置和领域请求生成稳定请求体及确认指纹。
     *
     * @param configuration 已校验的用户会话配置。
     * @param request 已校验的历史开奖分析请求。
     * @return 完整请求体和不含密钥的确认预览。
     */
    fun build(
        configuration: AiProviderConfiguration,
        request: AiAnalysisRequest,
    ): PreparedAiRequest {
        val userContent = "请只分析以下规范化 JSON 输入，并按输出结构返回：\n${analysisInputPayload(request)}"
        val body =
            when (configuration.apiFormat) {
                AiApiFormat.OPENAI_RESPONSES -> responsesBody(configuration.model, userContent, request)
                AiApiFormat.OPENAI_CHAT_COMPLETIONS -> chatCompletionsBody(configuration.model, userContent, request)
                AiApiFormat.ANTHROPIC_MESSAGES -> anthropicMessagesBody(configuration.model, userContent, request)
            }.toString()
        val requestFingerprint =
            buildString {
                append(CONFIRMATION_FINGERPRINT_VERSION)
                append('\n')
                append(configuration.displayName)
                append('\n')
                append(configuration.apiFormat.name)
                append('\n')
                append(configuration.endpointUrl)
                append('\n')
                append(configuration.model)
                append('\n')
                append(body)
            }.encodeUtf8()
                .sha256()
                .hex()
        return PreparedAiRequest(
            body = body,
            preview =
                AiProviderRequestPreview(
                    providerName = configuration.displayName,
                    apiFormat = configuration.apiFormat,
                    endpointHost = configuration.endpointHost,
                    usesHttps = configuration.usesHttps,
                    model = configuration.model,
                    snapshotId = request.snapshot.id,
                    sampleCount = request.snapshot.sampleCount,
                    firstIssue = request.snapshot.firstIssue.value,
                    lastIssue = request.snapshot.lastIssue.value,
                    templateName = request.template.displayName,
                    candidateCount = request.candidateCount,
                    sentFields = SENT_FIELD_NAMES,
                    requestBodyByteCount = body.encodeToByteArray().size,
                    maxOutputTokens = request.maxOutputTokens,
                    requestFingerprint = requestFingerprint,
                ),
        )
    }

    /** OpenAI Responses：严格 JSON Schema，关闭服务端存储与自动截断。 */
    private fun responsesBody(
        model: String,
        userContent: String,
        request: AiAnalysisRequest,
    ): JsonObject =
        buildJsonObject {
            put("model", model)
            put("store", false)
            put("max_output_tokens", request.maxOutputTokens)
            put("truncation", "disabled")
            put(
                "input",
                buildJsonArray {
                    add(message(role = "system", content = SYSTEM_INSTRUCTIONS))
                    add(message(role = "user", content = userContent))
                },
            )
            put(
                "text",
                buildJsonObject {
                    put(
                        "format",
                        buildJsonObject {
                            put("type", "json_schema")
                            put("name", OUTPUT_SCHEMA_NAME)
                            put("strict", true)
                            put("schema", outputSchema(request))
                        },
                    )
                },
            )
        }

    /** OpenAI Chat Completions：JSON 对象模式，Schema 写入系统提示词。 */
    private fun chatCompletionsBody(
        model: String,
        userContent: String,
        request: AiAnalysisRequest,
    ): JsonObject =
        buildJsonObject {
            put("model", model)
            put("max_tokens", request.maxOutputTokens)
            put(
                "messages",
                buildJsonArray {
                    add(message(role = "system", content = promptInstructions(request)))
                    add(message(role = "user", content = userContent))
                },
            )
            put("response_format", buildJsonObject { put("type", "json_object") })
        }

    /** Anthropic Messages：系统提示词给出 Schema，单条用户消息携带历史快照。 */
    private fun anthropicMessagesBody(
        model: String,
        userContent: String,
        request: AiAnalysisRequest,
    ): JsonObject =
        buildJsonObject {
            put("model", model)
            put("max_tokens", request.maxOutputTokens)
            put("system", promptInstructions(request))
            put("messages", buildJsonArray { add(message(role = "user", content = userContent)) })
        }

    /** 不支持严格 Schema 参数的格式：在系统提示词中写明只输出符合 Schema 的单个 JSON 对象。 */
    private fun promptInstructions(request: AiAnalysisRequest): String =
        "$SYSTEM_INSTRUCTIONS\n只输出一个符合以下 JSON Schema 的 JSON 对象，不要输出 Markdown 代码块、解释或其他文字。\n" +
            "JSON Schema：${outputSchema(request)}"

    /** 构造只含白名单业务字段的用户输入 JSON。 */
    private fun analysisInputPayload(request: AiAnalysisRequest): JsonObject =
        buildJsonObject {
            put("snapshotId", request.snapshot.id)
            put("analysisTemplateId", request.template.id)
            put("analysisFocus", request.template.analysisFocus)
            put("candidateCount", request.candidateCount)
            put("historySnapshot", AiProtocolJson.parseToJsonElement(request.snapshot.canonicalJson))
        }

    /** 构造输入消息。 */
    private fun message(
        role: String,
        content: String,
    ): JsonObject =
        buildJsonObject {
            put("role", role)
            put("content", content)
        }

    /**
     * 构造绑定当前彩种、快照和候选数量的输出 Schema。
     *
     * 只使用 OpenAI 严格模式支持的关键字；字符串长度写在 `description` 中，由本地校验器强制执行。
     */
    private fun outputSchema(request: AiAnalysisRequest): JsonObject {
        val primarySpec = request.snapshot.lotteryType.trendAreaSpec(LotteryTrendArea.PRIMARY)
        val secondarySpec = request.snapshot.lotteryType.trendAreaSpec(LotteryTrendArea.SECONDARY)
        return objectSchema(
            properties =
                buildJsonObject {
                    put("schemaVersion", singletonIntegerEnum(AiAnalysisProtocol.OUTPUT_SCHEMA_VERSION))
                    put("lotteryType", singletonStringEnum(request.snapshot.lotteryType.name))
                    put("snapshotId", singletonStringEnum(request.snapshot.id))
                    put(
                        "summary",
                        stringSchema(maxLength = AiAnalysisProtocol.MAX_SUMMARY_LENGTH),
                    )
                    put(
                        "candidates",
                        buildJsonObject {
                            put("type", "array")
                            put("minItems", request.candidateCount)
                            put("maxItems", request.candidateCount)
                            put(
                                "items",
                                objectSchema(
                                    properties =
                                        buildJsonObject {
                                            put(
                                                "primaryNumbers",
                                                numberArraySchema(
                                                    count = primarySpec.drawnNumberCount,
                                                    minimum = primarySpec.numberRange.first,
                                                    maximum = primarySpec.numberRange.last,
                                                ),
                                            )
                                            put(
                                                "secondaryNumbers",
                                                numberArraySchema(
                                                    count = secondarySpec.drawnNumberCount,
                                                    minimum = secondarySpec.numberRange.first,
                                                    maximum = secondarySpec.numberRange.last,
                                                ),
                                            )
                                            put(
                                                "reason",
                                                stringSchema(maxLength = AiAnalysisProtocol.MAX_REASON_LENGTH),
                                            )
                                        },
                                    required = listOf("primaryNumbers", "secondaryNumbers", "reason"),
                                ),
                            )
                        },
                    )
                },
            required = listOf("schemaVersion", "lotteryType", "snapshotId", "summary", "candidates"),
        )
    }

    /** 构造禁止额外字段的对象 Schema。 */
    private fun objectSchema(
        properties: JsonObject,
        required: List<String>,
    ): JsonObject =
        buildJsonObject {
            put("type", "object")
            put("properties", properties)
            put(
                "required",
                buildJsonArray {
                    required.forEach { field -> add(JsonPrimitive(field)) }
                },
            )
            put("additionalProperties", false)
        }

    /** 构造只允许一个整数值的 Schema。 */
    private fun singletonIntegerEnum(value: Int): JsonObject =
        buildJsonObject {
            put("type", "integer")
            put("enum", buildJsonArray { add(JsonPrimitive(value)) })
        }

    /** 构造只允许一个字符串值的 Schema。 */
    private fun singletonStringEnum(value: String): JsonObject =
        buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray { add(JsonPrimitive(value)) })
        }

    /** 构造在说明中写明长度边界的字符串 Schema；OpenAI 严格模式不接受 `minLength`、`maxLength`。 */
    private fun stringSchema(maxLength: Int): JsonObject =
        buildJsonObject {
            put("type", "string")
            put("description", "非空，不超过 $maxLength 个字符")
        }

    /** 构造固定数量和取值范围的整数数组 Schema。 */
    private fun numberArraySchema(
        count: Int,
        minimum: Int,
        maximum: Int,
    ): JsonObject =
        buildJsonObject {
            put("type", "array")
            put("minItems", count)
            put("maxItems", count)
            put(
                "items",
                buildJsonObject {
                    put("type", "integer")
                    put("minimum", minimum)
                    put("maximum", maximum)
                },
            )
        }

    /** 请求生成器固定文本与字段常量。 */
    private const val SYSTEM_INSTRUCTIONS =
        "你只分析用户提供的公开历史开奖。只描述样本内可观察事实；不得声称提高下一期中奖概率，不得提供购彩金额、收益承诺或官方开奖结果，不得输出 JSON Schema 之外的内容。候选号码仅供娱乐和研究。" +
            "回答保持简洁：摘要控制在 300 字以内，每注理由控制在 100 字以内。"

    /** 严格输出 Schema 名称。 */
    private const val OUTPUT_SCHEMA_NAME = "win_lottery_ai_analysis_v1"

    /** 确认指纹格式版本；指纹包含接口格式后升级为 v2。 */
    private const val CONFIRMATION_FINGERPRINT_VERSION = "ai-confirmation-v2"

    /** 确认页展示的实际业务字段。 */
    private val SENT_FIELD_NAMES =
        listOf("彩种", "期号", "开奖日期", "主号码", "次号码", "分析模板", "候选数量")
}

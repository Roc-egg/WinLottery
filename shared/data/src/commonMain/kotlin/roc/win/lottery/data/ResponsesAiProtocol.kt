package roc.win.lottery.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.ByteString.Companion.encodeUtf8
import roc.win.lottery.domain.AiAnalysisOutputValidator
import roc.win.lottery.domain.AiAnalysisProtocol
import roc.win.lottery.domain.AiAnalysisRequest
import roc.win.lottery.domain.AiAnalysisValidationResult
import roc.win.lottery.domain.LotteryTrendArea
import roc.win.lottery.domain.UntrustedAiAnalysisCandidate
import roc.win.lottery.domain.UntrustedAiAnalysisOutput
import roc.win.lottery.domain.trendAreaSpec

/**
 * 已生成但尚未联网的 Responses 风格请求。
 *
 * @property body 不含密钥的完整 JSON 请求体。
 * @property preview 可交给用户确认的无密钥摘要。
 */
internal data class PreparedResponsesAiRequest(
    val body: String,
    val preview: AiProviderRequestPreview,
)

/** 按官方 Responses `text.format` 结构生成严格 JSON Schema 请求。 */
internal object ResponsesAiRequestBuilder {
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
    ): PreparedResponsesAiRequest {
        val inputPayload = analysisInputPayload(request)
        val body =
            buildJsonObject {
                put("model", configuration.model)
                put("store", false)
                put("max_output_tokens", AiAnalysisProtocol.MAX_OUTPUT_TOKENS)
                put("truncation", "disabled")
                put(
                    "input",
                    buildJsonArray {
                        add(message(role = "system", content = SYSTEM_INSTRUCTIONS))
                        add(
                            message(
                                role = "user",
                                content = "请只分析以下规范化 JSON 输入，并按严格输出结构返回：\n$inputPayload",
                            ),
                        )
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
            }.toString()
        val requestFingerprint =
            buildString {
                append(CONFIRMATION_FINGERPRINT_VERSION)
                append('\n')
                append(configuration.displayName)
                append('\n')
                append(configuration.endpointUrl)
                append('\n')
                append(configuration.model)
                append('\n')
                append(body)
            }.encodeUtf8()
                .sha256()
                .hex()
        return PreparedResponsesAiRequest(
            body = body,
            preview =
                AiProviderRequestPreview(
                    providerName = configuration.displayName,
                    endpointHost = configuration.endpointHost,
                    model = configuration.model,
                    snapshotId = request.snapshot.id,
                    sampleCount = request.snapshot.sampleCount,
                    firstIssue = request.snapshot.firstIssue.value,
                    lastIssue = request.snapshot.lastIssue.value,
                    templateName = request.template.displayName,
                    candidateCount = request.candidateCount,
                    sentFields = SENT_FIELD_NAMES,
                    requestBodyByteCount = body.encodeToByteArray().size,
                    maxOutputTokens = AiAnalysisProtocol.MAX_OUTPUT_TOKENS,
                    requestFingerprint = requestFingerprint,
                ),
        )
    }

    /** 构造只含白名单业务字段的用户输入 JSON。 */
    private fun analysisInputPayload(request: AiAnalysisRequest): JsonObject =
        buildJsonObject {
            put("snapshotId", request.snapshot.id)
            put("analysisTemplateId", request.template.id)
            put("analysisFocus", request.template.analysisFocus)
            put("candidateCount", request.candidateCount)
            put("historySnapshot", ProtocolJson.parseToJsonElement(request.snapshot.canonicalJson))
        }

    /** 构造 Responses 输入消息。 */
    private fun message(
        role: String,
        content: String,
    ): JsonObject =
        buildJsonObject {
            put("role", role)
            put("content", content)
        }

    /** 构造绑定当前彩种、快照和候选数量的严格输出 Schema。 */
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

    /** 构造有非空与长度边界的字符串 Schema。 */
    private fun stringSchema(maxLength: Int): JsonObject =
        buildJsonObject {
            put("type", "string")
            put("minLength", 1)
            put("maxLength", maxLength)
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
        "你只分析用户提供的公开历史开奖。只描述样本内可观察事实；不得声称提高下一期中奖概率，不得提供购彩金额、收益承诺或官方开奖结果，不得输出 JSON Schema 之外的内容。候选号码仅供娱乐和研究。"

    /** 严格输出 Schema 名称。 */
    private const val OUTPUT_SCHEMA_NAME = "win_lottery_ai_analysis_v1"

    /** 确认指纹格式版本。 */
    private const val CONFIRMATION_FINGERPRINT_VERSION = "responses-confirmation-v1"

    /** 确认页展示的实际业务字段。 */
    private val SENT_FIELD_NAMES =
        listOf("彩种", "期号", "开奖日期", "主号码", "次号码", "分析模板", "候选数量")
}

/** 解析 Responses 原始响应，并在返回前执行领域二次校验。 */
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
        val root =
            runCatching { ProtocolJson.parseToJsonElement(rawResponse) as? JsonObject }.getOrNull()
                ?: return invalidResponse()
        if (root["error"] != null && root["error"] !is JsonNull) {
            return failure(AiProviderFailureCode.SERVICE_UNAVAILABLE, "AI 服务返回失败状态")
        }
        when (stringValue(root, "status")) {
            "completed" -> Unit
            "incomplete" -> return incompleteFailure(root)
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
            return failure(AiProviderFailureCode.REFUSED, "模型拒绝生成本次分析")
        }
        if (contentObjects.any { item -> stringValue(item, "type") != "output_text" }) {
            return invalidResponse()
        }
        val outputTexts = contentObjects.mapNotNull { item -> stringValue(item, "text") }
        if (outputTexts.size != 1 || outputTexts.size != contentObjects.size) return invalidResponse()

        val decoded =
            try {
                StrictOutputJson.decodeFromString<ResponsesAiOutputDto>(outputTexts.single())
            } catch (_: SerializationException) {
                return invalidResponse()
            } catch (_: IllegalArgumentException) {
                return invalidResponse()
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

    /** 将 Responses 未完成原因映射为截断、内容过滤或非法响应。 */
    private fun incompleteFailure(root: JsonObject): AiProviderResult.Failure {
        val details = root["incomplete_details"] as? JsonObject
        return when (details?.let { stringValue(it, "reason") }) {
            "max_output_tokens" -> failure(AiProviderFailureCode.TRUNCATED, "模型输出达到 token 上限，结果未完成")
            "content_filter" -> failure(AiProviderFailureCode.CONTENT_FILTERED, "模型输出被内容过滤器中止")
            else -> invalidResponse()
        }
    }

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
        failure(AiProviderFailureCode.INVALID_RESPONSE, "AI 服务响应不完整或不符合严格结构")

    /** 构造稳定失败结果。 */
    private fun failure(
        code: AiProviderFailureCode,
        message: String,
    ): AiProviderResult.Failure =
        AiProviderResult.Failure(
            code = code,
            message = message,
        )
}

/** 严格模型输出 DTO。 */
@Serializable
private data class ResponsesAiOutputDto(
    /** 输出格式版本。 */
    val schemaVersion: Int,
    /** 彩种枚举名称。 */
    val lotteryType: String,
    /** 历史快照 SHA-256。 */
    val snapshotId: String,
    /** 历史分析摘要。 */
    val summary: String,
    /** 模型候选列表。 */
    val candidates: List<ResponsesAiCandidateDto>,
)

/** 严格模型候选 DTO。 */
@Serializable
private data class ResponsesAiCandidateDto(
    /** 模型返回的主号码。 */
    val primaryNumbers: List<Int>,
    /** 模型返回的次号码。 */
    val secondaryNumbers: List<Int>,
    /** 模型返回的历史数据说明。 */
    val reason: String,
)

/** 协议 JSON 解析器，不接受宽松 JSON。 */
private val ProtocolJson =
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

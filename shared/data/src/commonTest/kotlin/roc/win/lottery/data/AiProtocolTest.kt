package roc.win.lottery.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import roc.win.lottery.domain.AiAnalysisOutputValidator
import roc.win.lottery.domain.AiAnalysisProtocol
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.TrendSampleSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** 三种接口格式的请求与响应协议测试。 */
class AiProtocolTest {
    /** 请求必须使用 `text.format` 严格 Schema、关闭存储与自动截断。 */
    @Test
    fun buildsStrictResponsesRequestAndSafePreview() {
        val configuration = testAiProviderConfiguration()
        val request = testAiAnalysisRequest()
        val prepared = AiRequestBuilder.build(configuration, request)
        val root = ProtocolTestJson.parseToJsonElement(prepared.body).jsonObject

        assertEquals(configuration.model, root.string("model"))
        assertEquals(false, root["store"]?.jsonPrimitive?.boolean)
        assertEquals(AiAnalysisProtocol.DEFAULT_OUTPUT_TOKEN_LIMIT, root["max_output_tokens"]?.jsonPrimitive?.int)
        assertEquals("disabled", root.string("truncation"))
        val format = root["text"]!!.jsonObject["format"]!!.jsonObject
        assertEquals("json_schema", format.string("type"))
        assertEquals(true, format["strict"]?.jsonPrimitive?.boolean)
        val schema = format["schema"]!!.jsonObject
        assertEquals(false, schema["additionalProperties"]?.jsonPrimitive?.boolean)
        assertEquals(
            listOf("schemaVersion", "lotteryType", "snapshotId", "summary", "candidates"),
            schema["required"]!!.jsonArray.map { field -> field.jsonPrimitive.content },
        )
        val candidates = schema.properties()["candidates"]!!.jsonObject
        assertEquals(request.candidateCount, candidates["minItems"]?.jsonPrimitive?.int)
        assertEquals(request.candidateCount, candidates["maxItems"]?.jsonPrimitive?.int)
        val candidateProperties = candidates["items"]!!.jsonObject.properties()
        assertNumberArray(candidateProperties["primaryNumbers"]!!.jsonObject, count = 5, minimum = 1, maximum = 35)
        assertNumberArray(candidateProperties["secondaryNumbers"]!!.jsonObject, count = 2, minimum = 1, maximum = 12)

        assertTrue(prepared.preview.requestBodyByteCount <= AiAnalysisProtocol.MAX_REQUEST_BODY_BYTES)
        assertTrue(Regex("^[0-9a-f]{64}$").matches(prepared.preview.requestFingerprint))
        assertEquals(configuration.endpointHost, prepared.preview.endpointHost)
        assertEquals(request.snapshot.id, prepared.preview.snapshotId)
        assertEquals(
            listOf("彩种", "期号", "开奖日期", "主号码", "次号码", "分析模板", "候选数量"),
            prepared.preview.sentFields,
        )
        assertTrue(TEST_AI_API_KEY !in prepared.body)
        assertTrue("ticket" !in prepared.body.lowercase())
        assertTrue("ocr" !in prepared.body.lowercase())
        // OpenAI 严格模式不接受字符串长度关键字，长度由本地校验器强制执行。
        assertTrue("minLength" !in prepared.body)
        assertTrue("maxLength" !in prepared.body)
        assertEquals(AiApiFormat.OPENAI_RESPONSES, prepared.preview.apiFormat)
    }

    /** Chat Completions 使用 messages、max_tokens 与 JSON 对象模式，Schema 写入系统提示词。 */
    @Test
    fun buildsChatCompletionsRequest() {
        val configuration = testAiProviderConfiguration(apiFormat = AiApiFormat.OPENAI_CHAT_COMPLETIONS)
        val request = testAiAnalysisRequest()
        val prepared = AiRequestBuilder.build(configuration, request)
        val root = ProtocolTestJson.parseToJsonElement(prepared.body).jsonObject

        assertEquals(configuration.model, root.string("model"))
        assertEquals(AiAnalysisProtocol.DEFAULT_OUTPUT_TOKEN_LIMIT, root["max_tokens"]?.jsonPrimitive?.int)
        assertEquals("json_object", root["response_format"]!!.jsonObject.string("type"))
        val messages = root["messages"]!!.jsonArray.map { message -> message.jsonObject }
        assertEquals(listOf("system", "user"), messages.map { message -> message.string("role") })
        assertTrue("JSON Schema" in messages.first().string("content"))
        assertTrue(request.snapshot.id in messages.last().string("content"))
        assertEquals(setOf("model", "max_tokens", "messages", "response_format"), root.keys)
        assertEquals(AiApiFormat.OPENAI_CHAT_COMPLETIONS, prepared.preview.apiFormat)
    }

    /** Anthropic Messages 使用顶层 system 与单条用户消息，不带 OpenAI 专有参数。 */
    @Test
    fun buildsAnthropicMessagesRequest() {
        val configuration =
            testAiProviderConfiguration(
                apiFormat = AiApiFormat.ANTHROPIC_MESSAGES,
                baseUrl = "https://api.example.test",
            )
        val request = testAiAnalysisRequest()
        val root = ProtocolTestJson.parseToJsonElement(AiRequestBuilder.build(configuration, request).body).jsonObject

        assertEquals(setOf("model", "max_tokens", "system", "messages"), root.keys)
        assertEquals(AiAnalysisProtocol.DEFAULT_OUTPUT_TOKEN_LIMIT, root["max_tokens"]?.jsonPrimitive?.int)
        assertTrue("JSON Schema" in root.string("system"))
        val messages = root["messages"]!!.jsonArray.map { message -> message.jsonObject }
        assertEquals(listOf("user"), messages.map { message -> message.string("role") })
    }

    /** 相同配置和请求指纹稳定，模型或请求变化必须使旧确认失效。 */
    @Test
    fun fingerprintsExactProviderAndRequest() {
        val request = testAiAnalysisRequest()
        val first = AiRequestBuilder.build(testAiProviderConfiguration(), request)
        val repeated = AiRequestBuilder.build(testAiProviderConfiguration(), request)
        val changedModel =
            AiRequestBuilder.build(
                testAiProviderConfiguration(model = "other-model-v1"),
                request,
            )
        val changedCandidates =
            AiRequestBuilder.build(
                testAiProviderConfiguration(),
                testAiAnalysisRequest(candidateCount = 3),
            )

        val changedFormat =
            AiRequestBuilder.build(
                testAiProviderConfiguration(apiFormat = AiApiFormat.OPENAI_CHAT_COMPLETIONS),
                request,
            )

        assertEquals(first.preview.requestFingerprint, repeated.preview.requestFingerprint)
        assertNotEquals(first.preview.requestFingerprint, changedModel.preview.requestFingerprint)
        assertNotEquals(first.preview.requestFingerprint, changedFormat.preview.requestFingerprint)
        assertNotEquals(first.preview.requestFingerprint, changedCandidates.preview.requestFingerprint)
    }

    /** 请求体和确认预览使用本次请求选择的输出上限。 */
    @Test
    fun usesSelectedOutputTokenLimit() {
        val request = testAiAnalysisRequest(maxOutputTokens = 32768)
        val responses = AiRequestBuilder.build(testAiProviderConfiguration(), request)
        val chat =
            AiRequestBuilder.build(
                testAiProviderConfiguration(apiFormat = AiApiFormat.OPENAI_CHAT_COMPLETIONS),
                request,
            )
        val anthropic =
            AiRequestBuilder.build(testAiProviderConfiguration(apiFormat = AiApiFormat.ANTHROPIC_MESSAGES), request)

        assertEquals(32768, responses.preview.maxOutputTokens)
        assertEquals(
            32768,
            ProtocolTestJson
                .parseToJsonElement(responses.body)
                .jsonObject["max_output_tokens"]
                ?.jsonPrimitive
                ?.int,
        )
        assertEquals(
            32768,
            ProtocolTestJson
                .parseToJsonElement(chat.body)
                .jsonObject["max_tokens"]
                ?.jsonPrimitive
                ?.int,
        )
        assertEquals(
            32768,
            ProtocolTestJson
                .parseToJsonElement(anthropic.body)
                .jsonObject["max_tokens"]
                ?.jsonPrimitive
                ?.int,
        )
        assertNotEquals(
            AiRequestBuilder.build(testAiProviderConfiguration(), testAiAnalysisRequest()).preview.requestFingerprint,
            responses.preview.requestFingerprint,
        )
    }

    /** 三种格式的 500 期完整请求体都必须仍位于 128 KiB 冻结上限内。 */
    @Test
    fun keepsMaximumHistoryRequestWithinByteLimit() {
        AiApiFormat.entries.forEach { format ->
            val prepared =
                AiRequestBuilder.build(
                    testAiProviderConfiguration(apiFormat = format),
                    testAiAnalysisRequest(
                        lotteryType = LotteryType.DOUBLE_COLOR_BALL,
                        sampleSize = TrendSampleSize.LAST_500,
                        candidateCount = 5,
                    ),
                )

            assertEquals(500, prepared.preview.sampleCount)
            assertTrue(prepared.preview.requestBodyByteCount <= AiAnalysisProtocol.MAX_REQUEST_BODY_BYTES, format.name)
        }
    }

    /** 双色球 Schema 必须切换为六个红球和一个蓝球的范围。 */
    @Test
    fun buildsDoubleColorBallNumberSchema() {
        val request = testAiAnalysisRequest(lotteryType = LotteryType.DOUBLE_COLOR_BALL, candidateCount = 1)
        val body = AiRequestBuilder.build(testAiProviderConfiguration(), request).body
        val schema =
            ProtocolTestJson
                .parseToJsonElement(body)
                .jsonObject["text"]!!
                .jsonObject["format"]!!
                .jsonObject["schema"]!!
                .jsonObject
        val candidateProperties =
            schema
                .properties()["candidates"]!!
                .jsonObject["items"]!!
                .jsonObject
                .properties()

        assertNumberArray(candidateProperties["primaryNumbers"]!!.jsonObject, count = 6, minimum = 1, maximum = 33)
        assertNumberArray(candidateProperties["secondaryNumbers"]!!.jsonObject, count = 1, minimum = 1, maximum = 16)
    }

    /** 完整 Responses 输出必须解析后再次通过本地领域校验。 */
    @Test
    fun parsesCompletedStrictOutput() {
        val request = testAiAnalysisRequest()
        val result =
            ResponsesAiResponseParser.parse(
                rawResponse = completedResponsesBody(validAiOutputJson(request)),
                request = request,
                outputValidator = AiAnalysisOutputValidator(),
            )

        assertEquals(2, assertIs<AiProviderResult.Success>(result).analysis.candidates.size)
    }

    /** 拒答、token 截断和内容过滤必须使用可区分失败编码。 */
    @Test
    fun mapsRefusalAndIncompleteResponses() {
        val request = testAiAnalysisRequest()
        val refusal =
            buildJsonObject {
                put("status", "completed")
                put(
                    "output",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "message")
                                put("status", "completed")
                                put("role", "assistant")
                                put(
                                    "content",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("type", "refusal")
                                                put("refusal", "不能完成")
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    },
                )
            }.toString()

        assertFailureCode(request, refusal, AiProviderFailureCode.REFUSED)
        assertFailureCode(request, incompleteBody("max_output_tokens"), AiProviderFailureCode.TRUNCATED)
        val truncated =
            ResponsesAiResponseParser.parse(
                incompleteBody("max_output_tokens", reasoningTokens = 7000),
                request,
                AiAnalysisOutputValidator(),
            )
        assertEquals(
            "模型输出达到 8192 token 上限，结果未完成（其中思考用了 7000 token）。" +
                "推理模型的思考也计入上限，可在连接配置中调高“输出上限”后重试",
            assertIs<AiProviderResult.Failure>(truncated).message,
        )
        assertFailureCode(request, incompleteBody("content_filter"), AiProviderFailureCode.CONTENT_FILTERED)
    }

    /** 非法 JSON、额外字段和非法彩票号码必须分别在协议或领域层关闭。 */
    @Test
    fun rejectsMalformedAndInvalidModelOutputs() {
        val request = testAiAnalysisRequest()
        val withUnknownField =
            ProtocolTestJson
                .parseToJsonElement(validAiOutputJson(request))
                .jsonObject
                .toMutableMap()
                .also { fields ->
                    fields["unexpected"] = JsonPrimitive(true)
                }.let(::JsonObject)
        val invalidNumbers =
            ProtocolTestJson
                .parseToJsonElement(validAiOutputJson(request))
                .jsonObject
                .toMutableMap()
                .also { fields ->
                    val candidates = fields.getValue("candidates").jsonArray.toMutableList()
                    val first = candidates.first().jsonObject.toMutableMap()
                    first["primaryNumbers"] =
                        buildJsonArray {
                            listOf(1, 2, 3, 3, 36).forEach { number -> add(JsonPrimitive(number)) }
                        }
                    candidates[0] = JsonObject(first)
                    fields["candidates"] = JsonArray(candidates)
                }.let(::JsonObject)

        assertFailureCode(request, completedResponsesBody("not-json"), AiProviderFailureCode.INVALID_RESPONSE)
        assertFailureCode(
            request,
            completedResponsesBody(withUnknownField.toString()),
            AiProviderFailureCode.INVALID_RESPONSE,
        )
        assertFailureCode(
            request,
            completedResponsesBody(invalidNumbers.toString()),
            AiProviderFailureCode.INVALID_MODEL_OUTPUT,
        )
    }

    /** Chat Completions 输出可带推理段和代码块外壳；拒答、截断、过滤和空正文必须分别失败。 */
    @Test
    fun parsesChatCompletionsResponses() {
        val request = testAiAnalysisRequest()
        val wrapped = "<think>先观察分布</think>\n```json\n${validAiOutputJson(request)}\n```"

        assertIs<AiProviderResult.Success>(
            ChatCompletionsAiResponseParser.parse(
                completedChatCompletionsBody(wrapped),
                request,
                AiAnalysisOutputValidator(),
            ),
        )
        val refusal =
            """{"choices":[{"index":0,"message":{"role":"assistant","content":null,"refusal":"不能完成"},""" +
                """"finish_reason":"stop"}]}"""
        assertChatFailure(request, refusal, AiProviderFailureCode.REFUSED)
        assertChatFailure(
            request,
            completedChatCompletionsBody("{", finishReason = "length"),
            AiProviderFailureCode.TRUNCATED,
        )
        val largerLimit = testAiAnalysisRequest(maxOutputTokens = 32768)
        val chatTruncated =
            ChatCompletionsAiResponseParser.parse(
                completedChatCompletionsBody("{", finishReason = "length"),
                largerLimit,
                AiAnalysisOutputValidator(),
            )
        assertTrue(assertIs<AiProviderResult.Failure>(chatTruncated).message.startsWith("模型输出达到 32768 token 上限"))
        assertChatFailure(
            request,
            completedChatCompletionsBody("", finishReason = "content_filter"),
            AiProviderFailureCode.CONTENT_FILTERED,
        )
        assertChatFailure(request, completedChatCompletionsBody("说明文字"), AiProviderFailureCode.INVALID_RESPONSE)
        assertChatFailure(request, """{"choices":[]}""", AiProviderFailureCode.INVALID_RESPONSE)
        assertChatFailure(
            request,
            """{"error":{"message":"upstream failed"}}""",
            AiProviderFailureCode.SERVICE_UNAVAILABLE,
        )
    }

    /** Anthropic Messages 按停止原因映射截断和拒答，未知内容块和工具调用失败关闭。 */
    @Test
    fun parsesAnthropicMessagesResponses() {
        val request = testAiAnalysisRequest()

        assertIs<AiProviderResult.Success>(
            AnthropicMessagesAiResponseParser.parse(
                completedAnthropicMessagesBody(validAiOutputJson(request), withThinking = true),
                request,
                AiAnalysisOutputValidator(),
            ),
        )
        assertAnthropicFailure(
            request,
            completedAnthropicMessagesBody("{", stopReason = "max_tokens"),
            AiProviderFailureCode.TRUNCATED,
        )
        assertAnthropicFailure(
            request,
            completedAnthropicMessagesBody("", stopReason = "refusal"),
            AiProviderFailureCode.REFUSED,
        )
        assertAnthropicFailure(
            request,
            completedAnthropicMessagesBody(validAiOutputJson(request), stopReason = "tool_use"),
            AiProviderFailureCode.INVALID_RESPONSE,
        )
        assertAnthropicFailure(
            request,
            """{"type":"message","content":[{"type":"tool_use","id":"t","name":"x","input":{}}],"stop_reason":"end_turn"}""",
            AiProviderFailureCode.INVALID_RESPONSE,
        )
        assertAnthropicFailure(
            request,
            """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""",
            AiProviderFailureCode.SERVICE_UNAVAILABLE,
        )
    }

    /** 校验固定数量和范围的号码数组 Schema。 */
    private fun assertNumberArray(
        schema: JsonObject,
        count: Int,
        minimum: Int,
        maximum: Int,
    ) {
        assertEquals(count, schema["minItems"]?.jsonPrimitive?.int)
        assertEquals(count, schema["maxItems"]?.jsonPrimitive?.int)
        val items = schema["items"]!!.jsonObject
        assertEquals(minimum, items["minimum"]?.jsonPrimitive?.int)
        assertEquals(maximum, items["maximum"]?.jsonPrimitive?.int)
    }

    /** 提取对象 Schema 的属性表。 */
    private fun JsonObject.properties(): JsonObject = this["properties"]!!.jsonObject

    /** 提取必需的 JSON 字符串字段。 */
    private fun JsonObject.string(field: String): String = this[field]!!.jsonPrimitive.content

    /** 构造指定原因的 Responses 未完成响应；可带服务商报告的思考 token 数。 */
    private fun incompleteBody(
        reason: String,
        reasoningTokens: Int? = null,
    ): String =
        buildJsonObject {
            if (reasoningTokens != null) {
                put(
                    "usage",
                    buildJsonObject {
                        put("output_tokens", AiAnalysisProtocol.DEFAULT_OUTPUT_TOKEN_LIMIT)
                        put("output_tokens_details", buildJsonObject { put("reasoning_tokens", reasoningTokens) })
                    },
                )
            }
            put("status", "incomplete")
            put(
                "incomplete_details",
                buildJsonObject {
                    put("reason", reason)
                },
            )
            put("output", buildJsonArray {})
        }.toString()

    /** 断言 Chat Completions 响应解析得到指定失败编码。 */
    private fun assertChatFailure(
        request: roc.win.lottery.domain.AiAnalysisRequest,
        response: String,
        expectedCode: AiProviderFailureCode,
    ) {
        val result = ChatCompletionsAiResponseParser.parse(response, request, AiAnalysisOutputValidator())

        assertEquals(expectedCode, assertIs<AiProviderResult.Failure>(result).code)
    }

    /** 断言 Anthropic Messages 响应解析得到指定失败编码。 */
    private fun assertAnthropicFailure(
        request: roc.win.lottery.domain.AiAnalysisRequest,
        response: String,
        expectedCode: AiProviderFailureCode,
    ) {
        val result = AnthropicMessagesAiResponseParser.parse(response, request, AiAnalysisOutputValidator())

        assertEquals(expectedCode, assertIs<AiProviderResult.Failure>(result).code)
    }

    /** 断言响应解析得到指定失败编码。 */
    private fun assertFailureCode(
        request: roc.win.lottery.domain.AiAnalysisRequest,
        response: String,
        expectedCode: AiProviderFailureCode,
    ) {
        val result = ResponsesAiResponseParser.parse(response, request, AiAnalysisOutputValidator())

        assertEquals(expectedCode, assertIs<AiProviderResult.Failure>(result).code)
    }
}

/** 协议测试使用的严格 JSON 解析器。 */
private val ProtocolTestJson = Json { ignoreUnknownKeys = false }

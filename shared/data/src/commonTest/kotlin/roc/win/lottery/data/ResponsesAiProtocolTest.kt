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

/** Responses 严格请求与响应协议测试。 */
class ResponsesAiProtocolTest {
    /** 请求必须使用 `text.format` 严格 Schema、关闭存储与自动截断。 */
    @Test
    fun buildsStrictResponsesRequestAndSafePreview() {
        val configuration = testAiProviderConfiguration()
        val request = testAiAnalysisRequest()
        val prepared = ResponsesAiRequestBuilder.build(configuration, request)
        val root = ProtocolTestJson.parseToJsonElement(prepared.body).jsonObject

        assertEquals(configuration.model, root.string("model"))
        assertEquals(false, root["store"]?.jsonPrimitive?.boolean)
        assertEquals(AiAnalysisProtocol.MAX_OUTPUT_TOKENS, root["max_output_tokens"]?.jsonPrimitive?.int)
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
    }

    /** 相同配置和请求指纹稳定，模型或请求变化必须使旧确认失效。 */
    @Test
    fun fingerprintsExactProviderAndRequest() {
        val request = testAiAnalysisRequest()
        val first = ResponsesAiRequestBuilder.build(testAiProviderConfiguration(), request)
        val repeated = ResponsesAiRequestBuilder.build(testAiProviderConfiguration(), request)
        val changedModel =
            ResponsesAiRequestBuilder.build(
                testAiProviderConfiguration(model = "other-model-v1"),
                request,
            )
        val changedCandidates =
            ResponsesAiRequestBuilder.build(
                testAiProviderConfiguration(),
                testAiAnalysisRequest(candidateCount = 3),
            )

        assertEquals(first.preview.requestFingerprint, repeated.preview.requestFingerprint)
        assertNotEquals(first.preview.requestFingerprint, changedModel.preview.requestFingerprint)
        assertNotEquals(first.preview.requestFingerprint, changedCandidates.preview.requestFingerprint)
    }

    /** 500 期完整 Responses 请求体必须仍位于 128 KiB 冻结上限内。 */
    @Test
    fun keepsMaximumHistoryRequestWithinByteLimit() {
        val prepared =
            ResponsesAiRequestBuilder.build(
                testAiProviderConfiguration(),
                testAiAnalysisRequest(sampleSize = TrendSampleSize.LAST_500, candidateCount = 5),
            )

        assertEquals(500, prepared.preview.sampleCount)
        assertTrue(prepared.preview.requestBodyByteCount <= AiAnalysisProtocol.MAX_REQUEST_BODY_BYTES)
    }

    /** 双色球 Schema 必须切换为六个红球和一个蓝球的范围。 */
    @Test
    fun buildsDoubleColorBallNumberSchema() {
        val request = testAiAnalysisRequest(lotteryType = LotteryType.DOUBLE_COLOR_BALL, candidateCount = 1)
        val body = ResponsesAiRequestBuilder.build(testAiProviderConfiguration(), request).body
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

    /** 构造指定原因的 Responses 未完成响应。 */
    private fun incompleteBody(reason: String): String =
        buildJsonObject {
            put("status", "incomplete")
            put(
                "incomplete_details",
                buildJsonObject {
                    put("reason", reason)
                },
            )
            put("output", buildJsonArray {})
        }.toString()

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

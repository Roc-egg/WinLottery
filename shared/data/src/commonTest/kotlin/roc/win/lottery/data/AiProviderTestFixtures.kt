package roc.win.lottery.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import roc.win.lottery.domain.AiAnalysisProtocol
import roc.win.lottery.domain.AiAnalysisRequest
import roc.win.lottery.domain.AiAnalysisTemplate
import roc.win.lottery.domain.AiHistorySnapshotBuildResult
import roc.win.lottery.domain.AiHistorySnapshotBuilder
import roc.win.lottery.domain.HistoricalDraw
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.TrendSampleSize
import kotlin.test.assertIs

/** 测试请求使用的固定非生产密钥。 */
internal const val TEST_AI_API_KEY = "test-session-secret"

/** 构造经过生产校验器验证的测试服务配置。 */
internal fun testAiProviderConfiguration(
    endpointUrl: String = "https://api.example.test/v1/responses",
    model: String = "test-model-v1",
    apiKey: String = TEST_AI_API_KEY,
): AiProviderConfiguration =
    assertIs<AiProviderConfigurationResult.Success>(
        AiProviderConfigurationValidator().validate(
            displayName = "测试服务",
            endpointUrl = endpointUrl,
            model = model,
            apiKey = apiKey,
        ),
    ).configuration

/** 构造指定彩种、样本和候选数量的合法领域请求。 */
internal fun testAiAnalysisRequest(
    lotteryType: LotteryType = LotteryType.SUPER_LOTTO,
    sampleSize: TrendSampleSize = TrendSampleSize.LAST_50,
    candidateCount: Int = 2,
): AiAnalysisRequest {
    val snapshot =
        assertIs<AiHistorySnapshotBuildResult.Success>(
            AiHistorySnapshotBuilder().build(
                lotteryType = lotteryType,
                sampleSize = sampleSize,
                draws = testHistoricalDraws(lotteryType, count = sampleSize.count),
            ),
        ).snapshot
    return AiAnalysisRequest(
        snapshot = snapshot,
        template = AiAnalysisTemplate.COMPREHENSIVE,
        candidateCount = candidateCount,
    )
}

/** 构造可通过严格解析和本地校验的模型输出 JSON。 */
internal fun validAiOutputJson(request: AiAnalysisRequest): String =
    buildJsonObject {
        put("schemaVersion", AiAnalysisProtocol.OUTPUT_SCHEMA_VERSION)
        put("lotteryType", request.snapshot.lotteryType.name)
        put("snapshotId", request.snapshot.id)
        put("summary", "只描述当前历史样本内的可观察分布")
        put(
            "candidates",
            buildJsonArray {
                repeat(request.candidateCount) { index ->
                    add(validCandidateJson(request.snapshot.lotteryType, index))
                }
            },
        )
    }.toString()

/** 构造 Responses 已完成消息响应。 */
internal fun completedResponsesBody(outputText: String): String =
    buildJsonObject {
        put("id", "resp_test")
        put("object", "response")
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
                                        put("type", "output_text")
                                        put("text", outputText)
                                        put("annotations", buildJsonArray {})
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
    }.toString()

/** 构造跨年度且年度内部连续的合法历史开奖。 */
private fun testHistoricalDraws(
    lotteryType: LotteryType,
    count: Int,
): List<HistoricalDraw> =
    (0 until count).map { index ->
        val year = 23 + index / 125
        val ordinal = index % 125 + 1
        when (lotteryType) {
            LotteryType.SUPER_LOTTO -> {
                HistoricalDraw(
                    lotteryType = lotteryType,
                    issue = Issue("$year${ordinal.toString().padStart(3, '0')}"),
                    drawDate = "20$year-08-10",
                    primaryNumbers = listOf(1, 2, 3, 4, 5),
                    secondaryNumbers = listOf(1, 2),
                )
            }

            LotteryType.DOUBLE_COLOR_BALL -> {
                HistoricalDraw(
                    lotteryType = lotteryType,
                    issue = Issue("20$year${ordinal.toString().padStart(3, '0')}"),
                    drawDate = "20$year-08-10",
                    primaryNumbers = listOf(1, 2, 3, 4, 5, 6),
                    secondaryNumbers = listOf(1),
                )
            }
        }
    }

/** 构造一注不会与同次其他候选重复的合法模型候选。 */
private fun validCandidateJson(
    lotteryType: LotteryType,
    index: Int,
): JsonObject =
    when (lotteryType) {
        LotteryType.SUPER_LOTTO -> {
            buildJsonObject {
                put(
                    "primaryNumbers",
                    buildJsonArray {
                        listOf(1 + index, 8 + index, 15 + index, 22 + index, 29 + index).forEach { number ->
                            add(JsonPrimitive(number))
                        }
                    },
                )
                put(
                    "secondaryNumbers",
                    buildJsonArray {
                        listOf(1 + index, 7 + index).forEach { number -> add(JsonPrimitive(number)) }
                    },
                )
                put("reason", "大乐透候选 ${index + 1} 的历史样本说明")
            }
        }

        LotteryType.DOUBLE_COLOR_BALL -> {
            buildJsonObject {
                put(
                    "primaryNumbers",
                    buildJsonArray {
                        listOf(1 + index, 6 + index, 11 + index, 16 + index, 21 + index, 26 + index).forEach { number ->
                            add(JsonPrimitive(number))
                        }
                    },
                )
                put("secondaryNumbers", buildJsonArray { add(JsonPrimitive(1 + index)) })
                put("reason", "双色球候选 ${index + 1} 的历史样本说明")
            }
        }
    }

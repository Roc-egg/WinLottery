package roc.win.lottery.domain

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.ByteString.Companion.encodeUtf8

/** AI 历史开奖快照无法形成时的稳定编码。 */
enum class AiHistorySnapshotFailureCode {
    /** 当前会话取得的有效历史期数不足。 */
    INSUFFICIENT_HISTORY,

    /** 历史开奖未通过 V1.3 的彩票领域校验。 */
    INVALID_HISTORY,

    /** 当前样本内存在可证明的期号空档。 */
    ISSUE_GAP,

    /** 开奖日期不是规范化的 `YYYY-MM-DD` 文本。 */
    INVALID_DRAW_DATE,

    /** 规范化快照超过冻结的字节上限。 */
    SNAPSHOT_TOO_LARGE,
}

/** AI 历史开奖快照构建结果。 */
sealed interface AiHistorySnapshotBuildResult {
    /**
     * 已形成稳定且可发送的规范化快照。
     *
     * @property snapshot 经过 V1.3 领域校验的历史快照。
     */
    data class Success(
        val snapshot: AiHistorySnapshot,
    ) : AiHistorySnapshotBuildResult

    /**
     * 当前历史数据不满足 V1.4 输入边界。
     *
     * @property code 稳定失败编码。
     * @property message 面向用户的简体中文阻断说明。
     */
    data class Invalid(
        val code: AiHistorySnapshotFailureCode,
        val message: String,
    ) : AiHistorySnapshotBuildResult
}

/**
 * 校验并规范化 V1.4 AI 历史开奖输入。
 *
 * @property trendCalculator 复用 V1.3 走势入口的完整性校验。
 */
class AiHistorySnapshotBuilder(
    private val trendCalculator: LotteryTrendCalculator = LotteryTrendCalculator(),
) {
    /**
     * 从当前会话历史开奖中选取用户要求的最近范围并生成稳定快照。
     *
     * @param lotteryType 用户选择的彩种。
     * @param sampleSize 用户选择的最近开奖期数。
     * @param draws V1.3 当前会话中的规范化历史开奖。
     * @return 稳定快照或明确失败状态。
     */
    fun build(
        lotteryType: LotteryType,
        sampleSize: TrendSampleSize,
        draws: List<HistoricalDraw>,
    ): AiHistorySnapshotBuildResult {
        if (draws.size < sampleSize.count) {
            return invalid(
                AiHistorySnapshotFailureCode.INSUFFICIENT_HISTORY,
                "AI 分析需要连续 ${sampleSize.count} 期历史开奖",
            )
        }

        val trendResult =
            trendCalculator.calculate(
                lotteryType = lotteryType,
                area = LotteryTrendArea.PRIMARY,
                sampleSize = sampleSize,
                draws = draws,
            )
        val trendSnapshot =
            when (trendResult) {
                is LotteryTrendCalculationResult.Success -> {
                    trendResult.snapshot
                }

                is LotteryTrendCalculationResult.InvalidData -> {
                    return invalid(
                        AiHistorySnapshotFailureCode.INVALID_HISTORY,
                        "官方历史开奖未通过 AI 输入完整性校验",
                    )
                }
            }
        if (trendSnapshot.actualSampleCount != sampleSize.count) {
            return invalid(
                AiHistorySnapshotFailureCode.INSUFFICIENT_HISTORY,
                "AI 分析需要连续 ${sampleSize.count} 期历史开奖",
            )
        }
        if (trendSnapshot.issueGaps.isNotEmpty()) {
            return invalid(
                AiHistorySnapshotFailureCode.ISSUE_GAP,
                "历史期号存在空档，不能形成 AI 分析快照",
            )
        }

        val selectedDraws =
            draws
                .sortedBy { draw -> draw.issue.value }
                .takeLast(sampleSize.count)
                .map { draw ->
                    draw.copy(
                        primaryNumbers = draw.primaryNumbers.toList(),
                        secondaryNumbers = draw.secondaryNumbers.toList(),
                    )
                }
        if (selectedDraws.any { draw -> !isCanonicalDrawDate(draw.drawDate) }) {
            return invalid(
                AiHistorySnapshotFailureCode.INVALID_DRAW_DATE,
                "历史开奖日期不是规范化的 YYYY-MM-DD 格式",
            )
        }

        val canonicalJson = canonicalSnapshotJson(lotteryType, selectedDraws)
        val byteCount = canonicalJson.encodeToByteArray().size
        if (byteCount > AiAnalysisProtocol.MAX_SNAPSHOT_BYTES) {
            return invalid(
                AiHistorySnapshotFailureCode.SNAPSHOT_TOO_LARGE,
                "AI 历史快照超过 ${AiAnalysisProtocol.MAX_SNAPSHOT_BYTES} 字节上限",
            )
        }
        return AiHistorySnapshotBuildResult.Success(
            snapshot =
                AiHistorySnapshot(
                    schemaVersion = AiAnalysisProtocol.SNAPSHOT_SCHEMA_VERSION,
                    id = canonicalJson.encodeUtf8().sha256().hex(),
                    lotteryType = lotteryType,
                    sampleSize = sampleSize,
                    draws = selectedDraws,
                    canonicalJson = canonicalJson,
                    byteCount = byteCount,
                ),
        )
    }

    /** 按冻结字段和插入顺序生成规范化 JSON。 */
    private fun canonicalSnapshotJson(
        lotteryType: LotteryType,
        draws: List<HistoricalDraw>,
    ): String =
        buildJsonObject {
            put("schemaVersion", AiAnalysisProtocol.SNAPSHOT_SCHEMA_VERSION)
            put("lotteryType", lotteryType.name)
            put("sampleCount", draws.size)
            put("firstIssue", draws.first().issue.value)
            put("lastIssue", draws.last().issue.value)
            put(
                "draws",
                buildJsonArray {
                    draws.forEach { draw ->
                        add(
                            buildJsonObject {
                                put("issue", draw.issue.value)
                                put("drawDate", draw.drawDate)
                                put(
                                    "primaryNumbers",
                                    buildJsonArray {
                                        draw.primaryNumbers.forEach { number -> add(JsonPrimitive(number)) }
                                    },
                                )
                                put(
                                    "secondaryNumbers",
                                    buildJsonArray {
                                        draw.secondaryNumbers.forEach { number -> add(JsonPrimitive(number)) }
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }.toString()

    /** 校验规范日期格式和真实公历月份天数。 */
    private fun isCanonicalDrawDate(value: String): Boolean {
        if (!DRAW_DATE_PATTERN.matches(value)) return false
        val year = value.substring(0, 4).toInt()
        val month = value.substring(5, 7).toInt()
        val day = value.substring(8, 10).toInt()
        val maximumDay =
            when (month) {
                1, 3, 5, 7, 8, 10, 12 -> 31
                4, 6, 9, 11 -> 30
                2 -> if (isLeapYear(year)) 29 else 28
                else -> return false
            }
        return day in 1..maximumDay
    }

    /** 按公历规则判断闰年。 */
    private fun isLeapYear(year: Int): Boolean = year % 400 == 0 || (year % 4 == 0 && year % 100 != 0)

    /** 构造快照失败结果。 */
    private fun invalid(
        code: AiHistorySnapshotFailureCode,
        message: String,
    ): AiHistorySnapshotBuildResult.Invalid =
        AiHistorySnapshotBuildResult.Invalid(
            code = code,
            message = message,
        )

    /** 快照日期格式常量。 */
    private companion object {
        /** 来源层规范化后的开奖日期格式。 */
        val DRAW_DATE_PATTERN = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    }
}

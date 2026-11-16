package roc.win.lottery.persistence

import roc.win.lottery.domain.AiAnalysisProtocol
import roc.win.lottery.domain.AiAnalysisResult
import roc.win.lottery.domain.LotteryTrendArea
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.TREND_LOTTERY_TYPES
import roc.win.lottery.domain.TrendSampleSize
import roc.win.lottery.domain.trendAreaSpec

/**
 * 一次通过本地校验的 AI 分析结果及其来源；按 ADR-036 每个彩种在本机只保存最近一次。
 *
 * 只包含展示与复核需要的字段，不包含密钥、服务地址、发送快照、完整请求体或模型原始响应。
 *
 * @property providerName 生成结果的服务商展示名称。
 * @property model 生成结果的模型标识。
 * @property templateName 用户选择的分析模板名称。
 * @property sourceName 发送快照所用历史开奖的官方来源名称。
 * @property sampleCount 发送的历史期数。
 * @property firstIssue 发送的最早期号。
 * @property lastIssue 发送的最晚期号。
 * @property generatedAtEpochMillis 结果通过本地校验的时间，即本次查询时间。
 * @property analysis 已通过严格协议与彩票规则复核的分析内容。
 */
data class AiAnalysisRecord(
    val providerName: String,
    val model: String,
    val templateName: String,
    val sourceName: String,
    val sampleCount: Int,
    val firstIssue: String,
    val lastIssue: String,
    val generatedAtEpochMillis: Long,
    val analysis: AiAnalysisResult,
) {
    /** 结果所属彩种。 */
    val lotteryType: LotteryType
        get() = analysis.lotteryType
}

/**
 * 本机保存的 AI 分析结果，每个彩种最多一条，新结果整体替换旧结果。
 *
 * 实现必须在读写边界执行同一套校验：写入不合法的结果直接拒绝，读取到不合法的行删除后视为没有保存。
 */
interface AiAnalysisRecordStore {
    /**
     * 读取指定彩种最近一次保存的结果。
     *
     * @param lotteryType 彩种。
     * @return 合法结果；从未保存、已删除或内容损坏时为 `null`。
     */
    suspend fun load(lotteryType: LotteryType): AiAnalysisRecord?

    /**
     * 保存结果并替换同一彩种的旧结果。
     *
     * @throws IllegalArgumentException 结果未通过校验时抛出，且不写入任何数据。
     */
    suspend fun save(record: AiAnalysisRecord)

    /** 删除指定彩种保存的结果，返回是否删除了数据。 */
    suspend fun delete(lotteryType: LotteryType): Boolean
}

/** 当前本机 AI 分析结果逻辑数据版本。 */
internal const val CURRENT_AI_ANALYSIS_RECORD_DATA_VERSION = 1

/** 服务商名称、模型、模板与来源名称允许的最大长度，与连接配置输入上限保持同一量级。 */
private const val MAX_AI_RECORD_LABEL_LENGTH = 200

/** 返回结果不能保存或读取的原因；合法时为 `null`。 */
internal fun AiAnalysisRecord.storageProblem(): String? {
    val result = analysis
    val primarySpec = lotteryType.trendAreaSpec(LotteryTrendArea.PRIMARY)
    val secondarySpec = lotteryType.trendAreaSpec(LotteryTrendArea.SECONDARY)
    val lines = result.candidates.map { it.line.primaryNumbers to it.line.secondaryNumbers }
    return when {
        lotteryType !in TREND_LOTTERY_TYPES -> {
            "AI 分析结果的彩种不受支持"
        }

        listOf(providerName, model, templateName, sourceName).any {
            it.isBlank() ||
                it.length > MAX_AI_RECORD_LABEL_LENGTH
        } -> {
            "AI 分析结果的来源信息不完整"
        }

        sampleCount !in 1..TrendSampleSize.entries.maxOf { it.count } -> {
            "AI 分析结果的历史期数不合法"
        }

        !firstIssue.isIssueText() || !lastIssue.isIssueText() || firstIssue.length != lastIssue.length ||
            firstIssue > lastIssue -> {
            "AI 分析结果的期号区间不合法"
        }

        generatedAtEpochMillis < 0L -> {
            "AI 分析结果的查询时间不合法"
        }

        result.schemaVersion != AiAnalysisProtocol.OUTPUT_SCHEMA_VERSION -> {
            "AI 分析结果的格式版本不受支持"
        }

        result.snapshotId.isBlank() -> {
            "AI 分析结果缺少快照标识"
        }

        result.summary.isBlank() || result.summary.length > AiAnalysisProtocol.MAX_SUMMARY_LENGTH -> {
            "AI 分析摘要为空或过长"
        }

        result.candidates.size !in AiAnalysisProtocol.MIN_CANDIDATE_COUNT..AiAnalysisProtocol.MAX_CANDIDATE_COUNT -> {
            "AI 候选数量不合法"
        }

        result.candidates.any { candidate ->
            !candidate.line.primaryNumbers.isValidArea(primarySpec.numberRange, primarySpec.drawnNumberCount) ||
                !candidate.line.secondaryNumbers.isValidArea(secondarySpec.numberRange, secondarySpec.drawnNumberCount)
        } -> {
            "AI 候选号码不合法"
        }

        lines.distinct().size != lines.size -> {
            "AI 候选号码重复"
        }

        result.candidates.any { it.reason.isBlank() || it.reason.length > AiAnalysisProtocol.MAX_REASON_LENGTH } -> {
            "AI 候选说明为空或过长"
        }

        else -> {
            null
        }
    }
}

/** 期号只由数字组成。 */
private fun String.isIssueText(): Boolean = isNotEmpty() && all { it in '0'..'9' }

/** 一个号码区域的数量、范围、唯一性和升序都符合规则。 */
private fun List<Int>.isValidArea(
    range: IntRange,
    count: Int,
): Boolean = size == count && all { it in range } && distinct().size == size && this == sorted()

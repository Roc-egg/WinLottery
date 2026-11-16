package roc.win.lottery.persistence

import roc.win.lottery.domain.AiAnalysisCandidate
import roc.win.lottery.domain.AiAnalysisProtocol
import roc.win.lottery.domain.AiAnalysisResult
import roc.win.lottery.domain.GeneratedNumberLine
import roc.win.lottery.domain.LotteryType

/** 构造一条合法的 AI 分析结果，只用于持久化测试。 */
internal fun testAiAnalysisRecord(
    lotteryType: LotteryType = LotteryType.SUPER_LOTTO,
    generatedAtEpochMillis: Long = 1_789_000_000_000L,
    summary: String = "近 50 期前区奇偶分布接近均衡。",
): AiAnalysisRecord {
    val lines =
        if (lotteryType == LotteryType.SUPER_LOTTO) {
            listOf(
                GeneratedNumberLine(listOf(3, 11, 19, 25, 33), listOf(4, 10)),
                GeneratedNumberLine(listOf(1, 8, 14, 22, 30), listOf(2, 7)),
            )
        } else {
            listOf(GeneratedNumberLine(listOf(2, 9, 15, 21, 27, 33), listOf(12)))
        }
    return AiAnalysisRecord(
        providerName = "测试服务",
        model = "model-v1",
        templateName = "综合历史分析",
        sourceName = "测试官方来源",
        sampleCount = 50,
        firstIssue = if (lotteryType == LotteryType.SUPER_LOTTO) "26101" else "2026101",
        lastIssue = if (lotteryType == LotteryType.SUPER_LOTTO) "26150" else "2026150",
        generatedAtEpochMillis = generatedAtEpochMillis,
        analysis =
            AiAnalysisResult(
                schemaVersion = AiAnalysisProtocol.OUTPUT_SCHEMA_VERSION,
                lotteryType = lotteryType,
                snapshotId = "a".repeat(64),
                summary = summary,
                candidates = lines.mapIndexed { index, line -> AiAnalysisCandidate(line, "第 ${index + 1} 注说明") },
            ),
    )
}

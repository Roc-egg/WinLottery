package roc.win.lottery.persistence

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import roc.win.lottery.domain.AiAnalysisCandidate
import roc.win.lottery.domain.AiAnalysisResult
import roc.win.lottery.domain.GeneratedNumberLine
import roc.win.lottery.domain.LotteryType

/**
 * 使用与票据记录相同的 Room 数据库保存每个彩种最近一次 AI 分析结果。
 *
 * 不持有数据库生命周期，数据库由票据仓库统一关闭。
 */
internal class RoomAiAnalysisRecordStore(
    /** 与票据记录共用的 Room 数据库。 */
    database: WinLotteryDatabase,
) : AiAnalysisRecordStore {
    /** AI 分析结果数据访问对象。 */
    private val dao = database.aiAnalysisRecordDao()

    /** 解码并复核；损坏或不合法的行删除后视为没有保存。 */
    override suspend fun load(lotteryType: LotteryType): AiAnalysisRecord? {
        val entity = dao.find(lotteryType.name) ?: return null
        val record = entity.toRecordOrNull(lotteryType)
        if (record == null) dao.delete(lotteryType.name)
        return record
    }

    /** 校验后整体替换同一彩种的旧结果。 */
    override suspend fun save(record: AiAnalysisRecord) {
        val problem = record.storageProblem()
        require(problem == null) { problem.orEmpty() }
        dao.upsert(record.toEntity())
    }

    /** 删除指定彩种的结果。 */
    override suspend fun delete(lotteryType: LotteryType): Boolean = dao.delete(lotteryType.name) > 0

    /** 把结果编码为数据库行。 */
    private fun AiAnalysisRecord.toEntity(): AiAnalysisRecordEntity =
        AiAnalysisRecordEntity(
            lotteryType = lotteryType.name,
            providerName = providerName,
            model = model,
            templateName = templateName,
            sourceName = sourceName,
            sampleCount = sampleCount,
            firstIssue = firstIssue,
            lastIssue = lastIssue,
            generatedAtEpochMillis = generatedAtEpochMillis,
            schemaVersion = analysis.schemaVersion,
            snapshotId = analysis.snapshotId,
            summary = analysis.summary,
            candidates =
                STORAGE_JSON.encodeToString(
                    CANDIDATES_SERIALIZER,
                    analysis.candidates.map { candidate ->
                        StoredAiCandidate(
                            primaryNumbers = candidate.line.primaryNumbers,
                            secondaryNumbers = candidate.line.secondaryNumbers,
                            reason = candidate.reason,
                        )
                    },
                ),
            dataVersion = CURRENT_AI_ANALYSIS_RECORD_DATA_VERSION,
        )

    /** 把数据库行还原为结果；彩种、版本或内容不合法时返回 `null`。 */
    private fun AiAnalysisRecordEntity.toRecordOrNull(expectedLotteryType: LotteryType): AiAnalysisRecord? {
        if (lotteryType != expectedLotteryType.name || dataVersion != CURRENT_AI_ANALYSIS_RECORD_DATA_VERSION) {
            return null
        }
        val storedCandidates =
            runCatching { STORAGE_JSON.decodeFromString(CANDIDATES_SERIALIZER, candidates) }.getOrNull()
                ?: return null
        val record =
            AiAnalysisRecord(
                providerName = providerName,
                model = model,
                templateName = templateName,
                sourceName = sourceName,
                sampleCount = sampleCount,
                firstIssue = firstIssue,
                lastIssue = lastIssue,
                generatedAtEpochMillis = generatedAtEpochMillis,
                analysis =
                    AiAnalysisResult(
                        schemaVersion = schemaVersion,
                        lotteryType = expectedLotteryType,
                        snapshotId = snapshotId,
                        summary = summary,
                        candidates =
                            storedCandidates.map { candidate ->
                                AiAnalysisCandidate(
                                    line = GeneratedNumberLine(candidate.primaryNumbers, candidate.secondaryNumbers),
                                    reason = candidate.reason,
                                )
                            },
                    ),
            )
        return record.takeIf { it.storageProblem() == null }
    }

    /** 本机保存使用的严格 JSON 配置。 */
    private companion object {
        /** 不忽略未知字段，结构漂移视为损坏。 */
        val STORAGE_JSON = Json

        /** 候选数组序列化器。 */
        val CANDIDATES_SERIALIZER = ListSerializer(StoredAiCandidate.serializer())
    }
}

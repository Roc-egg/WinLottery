package roc.win.lottery.persistence

import androidx.room3.withWriteTransaction
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import roc.win.lottery.domain.DrawPolicy
import roc.win.lottery.domain.DrawResult
import roc.win.lottery.domain.DrawStatus
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.PrizeTier
import roc.win.lottery.domain.SourceEvidence

/**
 * 使用与票据记录相同的 Room 数据库保存查询开奖时取得的已开奖官方结果。
 *
 * 不持有数据库生命周期，数据库由票据仓库统一关闭。
 */
internal class RoomDrawResultStore(
    /** 与票据记录共用的 Room 数据库。 */
    private val database: WinLotteryDatabase,
) : DrawResultStore {
    /** 开奖结果数据访问对象。 */
    private val dao = database.drawResultDao()

    /** 逐行解码和校验；不合法的行只删除该行，其他期次照常返回。 */
    override suspend fun load(
        lotteryType: LotteryType,
        issues: Collection<Issue>,
    ): Map<Issue, DrawResult> {
        val keys = issues.map { it.value }.distinct()
        if (keys.isEmpty()) return emptyMap()
        val valid = mutableMapOf<Issue, DrawResult>()
        dao.find(lotteryType.name, keys).forEach { entity ->
            val result = entity.toDrawResultOrNull(lotteryType)
            if (result == null) {
                dao.delete(entity.lotteryType, entity.issue)
            } else {
                valid[result.issue] = result
            }
        }
        return valid
    }

    /** 校验后在写事务中按内容哈希确定本机修订号并替换同一期。 */
    override suspend fun save(drawResult: DrawResult): DrawResult {
        val problem = drawResult.storageProblem()
        require(problem == null) { problem?.message.orEmpty() }
        return database.withWriteTransaction {
            val existing =
                dao
                    .findOne(drawResult.lotteryType.name, drawResult.issue.value)
                    ?.toDrawResultOrNull(drawResult.lotteryType)
            val revision =
                when {
                    existing == null -> drawResult.revision
                    existing.evidence.contentSha256 == drawResult.evidence.contentSha256 -> existing.revision
                    else -> existing.revision + 1
                }
            val stored = drawResult.copy(revision = revision)
            dao.upsert(stored.toEntity())
            stored
        }
    }

    /** 删除一期开奖结果。 */
    override suspend fun delete(
        lotteryType: LotteryType,
        issue: Issue,
    ): Boolean = dao.delete(lotteryType.name, issue.value) > 0

    /** 删除全部开奖结果，不触碰票据和历史开奖表。 */
    override suspend fun clearAll(): Int = dao.deleteAll()

    /** 把领域结果编码为数据库行。 */
    private fun DrawResult.toEntity(): DrawResultEntity =
        DrawResultEntity(
            lotteryType = lotteryType.name,
            issue = issue.value,
            drawDate = drawDate,
            primaryNumbers = STORAGE_JSON.encodeToString(primaryNumbers),
            secondaryNumbers = STORAGE_JSON.encodeToString(secondaryNumbers),
            status = status.name,
            revision = revision,
            ruleVersion = ruleVersion,
            policy = policy.name,
            prizeTiers = STORAGE_JSON.encodeToString(prizeTiers.map { it.toStored() }),
            evidence = STORAGE_JSON.encodeToString(evidence.toStored()),
            supportingEvidence = STORAGE_JSON.encodeToString(supportingEvidence.map { it.toStored() }),
            dataVersion = CURRENT_DRAW_RESULT_DATA_VERSION,
        )

    /** 把数据库行恢复为领域结果；任一字段无法解码或未通过校验时返回 `null`。 */
    private fun DrawResultEntity.toDrawResultOrNull(expectedType: LotteryType): DrawResult? {
        if (lotteryType != expectedType.name || dataVersion != CURRENT_DRAW_RESULT_DATA_VERSION) return null
        val result =
            try {
                DrawResult(
                    lotteryType = expectedType,
                    issue = Issue(issue),
                    drawDate = drawDate,
                    primaryNumbers = STORAGE_JSON.decodeFromString<List<Int>>(primaryNumbers),
                    secondaryNumbers = STORAGE_JSON.decodeFromString<List<Int>>(secondaryNumbers),
                    status = DrawStatus.entries.firstOrNull { it.name == status } ?: return null,
                    revision = revision,
                    ruleVersion = ruleVersion,
                    policy = DrawPolicy.entries.firstOrNull { it.name == policy } ?: return null,
                    prizeTiers =
                        STORAGE_JSON.decodeFromString<List<StoredPrizeTier>>(prizeTiers).map { it.toDomain() },
                    evidence = STORAGE_JSON.decodeFromString<StoredSourceEvidence>(evidence).toDomain(),
                    supportingEvidence =
                        STORAGE_JSON
                            .decodeFromString<List<StoredSourceEvidence>>(supportingEvidence)
                            .map { it.toDomain() },
                )
            } catch (_: SerializationException) {
                return null
            } catch (_: IllegalArgumentException) {
                return null
            }
        return result.takeIf { it.storageProblem() == null }
    }

    /** 本机保存使用的严格 JSON 配置。 */
    private companion object {
        /** 不忽略未知字段，结构漂移视为损坏。 */
        val STORAGE_JSON = Json
    }
}

/** 领域奖级转为本机字段。 */
private fun PrizeTier.toStored(): StoredPrizeTier =
    StoredPrizeTier(
        code = code,
        displayName = displayName,
        singlePrizeFen = singlePrizeFen,
        additionalPrizeFen = additionalPrizeFen,
        winnerCount = winnerCount,
        additionalWinnerCount = additionalWinnerCount,
        singlePrizeRaw = singlePrizeRaw,
        additionalPrizeRaw = additionalPrizeRaw,
    )

/** 本机字段恢复为领域奖级。 */
private fun StoredPrizeTier.toDomain(): PrizeTier =
    PrizeTier(
        code = code,
        displayName = displayName,
        singlePrizeFen = singlePrizeFen,
        additionalPrizeFen = additionalPrizeFen,
        winnerCount = winnerCount,
        additionalWinnerCount = additionalWinnerCount,
        singlePrizeRaw = singlePrizeRaw,
        additionalPrizeRaw = additionalPrizeRaw,
    )

/** 领域证据转为本机字段。 */
private fun SourceEvidence.toStored(): StoredSourceEvidence =
    StoredSourceEvidence(
        sourceName = sourceName,
        sourceUrl = sourceUrl,
        fetchedAtEpochMillis = fetchedAtEpochMillis,
        contentSha256 = contentSha256,
    )

/** 本机字段恢复为领域证据。 */
private fun StoredSourceEvidence.toDomain(): SourceEvidence =
    SourceEvidence(
        sourceName = sourceName,
        sourceUrl = sourceUrl,
        fetchedAtEpochMillis = fetchedAtEpochMillis,
        contentSha256 = contentSha256,
    )

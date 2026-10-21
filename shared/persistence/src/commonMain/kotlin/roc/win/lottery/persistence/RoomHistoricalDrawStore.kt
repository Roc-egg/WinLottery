package roc.win.lottery.persistence

import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import roc.win.lottery.domain.HistoricalDraw
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryType
import kotlin.coroutines.CoroutineContext

/**
 * 使用与票据记录相同的 Room 数据库保存本机官方历史开奖。
 *
 * 不持有数据库生命周期，数据库由票据仓库统一关闭。
 */
internal class RoomHistoricalDrawStore(
    /** 与票据记录共用的 Room 数据库。 */
    private val database: WinLotteryDatabase,
    /** 校验 500 期开奖使用的计算上下文。 */
    private val validationContext: CoroutineContext = Dispatchers.Default,
) : HistoricalDrawStore {
    /** 历史开奖数据访问对象。 */
    private val dao = database.historicalDrawDao()

    /** 在读事务中取得一致快照并完整校验；损坏数据只清除对应彩种。 */
    override suspend fun load(lotteryType: LotteryType): StoredHistoricalDraws? {
        val key = lotteryType.name
        val rows =
            try {
                database.withReadTransaction<Pair<HistoricalDrawSetEntity, List<HistoricalDrawEntity>>?> {
                    val set = dao.findSet(key) ?: return@withReadTransaction null
                    set to dao.findDraws(key)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: IllegalArgumentException) {
                // 号码 JSON 无法解码属于数据损坏，与 I/O 失败区分处理。
                clear(lotteryType)
                return null
            } ?: return null
        val (set, entities) = rows
        val stored = withContext(validationContext) { rows.toStoredHistoricalDrawsOrNull(lotteryType) }
        if (stored == null || set.drawCount != entities.size) {
            clear(lotteryType)
            return null
        }
        return stored
    }

    /** 校验后在一个写事务中删除旧集合并写入新集合。 */
    override suspend fun replace(value: StoredHistoricalDraws) {
        val problem = withContext(validationContext) { value.validationProblem() }
        require(problem == null) { problem?.message.orEmpty() }
        val key = value.lotteryType.name
        val set =
            HistoricalDrawSetEntity(
                lotteryType = key,
                sourceName = value.sourceName,
                fetchedAtEpochMillis = value.fetchedAtEpochMillis,
                drawCount = value.draws.size,
                newestIssue =
                    value.draws
                        .last()
                        .issue.value,
                dataVersion = CURRENT_HISTORICAL_DRAW_DATA_VERSION,
            )
        val draws =
            value.draws.map { draw ->
                HistoricalDrawEntity(
                    lotteryType = key,
                    issue = draw.issue.value,
                    drawDate = draw.drawDate,
                    primaryNumbers = draw.primaryNumbers,
                    secondaryNumbers = draw.secondaryNumbers,
                )
            }
        database.withWriteTransaction {
            dao.deleteDraws(key)
            dao.deleteSet(key)
            dao.insertSet(set)
            dao.insertDraws(draws)
        }
    }

    /** 只在最新期号一致时更新来源和核对时间。 */
    override suspend fun updateFetchedAt(
        lotteryType: LotteryType,
        newestIssue: Issue,
        sourceName: String,
        fetchedAtEpochMillis: Long,
    ): Boolean {
        require(sourceName.isNotBlank()) { "历史开奖来源为空" }
        require(fetchedAtEpochMillis >= 0L) { "历史开奖核对时间不合法" }
        return dao.touchSet(lotteryType.name, newestIssue.value, sourceName, fetchedAtEpochMillis) == 1
    }

    /** 在写事务中删除指定彩种的集合。 */
    override suspend fun clear(lotteryType: LotteryType): Int =
        database.withWriteTransaction {
            dao.deleteDraws(lotteryType.name)
            dao.deleteSet(lotteryType.name)
        }

    /** 在写事务中删除全部彩种的集合，不触碰票据表。 */
    override suspend fun clearAll(): Int =
        database.withWriteTransaction {
            dao.deleteAllDraws()
            dao.deleteAllSets()
        }

    /** 把数据库行恢复为业务集合；元数据或任一开奖不合法时返回 `null`。 */
    private fun Pair<HistoricalDrawSetEntity, List<HistoricalDrawEntity>>.toStoredHistoricalDrawsOrNull(
        lotteryType: LotteryType,
    ): StoredHistoricalDraws? {
        val (set, entities) = this
        if (set.lotteryType != lotteryType.name || set.dataVersion != CURRENT_HISTORICAL_DRAW_DATA_VERSION) {
            return null
        }
        if (entities.lastOrNull()?.issue != set.newestIssue) return null
        val stored =
            StoredHistoricalDraws(
                lotteryType = lotteryType,
                draws =
                    entities.map { entity ->
                        HistoricalDraw(
                            lotteryType = lotteryType,
                            issue = Issue(entity.issue),
                            drawDate = entity.drawDate,
                            primaryNumbers = entity.primaryNumbers,
                            secondaryNumbers = entity.secondaryNumbers,
                        )
                    },
                sourceName = set.sourceName,
                fetchedAtEpochMillis = set.fetchedAtEpochMillis,
            )
        return stored.takeIf { it.validationProblem() == null }
    }
}

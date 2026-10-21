package roc.win.lottery.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import roc.win.lottery.data.HistoricalDrawQueryResult
import roc.win.lottery.data.HistoricalDrawRepository
import roc.win.lottery.domain.HistoricalDraw
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.persistence.HISTORICAL_DRAW_SET_SIZE
import roc.win.lottery.persistence.HistoricalDrawSetProblem
import roc.win.lottery.persistence.HistoricalDrawStore
import roc.win.lottery.persistence.StoredHistoricalDraws
import roc.win.lottery.persistence.validationProblem
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * 一个彩种当前可用的 500 期官方历史开奖。
 *
 * @property lotteryType 彩种。
 * @property draws 按期号升序排列的连续开奖。
 * @property sourceName 官方来源展示名称。
 * @property fetchedAtEpochMillis 最近一次与官网核对成功的时间。
 * @property revision 开奖内容版本，只在号码内容变化时递增，用于判断界面是否需要重算。
 */
internal data class HistoricalDrawEntry(
    val lotteryType: LotteryType,
    val draws: List<HistoricalDraw>,
    val sourceName: String,
    val fetchedAtEpochMillis: Long,
    val revision: Long,
)

/** 一次与官网核对的结果。 */
internal sealed interface HistoricalDrawSyncResult {
    /**
     * 已取得可用的历史开奖。
     *
     * @property entry 当前可用的集合。
     * @property notice 需要告知用户、但不影响使用的说明。
     */
    data class Ready(
        val entry: HistoricalDrawEntry,
        val notice: String? = null,
    ) : HistoricalDrawSyncResult

    /**
     * 本次核对失败。
     *
     * @property message 不包含官网原始响应的恢复说明。
     * @property cached 失败时仍可继续展示的本机集合。
     */
    data class Failed(
        val message: String,
        val cached: HistoricalDrawEntry?,
    ) : HistoricalDrawSyncResult
}

/**
 * 合并内存、本机 Room 缓存和官网仓库的官方历史开奖来源。
 *
 * 只在用户操作触发时联网：本机数据未超过 [refreshInterval] 时直接复用；过期或手动刷新时只取最新一页做增量核对，
 * 重叠部分与本机不一致视为官网修订并整体重新加载。同一彩种的并发核对只发一次请求，清除后的迟到结果不会写回。
 *
 * 状态只在调用方所在的调度器上读写；计算上下文只用于无副作用的校验。
 */
internal class HistoricalDrawCache(
    /** 官网历史开奖仓库；未接入的平台为 `null`。 */
    private val repository: HistoricalDrawRepository?,
    /** 本机 Room 缓存；未接入的平台为 `null`，此时只在内存中保留。 */
    private val store: HistoricalDrawStore?,
    /** 判断本机数据是否过期的时钟。 */
    private val clock: Clock,
    /** 校验 500 期开奖使用的计算上下文。 */
    private val computationContext: CoroutineContext,
    /** 本机数据自动增量核对的最短间隔。 */
    private val refreshInterval: Duration = DEFAULT_REFRESH_INTERVAL,
) {
    /** 当前进程内已经取得的集合。 */
    private val memory = mutableMapOf<LotteryType, HistoricalDrawEntry>()

    /** 下一次分配的内容版本。 */
    private var lastRevision: Long = 0L

    /** 每个彩种独立的核对锁，合并走势和 AI 的并发请求。 */
    private val syncLocks = LotteryType.entries.associateWith { Mutex() }

    /** 每个彩种已成功完成的核对次数，用于识别等待锁期间已被其他请求刷新的情况。 */
    private val completedSyncCounts = mutableMapOf<LotteryType, Long>()

    /** 清除代次；清除后开始前的核对结果不得写回内存或本机。 */
    private var clearEpoch: Long = 0L

    /** 保证清除与写入互斥。 */
    private val writeLock = Mutex()

    /** 只读取内存中的集合，不访问本机数据库或网络。 */
    fun current(lotteryType: LotteryType): HistoricalDrawEntry? = memory[lotteryType]

    /** 依次读取内存和本机缓存，并把本机集合放入内存；读取失败时视为没有缓存。 */
    suspend fun peek(lotteryType: LotteryType): HistoricalDrawEntry? {
        memory[lotteryType]?.let { return it }
        val epoch = clearEpoch
        val stored =
            try {
                store?.load(lotteryType)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            } ?: return null
        memory[lotteryType]?.let { return it }
        if (epoch != clearEpoch) return null
        return remember(stored)
    }

    /** 判断集合是否需要与官网增量核对；时钟回拨也视为需要核对。 */
    fun isStale(entry: HistoricalDrawEntry): Boolean {
        val age = clock.now().toEpochMilliseconds() - entry.fetchedAtEpochMillis
        return age !in 0 until refreshInterval.inWholeMilliseconds
    }

    /**
     * 按需与官网核对指定彩种。
     *
     * @param force `true` 时即使本机数据未过期也做一次增量核对，用于手动刷新。
     */
    suspend fun sync(
        lotteryType: LotteryType,
        force: Boolean,
    ): HistoricalDrawSyncResult {
        val completedBeforeWaiting = completedSyncCounts[lotteryType] ?: 0L
        return syncLocks.getValue(lotteryType).withLock {
            val cached = peek(lotteryType)
            val refreshedWhileWaiting = (completedSyncCounts[lotteryType] ?: 0L) != completedBeforeWaiting
            if (cached != null && (refreshedWhileWaiting || (!force && !isStale(cached)))) {
                return@withLock HistoricalDrawSyncResult.Ready(cached)
            }
            val repository =
                repository
                    ?: return@withLock HistoricalDrawSyncResult.Failed("当前平台尚未接入官方历史开奖查询", cached)
            val epoch = clearEpoch
            val outcome =
                if (cached == null) {
                    loadFull(lotteryType, repository)
                } else {
                    loadIncrement(cached, repository)
                }
            if (outcome !is SyncOutcome.Failure) {
                completedSyncCounts[lotteryType] = (completedSyncCounts[lotteryType] ?: 0L) + 1L
            }
            commit(lotteryType, cached, outcome, epoch)
        }
    }

    /** 丢弃指定彩种的内存和本机集合，用于集合未通过计算或用户明确重试。 */
    suspend fun discard(lotteryType: LotteryType) {
        writeLock.withLock {
            memory.remove(lotteryType)
            try {
                store?.clear(lotteryType)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // 本机删除失败时下次读取仍会完整校验，内存已不再使用该集合。
            }
        }
    }

    /**
     * 清除全部彩种的内存和本机集合，不影响票据记录。
     *
     * @return 本机数据是否已成功清除。
     */
    suspend fun clearAll(): Boolean =
        writeLock.withLock {
            clearEpoch += 1L
            memory.clear()
            try {
                store?.clearAll()
                true
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
        }

    /** 从官网完整加载最近 500 期。 */
    private suspend fun loadFull(
        lotteryType: LotteryType,
        repository: HistoricalDrawRepository,
        notice: String? = null,
    ): SyncOutcome {
        val result = repository.getLatestDraws(lotteryType, HISTORICAL_DRAW_SET_SIZE)
        if (result is HistoricalDrawQueryResult.Unavailable) return SyncOutcome.Failure(result.message)
        result as HistoricalDrawQueryResult.Success
        if (result.draws.size != HISTORICAL_DRAW_SET_SIZE) {
            return SyncOutcome.Failure("官网未返回完整的 $HISTORICAL_DRAW_SET_SIZE 期历史开奖，请主动重试")
        }
        val stored = StoredHistoricalDraws(lotteryType, result.draws, result.sourceName, result.fetchedAtEpochMillis)
        return when (withContext(computationContext) { stored.validationProblem() }) {
            null -> {
                SyncOutcome.Replace(stored, notice)
            }

            // 同年度缺期沿用会话行为：走势仍可展示缺口，但不写入本机，避免把不完整样本长期保存。
            HistoricalDrawSetProblem.ISSUE_GAP -> {
                SyncOutcome.MemoryOnly(stored, notice ?: "官网历史样本存在期号空档，本次数据未保存到本机")
            }

            else -> {
                SyncOutcome.Failure("官方历史开奖未通过完整性校验，请主动重试")
            }
        }
    }

    /**
     * 只取官网最新一页与本机集合核对重叠部分，并补入新开奖。
     *
     * 官网最新期倒退、没有重叠、重叠内容不一致或合并结果不完整时，退回完整加载并覆盖本机集合。
     */
    private suspend fun loadIncrement(
        cached: HistoricalDrawEntry,
        repository: HistoricalDrawRepository,
    ): SyncOutcome {
        val lotteryType = cached.lotteryType
        val result = repository.getLatestDraws(lotteryType, INCREMENTAL_WINDOW_SIZE)
        if (result is HistoricalDrawQueryResult.Unavailable) return SyncOutcome.Failure(result.message)
        result as HistoricalDrawQueryResult.Success
        val window = result.draws
        if (window.size != INCREMENTAL_WINDOW_SIZE || window.any { draw -> draw.lotteryType != lotteryType }) {
            return loadFull(lotteryType, repository)
        }
        val cachedNewest =
            cached.draws
                .last()
                .issue.value
        val windowOldest = window.first().issue.value
        val windowNewest = window.last().issue.value
        if (windowOldest > cachedNewest) return loadFull(lotteryType, repository)
        val cachedOverlap = cached.draws.filter { draw -> draw.issue.value >= windowOldest }
        val windowOverlap = window.filter { draw -> draw.issue.value <= cachedNewest }
        if (windowNewest < cachedNewest || cachedOverlap != windowOverlap) {
            return loadFull(lotteryType, repository, notice = "检测到官网历史开奖有修订，已重新加载 500 期")
        }
        val newDraws = window.filter { draw -> draw.issue.value > cachedNewest }
        if (newDraws.isEmpty()) {
            return SyncOutcome.Touch(result.sourceName, result.fetchedAtEpochMillis)
        }
        val merged =
            StoredHistoricalDraws(
                lotteryType = lotteryType,
                draws = (cached.draws + newDraws).takeLast(HISTORICAL_DRAW_SET_SIZE),
                sourceName = result.sourceName,
                fetchedAtEpochMillis = result.fetchedAtEpochMillis,
            )
        if (withContext(computationContext) { merged.validationProblem() } != null) {
            return loadFull(lotteryType, repository)
        }
        return SyncOutcome.Replace(merged, notice = null)
    }

    /** 把核对结果写入内存和本机；核对期间被清除时丢弃结果。 */
    private suspend fun commit(
        lotteryType: LotteryType,
        cached: HistoricalDrawEntry?,
        outcome: SyncOutcome,
        epoch: Long,
    ): HistoricalDrawSyncResult =
        writeLock.withLock {
            if (epoch != clearEpoch) {
                return@withLock HistoricalDrawSyncResult.Failed("本机历史开奖已清除，请重新进入页面加载", null)
            }
            when (outcome) {
                is SyncOutcome.Failure -> {
                    HistoricalDrawSyncResult.Failed(outcome.message, cached)
                }

                is SyncOutcome.Touch -> {
                    val current = checkNotNull(cached) { "只更新核对时间时必须已有集合" }
                    val updated =
                        current.copy(
                            sourceName = outcome.sourceName,
                            fetchedAtEpochMillis = outcome.fetchedAtEpochMillis,
                        )
                    memory[lotteryType] = updated
                    val saved =
                        persist {
                            val touched =
                                store?.updateFetchedAt(
                                    lotteryType = lotteryType,
                                    newestIssue = current.draws.last().issue,
                                    sourceName = outcome.sourceName,
                                    fetchedAtEpochMillis = outcome.fetchedAtEpochMillis,
                                ) ?: true
                            if (!touched) store?.replace(updated.toStored())
                        }
                    HistoricalDrawSyncResult.Ready(updated, if (saved) null else PERSIST_FAILURE_NOTICE)
                }

                is SyncOutcome.Replace -> {
                    val entry = remember(outcome.stored)
                    val saved = persist { store?.replace(outcome.stored) }
                    HistoricalDrawSyncResult.Ready(entry, if (saved) outcome.notice else PERSIST_FAILURE_NOTICE)
                }

                is SyncOutcome.MemoryOnly -> {
                    HistoricalDrawSyncResult.Ready(remember(outcome.stored), outcome.notice)
                }
            }
        }

    /** 执行本机写入；失败时不影响本次展示。 */
    private suspend fun persist(block: suspend () -> Unit): Boolean =
        try {
            block()
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }

    /** 以新的内容版本放入内存。 */
    private fun remember(stored: StoredHistoricalDraws): HistoricalDrawEntry {
        lastRevision += 1L
        val entry =
            HistoricalDrawEntry(
                lotteryType = stored.lotteryType,
                draws = stored.draws,
                sourceName = stored.sourceName,
                fetchedAtEpochMillis = stored.fetchedAtEpochMillis,
                revision = lastRevision,
            )
        memory[stored.lotteryType] = entry
        return entry
    }

    /** 转换为本机仓库使用的集合。 */
    private fun HistoricalDrawEntry.toStored(): StoredHistoricalDraws =
        StoredHistoricalDraws(lotteryType, draws, sourceName, fetchedAtEpochMillis)

    /** 一次核对在提交前的内部结果。 */
    private sealed interface SyncOutcome {
        /** 官网没有新开奖，只更新来源和核对时间。 */
        data class Touch(
            val sourceName: String,
            val fetchedAtEpochMillis: Long,
        ) : SyncOutcome

        /** 以合法集合整体替换内存和本机缓存。 */
        data class Replace(
            val stored: StoredHistoricalDraws,
            val notice: String?,
        ) : SyncOutcome

        /** 集合存在同年度缺期，只放入内存。 */
        data class MemoryOnly(
            val stored: StoredHistoricalDraws,
            val notice: String,
        ) : SyncOutcome

        /** 网络或数据失败，不改变已有集合。 */
        data class Failure(
            val message: String,
        ) : SyncOutcome
    }

    /** 核对窗口和间隔。 */
    companion object {
        /** 本机数据自动增量核对的默认最短间隔。 */
        val DEFAULT_REFRESH_INTERVAL: Duration = 30.minutes

        /** 增量核对一次取得的最新期数，两个彩种都只需一次请求。 */
        const val INCREMENTAL_WINDOW_SIZE = 100

        /** 本机写入失败时的提示。 */
        private const val PERSIST_FAILURE_NOTICE = "本机保存失败，本次数据仅在应用运行期间有效"
    }
}

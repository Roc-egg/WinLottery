package roc.win.lottery.app

import kotlinx.coroutines.CompletableDeferred
import roc.win.lottery.data.HistoricalDrawFailureReason
import roc.win.lottery.data.HistoricalDrawQueryResult
import roc.win.lottery.data.HistoricalDrawRepository
import roc.win.lottery.domain.HistoricalDraw
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.persistence.HistoricalDrawStore
import roc.win.lottery.persistence.StoredHistoricalDraws
import kotlin.time.Clock
import kotlin.time.Instant

/** 测试历史开奖仓库统一返回的官网核对时间。 */
internal const val TEST_HISTORY_FETCHED_AT = 1_787_689_800_000L

/** 可由测试推进的时钟，默认停在测试官网核对时间，使刚取得的数据不被视为过期。 */
internal class MutableTestClock(
    /** 当前时间，Unix 毫秒。 */
    var nowEpochMillis: Long = TEST_HISTORY_FETCHED_AT,
) : Clock {
    /** 返回当前测试时间。 */
    override fun now(): Instant = Instant.fromEpochMilliseconds(nowEpochMillis)
}

/** 记录调用次数的内存版本机历史开奖缓存，可模拟写入失败。 */
internal class InMemoryHistoricalDrawStore(
    /** 预置的本机集合。 */
    initial: List<StoredHistoricalDraws> = emptyList(),
) : HistoricalDrawStore {
    /** 当前保存的集合。 */
    val sets: MutableMap<LotteryType, StoredHistoricalDraws> =
        initial.associateBy(StoredHistoricalDraws::lotteryType).toMutableMap()

    /** 是否让写入抛出受控异常。 */
    var failWrites: Boolean = false

    /** 已收到的整体替换次数。 */
    var replaceCount: Int = 0
        private set

    /** 已收到的只更新时间次数。 */
    var touchCount: Int = 0
        private set

    /** 已收到的全部清除次数。 */
    var clearAllCount: Int = 0
        private set

    /** 返回保存的集合。 */
    override suspend fun load(lotteryType: LotteryType): StoredHistoricalDraws? = sets[lotteryType]

    /** 整体替换集合。 */
    override suspend fun replace(value: StoredHistoricalDraws) {
        replaceCount += 1
        check(!failWrites) { "测试写入失败" }
        sets[value.lotteryType] = value
    }

    /** 只在最新期号一致时更新时间。 */
    override suspend fun updateFetchedAt(
        lotteryType: LotteryType,
        newestIssue: Issue,
        sourceName: String,
        fetchedAtEpochMillis: Long,
    ): Boolean {
        touchCount += 1
        check(!failWrites) { "测试写入失败" }
        val current = sets[lotteryType] ?: return false
        if (current.draws.last().issue != newestIssue) return false
        sets[lotteryType] = current.copy(sourceName = sourceName, fetchedAtEpochMillis = fetchedAtEpochMillis)
        return true
    }

    /** 删除指定彩种。 */
    override suspend fun clear(lotteryType: LotteryType): Int = if (sets.remove(lotteryType) == null) 0 else 1

    /** 删除全部彩种。 */
    override suspend fun clearAll(): Int {
        clearAllCount += 1
        val count = sets.size
        sets.clear()
        return count
    }
}

/**
 * 生成一期号码合法的测试开奖；期号为年度前缀加三位序号，日期随序号单调递增。
 *
 * @param revised 为 `true` 时返回号码不同的官方修订版本。
 */
internal fun testHistoricalDraw(
    lotteryType: LotteryType,
    ordinal: Int,
    revised: Boolean = false,
): HistoricalDraw {
    val shift = if (revised) 1 else 0
    val date = "${2000 + ordinal / 336}-${(1 + ordinal % 336 / 28).twoDigits()}-${(1 + ordinal % 28).twoDigits()}"
    return when (lotteryType) {
        LotteryType.SUPER_LOTTO -> {
            HistoricalDraw(
                lotteryType = lotteryType,
                issue = Issue("26${ordinal.toString().padStart(3, '0')}"),
                drawDate = date,
                primaryNumbers = (0 until 5).map { offset -> (ordinal + shift + offset * 7) % 35 + 1 }.sorted(),
                secondaryNumbers = listOf(ordinal % 12 + 1, (ordinal + 5) % 12 + 1).sorted(),
            )
        }

        LotteryType.DOUBLE_COLOR_BALL -> {
            HistoricalDraw(
                lotteryType = lotteryType,
                issue = Issue("2026${ordinal.toString().padStart(3, '0')}"),
                drawDate = date,
                primaryNumbers = (0 until 6).map { offset -> (ordinal + shift + offset * 5) % 33 + 1 }.sorted(),
                secondaryNumbers = listOf(ordinal % 16 + 1),
            )
        }
    }
}

/** 生成以 [newestOrdinal] 结尾、连续 [count] 期的测试开奖。 */
internal fun testHistoricalDraws(
    lotteryType: LotteryType,
    newestOrdinal: Int,
    count: Int = 500,
): List<HistoricalDraw> = (newestOrdinal - count + 1..newestOrdinal).map { testHistoricalDraw(lotteryType, it) }

/** 把测试开奖包装为本机缓存集合。 */
internal fun testStoredHistory(
    lotteryType: LotteryType,
    newestOrdinal: Int,
    fetchedAtEpochMillis: Long,
): StoredHistoricalDraws =
    StoredHistoricalDraws(
        lotteryType = lotteryType,
        draws = testHistoricalDraws(lotteryType, newestOrdinal),
        sourceName = "测试官网",
        fetchedAtEpochMillis = fetchedAtEpochMillis,
    )

/** 可脚本控制最新期、修订、缺期、断网和暂停的官网历史开奖仓库。 */
internal class ScriptedHistoryRepository(
    /** 提供官网核对时间的测试时钟。 */
    private val clock: MutableTestClock,
    /** 每个彩种官网最新一期的序号。 */
    var newestOrdinal: Int = 500,
) : HistoricalDrawRepository {
    /** 按调用顺序记录的彩种和请求期数。 */
    val requests = mutableListOf<Pair<LotteryType, Int>>()

    /** 号码已被官方修订的期次序号。 */
    var revisedOrdinals: Set<Int> = emptySet()

    /** 官网缺失的期次序号，用于构造期号空档。 */
    var missingOrdinals: Set<Int> = emptySet()

    /** 非空时所有请求返回网络不可用。 */
    var failureMessage: String? = null

    /** 非空时每个请求在返回前等待该信号，用于观察进行中的状态。 */
    var gate: CompletableDeferred<Unit>? = null

    /** 第一次请求进入仓库的信号。 */
    val started = CompletableDeferred<Unit>()

    /** 返回以最新期结尾的连续开奖，或按脚本失败。 */
    override suspend fun getLatestDraws(
        lotteryType: LotteryType,
        count: Int,
    ): HistoricalDrawQueryResult {
        requests += lotteryType to count
        started.complete(Unit)
        gate?.await()
        failureMessage?.let { message ->
            return HistoricalDrawQueryResult.Unavailable(HistoricalDrawFailureReason.NETWORK_UNAVAILABLE, message)
        }
        val draws =
            generateSequence(newestOrdinal) { it - 1 }
                .filter { ordinal -> ordinal !in missingOrdinals }
                .take(count)
                .toList()
                .asReversed()
                .map { ordinal -> testHistoricalDraw(lotteryType, ordinal, revised = ordinal in revisedOrdinals) }
        return HistoricalDrawQueryResult.Success(draws, "测试官网", clock.now().toEpochMilliseconds())
    }
}

/** 返回两位补零的日期字段。 */
private fun Int.twoDigits(): String = toString().padStart(2, '0')

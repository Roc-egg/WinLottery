package roc.win.lottery.persistence

import roc.win.lottery.domain.HistoricalDraw
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryTrendArea
import roc.win.lottery.domain.LotteryTrendCalculationResult
import roc.win.lottery.domain.LotteryTrendCalculator
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.TrendSampleSize

/**
 * 本机保存的一个彩种最近 500 期规范化官方开奖。
 *
 * @property lotteryType 彩种。
 * @property draws 按期号升序排列、恰好 [HISTORICAL_DRAW_SET_SIZE] 期的连续开奖。
 * @property sourceName 官方来源展示名称。
 * @property fetchedAtEpochMillis 最近一次与官网核对成功的时间，Unix 毫秒。
 */
data class StoredHistoricalDraws(
    val lotteryType: LotteryType,
    val draws: List<HistoricalDraw>,
    val sourceName: String,
    val fetchedAtEpochMillis: Long,
)

/**
 * 本机官方历史开奖缓存的业务接口。
 *
 * 只保存规范化开奖事实，不保存官网原始响应；实现必须在读写边界重复执行完整校验。
 */
interface HistoricalDrawStore {
    /** 读取指定彩种的合法集合；不存在或校验失败时返回 `null`，失败数据会被清除。 */
    suspend fun load(lotteryType: LotteryType): StoredHistoricalDraws?

    /**
     * 在单个事务中整体替换指定彩种的集合。
     *
     * @throws IllegalArgumentException 集合未通过校验时抛出，且不写入任何数据。
     */
    suspend fun replace(value: StoredHistoricalDraws)

    /** 官网没有新开奖时只更新来源和核对时间；最新期号已不一致时返回 `false`。 */
    suspend fun updateFetchedAt(
        lotteryType: LotteryType,
        newestIssue: Issue,
        sourceName: String,
        fetchedAtEpochMillis: Long,
    ): Boolean

    /** 删除指定彩种的集合，返回删除的集合数量。 */
    suspend fun clear(lotteryType: LotteryType): Int

    /** 删除全部彩种的集合，返回删除的集合数量；不影响票据记录。 */
    suspend fun clearAll(): Int
}

/** 每个彩种在本机保存的固定期数，与走势、数学研究和 AI 的最大样本一致。 */
val HISTORICAL_DRAW_SET_SIZE: Int = TrendSampleSize.LAST_500.count

/** 当前本机历史开奖逻辑数据版本。 */
internal const val CURRENT_HISTORICAL_DRAW_DATA_VERSION = 1

/** 本机历史开奖集合不合法的原因。 */
enum class HistoricalDrawSetProblem(
    /** 面向日志和异常的简体中文说明。 */
    val message: String,
) {
    /** 来源名称为空。 */
    BLANK_SOURCE("历史开奖来源为空"),

    /** 核对时间早于 Unix 纪元。 */
    INVALID_FETCHED_AT("历史开奖核对时间不合法"),

    /** 期数不是固定的 500 期。 */
    WRONG_SIZE("历史开奖必须恰好为 500 期"),

    /** 混入其他彩种。 */
    MIXED_LOTTERY("历史开奖混入其他彩种"),

    /** 期号没有严格升序。 */
    NOT_ASCENDING("历史开奖期号未严格升序"),

    /** 开奖日期格式不合法或倒退。 */
    INVALID_DATE("历史开奖日期不合法或倒退"),

    /** 号码或期号格式不合法。 */
    INVALID_DRAW("历史开奖号码或期号不合法"),

    /** 存在可证明的期号空档。 */
    ISSUE_GAP("历史开奖存在期号空档"),
}

/**
 * 校验一组本机历史开奖并返回失败原因；全部满足时返回 `null`。
 *
 * 要求来源非空、时间合法、恰好 500 期、期号严格升序、日期格式合法且不倒退，
 * 并通过走势计算器的号码与期号校验，且不存在可证明的期号空档。
 */
fun StoredHistoricalDraws.validationProblem(): HistoricalDrawSetProblem? {
    if (sourceName.isBlank()) return HistoricalDrawSetProblem.BLANK_SOURCE
    if (fetchedAtEpochMillis < 0L) return HistoricalDrawSetProblem.INVALID_FETCHED_AT
    if (draws.size != HISTORICAL_DRAW_SET_SIZE) return HistoricalDrawSetProblem.WRONG_SIZE
    if (draws.any { draw -> draw.lotteryType != lotteryType }) return HistoricalDrawSetProblem.MIXED_LOTTERY
    if (draws.zipWithNext().any { (previous, next) -> previous.issue.value >= next.issue.value }) {
        return HistoricalDrawSetProblem.NOT_ASCENDING
    }
    if (
        draws.any { draw -> !DRAW_DATE_REGEX.matches(draw.drawDate) } ||
        draws.zipWithNext().any { (previous, next) -> previous.drawDate > next.drawDate }
    ) {
        return HistoricalDrawSetProblem.INVALID_DATE
    }
    val calculation =
        LotteryTrendCalculator().calculate(
            lotteryType = lotteryType,
            area = LotteryTrendArea.PRIMARY,
            sampleSize = TrendSampleSize.LAST_500,
            draws = draws,
        )
    return when (calculation) {
        is LotteryTrendCalculationResult.InvalidData -> {
            HistoricalDrawSetProblem.INVALID_DRAW
        }

        is LotteryTrendCalculationResult.Success -> {
            if (calculation.snapshot.issueGaps.isEmpty()) null else HistoricalDrawSetProblem.ISSUE_GAP
        }
    }
}

/** 开奖日期固定格式。 */
private val DRAW_DATE_REGEX = Regex("""\d{4}-\d{2}-\d{2}""")

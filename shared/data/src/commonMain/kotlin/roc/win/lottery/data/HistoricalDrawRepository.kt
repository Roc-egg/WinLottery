package roc.win.lottery.data

import roc.win.lottery.domain.HistoricalDraw
import roc.win.lottery.domain.LotteryType

/** 按官方最新已发布期向前查询历史开奖。 */
interface HistoricalDrawRepository {
    /**
     * 查询指定彩种最近的真实历史开奖。
     *
     * @param lotteryType 需要查询的彩种。
     * @param count 从官方最新已发布期向前截取的期数。
     * @return 按开奖时间正序排列的规范化历史开奖或明确的不可用状态。
     */
    suspend fun getLatestDraws(
        lotteryType: LotteryType,
        count: Int,
    ): HistoricalDrawQueryResult

    /** 历史开奖查询边界。 */
    companion object {
        /** 单次用户操作最多加载的历史开奖期数。 */
        const val MAXIMUM_DRAW_COUNT = 500
    }
}

/** 历史开奖查询失败类型。 */
enum class HistoricalDrawFailureReason {
    /** 当前设备无法连接官网。 */
    NETWORK_UNAVAILABLE,

    /** 官网响应或规范化数据不满足已知契约。 */
    SOURCE_UNAVAILABLE,
}

/** 历史开奖查询的封闭结果。 */
sealed interface HistoricalDrawQueryResult {
    /**
     * 已取得完整、规范化的真实历史开奖。
     *
     * @property draws 按开奖时间正序排列，最后一条是官方最新已发布期。
     * @property sourceName 官方来源展示名称。
     * @property fetchedAtEpochMillis 本次用户操作完成抓取的时间。
     */
    data class Success(
        val draws: List<HistoricalDraw>,
        val sourceName: String,
        val fetchedAtEpochMillis: Long,
    ) : HistoricalDrawQueryResult

    /**
     * 当前无法形成完整历史样本。
     *
     * @property reason 失败类型。
     * @property message 不包含官网原始响应的恢复说明。
     */
    data class Unavailable(
        val reason: HistoricalDrawFailureReason,
        val message: String,
    ) : HistoricalDrawQueryResult
}

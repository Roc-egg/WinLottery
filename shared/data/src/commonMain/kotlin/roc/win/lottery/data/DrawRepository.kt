package roc.win.lottery.data

import roc.win.lottery.domain.DrawResult
import roc.win.lottery.domain.DrawStatus
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryType

/** 按用户确认的彩种和期号查询开奖数据。 */
fun interface DrawRepository {
    /**
     * 只查询指定的单个期号，不猜测或回退到最新一期。
     *
     * @param lotteryType 用户确认的彩种。
     * @param issue 用户确认的期号。
     * @return 统一开奖结果或明确的不可用状态。
     */
    suspend fun getDraw(
        lotteryType: LotteryType,
        issue: Issue,
    ): DrawQueryResult
}

/** 开奖查询的封闭结果。 */
sealed interface DrawQueryResult {
    /**
     * 查询并校验成功。
     *
     * @property drawResult 不包含上游 DTO 的统一领域模型。
     */
    data class Success(
        val drawResult: DrawResult,
    ) : DrawQueryResult

    /**
     * 当前无法得到足够证据，禁止把它解释成未中奖。
     *
     * @property status 明确的开奖不可用状态。
     * @property message 面向用户的简体中文恢复说明。
     */
    data class Unavailable(
        val status: DrawStatus,
        val message: String,
    ) : DrawQueryResult {
        init {
            require(status !in SUCCESS_STATUSES) { "可用状态必须通过 Success 返回" }
        }
    }

    /** 查询成功对应的状态集合。 */
    private companion object {
        /** 可携带统一开奖结果的状态。 */
        val SUCCESS_STATUSES = setOf(DrawStatus.FINAL_NUMBERS, DrawStatus.FINAL_PAYOUT)
    }
}

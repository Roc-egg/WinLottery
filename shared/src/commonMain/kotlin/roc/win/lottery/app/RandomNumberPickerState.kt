package roc.win.lottery.app

import roc.win.lottery.domain.CrossPeriodRandomMode
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.RandomNumberGenerationLimits
import roc.win.lottery.domain.RandomNumberGenerationRequest
import roc.win.lottery.domain.RandomNumberPlan

/** 用户在随机选号页发起的单一操作。 */
sealed interface RandomNumberPickerAction {
    /**
     * 修改目标彩种。
     *
     * @property lotteryType 用户选择的彩种。
     */
    data class ChangeLotteryType(
        val lotteryType: LotteryType,
    ) : RandomNumberPickerAction

    /**
     * 修改相对期次数量。
     *
     * @property value 新的期数。
     */
    data class ChangePeriodCount(
        val value: Int,
    ) : RandomNumberPickerAction

    /**
     * 修改每一期生成的注数。
     *
     * @property value 新的每期注数。
     */
    data class ChangeBetsPerPeriod(
        val value: Int,
    ) : RandomNumberPickerAction

    /**
     * 修改跨期生成模式。
     *
     * @property mode 用户选择的跨期模式。
     */
    data class ChangeCrossPeriodMode(
        val mode: CrossPeriodRandomMode,
    ) : RandomNumberPickerAction

    /** 使用当前配置生成或重新生成随机号码。 */
    data object Generate : RandomNumberPickerAction
}

/**
 * 当前应用会话中的随机选号配置与结果。
 *
 * @property lotteryType 当前目标彩种。
 * @property periodCount 相对期次数量。
 * @property betsPerPeriod 每一期生成的注数。
 * @property crossPeriodMode 当前跨期模式。
 * @property plan 最近一次完整生成的方案；配置变化后立即清除。
 * @property errorMessage 领域生成器拒绝请求时的安全说明。
 */
data class RandomNumberPickerState(
    val lotteryType: LotteryType = LotteryType.SUPER_LOTTO,
    val periodCount: Int = 1,
    val betsPerPeriod: Int = 1,
    val crossPeriodMode: CrossPeriodRandomMode = CrossPeriodRandomMode.REUSE_SAME_LINES,
    val plan: RandomNumberPlan? = null,
    val errorMessage: String? = null,
) {
    /** 当前配置包含的投注实例总数。 */
    val totalBetCount: Int
        get() = periodCount * betsPerPeriod

    /** 把当前合法配置转换为领域生成请求。 */
    fun toGenerationRequest(): RandomNumberGenerationRequest =
        RandomNumberGenerationRequest(
            lotteryType = lotteryType,
            periodCount = periodCount,
            betsPerPeriod = betsPerPeriod,
            crossPeriodMode = crossPeriodMode,
        )

    /** 应用一项配置操作；越界输入或当前彩种不支持的模式会被忽略。 */
    fun applyConfiguration(action: RandomNumberPickerAction): RandomNumberPickerState =
        when (action) {
            is RandomNumberPickerAction.ChangeLotteryType -> {
                if (lotteryType == action.lotteryType) {
                    this
                } else {
                    copy(
                        lotteryType = action.lotteryType,
                        crossPeriodMode =
                            if (action.lotteryType == LotteryType.SUPER_LOTTO) {
                                CrossPeriodRandomMode.REUSE_SAME_LINES
                            } else {
                                crossPeriodMode
                            },
                        plan = null,
                        errorMessage = null,
                    )
                }
            }

            is RandomNumberPickerAction.ChangePeriodCount -> {
                if (
                    action.value !in
                    RandomNumberGenerationLimits.MIN_PERIOD_COUNT..RandomNumberGenerationLimits.MAX_PERIOD_COUNT ||
                    action.value == periodCount
                ) {
                    this
                } else {
                    copy(periodCount = action.value, plan = null, errorMessage = null)
                }
            }

            is RandomNumberPickerAction.ChangeBetsPerPeriod -> {
                val isOutsideLimits =
                    action.value < RandomNumberGenerationLimits.MIN_BETS_PER_PERIOD ||
                        action.value > RandomNumberGenerationLimits.MAX_BETS_PER_PERIOD
                if (
                    isOutsideLimits || action.value == betsPerPeriod
                ) {
                    this
                } else {
                    copy(betsPerPeriod = action.value, plan = null, errorMessage = null)
                }
            }

            is RandomNumberPickerAction.ChangeCrossPeriodMode -> {
                if (
                    action.mode == crossPeriodMode ||
                    (
                        lotteryType == LotteryType.SUPER_LOTTO &&
                            action.mode == CrossPeriodRandomMode.INDEPENDENT_PER_PERIOD
                    )
                ) {
                    this
                } else {
                    copy(crossPeriodMode = action.mode, plan = null, errorMessage = null)
                }
            }

            RandomNumberPickerAction.Generate -> {
                this
            }
        }
}

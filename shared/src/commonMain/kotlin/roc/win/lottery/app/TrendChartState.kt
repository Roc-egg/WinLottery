package roc.win.lottery.app

import roc.win.lottery.domain.HistoricalDraw
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryTrendArea
import roc.win.lottery.domain.LotteryTrendCalculationResult
import roc.win.lottery.domain.LotteryTrendCalculator
import roc.win.lottery.domain.LotteryTrendSnapshot
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.TrendSampleSize

/** 用户在基本走势图页发起的单一配置操作。 */
sealed interface TrendChartAction {
    /**
     * 修改彩种。
     *
     * @property lotteryType 用户选择的彩种。
     */
    data class ChangeLotteryType(
        val lotteryType: LotteryType,
    ) : TrendChartAction

    /**
     * 修改号码区域。
     *
     * @property area 用户选择的号码区域。
     */
    data class ChangeArea(
        val area: LotteryTrendArea,
    ) : TrendChartAction

    /**
     * 修改最近开奖样本范围。
     *
     * @property sampleSize 用户选择的冻结样本范围。
     */
    data class ChangeSampleSize(
        val sampleSize: TrendSampleSize,
    ) : TrendChartAction
}

/**
 * 当前应用会话中的基本走势图状态。
 *
 * @property lotteryType 当前彩种。
 * @property area 当前号码区域。
 * @property sampleSize 当前样本范围。
 * @property snapshot 经过领域校验和计算的可绘制快照。
 * @property isDemonstration 是否为明确标记的受控演示快照。
 */
data class TrendChartState(
    val lotteryType: LotteryType,
    val area: LotteryTrendArea,
    val sampleSize: TrendSampleSize,
    val snapshot: LotteryTrendSnapshot,
    val isDemonstration: Boolean,
) {
    /** 应用一项配置操作，并重新生成口径一致的走势快照。 */
    fun apply(action: TrendChartAction): TrendChartState {
        val targetLotteryType =
            (action as? TrendChartAction.ChangeLotteryType)?.lotteryType ?: lotteryType
        val targetArea = (action as? TrendChartAction.ChangeArea)?.area ?: area
        val targetSampleSize =
            (action as? TrendChartAction.ChangeSampleSize)?.sampleSize ?: sampleSize
        if (
            targetLotteryType == lotteryType &&
            targetArea == area &&
            targetSampleSize == sampleSize
        ) {
            return this
        }
        return create(targetLotteryType, targetArea, targetSampleSize)
    }

    /** 创建当前阶段只使用受控演示快照的初始状态。 */
    companion object {
        /**
         * 创建指定配置的演示走势图状态。
         *
         * @param lotteryType 初始彩种。
         * @param area 初始号码区域。
         * @param sampleSize 初始样本范围。
         * @return 已通过领域计算器校验的演示状态。
         */
        fun create(
            lotteryType: LotteryType = LotteryType.SUPER_LOTTO,
            area: LotteryTrendArea = LotteryTrendArea.PRIMARY,
            sampleSize: TrendSampleSize = TrendSampleSize.LAST_30,
        ): TrendChartState {
            val result =
                TREND_CALCULATOR.calculate(
                    lotteryType = lotteryType,
                    area = area,
                    sampleSize = sampleSize,
                    draws = TrendChartDemoData.draws(lotteryType),
                )
            val snapshot =
                checkNotNull((result as? LotteryTrendCalculationResult.Success)?.snapshot) {
                    "受控走势演示快照未通过领域校验"
                }
            return TrendChartState(
                lotteryType = lotteryType,
                area = area,
                sampleSize = sampleSize,
                snapshot = snapshot,
                isDemonstration = true,
            )
        }

        /** 共享走势领域计算器。 */
        private val TREND_CALCULATOR = LotteryTrendCalculator()
    }
}

/** 只用于 V1.3 首批跨平台界面验收的确定性演示数据。 */
private object TrendChartDemoData {
    /** 返回指定彩种完整 50 期演示快照。 */
    fun draws(lotteryType: LotteryType): List<HistoricalDraw> =
        when (lotteryType) {
            LotteryType.SUPER_LOTTO -> superLottoDraws
            LotteryType.DOUBLE_COLOR_BALL -> doubleColorBallDraws
        }

    /** 大乐透 50 期演示快照。 */
    private val superLottoDraws: List<HistoricalDraw> =
        (1..DEMO_DRAW_COUNT).map { ordinal ->
            HistoricalDraw(
                lotteryType = LotteryType.SUPER_LOTTO,
                issue = Issue(ordinal.toString().padStart(SUPER_LOTTO_ISSUE_LENGTH, '0')),
                drawDate = DEMO_DRAW_DATE,
                primaryNumbers =
                    generatedNumbers(
                        rangeSize = SUPER_LOTTO_PRIMARY_RANGE_SIZE,
                        count = SUPER_LOTTO_PRIMARY_COUNT,
                        seed = ordinal * 7,
                        step = 6,
                    ),
                secondaryNumbers =
                    generatedNumbers(
                        rangeSize = SUPER_LOTTO_SECONDARY_RANGE_SIZE,
                        count = SUPER_LOTTO_SECONDARY_COUNT,
                        seed = ordinal * 3,
                        step = 5,
                    ),
            )
        }

    /** 双色球 50 期演示快照。 */
    private val doubleColorBallDraws: List<HistoricalDraw> =
        (1..DEMO_DRAW_COUNT).map { ordinal ->
            HistoricalDraw(
                lotteryType = LotteryType.DOUBLE_COLOR_BALL,
                issue = Issue(ordinal.toString().padStart(DOUBLE_COLOR_BALL_ISSUE_LENGTH, '0')),
                drawDate = DEMO_DRAW_DATE,
                primaryNumbers =
                    generatedNumbers(
                        rangeSize = DOUBLE_COLOR_BALL_PRIMARY_RANGE_SIZE,
                        count = DOUBLE_COLOR_BALL_PRIMARY_COUNT,
                        seed = ordinal * 7,
                        step = 5,
                    ),
                secondaryNumbers =
                    generatedNumbers(
                        rangeSize = DOUBLE_COLOR_BALL_SECONDARY_RANGE_SIZE,
                        count = DOUBLE_COLOR_BALL_SECONDARY_COUNT,
                        seed = ordinal * 7,
                        step = 1,
                    ),
            )
        }

    /** 使用确定性循环步长生成合法、唯一并升序的号码区域。 */
    private fun generatedNumbers(
        rangeSize: Int,
        count: Int,
        seed: Int,
        step: Int,
    ): List<Int> = (0 until count).map { index -> ((seed + index * step) % rangeSize) + 1 }.sorted()

    /** 演示快照常量。 */
    private const val DEMO_DRAW_COUNT = 50

    /** 演示快照使用的占位日期。 */
    private const val DEMO_DRAW_DATE = "2000-01-01"

    /** 大乐透演示期号长度。 */
    private const val SUPER_LOTTO_ISSUE_LENGTH = 5

    /** 双色球演示期号长度。 */
    private const val DOUBLE_COLOR_BALL_ISSUE_LENGTH = 7

    /** 大乐透前区号码范围大小。 */
    private const val SUPER_LOTTO_PRIMARY_RANGE_SIZE = 35

    /** 大乐透前区每期号码数量。 */
    private const val SUPER_LOTTO_PRIMARY_COUNT = 5

    /** 大乐透后区号码范围大小。 */
    private const val SUPER_LOTTO_SECONDARY_RANGE_SIZE = 12

    /** 大乐透后区每期号码数量。 */
    private const val SUPER_LOTTO_SECONDARY_COUNT = 2

    /** 双色球红球号码范围大小。 */
    private const val DOUBLE_COLOR_BALL_PRIMARY_RANGE_SIZE = 33

    /** 双色球红球每期号码数量。 */
    private const val DOUBLE_COLOR_BALL_PRIMARY_COUNT = 6

    /** 双色球蓝球号码范围大小。 */
    private const val DOUBLE_COLOR_BALL_SECONDARY_RANGE_SIZE = 16

    /** 双色球蓝球每期号码数量。 */
    private const val DOUBLE_COLOR_BALL_SECONDARY_COUNT = 1
}

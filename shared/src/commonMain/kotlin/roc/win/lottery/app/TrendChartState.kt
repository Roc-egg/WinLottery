package roc.win.lottery.app

import roc.win.lottery.domain.LotteryPredictionAnalysis
import roc.win.lottery.domain.LotteryTrendArea
import roc.win.lottery.domain.LotteryTrendSnapshot
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.TrendSampleSize

/** 走势一级页面内部的两个共享视图。 */
enum class TrendWorkspaceView {
    /** 官方常见基本走势矩阵。 */
    BASIC_TREND,

    /** 固定策略候选与时间前推回测。 */
    MATHEMATICAL_RESEARCH,
}

/** 用户在走势与数学研究页发起的单一操作。 */
sealed interface TrendChartAction {
    /**
     * 修改走势工作区视图。
     *
     * @property view 用户选择的基本走势或数学研究。
     */
    data class ChangeView(
        val view: TrendWorkspaceView,
    ) : TrendChartAction

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
     * @property sampleSize 用户选择的真实开奖样本范围。
     */
    data class ChangeSampleSize(
        val sampleSize: TrendSampleSize,
    ) : TrendChartAction

    /** 用户明确重试当前彩种的官方历史开奖加载。 */
    data object Retry : TrendChartAction
}

/** 走势图真实历史开奖加载状态。 */
sealed interface TrendChartContent {
    /** 正在等待当前用户操作触发的官网响应。 */
    data object Loading : TrendChartContent

    /**
     * 已根据真实官方历史开奖生成快照。
     *
     * @property snapshot 经过领域校验和计算的可绘制快照。
     * @property sourceName 官方数据来源名称。
     * @property fetchedAtEpochMillis 当前会话取得这批数据的时间。
     */
    data class Ready(
        val snapshot: LotteryTrendSnapshot,
        val sourceName: String,
        val fetchedAtEpochMillis: Long,
    ) : TrendChartContent

    /**
     * 当前无法形成完整真实样本。
     *
     * @property message 不包含官网原始响应的恢复说明。
     */
    data class Failed(
        val message: String,
    ) : TrendChartContent
}

/** 当前彩种的数学研究状态。 */
sealed interface TrendResearchContent {
    /** 正在等待官方历史开奖或共享领域计算。 */
    data object Loading : TrendResearchContent

    /**
     * 已形成候选号码和时间前推回测。
     *
     * @property analysis 共享领域引擎生成的完整研究结果。
     */
    data class Ready(
        val analysis: LotteryPredictionAnalysis,
    ) : TrendResearchContent

    /**
     * 当前历史数据不能形成数学研究。
     *
     * @property message 不包含官网原始响应的恢复说明。
     */
    data class Failed(
        val message: String,
    ) : TrendResearchContent
}

/**
 * 当前应用会话中的走势与数学研究状态。
 *
 * @property view 当前基本走势或数学研究视图。
 * @property lotteryType 当前彩种。
 * @property area 当前号码区域。
 * @property sampleSize 当前样本范围。
 * @property content 当前真实历史开奖加载状态。
 * @property researchContent 当前数学研究计算状态。
 */
data class TrendChartState(
    val view: TrendWorkspaceView,
    val lotteryType: LotteryType,
    val area: LotteryTrendArea,
    val sampleSize: TrendSampleSize,
    val content: TrendChartContent,
    val researchContent: TrendResearchContent,
) {
    /** 加载成功时返回可绘制快照，否则返回 `null`。 */
    val snapshot: LotteryTrendSnapshot?
        get() = (content as? TrendChartContent.Ready)?.snapshot

    /** 创建尚未取得官方历史开奖的默认配置。 */
    companion object {
        /**
         * 创建默认加载状态。
         *
         * @return 大乐透前区最近 50 期配置，不包含任何演示开奖。
         */
        fun create(): TrendChartState =
            TrendChartState(
                view = TrendWorkspaceView.BASIC_TREND,
                lotteryType = LotteryType.SUPER_LOTTO,
                area = LotteryTrendArea.PRIMARY,
                sampleSize = TrendSampleSize.LAST_50,
                content = TrendChartContent.Loading,
                researchContent = TrendResearchContent.Loading,
            )
    }
}

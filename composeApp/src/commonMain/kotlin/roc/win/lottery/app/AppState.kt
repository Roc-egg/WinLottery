package roc.win.lottery.app

import roc.win.lottery.domain.ConfirmedTicket
import roc.win.lottery.domain.DrawResult
import roc.win.lottery.recognition.ImageRef
import roc.win.lottery.recognition.TicketFieldRegion

/** 应用顶层页面。 */
sealed interface AppScreen {
    /** 首页。 */
    data object Home : AppScreen

    /**
     * 本地分析进度页。
     *
     * @property title 当前处理阶段。
     * @property detail 不包含票面敏感内容的阶段说明。
     * @property progress 当前进度，范围为 `0.0..1.0`。
     */
    data class Analysis(
        val title: String,
        val detail: String,
        val progress: Float,
    ) : AppScreen

    /**
     * 票面人工校正与确认页。
     *
     * @property editor 当前不可变编辑状态。
     * @property imageRef 当前流程的临时图片引用，用于原图区域对照。
     * @property evaluation 当前编辑状态的领域评估。
     * @property fieldRegions 可在原图中定位的 OCR 字段区域。
     */
    data class Review(
        val editor: TicketReviewState,
        val imageRef: ImageRef,
        val evaluation: TicketReviewEvaluation,
        val fieldRegions: List<TicketFieldRegion>,
    ) : AppScreen

    /**
     * 开奖查询状态页。
     *
     * @property ticket 已通过领域校验的用户确认票据。
     */
    data class DrawQuery(
        val ticket: ConfirmedTicket,
    ) : AppScreen

    /**
     * B1 演示完成页。
     *
     * @property drawResult Fake 返回的演示开奖数据。
     */
    data class DemoComplete(
        val drawResult: DrawResult,
    ) : AppScreen

    /**
     * 可恢复错误页。
     *
     * @property title 错误标题。
     * @property message 不包含敏感数据的恢复说明。
     */
    data class Error(
        val title: String,
        val message: String,
    ) : AppScreen

    /** 关于与隐私页。 */
    data object About : AppScreen
}

/**
 * 应用顶层 UI 状态。
 *
 * @property screen 当前页面。
 * @property isDemo 是否使用 B1 Fake 能力。
 */
data class AppUiState(
    val screen: AppScreen = AppScreen.Home,
    val isDemo: Boolean = true,
)

package roc.win.lottery.app

import roc.win.lottery.domain.ConfirmedTicket
import roc.win.lottery.domain.DrawResult
import roc.win.lottery.domain.DrawStatus
import roc.win.lottery.domain.PrizeCheckResult
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
     * @property imageRef OCR 流程的临时图片引用，手动录入时为 `null`。
     * @property evaluation 当前编辑状态的领域评估。
     * @property fieldRegions 可在原图中定位的 OCR 字段区域。
     * @property manualEntryReason OCR 无法安全形成草稿时，保留原图并转为空白手动录入的原因。
     */
    data class Review(
        val editor: TicketReviewState,
        val imageRef: ImageRef?,
        val evaluation: TicketReviewEvaluation,
        val fieldRegions: List<TicketFieldRegion>,
        val manualEntryReason: String? = null,
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
     * 已取得足够官网证据后的真实中奖测算页。
     *
     * @property ticket 用户确认且用于精确查询的票据。
     * @property drawResult 经双数据面核对的开奖结果。
     * @property prizeCheckResult 本地规则引擎生成的逐注测算结果。
     */
    data class VerificationResult(
        val ticket: ConfirmedTicket,
        val drawResult: DrawResult,
        val prizeCheckResult: PrizeCheckResult,
    ) : AppScreen

    /**
     * 官网证据暂不足时保留票据的可重试页面。
     *
     * @property ticket 用户确认且用于精确查询的票据。
     * @property status 本次查询的明确不可用状态。
     * @property message 仓库返回的安全恢复说明。
     */
    data class DrawUnavailable(
        val ticket: ConfirmedTicket,
        val status: DrawStatus,
        val message: String,
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

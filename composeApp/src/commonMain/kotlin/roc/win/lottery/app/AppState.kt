package roc.win.lottery.app

import roc.win.lottery.domain.ConfirmedTicket
import roc.win.lottery.domain.DrawResult
import roc.win.lottery.domain.DrawStatus
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.PrizeCheckResult
import roc.win.lottery.domain.PrizeCheckStatus
import roc.win.lottery.recognition.ImageRef
import roc.win.lottery.recognition.TicketFieldCandidate
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
     * @property fieldCandidates OCR 无法唯一确定、需要用户选择的字段候选。
     * @property ocrEngineName 当前字段所来自的 OCR 引擎；手动录入时为 `null`。
     * @property manualEntryReason OCR 无法安全形成草稿时，保留原图并转为空白手动录入的原因。
     */
    data class Review(
        val editor: TicketReviewState,
        val imageRef: ImageRef?,
        val evaluation: TicketReviewEvaluation,
        val fieldRegions: List<TicketFieldRegion>,
        val fieldCandidates: List<TicketFieldCandidate> = emptyList(),
        val ocrEngineName: String? = null,
        val manualEntryReason: String? = null,
    ) : AppScreen

    /**
     * 开奖查询状态页。
     *
     * @property ticket 已通过领域校验的用户确认票据。
     * @property currentIssue 当前正在查询的精确期号。
     * @property completedPeriodCount 本轮已经处理的期次数量。
     * @property totalPeriodCount 本轮需要处理的期次数量。
     */
    data class DrawQuery(
        val ticket: ConfirmedTicket,
        val currentIssue: Issue = ticket.issue.value,
        val completedPeriodCount: Int = 0,
        val totalPeriodCount: Int = 1,
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
     * 多期票逐期开奖核对结果页。
     *
     * @property ticket 用户确认的多期票据。
     * @property periodResults 按票面起始期号顺序保存的逐期结果。
     * @property retryNotice 本次重查未完成但已保留既有验证结果时的恢复提示。
     */
    data class MultiPeriodVerificationResult(
        val ticket: ConfirmedTicket,
        val periodResults: List<PeriodVerification>,
        val retryNotice: MultiPeriodRetryNotice? = null,
    ) : AppScreen {
        /** 已取得足够官方证据并执行本地规则计算的期次数量。 */
        val verifiedPeriodCount: Int
            get() = periodResults.count { it is PeriodVerification.Verified }

        /** 尚未取得足够证据、规则结论或完整中奖金额的期次数量。 */
        val unresolvedPeriodCount: Int
            get() = periodResults.count { it.requiresRetry }

        /** 是否至少有一个已验证期次命中奖级。 */
        val hasWinningPeriod: Boolean
            get() =
                periodResults.any { result ->
                    result is PeriodVerification.Verified && result.prizeCheckResult.status == PrizeCheckStatus.WIN
                }

        /** 所有期次是否都已形成中奖或未中奖结论。 */
        val isConclusive: Boolean
            get() =
                periodResults.size == ticket.periodCount.value &&
                    periodResults.all { result ->
                        result is PeriodVerification.Verified &&
                            result.prizeCheckResult.status in CONCLUSIVE_PRIZE_STATUSES
                    }

        /**
         * 全部期次金额完整时的整票税前奖金合计。
         *
         * 任一期证据或奖金缺失，以及安全加法溢出时均返回 `null`。
         */
        val estimatedPrizeFen: Long?
            get() {
                if (!isConclusive) return null
                var total = 0L
                for (periodResult in periodResults) {
                    val verified = periodResult as PeriodVerification.Verified
                    val amount = verified.prizeCheckResult.estimatedPrizeFen ?: return null
                    if (amount < 0L || total > Long.MAX_VALUE - amount) return null
                    total += amount
                }
                return total
            }

        /** 可形成逐期最终结论的本地测算状态。 */
        private companion object {
            /** 中奖和未中奖均属于证据充分后的明确结论。 */
            val CONCLUSIVE_PRIZE_STATUSES = setOf(PrizeCheckStatus.WIN, PrizeCheckStatus.NO_WIN)
        }
    }

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

/** 一张多期票中单个开奖期次的核对状态。 */
sealed interface PeriodVerification {
    /** 当前条目对应的精确期号。 */
    val issue: Issue

    /** 本期是否仍需主动重查开奖证据或奖金数据。 */
    val requiresRetry: Boolean
        get() =
            when (this) {
                is Unavailable -> {
                    true
                }

                is Verified -> {
                    (
                        prizeCheckResult.status != PrizeCheckStatus.WIN &&
                            prizeCheckResult.status != PrizeCheckStatus.NO_WIN
                    ) ||
                        (
                            prizeCheckResult.status == PrizeCheckStatus.WIN &&
                                prizeCheckResult.estimatedPrizeFen == null
                        )
                }
            }

    /**
     * 已取得双官方证据并执行本地规则计算。
     *
     * @property issue 当前精确期号。
     * @property drawResult 经交叉核对的统一开奖结果。
     * @property prizeCheckResult 本期逐注测算结果。
     */
    data class Verified(
        override val issue: Issue,
        val drawResult: DrawResult,
        val prizeCheckResult: PrizeCheckResult,
    ) : PeriodVerification

    /**
     * 本期证据不足或本轮尚未发起查询。
     *
     * @property issue 当前精确期号。
     * @property status 明确的不可用状态。
     * @property message 不包含票面敏感内容的恢复说明。
     * @property wasQueried 本轮是否实际向官网发起过该期查询。
     */
    data class Unavailable(
        override val issue: Issue,
        val status: DrawStatus,
        val message: String,
        val wasQueried: Boolean,
    ) : PeriodVerification
}

/**
 * 多期主动重查未能取得新证据时的非破坏性提示。
 *
 * @property status 本次重查遇到的瞬时不可用状态。
 * @property message 开奖仓库返回的不含票面敏感信息的说明。
 */
data class MultiPeriodRetryNotice(
    val status: DrawStatus,
    val message: String,
)

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

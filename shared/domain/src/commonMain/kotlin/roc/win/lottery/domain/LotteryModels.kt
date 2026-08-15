package roc.win.lottery.domain

/** 彩票发行玩法。 */
enum class LotteryType {
    /** 中国体育彩票超级大乐透。 */
    SUPER_LOTTO,

    /** 中国福利彩票双色球。 */
    DOUBLE_COLOR_BALL,
}

/** 字段在确认前的来源。 */
enum class TicketFieldOrigin {
    /** 字段由本地 OCR 识别得到。 */
    OCR,

    /** 字段由用户手工修改。 */
    USER,

    /** 字段根据票面金额等已知信息推导得到。 */
    DERIVED,
}

/**
 * 带来源的已确认字段。
 *
 * @property value 已确认且不可变的字段值。
 * @property origin 字段在确认前的来源。
 */
data class ConfirmedValue<T>(
    val value: T,
    val origin: TicketFieldOrigin,
)

/**
 * 彩票期号。
 *
 * @property value 保留前导零的期号文本。
 */
data class Issue(
    val value: String,
)

/**
 * 一行 V1 单式投注。
 *
 * @property primaryNumbers 前区或红球号码，按升序保存。
 * @property secondaryNumbers 后区或蓝球号码，按升序保存。
 * @property isAdditional 大乐透是否追加，双色球必须为 `false`。
 * @property originalText OCR 返回的原始行文本，仅用于确认界面对照。
 */
data class BetLine(
    val primaryNumbers: ConfirmedValue<List<Int>>,
    val secondaryNumbers: ConfirmedValue<List<Int>>,
    val isAdditional: ConfirmedValue<Boolean>,
    val originalText: String,
)

/**
 * 尚未经过用户确认的一行识别结果。
 *
 * @property primaryNumbers 识别得到的前区或红球号码。
 * @property secondaryNumbers 识别得到的后区或蓝球号码。
 * @property isAdditional 大乐透追加属性，无法识别时为 `null`。
 * @property originalText OCR 返回的原始行文本。
 */
data class BetLineDraft(
    val primaryNumbers: List<Int>,
    val secondaryNumbers: List<Int>,
    val isAdditional: Boolean?,
    val originalText: String,
)

/**
 * 等待用户核对的票面草稿。
 *
 * @property lotteryType 识别到的彩种，无法确定时为 `null`。
 * @property issue 识别到的期号原文。
 * @property betLines 识别到的单式投注行。
 * @property multiplier 识别到的倍数，无法确定时为 `null`。
 * @property periodCount 识别到的期数，无法确定时为 `null`。
 * @property paidAmountFen 识别到的支付金额，无法确定时为 `null`。
 */
data class TicketDraft(
    val lotteryType: LotteryType?,
    val issue: String,
    val betLines: List<BetLineDraft>,
    val multiplier: Int?,
    val periodCount: Int?,
    val paidAmountFen: Long?,
)

/**
 * 用户已确认的不可变彩票。
 *
 * @property lotteryType 已确认的彩种。
 * @property issue 已确认的期号。
 * @property betLines 保留票面顺序的单式投注行，重复行不得去重。
 * @property multiplier 已确认的投注倍数。
 * @property periodCount 已确认的连续投注期数，移动首版接受 1 至 20。
 * @property paidAmountFen 票面实际支付金额，单位为分。
 */
data class ConfirmedTicket(
    val lotteryType: ConfirmedValue<LotteryType>,
    val issue: ConfirmedValue<Issue>,
    val betLines: List<BetLine>,
    val multiplier: ConfirmedValue<Int>,
    val periodCount: ConfirmedValue<Int>,
    val paidAmountFen: ConfirmedValue<Long>,
)

/** 领域校验状态。 */
enum class TicketValidationStatus {
    /** 所有字段满足 V1 领域约束。 */
    VALID,

    /** 存在多个合法解释，需要用户选择。 */
    AMBIGUOUS,

    /** 票型可识别，但不属于 V1 支持范围。 */
    UNSUPPORTED,

    /** 字段自身不合法或互相矛盾。 */
    INVALID,
}

/**
 * 单条领域校验问题。
 *
 * @property field 对应的稳定字段标识。
 * @property message 面向用户的简体中文说明。
 */
data class TicketValidationProblem(
    val field: String,
    val message: String,
)

/**
 * 彩票领域校验结果。
 *
 * @property status 汇总后的校验状态。
 * @property problems 按发现顺序排列的问题列表。
 */
data class TicketValidationResult(
    val status: TicketValidationStatus,
    val problems: List<TicketValidationProblem>,
) {
    /** 只有完全合法的彩票才允许进入规则引擎。 */
    val canCalculate: Boolean
        get() = status == TicketValidationStatus.VALID
}

package roc.win.lottery.app

import roc.win.lottery.domain.BetLine
import roc.win.lottery.domain.ConfirmedTicket
import roc.win.lottery.domain.ConfirmedValue
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.TicketAmountCalculator
import roc.win.lottery.domain.TicketDraft
import roc.win.lottery.domain.TicketFieldOrigin
import roc.win.lottery.domain.TicketValidationProblem
import roc.win.lottery.domain.TicketValidator

/**
 * 保存一个仍可编辑字段的值和来源。
 *
 * @property value 当前编辑值。
 * @property origin 当前值来自 OCR、用户修改或金额推导。
 */
data class TicketReviewField<T>(
    val value: T,
    val origin: TicketFieldOrigin,
)

/** 号码球所在的投注区域。 */
enum class TicketNumberArea {
    /** 大乐透前区或双色球红球。 */
    PRIMARY,

    /** 大乐透后区或双色球蓝球。 */
    SECONDARY,
}

/** 用户在票面校正页发起的单一编辑动作。 */
sealed interface TicketReviewAction {
    /**
     * 选择彩票玩法。
     *
     * @property lotteryType 用户选择的玩法。
     */
    data class ChangeLotteryType(
        val lotteryType: LotteryType,
    ) : TicketReviewAction

    /**
     * 修改开奖期号。
     *
     * @property value 用户输入的纯数字期号。
     */
    data class ChangeIssue(
        val value: String,
    ) : TicketReviewAction

    /**
     * 切换一个号码球的选中状态。
     *
     * @property lineIndex 投注行下标。
     * @property area 号码区域。
     * @property number 待切换的号码。
     */
    data class ToggleNumber(
        val lineIndex: Int,
        val area: TicketNumberArea,
        val number: Int,
    ) : TicketReviewAction

    /**
     * 修改投注倍数。
     *
     * @property value 用户选择的 1 至 99 倍。
     */
    data class ChangeMultiplier(
        val value: Int,
    ) : TicketReviewAction

    /**
     * 修改整张大乐透彩票的追加属性。
     *
     * @property value 是否追加投注。
     */
    data class ChangeAdditional(
        val value: Boolean,
    ) : TicketReviewAction

    /**
     * 修改票面合计金额。
     *
     * @property value 以元为单位的十进制输入。
     */
    data class ChangePaidAmount(
        val value: String,
    ) : TicketReviewAction

    /** 使用当前投注结构推导出的金额覆盖金额输入。 */
    data object UseCalculatedAmount : TicketReviewAction
}

/**
 * 一行等待用户校正的单式投注。
 *
 * @property primaryNumbers 前区或红球号码。
 * @property secondaryNumbers 后区或蓝球号码。
 * @property isAdditional 该行是否为大乐透追加投注，尚待用户确认时值为 `null`。
 * @property originalText OCR 原始行文本。
 */
data class TicketLineReviewState(
    val primaryNumbers: TicketReviewField<List<Int>>,
    val secondaryNumbers: TicketReviewField<List<Int>>,
    val isAdditional: TicketReviewField<Boolean?>,
    val originalText: String,
)

/**
 * 一次票面编辑的领域评估结果。
 *
 * @property ticket 当前字段能够构造出的票据；基础输入不完整时为 `null`。
 * @property problems 当前全部领域问题。
 */
data class TicketReviewEvaluation(
    val ticket: ConfirmedTicket?,
    val problems: List<TicketValidationProblem>,
) {
    /** 只有票据可构造且没有领域问题时才允许确认。 */
    val canConfirm: Boolean
        get() = ticket != null && problems.isEmpty()
}

/**
 * Android 和 iOS 共用的不可变票面人工校正状态。
 *
 * @property lotteryType 当前玩法。
 * @property issue 当前期号输入。
 * @property betLines 保留票面顺序的投注行。
 * @property multiplier 当前投注倍数，尚待用户确认时值为 `null`。
 * @property periodCount 当前投注期数，V1 固定为 1。
 * @property paidAmountYuan 当前票面金额输入，单位为元。
 */
data class TicketReviewState(
    val lotteryType: TicketReviewField<LotteryType?>,
    val issue: TicketReviewField<String>,
    val betLines: List<TicketLineReviewState>,
    val multiplier: TicketReviewField<Int?>,
    val periodCount: TicketReviewField<Int>,
    val paidAmountYuan: TicketReviewField<String>,
) {
    /** 当前全票追加属性；尚未确认或各行不一致时为 `null`。 */
    val isAdditional: Boolean?
        get() = betLines.map { it.isAdditional.value }.toSet().singleOrNull()

    /** 根据当前投注结构计算出的理论金额，单位为分。 */
    val calculatedAmountFen: Long?
        get() {
            val selectedLotteryType = lotteryType.value ?: return null
            val selectedMultiplier = multiplier.value ?: return null
            val additionalValues = betLines.map { it.isAdditional.value }
            if (additionalValues.any { it == null }) return null
            return TicketAmountCalculator.calculate(
                lotteryType = selectedLotteryType,
                betLineCount = betLines.size,
                additionalLineCount = additionalValues.count { it == true },
                multiplier = selectedMultiplier,
                periodCount = periodCount.value,
            )
        }

    /**
     * 应用一个用户编辑动作并返回新状态。
     *
     * 非法字符、越界倍数和不存在的投注行会被忽略，最终领域合法性仍由 `TicketValidator` 判断。
     */
    fun applyAction(action: TicketReviewAction): TicketReviewState =
        when (action) {
            is TicketReviewAction.ChangeLotteryType -> changeLotteryType(action.lotteryType)
            is TicketReviewAction.ChangeIssue -> changeIssue(action.value)
            is TicketReviewAction.ToggleNumber -> toggleNumber(action)
            is TicketReviewAction.ChangeMultiplier -> changeMultiplier(action.value)
            is TicketReviewAction.ChangeAdditional -> changeAdditional(action.value)
            is TicketReviewAction.ChangePaidAmount -> changePaidAmount(action.value)
            TicketReviewAction.UseCalculatedAmount -> useCalculatedAmount()
        }

    /** 使用当前校正状态构造票据并执行完整领域校验。 */
    fun evaluate(validator: TicketValidator): TicketReviewEvaluation =
        when (val result = buildTicket()) {
            is TicketBuildResult.Invalid -> {
                TicketReviewEvaluation(null, result.problems)
            }

            is TicketBuildResult.Ready -> {
                val validation = validator.validate(result.ticket)
                TicketReviewEvaluation(result.ticket, validation.problems)
            }
        }

    /** 修改玩法，并在切换为双色球时清除不适用的追加属性。 */
    private fun changeLotteryType(value: LotteryType): TicketReviewState {
        if (lotteryType.value == value) return this
        val updatedLines =
            when {
                value == LotteryType.DOUBLE_COLOR_BALL -> {
                    betLines.map { line ->
                        line.copy(isAdditional = TicketReviewField(false, TicketFieldOrigin.DERIVED))
                    }
                }

                lotteryType.value == LotteryType.DOUBLE_COLOR_BALL -> {
                    betLines.map { line ->
                        line.copy(isAdditional = TicketReviewField(null, TicketFieldOrigin.DERIVED))
                    }
                }

                else -> {
                    betLines
                }
            }
        return copy(
            lotteryType = TicketReviewField(value, TicketFieldOrigin.USER),
            betLines = updatedLines,
        )
    }

    /** 接受最多七位的纯数字期号输入。 */
    private fun changeIssue(value: String): TicketReviewState {
        if (value.length > MAX_ISSUE_INPUT_LENGTH || value.any { !it.isDigit() }) return this
        return copy(issue = issue.withUserValue(value))
    }

    /** 切换指定投注行中的号码。 */
    private fun toggleNumber(action: TicketReviewAction.ToggleNumber): TicketReviewState {
        val line = betLines.getOrNull(action.lineIndex) ?: return this
        val globalRange =
            when (action.area) {
                TicketNumberArea.PRIMARY -> PRIMARY_GLOBAL_RANGE
                TicketNumberArea.SECONDARY -> SECONDARY_GLOBAL_RANGE
            }
        if (action.number !in globalRange) return this

        val updatedLine =
            when (action.area) {
                TicketNumberArea.PRIMARY -> {
                    line.copy(primaryNumbers = line.primaryNumbers.toggleUserNumber(action.number))
                }

                TicketNumberArea.SECONDARY -> {
                    line.copy(secondaryNumbers = line.secondaryNumbers.toggleUserNumber(action.number))
                }
            }
        return copy(betLines = betLines.replaceAt(action.lineIndex, updatedLine))
    }

    /** 接受 V1 范围内的投注倍数。 */
    private fun changeMultiplier(value: Int): TicketReviewState {
        if (value !in VALID_MULTIPLIER_RANGE) return this
        return copy(multiplier = multiplier.withUserValue(value))
    }

    /** 修改大乐透追加属性，并同步到每一行单式投注。 */
    private fun changeAdditional(value: Boolean): TicketReviewState {
        if (lotteryType.value != LotteryType.SUPER_LOTTO) return this
        return copy(
            betLines =
                betLines.map { line ->
                    line.copy(isAdditional = line.isAdditional.withUserValue(value))
                },
        )
    }

    /** 接受可继续输入的非负元金额。 */
    private fun changePaidAmount(value: String): TicketReviewState {
        if (!value.isPartialYuanInput()) return this
        return copy(paidAmountYuan = paidAmountYuan.withUserValue(value))
    }

    /** 用当前理论金额更新金额字段，并明确标记为推导值。 */
    private fun useCalculatedAmount(): TicketReviewState {
        val amountFen = calculatedAmountFen ?: return this
        return copy(
            paidAmountYuan = TicketReviewField(amountFen.toYuanInput(), TicketFieldOrigin.DERIVED),
        )
    }

    /** 尝试把编辑状态转换为不可变票据。 */
    private fun buildTicket(): TicketBuildResult {
        val problems = mutableListOf<TicketValidationProblem>()
        val selectedLotteryType =
            lotteryType.value ?: run {
                problems += TicketValidationProblem("lotteryType", "请选择彩票种类")
                null
            }
        val paidAmountFen =
            paidAmountYuan.value.toFenOrNull() ?: run {
                problems += TicketValidationProblem("paidAmountFen", "票面金额必须是最多两位小数的非负金额")
                null
            }
        val selectedMultiplier =
            multiplier.value ?: run {
                problems += TicketValidationProblem("multiplier", "请明确确认投注倍数")
                null
            }
        if (betLines.any { it.isAdditional.value == null }) {
            problems += TicketValidationProblem("isAdditional", "请选择基本投注或追加投注")
        }
        if (
            selectedLotteryType == null ||
            paidAmountFen == null ||
            selectedMultiplier == null ||
            betLines.any { it.isAdditional.value == null }
        ) {
            return TicketBuildResult.Invalid(problems)
        }

        return TicketBuildResult.Ready(
            ConfirmedTicket(
                lotteryType = ConfirmedValue(selectedLotteryType, lotteryType.origin),
                issue = ConfirmedValue(Issue(issue.value), issue.origin),
                betLines =
                    betLines.map { line ->
                        BetLine(
                            primaryNumbers =
                                ConfirmedValue(
                                    line.primaryNumbers.value.sorted(),
                                    line.primaryNumbers.origin,
                                ),
                            secondaryNumbers =
                                ConfirmedValue(
                                    line.secondaryNumbers.value.sorted(),
                                    line.secondaryNumbers.origin,
                                ),
                            isAdditional =
                                ConfirmedValue(
                                    checkNotNull(line.isAdditional.value),
                                    line.isAdditional.origin,
                                ),
                            originalText = line.originalText,
                        )
                    },
                multiplier = ConfirmedValue(selectedMultiplier, multiplier.origin),
                periodCount = ConfirmedValue(periodCount.value, periodCount.origin),
                paidAmountFen = ConfirmedValue(paidAmountFen, paidAmountYuan.origin),
            ),
        )
    }

    /** 从保守解析器输出的草稿创建初始校正状态。 */
    companion object {
        /**
         * 把 OCR 草稿映射为可编辑字段；未知倍数和追加属性保持为空，V1 固定期数使用推导来源。
         *
         * @param draft 等待人工核对的 OCR 草稿。
         */
        fun fromDraft(draft: TicketDraft): TicketReviewState =
            TicketReviewState(
                lotteryType = TicketReviewField(draft.lotteryType, TicketFieldOrigin.OCR),
                issue = TicketReviewField(draft.issue, TicketFieldOrigin.OCR),
                betLines =
                    draft.betLines.map { line ->
                        TicketLineReviewState(
                            primaryNumbers = TicketReviewField(line.primaryNumbers.sorted(), TicketFieldOrigin.OCR),
                            secondaryNumbers =
                                TicketReviewField(line.secondaryNumbers.sorted(), TicketFieldOrigin.OCR),
                            isAdditional =
                                if (draft.lotteryType == LotteryType.DOUBLE_COLOR_BALL) {
                                    TicketReviewField(false, TicketFieldOrigin.DERIVED)
                                } else {
                                    TicketReviewField(line.isAdditional, TicketFieldOrigin.OCR)
                                },
                            originalText = line.originalText,
                        )
                    },
                multiplier = TicketReviewField(draft.multiplier, TicketFieldOrigin.OCR),
                periodCount =
                    TicketReviewField(
                        draft.periodCount ?: V1_PERIOD_COUNT,
                        if (draft.periodCount == null) TicketFieldOrigin.DERIVED else TicketFieldOrigin.OCR,
                    ),
                paidAmountYuan =
                    TicketReviewField(
                        draft.paidAmountFen?.toYuanInput().orEmpty(),
                        TicketFieldOrigin.OCR,
                    ),
            )

        /** 期号输入允许的最大位数。 */
        private const val MAX_ISSUE_INPUT_LENGTH = 7

        /** V1 合法投注倍数。 */
        private val VALID_MULTIPLIER_RANGE = 1..99

        /** V1 固定投注期数。 */
        private const val V1_PERIOD_COUNT = 1

        /** 两种彩票主号码的全局可编辑范围。 */
        private val PRIMARY_GLOBAL_RANGE = 1..35

        /** 两种彩票次号码的全局可编辑范围。 */
        private val SECONDARY_GLOBAL_RANGE = 1..16
    }
}

/** 构造不可变票据的内部结果。 */
private sealed interface TicketBuildResult {
    /**
     * 已构造票据。
     *
     * @property ticket 等待领域校验的票据。
     */
    data class Ready(
        val ticket: ConfirmedTicket,
    ) : TicketBuildResult

    /**
     * 基础输入尚不能构造票据。
     *
     * @property problems 阻断构造的问题。
     */
    data class Invalid(
        val problems: List<TicketValidationProblem>,
    ) : TicketBuildResult
}

/** 在值实际变化时标记为用户修改。 */
private fun <T> TicketReviewField<T>.withUserValue(value: T): TicketReviewField<T> =
    if (this.value == value) this else TicketReviewField(value, TicketFieldOrigin.USER)

/** 切换号码并保持升序。 */
private fun TicketReviewField<List<Int>>.toggleUserNumber(number: Int): TicketReviewField<List<Int>> {
    val updated =
        if (number in value) {
            value - number
        } else {
            (value + number).sorted()
        }
    return withUserValue(updated)
}

/** 在指定位置替换列表元素。 */
private fun <T> List<T>.replaceAt(
    index: Int,
    value: T,
): List<T> = mapIndexed { currentIndex, item -> if (currentIndex == index) value else item }

/** 判断文本是否是可继续输入的元金额。 */
private fun String.isPartialYuanInput(): Boolean {
    if (isEmpty()) return true
    if (first() == DECIMAL_POINT || any { !it.isDigit() && it != DECIMAL_POINT }) return false
    val decimalIndex = indexOf(DECIMAL_POINT)
    if (decimalIndex < 0) return true
    if (decimalIndex != lastIndexOf(DECIMAL_POINT)) return false
    return length - decimalIndex - 1 <= MAX_FRACTION_DIGITS
}

/** 把元金额文本精确转换为分。 */
private fun String.toFenOrNull(): Long? {
    if (isEmpty() || !isPartialYuanInput()) return null
    val decimalIndex = indexOf(DECIMAL_POINT)
    val wholeText = if (decimalIndex < 0) this else substring(0, decimalIndex)
    val fractionText = if (decimalIndex < 0) "" else substring(decimalIndex + 1)
    val wholeYuan = wholeText.toLongOrNull() ?: return null
    val fractionFen = fractionText.padEnd(MAX_FRACTION_DIGITS, ASCII_ZERO).toLongOrNull() ?: 0L
    if (wholeYuan > (Long.MAX_VALUE - fractionFen) / FEN_PER_YUAN) return null
    return wholeYuan * FEN_PER_YUAN + fractionFen
}

/** 把分格式化为可继续编辑的元金额。 */
private fun Long.toYuanInput(): String {
    if (this < 0L) return ""
    return "${this / FEN_PER_YUAN}.${(this % FEN_PER_YUAN).toString().padStart(MAX_FRACTION_DIGITS, ASCII_ZERO)}"
}

/** 元与分的换算比例。 */
private const val FEN_PER_YUAN = 100L

/** 金额最多允许两位小数。 */
private const val MAX_FRACTION_DIGITS = 2

/** 小数点字符。 */
private const val DECIMAL_POINT = '.'

/** ASCII 数字零。 */
private const val ASCII_ZERO = '0'

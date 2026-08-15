package roc.win.lottery.domain

/** 收集一条校验问题。 */
private typealias ProblemCollector = (TicketValidationStatus, String, String) -> Unit

/** 校验移动首版已确认彩票的领域约束。 */
class TicketValidator(
    /** 将多期票展开为逐期查询列表的安全能力。 */
    private val issueSequenceResolver: IssueSequenceResolver = IssueSequenceResolver(),
) {
    /**
     * 校验票面字段、号码、倍数和支付金额是否一致。
     *
     * @param ticket 用户已确认的彩票。
     * @return 可供流程层决定是否放行的校验结果。
     */
    fun validate(ticket: ConfirmedTicket): TicketValidationResult {
        val problems = mutableListOf<TicketValidationProblem>()
        var status = TicketValidationStatus.VALID

        fun addProblem(
            problemStatus: TicketValidationStatus,
            field: String,
            message: String,
        ) {
            if (problemStatus.priority > status.priority) {
                status = problemStatus
            }
            problems += TicketValidationProblem(field, message)
        }

        validateIssue(ticket, ::addProblem)

        val sequence =
            issueSequenceResolver.resolve(
                lotteryType = ticket.lotteryType.value,
                firstIssue = ticket.issue.value,
                periodCount = ticket.periodCount.value,
            )
        if (sequence is IssueSequenceResult.Unsupported) {
            val problemStatus =
                if (ticket.periodCount.value < IssueSequenceResolver.MIN_PERIOD_COUNT) {
                    TicketValidationStatus.INVALID
                } else {
                    TicketValidationStatus.UNSUPPORTED
                }
            addProblem(problemStatus, "periodCount", sequence.message)
        }
        if (ticket.multiplier.value !in MIN_MULTIPLIER..MAX_MULTIPLIER) {
            addProblem(TicketValidationStatus.INVALID, "multiplier", "投注倍数必须在 1 至 99 之间")
        }
        if (ticket.betLines.isEmpty()) {
            addProblem(TicketValidationStatus.INVALID, "betLines", "彩票至少需要一行单式投注")
        }

        ticket.betLines.forEachIndexed { index, line ->
            validateLine(ticket.lotteryType.value, index, line, ::addProblem)
        }
        validateAmount(ticket, ::addProblem)

        return TicketValidationResult(status, problems.toList())
    }

    /** 校验期号格式。 */
    private fun validateIssue(
        ticket: ConfirmedTicket,
        addProblem: ProblemCollector,
    ) {
        val expectedLength =
            when (ticket.lotteryType.value) {
                LotteryType.SUPER_LOTTO -> SUPER_LOTTO_ISSUE_LENGTH
                LotteryType.DOUBLE_COLOR_BALL -> DOUBLE_COLOR_BALL_ISSUE_LENGTH
            }
        val value = ticket.issue.value.value
        if (value.length != expectedLength || value.any { !it.isDigit() }) {
            addProblem(TicketValidationStatus.INVALID, "issue", "期号必须是 $expectedLength 位数字")
        }
    }

    /** 校验一行单式号码。 */
    private fun validateLine(
        lotteryType: LotteryType,
        index: Int,
        line: BetLine,
        addProblem: ProblemCollector,
    ) {
        val spec = NumberSpec.forLottery(lotteryType)
        validateNumberArea(
            index,
            "primaryNumbers",
            line.primaryNumbers.value,
            spec.primaryCount,
            spec.primaryRange,
            addProblem,
        )
        validateNumberArea(
            index,
            "secondaryNumbers",
            line.secondaryNumbers.value,
            spec.secondaryCount,
            spec.secondaryRange,
            addProblem,
        )
        if (lotteryType == LotteryType.DOUBLE_COLOR_BALL && line.isAdditional.value) {
            addProblem(TicketValidationStatus.INVALID, "betLines[$index].isAdditional", "双色球不存在追加投注")
        }
    }

    /** 校验一个号码区域的数量、范围和唯一性。 */
    private fun validateNumberArea(
        lineIndex: Int,
        field: String,
        numbers: List<Int>,
        expectedCount: Int,
        allowedRange: IntRange,
        addProblem: ProblemCollector,
    ) {
        val path = "betLines[$lineIndex].$field"
        when {
            numbers.size > expectedCount -> {
                addProblem(TicketValidationStatus.UNSUPPORTED, path, "号码数量超过单式投注范围")
            }

            numbers.size < expectedCount -> {
                addProblem(TicketValidationStatus.INVALID, path, "号码数量不足，单式投注需要 $expectedCount 个号码")
            }
        }
        if (numbers.any { it !in allowedRange }) {
            addProblem(
                TicketValidationStatus.INVALID,
                path,
                "号码必须在 ${allowedRange.first} 至 ${allowedRange.last} 之间",
            )
        }
        if (numbers.distinct().size != numbers.size) {
            addProblem(TicketValidationStatus.INVALID, path, "同一号码区域不能包含重复号码")
        }
    }

    /** 校验投注金额与投注行、倍数和追加属性是否严格一致。 */
    private fun validateAmount(
        ticket: ConfirmedTicket,
        addProblem: ProblemCollector,
    ) {
        if (ticket.paidAmountFen.value < 0L) {
            addProblem(TicketValidationStatus.INVALID, "paidAmountFen", "票面金额不能为负数")
            return
        }
        if (
            ticket.multiplier.value !in MIN_MULTIPLIER..MAX_MULTIPLIER ||
            ticket.periodCount.value !in IssueSequenceResolver.MIN_PERIOD_COUNT..IssueSequenceResolver.MAX_PERIOD_COUNT
        ) {
            return
        }

        val baseAmountFen =
            TicketAmountCalculator.calculate(
                lotteryType = ticket.lotteryType.value,
                betLineCount = ticket.betLines.size,
                additionalLineCount = 0,
                multiplier = ticket.multiplier.value,
                periodCount = ticket.periodCount.value,
            ) ?: run {
                addProblem(TicketValidationStatus.INVALID, "paidAmountFen", "无法根据当前投注结构计算金额")
                return
            }
        val expectedAmountFen =
            TicketAmountCalculator.calculate(
                lotteryType = ticket.lotteryType.value,
                betLineCount = ticket.betLines.size,
                additionalLineCount = ticket.betLines.count { it.isAdditional.value },
                multiplier = ticket.multiplier.value,
                periodCount = ticket.periodCount.value,
            ) ?: run {
                addProblem(TicketValidationStatus.INVALID, "paidAmountFen", "无法根据当前投注结构计算金额")
                return
            }

        val maxAmountFen =
            if (ticket.lotteryType.value == LotteryType.SUPER_LOTTO) {
                SUPER_LOTTO_MAX_TOTAL_FEN
            } else {
                DOUBLE_COLOR_BALL_MAX_TOTAL_FEN
            }
        if (baseAmountFen > MAX_BASE_AMOUNT_FEN || expectedAmountFen > maxAmountFen) {
            addProblem(TicketValidationStatus.INVALID, "paidAmountFen", "投注金额超过单张彩票上限")
        }
        if (ticket.paidAmountFen.value != expectedAmountFen) {
            addProblem(
                TicketValidationStatus.INVALID,
                "paidAmountFen",
                "票面金额与投注行、倍数或追加属性不一致",
            )
        }
    }

    /** 号码区域规则。 */
    private data class NumberSpec(
        /** 主号码数量。 */
        val primaryCount: Int,
        /** 主号码合法范围。 */
        val primaryRange: IntRange,
        /** 次号码数量。 */
        val secondaryCount: Int,
        /** 次号码合法范围。 */
        val secondaryRange: IntRange,
    ) {
        /** 创建指定彩种的号码规则。 */
        companion object {
            /** 返回指定彩种当前 V1 单式号码规则。 */
            fun forLottery(lotteryType: LotteryType): NumberSpec =
                when (lotteryType) {
                    LotteryType.SUPER_LOTTO -> NumberSpec(5, 1..35, 2, 1..12)
                    LotteryType.DOUBLE_COLOR_BALL -> NumberSpec(6, 1..33, 1, 1..16)
                }
        }
    }

    /** 移动首版票据常量。 */
    private companion object {
        /** 最小投注倍数。 */
        const val MIN_MULTIPLIER = 1

        /** 最大投注倍数。 */
        const val MAX_MULTIPLIER = 99

        /** 大乐透期号长度。 */
        const val SUPER_LOTTO_ISSUE_LENGTH = 5

        /** 双色球期号长度。 */
        const val DOUBLE_COLOR_BALL_ISSUE_LENGTH = 7

        /** 单张彩票基本投注金额上限，单位为分。 */
        const val MAX_BASE_AMOUNT_FEN = 2_000_000L

        /** 大乐透含追加投注的总金额上限，单位为分。 */
        const val SUPER_LOTTO_MAX_TOTAL_FEN = 3_000_000L

        /** 双色球总金额上限，单位为分。 */
        const val DOUBLE_COLOR_BALL_MAX_TOTAL_FEN = 2_000_000L

        /** 返回校验状态的严重级别。 */
        val TicketValidationStatus.priority: Int
            get() =
                when (this) {
                    TicketValidationStatus.VALID -> 0
                    TicketValidationStatus.AMBIGUOUS -> 1
                    TicketValidationStatus.UNSUPPORTED -> 2
                    TicketValidationStatus.INVALID -> 3
                }
    }
}

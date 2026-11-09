package roc.win.lottery.domain

/** 收集一条校验问题。 */
private typealias ProblemCollector = (TicketValidationStatus, String, String) -> Unit

/** 校验移动首版已确认彩票的领域约束，单式与复式投注共用同一套规则。 */
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
            addProblem(TicketValidationStatus.INVALID, "betLines", "彩票至少需要一注投注")
        }

        ticket.betLines.forEachIndexed { index, line ->
            validateLine(ticket.lotteryType.value, index, line, ::addProblem)
        }
        if (
            ticket.lotteryType.value == LotteryType.HAPPY_8 &&
            ticket.betLines
                .map { it.primaryNumbers.value.size }
                .distinct()
                .size > 1
        ) {
            addProblem(TicketValidationStatus.INVALID, "betLines", "快乐8 同一张票只有一种玩法，每注号码个数必须相同")
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
                LotteryType.DOUBLE_COLOR_BALL, LotteryType.HAPPY_8 -> WELFARE_LOTTERY_ISSUE_LENGTH
            }
        val value = ticket.issue.value.value
        if (value.length != expectedLength || value.any { !it.isDigit() }) {
            addProblem(TicketValidationStatus.INVALID, "issue", "期号必须是 $expectedLength 位数字")
        }
    }

    /** 校验一行单式或复式号码。 */
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
        if (lotteryType != LotteryType.SUPER_LOTTO && line.isAdditional.value) {
            addProblem(TicketValidationStatus.INVALID, "betLines[$index].isAdditional", "只有大乐透存在追加投注")
        }
        if (lotteryType == LotteryType.HAPPY_8) {
            if (line.primaryNumbers.value.size > BetCombinations.HAPPY_8_PICK_COUNTS.last) {
                addProblem(
                    TicketValidationStatus.UNSUPPORTED,
                    "betLines[$index].primaryNumbers",
                    "快乐8 当前只支持选一至选十单式，每注最多 ${BetCombinations.HAPPY_8_PICK_COUNTS.last} 个号码",
                )
            }
            if (line.secondaryNumbers.value.isNotEmpty()) {
                addProblem(TicketValidationStatus.INVALID, "betLines[$index].secondaryNumbers", "快乐8 没有第二个号码区")
            }
        }
    }

    /** 校验一个号码区域的数量、范围和唯一性；多于单式数量的号码按复式投注展开。 */
    private fun validateNumberArea(
        lineIndex: Int,
        field: String,
        numbers: List<Int>,
        minimumCount: Int,
        allowedRange: IntRange,
        addProblem: ProblemCollector,
    ) {
        val path = "betLines[$lineIndex].$field"
        if (numbers.size < minimumCount) {
            addProblem(TicketValidationStatus.INVALID, path, "号码数量不足，至少需要 $minimumCount 个号码")
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

    /** 校验投注金额与展开后的单式注数、倍数和追加属性是否严格一致。 */
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

        val lotteryType = ticket.lotteryType.value
        val betCount = ticket.betLines.sumOf { it.betCount(lotteryType) }
        val additionalBetCount = ticket.betLines.filter { it.isAdditional.value }.sumOf { it.betCount(lotteryType) }
        val baseAmountFen =
            TicketAmountCalculator.calculate(
                lotteryType = lotteryType,
                betCount = betCount,
                additionalBetCount = 0L,
                multiplier = ticket.multiplier.value,
                periodCount = ticket.periodCount.value,
            ) ?: run {
                addProblem(TicketValidationStatus.INVALID, "paidAmountFen", "无法根据当前投注结构计算金额")
                return
            }
        val expectedAmountFen =
            TicketAmountCalculator.calculate(
                lotteryType = lotteryType,
                betCount = betCount,
                additionalBetCount = additionalBetCount,
                multiplier = ticket.multiplier.value,
                periodCount = ticket.periodCount.value,
            ) ?: run {
                addProblem(TicketValidationStatus.INVALID, "paidAmountFen", "无法根据当前投注结构计算金额")
                return
            }

        val maxAmountFen =
            if (lotteryType == LotteryType.SUPER_LOTTO) {
                SUPER_LOTTO_MAX_TOTAL_FEN
            } else {
                WELFARE_LOTTERY_MAX_TOTAL_FEN
            }
        if (baseAmountFen > MAX_BASE_AMOUNT_FEN || expectedAmountFen > maxAmountFen) {
            addProblem(TicketValidationStatus.INVALID, "paidAmountFen", "投注金额超过单张彩票上限")
        }
        if (ticket.paidAmountFen.value != expectedAmountFen) {
            addProblem(
                TicketValidationStatus.INVALID,
                "paidAmountFen",
                "票面金额与投注注数、倍数或追加属性不一致",
            )
        }
    }

    /** 号码区域规则。 */
    private data class NumberSpec(
        /** 单式主号码数量，也是复式主号码的最少数量。 */
        val primaryCount: Int,
        /** 主号码合法范围。 */
        val primaryRange: IntRange,
        /** 单式次号码数量，也是复式次号码的最少数量。 */
        val secondaryCount: Int,
        /** 次号码合法范围。 */
        val secondaryRange: IntRange,
    ) {
        /** 创建指定彩种的号码规则。 */
        companion object {
            /** 返回指定彩种当前号码规则。 */
            fun forLottery(lotteryType: LotteryType): NumberSpec =
                when (lotteryType) {
                    LotteryType.SUPER_LOTTO -> {
                        NumberSpec(5, 1..35, 2, 1..12)
                    }

                    LotteryType.DOUBLE_COLOR_BALL -> {
                        NumberSpec(6, 1..33, 1, 1..16)
                    }

                    LotteryType.HAPPY_8 -> {
                        NumberSpec(
                            BetCombinations.HAPPY_8_PICK_COUNTS.first,
                            1..80,
                            0,
                            IntRange.EMPTY,
                        )
                    }
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

        /** 双色球与快乐8 期号长度。 */
        const val WELFARE_LOTTERY_ISSUE_LENGTH = 7

        /** 单张彩票基本投注金额上限，单位为分。 */
        const val MAX_BASE_AMOUNT_FEN = 2_000_000L

        /** 大乐透含追加投注的总金额上限，单位为分。 */
        const val SUPER_LOTTO_MAX_TOTAL_FEN = 3_000_000L

        /** 双色球与快乐8 总金额上限，单位为分。 */
        const val WELFARE_LOTTERY_MAX_TOTAL_FEN = 2_000_000L

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

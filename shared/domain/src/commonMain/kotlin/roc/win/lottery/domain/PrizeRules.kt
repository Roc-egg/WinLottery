package roc.win.lottery.domain

/** B2 支持的规则版本。 */
enum class RuleVersion(
    /** 持久化和跨模块传递使用的稳定编码。 */
    val code: String,
    /** 该版本所属彩种。 */
    val lotteryType: LotteryType,
) {
    /** 超级大乐透自 26014 期起生效的七奖级规则。 */
    DLT_2026_01("DLT_2026_01", LotteryType.SUPER_LOTTO),

    /** 双色球自 2026014 期起生效的规则。 */
    SSQ_2026_01("SSQ_2026_01", LotteryType.DOUBLE_COLOR_BALL),
}

/** 领域层使用的稳定奖级编码。 */
object PrizeTierCodes {
    /** 一等奖。 */
    const val FIRST = "FIRST"

    /** 二等奖。 */
    const val SECOND = "SECOND"

    /** 三等奖。 */
    const val THIRD = "THIRD"

    /** 四等奖。 */
    const val FOURTH = "FOURTH"

    /** 五等奖。 */
    const val FIFTH = "FIFTH"

    /** 六等奖。 */
    const val SIXTH = "SIXTH"

    /** 七等奖。 */
    const val SEVENTH = "SEVENTH"

    /** 双色球特别规定期间的福运奖。 */
    const val FORTUNE = "FORTUNE"
}

/** 根据彩种和期号选择规则版本，不使用当前日期推断规则。 */
class RuleVersionSelector {
    /**
     * 返回指定期号的 B2 规则版本。
     *
     * @param lotteryType 彩票玩法。
     * @param issue 保留前导零的期号。
     * @return 已支持的规则版本；格式错误或规则未覆盖时为 `null`。
     */
    fun select(
        lotteryType: LotteryType,
        issue: Issue,
    ): RuleVersion? {
        val value = issue.value
        if (value.any { !it.isDigit() }) return null
        return when (lotteryType) {
            LotteryType.SUPER_LOTTO -> {
                if (value.length == DLT_ISSUE_LENGTH && value >= DLT_FIRST_SUPPORTED_ISSUE) {
                    RuleVersion.DLT_2026_01
                } else {
                    null
                }
            }

            LotteryType.DOUBLE_COLOR_BALL -> {
                if (value.length == SSQ_ISSUE_LENGTH && value >= SSQ_FIRST_SUPPORTED_ISSUE) {
                    RuleVersion.SSQ_2026_01
                } else {
                    null
                }
            }
        }
    }

    /** 规则边界常量。 */
    private companion object {
        /** 大乐透期号长度。 */
        const val DLT_ISSUE_LENGTH = 5

        /** 双色球期号长度。 */
        const val SSQ_ISSUE_LENGTH = 7

        /** 大乐透首个受支持期号。 */
        const val DLT_FIRST_SUPPORTED_ISSUE = "26014"

        /** 双色球首个受支持期号。 */
        const val SSQ_FIRST_SUPPORTED_ISSUE = "2026014"
    }
}

/** 两种彩票当前规则的纯命中矩阵。 */
object LotteryPrizeRules {
    /**
     * 根据命中数返回唯一的最高奖级。
     *
     * @param lotteryType 彩票玩法。
     * @param policy 当期已确认政策。
     * @param primaryHitCount 前区或红球命中数。
     * @param secondaryHitCount 后区或蓝球命中数。
     * @return 稳定奖级编码，未中奖或命中数非法时为 `null`。
     */
    fun findPrizeTierCode(
        lotteryType: LotteryType,
        policy: DrawPolicy,
        primaryHitCount: Int,
        secondaryHitCount: Int,
    ): String? =
        when (lotteryType) {
            LotteryType.SUPER_LOTTO -> {
                findSuperLottoPrize(primaryHitCount, secondaryHitCount)
            }

            LotteryType.DOUBLE_COLOR_BALL -> {
                findDoubleColorBallPrize(policy, primaryHitCount, secondaryHitCount)
            }
        }

    /** 返回大乐透七奖级矩阵中的最高奖级。 */
    private fun findSuperLottoPrize(
        primaryHitCount: Int,
        secondaryHitCount: Int,
    ): String? {
        if (primaryHitCount !in 0..5 || secondaryHitCount !in 0..2) return null
        return when {
            primaryHitCount == 5 && secondaryHitCount == 2 -> PrizeTierCodes.FIRST
            primaryHitCount == 5 && secondaryHitCount == 1 -> PrizeTierCodes.SECOND
            primaryHitCount == 5 && secondaryHitCount == 0 -> PrizeTierCodes.THIRD
            primaryHitCount == 4 && secondaryHitCount == 2 -> PrizeTierCodes.THIRD
            primaryHitCount == 4 && secondaryHitCount == 1 -> PrizeTierCodes.FOURTH
            primaryHitCount == 4 && secondaryHitCount == 0 -> PrizeTierCodes.FIFTH
            primaryHitCount == 3 && secondaryHitCount == 2 -> PrizeTierCodes.FIFTH
            primaryHitCount == 3 && secondaryHitCount == 1 -> PrizeTierCodes.SIXTH
            primaryHitCount == 2 && secondaryHitCount == 2 -> PrizeTierCodes.SIXTH
            primaryHitCount == 3 && secondaryHitCount == 0 -> PrizeTierCodes.SEVENTH
            primaryHitCount == 2 && secondaryHitCount == 1 -> PrizeTierCodes.SEVENTH
            primaryHitCount == 1 && secondaryHitCount == 2 -> PrizeTierCodes.SEVENTH
            primaryHitCount == 0 && secondaryHitCount == 2 -> PrizeTierCodes.SEVENTH
            else -> null
        }
    }

    /** 返回双色球普通矩阵及特别规定中的最高奖级。 */
    private fun findDoubleColorBallPrize(
        policy: DrawPolicy,
        primaryHitCount: Int,
        secondaryHitCount: Int,
    ): String? {
        if (primaryHitCount !in 0..6 || secondaryHitCount !in 0..1) return null
        return when {
            primaryHitCount == 6 && secondaryHitCount == 1 -> PrizeTierCodes.FIRST

            primaryHitCount == 6 && secondaryHitCount == 0 -> PrizeTierCodes.SECOND

            primaryHitCount == 5 && secondaryHitCount == 1 -> PrizeTierCodes.THIRD

            primaryHitCount == 5 && secondaryHitCount == 0 -> PrizeTierCodes.FOURTH

            primaryHitCount == 4 && secondaryHitCount == 1 -> PrizeTierCodes.FOURTH

            primaryHitCount == 4 && secondaryHitCount == 0 -> PrizeTierCodes.FIFTH

            primaryHitCount == 3 && secondaryHitCount == 1 -> PrizeTierCodes.FIFTH

            primaryHitCount in 0..2 && secondaryHitCount == 1 -> PrizeTierCodes.SIXTH

            primaryHitCount == 3 && secondaryHitCount == 0 &&
                policy == DrawPolicy.DOUBLE_COLOR_BALL_FORTUNE -> PrizeTierCodes.FORTUNE

            else -> null
        }
    }
}

/** 大乐透官方奖级金额数据的校验状态。 */
enum class SuperLottoPrizeTierValidationStatus {
    /** 奖级和奖金字段完整且符合现行规则。 */
    COMPLETE,

    /** 奖级结构合法，但官网仍有奖金字段尚未发布。 */
    INCOMPLETE,

    /** 奖级集合、固定奖金额或追加奖金关系不符合现行规则。 */
    INVALID,
}

/**
 * 大乐透官方奖级金额数据校验结果。
 *
 * @property status 完整性与合法性状态。
 * @property message 数据不合法时的安全说明。
 */
data class SuperLottoPrizeTierValidationResult(
    val status: SuperLottoPrizeTierValidationStatus,
    val message: String? = null,
)

/** 固化大乐透七奖级、固定奖两档和追加奖金关系。 */
object SuperLottoPrizeTierValidator {
    /**
     * 校验统一模型中的大乐透奖级数据。
     *
     * 一、二等奖金额仍取当期官网值，只校验已发布追加金额与基本金额的规则关系；三至七等奖必须整体落在
     * 开奖前奖池低于 8 亿元或达到 8 亿元的两套官方固定金额之一。
     *
     * @param prizeTiers 当期开奖的规范化奖级列表。
     * @return 可用于数据适配层和规则引擎的统一校验结果。
     */
    fun validate(prizeTiers: List<PrizeTier>): SuperLottoPrizeTierValidationResult {
        val tiersByCode = prizeTiers.groupBy { it.code }
        if (tiersByCode.keys != REQUIRED_TIER_CODES || tiersByCode.values.any { it.size != 1 }) {
            return invalid("大乐透奖级集合不完整、重复或包含未知奖级")
        }
        if (
            prizeTiers.any { tier ->
                tier.singlePrizeFen?.let { it < 0L } == true ||
                    tier.additionalPrizeFen?.let { it < 0L } == true ||
                    tier.winnerCount?.let { it < 0L } == true ||
                    tier.additionalWinnerCount?.let { it < 0L } == true
            }
        ) {
            return invalid("大乐透奖级金额或中奖注数不合法")
        }
        val fixedTiers = FIXED_TIER_CODES.map { code -> requireNotNull(tiersByCode[code]?.singleOrNull()) }
        if (fixedTiers.any { it.additionalPrizeFen != null || it.additionalWinnerCount != null }) {
            return invalid("大乐透三至七等奖不应包含追加奖金字段")
        }
        val matchingFixedBands =
            FIXED_PRIZE_BANDS.filter { band ->
                fixedTiers.all { tier -> tier.singlePrizeFen == null || band[tier.code] == tier.singlePrizeFen }
            }
        if (matchingFixedBands.isEmpty()) {
            return invalid("大乐透三至七等奖金额不属于官方固定奖档位")
        }
        for (code in ADDITIONAL_TIER_CODES) {
            val tier = requireNotNull(tiersByCode[code]?.singleOrNull())
            val basePrizeFen = tier.singlePrizeFen
            val additionalPrizeFen = tier.additionalPrizeFen
            if (
                basePrizeFen != null &&
                additionalPrizeFen != null &&
                !matchesRoundedAdditionalPrize(basePrizeFen, additionalPrizeFen)
            ) {
                return invalid("大乐透一、二等奖追加奖金不符合基本奖金的 80% 规则")
            }
        }
        val complete =
            prizeTiers.all { it.singlePrizeFen != null } &&
                ADDITIONAL_TIER_CODES.all { code ->
                    tiersByCode[code]?.singleOrNull()?.additionalPrizeFen != null
                }
        return SuperLottoPrizeTierValidationResult(
            status =
                if (complete) {
                    SuperLottoPrizeTierValidationStatus.COMPLETE
                } else {
                    SuperLottoPrizeTierValidationStatus.INCOMPLETE
                },
        )
    }

    /** 按官网以元公布的口径校验四舍五入后的 80% 追加单注奖金。 */
    private fun matchesRoundedAdditionalPrize(
        basePrizeFen: Long,
        additionalPrizeFen: Long,
    ): Boolean {
        if (basePrizeFen % FEN_PER_YUAN != 0L || additionalPrizeFen % FEN_PER_YUAN != 0L) return false
        val baseYuan = basePrizeFen / FEN_PER_YUAN
        val expectedAdditionalYuan =
            (baseYuan / ADDITIONAL_RATIO_DENOMINATOR) * ADDITIONAL_RATIO_NUMERATOR +
                (
                    (baseYuan % ADDITIONAL_RATIO_DENOMINATOR) * ADDITIONAL_RATIO_NUMERATOR +
                        ADDITIONAL_ROUNDING_OFFSET
                ) / ADDITIONAL_RATIO_DENOMINATOR
        return additionalPrizeFen / FEN_PER_YUAN == expectedAdditionalYuan
    }

    /** 创建统一的非法结果。 */
    private fun invalid(message: String): SuperLottoPrizeTierValidationResult =
        SuperLottoPrizeTierValidationResult(SuperLottoPrizeTierValidationStatus.INVALID, message)

    /** 大乐透奖级数据规则常量。 */
    private val REQUIRED_TIER_CODES =
        setOf(
            PrizeTierCodes.FIRST,
            PrizeTierCodes.SECOND,
            PrizeTierCodes.THIRD,
            PrizeTierCodes.FOURTH,
            PrizeTierCodes.FIFTH,
            PrizeTierCodes.SIXTH,
            PrizeTierCodes.SEVENTH,
        )

    /** 只允许基本投注金额的固定奖级。 */
    private val FIXED_TIER_CODES =
        listOf(
            PrizeTierCodes.THIRD,
            PrizeTierCodes.FOURTH,
            PrizeTierCodes.FIFTH,
            PrizeTierCodes.SIXTH,
            PrizeTierCodes.SEVENTH,
        )

    /** 允许出现追加单注奖金的一、二等奖。 */
    private val ADDITIONAL_TIER_CODES = setOf(PrizeTierCodes.FIRST, PrizeTierCodes.SECOND)

    /** 开奖前奖池低于 8 亿元时的固定奖金额，单位为分。 */
    private val LOW_POOL_FIXED_PRIZES =
        mapOf(
            PrizeTierCodes.THIRD to 500_000L,
            PrizeTierCodes.FOURTH to 30_000L,
            PrizeTierCodes.FIFTH to 15_000L,
            PrizeTierCodes.SIXTH to 1_500L,
            PrizeTierCodes.SEVENTH to 500L,
        )

    /** 开奖前奖池达到 8 亿元时的固定奖金额，单位为分。 */
    private val HIGH_POOL_FIXED_PRIZES =
        mapOf(
            PrizeTierCodes.THIRD to 666_600L,
            PrizeTierCodes.FOURTH to 38_000L,
            PrizeTierCodes.FIFTH to 20_000L,
            PrizeTierCodes.SIXTH to 1_800L,
            PrizeTierCodes.SEVENTH to 700L,
        )

    /** 官方允许的两套固定奖金额。 */
    private val FIXED_PRIZE_BANDS = listOf(LOW_POOL_FIXED_PRIZES, HIGH_POOL_FIXED_PRIZES)

    /** 一元对应的分数。 */
    private const val FEN_PER_YUAN = 100L

    /** 追加奖金比例分子。 */
    private const val ADDITIONAL_RATIO_NUMERATOR = 4L

    /** 追加奖金比例分母。 */
    private const val ADDITIONAL_RATIO_DENOMINATOR = 5L

    /** 整数除法实现四舍五入时使用的偏移。 */
    private const val ADDITIONAL_ROUNDING_OFFSET = 2L
}

/** B2 本地中奖规则计算器。 */
class LotteryPrizeCalculator(
    /** 按期号选择规则版本的能力。 */
    private val ruleVersionSelector: RuleVersionSelector = RuleVersionSelector(),
    /** 对调用方传入票据执行防御性复核的校验器。 */
    private val ticketValidator: TicketValidator = TicketValidator(),
) : PrizeCalculator {
    /** 根据已确认票据和开奖结果逐注计算最高奖级并汇总税前金额。 */
    override fun calculate(
        ticket: ConfirmedTicket,
        drawResult: DrawResult,
    ): PrizeCheckResult {
        val selectedRule =
            ruleVersionSelector.select(ticket.lotteryType.value, ticket.issue.value)
                ?: return unsupportedRuleResult()
        val preconditionProblem = validatePreconditions(ticket, drawResult, selectedRule)
        if (preconditionProblem != null) return manualReviewResult(preconditionProblem)

        val tiersByCode = drawResult.prizeTiers.groupBy { it.code }
        if (tiersByCode.values.any { it.size > 1 }) {
            return manualReviewResult("当期开奖包含重复奖级，无法可靠测算")
        }
        if (drawResult.prizeTiers.any { tier ->
                tier.singlePrizeFen?.let { it < 0L } == true ||
                    tier.additionalPrizeFen?.let { it < 0L } == true ||
                    tier.winnerCount?.let { it < 0L } == true ||
                    tier.additionalWinnerCount?.let { it < 0L } == true
            }
        ) {
            return manualReviewResult("当期开奖奖级数据不合法，需要人工复核")
        }

        val lineResults = mutableListOf<BetLinePrizeResult>()
        var totalPrizeFen = 0L
        var hasWinner = false
        var hasMissingAmount = false
        var hasOverflow = false

        ticket.betLines.forEachIndexed { index, line ->
            val primaryHits =
                line.primaryNumbers.value
                    .toSet()
                    .intersect(drawResult.primaryNumbers.toSet())
                    .size
            val secondaryHits =
                line.secondaryNumbers.value
                    .toSet()
                    .intersect(drawResult.secondaryNumbers.toSet())
                    .size
            val tierCode =
                LotteryPrizeRules.findPrizeTierCode(
                    lotteryType = ticket.lotteryType.value,
                    policy = drawResult.policy,
                    primaryHitCount = primaryHits,
                    secondaryHitCount = secondaryHits,
                )
            if (tierCode == null) {
                lineResults += BetLinePrizeResult(index, primaryHits, secondaryHits, null, 0L)
                return@forEachIndexed
            }

            hasWinner = true
            val tier = tiersByCode[tierCode]?.singleOrNull()
            val amount = calculateLineAmount(ticket, line, tierCode, tier, drawResult.status)
            when (amount) {
                LineAmount.Missing -> {
                    hasMissingAmount = true
                }

                LineAmount.Overflow -> {
                    hasOverflow = true
                }

                is LineAmount.Value -> {
                    if (totalPrizeFen > Long.MAX_VALUE - amount.fen) {
                        hasOverflow = true
                    } else {
                        totalPrizeFen += amount.fen
                    }
                }
            }
            lineResults +=
                BetLinePrizeResult(
                    lineIndex = index,
                    primaryHitCount = primaryHits,
                    secondaryHitCount = secondaryHits,
                    prizeTierCode = tierCode,
                    estimatedPrizeFen = (amount as? LineAmount.Value)?.fen,
                )
        }

        if (hasOverflow) {
            return PrizeCheckResult(
                status = PrizeCheckStatus.NEEDS_MANUAL_REVIEW,
                lineResults = lineResults,
                estimatedPrizeFen = null,
                message = "奖金金额超出可安全计算范围，需要人工复核",
            )
        }
        if (!hasWinner) {
            return PrizeCheckResult(
                status = PrizeCheckStatus.NO_WIN,
                lineResults = lineResults,
                estimatedPrizeFen = 0L,
                message = null,
            )
        }
        if (hasMissingAmount) {
            return PrizeCheckResult(
                status = PrizeCheckStatus.WIN,
                lineResults = lineResults,
                estimatedPrizeFen = null,
                message = "已确定命中奖级，奖金待官方数据确认",
            )
        }
        return PrizeCheckResult(
            status = PrizeCheckStatus.WIN,
            lineResults = lineResults,
            estimatedPrizeFen = totalPrizeFen,
            message = null,
        )
    }

    /** 校验票据、开奖结果、规则和政策是否能够共同驱动测算。 */
    private fun validatePreconditions(
        ticket: ConfirmedTicket,
        drawResult: DrawResult,
        selectedRule: RuleVersion,
    ): String? {
        if (!ticketValidator.validate(ticket).canCalculate) return "票面信息未通过领域校验"
        if (ticket.lotteryType.value != drawResult.lotteryType || ticket.issue.value != drawResult.issue) {
            return "彩票与开奖结果的彩种或期号不一致"
        }
        if (drawResult.status !in CALCULABLE_DRAW_STATUSES) return "开奖结果尚未达到可判断状态"
        if (drawResult.ruleVersion != selectedRule.code) return "开奖结果的规则版本与期号不一致"
        if (!isPolicyCompatible(drawResult)) return "当期特别规定状态与已确认规则边界冲突"
        if (!hasValidDrawNumbers(drawResult)) return "开奖号码数量、范围或唯一性不合法"
        if (drawResult.lotteryType == LotteryType.SUPER_LOTTO) {
            val tierValidation = SuperLottoPrizeTierValidator.validate(drawResult.prizeTiers)
            if (tierValidation.status == SuperLottoPrizeTierValidationStatus.INVALID) {
                return tierValidation.message ?: "大乐透奖级数据不符合现行规则"
            }
            if (
                drawResult.status == DrawStatus.FINAL_PAYOUT &&
                tierValidation.status != SuperLottoPrizeTierValidationStatus.COMPLETE
            ) {
                return "大乐透奖级金额尚未完整，不能进入最终奖金状态"
            }
        }
        if (drawResult.revision < 1) return "开奖结果修订号不合法"
        if (drawResult.evidence.contentSha256.isBlank()) return "开奖结果缺少内容校验证据"
        if (drawResult.supportingEvidence.isEmpty() ||
            drawResult.supportingEvidence.any { it.contentSha256.isBlank() }
        ) {
            return "开奖结果缺少官方辅助核对证据"
        }
        return null
    }

    /** 判断特别政策是否与已固化的 2026 边界一致。 */
    private fun isPolicyCompatible(drawResult: DrawResult): Boolean {
        if (drawResult.lotteryType == LotteryType.SUPER_LOTTO) {
            return drawResult.policy == DrawPolicy.STANDARD
        }
        val issue = drawResult.issue.value
        if (issue in SSQ_FORTUNE_FIRST_ISSUE..SSQ_FORTUNE_LAST_ISSUE) {
            return drawResult.policy == DrawPolicy.DOUBLE_COLOR_BALL_FORTUNE
        }
        return drawResult.policy == DrawPolicy.STANDARD
    }

    /** 严格校验统一模型中的开奖号码。 */
    private fun hasValidDrawNumbers(drawResult: DrawResult): Boolean {
        val spec =
            when (drawResult.lotteryType) {
                LotteryType.SUPER_LOTTO -> DrawNumberSpec(5, 1..35, 2, 1..12)
                LotteryType.DOUBLE_COLOR_BALL -> DrawNumberSpec(6, 1..33, 1, 1..16)
            }
        return drawResult.primaryNumbers.size == spec.primaryCount &&
            drawResult.primaryNumbers.distinct().size == spec.primaryCount &&
            drawResult.primaryNumbers.all { it in spec.primaryRange } &&
            drawResult.secondaryNumbers.size == spec.secondaryCount &&
            drawResult.secondaryNumbers.distinct().size == spec.secondaryCount &&
            drawResult.secondaryNumbers.all { it in spec.secondaryRange }
    }

    /** 计算一行投注计入倍数和追加后的金额。 */
    private fun calculateLineAmount(
        ticket: ConfirmedTicket,
        line: BetLine,
        tierCode: String,
        tier: PrizeTier?,
        drawStatus: DrawStatus,
    ): LineAmount {
        if (drawStatus != DrawStatus.FINAL_PAYOUT) return LineAmount.Missing
        val basePrizeFen = tier?.singlePrizeFen ?: return LineAmount.Missing
        val additionalPrizeFen =
            if (
                ticket.lotteryType.value == LotteryType.SUPER_LOTTO &&
                line.isAdditional.value &&
                tierCode in ADDITIONAL_PRIZE_TIER_CODES
            ) {
                tier.additionalPrizeFen ?: return LineAmount.Missing
            } else {
                0L
            }
        val singleBetTotal = safeAdd(basePrizeFen, additionalPrizeFen) ?: return LineAmount.Overflow
        val multiplied = safeMultiply(singleBetTotal, ticket.multiplier.value.toLong()) ?: return LineAmount.Overflow
        return LineAmount.Value(multiplied)
    }

    /** 对两个非负金额执行溢出保护加法。 */
    private fun safeAdd(
        left: Long,
        right: Long,
    ): Long? = if (left > Long.MAX_VALUE - right) null else left + right

    /** 对两个非负数执行溢出保护乘法。 */
    private fun safeMultiply(
        left: Long,
        right: Long,
    ): Long? = if (left != 0L && right > Long.MAX_VALUE / left) null else left * right

    /** 创建规则未覆盖结果。 */
    private fun unsupportedRuleResult(): PrizeCheckResult =
        PrizeCheckResult(
            status = PrizeCheckStatus.RULE_UNSUPPORTED,
            lineResults = emptyList(),
            estimatedPrizeFen = null,
            message = "该期号不在 V1 已验证规则范围内",
        )

    /** 创建需要人工复核的防御性结果。 */
    private fun manualReviewResult(message: String): PrizeCheckResult =
        PrizeCheckResult(
            status = PrizeCheckStatus.NEEDS_MANUAL_REVIEW,
            lineResults = emptyList(),
            estimatedPrizeFen = null,
            message = message,
        )

    /** 开奖号码的严格格式。 */
    private data class DrawNumberSpec(
        /** 主号码数量。 */
        val primaryCount: Int,
        /** 主号码范围。 */
        val primaryRange: IntRange,
        /** 次号码数量。 */
        val secondaryCount: Int,
        /** 次号码范围。 */
        val secondaryRange: IntRange,
    )

    /** 一行中奖金额的内部计算状态。 */
    private sealed interface LineAmount {
        /** 官网尚未提供该奖级所需金额。 */
        data object Missing : LineAmount

        /** 金额计算超出 `Long` 可表示范围。 */
        data object Overflow : LineAmount

        /** 已安全算出的税前金额。 */
        data class Value(
            /** 金额，单位为分。 */
            val fen: Long,
        ) : LineAmount
    }

    /** 计算规则使用的固定集合与政策边界。 */
    private companion object {
        /** 可以进入奖级判断的开奖状态。 */
        val CALCULABLE_DRAW_STATUSES = setOf(DrawStatus.FINAL_NUMBERS, DrawStatus.FINAL_PAYOUT)

        /** 大乐透追加投注会增加奖金的奖级。 */
        val ADDITIONAL_PRIZE_TIER_CODES = setOf(PrizeTierCodes.FIRST, PrizeTierCodes.SECOND)

        /** 双色球特别规定首期。 */
        const val SSQ_FORTUNE_FIRST_ISSUE = "2026014"

        /** 双色球本轮特别规定末期。 */
        const val SSQ_FORTUNE_LAST_ISSUE = "2026075"
    }
}

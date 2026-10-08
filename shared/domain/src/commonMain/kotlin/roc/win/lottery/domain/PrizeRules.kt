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
    /** 奖级和奖金字段完整且符合现行规则；零注奖级允许没有金额。 */
    COMPLETE,

    /** 奖级结构合法，但官网仍有产生中奖注的奖金字段尚未发布。 */
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
                !matchesPublishedAdditionalPrize(basePrizeFen, additionalPrizeFen)
            ) {
                return invalid("大乐透一、二等奖追加奖金不符合基本奖金 80% 的元级公布口径")
            }
        }
        // 零注奖级官网常以 `---` 表示未产生奖金，不能因此阻断其他奖级金额。
        val complete =
            prizeTiers.all { it.singlePrizeFen != null || it.winnerCount == 0L } &&
                ADDITIONAL_TIER_CODES.all { code ->
                    val tier = requireNotNull(tiersByCode[code]?.singleOrNull())
                    tier.additionalPrizeFen != null || tier.additionalWinnerCount == 0L
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

    /** 校验两个独立按元取整的官网金额仍可能来自同一组 80% 浮动奖金。 */
    private fun matchesPublishedAdditionalPrize(
        basePrizeFen: Long,
        additionalPrizeFen: Long,
    ): Boolean {
        if (basePrizeFen % FEN_PER_YUAN != 0L || additionalPrizeFen % FEN_PER_YUAN != 0L) return false
        val baseYuan = basePrizeFen / FEN_PER_YUAN
        val lowerAdditionalYuan =
            (baseYuan / ADDITIONAL_RATIO_DENOMINATOR) * ADDITIONAL_RATIO_NUMERATOR +
                (baseYuan % ADDITIONAL_RATIO_DENOMINATOR) * ADDITIONAL_RATIO_NUMERATOR /
                ADDITIONAL_RATIO_DENOMINATOR
        val upperAdditionalYuan =
            lowerAdditionalYuan +
                if (baseYuan % ADDITIONAL_RATIO_DENOMINATOR == 0L) 0L else 1L
        return additionalPrizeFen / FEN_PER_YUAN in lowerAdditionalYuan..upperAdditionalYuan
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
}

/**
 * B2 本地中奖规则计算器。
 *
 * 只要官方开奖号码可用就逐注比对并给出中奖或未中奖结论；奖级金额缺失、重复或异常时只把对应金额留空，
 * 不再阻断中奖结论。
 */
class LotteryPrizeCalculator(
    /** 按期号选择规则版本的能力。 */
    private val ruleVersionSelector: RuleVersionSelector = RuleVersionSelector(),
) : PrizeCalculator {
    /** 根据已确认票据和开奖结果逐注计算最高奖级并汇总税前金额。 */
    override fun calculate(
        ticket: ConfirmedTicket,
        drawResult: DrawResult,
    ): PrizeCheckResult {
        ruleVersionSelector.select(ticket.lotteryType.value, ticket.issue.value)
            ?: return unsupportedRuleResult()
        val comparisonProblem = findComparisonProblem(ticket, drawResult)
        if (comparisonProblem != null) return notCalculatedResult(comparisonProblem)

        val policy = effectivePolicy(drawResult)
        val tiersByCode = drawResult.prizeTiers.groupBy { it.code }
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
                    policy = policy,
                    primaryHitCount = primaryHits,
                    secondaryHitCount = secondaryHits,
                )
            if (tierCode == null) {
                lineResults += BetLinePrizeResult(index, primaryHits, secondaryHits, null, 0L)
                return@forEachIndexed
            }

            hasWinner = true
            // 重复奖级无法确定取哪一份金额，按金额未知处理。
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

        if (!hasWinner) {
            return PrizeCheckResult(
                status = PrizeCheckStatus.NO_WIN,
                lineResults = lineResults,
                estimatedPrizeFen = 0L,
                message = null,
            )
        }
        if (hasOverflow) {
            return PrizeCheckResult(
                status = PrizeCheckStatus.WIN,
                lineResults = lineResults,
                estimatedPrizeFen = null,
                message = "已确定命中奖级，奖金合计超出可精确计算范围，请以官方兑奖金额为准",
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

    /** 只检查逐注比对本身必需的条件，金额、政策和辅助证据问题不再阻断结论。 */
    private fun findComparisonProblem(
        ticket: ConfirmedTicket,
        drawResult: DrawResult,
    ): String? {
        if (ticket.lotteryType.value != drawResult.lotteryType || ticket.issue.value != drawResult.issue) {
            return "彩票与开奖结果的彩种或期号不一致"
        }
        if (drawResult.status !in CALCULABLE_DRAW_STATUSES) return "官方开奖号码尚未最终确认"
        val spec = DrawNumberSpec.forLottery(drawResult.lotteryType)
        if (!spec.matches(drawResult.primaryNumbers, drawResult.secondaryNumbers)) {
            return "官方开奖号码数量或范围异常，无法比对"
        }
        if (ticket.betLines.isEmpty()) return "票面没有可比对的投注行"
        ticket.betLines.forEachIndexed { index, line ->
            if (!spec.matches(line.primaryNumbers.value, line.secondaryNumbers.value)) {
                return "第 ${index + 1} 注号码不是有效的单式投注，无法比对"
            }
        }
        return null
    }

    /** 已知特别规定期间固定启用福运奖，其余期次以官方数据给出的政策为准。 */
    private fun effectivePolicy(drawResult: DrawResult): DrawPolicy =
        if (
            drawResult.lotteryType == LotteryType.DOUBLE_COLOR_BALL &&
            drawResult.issue.value in SSQ_FORTUNE_FIRST_ISSUE..SSQ_FORTUNE_LAST_ISSUE
        ) {
            DrawPolicy.DOUBLE_COLOR_BALL_FORTUNE
        } else {
            drawResult.policy
        }

    /** 计算一行投注计入倍数和追加后的金额。 */
    private fun calculateLineAmount(
        ticket: ConfirmedTicket,
        line: BetLine,
        tierCode: String,
        tier: PrizeTier?,
        drawStatus: DrawStatus,
    ): LineAmount {
        if (drawStatus != DrawStatus.FINAL_PAYOUT || ticket.multiplier.value < 1) return LineAmount.Missing
        val basePrizeFen = tier?.singlePrizeFen?.takeIf { it >= 0L } ?: return LineAmount.Missing
        val additionalPrizeFen =
            if (
                ticket.lotteryType.value == LotteryType.SUPER_LOTTO &&
                line.isAdditional.value &&
                tierCode in ADDITIONAL_PRIZE_TIER_CODES
            ) {
                tier.additionalPrizeFen?.takeIf { it >= 0L } ?: return LineAmount.Missing
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

    /** 创建无法执行号码比对的结果。 */
    private fun notCalculatedResult(message: String): PrizeCheckResult =
        PrizeCheckResult(
            status = PrizeCheckStatus.NOT_CALCULATED,
            lineResults = emptyList(),
            estimatedPrizeFen = null,
            message = message,
        )

    /** 号码区域的严格格式。 */
    private data class DrawNumberSpec(
        /** 主号码数量。 */
        val primaryCount: Int,
        /** 主号码范围。 */
        val primaryRange: IntRange,
        /** 次号码数量。 */
        val secondaryCount: Int,
        /** 次号码范围。 */
        val secondaryRange: IntRange,
    ) {
        /** 判断一组号码是否满足单式投注或开奖号码的数量、范围和唯一性。 */
        fun matches(
            primaryNumbers: List<Int>,
            secondaryNumbers: List<Int>,
        ): Boolean =
            primaryNumbers.size == primaryCount &&
                primaryNumbers.distinct().size == primaryCount &&
                primaryNumbers.all { it in primaryRange } &&
                secondaryNumbers.size == secondaryCount &&
                secondaryNumbers.distinct().size == secondaryCount &&
                secondaryNumbers.all { it in secondaryRange }

        /** 按彩种创建号码格式。 */
        companion object {
            /** 返回指定彩种的号码格式。 */
            fun forLottery(lotteryType: LotteryType): DrawNumberSpec =
                when (lotteryType) {
                    LotteryType.SUPER_LOTTO -> DrawNumberSpec(5, 1..35, 2, 1..12)
                    LotteryType.DOUBLE_COLOR_BALL -> DrawNumberSpec(6, 1..33, 1, 1..16)
                }
        }
    }

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

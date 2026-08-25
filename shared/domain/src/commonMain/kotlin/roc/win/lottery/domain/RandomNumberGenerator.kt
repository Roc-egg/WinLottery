package roc.win.lottery.domain

import kotlin.random.Random

/** V1.2 随机号码跨期生成模式。 */
enum class CrossPeriodRandomMode {
    /** 所有期次复用同一批随机注单。 */
    REUSE_SAME_LINES,

    /** 每一期按相同注数独立抽样，仅对双色球开放。 */
    INDEPENDENT_PER_PERIOD,
}

/**
 * 一注尚未购买的随机号码。
 *
 * @property primaryNumbers 大乐透前区或双色球红球，按升序保存。
 * @property secondaryNumbers 大乐透后区或双色球蓝球，按升序保存。
 */
data class GeneratedNumberLine(
    val primaryNumbers: List<Int>,
    val secondaryNumbers: List<Int>,
)

/**
 * 一个相对期次的随机号码集合。
 *
 * @property periodIndex 从 1 开始的相对期次序号，不代表官方开奖期号。
 * @property lines 当前期次的全部随机注单。
 */
data class GeneratedPeriodNumbers(
    val periodIndex: Int,
    val lines: List<GeneratedNumberLine>,
)

/**
 * 一次完整的随机选号请求。
 *
 * @property lotteryType 目标彩种。
 * @property periodCount 需要生成的相对期次数量。
 * @property betsPerPeriod 每一期的注数。
 * @property crossPeriodMode 跨期生成模式。
 */
data class RandomNumberGenerationRequest(
    val lotteryType: LotteryType,
    val periodCount: Int,
    val betsPerPeriod: Int,
    val crossPeriodMode: CrossPeriodRandomMode,
)

/**
 * 已生成但尚未购买的随机号码方案。
 *
 * @property lotteryType 目标彩种。
 * @property periodCount 相对期次数量。
 * @property betsPerPeriod 每一期的注数。
 * @property crossPeriodMode 跨期生成模式。
 * @property periods 按相对期次排列的全部结果。
 */
data class RandomNumberPlan(
    val lotteryType: LotteryType,
    val periodCount: Int,
    val betsPerPeriod: Int,
    val crossPeriodMode: CrossPeriodRandomMode,
    val periods: List<GeneratedPeriodNumbers>,
) {
    /** 方案中包含的投注实例总数，跨期复用也按每一期分别计数。 */
    val totalBetCount: Int
        get() = periodCount * betsPerPeriod
}

/** 随机选号结果。 */
sealed interface RandomNumberGenerationResult {
    /**
     * 已生成完整合法方案。
     *
     * @property plan 不包含购买事实或官方期号的随机号码方案。
     */
    data class Success(
        val plan: RandomNumberPlan,
    ) : RandomNumberGenerationResult

    /**
     * 请求不符合 V1.2 产品边界。
     *
     * @property message 可直接展示的简体中文阻断说明。
     */
    data class InvalidRequest(
        val message: String,
    ) : RandomNumberGenerationResult
}

/** 为领域生成器提供可替换的均匀整数随机源。 */
fun interface RandomIntSource {
    /**
     * 返回从 0（含）到上界（不含）的随机整数。
     *
     * @param untilExclusive 必须大于 0 的排他上界。
     * @return 位于 `0 until untilExclusive` 的随机整数。
     */
    fun nextInt(untilExclusive: Int): Int
}

/**
 * 使用 Kotlin Multiplatform 默认伪随机实现的整数随机源。
 *
 * @property random 实际提供均匀整数的 Kotlin 随机实例。
 */
class KotlinRandomIntSource(
    private val random: Random = Random.Default,
) : RandomIntSource {
    /** 返回 Kotlin 随机实例生成的下一个合法整数。 */
    override fun nextInt(untilExclusive: Int): Int = random.nextInt(untilExclusive)
}

/** V1.2 随机选号输入边界。 */
object RandomNumberGenerationLimits {
    /** 最少生成期数。 */
    const val MIN_PERIOD_COUNT = 1

    /** 最多生成期数，与当前受控多期票边界一致。 */
    const val MAX_PERIOD_COUNT = 20

    /** 每期最少注数。 */
    const val MIN_BETS_PER_PERIOD = 1

    /** 每期最多注数，避免生成不可读的超长列表。 */
    const val MAX_BETS_PER_PERIOD = 10

    /** 一次方案最多包含的投注实例数。 */
    const val MAX_TOTAL_BET_COUNT = 200
}

/**
 * 按当前两种彩票单式规则生成合法随机号码。
 *
 * @property randomIntSource 可在测试中替换的均匀整数随机源。
 */
class LotteryRandomNumberGenerator(
    private val randomIntSource: RandomIntSource = KotlinRandomIntSource(),
) {
    /**
     * 校验请求并生成完整随机号码方案。
     *
     * @param request 用户确认的彩种、期数、注数和跨期模式。
     * @return 完整方案或明确的输入阻断原因。
     */
    fun generate(request: RandomNumberGenerationRequest): RandomNumberGenerationResult {
        validate(request)?.let { message ->
            return RandomNumberGenerationResult.InvalidRequest(message)
        }

        val sharedLines =
            if (request.crossPeriodMode == CrossPeriodRandomMode.REUSE_SAME_LINES) {
                generateLines(request.lotteryType, request.betsPerPeriod)
            } else {
                null
            }
        val periods =
            (1..request.periodCount).map { periodIndex ->
                GeneratedPeriodNumbers(
                    periodIndex = periodIndex,
                    lines =
                        if (request.crossPeriodMode == CrossPeriodRandomMode.REUSE_SAME_LINES) {
                            requireNotNull(sharedLines)
                        } else {
                            generateLines(request.lotteryType, request.betsPerPeriod)
                        },
                )
            }
        return RandomNumberGenerationResult.Success(
            RandomNumberPlan(
                lotteryType = request.lotteryType,
                periodCount = request.periodCount,
                betsPerPeriod = request.betsPerPeriod,
                crossPeriodMode = request.crossPeriodMode,
                periods = periods,
            ),
        )
    }

    /** 返回首个输入问题；请求完全合法时返回 `null`。 */
    private fun validate(request: RandomNumberGenerationRequest): String? =
        when {
            request.periodCount !in
                RandomNumberGenerationLimits.MIN_PERIOD_COUNT..RandomNumberGenerationLimits.MAX_PERIOD_COUNT -> {
                "期数必须在 1 至 ${RandomNumberGenerationLimits.MAX_PERIOD_COUNT} 之间"
            }

            request.betsPerPeriod !in
                RandomNumberGenerationLimits.MIN_BETS_PER_PERIOD..RandomNumberGenerationLimits.MAX_BETS_PER_PERIOD -> {
                "每期注数必须在 1 至 ${RandomNumberGenerationLimits.MAX_BETS_PER_PERIOD} 之间"
            }

            request.periodCount * request.betsPerPeriod > RandomNumberGenerationLimits.MAX_TOTAL_BET_COUNT -> {
                "单次方案最多生成 ${RandomNumberGenerationLimits.MAX_TOTAL_BET_COUNT} 注"
            }

            request.lotteryType == LotteryType.SUPER_LOTTO &&
                request.crossPeriodMode == CrossPeriodRandomMode.INDEPENDENT_PER_PERIOD -> {
                "大乐透多期只支持复用同一批随机号码"
            }

            else -> {
                null
            }
        }

    /** 生成指定数量的独立单式注单，不强制排除注单之间的自然重复。 */
    private fun generateLines(
        lotteryType: LotteryType,
        count: Int,
    ): List<GeneratedNumberLine> {
        val spec = RandomNumberSpec.forLottery(lotteryType)
        return List(count) {
            GeneratedNumberLine(
                primaryNumbers = sampleWithoutReplacement(spec.primaryRange, spec.primaryCount),
                secondaryNumbers = sampleWithoutReplacement(spec.secondaryRange, spec.secondaryCount),
            )
        }
    }

    /** 使用部分 Fisher-Yates 洗牌从一个连续区间无放回抽样并升序返回。 */
    private fun sampleWithoutReplacement(
        range: IntRange,
        count: Int,
    ): List<Int> {
        val pool = range.toMutableList()
        repeat(count) { offset ->
            val remainingSize = pool.size - offset
            val randomOffset = randomIntSource.nextInt(remainingSize)
            check(randomOffset in 0 until remainingSize) { "随机源返回了超出约定范围的整数" }
            val selectedIndex = offset + randomOffset
            val previous = pool[offset]
            pool[offset] = pool[selectedIndex]
            pool[selectedIndex] = previous
        }
        return pool.take(count).sorted()
    }

    /** 两种彩票随机号码区域规则。 */
    private data class RandomNumberSpec(
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
            /** 返回与当前单式领域校验一致的号码规则。 */
            fun forLottery(lotteryType: LotteryType): RandomNumberSpec =
                when (lotteryType) {
                    LotteryType.SUPER_LOTTO -> RandomNumberSpec(5, 1..35, 2, 1..12)
                    LotteryType.DOUBLE_COLOR_BALL -> RandomNumberSpec(6, 1..33, 1, 1..16)
                }
        }
    }
}

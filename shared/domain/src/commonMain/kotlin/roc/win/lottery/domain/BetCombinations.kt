package roc.win.lottery.domain

/**
 * 单式与复式投注的注数规则。
 *
 * 一组号码的主区、次区数量都不少于单式要求时有效；任一区多于单式要求即为复式，等价于从两区分别取出
 * 单式数量号码的全部组合，每个组合是一注独立计奖的单式投注。
 */
object BetCombinations {
    /** 返回指定彩种单式投注的主号码数量。 */
    fun primaryCount(lotteryType: LotteryType): Int =
        when (lotteryType) {
            LotteryType.SUPER_LOTTO -> SUPER_LOTTO_PRIMARY_COUNT
            LotteryType.DOUBLE_COLOR_BALL -> DOUBLE_COLOR_BALL_PRIMARY_COUNT
        }

    /** 返回指定彩种单式投注的次号码数量。 */
    fun secondaryCount(lotteryType: LotteryType): Int =
        when (lotteryType) {
            LotteryType.SUPER_LOTTO -> SUPER_LOTTO_SECONDARY_COUNT
            LotteryType.DOUBLE_COLOR_BALL -> DOUBLE_COLOR_BALL_SECONDARY_COUNT
        }

    /**
     * 返回一组号码展开后的单式注数。
     *
     * @param lotteryType 彩票玩法。
     * @param primaryNumberCount 前区或红球号码个数。
     * @param secondaryNumberCount 后区或蓝球号码个数。
     * @return 单式注数；任一区号码少于单式要求时为 `0`。
     */
    fun betCount(
        lotteryType: LotteryType,
        primaryNumberCount: Int,
        secondaryNumberCount: Int,
    ): Long =
        combinations(primaryNumberCount, primaryCount(lotteryType)) *
            combinations(secondaryNumberCount, secondaryCount(lotteryType))

    /**
     * 返回组合数 `C(n, k)`。
     *
     * 两种彩票号码区最多 35 个号码，结果远小于 `Long` 上限；`k` 不在 `0..n` 内时为 `0`。
     */
    fun combinations(
        n: Int,
        k: Int,
    ): Long {
        if (n < 0 || k !in 0..n) return 0L
        val smallerK = minOf(k, n - k)
        var result = 1L
        // 每一步的乘积都能被 i 整除，逐步约分保持精确整数。
        for (i in 1..smallerK) {
            result = result * (n - smallerK + i) / i
        }
        return result
    }

    /** 大乐透单式前区号码数量。 */
    private const val SUPER_LOTTO_PRIMARY_COUNT = 5

    /** 大乐透单式后区号码数量。 */
    private const val SUPER_LOTTO_SECONDARY_COUNT = 2

    /** 双色球单式红球数量。 */
    private const val DOUBLE_COLOR_BALL_PRIMARY_COUNT = 6

    /** 双色球单式蓝球数量。 */
    private const val DOUBLE_COLOR_BALL_SECONDARY_COUNT = 1
}

/** 返回本行展开后的单式注数，号码数量不足单式要求时为 `0`。 */
fun BetLine.betCount(lotteryType: LotteryType): Long =
    BetCombinations.betCount(lotteryType, primaryNumbers.value.size, secondaryNumbers.value.size)

/** 返回本行是否为复式投注，即任一区号码多于单式要求。 */
fun BetLine.isCompound(lotteryType: LotteryType): Boolean =
    primaryNumbers.value.size > BetCombinations.primaryCount(lotteryType) ||
        secondaryNumbers.value.size > BetCombinations.secondaryCount(lotteryType)

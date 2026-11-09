package roc.win.lottery.domain

/** 按单式注数计算整张彩票的理论支付金额；复式投注先按 [BetCombinations] 展开为单式注数。 */
object TicketAmountCalculator {
    /**
     * 根据单式注数、倍数、期数和追加注数计算理论金额。
     *
     * @param lotteryType 彩票玩法。
     * @param betCount 全票展开后的单式注数。
     * @param additionalBetCount 其中大乐透追加投注的单式注数。
     * @param multiplier 投注倍数。
     * @param periodCount 投注期数。
     * @return 理论支付金额，单位为分；参数不合法或计算溢出时返回 `null`。
     */
    fun calculate(
        lotteryType: LotteryType,
        betCount: Long,
        additionalBetCount: Long,
        multiplier: Int,
        periodCount: Int,
    ): Long? {
        if (
            betCount < 0L ||
            additionalBetCount !in 0L..betCount ||
            multiplier <= 0 ||
            periodCount <= 0 ||
            (lotteryType != LotteryType.SUPER_LOTTO && additionalBetCount != 0L)
        ) {
            return null
        }

        val baseAmountFen = multiplyExactOrNull(BASE_BET_PRICE_FEN, betCount) ?: return null
        val additionalAmountFen =
            if (lotteryType == LotteryType.SUPER_LOTTO) {
                multiplyExactOrNull(ADDITIONAL_BET_PRICE_FEN, additionalBetCount) ?: return null
            } else {
                0L
            }
        if (baseAmountFen > Long.MAX_VALUE - additionalAmountFen) return null
        return multiplyExactOrNull(
            baseAmountFen + additionalAmountFen,
            multiplier.toLong(),
            periodCount.toLong(),
        )
    }

    /** 对多个非负因子执行带溢出保护的乘法。 */
    private fun multiplyExactOrNull(vararg factors: Long): Long? {
        var result = 1L
        factors.forEach { factor ->
            if (factor < 0L || (factor != 0L && result > Long.MAX_VALUE / factor)) return null
            result *= factor
        }
        return result
    }

    /** 单注基本投注金额，单位为分。 */
    private const val BASE_BET_PRICE_FEN = 200L

    /** 大乐透单注追加投注金额，单位为分。 */
    private const val ADDITIONAL_BET_PRICE_FEN = 100L
}

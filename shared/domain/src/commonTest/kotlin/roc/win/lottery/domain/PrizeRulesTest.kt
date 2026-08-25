package roc.win.lottery.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 规则版本边界与完整命中矩阵测试。 */
class PrizeRulesTest {
    /** 被测规则版本选择器。 */
    private val selector = RuleVersionSelector()

    /** 大乐透必须从 26014 期开始启用七奖级规则。 */
    @Test
    fun superLottoRuleStartsAt26014() {
        assertNull(selector.select(LotteryType.SUPER_LOTTO, Issue("26013")))
        assertEquals(
            RuleVersion.DLT_2026_01,
            selector.select(LotteryType.SUPER_LOTTO, Issue("26014")),
        )
    }

    /** 双色球必须从 2026014 期开始启用现行规则。 */
    @Test
    fun doubleColorBallRuleStartsAt2026014() {
        assertNull(selector.select(LotteryType.DOUBLE_COLOR_BALL, Issue("2026013")))
        assertEquals(
            RuleVersion.SSQ_2026_01,
            selector.select(LotteryType.DOUBLE_COLOR_BALL, Issue("2026014")),
        )
    }

    /** 格式错误的期号不能通过字符串比较误选规则。 */
    @Test
    fun malformedIssueDoesNotSelectRule() {
        assertNull(selector.select(LotteryType.SUPER_LOTTO, Issue("260A4")))
        assertNull(selector.select(LotteryType.DOUBLE_COLOR_BALL, Issue("26014")))
    }

    /** 大乐透 0..5 加 0..2 的全部 18 种命中组合必须唯一映射。 */
    @Test
    fun superLottoExhaustiveMatrixMatchesRule() {
        val expected =
            mapOf(
                (5 to 2) to PrizeTierCodes.FIRST,
                (5 to 1) to PrizeTierCodes.SECOND,
                (5 to 0) to PrizeTierCodes.THIRD,
                (4 to 2) to PrizeTierCodes.THIRD,
                (4 to 1) to PrizeTierCodes.FOURTH,
                (4 to 0) to PrizeTierCodes.FIFTH,
                (3 to 2) to PrizeTierCodes.FIFTH,
                (3 to 1) to PrizeTierCodes.SIXTH,
                (2 to 2) to PrizeTierCodes.SIXTH,
                (3 to 0) to PrizeTierCodes.SEVENTH,
                (2 to 1) to PrizeTierCodes.SEVENTH,
                (1 to 2) to PrizeTierCodes.SEVENTH,
                (0 to 2) to PrizeTierCodes.SEVENTH,
            )

        for (primaryHits in 0..5) {
            for (secondaryHits in 0..2) {
                assertEquals(
                    expected[primaryHits to secondaryHits],
                    LotteryPrizeRules.findPrizeTierCode(
                        lotteryType = LotteryType.SUPER_LOTTO,
                        policy = DrawPolicy.STANDARD,
                        primaryHitCount = primaryHits,
                        secondaryHitCount = secondaryHits,
                    ),
                    "大乐透命中组合 $primaryHits+$secondaryHits 映射错误",
                )
            }
        }
    }

    /** 双色球普通状态下 0..6 加 0..1 的全部 14 种组合必须唯一映射。 */
    @Test
    fun doubleColorBallExhaustiveStandardMatrixMatchesRule() {
        val expected =
            mapOf(
                (6 to 1) to PrizeTierCodes.FIRST,
                (6 to 0) to PrizeTierCodes.SECOND,
                (5 to 1) to PrizeTierCodes.THIRD,
                (5 to 0) to PrizeTierCodes.FOURTH,
                (4 to 1) to PrizeTierCodes.FOURTH,
                (4 to 0) to PrizeTierCodes.FIFTH,
                (3 to 1) to PrizeTierCodes.FIFTH,
                (2 to 1) to PrizeTierCodes.SIXTH,
                (1 to 1) to PrizeTierCodes.SIXTH,
                (0 to 1) to PrizeTierCodes.SIXTH,
            )

        for (redHits in 0..6) {
            for (blueHits in 0..1) {
                assertEquals(
                    expected[redHits to blueHits],
                    LotteryPrizeRules.findPrizeTierCode(
                        lotteryType = LotteryType.DOUBLE_COLOR_BALL,
                        policy = DrawPolicy.STANDARD,
                        primaryHitCount = redHits,
                        secondaryHitCount = blueHits,
                    ),
                    "双色球命中组合 $redHits+$blueHits 映射错误",
                )
            }
        }
    }

    /** 特别规定只给三红零蓝增加福运奖，不得覆盖三红一蓝的五等奖。 */
    @Test
    fun fortunePolicyOnlyAddsThreeRedWithoutBlue() {
        assertEquals(
            PrizeTierCodes.FORTUNE,
            LotteryPrizeRules.findPrizeTierCode(
                LotteryType.DOUBLE_COLOR_BALL,
                DrawPolicy.DOUBLE_COLOR_BALL_FORTUNE,
                3,
                0,
            ),
        )
        assertEquals(
            PrizeTierCodes.FIFTH,
            LotteryPrizeRules.findPrizeTierCode(
                LotteryType.DOUBLE_COLOR_BALL,
                DrawPolicy.DOUBLE_COLOR_BALL_FORTUNE,
                3,
                1,
            ),
        )
    }

    /** 非法命中数不得被夹取到合法矩阵。 */
    @Test
    fun illegalHitCountsDoNotWin() {
        assertNull(
            LotteryPrizeRules.findPrizeTierCode(
                LotteryType.SUPER_LOTTO,
                DrawPolicy.STANDARD,
                6,
                2,
            ),
        )
        assertNull(
            LotteryPrizeRules.findPrizeTierCode(
                LotteryType.DOUBLE_COLOR_BALL,
                DrawPolicy.STANDARD,
                6,
                2,
            ),
        )
    }

    /** 大乐透高奖池固定奖档和元级取整追加奖金应完整通过。 */
    @Test
    fun superLottoOfficialHighPoolPayoutsAreComplete() {
        val result =
            SuperLottoPrizeTierValidator.validate(
                superLottoTiers(
                    firstPrizeFen = 1_000_000_000L,
                    firstAdditionalPrizeFen = 800_000_000L,
                    secondPrizeFen = 15_584_600L,
                    secondAdditionalPrizeFen = 12_467_700L,
                    fixedPrizes = highPoolFixedPrizes,
                ),
            )

        assertEquals(SuperLottoPrizeTierValidationStatus.COMPLETE, result.status)
    }

    /** 第 26096 期上下两个方向的元级取整追加奖金都应通过。 */
    @Test
    fun superLotto26096PublishedPayoutsAreComplete() {
        val result =
            SuperLottoPrizeTierValidator.validate(
                superLottoTiers(
                    firstPrizeFen = 656_195_200L,
                    firstAdditionalPrizeFen = 524_956_100L,
                    secondPrizeFen = 5_789_300L,
                    secondAdditionalPrizeFen = 4_631_500L,
                    fixedPrizes = highPoolFixedPrizes,
                ),
            )

        assertEquals(SuperLottoPrizeTierValidationStatus.COMPLETE, result.status)
    }

    /** 大乐透低奖池固定奖档同样属于现行规则合法金额。 */
    @Test
    fun superLottoOfficialLowPoolPayoutsAreComplete() {
        val result =
            SuperLottoPrizeTierValidator.validate(
                superLottoTiers(
                    firstPrizeFen = 5_000_000L,
                    firstAdditionalPrizeFen = 4_000_000L,
                    secondPrizeFen = 3_431_800L,
                    secondAdditionalPrizeFen = 2_745_400L,
                    fixedPrizes = lowPoolFixedPrizes,
                ),
            )

        assertEquals(SuperLottoPrizeTierValidationStatus.COMPLETE, result.status)
    }

    /** 两套固定奖金额混用时必须失败关闭。 */
    @Test
    fun mixedSuperLottoFixedPrizeBandsAreInvalid() {
        val mixedFixedPrizes = highPoolFixedPrizes + (PrizeTierCodes.THIRD to 500_000L)

        val result =
            SuperLottoPrizeTierValidator.validate(
                superLottoTiers(
                    firstPrizeFen = 1_000_000L,
                    firstAdditionalPrizeFen = 800_000L,
                    secondPrizeFen = 500_000L,
                    secondAdditionalPrizeFen = 400_000L,
                    fixedPrizes = mixedFixedPrizes,
                ),
            )

        assertEquals(SuperLottoPrizeTierValidationStatus.INVALID, result.status)
    }

    /** 一等奖追加金额偏离基本奖金 80% 时必须失败关闭。 */
    @Test
    fun invalidSuperLottoAdditionalRatioIsRejected() {
        val result =
            SuperLottoPrizeTierValidator.validate(
                superLottoTiers(
                    firstPrizeFen = 1_000_000L,
                    firstAdditionalPrizeFen = 799_900L,
                    secondPrizeFen = 500_000L,
                    secondAdditionalPrizeFen = 400_000L,
                    fixedPrizes = highPoolFixedPrizes,
                ),
            )

        assertEquals(SuperLottoPrizeTierValidationStatus.INVALID, result.status)
    }

    /** 追加金额超出 80% 相邻整数元范围时仍必须失败关闭。 */
    @Test
    fun additionalPrizeOutsideAdjacentYuanRangeIsRejected() {
        val result =
            SuperLottoPrizeTierValidator.validate(
                superLottoTiers(
                    firstPrizeFen = 1_000_100L,
                    firstAdditionalPrizeFen = 800_200L,
                    secondPrizeFen = 500_000L,
                    secondAdditionalPrizeFen = 400_000L,
                    fixedPrizes = highPoolFixedPrizes,
                ),
            )

        assertEquals(SuperLottoPrizeTierValidationStatus.INVALID, result.status)
    }

    /** 官网尚未给出追加单注奖金时应保留为金额未完整而不是伪造零元。 */
    @Test
    fun missingSuperLottoAdditionalPayoutIsIncomplete() {
        val tiers =
            superLottoTiers(
                firstPrizeFen = 1_000_000L,
                firstAdditionalPrizeFen = 800_000L,
                secondPrizeFen = 500_000L,
                secondAdditionalPrizeFen = 400_000L,
                fixedPrizes = highPoolFixedPrizes,
            ).map { tier ->
                if (tier.code == PrizeTierCodes.FIRST) tier.copy(additionalPrizeFen = null) else tier
            }

        val result = SuperLottoPrizeTierValidator.validate(tiers)

        assertEquals(SuperLottoPrizeTierValidationStatus.INCOMPLETE, result.status)
    }

    /** 大乐透缺少任一基础奖级时必须失败关闭。 */
    @Test
    fun missingSuperLottoBaseTierIsInvalid() {
        val tiers =
            superLottoTiers(
                firstPrizeFen = 1_000_000L,
                firstAdditionalPrizeFen = 800_000L,
                secondPrizeFen = 500_000L,
                secondAdditionalPrizeFen = 400_000L,
                fixedPrizes = highPoolFixedPrizes,
            ).filterNot { it.code == PrizeTierCodes.SEVENTH }

        val result = SuperLottoPrizeTierValidator.validate(tiers)

        assertEquals(SuperLottoPrizeTierValidationStatus.INVALID, result.status)
    }

    /** 大乐透同一基础奖级重复出现时必须失败关闭。 */
    @Test
    fun duplicatedSuperLottoBaseTierIsInvalid() {
        val tiers =
            superLottoTiers(
                firstPrizeFen = 1_000_000L,
                firstAdditionalPrizeFen = 800_000L,
                secondPrizeFen = 500_000L,
                secondAdditionalPrizeFen = 400_000L,
                fixedPrizes = highPoolFixedPrizes,
            )

        val result = SuperLottoPrizeTierValidator.validate(tiers + tiers.first())

        assertEquals(SuperLottoPrizeTierValidationStatus.INVALID, result.status)
    }

    /** 大乐透三至七等奖携带追加奖金字段时必须失败关闭。 */
    @Test
    fun fixedSuperLottoTierWithAdditionalPayoutIsInvalid() {
        val tiers =
            superLottoTiers(
                firstPrizeFen = 1_000_000L,
                firstAdditionalPrizeFen = 800_000L,
                secondPrizeFen = 500_000L,
                secondAdditionalPrizeFen = 400_000L,
                fixedPrizes = highPoolFixedPrizes,
            ).map { tier ->
                if (tier.code == PrizeTierCodes.THIRD) tier.copy(additionalPrizeFen = 100L) else tier
            }

        val result = SuperLottoPrizeTierValidator.validate(tiers)

        assertEquals(SuperLottoPrizeTierValidationStatus.INVALID, result.status)
    }

    /** 创建一套七奖级大乐透奖金数据。 */
    private fun superLottoTiers(
        firstPrizeFen: Long,
        firstAdditionalPrizeFen: Long,
        secondPrizeFen: Long,
        secondAdditionalPrizeFen: Long,
        fixedPrizes: Map<String, Long>,
    ): List<PrizeTier> =
        listOf(
            prizeTier(PrizeTierCodes.FIRST, firstPrizeFen, firstAdditionalPrizeFen),
            prizeTier(PrizeTierCodes.SECOND, secondPrizeFen, secondAdditionalPrizeFen),
        ) + fixedPrizes.map { (code, amount) -> prizeTier(code, amount) }

    /** 创建一个只包含规则校验必要金额的奖级。 */
    private fun prizeTier(
        code: String,
        singlePrizeFen: Long,
        additionalPrizeFen: Long? = null,
    ): PrizeTier =
        PrizeTier(
            code = code,
            displayName = code,
            singlePrizeFen = singlePrizeFen,
            additionalPrizeFen = additionalPrizeFen,
        )

    /** 大乐透低于 8 亿元奖池时的固定奖档。 */
    private val lowPoolFixedPrizes =
        linkedMapOf(
            PrizeTierCodes.THIRD to 500_000L,
            PrizeTierCodes.FOURTH to 30_000L,
            PrizeTierCodes.FIFTH to 15_000L,
            PrizeTierCodes.SIXTH to 1_500L,
            PrizeTierCodes.SEVENTH to 500L,
        )

    /** 大乐透达到 8 亿元奖池时的固定奖档。 */
    private val highPoolFixedPrizes =
        linkedMapOf(
            PrizeTierCodes.THIRD to 666_600L,
            PrizeTierCodes.FOURTH to 38_000L,
            PrizeTierCodes.FIFTH to 20_000L,
            PrizeTierCodes.SIXTH to 1_800L,
            PrizeTierCodes.SEVENTH to 700L,
        )
}

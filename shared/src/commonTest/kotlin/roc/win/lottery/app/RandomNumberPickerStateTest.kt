package roc.win.lottery.app

import roc.win.lottery.domain.CrossPeriodRandomMode
import roc.win.lottery.domain.LotteryRandomNumberGenerator
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.RandomNumberGenerationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** V1.2 随机选号应用状态测试。 */
class RandomNumberPickerStateTest {
    /** 默认状态必须对应一注单期大乐透，并且能够形成合法领域请求。 */
    @Test
    fun defaultStateMatchesTheMinimumSupportedConfiguration() {
        val state = RandomNumberPickerState()

        assertEquals(LotteryType.SUPER_LOTTO, state.lotteryType)
        assertEquals(1, state.periodCount)
        assertEquals(1, state.betsPerPeriod)
        assertEquals(1, state.totalBetCount)
        assertIs<RandomNumberGenerationResult.Success>(
            LotteryRandomNumberGenerator().generate(state.toGenerationRequest()),
        )
    }

    /** 越界数量与大乐透逐期独立模式必须被应用状态直接忽略。 */
    @Test
    fun unsupportedConfigurationChangesAreIgnored() {
        val state = RandomNumberPickerState()

        assertEquals(
            state,
            state.applyConfiguration(RandomNumberPickerAction.ChangePeriodCount(0)),
        )
        assertEquals(
            state,
            state.applyConfiguration(RandomNumberPickerAction.ChangePeriodCount(21)),
        )
        assertEquals(
            state,
            state.applyConfiguration(RandomNumberPickerAction.ChangeBetsPerPeriod(0)),
        )
        assertEquals(
            state,
            state.applyConfiguration(RandomNumberPickerAction.ChangeBetsPerPeriod(11)),
        )
        assertEquals(
            state,
            state.applyConfiguration(
                RandomNumberPickerAction.ChangeCrossPeriodMode(
                    CrossPeriodRandomMode.INDEPENDENT_PER_PERIOD,
                ),
            ),
        )
    }

    /** 修改任一有效配置必须清除旧结果，切回大乐透还要恢复唯一合法跨期模式。 */
    @Test
    fun configurationChangesClearPlanAndNormalizeSuperLottoMode() {
        val doubleColorBallState =
            RandomNumberPickerState()
                .applyConfiguration(
                    RandomNumberPickerAction.ChangeLotteryType(LotteryType.DOUBLE_COLOR_BALL),
                ).applyConfiguration(
                    RandomNumberPickerAction.ChangeCrossPeriodMode(
                        CrossPeriodRandomMode.INDEPENDENT_PER_PERIOD,
                    ),
                )
        val plan =
            assertIs<RandomNumberGenerationResult.Success>(
                LotteryRandomNumberGenerator().generate(doubleColorBallState.toGenerationRequest()),
            ).plan
        val generated = doubleColorBallState.copy(plan = plan)

        val changedCount =
            generated.applyConfiguration(RandomNumberPickerAction.ChangePeriodCount(2))
        assertNull(changedCount.plan)

        val changedLottery =
            generated.applyConfiguration(
                RandomNumberPickerAction.ChangeLotteryType(LotteryType.SUPER_LOTTO),
            )
        assertEquals(LotteryType.SUPER_LOTTO, changedLottery.lotteryType)
        assertEquals(CrossPeriodRandomMode.REUSE_SAME_LINES, changedLottery.crossPeriodMode)
        assertNull(changedLottery.plan)
    }
}

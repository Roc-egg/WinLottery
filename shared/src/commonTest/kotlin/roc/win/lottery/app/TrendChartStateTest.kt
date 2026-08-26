package roc.win.lottery.app

import roc.win.lottery.domain.LotteryTrendArea
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.TrendSampleSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** V1.3 会话内走势图状态测试。 */
class TrendChartStateTest {
    /** 默认状态必须提供明确标记的大乐透前区最近 30 期演示快照。 */
    @Test
    fun defaultStateUsesThirtyDrawDemonstrationSnapshot() {
        val state = TrendChartState.create()

        assertEquals(LotteryType.SUPER_LOTTO, state.lotteryType)
        assertEquals(LotteryTrendArea.PRIMARY, state.area)
        assertEquals(TrendSampleSize.LAST_30, state.sampleSize)
        assertEquals(30, state.snapshot.actualSampleCount)
        assertEquals("00021", state.snapshot.firstIssue.value)
        assertEquals("00050", state.snapshot.lastIssue.value)
        assertTrue(state.isDemonstration)
    }

    /** 彩种、号码区域和样本范围变化必须重新生成口径一致的快照。 */
    @Test
    fun configurationActionsRecalculateSnapshot() {
        val state =
            TrendChartState
                .create()
                .apply(TrendChartAction.ChangeLotteryType(LotteryType.DOUBLE_COLOR_BALL))
                .apply(TrendChartAction.ChangeArea(LotteryTrendArea.SECONDARY))
                .apply(TrendChartAction.ChangeSampleSize(TrendSampleSize.LAST_10))

        assertEquals(LotteryType.DOUBLE_COLOR_BALL, state.snapshot.lotteryType)
        assertEquals(LotteryTrendArea.SECONDARY, state.snapshot.area)
        assertEquals(10, state.snapshot.actualSampleCount)
        assertEquals(16, state.snapshot.statistics.size)
        assertEquals("0000041", state.snapshot.firstIssue.value)
        assertEquals("0000050", state.snapshot.lastIssue.value)
    }
}

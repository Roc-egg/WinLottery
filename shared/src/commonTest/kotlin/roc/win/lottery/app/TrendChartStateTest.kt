package roc.win.lottery.app

import roc.win.lottery.domain.LotteryTrendArea
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.TrendSampleSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** V1.3 会话内走势图状态测试。 */
class TrendChartStateTest {
    /** 默认状态必须等待大乐透前区最近 50 期真实开奖，不包含演示快照。 */
    @Test
    fun defaultStateWaitsForFiftyOfficialDraws() {
        val state = TrendChartState.create()

        assertEquals(TrendWorkspaceView.BASIC_TREND, state.view)
        assertEquals(LotteryType.SUPER_LOTTO, state.lotteryType)
        assertEquals(LotteryTrendArea.PRIMARY, state.area)
        assertEquals(TrendSampleSize.LAST_50, state.sampleSize)
        assertIs<TrendChartContent.Loading>(state.content)
        assertIs<TrendResearchContent.Loading>(state.researchContent)
        assertNull(state.snapshot)
    }

    /** 样本范围必须只暴露产品确认的五个真实开奖档位。 */
    @Test
    fun exposesOnlyOfficialHistorySampleSizes() {
        assertEquals(
            listOf(50, 80, 120, 300, 500),
            TrendSampleSize.entries.map { it.count },
        )
    }
}

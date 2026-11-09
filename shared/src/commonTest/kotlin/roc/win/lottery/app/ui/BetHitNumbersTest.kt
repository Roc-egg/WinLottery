package roc.win.lottery.app.ui

import roc.win.lottery.domain.LotteryType
import kotlin.test.Test
import kotlin.test.assertEquals

/** 每注命中号码读屏说明测试。 */
class BetHitNumbersTest {
    /** 按号码区列出全部号码和命中号码，号码统一升序两位显示。 */
    @Test
    fun describesHitsPerArea() {
        assertEquals(
            "红球 04 08 11 13 16 24 25 33，命中 13 25；蓝球 04 13，未命中",
            betHitDescription(
                lotteryType = LotteryType.DOUBLE_COLOR_BALL,
                primaryNumbers = listOf(33, 4, 8, 11, 13, 16, 24, 25),
                secondaryNumbers = listOf(13, 4),
                drawPrimaryNumbers = listOf(13, 14, 19, 25, 29, 32),
                drawSecondaryNumbers = listOf(12),
            ),
        )
        assertEquals(
            "前区 05 11 17 25 34，命中 05 11 17 25 34；后区 04 06，命中 04",
            betHitDescription(
                lotteryType = LotteryType.SUPER_LOTTO,
                primaryNumbers = listOf(5, 11, 17, 25, 34),
                secondaryNumbers = listOf(4, 6),
                drawPrimaryNumbers = listOf(5, 11, 17, 25, 34),
                drawSecondaryNumbers = listOf(4, 9),
            ),
        )
    }
}

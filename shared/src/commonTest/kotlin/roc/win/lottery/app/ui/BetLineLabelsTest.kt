package roc.win.lottery.app.ui

import roc.win.lottery.domain.PrizeTierCodes
import roc.win.lottery.domain.PrizeTierWin
import kotlin.test.Test
import kotlin.test.assertEquals

/** 投注行编号与复式中奖注数文案测试。 */
class BetLineLabelsTest {
    /** 全票单式时沿用“第 N 注”，含复式时按组编号并标出投注类型。 */
    @Test
    fun titleDependsOnWhetherTicketHasCompound() {
        assertEquals("第 2 注", betLineTitle(1, 1L, isCompound = false, ticketHasCompound = false))
        assertEquals("第 1 组 · 复式 63 注", betLineTitle(0, 63L, isCompound = true, ticketHasCompound = true))
        assertEquals("第 3 组 · 单式", betLineTitle(2, 1L, isCompound = false, ticketHasCompound = true))
    }

    /** 各奖级注数按给定顺序以顿号连接。 */
    @Test
    fun tierWinsAreJoinedInOrder() {
        val wins =
            listOf(
                PrizeTierWin(PrizeTierCodes.FIFTH, 2L, 40_000L),
                PrizeTierWin(PrizeTierCodes.SEVENTH, 6L, 3_000L),
            )

        assertEquals("FIFTH 2 注、SEVENTH 6 注", wins.tierWinsText { it })
    }
}

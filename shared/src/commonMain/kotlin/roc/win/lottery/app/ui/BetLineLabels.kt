package roc.win.lottery.app.ui

import roc.win.lottery.domain.PrizeTierWin

/**
 * 返回投注行编号。
 *
 * 全票都是单式时一行就是一注，沿用“第 N 注”；含复式时一行不再等于一注，改按“第 N 组”编号。
 */
internal fun betLineLabel(
    lineIndex: Int,
    ticketHasCompound: Boolean,
): String = if (ticketHasCompound) "第 ${lineIndex + 1} 组" else "第 ${lineIndex + 1} 注"

/**
 * 返回带投注类型的投注行标题，复式行标出展开后的单式注数。
 *
 * @param lineIndex 投注行零基下标。
 * @param betCount 本行展开后的单式注数。
 * @param isCompound 本行是否为复式。
 * @param ticketHasCompound 全票是否含复式行。
 */
internal fun betLineTitle(
    lineIndex: Int,
    betCount: Long,
    isCompound: Boolean,
    ticketHasCompound: Boolean,
): String {
    val label = betLineLabel(lineIndex, ticketHasCompound)
    return when {
        !ticketHasCompound -> label
        isCompound -> "$label · 复式 $betCount 注"
        else -> "$label · 单式"
    }
}

/** 返回一行中各奖级的中奖注数，如“五等奖 2 注、七等奖 6 注”。 */
internal fun List<PrizeTierWin>.tierWinsText(tierName: (String) -> String): String =
    joinToString(separator = "、") { win -> "${tierName(win.prizeTierCode)} ${win.winningBetCount} 注" }

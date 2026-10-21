package roc.win.lottery.app

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryType
import kotlin.time.Instant

/**
 * 两个彩种固定的每周开奖日和开奖时刻（北京时间），只用于推算某期“最早可能的开奖时间”。
 *
 * 同一年度内期号连续，目标期正好在已知期之后若干期，且每期占用一个不同的开奖日；休市只会推迟开奖，
 * 因此按开奖日顺数得到的时刻是安全下界。官方调整开奖日时必须同步修改本表和 ADR-027。
 */
internal object DrawSchedule {
    /**
     * 根据一期已知开奖推算目标期最早可能的开奖时刻。
     *
     * @param lotteryType 彩种。
     * @param knownIssue 已知已开奖的期号，必须早于目标期。
     * @param knownDrawDate 已知期的开奖日期，`YYYY-MM-DD`。
     * @param target 需要判断的目标期号。
     * @return 最早开奖时刻；期号或日期不合法、已知期不早于目标期时为 `null`。
     */
    fun earliestDrawTime(
        lotteryType: LotteryType,
        knownIssue: Issue,
        knownDrawDate: String,
        target: Issue,
    ): Instant? {
        val drawsAfter = drawsBetween(lotteryType, knownIssue, target) ?: return null
        val date = runCatching { LocalDate.parse(knownDrawDate) }.getOrNull() ?: return null
        val drawDays = lotteryType.drawDays()
        var current = date
        var remaining = drawsAfter
        while (remaining > 0) {
            current = current.plus(1, DateTimeUnit.DAY)
            if (current.dayOfWeek in drawDays) remaining -= 1
        }
        return LocalDateTime(current, lotteryType.drawTime()).toInstant(CHINA_TIME_ZONE)
    }

    /**
     * 返回目标期至少在已知期之后第几个开奖日。
     *
     * 同年度取序号差；已知期属于更早年度时，目标期之前至少还有本年度的前序期次，取目标序号。
     */
    private fun drawsBetween(
        lotteryType: LotteryType,
        known: Issue,
        target: Issue,
    ): Int? {
        val prefixLength =
            when (lotteryType) {
                LotteryType.SUPER_LOTTO -> SUPER_LOTTO_YEAR_LENGTH
                LotteryType.DOUBLE_COLOR_BALL -> DOUBLE_COLOR_BALL_YEAR_LENGTH
            }
        val knownParts = known.split(prefixLength) ?: return null
        val targetParts = target.split(prefixLength) ?: return null
        val count =
            when {
                knownParts.first == targetParts.first -> targetParts.second - knownParts.second
                knownParts.first < targetParts.first -> targetParts.second
                else -> return null
            }
        return count.takeIf { it >= 1 }
    }

    /** 拆分为年度和三位序号；格式不合法时为 `null`。 */
    private fun Issue.split(prefixLength: Int): Pair<Int, Int>? {
        if (value.length != prefixLength + ORDINAL_LENGTH || !value.all(Char::isDigit)) return null
        return value.take(prefixLength).toInt() to value.takeLast(ORDINAL_LENGTH).toInt()
    }

    /** 每周固定开奖日。 */
    private fun LotteryType.drawDays(): Set<DayOfWeek> =
        when (this) {
            LotteryType.SUPER_LOTTO -> setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.SATURDAY)
            LotteryType.DOUBLE_COLOR_BALL -> setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SUNDAY)
        }

    /** 开奖日的开奖时刻。 */
    private fun LotteryType.drawTime(): LocalTime =
        when (this) {
            LotteryType.SUPER_LOTTO -> LocalTime(21, 25)
            LotteryType.DOUBLE_COLOR_BALL -> LocalTime(21, 15)
        }

    /** 大乐透期号中的两位年度。 */
    private const val SUPER_LOTTO_YEAR_LENGTH = 2

    /** 双色球期号中的四位年度。 */
    private const val DOUBLE_COLOR_BALL_YEAR_LENGTH = 4

    /** 年度内三位序号。 */
    private const val ORDINAL_LENGTH = 3

    /** 开奖日程使用的北京时间。 */
    private val CHINA_TIME_ZONE = TimeZone.of("Asia/Shanghai")
}

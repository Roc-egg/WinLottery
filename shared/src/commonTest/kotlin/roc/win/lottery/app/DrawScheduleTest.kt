package roc.win.lottery.app

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/** 验证按固定开奖日推算的最早开奖时刻。 */
class DrawScheduleTest {
    /** 大乐透按周一、三、六 21:25 顺数，序号差决定顺数几个开奖日。 */
    @Test
    fun superLottoCountsMondayWednesdaySaturdayDraws() {
        assertEquals(
            beijing(2026, 8, 15, 21, 25),
            earliest(LotteryType.SUPER_LOTTO, "26091", "2026-08-12", "26092"),
        )
        assertEquals(
            beijing(2026, 8, 19, 21, 25),
            earliest(LotteryType.SUPER_LOTTO, "26091", "2026-08-12", "26094"),
        )
        // 周六之后的下一个开奖日是周一。
        assertEquals(
            beijing(2026, 8, 17, 21, 25),
            earliest(LotteryType.SUPER_LOTTO, "26093", "2026-08-15", "26094"),
        )
    }

    /** 双色球按周二、四、日 21:15 顺数，周日之后是周二。 */
    @Test
    fun doubleColorBallCountsTuesdayThursdaySundayDraws() {
        assertEquals(
            beijing(2026, 8, 11, 21, 15),
            earliest(LotteryType.DOUBLE_COLOR_BALL, "2026091", "2026-08-09", "2026092"),
        )
        assertEquals(
            beijing(2026, 8, 18, 21, 15),
            earliest(LotteryType.DOUBLE_COLOR_BALL, "2026094", "2026-08-16", "2026095"),
        )
    }

    /** 已知期属于上一年度时，目标期至少排在本年度序号对应的开奖日之后。 */
    @Test
    fun earlierYearUsesTargetOrdinalAsMinimumDrawCount() {
        assertEquals(
            beijing(2027, 1, 4, 21, 25),
            earliest(LotteryType.SUPER_LOTTO, "26150", "2026-12-30", "27002"),
        )
        assertEquals(
            beijing(2027, 1, 3, 21, 15),
            earliest(LotteryType.DOUBLE_COLOR_BALL, "2026151", "2026-12-29", "2027002"),
        )
    }

    /** 已知期不早于目标期、日期或期号不合法时无法给出下界。 */
    @Test
    fun invalidReferencesYieldNoBound() {
        assertNull(earliest(LotteryType.SUPER_LOTTO, "26092", "2026-08-15", "26092"))
        assertNull(earliest(LotteryType.SUPER_LOTTO, "26093", "2026-08-15", "26092"))
        assertNull(earliest(LotteryType.SUPER_LOTTO, "27001", "2027-01-02", "26150"))
        assertNull(earliest(LotteryType.SUPER_LOTTO, "26091", "2026/08/12", "26092"))
        assertNull(earliest(LotteryType.SUPER_LOTTO, "2026091", "2026-08-12", "26092"))
        assertNull(earliest(LotteryType.DOUBLE_COLOR_BALL, "2026091", "2026-08-09", "26092"))
    }

    /** 调用被测推算。 */
    private fun earliest(
        lotteryType: LotteryType,
        known: String,
        knownDrawDate: String,
        target: String,
    ): Instant? = DrawSchedule.earliestDrawTime(lotteryType, Issue(known), knownDrawDate, Issue(target))

    /** 返回北京时间对应的时刻。 */
    private fun beijing(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ): Instant = LocalDateTime(year, month, day, hour, minute).toInstant(TimeZone.of("Asia/Shanghai"))
}

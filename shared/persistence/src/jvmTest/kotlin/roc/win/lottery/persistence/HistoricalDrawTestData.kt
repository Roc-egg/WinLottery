package roc.win.lottery.persistence

import roc.win.lottery.domain.HistoricalDraw
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryType
import java.time.LocalDate

/** 生成同一年度内连续、号码合法的大乐透测试开奖，期号从 `24001` 起。 */
internal fun superLottoTestDraws(
    count: Int = HISTORICAL_DRAW_SET_SIZE,
    firstOrdinal: Int = 1,
): List<HistoricalDraw> =
    (firstOrdinal until firstOrdinal + count).map { ordinal ->
        HistoricalDraw(
            lotteryType = LotteryType.SUPER_LOTTO,
            issue = Issue("24${ordinal.toString().padStart(3, '0')}"),
            drawDate = TEST_FIRST_DRAW_DATE.plusDays(ordinal.toLong()).toString(),
            primaryNumbers = (0 until 5).map { offset -> (ordinal + offset) % 35 + 1 }.sorted(),
            secondaryNumbers = listOf(ordinal % 12 + 1, (ordinal + 1) % 12 + 1).sorted(),
        )
    }

/** 生成同一年度内连续、号码合法的双色球测试开奖，期号从 `2024001` 起。 */
internal fun doubleColorBallTestDraws(count: Int = HISTORICAL_DRAW_SET_SIZE): List<HistoricalDraw> =
    (1..count).map { ordinal ->
        HistoricalDraw(
            lotteryType = LotteryType.DOUBLE_COLOR_BALL,
            issue = Issue("2024${ordinal.toString().padStart(3, '0')}"),
            drawDate = TEST_FIRST_DRAW_DATE.plusDays(ordinal.toLong()).toString(),
            primaryNumbers = (0 until 6).map { offset -> (ordinal + offset) % 33 + 1 }.sorted(),
            secondaryNumbers = listOf(ordinal % 16 + 1),
        )
    }

/** 把测试开奖包装为可写入本机缓存的集合。 */
internal fun storedTestDraws(
    draws: List<HistoricalDraw>,
    fetchedAtEpochMillis: Long = 1_000L,
    sourceName: String = "测试官网",
): StoredHistoricalDraws =
    StoredHistoricalDraws(
        lotteryType = draws.first().lotteryType,
        draws = draws,
        sourceName = sourceName,
        fetchedAtEpochMillis = fetchedAtEpochMillis,
    )

/** 测试开奖日期的起点，逐期递增一天。 */
private val TEST_FIRST_DRAW_DATE: LocalDate = LocalDate.of(2024, 1, 1)

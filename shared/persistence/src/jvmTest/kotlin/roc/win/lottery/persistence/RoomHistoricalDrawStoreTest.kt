package roc.win.lottery.persistence

import androidx.room3.executeSQL
import androidx.room3.useWriterConnection
import kotlinx.coroutines.test.runTest
import roc.win.lottery.domain.BetLine
import roc.win.lottery.domain.ConfirmedTicket
import roc.win.lottery.domain.ConfirmedValue
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.TicketFieldOrigin
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 使用真实 JVM SQLite 文件验证本机官方历史开奖缓存。 */
class RoomHistoricalDrawStoreTest {
    /** 写入的集合应完整读回，并在关闭重开数据库后保持不变。 */
    @Test
    fun replaceAndLoadRoundTripAndSurviveReopen() =
        runTest {
            val root = Files.createTempDirectory("winlottery-history-reopen-").toFile()
            val databasePath = File(root, "records.db").absolutePath
            val stored = storedTestDraws(superLottoTestDraws(), fetchedAtEpochMillis = 5_000L)
            try {
                val first = createJvmLocalStorage(databasePath)
                try {
                    first.historicalDrawStore.replace(stored)
                    assertEquals(stored, first.historicalDrawStore.load(LotteryType.SUPER_LOTTO))
                } finally {
                    first.ticketRecordStore.close()
                }
                val reopened = createJvmLocalStorage(databasePath)
                try {
                    assertEquals(stored, reopened.historicalDrawStore.load(LotteryType.SUPER_LOTTO))
                    assertNull(reopened.historicalDrawStore.load(LotteryType.DOUBLE_COLOR_BALL))
                } finally {
                    reopened.ticketRecordStore.close()
                }
            } finally {
                root.deleteRecursively()
            }
        }

    /** 整体替换只影响目标彩种，另一彩种保持原样。 */
    @Test
    fun replaceOverwritesOnlyTargetLottery() =
        runTest {
            withStorage { fixture ->
                val store = fixture.storage.historicalDrawStore
                val doubleColorBall = storedTestDraws(doubleColorBallTestDraws())
                store.replace(storedTestDraws(superLottoTestDraws()))
                store.replace(doubleColorBall)
                val newer = storedTestDraws(superLottoTestDraws(firstOrdinal = 4), fetchedAtEpochMillis = 9_000L)

                store.replace(newer)

                assertEquals(newer, store.load(LotteryType.SUPER_LOTTO))
                assertEquals(doubleColorBall, store.load(LotteryType.DOUBLE_COLOR_BALL))
                assertEquals(HISTORICAL_DRAW_SET_SIZE * 2, fixture.countRows("historical_draws"))
            }
        }

    /** 不合法集合必须在写入前被拒绝，既有集合保持不变。 */
    @Test
    fun replaceRejectsInvalidSetsWithoutWrites() =
        runTest {
            withStorage { fixture ->
                val store = fixture.storage.historicalDrawStore
                val existing = storedTestDraws(superLottoTestDraws())
                store.replace(existing)
                val draws = superLottoTestDraws()
                val invalidSets =
                    listOf(
                        storedTestDraws(superLottoTestDraws(count = HISTORICAL_DRAW_SET_SIZE - 1)),
                        storedTestDraws(draws.filterIndexed { index, _ -> index != 200 } + superLottoTestDraws(1, 501)),
                        storedTestDraws(draws.dropLast(1) + doubleColorBallTestDraws(1)),
                        storedTestDraws(draws.dropLast(1) + draws.last().copy(primaryNumbers = listOf(1, 2, 3, 4, 36))),
                        storedTestDraws(draws, sourceName = " "),
                        storedTestDraws(draws.reversed()),
                    )

                invalidSets.forEach { invalid ->
                    assertFailsWith<IllegalArgumentException> { store.replace(invalid) }
                }

                assertEquals(existing, store.load(LotteryType.SUPER_LOTTO))
            }
        }

    /** 行数缺失、号码损坏或数据版本未知时只清除对应彩种并返回空。 */
    @Test
    fun corruptRowsAreDiscardedOnLoadWithoutTouchingOtherLottery() =
        runTest {
            val corruptions =
                listOf(
                    "DELETE FROM historical_draws WHERE lottery_type = 'SUPER_LOTTO' AND issue = '24200'",
                    "UPDATE historical_draws SET primary_numbers = 'not-json' WHERE issue = '24100'",
                    "UPDATE historical_draw_sets SET data_version = 99 WHERE lottery_type = 'SUPER_LOTTO'",
                    "UPDATE historical_draw_sets SET newest_issue = '24499' WHERE lottery_type = 'SUPER_LOTTO'",
                )
            corruptions.forEach { corruption ->
                withStorage { fixture ->
                    val store = fixture.storage.historicalDrawStore
                    val doubleColorBall = storedTestDraws(doubleColorBallTestDraws())
                    store.replace(storedTestDraws(superLottoTestDraws()))
                    store.replace(doubleColorBall)
                    fixture.database.useWriterConnection { connection -> connection.executeSQL(corruption) }

                    assertNull(store.load(LotteryType.SUPER_LOTTO), corruption)

                    assertEquals(0, fixture.countRows("historical_draw_sets WHERE lottery_type = 'SUPER_LOTTO'"))
                    assertEquals(0, fixture.countRows("historical_draws WHERE lottery_type = 'SUPER_LOTTO'"))
                    assertEquals(doubleColorBall, store.load(LotteryType.DOUBLE_COLOR_BALL))
                }
            }
        }

    /** 只有最新期号仍一致时才允许只更新核对时间。 */
    @Test
    fun updateFetchedAtRequiresMatchingNewestIssue() =
        runTest {
            withStorage { fixture ->
                val store = fixture.storage.historicalDrawStore
                val stored = storedTestDraws(superLottoTestDraws(), fetchedAtEpochMillis = 1_000L)
                store.replace(stored)

                assertFalse(store.updateFetchedAt(LotteryType.SUPER_LOTTO, Issue("24499"), "测试官网", 2_000L))
                assertTrue(store.updateFetchedAt(LotteryType.SUPER_LOTTO, Issue("24500"), "新来源", 3_000L))

                val loaded = assertNotNull(store.load(LotteryType.SUPER_LOTTO))
                assertEquals(3_000L, loaded.fetchedAtEpochMillis)
                assertEquals("新来源", loaded.sourceName)
                assertEquals(stored.draws, loaded.draws)
                assertFalse(store.updateFetchedAt(LotteryType.DOUBLE_COLOR_BALL, Issue("2024500"), "测试官网", 1L))
            }
        }

    /** 清除全部历史开奖不得影响共用数据库中的票据记录。 */
    @Test
    fun clearAllKeepsTicketRecords() =
        runTest {
            withStorage { fixture ->
                val saved =
                    fixture.storage.ticketRecordStore.save(
                        ticket = ticket(),
                        acquisitionSource = TicketAcquisitionSource.CAMERA,
                    )
                fixture.storage.historicalDrawStore.replace(storedTestDraws(superLottoTestDraws()))
                fixture.storage.historicalDrawStore.replace(storedTestDraws(doubleColorBallTestDraws()))

                assertEquals(2, fixture.storage.historicalDrawStore.clearAll())

                assertNull(fixture.storage.historicalDrawStore.load(LotteryType.SUPER_LOTTO))
                assertNull(fixture.storage.historicalDrawStore.load(LotteryType.DOUBLE_COLOR_BALL))
                assertEquals(saved, fixture.storage.ticketRecordStore.findById(saved.id))
            }
        }

    /** 在独立临时目录中打开共用数据库，并保证测试结束后关闭连接和删除文件。 */
    private suspend fun withStorage(block: suspend (StorageFixture) -> Unit) {
        val root = Files.createTempDirectory("winlottery-history-").toFile()
        val database =
            buildWinLotteryDatabase(
                androidx.room3.Room.databaseBuilder<WinLotteryDatabase>(
                    name = File(root, "records.db").absolutePath,
                    factory = WinLotteryDatabaseConstructor::initialize,
                ),
            )
        val fixture = StorageFixture(database, WinLotteryLocalStorage(database))
        try {
            block(fixture)
        } finally {
            fixture.storage.ticketRecordStore.close()
            root.deleteRecursively()
        }
    }

    /** 创建一张合法的大乐透单期票。 */
    private fun ticket(): ConfirmedTicket =
        ConfirmedTicket(
            lotteryType = ConfirmedValue(LotteryType.SUPER_LOTTO, TicketFieldOrigin.USER),
            issue = ConfirmedValue(Issue("26091"), TicketFieldOrigin.USER),
            betLines =
                listOf(
                    BetLine(
                        primaryNumbers = ConfirmedValue(listOf(2, 7, 14, 21, 33), TicketFieldOrigin.USER),
                        secondaryNumbers = ConfirmedValue(listOf(4, 9), TicketFieldOrigin.USER),
                        isAdditional = ConfirmedValue(false, TicketFieldOrigin.USER),
                        originalText = "",
                    ),
                ),
            multiplier = ConfirmedValue(1, TicketFieldOrigin.USER),
            periodCount = ConfirmedValue(1, TicketFieldOrigin.DERIVED),
            paidAmountFen = ConfirmedValue(200L, TicketFieldOrigin.DERIVED),
        )

    /** 同时持有共用数据库及其两个仓库，便于检查数据库级副作用。 */
    private class StorageFixture(
        /** 当前测试独占的 Room 数据库。 */
        val database: WinLotteryDatabase,
        /** 使用该数据库的本机存储。 */
        val storage: WinLotteryLocalStorage,
    ) {
        /** 返回指定表达式的行数。 */
        suspend fun countRows(tableExpression: String): Int =
            database.useWriterConnection { connection ->
                connection.usePrepared("SELECT COUNT(*) FROM $tableExpression") { statement ->
                    check(statement.step()) { "计数查询没有返回结果" }
                    statement.getLong(0).toInt()
                }
            }
    }
}

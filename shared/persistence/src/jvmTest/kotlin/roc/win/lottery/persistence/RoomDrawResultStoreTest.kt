package roc.win.lottery.persistence

import androidx.room3.executeSQL
import androidx.room3.useWriterConnection
import kotlinx.coroutines.test.runTest
import roc.win.lottery.domain.DrawStatus
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryType
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 使用真实 JVM SQLite 文件验证已开奖查询结果缓存。 */
class RoomDrawResultStoreTest {
    /** 写入的结果完整读回，并在关闭重开数据库后保持不变；未保存的期号不返回。 */
    @Test
    fun saveAndLoadRoundTripAndSurviveReopen() =
        runTest {
            val root = Files.createTempDirectory("winlottery-draw-results-").toFile()
            val databasePath = File(root, "records.db").absolutePath
            val superLotto = testDrawResult()
            val doubleColorBall = testDrawResult(LotteryType.DOUBLE_COLOR_BALL, status = DrawStatus.FINAL_NUMBERS)
            try {
                val first = createJvmLocalStorage(databasePath)
                try {
                    assertEquals(superLotto, first.drawResultStore.save(superLotto))
                    assertEquals(doubleColorBall, first.drawResultStore.save(doubleColorBall))
                } finally {
                    first.ticketRecordStore.close()
                }
                val reopened = createJvmLocalStorage(databasePath)
                try {
                    val store = reopened.drawResultStore
                    assertEquals(
                        mapOf(superLotto.issue to superLotto),
                        store.load(LotteryType.SUPER_LOTTO, listOf(superLotto.issue, Issue("26091"))),
                    )
                    assertEquals(
                        mapOf(doubleColorBall.issue to doubleColorBall),
                        store.load(LotteryType.DOUBLE_COLOR_BALL, listOf(doubleColorBall.issue)),
                    )
                    assertEquals(emptyMap(), store.load(LotteryType.SUPER_LOTTO, emptyList()))
                } finally {
                    reopened.ticketRecordStore.close()
                }
            } finally {
                root.deleteRecursively()
            }
        }

    /** 同一期再次保存时整体替换；内容哈希变化才递增本机修订号。 */
    @Test
    fun replacingSameIssueTracksRevisionByContentHash() =
        runTest {
            withStorage { fixture ->
                val store = fixture.storage.drawResultStore
                store.save(testDrawResult(status = DrawStatus.FINAL_NUMBERS, revision = 1))

                val samePayload = store.save(testDrawResult(status = DrawStatus.FINAL_PAYOUT, revision = 1))
                assertEquals(1, samePayload.revision)
                assertEquals(DrawStatus.FINAL_PAYOUT, samePayload.status)

                val revised = store.save(testDrawResult(contentSha256 = "c".repeat(64), revision = 1))
                assertEquals(2, revised.revision)
                assertEquals(revised, store.load(LotteryType.SUPER_LOTTO, listOf(revised.issue))[revised.issue])
                assertEquals(1, fixture.countRows("draw_results"))
            }
        }

    /** 不合法的结果在写入前被拒绝，既有数据保持不变。 */
    @Test
    fun saveRejectsInvalidResultsWithoutWrites() =
        runTest {
            withStorage { fixture ->
                val store = fixture.storage.drawResultStore
                val existing = store.save(testDrawResult())
                val invalidResults =
                    listOf(
                        existing.copy(status = DrawStatus.NOT_PUBLISHED),
                        existing.copy(issue = Issue("2609")),
                        existing.copy(drawDate = "2026/08/12"),
                        existing.copy(primaryNumbers = listOf(2, 7, 14, 21, 36)),
                        existing.copy(secondaryNumbers = listOf(9, 4)),
                        existing.copy(ruleVersion = "SSQ_2026_01"),
                        existing.copy(revision = 0),
                        existing.copy(evidence = existing.evidence.copy(sourceUrl = "about:blank")),
                        existing.copy(evidence = existing.evidence.copy(contentSha256 = "demo-only")),
                        existing.copy(prizeTiers = existing.prizeTiers + existing.prizeTiers.first()),
                        existing.copy(
                            prizeTiers = listOf(existing.prizeTiers.first().copy(singlePrizeFen = -1L)),
                        ),
                    )

                invalidResults.forEach { invalid ->
                    assertFailsWith<IllegalArgumentException> { store.save(invalid) }
                }

                assertEquals(existing, store.load(LotteryType.SUPER_LOTTO, listOf(existing.issue))[existing.issue])
            }
        }

    /** 单行损坏、数据版本未知或规则版本不符时只删除该行，同批其他期次照常返回。 */
    @Test
    fun corruptRowsAreDeletedIndividually() =
        runTest {
            val corruptions =
                listOf(
                    "UPDATE draw_results SET primary_numbers = 'not-json' WHERE issue = '26091'",
                    "UPDATE draw_results SET prize_tiers = '[{\"code\":\"FIRST\"}]' WHERE issue = '26091'",
                    "UPDATE draw_results SET evidence = '{}' WHERE issue = '26091'",
                    "UPDATE draw_results SET status = 'PUBLISHING' WHERE issue = '26091'",
                    "UPDATE draw_results SET policy = 'UNKNOWN' WHERE issue = '26091'",
                    "UPDATE draw_results SET data_version = 99 WHERE issue = '26091'",
                    "UPDATE draw_results SET rule_version = 'DLT_2019_01' WHERE issue = '26091'",
                )
            corruptions.forEach { corruption ->
                withStorage { fixture ->
                    val store = fixture.storage.drawResultStore
                    val kept = store.save(testDrawResult(issue = "26090"))
                    store.save(testDrawResult(issue = "26091"))
                    fixture.database.useWriterConnection { connection -> connection.executeSQL(corruption) }

                    val loaded = store.load(LotteryType.SUPER_LOTTO, listOf(Issue("26090"), Issue("26091")))

                    assertEquals(mapOf(kept.issue to kept), loaded, corruption)
                    assertEquals(0, fixture.countRows("draw_results WHERE issue = '26091'"), corruption)
                    assertEquals(1, fixture.countRows("draw_results WHERE issue = '26090'"), corruption)
                }
            }
        }

    /** 删除单期和全部删除都不影响票据记录和历史开奖。 */
    @Test
    fun deleteAndClearAllKeepTicketsAndHistory() =
        runTest {
            withStorage { fixture ->
                val tickets = fixture.storage.ticketRecordStore
                val saved = tickets.save(ticket = testTicket(), acquisitionSource = TicketAcquisitionSource.CAMERA)
                val history = storedTestDraws(superLottoTestDraws())
                fixture.storage.historicalDrawStore.replace(history)
                val store = fixture.storage.drawResultStore
                store.save(testDrawResult(issue = "26090"))
                store.save(testDrawResult(issue = "26091"))
                store.save(testDrawResult(LotteryType.DOUBLE_COLOR_BALL))

                assertTrue(store.delete(LotteryType.SUPER_LOTTO, Issue("26090")))
                assertFalse(store.delete(LotteryType.SUPER_LOTTO, Issue("26090")))
                assertEquals(2, store.clearAll())

                assertEquals(0, fixture.countRows("draw_results"))
                assertEquals(saved, tickets.findById(saved.id))
                assertEquals(history, assertNotNull(fixture.storage.historicalDrawStore.load(LotteryType.SUPER_LOTTO)))
            }
        }

    /** 在独立临时目录中打开共用数据库，并保证测试结束后关闭连接和删除文件。 */
    private suspend fun withStorage(block: suspend (DrawResultStorageFixture) -> Unit) {
        val root = Files.createTempDirectory("winlottery-draw-results-").toFile()
        val database =
            buildWinLotteryDatabase(
                androidx.room3.Room.databaseBuilder<WinLotteryDatabase>(
                    name = File(root, "records.db").absolutePath,
                    factory = WinLotteryDatabaseConstructor::initialize,
                ),
            )
        val fixture = DrawResultStorageFixture(database, WinLotteryLocalStorage(database))
        try {
            block(fixture)
        } finally {
            fixture.storage.ticketRecordStore.close()
            root.deleteRecursively()
        }
    }

    /** 同时持有共用数据库及其仓库，便于检查数据库级副作用。 */
    private class DrawResultStorageFixture(
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

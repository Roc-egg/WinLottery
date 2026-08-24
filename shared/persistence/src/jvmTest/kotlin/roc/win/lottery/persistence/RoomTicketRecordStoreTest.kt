package roc.win.lottery.persistence

import androidx.room3.Room
import androidx.room3.useReaderConnection
import kotlinx.coroutines.flow.first
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

/** 使用真实 JVM SQLite 文件验证结构化票据持久化边界。 */
class RoomTicketRecordStoreTest {
    /** 合法票据保存后应保留结构化字段、票面顺序和重复行，但不保存 OCR 原文。 */
    @Test
    fun saveAndReadPreserveTicketStructureWithoutOriginalText() =
        runTest {
            withFixture { fixture ->
                val firstLine =
                    betLine(
                        primary = listOf(2, 7, 14, 21, 33),
                        secondary = listOf(4, 9),
                        primaryOrigin = TicketFieldOrigin.OCR,
                    )
                val secondLine =
                    betLine(
                        primary = listOf(1, 8, 16, 24, 35),
                        secondary = listOf(3, 11),
                        secondaryOrigin = TicketFieldOrigin.DERIVED,
                    )
                val duplicateLine = firstLine.copy(originalText = "另一段重复行 OCR 原文")
                val ticket = ticket(listOf(firstLine, secondLine, duplicateLine), paidAmountFen = 600L)

                val saved =
                    fixture.store.save(
                        ticket = ticket,
                        acquisitionSource = TicketAcquisitionSource.SYSTEM_PICKER,
                        displayName = "  周一票  ",
                    )
                val loaded = assertNotNull(fixture.store.findById(saved.id))

                assertEquals("周一票", saved.displayName)
                assertEquals(saved, loaded)
                assertEquals(3, loaded.ticket.betLines.size)
                assertEquals(
                    secondLine.primaryNumbers.value,
                    loaded.ticket.betLines[1]
                        .primaryNumbers.value,
                )
                assertEquals(loaded.ticket.betLines[0], loaded.ticket.betLines[2])
                assertTrue(loaded.ticket.betLines.all { it.originalText.isEmpty() })
                assertEquals(listOf(saved), fixture.store.records.first())
            }
        }

    /** 非法票据必须在写入前被拒绝，主表和投注行表都保持为空。 */
    @Test
    fun invalidTicketIsRejectedWithoutDatabaseWrites() =
        runTest {
            withFixture { fixture ->
                val invalidLine = betLine(primary = listOf(2, 7, 14, 21), secondary = listOf(4, 9))
                val invalidTicket = ticket(listOf(invalidLine), paidAmountFen = 200L)

                assertFailsWith<IllegalArgumentException> {
                    fixture.store.save(invalidTicket, TicketAcquisitionSource.CAMERA)
                }

                assertTrue(
                    fixture.store.records
                        .first()
                        .isEmpty(),
                )
                assertEquals(0, fixture.database.countBetLines())
            }
        }

    /** 关闭并重新打开同一数据库文件后仍应完整读取已保存记录。 */
    @Test
    fun recordSurvivesDatabaseReopen() =
        runTest {
            val root = Files.createTempDirectory("winlottery-persistence-reopen-").toFile()
            val databaseFile = File(root, "records.db")
            var firstStore: TicketRecordStore? = null
            var reopenedStore: TicketRecordStore? = null
            try {
                firstStore = createJvmTicketRecordStore(databaseFile.absolutePath)
                val saved =
                    firstStore.save(
                        ticket = ticket(listOf(betLine()), paidAmountFen = 200L),
                        acquisitionSource = TicketAcquisitionSource.MANUAL_ENTRY,
                    )
                firstStore.close()
                firstStore = null

                reopenedStore = createJvmTicketRecordStore(databaseFile.absolutePath)

                assertEquals(saved, reopenedStore.findById(saved.id))
            } finally {
                firstStore?.close()
                reopenedStore?.close()
                root.deleteRecursively()
            }
        }

    /** 重命名应规范化名称并只修改目标记录。 */
    @Test
    fun renameUpdatesExistingRecord() =
        runTest {
            withFixture { fixture ->
                val saved =
                    fixture.store.save(
                        ticket = ticket(listOf(betLine()), paidAmountFen = 200L),
                        acquisitionSource = TicketAcquisitionSource.CAMERA,
                    )

                assertTrue(fixture.store.rename(saved.id, "  收藏票  "))
                assertFalse(fixture.store.rename(MISSING_RECORD_ID, "不存在"))

                val renamed = assertNotNull(fixture.store.findById(saved.id))
                assertEquals("收藏票", renamed.displayName)
                assertTrue(renamed.updatedAtEpochMillis >= saved.updatedAtEpochMillis)
                assertEquals(saved.ticket, renamed.ticket)
            }
        }

    /** 删除主记录后外键级联必须同步删除其全部投注行。 */
    @Test
    fun deleteCascadesToBetLines() =
        runTest {
            withFixture { fixture ->
                val saved =
                    fixture.store.save(
                        ticket = ticket(listOf(betLine(), secondBetLine()), paidAmountFen = 400L),
                        acquisitionSource = TicketAcquisitionSource.SYSTEM_PICKER,
                    )
                assertEquals(2, fixture.database.countBetLines())

                assertTrue(fixture.store.delete(saved.id))

                assertNull(fixture.store.findById(saved.id))
                assertEquals(0, fixture.database.countBetLines())
                assertFalse(fixture.store.delete(saved.id))
            }
        }

    /** 清空应返回主记录数量，并通过外键级联移除全部投注行。 */
    @Test
    fun clearRemovesAllRecordsAndBetLines() =
        runTest {
            withFixture { fixture ->
                fixture.store.save(
                    ticket = ticket(listOf(betLine()), paidAmountFen = 200L),
                    acquisitionSource = TicketAcquisitionSource.CAMERA,
                )
                fixture.store.save(
                    ticket = ticket(listOf(secondBetLine()), paidAmountFen = 200L),
                    acquisitionSource = TicketAcquisitionSource.MANUAL_ENTRY,
                )

                assertEquals(2, fixture.store.clear())

                assertTrue(
                    fixture.store.records
                        .first()
                        .isEmpty(),
                )
                assertEquals(0, fixture.database.countBetLines())
                assertEquals(0, fixture.store.clear())
            }
        }

    /** 在独立临时目录中打开数据库，并保证测试结束后关闭连接和删除文件。 */
    private suspend fun withFixture(block: suspend (DatabaseFixture) -> Unit) {
        val root = Files.createTempDirectory("winlottery-persistence-").toFile()
        val database = openDatabase(File(root, "records.db"))
        val fixture = DatabaseFixture(database, RoomTicketRecordStore(database))
        try {
            block(fixture)
        } finally {
            fixture.store.close()
            root.deleteRecursively()
        }
    }

    /** 创建使用生产驱动和配置的 JVM Room 数据库。 */
    private fun openDatabase(databaseFile: File): WinLotteryDatabase {
        val builder =
            Room.databaseBuilder<WinLotteryDatabase>(
                name = databaseFile.absolutePath,
                factory = WinLotteryDatabaseConstructor::initialize,
            )
        return buildWinLotteryDatabase(builder)
    }

    /** 返回投注行表中的实际行数，用于验证事务原子性和外键级联。 */
    private suspend fun WinLotteryDatabase.countBetLines(): Int =
        useReaderConnection { connection ->
            connection.usePrepared("SELECT COUNT(*) FROM ticket_bet_lines") { statement ->
                check(statement.step()) { "投注行计数查询没有返回结果" }
                statement.getLong(0).toInt()
            }
        }

    /** 创建一张合法的大乐透单期票。 */
    private fun ticket(
        lines: List<BetLine>,
        paidAmountFen: Long,
    ): ConfirmedTicket =
        ConfirmedTicket(
            lotteryType = confirmed(LotteryType.SUPER_LOTTO, TicketFieldOrigin.OCR),
            issue = confirmed(Issue("26091"), TicketFieldOrigin.OCR),
            betLines = lines,
            multiplier = confirmed(1),
            periodCount = confirmed(1, TicketFieldOrigin.DERIVED),
            paidAmountFen = confirmed(paidAmountFen, TicketFieldOrigin.DERIVED),
        )

    /** 创建第一组合法大乐透单式投注。 */
    private fun betLine(
        primary: List<Int> = listOf(2, 7, 14, 21, 33),
        secondary: List<Int> = listOf(4, 9),
        primaryOrigin: TicketFieldOrigin = TicketFieldOrigin.USER,
        secondaryOrigin: TicketFieldOrigin = TicketFieldOrigin.USER,
    ): BetLine =
        BetLine(
            primaryNumbers = confirmed(primary, primaryOrigin),
            secondaryNumbers = confirmed(secondary, secondaryOrigin),
            isAdditional = confirmed(false),
            originalText = "测试票面 OCR 原文",
        )

    /** 创建第二组合法大乐透单式投注。 */
    private fun secondBetLine(): BetLine =
        betLine(
            primary = listOf(1, 8, 16, 24, 35),
            secondary = listOf(3, 11),
        )

    /** 创建带指定来源的已确认字段。 */
    private fun <T> confirmed(
        value: T,
        origin: TicketFieldOrigin = TicketFieldOrigin.USER,
    ): ConfirmedValue<T> = ConfirmedValue(value, origin)

    /** 同时持有测试数据库及其仓库，便于检查数据库级副作用。 */
    private data class DatabaseFixture(
        /** 当前测试独占的 Room 数据库。 */
        val database: WinLotteryDatabase,
        /** 使用该数据库的票据记录仓库。 */
        val store: TicketRecordStore,
    )

    /** 测试不存在记录时使用的合法 UUID。 */
    private companion object {
        /** 不会由随机生成器产生的全零 UUID。 */
        const val MISSING_RECORD_ID = "00000000-0000-0000-0000-000000000000"
    }
}

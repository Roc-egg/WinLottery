package roc.win.lottery.persistence

import androidx.room3.Room
import androidx.room3.executeSQL
import androidx.room3.useWriterConnection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okio.ByteString.Companion.encodeUtf8
import roc.win.lottery.domain.BetLine
import roc.win.lottery.domain.ConfirmedTicket
import roc.win.lottery.domain.ConfirmedValue
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.TicketFieldOrigin
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 使用真实 JVM SQLite 验证逻辑票据包的完整性、冲突和事务契约。 */
class TicketRecordTransferTest {
    /** 规范化负载必须按 UUID 排序，且相同内容始终产生相同字节和摘要。 */
    @Test
    fun canonicalExportIsStableAndSortedByUuid() {
        val codec = TicketRecordTransferCodec()
        val firstRecord = record(FIRST_RECORD_ID, "第一张票", createdAtEpochMillis = 10L)
        val secondRecord = record(SECOND_RECORD_ID, "第二张票", createdAtEpochMillis = 20L)

        val firstExport = codec.encode(listOf(secondRecord, firstRecord), EXPORTED_AT_EPOCH_MILLIS)
        val repeatedExport = codec.encode(listOf(firstRecord, secondRecord), EXPORTED_AT_EPOCH_MILLIS)
        val text = firstExport.content.decodeToString()
        val payload =
            Json
                .parseToJsonElement(text)
                .jsonObject
                .getValue("payload")
                .toString()

        assertContentEquals(firstExport.content, repeatedExport.content)
        assertEquals(payload.encodeUtf8().sha256().hex(), firstExport.payloadSha256)
        assertEquals(64, firstExport.payloadSha256.length)
        assertTrue(text.indexOf(FIRST_RECORD_ID) < text.indexOf(SECOND_RECORD_ID))
        assertTrue("originalText" !in text)
        assertEquals(2, firstExport.recordCount)
        assertEquals(EXPORTED_AT_EPOCH_MILLIS, firstExport.exportedAtEpochMillis)
    }

    /** 空库导出包必须可被另一数据库完整预检并作为零写入事务导入。 */
    @Test
    fun emptyPackageRoundTripsWithoutWrites() =
        runTest {
            withFixture { source ->
                withFixture { target ->
                    val exported = source.store.exportPackage()

                    val preview = target.store.inspectImport(exported.content)
                    val result = target.store.importPackage(exported.content)

                    assertEquals(0, preview.recordCount)
                    assertEquals(0, preview.importableCount)
                    assertEquals(0, preview.duplicateCount)
                    assertTrue(preview.conflicts.isEmpty())
                    assertEquals(TicketRecordImportResult.Completed(0, 0, 0), result)
                    assertTrue(
                        target.store.records
                            .first()
                            .isEmpty(),
                    )
                }
            }
        }

    /** Android/iOS 共用的逻辑包应保留完整字段，并把再次导入计为重复跳过。 */
    @Test
    fun exportedRecordsRoundTripAndRepeatedImportIsSkipped() =
        runTest {
            withFixture { source ->
                withFixture { target ->
                    source.store.save(
                        ticket = ticket("26091", firstBetLine("不会进入导出包")),
                        acquisitionSource = TicketAcquisitionSource.SYSTEM_PICKER,
                        displayName = "第一张票",
                    )
                    source.store.save(
                        ticket = ticket("26092", secondBetLine()),
                        acquisitionSource = TicketAcquisitionSource.MANUAL_ENTRY,
                        displayName = "第二张票",
                    )
                    val expected =
                        source.store.records
                            .first()
                            .sortedBy(StoredTicketRecord::id)
                    val exported = source.store.exportPackage()

                    val preview = target.store.inspectImport(exported.content)
                    val firstImport = target.store.importPackage(exported.content)
                    val repeatedImport = target.store.importPackage(exported.content)
                    val actual =
                        target.store.records
                            .first()
                            .sortedBy(StoredTicketRecord::id)

                    assertEquals(2, preview.importableCount)
                    assertEquals(0, preview.duplicateCount)
                    assertEquals(TicketRecordImportResult.Completed(2, 0, 0), firstImport)
                    assertEquals(TicketRecordImportResult.Completed(0, 2, 0), repeatedImport)
                    assertEquals(expected, actual)
                    assertTrue(actual.all { record -> record.ticket.betLines.all { it.originalText.isEmpty() } })
                }
            }
        }

    /** UUID 内容冲突必须先整批拒绝，明确保留本机后才导入其余新记录。 */
    @Test
    fun conflictRejectsWholeBatchUntilKeepingLocalIsConfirmed() =
        runTest {
            withFixture { target ->
                val codec = TicketRecordTransferCodec()
                val localRecord = record(FIRST_RECORD_ID, "本机票", createdAtEpochMillis = 10L)
                val conflictingRecord = localRecord.copy(displayName = "导入票", updatedAtEpochMillis = 11L)
                val newRecord = record(SECOND_RECORD_ID, "新票", createdAtEpochMillis = 20L)
                val localPackage = codec.encode(listOf(localRecord), EXPORTED_AT_EPOCH_MILLIS)
                val incomingPackage =
                    codec.encode(
                        listOf(conflictingRecord, newRecord),
                        EXPORTED_AT_EPOCH_MILLIS + 1L,
                    )
                assertIs<TicketRecordImportResult.Completed>(target.store.importPackage(localPackage.content))

                val preview = target.store.inspectImport(incomingPackage.content)
                val rejected = target.store.importPackage(incomingPackage.content)

                assertEquals(2, preview.recordCount)
                assertEquals(1, preview.importableCount)
                assertEquals(0, preview.duplicateCount)
                assertEquals(
                    listOf(TicketRecordImportConflict(FIRST_RECORD_ID, "本机票", "导入票")),
                    preview.conflicts,
                )
                assertIs<TicketRecordImportResult.Conflicts>(rejected)
                assertEquals(listOf(localRecord), target.store.records.first())

                val completed =
                    target.store.importPackage(
                        incomingPackage.content,
                        TicketRecordImportConflictPolicy.KEEP_LOCAL_AND_IMPORT_REST,
                    )
                val recordsById =
                    target.store.records
                        .first()
                        .associateBy(StoredTicketRecord::id)

                assertEquals(TicketRecordImportResult.Completed(1, 0, 1), completed)
                assertEquals(localRecord, recordsById[FIRST_RECORD_ID])
                assertEquals(newRecord, recordsById[SECOND_RECORD_ID])
            }
        }

    /** 未知字段、版本漂移、数量漂移、领域错误和哈希篡改都必须零写入拒绝。 */
    @Test
    fun malformedPackagesAreRejectedWithoutDatabaseWrites() =
        runTest {
            withFixture { target ->
                val validText =
                    TicketRecordTransferCodec()
                        .encode(
                            listOf(record(FIRST_RECORD_ID, "测试票", createdAtEpochMillis = 10L)),
                            EXPORTED_AT_EPOCH_MILLIS,
                        ).content
                        .decodeToString()
                val malformedContents =
                    listOf(
                        validText.replaceFirst("\"format\":", "\"unknownField\":0,\"format\":"),
                        validText.replaceFirst("\"formatVersion\":1", "\"formatVersion\":2"),
                        validText.replaceFirst("\"recordCount\":1", "\"recordCount\":2"),
                        validText.replaceFirst(FIRST_RECORD_ID, "不是合法 UUID"),
                        validText.replaceFirst("\"createdAtEpochMillis\":10", "\"createdAtEpochMillis\":-1"),
                        validText.replaceFirst("\"paidAmountFen\":200", "\"paidAmountFen\":201"),
                        validText.replaceFirst(
                            Regex("\"payloadSha256\":\"[0-9a-f]{64}\""),
                            "\"payloadSha256\":\"${"0".repeat(64)}\"",
                        ),
                    )

                malformedContents.forEach { malformed ->
                    assertFailsWith<TicketRecordImportException> {
                        target.store.inspectImport(malformed.encodeToByteArray())
                    }
                    assertTrue(
                        target.store.records
                            .first()
                            .isEmpty(),
                    )
                }
            }
        }

    /** 5 MiB 和 2,000 条边界必须在解析或写事务前失败关闭。 */
    @Test
    fun oversizedFileAndRecordCountAreRejectedBeforeWrites() =
        runTest {
            withFixture { target ->
                val oversizedFile = ByteArray(MAX_TICKET_RECORD_IMPORT_BYTES + 1)
                val excessiveCount =
                    """{"payload":{"format":"$TICKET_RECORD_TRANSFER_FORMAT","formatVersion":1,"roomSchemaVersion":1,"exportedAtEpochMillis":1,"recordCount":2001,"records":[]},"payloadSha256":"${"0".repeat(
                        64,
                    )}"}"""

                assertFailsWith<TicketRecordImportException> {
                    target.store.inspectImport(oversizedFile)
                }
                assertFailsWith<TicketRecordImportException> {
                    target.store.inspectImport(excessiveCount.encodeToByteArray())
                }
                assertTrue(
                    target.store.records
                        .first()
                        .isEmpty(),
                )
            }
        }

    /** 正好 2,000 条的规范化逻辑包必须仍在当前导入边界内。 */
    @Test
    fun maximumRecordCountIsAccepted() {
        val records =
            (1..MAX_TICKET_RECORD_IMPORT_COUNT).map { sequence ->
                record(
                    id = "00000000-0000-4000-8000-${sequence.toString().padStart(12, '0')}",
                    displayName = "记录 $sequence",
                    createdAtEpochMillis = sequence.toLong(),
                )
            }
        val codec = TicketRecordTransferCodec()

        val exported = codec.encode(records, EXPORTED_AT_EPOCH_MILLIS)
        val decoded = codec.decode(exported.content)

        assertEquals(MAX_TICKET_RECORD_IMPORT_COUNT, decoded.records.size)
        assertTrue(exported.content.size <= MAX_TICKET_RECORD_IMPORT_BYTES)
    }

    /** 事务中任一插入被 SQLite 拒绝时，本批此前写入也必须全部回滚。 */
    @Test
    fun failedInsertRollsBackWholeImportTransaction() =
        runTest {
            withFixture { target ->
                val incoming =
                    TicketRecordTransferCodec().encode(
                        records =
                            listOf(
                                record(FIRST_RECORD_ID, "第一张票", createdAtEpochMillis = 10L),
                                record(SECOND_RECORD_ID, "第二张票", createdAtEpochMillis = 20L),
                            ),
                        exportedAtEpochMillis = EXPORTED_AT_EPOCH_MILLIS,
                    )
                target.database.useWriterConnection { connection ->
                    connection.executeSQL(
                        "CREATE TRIGGER reject_second_import " +
                            "BEFORE INSERT ON ticket_records " +
                            "WHEN NEW.id = '$SECOND_RECORD_ID' " +
                            "BEGIN SELECT RAISE(ABORT, '测试中断'); END",
                    )
                }

                assertFailsWith<Exception> {
                    target.store.importPackage(incoming.content)
                }

                assertTrue(
                    target.store.records
                        .first()
                        .isEmpty(),
                )
            }
        }

    /** 在独立临时目录中打开真实 Room 数据库并负责关闭和清理。 */
    private suspend fun withFixture(block: suspend (TransferDatabaseFixture) -> Unit) {
        val root = Files.createTempDirectory("winlottery-transfer-").toFile()
        val databaseFile = File(root, "records.db")
        val database =
            buildWinLotteryDatabase(
                Room.databaseBuilder<WinLotteryDatabase>(
                    name = databaseFile.absolutePath,
                    factory = WinLotteryDatabaseConstructor::initialize,
                ),
            )
        val fixture = TransferDatabaseFixture(database, RoomTicketRecordStore(database))
        try {
            block(fixture)
        } finally {
            fixture.store.close()
            root.deleteRecursively()
        }
    }

    /** 创建可直接进入逻辑包的固定结构化记录。 */
    private fun record(
        id: String,
        displayName: String,
        createdAtEpochMillis: Long,
    ): StoredTicketRecord =
        StoredTicketRecord(
            id = id,
            displayName = displayName,
            ticket = ticket("26091", firstBetLine()),
            acquisitionSource = TicketAcquisitionSource.SYSTEM_PICKER,
            createdAtEpochMillis = createdAtEpochMillis,
            updatedAtEpochMillis = createdAtEpochMillis,
            dataVersion = CURRENT_TICKET_RECORD_DATA_VERSION,
        )

    /** 创建一张合法的大乐透单期单式票。 */
    private fun ticket(
        issue: String,
        line: BetLine,
    ): ConfirmedTicket =
        ConfirmedTicket(
            lotteryType = confirmed(LotteryType.SUPER_LOTTO, TicketFieldOrigin.OCR),
            issue = confirmed(Issue(issue), TicketFieldOrigin.OCR),
            betLines = listOf(line),
            multiplier = confirmed(1),
            periodCount = confirmed(1, TicketFieldOrigin.DERIVED),
            paidAmountFen = confirmed(200L, TicketFieldOrigin.DERIVED),
        )

    /** 创建第一组合法大乐透单式投注。 */
    private fun firstBetLine(originalText: String = ""): BetLine =
        BetLine(
            primaryNumbers = confirmed(listOf(2, 7, 14, 21, 33)),
            secondaryNumbers = confirmed(listOf(4, 9)),
            isAdditional = confirmed(false),
            originalText = originalText,
        )

    /** 创建第二组合法大乐透单式投注。 */
    private fun secondBetLine(): BetLine =
        BetLine(
            primaryNumbers = confirmed(listOf(1, 8, 16, 24, 35)),
            secondaryNumbers = confirmed(listOf(3, 11)),
            isAdditional = confirmed(false),
            originalText = "",
        )

    /** 创建带指定来源的已确认字段。 */
    private fun <T> confirmed(
        value: T,
        origin: TicketFieldOrigin = TicketFieldOrigin.USER,
    ): ConfirmedValue<T> = ConfirmedValue(value, origin)

    /** 同时持有真实数据库和具体仓库，便于验证事务副作用。 */
    private data class TransferDatabaseFixture(
        /** 当前测试独占的 Room 数据库。 */
        val database: WinLotteryDatabase,
        /** 使用该数据库的具体票据仓库。 */
        val store: RoomTicketRecordStore,
    )

    /** 逻辑包测试使用的固定标识和时间。 */
    private companion object {
        /** UUID 排序靠前的测试记录。 */
        const val FIRST_RECORD_ID = "00000000-0000-4000-8000-000000000001"

        /** UUID 排序靠后的测试记录。 */
        const val SECOND_RECORD_ID = "00000000-0000-4000-8000-000000000002"

        /** 规范化导出使用的固定 Unix 毫秒。 */
        const val EXPORTED_AT_EPOCH_MILLIS = 1_787_500_800_000L
    }
}

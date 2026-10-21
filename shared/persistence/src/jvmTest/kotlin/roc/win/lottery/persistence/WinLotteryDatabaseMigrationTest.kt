package roc.win.lottery.persistence

import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryType
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull

/** 按已提交的模式快照构造真实旧版数据库，验证显式迁移到当前版本不丢失既有数据。 */
class WinLotteryDatabaseMigrationTest {
    /** v1 数据库连续升级后票据完整保留，新增的历史开奖表和开奖结果表都可以正常读写。 */
    @Test
    fun migratingVersionOneDatabaseKeepsTicketsAndAddsHistoryTables() =
        runTest {
            withLegacyDatabase(version = 1) { databasePath ->
                val storage = createJvmLocalStorage(databasePath)
                try {
                    val record = assertNotNull(storage.ticketRecordStore.findById(LEGACY_TICKET_ID))
                    assertEquals("迁移前票据", record.displayName)
                    assertEquals(Issue("26091"), record.ticket.issue.value)
                    assertEquals(
                        listOf(2, 7, 14, 21, 33),
                        record.ticket.betLines
                            .single()
                            .primaryNumbers.value,
                    )
                    assertEquals(TicketAcquisitionSource.CAMERA, record.acquisitionSource)

                    val stored = storedTestDraws(superLottoTestDraws())
                    storage.historicalDrawStore.replace(stored)
                    assertEquals(stored, storage.historicalDrawStore.load(LotteryType.SUPER_LOTTO))
                    val drawResult = testDrawResult()
                    storage.drawResultStore.save(drawResult)
                    assertEquals(
                        drawResult,
                        storage.drawResultStore.load(
                            LotteryType.SUPER_LOTTO,
                            listOf(drawResult.issue),
                        )[drawResult.issue],
                    )
                } finally {
                    storage.ticketRecordStore.close()
                }
                assertEquals(WIN_LOTTERY_DATABASE_VERSION, readUserVersion(databasePath))
            }
        }

    /** v2 数据库升级后票据和本机历史开奖都完整保留，开奖结果表可以正常读写。 */
    @Test
    fun migratingVersionTwoDatabaseKeepsTicketsAndHistory() =
        runTest {
            val history = storedTestDraws(superLottoTestDraws(), fetchedAtEpochMillis = 7_000L)
            withLegacyDatabase(version = 2, history = history) { databasePath ->
                val storage = createJvmLocalStorage(databasePath)
                try {
                    assertEquals(
                        "迁移前票据",
                        assertNotNull(storage.ticketRecordStore.findById(LEGACY_TICKET_ID)).displayName,
                    )
                    assertEquals(history, storage.historicalDrawStore.load(LotteryType.SUPER_LOTTO))
                    val drawResult = testDrawResult(LotteryType.DOUBLE_COLOR_BALL)
                    storage.drawResultStore.save(drawResult)
                    assertEquals(
                        mapOf(drawResult.issue to drawResult),
                        storage.drawResultStore.load(LotteryType.DOUBLE_COLOR_BALL, listOf(drawResult.issue)),
                    )
                } finally {
                    storage.ticketRecordStore.close()
                }
                assertEquals(WIN_LOTTERY_DATABASE_VERSION, readUserVersion(databasePath))
            }
        }

    /** 每个迁移语句都必须与 Room 导出的模式快照逐字一致，且既有表在后续版本中保持不变。 */
    @Test
    fun migrationSqlMatchesExportedSchema() {
        val version2 = schemaEntities(2)
        val current = schemaEntities(WIN_LOTTERY_DATABASE_VERSION)

        assertEquals(CREATE_HISTORICAL_DRAW_SETS_SQL, version2.getValue("historical_draw_sets"))
        assertEquals(CREATE_HISTORICAL_DRAWS_SQL, version2.getValue("historical_draws"))
        assertEquals(CREATE_DRAW_RESULTS_SQL, current.getValue("draw_results"))
        assertEquals(schemaEntities(1), version2 - setOf("historical_draw_sets", "historical_draws"))
        assertEquals(version2, current - "draw_results")
    }

    /** 缺少迁移时必须打开失败且不删除票据，随后使用正式迁移仍能完整读取。 */
    @Test
    fun missingMigrationFailsClosedWithoutDroppingTickets() =
        runTest {
            for (version in 1 until WIN_LOTTERY_DATABASE_VERSION) {
                assertMissingMigrationFailsClosed(version)
            }
        }

    /** 从指定旧版本打开时缺少迁移会失败，票据仍然保留。 */
    private suspend fun assertMissingMigrationFailsClosed(version: Int) {
        withLegacyDatabase(version) { databasePath ->
            val withoutMigrations =
                Room
                    .databaseBuilder<WinLotteryDatabase>(
                        name = databasePath,
                        factory = WinLotteryDatabaseConstructor::initialize,
                    ).setDriver(BundledSQLiteDriver())
                    .build()
            try {
                assertFails { withoutMigrations.ticketRecordDao().findAll() }
            } finally {
                withoutMigrations.close()
            }

            val storage = createJvmLocalStorage(databasePath)
            try {
                assertNotNull(storage.ticketRecordStore.findById(LEGACY_TICKET_ID))
            } finally {
                storage.ticketRecordStore.close()
            }
        }
    }

    /**
     * 按指定版本的模式快照建库并写入一张合法票据，测试结束后删除文件。
     *
     * @param history v2 及以上可选写入的一组本机历史开奖。
     */
    private suspend fun withLegacyDatabase(
        version: Int,
        history: StoredHistoricalDraws? = null,
        block: suspend (String) -> Unit,
    ) {
        val root = Files.createTempDirectory("winlottery-migration-").toFile()
        val databasePath = File(root, "records.db").absolutePath
        try {
            withConnection(databasePath) { connection ->
                val database = schemaDatabase(version)
                database.getValue("entities").jsonArray.forEach { entityElement ->
                    val entity = entityElement.jsonObject
                    val tableName = entity.getValue("tableName").jsonPrimitive.content
                    connection.execSQL(entity.createSql(tableName))
                    entity["indices"]?.jsonArray?.forEach { index ->
                        connection.execSQL(index.jsonObject.createSql(tableName))
                    }
                }
                database.getValue("setupQueries").jsonArray.forEach { query ->
                    connection.execSQL(query.jsonPrimitive.content)
                }
                connection.execSQL(LEGACY_TICKET_RECORD_SQL)
                connection.execSQL(LEGACY_TICKET_BET_LINE_SQL)
                history?.let { connection.insertLegacyHistory(it) }
                connection.execSQL("PRAGMA user_version = $version")
            }
            assertEquals(version, readUserVersion(databasePath))
            block(databasePath)
        } finally {
            root.deleteRecursively()
        }
    }

    /** 按 v2 表结构直接写入一组历史开奖，模拟升级前已保存的数据。 */
    private fun SQLiteConnection.insertLegacyHistory(history: StoredHistoricalDraws) {
        val type = history.lotteryType.name
        val newest =
            history.draws
                .last()
                .issue.value
        execSQL(
            "INSERT INTO historical_draw_sets VALUES ('$type', '${history.sourceName}', " +
                "${history.fetchedAtEpochMillis}, ${history.draws.size}, '$newest', 1)",
        )
        history.draws.forEach { draw ->
            execSQL(
                "INSERT INTO historical_draws VALUES ('$type', '${draw.issue.value}', '${draw.drawDate}', " +
                    "'${draw.primaryNumbers.joinToString(",", "[", "]")}', " +
                    "'${draw.secondaryNumbers.joinToString(",", "[", "]")}')",
            )
        }
    }

    /** 读取 SQLite 文件当前的模式版本。 */
    private fun readUserVersion(databasePath: String): Int =
        withConnection(databasePath) { connection ->
            connection.prepare("PRAGMA user_version").use { statement ->
                check(statement.step()) { "模式版本查询没有返回结果" }
                statement.getLong(0).toInt()
            }
        }

    /** 使用生产相同的捆绑驱动打开原始连接。 */
    private fun <T> withConnection(
        databasePath: String,
        block: (SQLiteConnection) -> T,
    ): T {
        val connection = BundledSQLiteDriver().open(databasePath)
        return try {
            block(connection)
        } finally {
            connection.close()
        }
    }

    /** 返回指定版本模式快照中各表的建表语句。 */
    private fun schemaEntities(version: Int): Map<String, String> =
        schemaDatabase(version).getValue("entities").jsonArray.associate { entityElement ->
            val entity = entityElement.jsonObject
            val tableName = entity.getValue("tableName").jsonPrimitive.content
            tableName to entity.createSql(tableName)
        }

    /** 读取已提交的模式快照；测试工作目录为模块目录。 */
    private fun schemaDatabase(version: Int): JsonObject {
        val schema =
            Json
                .parseToJsonElement(File(SCHEMA_DIRECTORY, "$version.json").readText())
                .jsonObject
        assertEquals(1, schema.getValue("formatVersion").jsonPrimitive.int)
        return schema.getValue("database").jsonObject
    }

    /** 把快照中的表名占位符替换为实际表名。 */
    private fun JsonObject.createSql(tableName: String): String =
        getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", tableName)

    /** 迁移测试固定数据。 */
    private companion object {
        /** 已提交模式快照所在目录。 */
        const val SCHEMA_DIRECTORY = "schemas/roc.win.lottery.persistence.WinLotteryDatabase"

        /** v1 库中预置票据的 UUID。 */
        const val LEGACY_TICKET_ID = "10000000-0000-4000-8000-000000000001"

        /** 按 v1 表结构写入的一张合法单期大乐透票。 */
        const val LEGACY_TICKET_RECORD_SQL =
            "INSERT INTO ticket_records (id, display_name, lottery_type, first_issue, period_count, multiplier, " +
                "paid_amount_fen, acquisition_source, lottery_type_origin, issue_origin, period_count_origin, " +
                "multiplier_origin, paid_amount_origin, created_at_epoch_millis, updated_at_epoch_millis, " +
                "data_version) " +
                "VALUES ('$LEGACY_TICKET_ID', '迁移前票据', 'SUPER_LOTTO', '26091', 1, 1, 200, 'CAMERA', 'USER', " +
                "'USER', 'DERIVED', 'USER', 'DERIVED', 1000, 1000, 1)"

        /** 预置票据的唯一一注号码。 */
        const val LEGACY_TICKET_BET_LINE_SQL =
            "INSERT INTO ticket_bet_lines (record_id, line_index, primary_numbers, secondary_numbers, is_additional, " +
                "primary_numbers_origin, secondary_numbers_origin, is_additional_origin) " +
                "VALUES ('$LEGACY_TICKET_ID', 0, '[2,7,14,21,33]', '[4,9]', 0, 'USER', 'USER', 'USER')"
    }
}

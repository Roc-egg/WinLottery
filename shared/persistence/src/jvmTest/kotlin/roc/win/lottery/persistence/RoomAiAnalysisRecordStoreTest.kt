package roc.win.lottery.persistence

import androidx.room3.executeSQL
import androidx.room3.useWriterConnection
import kotlinx.coroutines.test.runTest
import roc.win.lottery.domain.GeneratedNumberLine
import roc.win.lottery.domain.LotteryType
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 本机 AI 分析结果仓库测试。 */
class RoomAiAnalysisRecordStoreTest {
    /** 结果完整读回并在重开数据库后保持；每个彩种各存一条，新结果替换同彩种旧结果。 */
    @Test
    fun savesLatestRecordPerLotteryTypeAndSurvivesReopen() =
        runTest {
            val root = Files.createTempDirectory("winlottery-ai-records-").toFile()
            val databasePath = File(root, "records.db").absolutePath
            try {
                val first = testAiAnalysisRecord(generatedAtEpochMillis = 1_000L, summary = "旧结果")
                val latest = testAiAnalysisRecord(generatedAtEpochMillis = 2_000L, summary = "新结果")
                val doubleColorBall = testAiAnalysisRecord(LotteryType.DOUBLE_COLOR_BALL)
                createJvmLocalStorage(databasePath).let { storage ->
                    try {
                        assertNull(storage.aiAnalysisRecordStore.load(LotteryType.SUPER_LOTTO))
                        storage.aiAnalysisRecordStore.save(first)
                        storage.aiAnalysisRecordStore.save(latest)
                        storage.aiAnalysisRecordStore.save(doubleColorBall)
                    } finally {
                        storage.ticketRecordStore.close()
                    }
                }
                createJvmLocalStorage(databasePath).let { storage ->
                    try {
                        assertEquals(latest, storage.aiAnalysisRecordStore.load(LotteryType.SUPER_LOTTO))
                        assertEquals(doubleColorBall, storage.aiAnalysisRecordStore.load(LotteryType.DOUBLE_COLOR_BALL))
                    } finally {
                        storage.ticketRecordStore.close()
                    }
                }
            } finally {
                root.deleteRecursively()
            }
        }

    /** 删除只影响指定彩种，重复删除返回没有删除数据。 */
    @Test
    fun deleteRemovesOnlyRequestedLotteryType() =
        runTest {
            withStorage { _, store ->
                store.save(testAiAnalysisRecord())
                store.save(testAiAnalysisRecord(LotteryType.DOUBLE_COLOR_BALL))
                assertTrue(store.delete(LotteryType.SUPER_LOTTO))
                assertFalse(store.delete(LotteryType.SUPER_LOTTO))
                assertNull(store.load(LotteryType.SUPER_LOTTO))
                assertEquals(
                    testAiAnalysisRecord(LotteryType.DOUBLE_COLOR_BALL),
                    store.load(LotteryType.DOUBLE_COLOR_BALL),
                )
            }
        }

    /** 不合法的结果拒绝写入，不覆盖已有结果。 */
    @Test
    fun invalidRecordIsRejected() =
        runTest {
            withStorage { _, store ->
                val saved = testAiAnalysisRecord()
                store.save(saved)
                val outOfRange =
                    saved.copy(
                        analysis =
                            saved.analysis.copy(
                                candidates =
                                    listOf(
                                        saved.analysis.candidates
                                            .first()
                                            .copy(line = GeneratedNumberLine(listOf(1, 2, 3, 4, 36), listOf(1, 2))),
                                    ),
                            ),
                    )
                val invalidRecords =
                    listOf(
                        outOfRange,
                        saved.copy(providerName = " "),
                        saved.copy(firstIssue = "26151"),
                        saved.copy(analysis = saved.analysis.copy(summary = "")),
                        saved.copy(analysis = saved.analysis.copy(lotteryType = LotteryType.HAPPY_8)),
                    )
                invalidRecords.forEach { invalid ->
                    assertFailsWith<IllegalArgumentException> { store.save(invalid) }
                }
                assertEquals(saved, store.load(LotteryType.SUPER_LOTTO))
            }
        }

    /** 读取到损坏或版本不符的行时删除该行并视为没有保存，其他彩种不受影响。 */
    @Test
    fun corruptedRowIsDroppedOnLoad() =
        runTest {
            val corruptions =
                listOf(
                    "UPDATE ai_analysis_records SET candidates = '[' WHERE lottery_type = 'SUPER_LOTTO'",
                    "UPDATE ai_analysis_records SET data_version = 99 WHERE lottery_type = 'SUPER_LOTTO'",
                    "UPDATE ai_analysis_records SET candidates = '[{\"primaryNumbers\":[1,1,2,3,4]," +
                        "\"secondaryNumbers\":[1,2],\"reason\":\"重复号码\"}]' WHERE lottery_type = 'SUPER_LOTTO'",
                )
            corruptions.forEach { corruption ->
                withStorage { database, store ->
                    store.save(testAiAnalysisRecord())
                    store.save(testAiAnalysisRecord(LotteryType.DOUBLE_COLOR_BALL))
                    database.useWriterConnection { connection -> connection.executeSQL(corruption) }

                    assertNull(store.load(LotteryType.SUPER_LOTTO), corruption)
                    assertFalse(store.delete(LotteryType.SUPER_LOTTO), corruption)
                    assertEquals(
                        testAiAnalysisRecord(LotteryType.DOUBLE_COLOR_BALL),
                        store.load(LotteryType.DOUBLE_COLOR_BALL),
                        corruption,
                    )
                }
            }
        }

    /** 在独立临时目录中打开共用数据库，并保证测试结束后关闭连接和删除文件。 */
    private suspend fun withStorage(block: suspend (WinLotteryDatabase, AiAnalysisRecordStore) -> Unit) {
        val root = Files.createTempDirectory("winlottery-ai-records-").toFile()
        val database =
            buildWinLotteryDatabase(
                androidx.room3.Room.databaseBuilder<WinLotteryDatabase>(
                    name = File(root, "records.db").absolutePath,
                    factory = WinLotteryDatabaseConstructor::initialize,
                ),
            )
        val storage = WinLotteryLocalStorage(database)
        try {
            block(database, storage.aiAnalysisRecordStore)
        } finally {
            storage.ticketRecordStore.close()
            root.deleteRecursively()
        }
    }
}

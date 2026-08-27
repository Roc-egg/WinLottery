package roc.win.lottery.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** V1.4 AI 历史开奖快照领域契约测试。 */
class AiHistorySnapshotBuilderTest {
    /** 相同开奖集合不受输入顺序影响，并只截取用户选择的最近范围。 */
    @Test
    fun buildsStableSnapshotFromLatestRequestedDraws() {
        val chronological = superLottoDraws(60)
        val first = success(chronological)
        val second = success(chronological.reversed())

        assertEquals(AiAnalysisProtocol.SNAPSHOT_SCHEMA_VERSION, first.schemaVersion)
        assertEquals(TrendSampleSize.LAST_50, first.sampleSize)
        assertEquals(50, first.sampleCount)
        assertEquals("26011", first.firstIssue.value)
        assertEquals("26060", first.lastIssue.value)
        assertEquals(first.canonicalJson, second.canonicalJson)
        assertEquals(first.id, second.id)
        assertEquals(first.canonicalJson.encodeToByteArray().size, first.byteCount)
        assertTrue(Regex("^[0-9a-f]{64}$").matches(first.id))
        assertTrue(first.canonicalJson.startsWith("{\"schemaVersion\":1,\"lotteryType\":\"SUPER_LOTTO\""))
        assertTrue("\"sampleCount\":50" in first.canonicalJson)
        assertTrue("source" !in first.canonicalJson)
        assertTrue("fetchedAt" !in first.canonicalJson)
    }

    /** 任一期规范化开奖变化都必须改变快照标识。 */
    @Test
    fun changesSnapshotIdWhenDrawContentChanges() {
        val draws = superLottoDraws(50)
        val changed =
            draws.dropLast(1) +
                draws.last().copy(primaryNumbers = listOf(1, 2, 3, 4, 6))

        assertNotEquals(success(draws).id, success(changed).id)
    }

    /** 构建后修改调用方号码集合不得改变快照内容或既有摘要。 */
    @Test
    fun isolatesSnapshotFromMutableInputLists() {
        val mutablePrimary = mutableListOf(1, 2, 3, 4, 5)
        val draws =
            superLottoDraws(49) +
                superLottoDraw(50).copy(primaryNumbers = mutablePrimary)
        val snapshot = success(draws)
        val originalId = snapshot.id
        val originalJson = snapshot.canonicalJson

        mutablePrimary[0] = 6

        assertEquals(listOf(1, 2, 3, 4, 5), snapshot.draws.last().primaryNumbers)
        assertEquals(originalId, snapshot.id)
        assertEquals(originalJson, snapshot.canonicalJson)
    }

    /** 五档冻结样本都必须能从 500 期输入形成精确数量且不超过快照上限。 */
    @Test
    fun supportsAllFrozenSampleSizesWithinByteLimit() {
        val draws = superLottoDrawsAcrossYears(500)

        TrendSampleSize.entries.forEach { sampleSize ->
            val snapshot =
                assertIs<AiHistorySnapshotBuildResult.Success>(
                    builder.build(LotteryType.SUPER_LOTTO, sampleSize, draws),
                ).snapshot

            assertEquals(sampleSize.count, snapshot.sampleCount)
            assertTrue(snapshot.byteCount <= AiAnalysisProtocol.MAX_SNAPSHOT_BYTES)
        }
    }

    /** 少于用户选择期数时必须失败，不能缩小样本冒充成功。 */
    @Test
    fun rejectsInsufficientHistory() {
        val result = builder.build(LotteryType.SUPER_LOTTO, TrendSampleSize.LAST_50, superLottoDraws(49))

        assertEquals(
            AiHistorySnapshotFailureCode.INSUFFICIENT_HISTORY,
            assertIs<AiHistorySnapshotBuildResult.Invalid>(result).code,
        )
    }

    /** 混入其他彩种或非法号码时必须复用 V1.3 完整性校验失败关闭。 */
    @Test
    fun rejectsInvalidHistoricalDraws() {
        val mixed =
            superLottoDraws(49) +
                HistoricalDraw(
                    lotteryType = LotteryType.DOUBLE_COLOR_BALL,
                    issue = Issue("2026050"),
                    drawDate = "2026-08-10",
                    primaryNumbers = listOf(1, 2, 3, 4, 5, 6),
                    secondaryNumbers = listOf(7),
                )
        val invalidNumbers =
            superLottoDraws(49) +
                superLottoDraw(50).copy(primaryNumbers = listOf(1, 2, 3, 4, 36))

        assertEquals(AiHistorySnapshotFailureCode.INVALID_HISTORY, invalid(mixed).code)
        assertEquals(AiHistorySnapshotFailureCode.INVALID_HISTORY, invalid(invalidNumbers).code)
    }

    /** 样本内可证明的期号空档必须阻止 AI 请求。 */
    @Test
    fun rejectsProvableIssueGap() {
        val draws = superLottoDraws(51).filterNot { draw -> draw.issue.value == "26025" }

        assertEquals(AiHistorySnapshotFailureCode.ISSUE_GAP, invalid(draws).code)
    }

    /** 非规范化开奖日期不得进入稳定 JSON。 */
    @Test
    fun rejectsNonCanonicalDrawDate() {
        val wrongFormat =
            superLottoDraws(49) +
                superLottoDraw(50).copy(drawDate = "2026/08/10")
        val impossibleDate =
            superLottoDraws(49) +
                superLottoDraw(50).copy(drawDate = "2026-02-30")

        assertEquals(AiHistorySnapshotFailureCode.INVALID_DRAW_DATE, invalid(wrongFormat).code)
        assertEquals(AiHistorySnapshotFailureCode.INVALID_DRAW_DATE, invalid(impossibleDate).code)
    }

    /** 提取成功快照。 */
    private fun success(draws: List<HistoricalDraw>): AiHistorySnapshot =
        assertIs<AiHistorySnapshotBuildResult.Success>(
            builder.build(
                lotteryType = LotteryType.SUPER_LOTTO,
                sampleSize = TrendSampleSize.LAST_50,
                draws = draws,
            ),
        ).snapshot

    /** 提取失败状态。 */
    private fun invalid(draws: List<HistoricalDraw>): AiHistorySnapshotBuildResult.Invalid =
        assertIs<AiHistorySnapshotBuildResult.Invalid>(
            builder.build(
                lotteryType = LotteryType.SUPER_LOTTO,
                sampleSize = TrendSampleSize.LAST_50,
                draws = draws,
            ),
        )

    /** 构造连续的大乐透历史开奖。 */
    private fun superLottoDraws(count: Int): List<HistoricalDraw> = (1..count).map(::superLottoDraw)

    /** 构造跨年度且每个年度内部连续的大乐透历史开奖。 */
    private fun superLottoDrawsAcrossYears(count: Int): List<HistoricalDraw> =
        (0 until count).map { index ->
            val year = 23 + index / 125
            val ordinal = index % 125 + 1
            superLottoDraw(ordinal).copy(
                issue = Issue("$year${ordinal.toString().padStart(3, '0')}"),
                drawDate = "20$year-08-10",
            )
        }

    /** 构造单期合法大乐透历史开奖。 */
    private fun superLottoDraw(ordinal: Int): HistoricalDraw =
        HistoricalDraw(
            lotteryType = LotteryType.SUPER_LOTTO,
            issue = Issue("26${ordinal.toString().padStart(3, '0')}"),
            drawDate = "2026-08-10",
            primaryNumbers = listOf(1, 2, 3, 4, 5),
            secondaryNumbers = listOf(1, 2),
        )

    /** 被测快照构建器。 */
    private val builder = AiHistorySnapshotBuilder()
}

package roc.win.lottery.data

import roc.win.lottery.domain.DrawPolicy
import roc.win.lottery.domain.PrizeTierCodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 双色球列表主源、详情辅助源与特别规定契约测试。 */
class DoubleColorBallSourceAdapterTest {
    /** 普通期主响应应映射六个基础奖级和确定金额。 */
    @Test
    fun validStandardMainResponseIsNormalized() {
        val snapshot =
            assertIs<SourceParseResult.Success<MainDrawSnapshot>>(
                parseMain(DrawContractFixtures.doubleColorBallMain()),
            ).value

        assertEquals(DrawPolicy.STANDARD, snapshot.policy)
        assertEquals(listOf(2, 13, 14, 16, 20, 24), snapshot.primaryNumbers)
        assertEquals(listOf(5), snapshot.secondaryNumbers)
        assertEquals(6, snapshot.prizeTiers.size)
        assertTrue(snapshot.payoutFieldsComplete)
        assertEquals(300_000L, snapshot.prizeTiers.single { it.code == PrizeTierCodes.THIRD }.singlePrizeFen)
    }

    /** 特别规定期间必须从主响应显式生成福运奖。 */
    @Test
    fun fortunePeriodUsesDedicatedFields() {
        val raw =
            DrawContractFixtures.doubleColorBallMain(
                issue = "2026014",
                fortuneCount = "9373173",
                fortuneMoney = "5",
            )
        val snapshot =
            assertIs<SourceParseResult.Success<MainDrawSnapshot>>(
                DoubleColorBallSourceAdapter.parseMain(raw, "2026014", MAIN_URL),
            ).value

        assertEquals(DrawPolicy.DOUBLE_COLOR_BALL_FORTUNE, snapshot.policy)
        val fortune = snapshot.prizeTiers.single { it.code == PrizeTierCodes.FORTUNE }
        assertEquals(9373173L, fortune.winnerCount)
        assertEquals(500L, fortune.singlePrizeFen)
    }

    /** 特别规定期间缺少福运奖字段时必须停留在发布中。 */
    @Test
    fun missingFortuneFieldsArePublishing() {
        val raw = DrawContractFixtures.doubleColorBallMain(issue = "2026014")

        assertIs<SourceParseResult.Publishing>(
            DoubleColorBallSourceAdapter.parseMain(raw, "2026014", MAIN_URL),
        )
    }

    /** 最新固化期应按普通状态解析，不得继续停留在发布中。 */
    @Test
    fun latestConfirmedStandardIssueIsNormalized() {
        val raw = DrawContractFixtures.doubleColorBallMain(issue = "2026093")

        val snapshot =
            assertIs<SourceParseResult.Success<MainDrawSnapshot>>(
                DoubleColorBallSourceAdapter.parseMain(raw, "2026093", MAIN_URL),
            ).value

        assertEquals(DrawPolicy.STANDARD, snapshot.policy)
    }

    /** 超过已固化政策证据末期时不能把空字段猜成普通状态。 */
    @Test
    fun futurePolicyWithoutEvidenceIsPublishing() {
        val raw = DrawContractFixtures.doubleColorBallMain(issue = "2026094")

        assertIs<SourceParseResult.Publishing>(
            DoubleColorBallSourceAdapter.parseMain(raw, "2026094", MAIN_URL),
        )
    }

    /** 普通期出现活动字段时必须人工复核。 */
    @Test
    fun unexpectedSpecialRuleInStandardPeriodIsPublishing() {
        val raw = DrawContractFixtures.doubleColorBallMain(specialRuleInfo = "新的特别规定")

        assertIs<SourceParseResult.Publishing>(parseMain(raw))
    }

    /** 福彩网无记录业务提示必须映射为未发布。 */
    @Test
    fun noDataMessageIsNotPublished() {
        val raw =
            DrawContractFixtures.doubleColorBallMain(
                state = 1,
                message = "没有查到数据",
                includeRecord = false,
            )

        assertIs<SourceParseResult.NotPublished>(parseMain(raw))
    }

    /** 记录数组字段缺失不能被误判为尚未发布。 */
    @Test
    fun missingResultIsSourceUnavailable() {
        assertIs<SourceParseResult.SourceUnavailable>(parseMain("""{"state":0,"message":"查询成功"}"""))
    }

    /** 其他业务失败必须映射为数据源异常。 */
    @Test
    fun otherBusinessFailureIsSourceUnavailable() {
        val raw = DrawContractFixtures.doubleColorBallMain(state = 2, message = "系统异常")

        assertIs<SourceParseResult.SourceUnavailable>(parseMain(raw))
    }

    /** 重复目标期号必须进入冲突。 */
    @Test
    fun duplicateIssueIsConflict() {
        assertIs<SourceParseResult.Conflict>(
            parseMain(DrawContractFixtures.doubleColorBallMain(duplicateRecord = true)),
        )
    }

    /** 固定奖金额与现行规则不一致时必须阻断。 */
    @Test
    fun changedFixedPrizeIsSourceUnavailable() {
        assertIs<SourceParseResult.SourceUnavailable>(
            parseMain(DrawContractFixtures.doubleColorBallMain(thirdPrizeAmount = "2999")),
        )
    }

    /** 未知奖级类型必须阻断，不能按数组位置推断。 */
    @Test
    fun unknownPrizeTypeIsSourceUnavailable() {
        assertIs<SourceParseResult.SourceUnavailable>(
            parseMain(DrawContractFixtures.doubleColorBallMain(extraPrizeType = 8)),
        )
    }

    /** 浮动奖金额不可解析时仍可确认号码和奖级，但不能确认金额。 */
    @Test
    fun unparseableFloatingPrizeKeepsFinalNumbers() {
        val raw = DrawContractFixtures.doubleColorBallMain(firstPrizeAmount = "奖金待定")
        val snapshot = assertIs<SourceParseResult.Success<MainDrawSnapshot>>(parseMain(raw)).value

        assertFalse(snapshot.payoutFieldsComplete)
        assertNull(snapshot.prizeTiers.single { it.code == PrizeTierCodes.FIRST }.singlePrizeFen)
    }

    /** 详情辅助响应不包含福运奖，但必须完整提供一至六等奖用于核对。 */
    @Test
    fun detailResponseProvidesOnlyBasicTiers() {
        val snapshot =
            assertIs<SourceParseResult.Success<SupportingDrawSnapshot>>(
                parseSupporting(DrawContractFixtures.doubleColorBallSupporting()),
            ).value

        assertEquals(6, snapshot.prizeTiers.size)
        assertTrue(snapshot.prizeTiers.none { it.code == PrizeTierCodes.FORTUNE })
    }

    /** 创建默认双色球主响应解析结果。 */
    private fun parseMain(rawJson: String): SourceParseResult<MainDrawSnapshot> =
        DoubleColorBallSourceAdapter.parseMain(rawJson, DrawContractFixtures.DEFAULT_SSQ_ISSUE, MAIN_URL)

    /** 创建默认双色球详情响应解析结果。 */
    private fun parseSupporting(rawJson: String): SourceParseResult<SupportingDrawSnapshot> =
        DoubleColorBallSourceAdapter.parseSupporting(
            rawJson,
            DrawContractFixtures.DEFAULT_SSQ_ISSUE,
            SUPPORTING_URL,
        )

    /** 测试地址常量。 */
    private companion object {
        /** 双色球主源占位地址。 */
        const val MAIN_URL = "https://example.invalid/ssq/main"

        /** 双色球辅助源占位地址。 */
        const val SUPPORTING_URL = "https://example.invalid/ssq/supporting"
    }
}

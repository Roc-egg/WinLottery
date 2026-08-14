package roc.win.lottery.data

import roc.win.lottery.domain.DrawPolicy
import roc.win.lottery.domain.PrizeTierCodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 大乐透官网主、辅助响应契约测试。 */
class SuperLottoSourceAdapterTest {
    /** 合法主响应应聚合七个基础奖级和两个追加字段。 */
    @Test
    fun validMainResponseIsNormalized() {
        val result = parseMain(DrawContractFixtures.superLottoMain())
        val snapshot = assertIs<SourceParseResult.Success<MainDrawSnapshot>>(result).value

        assertEquals(DrawContractFixtures.DEFAULT_DLT_ISSUE, snapshot.issue)
        assertEquals(listOf(3, 4, 7, 12, 32), snapshot.primaryNumbers)
        assertEquals(listOf(1, 2), snapshot.secondaryNumbers)
        assertEquals(DrawPolicy.STANDARD, snapshot.policy)
        assertEquals(7, snapshot.prizeTiers.size)
        assertTrue(snapshot.publicationFieldsComplete)
        assertFalse(snapshot.payoutFieldsComplete)
        val first = snapshot.prizeTiers.single { it.code == PrizeTierCodes.FIRST }
        assertEquals(1_000_000_000L, first.singlePrizeFen)
        assertNull(first.additionalPrizeFen)
        assertEquals(0L, first.additionalWinnerCount)
    }

    /** 一、二等奖追加单注金额齐全时才能确认整期金额状态。 */
    @Test
    fun completeAdditionalPayoutCanReachFinalPayout() {
        val result =
            parseMain(
                DrawContractFixtures.superLottoMain(
                    firstAdditionalCount = "1",
                    firstAdditionalAmount = "8,000,000",
                ),
            )
        val snapshot = assertIs<SourceParseResult.Success<MainDrawSnapshot>>(result).value

        assertTrue(snapshot.payoutFieldsComplete)
        assertEquals(
            800_000_000L,
            snapshot.prizeTiers.single { it.code == PrizeTierCodes.FIRST }.additionalPrizeFen,
        )
    }

    /** 大乐透前后区属于不同号码池，跨区同值不应被当作重复。 */
    @Test
    fun sameValueAcrossNumberAreasIsAllowed() {
        val result = parseMain(DrawContractFixtures.superLottoMain(numbers = "01 02 03 04 05 01 02"))

        assertIs<SourceParseResult.Success<MainDrawSnapshot>>(result)
    }

    /** 空列表只表示目标期号尚未发布。 */
    @Test
    fun emptyListIsNotPublished() {
        assertIs<SourceParseResult.NotPublished>(
            parseMain(DrawContractFixtures.superLottoMain(includeRecord = false)),
        )
    }

    /** 记录数组字段缺失不能被误判为尚未发布。 */
    @Test
    fun missingListIsSourceUnavailable() {
        assertIs<SourceParseResult.SourceUnavailable>(parseMain("""{"success":true,"errorCode":"0","value":{}}"""))
    }

    /** HTTP 200 内的业务失败必须映射为数据源异常。 */
    @Test
    fun businessFailureIsSourceUnavailable() {
        assertIs<SourceParseResult.SourceUnavailable>(
            parseMain(DrawContractFixtures.superLottoMain(businessSuccess = false)),
        )
    }

    /** 重复目标期号必须立即进入冲突。 */
    @Test
    fun duplicateIssueIsConflict() {
        assertIs<SourceParseResult.Conflict>(
            parseMain(DrawContractFixtures.superLottoMain(duplicateRecord = true)),
        )
    }

    /** 审核、开奖或公告字段未完成时只能返回发布中。 */
    @Test
    fun incompletePublicationFieldsArePublishing() {
        assertIs<SourceParseResult.Publishing>(
            parseMain(DrawContractFixtures.superLottoMain(verify = 0)),
        )
        assertIs<SourceParseResult.Publishing>(
            parseMain(DrawContractFixtures.superLottoMain(drawStatus = 10)),
        )
        assertIs<SourceParseResult.Publishing>(
            parseMain(DrawContractFixtures.superLottoMain(notice = 0)),
        )
    }

    /** 未经验证的临时派奖不能套用基础规则。 */
    @Test
    fun promotionFlagIsPublishing() {
        assertIs<SourceParseResult.Publishing>(
            parseMain(DrawContractFixtures.superLottoMain(promotionFlag = 1)),
        )
    }

    /** 未知奖级必须阻断，不能按数组下标猜测。 */
    @Test
    fun unknownPrizeTierIsSourceUnavailable() {
        assertIs<SourceParseResult.SourceUnavailable>(
            parseMain(DrawContractFixtures.superLottoMain(extraPrizeName = "派奖奖级")),
        )
    }

    /** 实际有中奖注的奖级金额无法解析时只能确认号码，不能确认奖金。 */
    @Test
    fun unparseablePayoutKeepsNumbersButNotPayout() {
        val result = parseMain(DrawContractFixtures.superLottoMain(thirdPrizeAmount = "待官方确认"))
        val snapshot = assertIs<SourceParseResult.Success<MainDrawSnapshot>>(result).value

        assertFalse(snapshot.payoutFieldsComplete)
        assertNull(snapshot.prizeTiers.single { it.code == PrizeTierCodes.THIRD }.singlePrizeFen)
    }

    /** 辅助接口返回其他最新期时不能冒充目标期号证据。 */
    @Test
    fun supportingDifferentIssueIsNotPublishedForTarget() {
        assertIs<SourceParseResult.NotPublished>(
            parseSupporting(DrawContractFixtures.superLottoSupporting(issue = "26092")),
        )
    }

    /** 创建默认大乐透主响应解析结果。 */
    private fun parseMain(rawJson: String): SourceParseResult<MainDrawSnapshot> =
        SuperLottoSourceAdapter.parseMain(rawJson, DrawContractFixtures.DEFAULT_DLT_ISSUE, MAIN_URL)

    /** 创建默认大乐透辅助响应解析结果。 */
    private fun parseSupporting(rawJson: String): SourceParseResult<SupportingDrawSnapshot> =
        SuperLottoSourceAdapter.parseSupporting(rawJson, DrawContractFixtures.DEFAULT_DLT_ISSUE, SUPPORTING_URL)

    /** 测试主源地址。 */
    private companion object {
        /** 大乐透主源占位地址。 */
        const val MAIN_URL = "https://example.invalid/dlt/main"

        /** 大乐透辅助源占位地址。 */
        const val SUPPORTING_URL = "https://example.invalid/dlt/supporting"
    }
}

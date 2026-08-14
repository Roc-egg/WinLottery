package roc.win.lottery.data

import roc.win.lottery.domain.PrizeTierCodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** 大乐透官方 PDF 公告文本层的离线严格契约测试。 */
class SuperLottoAnnouncementPdfParserTest {
    /** 当前单页公告的期号、日期、号码和全部奖级应被完整解析。 */
    @Test
    fun currentAnnouncementTextReturnsSupportingSnapshot() {
        val result = SuperLottoAnnouncementPdfParser.parseExtractedText(VALID_ANNOUNCEMENT_TEXT, ISSUE)

        val snapshot = assertIs<SourceParseResult.Success<SupportingDrawSnapshot>>(result).value
        assertEquals(ISSUE, snapshot.issue)
        assertEquals("2026-08-10", snapshot.drawDate)
        assertEquals(listOf(9, 14, 17, 19, 24), snapshot.primaryNumbers)
        assertEquals(listOf(2, 9), snapshot.secondaryNumbers)
        assertEquals(EXPECTED_PDF_URL, snapshot.detailUrl)
        assertEquals(EXPECTED_TIER_CODES, snapshot.prizeTiers.map { it.code }.toSet())
        val first = snapshot.prizeTiers.single { it.code == PrizeTierCodes.FIRST }
        assertEquals(2L, first.winnerCount)
        assertEquals(1_000_000_000L, first.singlePrizeFen)
        assertEquals(0L, first.additionalWinnerCount)
        assertEquals(null, first.additionalPrizeFen)
        val seventh = snapshot.prizeTiers.single { it.code == PrizeTierCodes.SEVENTH }
        assertEquals(8_260_921L, seventh.winnerCount)
        assertEquals(700L, seventh.singlePrizeFen)
    }

    /** 合并奖级单元格位于基本与追加行之间时也应按同一字段语义解析。 */
    @Test
    fun mergedTierCellBetweenRowsReturnsSupportingSnapshot() {
        val sortedText =
            VALID_ANNOUNCEMENT_TEXT
                .replace(
                    "一等奖 基本 2注 10,000,000元 20,000,000元\n追加 0注 --- 0元",
                    "基本 2注 10,000,000元 20,000,000元\n一等奖\n追加 0注 --- 0元",
                ).replace(
                    "二等奖 基本 93注 155,846元 14,493,678元\n追加 32注 124,677元 3,989,664元",
                    "基本 93注 155,846元 14,493,678元\n二等奖\n追加 32注 124,677元 3,989,664元",
                )

        val result = SuperLottoAnnouncementPdfParser.parseExtractedText(sortedText, ISSUE)

        assertIs<SourceParseResult.Success<SupportingDrawSnapshot>>(result)
    }

    /** 总奖金与注数乘以单注奖金不一致时必须失败关闭。 */
    @Test
    fun mismatchedPrizeTotalIsRejected() {
        val malformed = VALID_ANNOUNCEMENT_TEXT.replace("57,826,447元", "57,826,448元")

        val result = SuperLottoAnnouncementPdfParser.parseExtractedText(malformed, ISSUE)

        assertIs<SourceParseResult.SourceUnavailable>(result)
    }

    /** 公告期号与目标期号不一致时必须失败关闭。 */
    @Test
    fun mismatchedIssueIsRejected() {
        val result = SuperLottoAnnouncementPdfParser.parseExtractedText(VALID_ANNOUNCEMENT_TEXT, "26091")

        assertIs<SourceParseResult.SourceUnavailable>(result)
    }

    /** 离线公告夹具和断言常量。 */
    private companion object {
        /** 夹具期号。 */
        const val ISSUE = "26090"

        /** 夹具官方公告地址。 */
        const val EXPECTED_PDF_URL = "https://pdf.sporttery.cn/33800/26090/26090.pdf"

        /** 七个基础奖级编码。 */
        val EXPECTED_TIER_CODES =
            setOf(
                PrizeTierCodes.FIRST,
                PrizeTierCodes.SECOND,
                PrizeTierCodes.THIRD,
                PrizeTierCodes.FOURTH,
                PrizeTierCodes.FIFTH,
                PrizeTierCodes.SIXTH,
                PrizeTierCodes.SEVENTH,
            )

        /** 人工最小化的当前官方公告文本结构，不保存官网 PDF 或原始响应。 */
        val VALID_ANNOUNCEMENT_TEXT =
            """
            中国体育彩票超级大乐透第26090期开奖公告
            开奖日期：2026年8月10日
            本期开奖号码： 09 14 17 19 24 02 09
            一等奖 基本 2注 10,000,000元 20,000,000元
            追加 0注 --- 0元
            二等奖 基本 93注 155,846元 14,493,678元
            追加 32注 124,677元 3,989,664元
            三等奖 1,169注 6,666元 7,792,554元
            四等奖 21,172注 380元 8,045,360元
            五等奖 90,542注 200元 18,108,400元
            六等奖 1,039,876注 18元 18,717,768元
            七等奖 8,260,921注 7元 57,826,447元
            """.trimIndent()
    }
}

package roc.win.lottery.data

import kotlinx.coroutines.test.runTest
import roc.win.lottery.domain.PrizeTierCodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** 大乐透官方 PDF 公告文件身份与文本层的三端严格契约测试。 */
class SuperLottoAnnouncementParserTest {
    /** 当前单页公告的期号、日期、号码和全部奖级应被完整解析。 */
    @Test
    fun currentAnnouncementTextReturnsSupportingSnapshot() {
        val result =
            SuperLottoAnnouncementParser.parseExtractedText(
                SuperLottoAnnouncementTestFixtures.VALID_ANNOUNCEMENT_TEXT,
                SuperLottoAnnouncementTestFixtures.ISSUE,
            )

        val snapshot = assertIs<SourceParseResult.Success<SupportingDrawSnapshot>>(result).value
        assertEquals(SuperLottoAnnouncementTestFixtures.ISSUE, snapshot.issue)
        assertEquals("2026-08-10", snapshot.drawDate)
        assertEquals(listOf(9, 14, 17, 19, 24), snapshot.primaryNumbers)
        assertEquals(listOf(2, 9), snapshot.secondaryNumbers)
        assertEquals(SuperLottoAnnouncementTestFixtures.EXPECTED_PDF_URL, snapshot.detailUrl)
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
            SuperLottoAnnouncementTestFixtures.VALID_ANNOUNCEMENT_TEXT
                .replace(
                    "一等奖 基本 2注 10,000,000元 20,000,000元\n追加 0注 --- 0元",
                    "基本 2注 10,000,000元 20,000,000元\n一等奖\n追加 0注 --- 0元",
                ).replace(
                    "二等奖 基本 93注 155,846元 14,493,678元\n追加 32注 124,677元 3,989,664元",
                    "基本 93注 155,846元 14,493,678元\n二等奖\n追加 32注 124,677元 3,989,664元",
                )

        val result =
            SuperLottoAnnouncementParser.parseExtractedText(
                sortedText,
                SuperLottoAnnouncementTestFixtures.ISSUE,
            )

        assertIs<SourceParseResult.Success<SupportingDrawSnapshot>>(result)
    }

    /** PDFKit 按列输出基本与追加单元格时也必须恢复为两行独立奖级。 */
    @Test
    fun pdfKitColumnOrderedAdditionalRowsReturnSupportingSnapshot() {
        val columnOrderedText =
            SuperLottoAnnouncementTestFixtures.VALID_ANNOUNCEMENT_TEXT
                .replace(
                    "一等奖 基本 2注 10,000,000元 20,000,000元\n追加 0注 --- 0元",
                    "一等奖 基本 追加 2注 0注 10,000,000元 --- 20,000,000元\n0元",
                ).replace(
                    "二等奖 基本 93注 155,846元 14,493,678元\n追加 32注 124,677元 3,989,664元",
                    "二等奖 基本 追加 93注 32注 155,846元 124,677元 14,493,678元\n3,989,664元",
                )

        val result =
            SuperLottoAnnouncementParser.parseExtractedText(
                columnOrderedText,
                SuperLottoAnnouncementTestFixtures.ISSUE,
            )

        assertIs<SourceParseResult.Success<SupportingDrawSnapshot>>(result)
    }

    /** 总奖金与注数乘以单注奖金不一致时必须失败关闭。 */
    @Test
    fun mismatchedPrizeTotalIsRejected() {
        val malformed =
            SuperLottoAnnouncementTestFixtures.VALID_ANNOUNCEMENT_TEXT.replace(
                "57,826,447元",
                "57,826,448元",
            )

        val result =
            SuperLottoAnnouncementParser.parseExtractedText(
                malformed,
                SuperLottoAnnouncementTestFixtures.ISSUE,
            )

        assertIs<SourceParseResult.SourceUnavailable>(result)
    }

    /** 公告期号与目标期号不一致时必须失败关闭。 */
    @Test
    fun mismatchedIssueIsRejected() {
        val result =
            SuperLottoAnnouncementParser.parseExtractedText(
                SuperLottoAnnouncementTestFixtures.VALID_ANNOUNCEMENT_TEXT,
                "26091",
            )

        assertIs<SourceParseResult.SourceUnavailable>(result)
    }

    /** 文件身份和平台结构均符合固定契约时才允许进入文本语义解析。 */
    @Test
    fun validPdfEnvelopeUsesPlatformText() =
        runTest {
            val result =
                SuperLottoAnnouncementParser.parse(
                    pdfBytes = SuperLottoAnnouncementTestFixtures.validPdfEnvelope(),
                    targetIssue = SuperLottoAnnouncementTestFixtures.ISSUE,
                    sourceUrl = SuperLottoAnnouncementTestFixtures.EXPECTED_PDF_URL,
                    textExtractor = fixedExtractor(),
                )

            assertIs<SourceParseResult.Success<SupportingDrawSnapshot>>(result)
        }

    /** 主响应给出的公告地址不是目标期号固定路径时不得调用平台解析器。 */
    @Test
    fun mismatchedPdfUrlIsRejectedBeforeExtraction() =
        runTest {
            var extractionCount = 0
            val result =
                SuperLottoAnnouncementParser.parse(
                    pdfBytes = SuperLottoAnnouncementTestFixtures.validPdfEnvelope(),
                    targetIssue = SuperLottoAnnouncementTestFixtures.ISSUE,
                    sourceUrl = "https://pdf.sporttery.cn/33800/26091/26091.pdf",
                    textExtractor =
                        SuperLottoPdfTextExtractor {
                            extractionCount += 1
                            SuperLottoPdfTextExtractionResult.Success(
                                SuperLottoAnnouncementTestFixtures.validDocument(),
                            )
                        },
                )

            assertIs<SourceParseResult.SourceUnavailable>(result)
            assertEquals(0, extractionCount)
        }

    /** 平台报告的生产器、页数、加密或表单结构变化必须失败关闭。 */
    @Test
    fun changedPdfStructureIsRejected() =
        runTest {
            val changed = SuperLottoAnnouncementTestFixtures.validDocument().copy(producer = "未知生产器")
            val result =
                SuperLottoAnnouncementParser.parse(
                    pdfBytes = SuperLottoAnnouncementTestFixtures.validPdfEnvelope(),
                    targetIssue = SuperLottoAnnouncementTestFixtures.ISSUE,
                    sourceUrl = SuperLottoAnnouncementTestFixtures.EXPECTED_PDF_URL,
                    textExtractor = fixedExtractor(changed),
                )

            assertIs<SourceParseResult.SourceUnavailable>(result)
        }

    /** 创建返回固定文档结构的平台提取器。 */
    private fun fixedExtractor(
        document: SuperLottoPdfDocument = SuperLottoAnnouncementTestFixtures.validDocument(),
    ): SuperLottoPdfTextExtractor =
        SuperLottoPdfTextExtractor {
            SuperLottoPdfTextExtractionResult.Success(document)
        }

    /** 当前公告必须出现的七个基础奖级。 */
    private companion object {
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
    }
}

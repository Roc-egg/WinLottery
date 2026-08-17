package roc.win.lottery.data

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper

/** 仅供 B2 JVM 真实对账使用的 PDFBox 文本提取适配器。 */
internal object SuperLottoAnnouncementPdfParser {
    /**
     * 使用与移动端相同的共享公告契约解析官方 PDF。
     *
     * @param pdfBytes 当前调用内存中的 PDF 字节。
     * @param targetIssue 目标期号。
     * @param sourceUrl 固定官方公告地址。
     * @return 严格辅助快照或明确失败状态。
     */
    suspend fun parse(
        pdfBytes: ByteArray,
        targetIssue: String,
        sourceUrl: String,
    ): SourceParseResult<SupportingDrawSnapshot> =
        SuperLottoAnnouncementParser.parse(
            pdfBytes = pdfBytes,
            targetIssue = targetIssue,
            sourceUrl = sourceUrl,
            textExtractor = JvmPdfBoxTextExtractor,
        )

    /** JVM PDFBox 只负责结构读取，不解释彩票字段。 */
    private object JvmPdfBoxTextExtractor : SuperLottoPdfTextExtractor {
        /** 提取 PDFBox 可见的文档结构和按位置排序的文本层。 */
        override suspend fun extract(pdfBytes: ByteArray): SuperLottoPdfTextExtractionResult =
            try {
                Loader.loadPDF(pdfBytes).use { document ->
                    val firstPage = document.getPage(0)
                    SuperLottoPdfTextExtractionResult.Success(
                        SuperLottoPdfDocument(
                            text = PDFTextStripper().apply { sortByPosition = true }.getText(document),
                            pageCount = document.numberOfPages,
                            producer = document.documentInformation.producer,
                            pageRotationDegrees = firstPage.rotation,
                            isEncrypted = document.isEncrypted,
                            hasInteractiveForm = document.documentCatalog.acroForm != null,
                        ),
                    )
                }
            } catch (_: Exception) {
                SuperLottoPdfTextExtractionResult.Failure("大乐透 PDF 公告无法安全解析")
            }
    }
}

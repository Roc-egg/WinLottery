package roc.win.lottery.data

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
            textExtractor = JvmSuperLottoPdfTextExtractor(),
        )
}

package roc.win.lottery.data

/** 三端公告解析测试共用的最小化公开文本夹具。 */
internal object SuperLottoAnnouncementTestFixtures {
    /** 夹具期号。 */
    const val ISSUE = "26090"

    /** 夹具官方公告地址。 */
    const val EXPECTED_PDF_URL = "https://pdf.sporttery.cn/33800/26090/26090.pdf"

    /** 元级取整回归夹具期号。 */
    const val ISSUE_26096 = "26096"

    /** 元级取整回归夹具官方公告地址。 */
    const val ISSUE_26096_PDF_URL = "https://pdf.sporttery.cn/33800/26096/26096.pdf"

    /**
     * 人工最小化的当前官方公告文本结构。
     *
     * 只包含公开开奖结果字段，不保存官网 PDF 或原始响应。
     */
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

    /** 第 26096 期官方公告公开字段的人工最小化文本。 */
    val ISSUE_26096_ANNOUNCEMENT_TEXT =
        """
        中国体育彩票超级大乐透第26096期开奖公告
        开奖日期：2026年8月24日
        本期开奖号码： 08 09 10 11 25 04 12
        一等奖 基本 9注 6,561,952元 59,057,568元
        追加 1注 5,249,561元 5,249,561元
        二等奖 基本 222注 57,893元 12,852,246元
        追加 53注 46,315元 2,454,695元
        三等奖 1,623注 6,666元 10,818,918元
        四等奖 26,899注 380元 10,221,620元
        五等奖 94,342注 200元 18,868,400元
        六等奖 808,020注 18元 14,544,360元
        七等奖 7,823,577注 7元 54,765,039元
        """.trimIndent()

    /** 构造满足共享文件头和大小限制的内存 PDF 占位字节。 */
    fun validPdfEnvelope(): ByteArray =
        ByteArray(SuperLottoAnnouncementParser.MINIMUM_PDF_BYTES).also { bytes ->
            "%PDF-1.4".encodeToByteArray().copyInto(bytes)
        }

    /** 构造与当前公告结构一致的平台提取结果。 */
    fun validDocument(text: String = VALID_ANNOUNCEMENT_TEXT): SuperLottoPdfDocument =
        SuperLottoPdfDocument(
            text = text,
            pageCount = 1,
            producer = "iText 1.4.4 (by lowagie.com)",
            pageRotationDegrees = 0,
            isEncrypted = false,
            hasInteractiveForm = false,
        )
}

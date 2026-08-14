package roc.win.lottery.data

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import roc.win.lottery.domain.PrizeTier
import roc.win.lottery.domain.PrizeTierCodes

/** 仅供 B2 JVM 验收使用的中国体彩网大乐透 PDF 公告解析器。 */
internal object SuperLottoAnnouncementPdfParser {
    /**
     * 严格解析当前官方单页 PDF 公告，并生成可与历史 JSON 比较的辅助快照。
     *
     * 任何文件结构、文本布局或必要字段变化都返回数据源不可用，不猜测公告内容。
     */
    fun parse(
        pdfBytes: ByteArray,
        targetIssue: String,
        sourceUrl: String,
    ): SourceParseResult<SupportingDrawSnapshot> {
        if (!ISSUE_PATTERN.matches(targetIssue)) {
            return SourceParseResult.SourceUnavailable("大乐透 PDF 目标期号格式不合法")
        }
        val expectedUrl = expectedPdfUrl(targetIssue)
        if (sourceUrl != expectedUrl) {
            return SourceParseResult.SourceUnavailable("大乐透 PDF 公告地址与目标期号不一致")
        }
        if (pdfBytes.size !in MINIMUM_PDF_BYTES..MAXIMUM_PDF_BYTES || !pdfBytes.startsWith(PDF_HEADER)) {
            return SourceParseResult.SourceUnavailable("大乐透公告不是受支持的 PDF 文件")
        }

        return try {
            Loader.loadPDF(pdfBytes).use { document ->
                if (document.isEncrypted) {
                    return SourceParseResult.SourceUnavailable("大乐透 PDF 公告已加密")
                }
                if (document.numberOfPages != EXPECTED_PAGE_COUNT) {
                    return SourceParseResult.SourceUnavailable("大乐透 PDF 公告页数发生变化")
                }
                if (document.version != EXPECTED_PDF_VERSION) {
                    return SourceParseResult.SourceUnavailable("大乐透 PDF 公告版本发生变化")
                }
                if (document.documentInformation.producer != EXPECTED_PRODUCER) {
                    return SourceParseResult.SourceUnavailable("大乐透 PDF 公告生成器发生变化")
                }
                val page = document.getPage(0)
                if (page.rotation != EXPECTED_PAGE_ROTATION || document.documentCatalog.acroForm != null) {
                    return SourceParseResult.SourceUnavailable("大乐透 PDF 公告页面结构发生变化")
                }
                val text = PDFTextStripper().apply { sortByPosition = true }.getText(document)
                parseExtractedText(text, targetIssue, sourceUrl)
            }
        } catch (_: Exception) {
            SourceParseResult.SourceUnavailable("大乐透 PDF 公告无法安全解析")
        }
    }

    /** 将 PDF 文本层严格转换为统一辅助快照，供离线契约测试直接覆盖。 */
    fun parseExtractedText(
        rawText: String,
        targetIssue: String,
        sourceUrl: String = expectedPdfUrl(targetIssue),
    ): SourceParseResult<SupportingDrawSnapshot> {
        val text = rawText.replace(NORMALIZED_WHITESPACE, " ").trim()
        if (!Regex("第\\s*${Regex.escape(targetIssue)}\\s*期开奖公告").containsMatchIn(text)) {
            return SourceParseResult.SourceUnavailable("大乐透 PDF 公告期号不一致")
        }
        val drawDate = parseDrawDate(text) ?: return SourceParseResult.SourceUnavailable("大乐透 PDF 开奖日期不合法")
        if (drawDate.substring(2, 4) != targetIssue.take(2)) {
            return SourceParseResult.SourceUnavailable("大乐透 PDF 开奖日期与期号年份不一致")
        }
        val numbers = parseNumbers(text) ?: return SourceParseResult.SourceUnavailable("大乐透 PDF 开奖号码不合法")
        val tiers = parsePrizeTiers(text) ?: return SourceParseResult.SourceUnavailable("大乐透 PDF 奖级数据不合法")
        val canonical =
            canonicalSupportingDraw(
                issue = targetIssue,
                drawDate = drawDate,
                primaryNumbers = numbers.first,
                secondaryNumbers = numbers.second,
                prizeTiers = tiers,
                detailUrl = sourceUrl,
            )
        return SourceParseResult.Success(
            SupportingDrawSnapshot(
                issue = targetIssue,
                drawDate = drawDate,
                primaryNumbers = numbers.first,
                secondaryNumbers = numbers.second,
                prizeTiers = tiers,
                detailUrl = sourceUrl,
                evidence = EvidenceDraft("中国体彩网开奖公告 PDF", sourceUrl, canonical),
            ),
        )
    }

    /** 解析中文开奖日期并归一化为 ISO 日期。 */
    private fun parseDrawDate(text: String): String? {
        val match = DRAW_DATE_PATTERN.find(text) ?: return null
        val year = match.groupValues[1]
        val month = match.groupValues[2].padStart(2, '0')
        val day = match.groupValues[3].padStart(2, '0')
        return normalizeDrawDate("$year-$month-$day")
    }

    /** 解析并严格校验五个前区和两个后区号码。 */
    private fun parseNumbers(text: String): Pair<List<Int>, List<Int>>? {
        val match = DRAW_NUMBERS_PATTERN.find(text) ?: return null
        val numbers =
            match.groupValues[1]
                .trim()
                .split(Regex("\\s+"))
                .map { it.toIntOrNull() ?: return null }
        if (numbers.size != TOTAL_NUMBER_COUNT) return null
        val primary = numbers.take(PRIMARY_NUMBER_COUNT)
        val secondary = numbers.drop(PRIMARY_NUMBER_COUNT)
        if (primary.any { it !in 1..PRIMARY_MAX_NUMBER } || primary.distinct().size != PRIMARY_NUMBER_COUNT) {
            return null
        }
        if (secondary.any { it !in 1..SECONDARY_MAX_NUMBER } || secondary.distinct().size != SECONDARY_NUMBER_COUNT) {
            return null
        }
        return primary to secondary
    }

    /** 解析七个基础奖级以及一、二等奖追加奖级。 */
    private fun parsePrizeTiers(text: String): List<PrizeTier>? {
        val first = parseAdditionalTier(text, "一等奖", PrizeTierCodes.FIRST) ?: return null
        val second = parseAdditionalTier(text, "二等奖", PrizeTierCodes.SECOND) ?: return null
        val fixed =
            listOf(
                parseBaseTier(text, "三等奖", PrizeTierCodes.THIRD),
                parseBaseTier(text, "四等奖", PrizeTierCodes.FOURTH),
                parseBaseTier(text, "五等奖", PrizeTierCodes.FIFTH),
                parseBaseTier(text, "六等奖", PrizeTierCodes.SIXTH),
                parseBaseTier(text, "七等奖", PrizeTierCodes.SEVENTH),
            )
        if (fixed.any { it == null }) return null
        return listOf(first, second) + fixed.filterNotNull()
    }

    /** 解析包含基本与追加两行的一、二等奖。 */
    private fun parseAdditionalTier(
        text: String,
        displayName: String,
        code: String,
    ): PrizeTier? {
        val nameBeforeRowsPattern =
            Regex(
                "${Regex.escape(displayName)}\\s+基本\\s+($COUNT_TEXT)\\s*注\\s+($MONEY_TEXT)\\s*元?\\s+" +
                    "($COUNT_TEXT)\\s*元\\s+追加\\s+($COUNT_TEXT)\\s*注\\s+($MONEY_TEXT)\\s*元?\\s+" +
                    "($COUNT_TEXT)\\s*元",
            )
        val nameBetweenRowsPattern =
            Regex(
                "基本\\s+($COUNT_TEXT)\\s*注\\s+($MONEY_TEXT)\\s*元?\\s+($COUNT_TEXT)\\s*元\\s+" +
                    "${Regex.escape(displayName)}\\s+追加\\s+($COUNT_TEXT)\\s*注\\s+($MONEY_TEXT)\\s*元?\\s+" +
                    "($COUNT_TEXT)\\s*元",
            )
        val match = nameBeforeRowsPattern.find(text) ?: nameBetweenRowsPattern.find(text) ?: return null
        val base = parsePrizeRow(match.groupValues[1], match.groupValues[2], match.groupValues[3]) ?: return null
        val additional = parsePrizeRow(match.groupValues[4], match.groupValues[5], match.groupValues[6]) ?: return null
        return PrizeTier(
            code = code,
            displayName = displayName,
            singlePrizeFen = base.singlePrizeFen,
            additionalPrizeFen = additional.singlePrizeFen,
            winnerCount = base.winnerCount,
            additionalWinnerCount = additional.winnerCount,
            singlePrizeRaw = base.prizeRaw,
            additionalPrizeRaw = additional.prizeRaw,
        )
    }

    /** 解析三至七等奖的一行基本投注数据。 */
    private fun parseBaseTier(
        text: String,
        displayName: String,
        code: String,
    ): PrizeTier? {
        val pattern =
            Regex(
                "${Regex.escape(displayName)}\\s+($COUNT_TEXT)\\s*注\\s+($MONEY_TEXT)\\s*元?\\s+" +
                    "($COUNT_TEXT)\\s*元",
            )
        val match = pattern.find(text) ?: return null
        val row = parsePrizeRow(match.groupValues[1], match.groupValues[2], match.groupValues[3]) ?: return null
        return PrizeTier(
            code = code,
            displayName = displayName,
            singlePrizeFen = row.singlePrizeFen,
            additionalPrizeFen = null,
            winnerCount = row.winnerCount,
            additionalWinnerCount = null,
            singlePrizeRaw = row.prizeRaw,
            additionalPrizeRaw = null,
        )
    }

    /** 解析注数、单注奖金和总奖金，并验证表内乘法关系。 */
    private fun parsePrizeRow(
        countRaw: String,
        prizeRaw: String,
        totalRaw: String,
    ): ParsedPrizeRow? {
        val winnerCount = parseCount(countRaw) ?: return null
        val totalYuan = parseCount(totalRaw) ?: return null
        val prizeFen = parseYuanToFen(prizeRaw)
        val prizeYuan = prizeFen?.div(FEN_PER_YUAN)
        if (winnerCount < 0L || totalYuan < 0L) return null
        if (prizeYuan == null) {
            if (winnerCount != 0L || totalYuan != 0L || prizeRaw !in MISSING_AMOUNT_MARKERS) return null
        } else {
            val expectedTotal = runCatching { Math.multiplyExact(winnerCount, prizeYuan) }.getOrNull() ?: return null
            if (expectedTotal != totalYuan) return null
        }
        return ParsedPrizeRow(
            winnerCount = winnerCount,
            singlePrizeFen = prizeFen.takeUnless { winnerCount == 0L && it == 0L },
            prizeRaw = prizeRaw,
        )
    }

    /** 判断字节数组是否以指定固定头开始。 */
    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { this[it] == prefix[it] }
    }

    /** 当前官方公告的固定地址。 */
    private fun expectedPdfUrl(issue: String): String = "https://pdf.sporttery.cn/33800/$issue/$issue.pdf"

    /** 已校验奖级行的内部结构。 */
    private data class ParsedPrizeRow(
        /** 中奖注数。 */
        val winnerCount: Long,
        /** 基本或追加单注奖金，单位为分。 */
        val singlePrizeFen: Long?,
        /** 公告中的单注奖金原文。 */
        val prizeRaw: String,
    )

    /** 受支持 PDF 的最小字节数。 */
    private const val MINIMUM_PDF_BYTES = 10_000

    /** 受支持 PDF 的最大字节数。 */
    private const val MAXIMUM_PDF_BYTES = 1_000_000

    /** 当前官方公告页数。 */
    private const val EXPECTED_PAGE_COUNT = 1

    /** 当前官方公告 PDF 版本。 */
    private const val EXPECTED_PDF_VERSION = 1.4f

    /** 当前官方公告页面旋转角度。 */
    private const val EXPECTED_PAGE_ROTATION = 0

    /** 当前官方公告生成器。 */
    private const val EXPECTED_PRODUCER = "iText 1.4.4 (by lowagie.com)"

    /** 大乐透前区号码数量。 */
    private const val PRIMARY_NUMBER_COUNT = 5

    /** 大乐透后区号码数量。 */
    private const val SECONDARY_NUMBER_COUNT = 2

    /** 大乐透号码总数。 */
    private const val TOTAL_NUMBER_COUNT = PRIMARY_NUMBER_COUNT + SECONDARY_NUMBER_COUNT

    /** 大乐透前区最大号码。 */
    private const val PRIMARY_MAX_NUMBER = 35

    /** 大乐透后区最大号码。 */
    private const val SECONDARY_MAX_NUMBER = 12

    /** 一元对应的分数。 */
    private const val FEN_PER_YUAN = 100L

    /** PDF 文件头。 */
    private val PDF_HEADER = "%PDF-".encodeToByteArray()

    /** 大乐透五位期号。 */
    private val ISSUE_PATTERN = Regex("^\\d{5}$")

    /** 中文开奖日期。 */
    private val DRAW_DATE_PATTERN = Regex("开奖日期\\s*[:：]\\s*(\\d{4})年\\s*(\\d{1,2})月\\s*(\\d{1,2})日")

    /** 七个大乐透开奖号码。 */
    private val DRAW_NUMBERS_PATTERN =
        Regex("本期开奖号码\\s*[:：]\\s*((?:\\d{1,2}\\s+){6}\\d{1,2})(?=\\s|$)")

    /** PDF 文本层中所有空白的归一化规则。 */
    private val NORMALIZED_WHITESPACE = Regex("[\\s\\u00a0\\u3000]+")

    /** 带可选千分位的非负整数。 */
    private const val COUNT_TEXT = "[\\d,]+"

    /** 公告单注奖金或未发布占位符。 */
    private const val MONEY_TEXT = "(?:[\\d,]+|---)"

    /** 当前公告接受的缺失奖金占位符。 */
    private val MISSING_AMOUNT_MARKERS = setOf("---")
}

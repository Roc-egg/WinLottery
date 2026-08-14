package roc.win.lottery.recognition

import roc.win.lottery.domain.BetLineDraft
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.TicketAmountCalculator
import roc.win.lottery.domain.TicketDraft

/**
 * 从平台标准 OCR 行中保守提取 V1 单式票字段。
 *
 * 解析器只接受能够由票面原文直接证明的值。多期、补打、复式、胆拖、疑似字符混淆会被明确阻断；
 * 只有彩种与投注结构已经确定时，缺失期号、缺失金额或金额矛盾才会携带草稿进入人工校正。
 */
class ConservativeTicketParser : TicketParser {
    /** 解析一份标准 OCR 文档，并返回等待确认或明确阻断状态。 */
    override fun parse(document: OcrDocument): TicketParseResult {
        val rows = mergeVisualRows(document.lines)
        if (rows.isEmpty()) {
            return TicketParseResult.NeedsCorrection("未识别到可解析的票面文字")
        }

        val lotteryType =
            when (val result = detectLotteryType(rows)) {
                is LotteryTypeDetection.Known -> result.lotteryType
                is LotteryTypeDetection.Failed -> return TicketParseResult.NeedsCorrection(result.message)
            }

        detectUnsupportedTicket(rows)?.let { message ->
            return TicketParseResult.Unsupported(message)
        }

        val explicitPeriodCounts = extractExplicitPeriodCounts(rows)
        if (explicitPeriodCounts.size > 1) {
            return TicketParseResult.NeedsCorrection("识别到互相冲突的投注期数，请人工核对")
        }
        val explicitPeriodCount = explicitPeriodCounts.singleOrNull()
        if (explicitPeriodCount != null && explicitPeriodCount != V1_PERIOD_COUNT) {
            return TicketParseResult.Unsupported("识别到${explicitPeriodCount}期投注，V1 仅支持单期彩票")
        }

        val issueCandidates = extractIssueCandidates(rows, lotteryType)
        if (issueCandidates.size > 1) {
            return TicketParseResult.NeedsCorrection("识别到多个开奖期号，请重新拍摄或人工核对")
        }
        val issue = issueCandidates.singleOrNull().orEmpty()
        val parsedBets =
            when (val result = parseBetRows(rows, lotteryType)) {
                is BetRowsResult.Success -> result.rows
                is BetRowsResult.Failed -> return TicketParseResult.NeedsCorrection(result.message)
            }
        val multiplier =
            extractMultiplier(rows, parsedBets, lotteryType)
                ?: return TicketParseResult.NeedsCorrection("投注倍数缺失、存在字符混淆或多个候选值")
        val isAdditional =
            when (lotteryType) {
                LotteryType.SUPER_LOTTO -> {
                    extractSuperLottoAdditional(rows)
                        ?: return TicketParseResult.NeedsCorrection("大乐透追加属性缺失或存在冲突")
                }

                LotteryType.DOUBLE_COLOR_BALL -> {
                    false
                }
            }
        val paidAmountCandidates = extractPaidAmountCandidates(rows)
        if (paidAmountCandidates.size > 1) {
            return TicketParseResult.NeedsCorrection("识别到多个票面合计金额，请重新拍摄或人工核对")
        }
        val paidAmountFen = paidAmountCandidates.singleOrNull()
        val periodCount = explicitPeriodCount ?: V1_PERIOD_COUNT
        val expectedAmountFen =
            TicketAmountCalculator.calculate(
                lotteryType = lotteryType,
                betLineCount = parsedBets.size,
                additionalLineCount = if (isAdditional) parsedBets.size else 0,
                multiplier = multiplier,
                periodCount = periodCount,
            )
                ?: return TicketParseResult.NeedsCorrection("无法根据当前投注结构计算金额")
        val draft =
            TicketDraft(
                lotteryType = lotteryType,
                issue = issue,
                betLines =
                    parsedBets.map { parsed ->
                        BetLineDraft(
                            primaryNumbers = parsed.primaryNumbers,
                            secondaryNumbers = parsed.secondaryNumbers,
                            isAdditional = isAdditional,
                            originalText = parsed.originalText,
                        )
                    },
                multiplier = multiplier,
                periodCount = periodCount,
                paidAmountFen = paidAmountFen,
            )
        val correctionMessages =
            buildList {
                if (issue.isEmpty()) {
                    add("期号缺失或格式错误，请在票面校正页补充")
                }
                if (paidAmountFen == null) {
                    add("票面合计金额缺失或格式错误，请在票面校正页补充")
                } else if (expectedAmountFen != paidAmountFen) {
                    add("票面金额与投注行、倍数、期数或追加属性不一致")
                }
            }
        if (correctionMessages.isNotEmpty()) {
            return TicketParseResult.NeedsCorrection(
                message = correctionMessages.joinToString(separator = "；"),
                draft = draft,
            )
        }

        return TicketParseResult.ReadyForReview(draft)
    }

    /**
     * 优先根据精确标题识别彩种；标题缺失时，只接受发行机构与唯一合法单式号码结构的联合证据。
     */
    private fun detectLotteryType(rows: List<VisualRow>): LotteryTypeDetection {
        val compactTexts = rows.map { it.text.compactForKeywords() }
        val hasSuperLotto = compactTexts.any { it.contains(SUPER_LOTTO_KEYWORD) }
        val hasDoubleColorBall = compactTexts.any { it.contains(DOUBLE_COLOR_BALL_KEYWORD) }
        when {
            hasSuperLotto && hasDoubleColorBall -> LotteryTypeDetection.Failed("同时识别到大乐透和双色球标题")
            hasSuperLotto -> LotteryTypeDetection.Known(LotteryType.SUPER_LOTTO)
            hasDoubleColorBall -> LotteryTypeDetection.Known(LotteryType.DOUBLE_COLOR_BALL)
            else -> null
        }?.let { return it }

        val hasWelfareLotteryIssuer = compactTexts.any { it.contains(WELFARE_LOTTERY_ISSUER_KEYWORD) }
        val hasSportsLotteryIssuer = compactTexts.any { it.contains(SPORTS_LOTTERY_ISSUER_KEYWORD) }
        if (hasWelfareLotteryIssuer && hasSportsLotteryIssuer) {
            return LotteryTypeDetection.Failed("同时识别到福利彩票和体育彩票发行机构，请人工核对")
        }
        if (!hasWelfareLotteryIssuer && !hasSportsLotteryIssuer) {
            return LotteryTypeDetection.Failed("未识别到受支持的彩种标题或发行机构")
        }

        val structureType =
            detectUniqueStrictBetStructure(rows)
                ?: return LotteryTypeDetection.Failed("彩种标题缺失，且单式号码结构不完整或存在冲突")
        val issuerType =
            if (hasWelfareLotteryIssuer) {
                LotteryType.DOUBLE_COLOR_BALL
            } else {
                LotteryType.SUPER_LOTTO
            }
        return if (structureType == issuerType) {
            LotteryTypeDetection.Known(issuerType)
        } else {
            LotteryTypeDetection.Failed("发行机构与单式号码结构不一致，请人工核对")
        }
    }

    /**
     * 从所有疑似投注行提取唯一合法的 `6+1` 或 `5+2` 结构；任何残缺或混合结构都会失败。
     */
    private fun detectUniqueStrictBetStructure(rows: List<VisualRow>): LotteryType? {
        val detectedTypes = mutableSetOf<LotteryType>()
        var candidateFound = false
        rows.forEach { row ->
            val normalized = row.text.normalizedForParsing()
            val plusIndex = normalized.indexOf(PLUS_SIGN)
            if (plusIndex < 0) return@forEach

            val primaryNumbers = extractTwoDigitNumbers(normalized.substring(0, plusIndex))
            val secondaryNumbers =
                extractTwoDigitNumbers(
                    normalized
                        .substring(plusIndex + 1)
                        .replace(ROW_MULTIPLIER_REGEX, EMPTY_TEXT),
                )
            val resemblesBetRow =
                primaryNumbers.size + secondaryNumbers.size >= MIN_NUMBER_TOKENS_FOR_BET_CANDIDATE
            if (!resemblesBetRow) return@forEach
            candidateFound = true

            val matchingTypes =
                LotteryType.entries.filter { lotteryType ->
                    val spec = BetNumberSpec.forLottery(lotteryType)
                    primaryNumbers.size == spec.primaryCount &&
                        secondaryNumbers.size == spec.secondaryCount &&
                        numbersAreValid(primaryNumbers, spec.primaryRange) &&
                        numbersAreValid(secondaryNumbers, spec.secondaryRange)
                }
            if (matchingTypes.size != 1) return null
            detectedTypes += matchingTypes.single()
        }
        return detectedTypes.singleOrNull().takeIf { candidateFound }
    }

    /** 识别无需继续解析即可确定的 V1 不支持票型。 */
    private fun detectUnsupportedTicket(rows: List<VisualRow>): String? {
        val compactTexts = rows.map { it.text.compactForKeywords() }
        if (compactTexts.any { it.contains(REPRINT_KEYWORD) }) {
            return "识别到补打票，当前版本不支持自动测算"
        }
        if (compactTexts.any { it.contains(MULTI_PERIOD_KEYWORD) }) {
            return "识别到多期投注，V1 仅支持单期彩票"
        }
        val unsupportedPlay =
            compactTexts.firstOrNull { text ->
                UNSUPPORTED_PLAY_KEYWORDS.any { keyword ->
                    (text.startsWith(PLAY_PREFIX) && text.contains(keyword)) ||
                        text.contains("${keyword}票") ||
                        text.contains("${keyword}投注")
                }
            }
        if (unsupportedPlay != null) {
            return "识别到复式或胆拖票，当前版本仅支持单式投注"
        }
        return null
    }

    /** 从带倍数的投注摘要行提取明确期数，避免把开奖期号误识别为期数。 */
    private fun extractExplicitPeriodCounts(rows: List<VisualRow>): Set<Int> =
        rows
            .asSequence()
            .map { it.text.normalizedForParsing() }
            .filter { it.contains(MULTIPLIER_UNIT) }
            .mapNotNull { text ->
                PERIOD_WITH_MULTIPLIER_REGEX
                    .find(text)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
            }.toSet()

    /** 提取所有与彩种长度一致的开奖期号候选，交由主流程判断缺失或冲突。 */
    private fun extractIssueCandidates(
        rows: List<VisualRow>,
        lotteryType: LotteryType,
    ): Set<String> {
        val regex =
            when (lotteryType) {
                LotteryType.SUPER_LOTTO -> SUPER_LOTTO_ISSUE_REGEX
                LotteryType.DOUBLE_COLOR_BALL -> DOUBLE_COLOR_BALL_ISSUE_REGEX
            }
        val candidates =
            rows
                .asSequence()
                .map { it.text.normalizedForParsing() }
                .flatMap { text ->
                    regex
                        .findAll(text)
                        .map { match -> match.groupValues[1] }
                }.toSet()
        return candidates
    }

    /** 解析所有外观上属于投注号码的行，并对数量、范围和顺序执行严格校验。 */
    private fun parseBetRows(
        rows: List<VisualRow>,
        lotteryType: LotteryType,
    ): BetRowsResult {
        val spec = BetNumberSpec.forLottery(lotteryType)
        val parsedRows = mutableListOf<ParsedBetRow>()
        var malformedCandidateFound = false
        rows.forEach { row ->
            val normalized = row.text.normalizedForParsing()
            val plusIndex = normalized.indexOf(PLUS_SIGN)
            if (plusIndex < 0) return@forEach

            val rowMultiplierMatch = ROW_MULTIPLIER_REGEX.find(normalized)
            val primaryNumbers = extractTwoDigitNumbers(normalized.substring(0, plusIndex))
            val secondaryText =
                normalized
                    .substring(plusIndex + 1)
                    .replace(ROW_MULTIPLIER_REGEX, EMPTY_TEXT)
            val secondaryNumbers = extractTwoDigitNumbers(secondaryText)
            val resemblesBetRow =
                primaryNumbers.size + secondaryNumbers.size >= MIN_NUMBER_TOKENS_FOR_BET_CANDIDATE
            if (!resemblesBetRow) return@forEach
            if (primaryNumbers.size != spec.primaryCount || secondaryNumbers.size != spec.secondaryCount) {
                malformedCandidateFound = true
                return@forEach
            }
            if (!numbersAreValid(primaryNumbers, spec.primaryRange) ||
                !numbersAreValid(secondaryNumbers, spec.secondaryRange)
            ) {
                return BetRowsResult.Failed("投注号码存在越界、重复或顺序异常，请人工核对")
            }
            parsedRows +=
                ParsedBetRow(
                    primaryNumbers = primaryNumbers,
                    secondaryNumbers = secondaryNumbers,
                    rowMultiplier = rowMultiplierMatch?.groupValues?.get(1)?.toIntOrNull(),
                    originalText = row.text,
                )
        }
        return when {
            malformedCandidateFound -> BetRowsResult.Failed("至少一行投注号码不完整，请重新拍摄或人工核对")
            parsedRows.isEmpty() -> BetRowsResult.Failed("未识别到完整的单式投注号码")
            else -> BetRowsResult.Success(parsedRows.toList())
        }
    }

    /** 提取独立的两位数字，不把流水号、单个数字或字符混淆解释为投注号码。 */
    private fun extractTwoDigitNumbers(text: String): List<Int> =
        TWO_DIGIT_NUMBER_REGEX
            .findAll(text)
            .mapNotNull { match -> match.value.toIntOrNull() }
            .toList()

    /** 校验号码范围、唯一性和票面升序，任何异常都不自动修正。 */
    private fun numbersAreValid(
        numbers: List<Int>,
        allowedRange: IntRange,
    ): Boolean =
        numbers.all { it in allowedRange } &&
            numbers.distinct().size == numbers.size &&
            numbers.zipWithNext().all { (left, right) -> left < right }

    /** 提取全票统一倍数；双色球可从每行一致的 `xN` 标记取得倍数。 */
    private fun extractMultiplier(
        rows: List<VisualRow>,
        parsedBets: List<ParsedBetRow>,
        lotteryType: LotteryType,
    ): Int? {
        val summaryCandidates =
            rows
                .asSequence()
                .map { it.text.normalizedForParsing() }
                .mapNotNull { text ->
                    EXACT_MULTIPLIER_REGEX
                        .find(text)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull()
                }.toSet()
        if (summaryCandidates.size == 1) return summaryCandidates.single().takeIf { it in VALID_MULTIPLIER_RANGE }
        if (summaryCandidates.size > 1 || lotteryType != LotteryType.DOUBLE_COLOR_BALL) return null

        val rowCandidates = parsedBets.mapNotNull { it.rowMultiplier }.toSet()
        return rowCandidates.singleOrNull()?.takeIf { candidate ->
            candidate in VALID_MULTIPLIER_RANGE && parsedBets.all { it.rowMultiplier == candidate }
        }
    }

    /** 从大乐透投注摘要判断是否追加；摘要缺失时不默认解释为基本投注。 */
    private fun extractSuperLottoAdditional(rows: List<VisualRow>): Boolean? {
        val summaries =
            rows
                .asSequence()
                .map { it.text.normalizedForParsing() }
                .filter { text -> EXACT_MULTIPLIER_REGEX.containsMatchIn(text) }
                .toList()
        if (summaries.isEmpty()) return null
        val additionalValues = summaries.map { it.compactForKeywords().contains(ADDITIONAL_KEYWORD) }.toSet()
        return additionalValues.singleOrNull()
    }

    /** 提取所有带“合计”锚点的金额候选，并精确转换为分。 */
    private fun extractPaidAmountCandidates(rows: List<VisualRow>): Set<Long> {
        val candidates =
            rows
                .asSequence()
                .map { it.text.normalizedForParsing() }
                .flatMap { text ->
                    TOTAL_AMOUNT_REGEX
                        .findAll(text)
                        .mapNotNull { match -> match.groupValues[1].toFenOrNull() }
                }.toSet()
        return candidates
    }

    /** 把平台可能输出的全角字符归一化，但不替换形似数字的字母。 */
    private fun String.normalizedForParsing(): String {
        val builder = StringBuilder(length)
        forEach { character ->
            builder.append(
                when (character) {
                    in FULL_WIDTH_ZERO..FULL_WIDTH_NINE -> ASCII_ZERO + (character - FULL_WIDTH_ZERO)
                    FULL_WIDTH_PLUS -> PLUS_SIGN
                    FULL_WIDTH_COLON -> ASCII_COLON
                    FULL_WIDTH_X_LOWER, FULL_WIDTH_X_UPPER, MULTIPLICATION_SIGN -> ASCII_X
                    IDEOGRAPHIC_SPACE -> ASCII_SPACE
                    else -> character
                },
            )
        }
        return builder.toString()
    }

    /** 移除空白以匹配稳定中文锚点。 */
    private fun String.compactForKeywords(): String =
        normalizedForParsing().filterNot { character -> character.isWhitespace() }

    /** 把十进制元金额精确转换为分，不使用浮点数。 */
    private fun String.toFenOrNull(): Long? {
        val parts = split(DECIMAL_POINT)
        if (parts.size !in 1..2) return null
        val wholeYuan = parts[0].toLongOrNull() ?: return null
        val fractionText = parts.getOrNull(1).orEmpty()
        if (fractionText.length > MAX_FRACTION_DIGITS) return null
        val fractionFen = fractionText.padEnd(MAX_FRACTION_DIGITS, ASCII_ZERO).toLongOrNull() ?: 0L
        if (wholeYuan > (Long.MAX_VALUE - fractionFen) / FEN_PER_YUAN) return null
        return wholeYuan * FEN_PER_YUAN + fractionFen
    }

    /** 按归一化纵向位置合并同一视觉行中的 OCR 片段。 */
    private fun mergeVisualRows(lines: List<OcrTextLine>): List<VisualRow> {
        val groups = mutableListOf<MutableList<OcrTextLine>>()
        lines
            .filter { it.text.isNotBlank() }
            .sortedWith(compareBy<OcrTextLine> { it.bounds.top }.thenBy { it.bounds.left })
            .forEach { line ->
                val currentGroup = groups.lastOrNull()
                if (currentGroup != null && belongsToSameVisualRow(currentGroup, line)) {
                    currentGroup += line
                } else {
                    groups += mutableListOf(line)
                }
            }
        return groups.map { group ->
            val ordered = group.sortedBy { it.bounds.left }
            VisualRow(text = ordered.joinToString(separator = ASCII_SPACE.toString()) { it.text.trim() })
        }
    }

    /** 判断一个 OCR 片段是否与当前分组位于同一视觉行。 */
    private fun belongsToSameVisualRow(
        group: List<OcrTextLine>,
        candidate: OcrTextLine,
    ): Boolean {
        val groupCenter = group.map { it.bounds.verticalCenter }.average().toFloat()
        val tallestHeight = (group.maxOfOrNull { it.bounds.height } ?: 0f).coerceAtLeast(candidate.bounds.height)
        val tolerance = (tallestHeight * SAME_ROW_HEIGHT_RATIO).coerceAtLeast(MIN_SAME_ROW_TOLERANCE)
        return kotlin.math.abs(groupCenter - candidate.bounds.verticalCenter) <= tolerance
    }

    /** 归一化边界的垂直中心。 */
    private val NormalizedBounds.verticalCenter: Float
        get() = (top + bottom) / 2f

    /** 归一化边界的非负高度。 */
    private val NormalizedBounds.height: Float
        get() = kotlin.math.abs(bottom - top)

    /** 彩种检测结果。 */
    private sealed interface LotteryTypeDetection {
        /**
         * 已唯一识别彩种。
         *
         * @property lotteryType 识别到的彩种。
         */
        data class Known(
            val lotteryType: LotteryType,
        ) : LotteryTypeDetection

        /**
         * 彩种标题缺失或互相冲突。
         *
         * @property message 面向用户的修正说明。
         */
        data class Failed(
            val message: String,
        ) : LotteryTypeDetection
    }

    /** 投注号码行解析结果。 */
    private sealed interface BetRowsResult {
        /**
         * 所有候选投注行均完整合法。
         *
         * @property rows 按票面顺序保留的投注行。
         */
        data class Success(
            val rows: List<ParsedBetRow>,
        ) : BetRowsResult

        /**
         * 投注号码缺失或存在不允许猜测的异常。
         *
         * @property message 面向用户的修正说明。
         */
        data class Failed(
            val message: String,
        ) : BetRowsResult
    }

    /**
     * 一行已严格解析但尚未进入领域模型的投注。
     *
     * @property primaryNumbers 前区或红球号码。
     * @property secondaryNumbers 后区或蓝球号码。
     * @property rowMultiplier 双色球行尾倍数，未识别时为 `null`。
     * @property originalText 供确认界面对照的 OCR 行原文。
     */
    private data class ParsedBetRow(
        val primaryNumbers: List<Int>,
        val secondaryNumbers: List<Int>,
        val rowMultiplier: Int?,
        val originalText: String,
    )

    /**
     * 合并后的视觉文字行。
     *
     * @property text 按横向顺序连接的原始 OCR 片段。
     */
    private data class VisualRow(
        val text: String,
    )

    /** 彩票单式号码规格。 */
    private data class BetNumberSpec(
        /** 主号码数量。 */
        val primaryCount: Int,
        /** 主号码合法范围。 */
        val primaryRange: IntRange,
        /** 次号码数量。 */
        val secondaryCount: Int,
        /** 次号码合法范围。 */
        val secondaryRange: IntRange,
    ) {
        /** 彩票号码规格工厂。 */
        companion object {
            /** 返回指定彩种的 V1 单式号码规格。 */
            fun forLottery(lotteryType: LotteryType): BetNumberSpec =
                when (lotteryType) {
                    LotteryType.SUPER_LOTTO -> BetNumberSpec(5, 1..35, 2, 1..12)
                    LotteryType.DOUBLE_COLOR_BALL -> BetNumberSpec(6, 1..33, 1, 1..16)
                }
        }
    }

    /** 解析器使用的稳定锚点、正则表达式和金额常量。 */
    private companion object {
        /** 大乐透标题关键词。 */
        const val SUPER_LOTTO_KEYWORD = "大乐透"

        /** 双色球标题关键词。 */
        const val DOUBLE_COLOR_BALL_KEYWORD = "双色球"

        /** 福利彩票发行机构关键词。 */
        const val WELFARE_LOTTERY_ISSUER_KEYWORD = "福利彩票"

        /** 体育彩票发行机构关键词。 */
        const val SPORTS_LOTTERY_ISSUER_KEYWORD = "中国体育彩票"

        /** 补打票关键词。 */
        const val REPRINT_KEYWORD = "补打票"

        /** 多期投注关键词。 */
        const val MULTI_PERIOD_KEYWORD = "多期投注"

        /** 大乐透追加投注关键词。 */
        const val ADDITIONAL_KEYWORD = "追加投注"

        /** 不支持的复杂玩法关键词。 */
        val UNSUPPORTED_PLAY_KEYWORDS = listOf("复式", "胆拖")

        /** 玩法标题前缀。 */
        const val PLAY_PREFIX = "玩法"

        /** 大乐透五位开奖期号。 */
        val SUPER_LOTTO_ISSUE_REGEX = Regex("(?:第\\s*)?(?<!\\d)(\\d{5})\\s*期(?!\\d)")

        /** 双色球七位开奖期号，不匹配销售期后缀。 */
        val DOUBLE_COLOR_BALL_ISSUE_REGEX = Regex("(?:开奖期|第)\\s*[:：]?\\s*(\\d{7})(?![\\d-])")

        /** 带期数和倍数单位的投注摘要。 */
        val PERIOD_WITH_MULTIPLIER_REGEX = Regex("(?<![第\\d])(\\d{1,2})\\s*期(?=.{0,4}倍)")

        /** 只接受阿拉伯数字组成的明确倍数。 */
        val EXACT_MULTIPLIER_REGEX = Regex("(?<![\\dA-Za-z])(\\d{1,2})\\s*倍")

        /** 双色球投注行末尾的倍数。 */
        val ROW_MULTIPLIER_REGEX = Regex("[xX]\\s*(\\d{1,2})(?!\\d)")

        /** 与其他数字边界分离的两位号码。 */
        val TWO_DIGIT_NUMBER_REGEX = Regex("(?<!\\d)\\d{2}(?!\\d)")

        /** 带合计锚点的元金额。 */
        val TOTAL_AMOUNT_REGEX = Regex("(?:合计|总计)\\s*[:：]?\\s*(\\d+(?:\\.\\d{1,2})?)\\s*元")

        /** 合法投注倍数范围。 */
        val VALID_MULTIPLIER_RANGE = 1..99

        /** V1 只支持一期投注。 */
        const val V1_PERIOD_COUNT = 1

        /** 一行至少出现这些两位数字才视为疑似投注行。 */
        const val MIN_NUMBER_TOKENS_FOR_BET_CANDIDATE = 5

        /** 同一视觉行允许的相对字符高度偏差。 */
        const val SAME_ROW_HEIGHT_RATIO = 0.6f

        /** 同一视觉行允许的最小归一化中心偏差。 */
        const val MIN_SAME_ROW_TOLERANCE = 0.008f

        /** 每元包含的分数。 */
        const val FEN_PER_YUAN = 100L

        /** 金额最多允许两位小数。 */
        const val MAX_FRACTION_DIGITS = 2

        /** 倍数中文单位。 */
        const val MULTIPLIER_UNIT = '倍'

        /** 加号分隔符。 */
        const val PLUS_SIGN = '+'

        /** 全角加号。 */
        const val FULL_WIDTH_PLUS = '＋'

        /** 全角冒号。 */
        const val FULL_WIDTH_COLON = '：'

        /** ASCII 冒号。 */
        const val ASCII_COLON = ':'

        /** 全角小写 X。 */
        const val FULL_WIDTH_X_LOWER = 'ｘ'

        /** 全角大写 X。 */
        const val FULL_WIDTH_X_UPPER = 'Ｘ'

        /** 乘号。 */
        const val MULTIPLICATION_SIGN = '×'

        /** ASCII 小写 X。 */
        const val ASCII_X = 'x'

        /** 中文全角空格。 */
        const val IDEOGRAPHIC_SPACE = '　'

        /** ASCII 空格。 */
        const val ASCII_SPACE = ' '

        /** 全角数字零。 */
        const val FULL_WIDTH_ZERO = '０'

        /** 全角数字九。 */
        const val FULL_WIDTH_NINE = '９'

        /** ASCII 数字零。 */
        const val ASCII_ZERO = '0'

        /** 用于删除已独立解析标记的空文本。 */
        const val EMPTY_TEXT = ""

        /** 十进制小数点。 */
        const val DECIMAL_POINT = '.'
    }
}

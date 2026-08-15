package roc.win.lottery.recognition

import roc.win.lottery.domain.BetLineDraft
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.TicketAmountCalculator
import roc.win.lottery.domain.TicketDraft

/**
 * 从平台标准 OCR 行中保守提取单式票字段。
 *
 * 解析器只接受能够由票面文字、坐标结构和金额关系共同证明的值。补打、复式和胆拖会被明确阻断；
 * 多期票会保留已识别字段进入人工核对，并由领域层限制可安全逐期查询的范围。
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
        val issueCandidates = extractIssueCandidates(rows, lotteryType)
        if (issueCandidates.map { it.value }.toSet().size > 1) {
            return TicketParseResult.NeedsCorrection("识别到多个开奖期号，请重新拍摄或人工核对")
        }
        val issueCandidate = issueCandidates.firstOrNull()
        val issue = issueCandidate?.value.orEmpty()
        val parsedBets =
            when (val result = parseBetRows(rows, lotteryType)) {
                is BetRowsResult.Success -> result.rows
                is BetRowsResult.Failed -> return TicketParseResult.NeedsCorrection(result.message)
            }
        val explicitMultiplierExtraction = extractMultiplier(rows, parsedBets, lotteryType)
        val additionalExtraction =
            when (lotteryType) {
                LotteryType.SUPER_LOTTO -> extractSuperLottoAdditional(rows)
                LotteryType.DOUBLE_COLOR_BALL -> FieldExtraction(value = false, bounds = null)
            }
        val isAdditional = additionalExtraction.value
        val paidAmountCandidates = extractPaidAmountCandidates(rows)
        if (paidAmountCandidates.map { it.value }.toSet().size > 1) {
            return TicketParseResult.NeedsCorrection("识别到多个票面合计金额，请重新拍摄或人工核对")
        }
        val paidAmountCandidate = paidAmountCandidates.firstOrNull()
        val paidAmountFen = paidAmountCandidate?.value
        val hasConflictingMultiplierEvidence =
            explicitMultiplierExtraction.value == null && explicitMultiplierExtraction.bounds != null
        val recoveredSummary =
            if (
                (explicitPeriodCount == null || explicitMultiplierExtraction.value == null) &&
                !hasConflictingMultiplierEvidence
            ) {
                recoverDamagedSummary(
                    rows = rows,
                    lotteryType = lotteryType,
                    betLineCount = parsedBets.size,
                    isAdditional = additionalExtraction.value,
                    paidAmountFen = paidAmountFen,
                    knownPeriodCount = explicitPeriodCount,
                    knownMultiplier = explicitMultiplierExtraction.value,
                )
            } else {
                null
            }
        val multiplierExtraction =
            if (explicitMultiplierExtraction.value != null) {
                explicitMultiplierExtraction
            } else {
                FieldExtraction(
                    recoveredSummary?.multiplier,
                    recoveredSummary?.bounds ?: explicitMultiplierExtraction.bounds,
                )
            }
        val multiplier = multiplierExtraction.value
        val periodCount = explicitPeriodCount ?: recoveredSummary?.periodCount ?: V1_PERIOD_COUNT
        val expectedAmountFen =
            if (multiplier != null && isAdditional != null) {
                TicketAmountCalculator.calculate(
                    lotteryType = lotteryType,
                    betLineCount = parsedBets.size,
                    additionalLineCount = if (isAdditional) parsedBets.size else 0,
                    multiplier = multiplier,
                    periodCount = periodCount,
                )
            } else {
                null
            }
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
        val fieldRegions =
            buildList {
                issueCandidate?.let { candidate ->
                    add(TicketFieldRegion(TicketFieldReference.Issue, candidate.bounds))
                }
                parsedBets.forEachIndexed { index, parsed ->
                    add(TicketFieldRegion(TicketFieldReference.BetLine(index), parsed.bounds))
                }
                multiplierExtraction.bounds?.let { bounds ->
                    add(TicketFieldRegion(TicketFieldReference.Multiplier, bounds))
                }
                if (lotteryType == LotteryType.SUPER_LOTTO) {
                    additionalExtraction.bounds?.let { bounds ->
                        add(TicketFieldRegion(TicketFieldReference.Additional, bounds))
                    }
                }
                paidAmountCandidate?.let { candidate ->
                    add(TicketFieldRegion(TicketFieldReference.PaidAmount, candidate.bounds))
                }
            }
        val correctionMessages =
            buildList {
                if (issue.isEmpty()) {
                    add("期号缺失或格式错误，请在票面校正页补充")
                }
                if (multiplier == null) {
                    add("投注倍数缺失、存在字符混淆或多个候选值，请明确选择")
                }
                if (isAdditional == null) {
                    add("大乐透追加属性缺失或存在冲突，请明确选择")
                }
                if (paidAmountFen == null) {
                    add("票面合计金额缺失或格式错误，请在票面校正页补充")
                } else if (expectedAmountFen != null && expectedAmountFen != paidAmountFen) {
                    add("票面金额与投注行、倍数、期数或追加属性不一致")
                }
                if (multiplier != null && isAdditional != null && expectedAmountFen == null) {
                    add("无法根据当前投注结构计算金额")
                }
            }
        if (correctionMessages.isNotEmpty()) {
            return TicketParseResult.NeedsCorrection(
                message = correctionMessages.joinToString(separator = "；"),
                draft = draft,
                fieldRegions = fieldRegions,
            )
        }

        return TicketParseResult.ReadyForReview(draft, fieldRegions)
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

        val hasWelfareLotteryIssuer =
            compactTexts.any { text ->
                text.contains(WELFARE_LOTTERY_ISSUER_KEYWORD) ||
                    text.contains(WELFARE_LOTTERY_ISSUER_ABBREVIATION)
            }
        val hasSportsLotteryIssuer =
            compactTexts.any { text ->
                text.contains(SPORTS_LOTTERY_ISSUER_KEYWORD) ||
                    text.contains(SPORTS_LOTTERY_ISSUER_ABBREVIATION)
            }
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

    /** 从所有疑似投注行提取唯一合法的 `6+1` 或 `5+2` 结构；任何残缺或混合结构都会失败。 */
    private fun detectUniqueStrictBetStructure(rows: List<VisualRow>): LotteryType? {
        val detectedTypes = mutableSetOf<LotteryType>()
        var candidateFound = false
        rows.forEach { row ->
            val normalized = row.text.normalizedForParsing()
            val matchingTypes =
                LotteryType.entries.filter { lotteryType ->
                    val spec = BetNumberSpec.forLottery(lotteryType)
                    val split = extractBetNumberSplit(row, spec) ?: return@filter false
                    numbersAreValid(split.primaryNumbers, spec.primaryRange) &&
                        numbersAreValid(split.secondaryNumbers, spec.secondaryRange)
                }
            if (matchingTypes.isEmpty()) {
                val explicitCandidate =
                    normalized.contains(PLUS_SIGN) &&
                        extractTwoDigitNumbers(normalized).size >= MIN_NUMBER_TOKENS_FOR_BET_CANDIDATE
                if (explicitCandidate) return null
                return@forEach
            }
            candidateFound = true
            if (matchingTypes.size != 1) return null
            detectedTypes += matchingTypes.single()
        }
        return detectedTypes.singleOrNull().takeIf { candidateFound }
    }

    /** 识别无需继续解析即可确定的不支持票型。 */
    private fun detectUnsupportedTicket(rows: List<VisualRow>): String? {
        val compactTexts = rows.map { it.text.compactForKeywords() }
        if (compactTexts.any { it.contains(REPRINT_KEYWORD) }) {
            return "识别到补打票，当前版本不支持自动测算"
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
            .mapNotNull { row ->
                val text = row.text.normalizedForParsing()
                PERIOD_WITH_MULTIPLIER_REGEX
                    .find(text)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
                    ?: extractDegradedSummary(row)?.periodCount
            }.toSet()

    /** 提取所有与彩种长度一致的开奖期号候选，交由主流程判断缺失或冲突。 */
    private fun extractIssueCandidates(
        rows: List<VisualRow>,
        lotteryType: LotteryType,
    ): List<LocatedValue<String>> {
        val regex =
            when (lotteryType) {
                LotteryType.SUPER_LOTTO -> SUPER_LOTTO_ISSUE_REGEX
                LotteryType.DOUBLE_COLOR_BALL -> DOUBLE_COLOR_BALL_ISSUE_REGEX
            }
        val candidates =
            rows
                .asSequence()
                .flatMap { row ->
                    val text = row.text.normalizedForParsing()
                    regex
                        .findAll(text)
                        .map { match -> LocatedValue(match.groupValues[1], row.bounds) }
                }.toList()
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
            val split = extractBetNumberSplit(row, spec)
            if (split == null) {
                if (
                    normalized.contains(PLUS_SIGN) &&
                    extractTwoDigitNumbers(normalized).size >= MIN_NUMBER_TOKENS_FOR_BET_CANDIDATE
                ) {
                    malformedCandidateFound = true
                }
                return@forEach
            }
            if (!numbersAreValid(split.primaryNumbers, spec.primaryRange) ||
                !numbersAreValid(split.secondaryNumbers, spec.secondaryRange)
            ) {
                return BetRowsResult.Failed("投注号码存在越界、重复或顺序异常，请人工核对")
            }
            parsedRows +=
                ParsedBetRow(
                    primaryNumbers = split.primaryNumbers,
                    secondaryNumbers = split.secondaryNumbers,
                    rowMultiplier = split.rowMultiplier,
                    originalText = row.text,
                    bounds = row.bounds,
                )
        }
        return when {
            malformedCandidateFound -> BetRowsResult.Failed("至少一行投注号码不完整，请重新拍摄或人工核对")
            parsedRows.isEmpty() -> BetRowsResult.Failed("未识别到完整的单式投注号码")
            else -> BetRowsResult.Success(parsedRows.toList())
        }
    }

    /**
     * 提取一行投注号码；加号缺失时只接受数量、范围、顺序和横向大间隔同时吻合的结构。
     */
    private fun extractBetNumberSplit(
        row: VisualRow,
        spec: BetNumberSpec,
    ): BetNumberSplit? {
        val normalized = row.text.normalizedForParsing()
        val rowMultiplier =
            ROW_MULTIPLIER_REGEX
                .find(normalized)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
        val plusIndex = normalized.indexOf(PLUS_SIGN)
        if (plusIndex >= 0) {
            val primaryNumbers = extractTwoDigitNumbers(normalized.substring(0, plusIndex))
            val secondaryNumbers =
                extractTwoDigitNumbers(
                    normalized
                        .substring(plusIndex + 1)
                        .replace(ROW_MULTIPLIER_REGEX, EMPTY_TEXT),
                )
            if (primaryNumbers.size != spec.primaryCount || secondaryNumbers.size != spec.secondaryCount) {
                return null
            }
            return BetNumberSplit(primaryNumbers, secondaryNumbers, rowMultiplier)
        }

        val locatedNumbers = extractLocatedTwoDigitNumbers(row)
        val totalNumberCount = spec.primaryCount + spec.secondaryCount
        if (locatedNumbers.size != totalNumberCount ||
            !hasReliableInferredSeparator(locatedNumbers, spec.primaryCount)
        ) {
            return null
        }
        return BetNumberSplit(
            primaryNumbers = locatedNumbers.take(spec.primaryCount).map(LocatedNumber::value),
            secondaryNumbers = locatedNumbers.drop(spec.primaryCount).map(LocatedNumber::value),
            rowMultiplier = rowMultiplier,
        )
    }

    /** 提取带横向中心位置的两位号码，并排除双色球行尾倍数。 */
    private fun extractLocatedTwoDigitNumbers(row: VisualRow): List<LocatedNumber> =
        row.fragments
            .flatMap { fragment ->
                val text = fragment.text.normalizedForParsing()
                if (text.isEmpty()) return@flatMap emptyList()
                val multiplierRanges = ROW_MULTIPLIER_REGEX.findAll(text).map { it.range }.toList()
                TWO_DIGIT_NUMBER_REGEX
                    .findAll(text)
                    .filterNot { match ->
                        multiplierRanges.any { range ->
                            match.range.first <= range.last && range.first <= match.range.last
                        }
                    }.map { match ->
                        val relativeCenter =
                            (match.range.first + match.range.last + 1).toFloat() /
                                (2f * text.length.toFloat())
                        LocatedNumber(
                            value = match.value.toInt(),
                            horizontalCenter =
                                fragment.bounds.left +
                                    (fragment.bounds.right - fragment.bounds.left) * relativeCenter,
                        )
                    }.toList()
            }.sortedBy(LocatedNumber::horizontalCenter)

    /** 只有主区与次区之间是唯一明显横向大间隔时，才补偿 OCR 漏掉的加号。 */
    private fun hasReliableInferredSeparator(
        numbers: List<LocatedNumber>,
        primaryCount: Int,
    ): Boolean {
        if (primaryCount <= 0 || primaryCount >= numbers.size) return false
        val gaps = numbers.zipWithNext { left, right -> right.horizontalCenter - left.horizontalCenter }
        val separatorIndex = primaryCount - 1
        val separatorGap = gaps[separatorIndex]
        val largestOtherGap =
            gaps
                .filterIndexed { index, _ -> index != separatorIndex }
                .maxOrNull()
                ?: 0f
        return separatorGap >= MIN_INFERRED_SEPARATOR_GAP &&
            separatorGap >= largestOtherGap * MIN_INFERRED_SEPARATOR_DOMINANCE
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

    /** 提取全票统一倍数及所在区域；双色球可从每行一致的 `xN` 标记取得倍数。 */
    private fun extractMultiplier(
        rows: List<VisualRow>,
        parsedBets: List<ParsedBetRow>,
        lotteryType: LotteryType,
    ): FieldExtraction<Int> {
        val summaryCandidates =
            rows
                .asSequence()
                .mapNotNull { row ->
                    val text = row.text.normalizedForParsing()
                    val value =
                        EXACT_MULTIPLIER_REGEX
                            .find(text)
                            ?.groupValues
                            ?.get(1)
                            ?.toIntOrNull()
                            ?: extractDegradedSummary(row)?.multiplier
                    value?.let { LocatedValue(it, row.bounds) }
                }.toList()
        if (summaryCandidates.isNotEmpty()) {
            val values = summaryCandidates.map { it.value }.toSet()
            return FieldExtraction(
                value = values.singleOrNull()?.takeIf { it in VALID_MULTIPLIER_RANGE },
                bounds = summaryCandidates.map { it.bounds }.coveringBoundsOrNull(),
            )
        }
        if (lotteryType != LotteryType.DOUBLE_COLOR_BALL) return FieldExtraction(value = null, bounds = null)

        val rowsWithMultiplier = parsedBets.filter { it.rowMultiplier != null }
        val rowCandidates = rowsWithMultiplier.mapNotNull { it.rowMultiplier }.toSet()
        val value =
            rowCandidates.singleOrNull()?.takeIf { candidate ->
                candidate in VALID_MULTIPLIER_RANGE && parsedBets.all { it.rowMultiplier == candidate }
            }
        return FieldExtraction(
            value = value,
            bounds = rowsWithMultiplier.map { it.bounds }.coveringBoundsOrNull(),
        )
    }

    /** 从大乐透投注摘要判断是否追加并保留区域；摘要缺失时不默认解释为基本投注。 */
    private fun extractSuperLottoAdditional(rows: List<VisualRow>): FieldExtraction<Boolean> {
        val summaries =
            rows
                .asSequence()
                .mapNotNull { row ->
                    val text = row.text.normalizedForParsing()
                    val compactText = text.compactForKeywords()
                    if (
                        EXACT_MULTIPLIER_REGEX.containsMatchIn(text) ||
                        extractDegradedSummary(row) != null ||
                        compactText.contains(ADDITIONAL_KEYWORD) ||
                        compactText.contains(SINGLE_PLAY_KEYWORD)
                    ) {
                        LocatedValue(compactText.contains(ADDITIONAL_KEYWORD), row.bounds)
                    } else {
                        null
                    }
                }.toList()
        return FieldExtraction(
            value = summaries.map { it.value }.toSet().singleOrNull(),
            bounds = summaries.map { it.bounds }.coveringBoundsOrNull(),
        )
    }

    /** 仅在“单式票或追加投注 + 两个数值 + 合计”完整锚点内恢复被 OCR 损坏的期数和倍数单位。 */
    private fun extractDegradedSummary(row: VisualRow): DegradedSummary? {
        val match = DEGRADED_SUMMARY_REGEX.find(row.text.normalizedForParsing()) ?: return null
        val periodCount = match.groupValues[1].toIntOrNull() ?: return null
        val multiplier = match.groupValues[2].toIntOrNull() ?: return null
        if (periodCount !in VALID_PERIOD_COUNT_RANGE || multiplier !in VALID_MULTIPLIER_RANGE) return null
        return DegradedSummary(periodCount, multiplier)
    }

    /**
     * 单位被吞并成连续数字时，仅依靠完整摘要锚点和精确金额关系恢复唯一的期数、倍数。
     *
     * 任一必要字段缺失、候选不唯一或金额不一致时都不恢复，继续交给用户人工确认。
     */
    private fun recoverDamagedSummary(
        rows: List<VisualRow>,
        lotteryType: LotteryType,
        betLineCount: Int,
        isAdditional: Boolean?,
        paidAmountFen: Long?,
        knownPeriodCount: Int?,
        knownMultiplier: Int?,
    ): RecoveredSummary? {
        if (isAdditional == null || paidAmountFen == null || betLineCount <= 0) return null
        val candidates =
            rows.flatMap { row ->
                val body =
                    SUMMARY_BODY_REGEX
                        .find(row.text.normalizedForParsing())
                        ?.groupValues
                        ?.get(1)
                        ?: return@flatMap emptyList()
                damagedSummaryCandidates(body)
                    .filter { candidate ->
                        (knownPeriodCount == null || candidate.first == knownPeriodCount) &&
                            (knownMultiplier == null || candidate.second == knownMultiplier)
                    }.filter { (periodCount, multiplier) ->
                        TicketAmountCalculator.calculate(
                            lotteryType = lotteryType,
                            betLineCount = betLineCount,
                            additionalLineCount = if (isAdditional) betLineCount else 0,
                            multiplier = multiplier,
                            periodCount = periodCount,
                        ) == paidAmountFen
                    }.map { (periodCount, multiplier) ->
                        RecoveredSummary(periodCount, multiplier, row.bounds)
                    }
            }
        val uniqueValues = candidates.map { it.periodCount to it.multiplier }.distinct()
        val unique = uniqueValues.singleOrNull() ?: return null
        return RecoveredSummary(
            periodCount = unique.first,
            multiplier = unique.second,
            bounds =
                candidates
                    .filter { it.periodCount == unique.first && it.multiplier == unique.second }
                    .map { it.bounds }
                    .coveringBoundsOrNull()
                    ?: return null,
        )
    }

    /** 从受损摘要中的连续数字生成有限的期数、倍数组合，不改变数字顺序。 */
    private fun damagedSummaryCandidates(body: String): Set<Pair<Int, Int>> {
        val compact = body.filterNot(Char::isWhitespace)
        if (compact.length !in MIN_DAMAGED_SUMMARY_LENGTH..MAX_DAMAGED_SUMMARY_LENGTH) return emptySet()
        val digits = compact.filter(Char::isDigit)
        if (digits.length !in MIN_DAMAGED_DIGIT_COUNT..MAX_DAMAGED_DIGIT_COUNT) return emptySet()
        if (compact.length - digits.length > MAX_DAMAGED_NOISE_COUNT) return emptySet()

        val variants =
            buildSet {
                add(digits)
                if (digits.length >= MIN_DIGITS_ALLOWING_ONE_NOISE_REMOVAL) {
                    digits.indices.forEach { index -> add(digits.removeRange(index, index + 1)) }
                }
            }
        return buildSet {
            variants.forEach { variant ->
                for (splitIndex in 1 until variant.length) {
                    val periodRaw = variant.substring(0, splitIndex)
                    val multiplierRaw = variant.substring(splitIndex)
                    if (periodRaw.length > MAX_SUMMARY_NUMBER_LENGTH ||
                        multiplierRaw.length > MAX_SUMMARY_NUMBER_LENGTH ||
                        periodRaw.startsWith(ASCII_ZERO) ||
                        multiplierRaw.startsWith(ASCII_ZERO)
                    ) {
                        continue
                    }
                    val periodCount = periodRaw.toIntOrNull() ?: continue
                    val multiplier = multiplierRaw.toIntOrNull() ?: continue
                    if (periodCount in VALID_PERIOD_COUNT_RANGE && multiplier in VALID_MULTIPLIER_RANGE) {
                        add(periodCount to multiplier)
                    }
                }
            }
        }
    }

    /** 提取所有带“合计”锚点的金额候选，并精确转换为分。 */
    private fun extractPaidAmountCandidates(rows: List<VisualRow>): List<LocatedValue<Long>> {
        val candidates =
            rows
                .asSequence()
                .flatMap { row ->
                    val text = row.text.normalizedForParsing()
                    TOTAL_AMOUNT_REGEX
                        .findAll(text)
                        .mapNotNull { match ->
                            match.groupValues[1].toFenOrNull()?.let { value ->
                                LocatedValue(value, row.bounds)
                            }
                        }
                }.toList()
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
            VisualRow(
                text = ordered.joinToString(separator = ASCII_SPACE.toString()) { it.text.trim() },
                bounds =
                    NormalizedBounds(
                        left = ordered.minOf { it.bounds.left },
                        top = ordered.minOf { it.bounds.top },
                        right = ordered.maxOf { it.bounds.right },
                        bottom = ordered.maxOf { it.bounds.bottom },
                    ),
                fragments = ordered,
            )
        }
    }

    /** 判断一个 OCR 片段是否与当前分组位于同一视觉行。 */
    private fun belongsToSameVisualRow(
        group: List<OcrTextLine>,
        candidate: OcrTextLine,
    ): Boolean {
        if (candidate.isLikelyVerticalText || group.any { line -> line.isLikelyVerticalText }) {
            return false
        }
        val groupCenter = group.map { it.bounds.verticalCenter }.average().toFloat()
        val tallestHeight = (group.maxOfOrNull { it.bounds.height } ?: 0f).coerceAtLeast(candidate.bounds.height)
        val tolerance = (tallestHeight * SAME_ROW_HEIGHT_RATIO).coerceAtLeast(MIN_SAME_ROW_TOLERANCE)
        return kotlin.math.abs(groupCenter - candidate.bounds.verticalCenter) <= tolerance
    }

    /** 判断 OCR 行是否是票面侧边纵排文字，避免它把多行号码错误粘连。 */
    private val OcrTextLine.isLikelyVerticalText: Boolean
        get() =
            text.count { character -> !character.isWhitespace() } >= MIN_VERTICAL_TEXT_LENGTH &&
                bounds.height >= bounds.width * VERTICAL_TEXT_ASPECT_RATIO

    /** 归一化边界的垂直中心。 */
    private val NormalizedBounds.verticalCenter: Float
        get() = (top + bottom) / 2f

    /** 归一化边界的非负高度。 */
    private val NormalizedBounds.height: Float
        get() = kotlin.math.abs(bottom - top)

    /** 归一化边界的非负宽度。 */
    private val NormalizedBounds.width: Float
        get() = kotlin.math.abs(right - left)

    /** 返回所有区域的最小覆盖矩形；列表为空时返回 `null`。 */
    private fun List<NormalizedBounds>.coveringBoundsOrNull(): NormalizedBounds? {
        if (isEmpty()) return null
        return NormalizedBounds(
            left = minOf { it.left },
            top = minOf { it.top },
            right = maxOf { it.right },
            bottom = maxOf { it.bottom },
        )
    }

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
     * @property bounds 投注行在原始校正图中的归一化边界。
     */
    private data class ParsedBetRow(
        val primaryNumbers: List<Int>,
        val secondaryNumbers: List<Int>,
        val rowMultiplier: Int?,
        val originalText: String,
        val bounds: NormalizedBounds,
    )

    /**
     * 一行按彩票规则分区后的号码。
     *
     * @property primaryNumbers 前区或红球号码。
     * @property secondaryNumbers 后区或蓝球号码。
     * @property rowMultiplier 双色球行尾倍数，未识别时为 `null`。
     */
    private data class BetNumberSplit(
        val primaryNumbers: List<Int>,
        val secondaryNumbers: List<Int>,
        val rowMultiplier: Int?,
    )

    /**
     * 保留横向位置的两位号码。
     *
     * @property value 两位号码值。
     * @property horizontalCenter 号码在原图中的归一化横向中心。
     */
    private data class LocatedNumber(
        val value: Int,
        val horizontalCenter: Float,
    )

    /**
     * 一个保留 OCR 视觉行位置的已解析值。
     *
     * @property value 已解析字段值。
     * @property bounds 字段所在视觉行的归一化边界。
     */
    private data class LocatedValue<T>(
        val value: T,
        val bounds: NormalizedBounds,
    )

    /**
     * 一个允许值待人工确认、同时保留候选区域的字段提取结果。
     *
     * @property value 唯一合法值；缺失、非法或冲突时为 `null`。
     * @property bounds 所有相关 OCR 视觉行的最小覆盖区域。
     */
    private data class FieldExtraction<T>(
        val value: T?,
        val bounds: NormalizedBounds?,
    )

    /**
     * 投注摘要单位损坏后，仍由稳定锚点限定的期数和倍数。
     *
     * @property periodCount 投注期数。
     * @property multiplier 投注倍数。
     */
    private data class DegradedSummary(
        val periodCount: Int,
        val multiplier: Int,
    )

    /**
     * 由摘要锚点和金额关系唯一恢复的投注参数。
     *
     * @property periodCount 投注期数。
     * @property multiplier 投注倍数。
     * @property bounds 摘要在原图中的归一化区域。
     */
    private data class RecoveredSummary(
        val periodCount: Int,
        val multiplier: Int,
        val bounds: NormalizedBounds,
    )

    /**
     * 合并后的视觉文字行。
     *
     * @property text 按横向顺序连接的原始 OCR 片段。
     * @property bounds 合并后覆盖所有片段的归一化边界。
     * @property fragments 按横向位置排序的原始 OCR 片段。
     */
    private data class VisualRow(
        val text: String,
        val bounds: NormalizedBounds,
        val fragments: List<OcrTextLine>,
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

        /** 只能与唯一合法号码结构联合使用的福利彩票简称。 */
        const val WELFARE_LOTTERY_ISSUER_ABBREVIATION = "福彩"

        /** 体育彩票发行机构关键词。 */
        const val SPORTS_LOTTERY_ISSUER_KEYWORD = "中国体育彩票"

        /** 只能与唯一合法号码结构联合使用的体育彩票简称。 */
        const val SPORTS_LOTTERY_ISSUER_ABBREVIATION = "体彩"

        /** 补打票关键词。 */
        const val REPRINT_KEYWORD = "补打票"

        /** 大乐透追加投注关键词。 */
        const val ADDITIONAL_KEYWORD = "追加投注"

        /** 明确表示基本单式投注的关键词。 */
        const val SINGLE_PLAY_KEYWORD = "单式票"

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

        /** 期、倍单位损坏时，只在完整投注摘要锚点之间提取两个数值。 */
        val DEGRADED_SUMMARY_REGEX =
            Regex("(?:单式票|追加投注)\\s*(\\d{1,2})\\D{1,4}(\\d{1,2})\\D{0,4}(?=(?:合计|总计))")

        /** 单式或追加摘要与合计金额之间的短文本。 */
        val SUMMARY_BODY_REGEX = Regex("(?:单式票|追加投注)\\s*(.{2,12}?)\\s*(?=(?:合计|总计))")

        /** 双色球投注行末尾的倍数。 */
        val ROW_MULTIPLIER_REGEX = Regex("[xX]\\s*(\\d{1,2})(?!\\d)")

        /** 与其他数字边界分离的两位号码。 */
        val TWO_DIGIT_NUMBER_REGEX = Regex("(?<!\\d)\\d{2}(?!\\d)")

        /** 带合计锚点的元金额。 */
        val TOTAL_AMOUNT_REGEX = Regex("(?:合计|总计)\\s*[:：]?\\s*(\\d+(?:\\.\\d{1,2})?)\\s*元")

        /** 合法投注倍数范围。 */
        val VALID_MULTIPLIER_RANGE = 1..99

        /** 票面摘要允许识别的投注期数范围。 */
        val VALID_PERIOD_COUNT_RANGE = 1..99

        /** 缺失明确期数时采用的单期值。 */
        const val V1_PERIOD_COUNT = 1

        /** 一行至少出现这些两位数字才视为疑似投注行。 */
        const val MIN_NUMBER_TOKENS_FOR_BET_CANDIDATE = 5

        /** 漏识别加号时，主区与次区之间至少需要的归一化横向间隔。 */
        const val MIN_INFERRED_SEPARATOR_GAP = 0.055f

        /** 主次区间隔相对其他号码间隔至少需要达到的倍数。 */
        const val MIN_INFERRED_SEPARATOR_DOMINANCE = 1.35f

        /** 同一视觉行允许的相对字符高度偏差。 */
        const val SAME_ROW_HEIGHT_RATIO = 0.6f

        /** 同一视觉行允许的最小归一化中心偏差。 */
        const val MIN_SAME_ROW_TOLERANCE = 0.008f

        /** 纵排文字至少包含的非空白字符数。 */
        const val MIN_VERTICAL_TEXT_LENGTH = 2

        /** 高宽比达到该值时把文字框视为纵排版式。 */
        const val VERTICAL_TEXT_ASPECT_RATIO = 1.5f

        /** 每元包含的分数。 */
        const val FEN_PER_YUAN = 100L

        /** 金额最多允许两位小数。 */
        const val MAX_FRACTION_DIGITS = 2

        /** 受损摘要至少包含的非空白字符数。 */
        const val MIN_DAMAGED_SUMMARY_LENGTH = 2

        /** 受损摘要最多接受的非空白字符数。 */
        const val MAX_DAMAGED_SUMMARY_LENGTH = 12

        /** 受损摘要至少保留的数字数。 */
        const val MIN_DAMAGED_DIGIT_COUNT = 2

        /** 期数、倍数及一个误识别单位最多形成五位数字。 */
        const val MAX_DAMAGED_DIGIT_COUNT = 5

        /** 摘要数字以外允许的少量 OCR 噪声字符数。 */
        const val MAX_DAMAGED_NOISE_COUNT = 4

        /** 至少三位数字时才允许把其中一位视为被误识别的单位。 */
        const val MIN_DIGITS_ALLOWING_ONE_NOISE_REMOVAL = 3

        /** 期数和倍数各自最多两位。 */
        const val MAX_SUMMARY_NUMBER_LENGTH = 2

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

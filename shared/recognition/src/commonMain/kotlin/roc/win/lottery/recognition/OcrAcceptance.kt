package roc.win.lottery.recognition

import kotlin.math.ceil
import kotlin.math.roundToLong
import kotlin.time.TimeSource

/** 单张真实票图的匿名 OCR 验收结果分类。 */
internal enum class OcrAcceptanceOutcome {
    /** 图片质量闸门拒绝继续识别。 */
    QUALITY_REJECTED,

    /** OCR 未检测到足够清晰的文字。 */
    RECOGNITION_POOR_IMAGE,

    /** OCR 运行时未能完成推理。 */
    RECOGNITION_FAILURE,

    /** OCR 和解析已形成完整待确认草稿。 */
    READY,

    /** 解析需要人工校正，但已保留部分安全草稿。 */
    CORRECTION_WITH_DRAFT,

    /** 解析需要从空白草稿开始人工录入。 */
    CORRECTION_EMPTY,

    /** 解析明确判定当前票型不受支持。 */
    UNSUPPORTED,
}

/** 一张真实票图完成质量检查、OCR 和保守解析后的匿名报告。 */
internal data class OcrAcceptanceSampleReport(
    /** 当前样本最终停留的安全状态。 */
    val outcome: OcrAcceptanceOutcome,
    /** OCR 文档中的文字行数量，不包含任何文字内容。 */
    val recognizedLineCount: Int,
    /** 解析器形成的安全字段区域数量。 */
    val safeFieldCount: Int,
    /** 安全草稿中形成的投注行数量。 */
    val safeBetLineCount: Int,
    /** 合并同一几何行后的视觉行数量。 */
    val visualRowCount: Int,
    /** 合并后至少包含五个两位数字片段的疑似投注行数量。 */
    val numericCandidateLineCount: Int,
    /** OCR 文档中的十进制数字字符总数。 */
    val digitCharacterCount: Int,
    /** 质量检查、OCR 和解析的端到端微秒数。 */
    val totalMicroseconds: Long,
    /** OCR 阶段微秒数；未进入 OCR 时为零。 */
    val recognitionMicroseconds: Long,
) {
    /** 输出不包含图片标识、OCR 内容、字段值或坐标的单样本报告。 */
    fun toAnonymousLine(
        platform: String,
        sampleNumber: Int,
    ): String =
        listOf(
            OCR_ACCEPTANCE_SAMPLE_MARKER,
            "platform=$platform",
            "sample=$sampleNumber",
            "outcome=${outcome.diagnosticName()}",
            "ocr_lines=$recognizedLineCount",
            "safe_fields=$safeFieldCount",
            "safe_bet_lines=$safeBetLineCount",
            "visual_rows=$visualRowCount",
            "numeric_candidates=$numericCandidateLineCount",
            "digit_characters=$digitCharacterCount",
            "total_ms=${totalMicroseconds.toMillisecondsText()}",
            "ocr_ms=${recognitionMicroseconds.toMillisecondsText()}",
        ).joinToString(separator = " ")
}

/** 一批真实票图的匿名 OCR 验收汇总。 */
internal data class OcrAcceptanceSummary(
    /** 按匿名序号排列的全部样本报告。 */
    val samples: List<OcrAcceptanceSampleReport>,
) {
    init {
        require(samples.isNotEmpty()) { "OCR 验收汇总至少需要一个样本" }
    }

    /** 输出不包含图片标识或票面内容的批量汇总。 */
    fun toAnonymousLine(platform: String): String {
        val totalTimes = samples.map(OcrAcceptanceSampleReport::totalMicroseconds)
        val recognitionTimes = samples.map(OcrAcceptanceSampleReport::recognitionMicroseconds)
        return listOf(
            OCR_ACCEPTANCE_SUMMARY_MARKER,
            "platform=$platform",
            "samples=${samples.size}",
            "ready=${count(OcrAcceptanceOutcome.READY)}",
            "correction_with_draft=${count(OcrAcceptanceOutcome.CORRECTION_WITH_DRAFT)}",
            "correction_empty=${count(OcrAcceptanceOutcome.CORRECTION_EMPTY)}",
            "unsupported=${count(OcrAcceptanceOutcome.UNSUPPORTED)}",
            "quality_rejected=${count(OcrAcceptanceOutcome.QUALITY_REJECTED)}",
            "recognition_poor=${count(OcrAcceptanceOutcome.RECOGNITION_POOR_IMAGE)}",
            "recognition_failure=${count(OcrAcceptanceOutcome.RECOGNITION_FAILURE)}",
            "ocr_lines=${samples.sumOf(OcrAcceptanceSampleReport::recognizedLineCount)}",
            "safe_fields=${samples.sumOf(OcrAcceptanceSampleReport::safeFieldCount)}",
            "safe_bet_lines=${samples.sumOf(OcrAcceptanceSampleReport::safeBetLineCount)}",
            "visual_rows=${samples.sumOf(OcrAcceptanceSampleReport::visualRowCount)}",
            "numeric_candidates=${samples.sumOf(OcrAcceptanceSampleReport::numericCandidateLineCount)}",
            "digit_characters=${samples.sumOf(OcrAcceptanceSampleReport::digitCharacterCount)}",
            "p50_total_ms=${percentile(totalTimes, 0.50).toMillisecondsText()}",
            "p95_total_ms=${percentile(totalTimes, 0.95).toMillisecondsText()}",
            "p50_ocr_ms=${percentile(recognitionTimes, 0.50).toMillisecondsText()}",
            "p95_ocr_ms=${percentile(recognitionTimes, 0.95).toMillisecondsText()}",
        ).joinToString(separator = " ")
    }

    /** 统计指定结果分类的样本数。 */
    private fun count(outcome: OcrAcceptanceOutcome): Int = samples.count { sample -> sample.outcome == outcome }
}

/** 对一张真实票图执行生产一致的质量检查、OCR 和保守解析。 */
internal suspend fun analyzeOcrAcceptanceSample(
    imageRef: ImageRef,
    imageQualityAnalyzer: ImageQualityAnalyzer,
    ticketRecognizer: TicketRecognizer,
    ticketParser: TicketParser,
): OcrAcceptanceSampleReport {
    val totalStart = TimeSource.Monotonic.markNow()
    if (imageQualityAnalyzer.analyze(imageRef) !is ImageQualityResult.Passed) {
        return OcrAcceptanceSampleReport(
            outcome = OcrAcceptanceOutcome.QUALITY_REJECTED,
            recognizedLineCount = 0,
            safeFieldCount = 0,
            safeBetLineCount = 0,
            visualRowCount = 0,
            numericCandidateLineCount = 0,
            digitCharacterCount = 0,
            totalMicroseconds = totalStart.elapsedNow().inWholeMicroseconds,
            recognitionMicroseconds = 0,
        )
    }

    val recognitionStart = TimeSource.Monotonic.markNow()
    val recognition = ticketRecognizer.recognize(imageRef)
    val recognitionMicroseconds = recognitionStart.elapsedNow().inWholeMicroseconds
    if (recognition !is RecognitionResult.Success) {
        return OcrAcceptanceSampleReport(
            outcome =
                when (recognition) {
                    is RecognitionResult.PoorImage -> OcrAcceptanceOutcome.RECOGNITION_POOR_IMAGE
                    is RecognitionResult.Failure -> OcrAcceptanceOutcome.RECOGNITION_FAILURE
                    is RecognitionResult.Success -> error("成功结果已在前置分支排除")
                },
            recognizedLineCount = 0,
            safeFieldCount = 0,
            safeBetLineCount = 0,
            visualRowCount = 0,
            numericCandidateLineCount = 0,
            digitCharacterCount = 0,
            totalMicroseconds = totalStart.elapsedNow().inWholeMicroseconds,
            recognitionMicroseconds = recognitionMicroseconds,
        )
    }

    val parsed = ticketParser.parse(recognition.document)
    val structure =
        if (ticketParser is ConservativeTicketParser) {
            ticketParser.summarizeForAcceptance(recognition.document)
        } else {
            OcrAcceptanceStructure(
                visualRowCount = recognition.document.lines.size,
                numericCandidateLineCount =
                    recognition.document.lines.count { line ->
                        TWO_DIGIT_TOKEN_REGEX.findAll(line.text).count() >= MINIMUM_NUMERIC_CANDIDATE_TOKEN_COUNT
                    },
            )
        }
    val outcome: OcrAcceptanceOutcome
    val safeFieldCount: Int
    val safeBetLineCount: Int
    when (parsed) {
        is TicketParseResult.ReadyForReview -> {
            outcome = OcrAcceptanceOutcome.READY
            safeFieldCount = parsed.fieldRegions.size
            safeBetLineCount = parsed.draft.betLines.size
        }

        is TicketParseResult.NeedsCorrection -> {
            outcome =
                if (parsed.draft == null) {
                    OcrAcceptanceOutcome.CORRECTION_EMPTY
                } else {
                    OcrAcceptanceOutcome.CORRECTION_WITH_DRAFT
                }
            safeFieldCount = parsed.fieldRegions.size
            safeBetLineCount = parsed.draft?.betLines?.size ?: 0
        }

        is TicketParseResult.Unsupported -> {
            outcome = OcrAcceptanceOutcome.UNSUPPORTED
            safeFieldCount = 0
            safeBetLineCount = 0
        }
    }
    return OcrAcceptanceSampleReport(
        outcome = outcome,
        recognizedLineCount = recognition.document.lines.size,
        safeFieldCount = safeFieldCount,
        safeBetLineCount = safeBetLineCount,
        visualRowCount = structure.visualRowCount,
        numericCandidateLineCount = structure.numericCandidateLineCount,
        digitCharacterCount = recognition.document.lines.sumOf { line -> line.text.count(Char::isDigit) },
        totalMicroseconds = totalStart.elapsedNow().inWholeMicroseconds,
        recognitionMicroseconds = recognitionMicroseconds,
    )
}

/** 共享解析器合并 OCR 片段后的匿名结构计数。 */
internal data class OcrAcceptanceStructure(
    /** 合并同一几何行后的视觉行数量。 */
    val visualRowCount: Int,
    /** 至少包含五个两位数字片段的疑似投注行数量。 */
    val numericCandidateLineCount: Int,
)

/** 使用最近秩定义计算真实票图小样本百分位。 */
private fun percentile(
    values: List<Long>,
    ratio: Double,
): Long {
    require(values.isNotEmpty()) { "OCR 验收百分位样本不能为空" }
    require(ratio in 0.0..1.0) { "OCR 验收百分位必须位于 0 至 1" }
    val sorted = values.sorted()
    val index = (ceil(sorted.size * ratio).toInt() - 1).coerceIn(sorted.indices)
    return sorted[index]
}

/** 把微秒四舍五入为一位小数的毫秒文本。 */
private fun Long.toMillisecondsText(): String {
    val tenths = (this.toDouble() / MICROSECONDS_PER_TENTH_MILLISECOND).roundToLong()
    return "${tenths / TENTHS_PER_MILLISECOND}.${tenths % TENTHS_PER_MILLISECOND}"
}

/** 返回验收分类的稳定小写日志名称。 */
private fun OcrAcceptanceOutcome.diagnosticName(): String = name.lowercase()

/** 单张匿名验收日志固定标记。 */
internal const val OCR_ACCEPTANCE_SAMPLE_MARKER = "WINLOTTERY_OCR_ACCEPTANCE_SAMPLE"

/** 批量匿名验收汇总固定标记。 */
internal const val OCR_ACCEPTANCE_SUMMARY_MARKER = "WINLOTTERY_OCR_ACCEPTANCE_SUMMARY"

/** 投注行候选至少需要的两位数字片段数量。 */
private const val MINIMUM_NUMERIC_CANDIDATE_TOKEN_COUNT = 7

/** 匹配不属于更长数字串的两位数字片段。 */
private val TWO_DIGIT_TOKEN_REGEX = Regex("(?<!\\d)\\d{2}(?!\\d)")

/** 0.1 毫秒包含的微秒数。 */
private const val MICROSECONDS_PER_TENTH_MILLISECOND = 100.0

/** 1 毫秒包含的十分之一毫秒数量。 */
private const val TENTHS_PER_MILLISECOND = 10L

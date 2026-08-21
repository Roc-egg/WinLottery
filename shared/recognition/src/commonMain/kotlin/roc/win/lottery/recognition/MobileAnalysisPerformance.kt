package roc.win.lottery.recognition

import kotlin.math.ceil
import kotlin.math.roundToLong
import kotlin.time.TimeSource

/** 单次移动图片分析的匿名阶段耗时。 */
data class MobileAnalysisTiming(
    /** 质量检查、OCR 和解析的端到端微秒数。 */
    val totalMicroseconds: Long,
    /** 图片质量检查微秒数。 */
    val qualityMicroseconds: Long,
    /** 平台本地 OCR 微秒数。 */
    val recognitionMicroseconds: Long,
    /** 共享保守解析微秒数。 */
    val parsingMicroseconds: Long,
)

/** 移动图片分析冷样本与重复热样本的匿名报告。 */
data class MobileAnalysisPerformanceReport(
    /** 不计入百分位的首次冷样本。 */
    val coldTiming: MobileAnalysisTiming,
    /** 热身后参与百分位计算的样本。 */
    val measuredTimings: List<MobileAnalysisTiming>,
    /** 冷样本解析结果分类，不包含 OCR 文本或票面字段。 */
    val parseCategory: String,
) {
    init {
        require(measuredTimings.isNotEmpty()) { "性能报告必须包含至少一个统计样本" }
    }

    /** 输出只含平台、尺寸、次数和耗时的单行报告。 */
    fun toAnonymousLine(
        platform: String,
        widthPixels: Int,
        heightPixels: Int,
        warmupCount: Int,
    ): String {
        val total = measuredTimings.map(MobileAnalysisTiming::totalMicroseconds)
        val quality = measuredTimings.map(MobileAnalysisTiming::qualityMicroseconds)
        val recognition = measuredTimings.map(MobileAnalysisTiming::recognitionMicroseconds)
        val parsing = measuredTimings.map(MobileAnalysisTiming::parsingMicroseconds)
        return listOf(
            MOBILE_ANALYSIS_BASELINE_MARKER,
            "platform=$platform",
            "input=${widthPixels}x$heightPixels",
            "input_pixels=${widthPixels.toLong() * heightPixels.toLong()}",
            "samples=${measuredTimings.size}",
            "warmups=$warmupCount",
            "cold_total_ms=${coldTiming.totalMicroseconds.toMillisecondsText()}",
            "p50_total_ms=${percentile(total, 0.50).toMillisecondsText()}",
            "p95_total_ms=${percentile(total, 0.95).toMillisecondsText()}",
            "max_total_ms=${total.max().toMillisecondsText()}",
            "p95_quality_ms=${percentile(quality, 0.95).toMillisecondsText()}",
            "p95_ocr_ms=${percentile(recognition, 0.95).toMillisecondsText()}",
            "p95_parse_ms=${percentile(parsing, 0.95).toMillisecondsText()}",
            "parse=$parseCategory",
        ).joinToString(separator = " ")
    }
}

/** 统一执行真实质量检查、平台 OCR 和共享解析的冷、热样本。 */
suspend fun measureMobileAnalysisPerformance(
    imageRef: ImageRef,
    imageQualityAnalyzer: ImageQualityAnalyzer,
    ticketRecognizer: TicketRecognizer,
    ticketParser: TicketParser = ConservativeTicketParser(),
    warmupCount: Int,
    sampleCount: Int,
): MobileAnalysisPerformanceReport {
    require(imageRef.widthPixels != null && imageRef.heightPixels != null) { "性能输入必须包含图片尺寸" }
    require(imageRef.widthPixels.toLong() * imageRef.heightPixels.toLong() == EXPECTED_INPUT_PIXEL_COUNT) {
        "性能输入必须精确为 1200 万像素"
    }
    require(warmupCount >= 0) { "热身次数不得小于 0" }
    require(sampleCount > 0) { "统计样本数必须大于 0" }

    suspend fun measureOnce(): Pair<MobileAnalysisTiming, String> {
        val totalStart = TimeSource.Monotonic.markNow()
        val qualityStart = TimeSource.Monotonic.markNow()
        val qualityResult = imageQualityAnalyzer.analyze(imageRef)
        val qualityMicroseconds = qualityStart.elapsedNow().inWholeMicroseconds
        check(qualityResult is ImageQualityResult.Passed) { "固定 12MP 输入未通过图片质量检查：$qualityResult" }

        val recognitionStart = TimeSource.Monotonic.markNow()
        val recognitionResult = ticketRecognizer.recognize(imageRef)
        val recognitionMicroseconds = recognitionStart.elapsedNow().inWholeMicroseconds
        check(recognitionResult is RecognitionResult.Success) { "固定 12MP 输入未形成 OCR 文档：$recognitionResult" }
        check(recognitionResult.document.lines.isNotEmpty()) { "固定 12MP 输入的 OCR 文档为空" }

        val parsingStart = TimeSource.Monotonic.markNow()
        val parseResult = ticketParser.parse(recognitionResult.document)
        val parsingMicroseconds = parsingStart.elapsedNow().inWholeMicroseconds
        return MobileAnalysisTiming(
            totalMicroseconds = totalStart.elapsedNow().inWholeMicroseconds,
            qualityMicroseconds = qualityMicroseconds,
            recognitionMicroseconds = recognitionMicroseconds,
            parsingMicroseconds = parsingMicroseconds,
        ) to parseResult.anonymousCategory()
    }

    val cold = measureOnce()
    repeat(warmupCount) { measureOnce() }
    val measured = List(sampleCount) { measureOnce().first }
    return MobileAnalysisPerformanceReport(
        coldTiming = cold.first,
        measuredTimings = measured,
        parseCategory = cold.second,
    )
}

/** 使用最近秩定义计算小样本百分位。 */
private fun percentile(
    values: List<Long>,
    ratio: Double,
): Long {
    require(values.isNotEmpty()) { "百分位样本不能为空" }
    require(ratio in 0.0..1.0) { "百分位比例必须位于 0 至 1" }
    val sorted = values.sorted()
    val index = (ceil(sorted.size * ratio).toInt() - 1).coerceIn(sorted.indices)
    return sorted[index]
}

/** 把微秒四舍五入为一位小数的毫秒文本。 */
private fun Long.toMillisecondsText(): String {
    val tenths = (this.toDouble() / MICROSECONDS_PER_TENTH_MILLISECOND).roundToLong()
    return "${tenths / TENTHS_PER_MILLISECOND}.${tenths % TENTHS_PER_MILLISECOND}"
}

/** 把解析结果压缩为不含票面内容的固定分类。 */
private fun TicketParseResult.anonymousCategory(): String =
    when (this) {
        is TicketParseResult.ReadyForReview -> "ready"
        is TicketParseResult.NeedsCorrection -> "correction"
        is TicketParseResult.Unsupported -> "unsupported"
    }

/** 匿名性能报告固定参数。 */
const val MOBILE_ANALYSIS_BASELINE_MARKER = "WINLOTTERY_MOBILE_ANALYSIS_BASELINE"

/** 固定输入总像素数。 */
private const val EXPECTED_INPUT_PIXEL_COUNT = 12_000_000L

/** 0.1 毫秒包含的微秒数。 */
private const val MICROSECONDS_PER_TENTH_MILLISECOND = 100.0

/** 1 毫秒包含的十分之一毫秒数量。 */
private const val TENTHS_PER_MILLISECOND = 10L

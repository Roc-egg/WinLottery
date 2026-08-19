package roc.win.lottery.app

import roc.win.lottery.recognition.TicketFieldReference
import roc.win.lottery.recognition.TicketParseResult

/** 一次不含图片、文本、坐标和字段值的 OCR 解析诊断。 */
data class OcrConfidenceSample(
    /** 当前解析结果类型。 */
    val outcome: OcrConfidenceOutcome,
    /** 已安全形成的匿名字段类型和原始分数。 */
    val fields: List<OcrFieldConfidence>,
)

/** OCR 解析结果的匿名分类。 */
enum class OcrConfidenceOutcome {
    /** 已形成可供用户确认的完整草稿。 */
    READY,

    /** 仍需用户校正或从空白状态录入。 */
    CORRECTION,

    /** 已识别为 V1 明确不支持的票型。 */
    UNSUPPORTED,
}

/** 一个匿名字段类型及其平台原始置信度。 */
data class OcrFieldConfidence(
    /** 不包含投注行下标的字段分类。 */
    val field: OcrConfidenceField,
    /** 平台校准前的原始分数，未知时为 `null`。 */
    val rawConfidence: Float?,
)

/** 可以进入匿名分布的票面字段分类。 */
enum class OcrConfidenceField {
    /** 彩票种类。 */
    LOTTERY_TYPE,

    /** 开奖期号。 */
    ISSUE,

    /** 任意一行单式投注号码。 */
    BET_LINE,

    /** 投注倍数。 */
    MULTIPLIER,

    /** 大乐透基本或追加属性。 */
    ADDITIONAL,

    /** 连续投注期数。 */
    PERIOD_COUNT,

    /** 票面合计金额。 */
    PAID_AMOUNT,
}

/** 记录不含图片标识、OCR 文本、坐标和字段值的原始置信度诊断。 */
fun interface OcrConfidenceDiagnostics {
    /**
     * 记录一次本地 OCR 解析的匿名字段分数。
     *
     * @param engineName 当前平台 OCR 引擎名称和版本摘要。
     * @param sample 已移除全部票面值和位置的匿名样本。
     */
    fun record(
        engineName: String,
        sample: OcrConfidenceSample,
    )

    /** 默认禁用的诊断实现。 */
    companion object {
        /** 不产生日志或持久化数据的空实现。 */
        val Disabled = OcrConfidenceDiagnostics { _, _ -> }
    }
}

/**
 * 把匿名字段分数写入平台 Debug 日志。
 *
 * @property logger 平台提供的单行日志写入函数。
 */
internal class LogOcrConfidenceDiagnostics(
    private val logger: (String) -> Unit,
) : OcrConfidenceDiagnostics {
    /** 当前进程内从 1 开始递增的匿名样本序号。 */
    private var sampleNumber: Int = 0

    /** 生成稳定单行记录；平台日志失败不得中断用户 OCR 流程。 */
    override fun record(
        engineName: String,
        sample: OcrConfidenceSample,
    ) {
        sampleNumber += 1
        val fields =
            sample.fields
                .joinToString(separator = ",") { field ->
                    val confidence =
                        field.rawConfidence?.takeIf { it in 0f..1f }?.toString() ?: UNKNOWN_CONFIDENCE
                    "${field.field.diagnosticName()}:$confidence"
                }.ifEmpty { NO_FIELDS }
        val safeEngineName =
            engineName
                .replace('\t', ' ')
                .replace('\r', ' ')
                .replace('\n', ' ')
                .take(MAXIMUM_ENGINE_NAME_LENGTH)
        val line =
            listOf(
                LOG_MARKER,
                "sample=$sampleNumber",
                "outcome=${sample.outcome.diagnosticName()}",
                "engine=$safeEngineName",
                "fields=$fields",
            ).joinToString(separator = "\t")
        runCatching { logger(line) }
    }

    /** 匿名诊断输出的固定值。 */
    private companion object {
        /** 便于从平台日志中精确筛选记录的固定标记。 */
        const val LOG_MARKER = "WINLOTTERY_OCR_CONFIDENCE"

        /** 平台未提供合法原始分数时的稳定值。 */
        const val UNKNOWN_CONFIDENCE = "unknown"

        /** 解析未形成任何安全字段时的稳定值。 */
        const val NO_FIELDS = "none"

        /** 引擎摘要最大长度，避免异常平台值制造无界日志。 */
        const val MAXIMUM_ENGINE_NAME_LENGTH = 80
    }
}

/** 从解析结果中投影不包含图片、文本、坐标和字段值的诊断样本。 */
internal fun TicketParseResult.toOcrConfidenceSample(): OcrConfidenceSample =
    when (this) {
        is TicketParseResult.ReadyForReview -> {
            OcrConfidenceSample(
                outcome = OcrConfidenceOutcome.READY,
                fields = fieldRegions.map { OcrFieldConfidence(it.field.toOcrConfidenceField(), it.rawConfidence) },
            )
        }

        is TicketParseResult.NeedsCorrection -> {
            OcrConfidenceSample(
                outcome = OcrConfidenceOutcome.CORRECTION,
                fields = fieldRegions.map { OcrFieldConfidence(it.field.toOcrConfidenceField(), it.rawConfidence) },
            )
        }

        is TicketParseResult.Unsupported -> {
            OcrConfidenceSample(
                outcome = OcrConfidenceOutcome.UNSUPPORTED,
                fields = emptyList(),
            )
        }
    }

/** 把字段引用投影为不包含投注行下标的匿名分类。 */
private fun TicketFieldReference.toOcrConfidenceField(): OcrConfidenceField =
    when (this) {
        TicketFieldReference.LotteryType -> OcrConfidenceField.LOTTERY_TYPE
        TicketFieldReference.Issue -> OcrConfidenceField.ISSUE
        is TicketFieldReference.BetLine -> OcrConfidenceField.BET_LINE
        TicketFieldReference.Multiplier -> OcrConfidenceField.MULTIPLIER
        TicketFieldReference.Additional -> OcrConfidenceField.ADDITIONAL
        TicketFieldReference.PeriodCount -> OcrConfidenceField.PERIOD_COUNT
        TicketFieldReference.PaidAmount -> OcrConfidenceField.PAID_AMOUNT
    }

/** 返回解析状态的稳定日志名称。 */
private fun OcrConfidenceOutcome.diagnosticName(): String =
    when (this) {
        OcrConfidenceOutcome.READY -> "ready"
        OcrConfidenceOutcome.CORRECTION -> "correction"
        OcrConfidenceOutcome.UNSUPPORTED -> "unsupported"
    }

/** 返回匿名字段分类的稳定日志名称。 */
private fun OcrConfidenceField.diagnosticName(): String =
    when (this) {
        OcrConfidenceField.LOTTERY_TYPE -> "lotteryType"
        OcrConfidenceField.ISSUE -> "issue"
        OcrConfidenceField.BET_LINE -> "betLine"
        OcrConfidenceField.MULTIPLIER -> "multiplier"
        OcrConfidenceField.ADDITIONAL -> "additional"
        OcrConfidenceField.PERIOD_COUNT -> "periodCount"
        OcrConfidenceField.PAID_AMOUNT -> "paidAmount"
    }

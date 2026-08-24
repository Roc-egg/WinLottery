package roc.win.lottery.recognition

import roc.win.lottery.domain.TicketDraft

/**
 * OCR 元素的归一化位置，坐标范围均为 `0.0..1.0`。
 *
 * @property left 左边界。
 * @property top 上边界。
 * @property right 右边界。
 * @property bottom 下边界。
 */
data class NormalizedBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * 一行平台无关的 OCR 结果。
 *
 * @property text 原始识别文本。
 * @property bounds 在原始校正图中的归一化位置。
 * @property confidence 平台校准前的原始置信度，无法提供时为 `null`。
 */
data class OcrTextLine(
    val text: String,
    val bounds: NormalizedBounds,
    val confidence: Float?,
)

/**
 * 一次本地 OCR 的标准输出。
 *
 * @property imageId 对应的当前流程图片标识。
 * @property lines 按阅读顺序排列的文字行。
 * @property engineName 平台 OCR 引擎名称和版本摘要。
 */
data class OcrDocument(
    val imageId: String,
    val lines: List<OcrTextLine>,
    val engineName: String,
)

/** 票面校正页可定位的 OCR 字段。 */
sealed interface TicketFieldReference {
    /** 彩票种类及其标题或联合识别证据。 */
    data object LotteryType : TicketFieldReference

    /** 开奖期号。 */
    data object Issue : TicketFieldReference

    /**
     * 一行单式投注号码。
     *
     * @property index 投注行在草稿中的下标。
     */
    data class BetLine(
        val index: Int,
    ) : TicketFieldReference

    /** 投注倍数。 */
    data object Multiplier : TicketFieldReference

    /** 大乐透基本或追加投注属性。 */
    data object Additional : TicketFieldReference

    /** 连续投注期数。 */
    data object PeriodCount : TicketFieldReference

    /** 票面合计金额。 */
    data object PaidAmount : TicketFieldReference
}

/**
 * 一个已解析字段在原始校正图中的位置。
 *
 * @property field 可定位的草稿字段。
 * @property bounds 字段所在 OCR 视觉行的归一化边界。
 * @property rawConfidence 平台校准前的保守原始置信度；任一必要片段未提供时为 `null`。
 */
data class TicketFieldRegion(
    val field: TicketFieldReference,
    val bounds: NormalizedBounds,
    val rawConfidence: Float? = null,
)

/** OCR 无法唯一确定、需要用户对照原图选择的字段候选。 */
sealed interface TicketFieldCandidate {
    /**
     * 开奖期号候选。
     *
     * @property value OCR 识别到的合法期号。
     */
    data class Issue(
        val value: String,
    ) : TicketFieldCandidate

    /**
     * 票面合计金额候选。
     *
     * @property valueFen OCR 识别到的非负金额，单位为分。
     */
    data class PaidAmount(
        val valueFen: Long,
    ) : TicketFieldCandidate
}

/** 本地 OCR 结果。 */
sealed interface RecognitionResult {
    /**
     * OCR 已完成，仍必须进入人工确认。
     *
     * @property document 平台无关的 OCR 文档。
     */
    data class Success(
        val document: OcrDocument,
    ) : RecognitionResult

    /**
     * 图片质量不足，不能继续自动解析。
     *
     * @property issues 可操作的简体中文质量问题。
     */
    data class PoorImage(
        val issues: List<String>,
    ) : RecognitionResult

    /**
     * OCR 引擎未能完成识别。
     *
     * @property message 不包含票面全文的错误说明。
     */
    data class Failure(
        val message: String,
    ) : RecognitionResult
}

/** 平台本地 OCR 的最小接口。 */
fun interface TicketRecognizer {
    /**
     * 对私有临时图片执行本地 OCR，不得上传图片或识别文本。
     *
     * @param imageRef 已完成方向归一化的临时图片引用。
     * @return 标准 OCR 文档或明确失败状态。
     */
    suspend fun recognize(imageRef: ImageRef): RecognitionResult
}

/** 本地 OCR 内部阶段的可展示进度。 */
data class RecognitionProgress(
    /** 当前 OCR 完成比例，范围为 `0.0..1.0`。 */
    val fraction: Float,
    /** 不包含票面隐私内容的阶段说明。 */
    val message: String,
) {
    init {
        require(fraction in 0f..1f) { "OCR 进度必须位于 0.0..1.0" }
        require(message.isNotBlank()) { "OCR 进度说明不能为空" }
    }
}

/** 能够在识别期间持续报告内部阶段进度的本地 OCR。 */
interface ProgressiveTicketRecognizer : TicketRecognizer {
    /** 未提供进度监听时仍执行同一套本地识别。 */
    override suspend fun recognize(imageRef: ImageRef): RecognitionResult = recognize(imageRef) { }

    /**
     * 对私有临时图片执行本地 OCR，并报告不包含票面内容的阶段进度。
     *
     * @param imageRef 已完成方向归一化的临时图片引用。
     * @param onProgress 单调递增的 OCR 阶段进度监听器。
     * @return 标准 OCR 文档或明确失败状态。
     */
    suspend fun recognize(
        imageRef: ImageRef,
        onProgress: (RecognitionProgress) -> Unit,
    ): RecognitionResult
}

/** OCR 文档到票面草稿的解析结果。 */
sealed interface TicketParseResult {
    /**
     * 已生成等待用户确认的草稿。
     *
     * @property draft 不可直接进入规则引擎的票面草稿。
     * @property fieldRegions 可供校正页对照原图的字段区域。
     */
    data class ReadyForReview(
        val draft: TicketDraft,
        val fieldRegions: List<TicketFieldRegion> = emptyList(),
    ) : TicketParseResult

    /**
     * 识别到了 V1 不支持的票型或未知版式。
     *
     * @property message 面向用户的阻断原因。
     */
    data class Unsupported(
        val message: String,
    ) : TicketParseResult

    /**
     * 关键字段不完整或不合法，需要人工重新处理。
     *
     * @property message 面向用户的修正说明。
     * @property draft 已安全解析的可编辑草稿；无法保证票型或投注结构时为 `null`。
     * @property fieldRegions 草稿或候选存在时可供校正页对照原图的字段区域。
     * @property fieldCandidates OCR 已识别但无法唯一确定的安全候选值。
     */
    data class NeedsCorrection(
        val message: String,
        val draft: TicketDraft? = null,
        val fieldRegions: List<TicketFieldRegion> = emptyList(),
        val fieldCandidates: List<TicketFieldCandidate> = emptyList(),
    ) : TicketParseResult
}

/** 把标准 OCR 文档转换为受领域约束保护的票面草稿。 */
fun interface TicketParser {
    /**
     * 解析标题、期号、号码、倍数和追加属性，不静默猜测非法数字。
     *
     * @param document 平台 OCR 的标准输出。
     * @return 等待确认、明确不支持或需要修正状态。
     */
    fun parse(document: OcrDocument): TicketParseResult
}

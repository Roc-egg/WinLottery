package roc.win.lottery.domain

/** V1.4 AI 分析协议常量。 */
object AiAnalysisProtocol {
    /** 历史开奖快照格式版本。 */
    const val SNAPSHOT_SCHEMA_VERSION = 1

    /** 模型输出格式版本。 */
    const val OUTPUT_SCHEMA_VERSION = 1

    /** 单次请求最少候选注数。 */
    const val MIN_CANDIDATE_COUNT = 1

    /** 单次请求最多候选注数。 */
    const val MAX_CANDIDATE_COUNT = 5

    /** 规范化历史快照允许占用的最多 UTF-8 字节数。 */
    const val MAX_SNAPSHOT_BYTES = 96 * 1024

    /** 完整模型请求体允许占用的最多 UTF-8 字节数。 */
    const val MAX_REQUEST_BODY_BYTES = 128 * 1024

    /** 模型输出 token 固定上限。 */
    const val MAX_OUTPUT_TOKENS = 2048

    /** 单次模型请求固定超时秒数。 */
    const val REQUEST_TIMEOUT_SECONDS = 60

    /** 分析摘要允许的最多字符数。 */
    const val MAX_SUMMARY_LENGTH = 1200

    /** 单注候选说明允许的最多字符数。 */
    const val MAX_REASON_LENGTH = 400
}

/**
 * V1.4 首发分析模板。
 *
 * @property id 进入请求和复核记录的稳定模板标识。
 * @property displayName 面向用户展示的模板名称。
 * @property analysisFocus 只描述历史数据观察范围的固定分析重点。
 */
enum class AiAnalysisTemplate(
    val id: String,
    val displayName: String,
    val analysisFocus: String,
) {
    /** 同时观察频次、遗漏和号码分布，不声称存在概率优势。 */
    COMPREHENSIVE(
        id = "comprehensive-history-v1",
        displayName = "综合历史分析",
        analysisFocus = "综合说明样本内的频次、遗漏和号码分布，并给出娱乐性候选",
    ),

    /** 重点描述样本内出现次数和当前遗漏。 */
    FREQUENCY_OMISSION(
        id = "frequency-omission-v1",
        displayName = "频次与遗漏",
        analysisFocus = "重点说明样本内的号码出现次数和当前遗漏，并给出娱乐性候选",
    ),

    /** 重点描述号码区间、奇偶和相邻结构。 */
    DISTRIBUTION_STRUCTURE(
        id = "distribution-structure-v1",
        displayName = "分布结构",
        analysisFocus = "重点说明样本内的区间、奇偶和相邻号码结构，并给出娱乐性候选",
    ),
}

/**
 * 一次已经通过本地校验的 AI 历史开奖快照。
 *
 * @property schemaVersion 快照规范化格式版本。
 * @property id 规范化 JSON 的 SHA-256 小写十六进制摘要。
 * @property lotteryType 当前快照彩种。
 * @property sampleSize 用户选择的历史样本范围。
 * @property draws 按开奖时间和期号升序排列的规范化开奖。
 * @property canonicalJson 固定字段和顺序形成的规范化 JSON。
 * @property byteCount 规范化 JSON 的 UTF-8 字节数。
 */
class AiHistorySnapshot internal constructor(
    val schemaVersion: Int,
    val id: String,
    val lotteryType: LotteryType,
    val sampleSize: TrendSampleSize,
    val draws: List<HistoricalDraw>,
    val canonicalJson: String,
    val byteCount: Int,
) {
    /** 快照实际包含的开奖期数。 */
    val sampleCount: Int
        get() = draws.size

    /** 快照最早期号。 */
    val firstIssue: Issue
        get() = draws.first().issue

    /** 快照最晚期号。 */
    val lastIssue: Issue
        get() = draws.last().issue
}

/**
 * 一次等待用户确认的 AI 分析请求。
 *
 * @property snapshot 经过完整校验且可复核的历史快照。
 * @property template 用户选择的冻结分析模板。
 * @property candidateCount 需要模型返回的候选注数。
 */
data class AiAnalysisRequest(
    val snapshot: AiHistorySnapshot,
    val template: AiAnalysisTemplate,
    val candidateCount: Int,
) {
    init {
        require(candidateCount in AiAnalysisProtocol.MIN_CANDIDATE_COUNT..AiAnalysisProtocol.MAX_CANDIDATE_COUNT) {
            "AI 候选注数必须位于 ${AiAnalysisProtocol.MIN_CANDIDATE_COUNT}..${AiAnalysisProtocol.MAX_CANDIDATE_COUNT}"
        }
    }
}

/**
 * 尚未经过本地规则校验的一注模型候选。
 *
 * @property primaryNumbers 模型返回的主号码。
 * @property secondaryNumbers 模型返回的次号码。
 * @property reason 模型对当前候选的历史数据说明。
 */
data class UntrustedAiAnalysisCandidate(
    val primaryNumbers: List<Int>,
    val secondaryNumbers: List<Int>,
    val reason: String,
)

/**
 * 从严格 JSON 解析得到、尚未经过领域校验的模型输出。
 *
 * @property schemaVersion 模型回显的输出格式版本。
 * @property lotteryType 模型回显的彩种枚举名称。
 * @property snapshotId 模型回显的历史快照标识。
 * @property summary 模型生成的历史分析摘要。
 * @property candidates 模型生成的候选列表。
 */
data class UntrustedAiAnalysisOutput(
    val schemaVersion: Int,
    val lotteryType: String,
    val snapshotId: String,
    val summary: String,
    val candidates: List<UntrustedAiAnalysisCandidate>,
)

/**
 * 已通过本地规则校验的一注 AI 候选。
 *
 * @property line 合法、升序且不重复的一注号码。
 * @property reason 经过长度和空白校验的历史数据说明。
 */
data class AiAnalysisCandidate(
    val line: GeneratedNumberLine,
    val reason: String,
)

/**
 * 可进入共享界面展示的 AI 分析结果。
 *
 * @property schemaVersion 已验证的输出格式版本。
 * @property lotteryType 已验证的彩种。
 * @property snapshotId 已验证的历史快照标识。
 * @property summary 经过本地文本边界校验的分析摘要。
 * @property candidates 全部通过彩票规则校验且互不重复的候选。
 */
data class AiAnalysisResult(
    val schemaVersion: Int,
    val lotteryType: LotteryType,
    val snapshotId: String,
    val summary: String,
    val candidates: List<AiAnalysisCandidate>,
)

/** AI 模型输出违反本地契约时的稳定编码。 */
enum class AiAnalysisValidationProblemCode {
    /** 输出格式版本不匹配。 */
    SCHEMA_VERSION_MISMATCH,

    /** 输出彩种与请求快照不匹配。 */
    LOTTERY_TYPE_MISMATCH,

    /** 输出回显的快照标识不匹配。 */
    SNAPSHOT_ID_MISMATCH,

    /** 分析摘要为空或超过长度上限。 */
    INVALID_SUMMARY,

    /** 候选数量与用户确认值不匹配。 */
    CANDIDATE_COUNT_MISMATCH,

    /** 候选主号码数量、范围、顺序或唯一性不合法。 */
    INVALID_PRIMARY_NUMBERS,

    /** 候选次号码数量、范围、顺序或唯一性不合法。 */
    INVALID_SECONDARY_NUMBERS,

    /** 多注候选中出现完全相同的号码。 */
    DUPLICATE_CANDIDATE,

    /** 单注候选说明为空或超过长度上限。 */
    INVALID_REASON,
}

/**
 * 一条 AI 输出本地校验问题。
 *
 * @property code 稳定问题编码。
 * @property candidateIndex 能定位到候选时使用的从零开始索引。
 * @property message 面向用户的简体中文失败说明。
 */
data class AiAnalysisValidationProblem(
    val code: AiAnalysisValidationProblemCode,
    val candidateIndex: Int?,
    val message: String,
)

/** AI 模型输出的本地校验结果。 */
sealed interface AiAnalysisValidationResult {
    /**
     * 全部字段和候选均已通过本地校验。
     *
     * @property result 可进入界面展示的结果。
     */
    data class Success(
        val result: AiAnalysisResult,
    ) : AiAnalysisValidationResult

    /**
     * 至少一个字段或候选不满足本地契约。
     *
     * @property problems 按字段和候选检查顺序排列的问题。
     */
    data class Invalid(
        val problems: List<AiAnalysisValidationProblem>,
    ) : AiAnalysisValidationResult
}

/** 对已经解析的模型输出执行独立彩票领域校验。 */
class AiAnalysisOutputValidator {
    /**
     * 对照用户确认的请求校验模型回显、文本和全部候选。
     *
     * @param request 用户确认后实际发送的领域请求。
     * @param output 严格 JSON 解析形成的未信任模型输出。
     * @return 完整合法结果或全部可定位问题。
     */
    fun validate(
        request: AiAnalysisRequest,
        output: UntrustedAiAnalysisOutput,
    ): AiAnalysisValidationResult {
        val problems = mutableListOf<AiAnalysisValidationProblem>()
        if (output.schemaVersion != AiAnalysisProtocol.OUTPUT_SCHEMA_VERSION) {
            problems += problem(AiAnalysisValidationProblemCode.SCHEMA_VERSION_MISMATCH, "AI 输出格式版本不匹配")
        }
        if (output.lotteryType != request.snapshot.lotteryType.name) {
            problems += problem(AiAnalysisValidationProblemCode.LOTTERY_TYPE_MISMATCH, "AI 输出彩种与请求不一致")
        }
        if (output.snapshotId != request.snapshot.id) {
            problems += problem(AiAnalysisValidationProblemCode.SNAPSHOT_ID_MISMATCH, "AI 输出快照标识与请求不一致")
        }

        val summary = output.summary.trim()
        if (summary.isEmpty() || summary.length > AiAnalysisProtocol.MAX_SUMMARY_LENGTH) {
            problems += problem(AiAnalysisValidationProblemCode.INVALID_SUMMARY, "AI 分析摘要为空或过长")
        }
        if (output.candidates.size != request.candidateCount) {
            problems += problem(AiAnalysisValidationProblemCode.CANDIDATE_COUNT_MISMATCH, "AI 候选数量与确认值不一致")
        }

        val primarySpec = request.snapshot.lotteryType.trendAreaSpec(LotteryTrendArea.PRIMARY)
        val secondarySpec = request.snapshot.lotteryType.trendAreaSpec(LotteryTrendArea.SECONDARY)
        val seenCandidates = mutableSetOf<Pair<List<Int>, List<Int>>>()
        output.candidates.forEachIndexed { index, candidate ->
            if (!isValidNumbers(candidate.primaryNumbers, primarySpec)) {
                problems +=
                    problem(
                        code = AiAnalysisValidationProblemCode.INVALID_PRIMARY_NUMBERS,
                        message = "第 ${index + 1} 注主号码不合法",
                        candidateIndex = index,
                    )
            }
            if (!isValidNumbers(candidate.secondaryNumbers, secondarySpec)) {
                problems +=
                    problem(
                        code = AiAnalysisValidationProblemCode.INVALID_SECONDARY_NUMBERS,
                        message = "第 ${index + 1} 注次号码不合法",
                        candidateIndex = index,
                    )
            }
            if (!seenCandidates.add(candidate.primaryNumbers to candidate.secondaryNumbers)) {
                problems +=
                    problem(
                        code = AiAnalysisValidationProblemCode.DUPLICATE_CANDIDATE,
                        message = "第 ${index + 1} 注与前面的候选重复",
                        candidateIndex = index,
                    )
            }
            val reason = candidate.reason.trim()
            if (reason.isEmpty() || reason.length > AiAnalysisProtocol.MAX_REASON_LENGTH) {
                problems +=
                    problem(
                        code = AiAnalysisValidationProblemCode.INVALID_REASON,
                        message = "第 ${index + 1} 注的说明为空或过长",
                        candidateIndex = index,
                    )
            }
        }

        if (problems.isNotEmpty()) return AiAnalysisValidationResult.Invalid(problems)

        return AiAnalysisValidationResult.Success(
            result =
                AiAnalysisResult(
                    schemaVersion = output.schemaVersion,
                    lotteryType = request.snapshot.lotteryType,
                    snapshotId = output.snapshotId,
                    summary = summary,
                    candidates =
                        output.candidates.map { candidate ->
                            AiAnalysisCandidate(
                                line =
                                    GeneratedNumberLine(
                                        primaryNumbers = candidate.primaryNumbers.toList(),
                                        secondaryNumbers = candidate.secondaryNumbers.toList(),
                                    ),
                                reason = candidate.reason.trim(),
                            )
                        },
                ),
        )
    }

    /** 校验一个号码区域的数量、范围、唯一性和升序。 */
    private fun isValidNumbers(
        numbers: List<Int>,
        spec: LotteryTrendAreaSpec,
    ): Boolean =
        numbers.size == spec.drawnNumberCount &&
            numbers.all { number -> number in spec.numberRange } &&
            numbers.distinct().size == numbers.size &&
            numbers == numbers.sorted()

    /** 构造不关联具体候选的校验问题。 */
    private fun problem(
        code: AiAnalysisValidationProblemCode,
        message: String,
        candidateIndex: Int? = null,
    ): AiAnalysisValidationProblem =
        AiAnalysisValidationProblem(
            code = code,
            candidateIndex = candidateIndex,
            message = message,
        )
}

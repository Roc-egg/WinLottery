package roc.win.lottery.domain

/** 开奖数据的可用状态。 */
enum class DrawStatus {
    /** 尚未查询到对应期次。 */
    NOT_PUBLISHED,

    /** 已有数据，但号码或政策证据尚不完整。 */
    PUBLISHING,

    /** 号码和当期政策已确认，可判断奖级。 */
    FINAL_NUMBERS,

    /** 奖级金额也已完整，可展示确定的税前金额。 */
    FINAL_PAYOUT,

    /** 当前设备无法联网。 */
    NETWORK_UNAVAILABLE,

    /** 官网数据源异常或结构发生变化。 */
    SOURCE_UNAVAILABLE,

    /** 同一期的两份证据互相冲突。 */
    CONFLICT,
}

/**
 * 开奖数据来源证据。
 *
 * @property sourceName 数据来源的展示名称。
 * @property sourceUrl 对应的官方页面或请求地址。
 * @property fetchedAtEpochMillis 获取时间的 Unix 毫秒值。
 * @property contentSha256 规范化必要字段的 SHA-256。
 */
data class SourceEvidence(
    val sourceName: String,
    val sourceUrl: String,
    val fetchedAtEpochMillis: Long,
    val contentSha256: String,
)

/**
 * 当期开奖中的一个奖级。
 *
 * @property code 适配层归一化后的稳定奖级编码。
 * @property displayName 奖级的中文名称。
 * @property singlePrizeFen 基本投注单注税前奖金，未知时为 `null`。
 * @property additionalPrizeFen 大乐透追加单注奖金，不适用或未知时为 `null`。
 * @property winnerCount 该奖级的基本投注中奖注数，未知时为 `null`。
 * @property additionalWinnerCount 大乐透追加中奖注数，不适用或未知时为 `null`。
 * @property singlePrizeRaw 基本投注单注奖金的官网原文。
 * @property additionalPrizeRaw 追加单注奖金的官网原文。
 */
data class PrizeTier(
    val code: String,
    val displayName: String,
    val singlePrizeFen: Long?,
    val additionalPrizeFen: Long?,
    /** 该奖级的中奖注数，官网未提供或无法解析时为 `null`。 */
    val winnerCount: Long? = null,
    /** 大乐透追加中奖注数，不适用或官网未提供时为 `null`。 */
    val additionalWinnerCount: Long? = null,
    /** 基本投注单注奖金的官网原文，用于解释无法解析的金额。 */
    val singlePrizeRaw: String? = null,
    /** 追加单注奖金的官网原文，不适用时为 `null`。 */
    val additionalPrizeRaw: String? = null,
)

/** 当期可能影响命中矩阵的已确认政策。 */
enum class DrawPolicy {
    /** 使用对应规则版本的普通命中矩阵。 */
    STANDARD,

    /** 双色球特别规定生效，额外支持三红零蓝的福运奖。 */
    DOUBLE_COLOR_BALL_FORTUNE,
}

/**
 * 经适配层严格校验后的统一开奖结果。
 *
 * @property lotteryType 彩种。
 * @property issue 开奖期号。
 * @property drawDate 开奖日期，采用 `YYYY-MM-DD` 文本。
 * @property primaryNumbers 前区或红球号码。
 * @property secondaryNumbers 后区或蓝球号码。
 * @property status 数据可用状态。
 * @property revision 同一期内容的修订序号。
 * @property ruleVersion 由期号选中的规则版本编码。
 * @property policy 当期经官方信息确认的特别政策。
 * @property prizeTiers 当期已发布的奖级金额。
 * @property evidence 主查询数据来源证据。
 * @property supportingEvidence 用于交叉核对的其他官方证据。
 */
data class DrawResult(
    val lotteryType: LotteryType,
    val issue: Issue,
    val drawDate: String,
    val primaryNumbers: List<Int>,
    val secondaryNumbers: List<Int>,
    val status: DrawStatus,
    val revision: Int,
    val ruleVersion: String,
    val policy: DrawPolicy,
    val prizeTiers: List<PrizeTier>,
    val evidence: SourceEvidence,
    val supportingEvidence: List<SourceEvidence>,
)

/** 中奖测算的整体状态。 */
enum class PrizeCheckStatus {
    /** 尚未执行测算。 */
    NOT_CALCULATED,

    /** 至少一行投注中奖。 */
    WIN,

    /** 所有投注均完成计算且均未中奖。 */
    NO_WIN,

    /** 对应期号没有经过验证的规则。 */
    RULE_UNSUPPORTED,

    /** 输入或开奖证据不足，需要人工复核。 */
    NEEDS_MANUAL_REVIEW,
}

/**
 * 单行投注的测算结果。
 *
 * @property lineIndex 票面投注行的零基索引。
 * @property primaryHitCount 主号码命中数。
 * @property secondaryHitCount 次号码命中数。
 * @property prizeTierCode 命中奖级编码，未中奖时为 `null`。
 * @property estimatedPrizeFen 该行计入倍数后的税前奖金，金额未发布时为 `null`。
 */
data class BetLinePrizeResult(
    val lineIndex: Int,
    val primaryHitCount: Int,
    val secondaryHitCount: Int,
    val prizeTierCode: String?,
    val estimatedPrizeFen: Long?,
)

/**
 * 整张彩票的中奖测算结果。
 *
 * @property status 测算状态。
 * @property lineResults 保留票面行顺序的逐注结果。
 * @property estimatedPrizeFen 整票税前奖金，金额不完整时为 `null`。
 * @property message 不能完成测算时的简体中文原因。
 */
data class PrizeCheckResult(
    val status: PrizeCheckStatus,
    val lineResults: List<BetLinePrizeResult>,
    val estimatedPrizeFen: Long?,
    val message: String?,
)

/** 本地中奖规则计算器。 */
fun interface PrizeCalculator {
    /**
     * 根据已确认票据和统一开奖结果执行测算。
     *
     * @param ticket 已通过领域校验的彩票。
     * @param drawResult 至少达到号码可判断状态的开奖结果。
     * @return 不会把未知状态降级为未中奖的测算结果。
     */
    fun calculate(
        ticket: ConfirmedTicket,
        drawResult: DrawResult,
    ): PrizeCheckResult
}

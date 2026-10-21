package roc.win.lottery.persistence

import roc.win.lottery.domain.DrawResult
import roc.win.lottery.domain.DrawStatus
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryTrendArea
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.RuleVersionSelector
import roc.win.lottery.domain.SourceEvidence
import roc.win.lottery.domain.trendAreaSpec

/**
 * 查询开奖时取得的已开奖官方结果在本机的缓存。
 *
 * 按彩种和期号保存规范化开奖事实，所有票据共用；不保存官网原始响应，也不保存任何票据的中奖结论。
 * 实现必须在读写边界执行同一套校验，读取时发现的不合法行只删除该行。
 */
interface DrawResultStore {
    /**
     * 读取指定期号中本机已保存且仍合法的开奖结果。
     *
     * @param lotteryType 彩种。
     * @param issues 需要读取的期号，可以包含本机没有的期号。
     * @return 以期号为键的合法结果；损坏或已过期的行会被删除并跳过。
     */
    suspend fun load(
        lotteryType: LotteryType,
        issues: Collection<Issue>,
    ): Map<Issue, DrawResult>

    /**
     * 保存或替换一期开奖结果。
     *
     * 主证据内容哈希与本机旧值不同时，在本机修订号上加 1；相同则沿用本机修订号。
     *
     * @return 实际写入的结果，修订号以本机为准。
     * @throws IllegalArgumentException 结果未通过校验时抛出，且不写入任何数据。
     */
    suspend fun save(drawResult: DrawResult): DrawResult

    /** 删除一期开奖结果，返回是否删除了数据。 */
    suspend fun delete(
        lotteryType: LotteryType,
        issue: Issue,
    ): Boolean

    /** 删除全部已保存的开奖结果，返回删除的行数；不影响票据记录和历史开奖。 */
    suspend fun clearAll(): Int
}

/** 当前本机开奖结果逻辑数据版本。 */
internal const val CURRENT_DRAW_RESULT_DATA_VERSION = 1

/** 本机开奖结果不合法的原因。 */
enum class DrawResultProblem(
    /** 面向日志和异常的简体中文说明。 */
    val message: String,
) {
    /** 只保存号码已确认的结果。 */
    NOT_FINAL("只保存号码已确认的开奖结果"),

    /** 期号长度或字符不符合彩种规则。 */
    INVALID_ISSUE("开奖期号格式不合法"),

    /** 开奖日期不是 `YYYY-MM-DD`。 */
    INVALID_DATE("开奖日期格式不合法"),

    /** 号码数量、范围、唯一性或顺序不合法。 */
    INVALID_NUMBERS("开奖号码不合法"),

    /** 规则版本与当前应用对该期的选择不一致。 */
    RULE_VERSION_MISMATCH("开奖规则版本与当前应用不一致"),

    /** 修订号必须为正数。 */
    INVALID_REVISION("开奖修订号不合法"),

    /** 来源证据缺少名称、地址、哈希或时间。 */
    INVALID_EVIDENCE("开奖来源证据不完整"),

    /** 奖级编码为空或金额、注数为负数。 */
    INVALID_PRIZE_TIERS("奖级数据不合法"),
}

/** 返回本机保存前后都要满足的校验问题；合法时为 `null`。 */
fun DrawResult.storageProblem(): DrawResultProblem? {
    if (status != DrawStatus.FINAL_NUMBERS && status != DrawStatus.FINAL_PAYOUT) return DrawResultProblem.NOT_FINAL
    val issueLength =
        when (lotteryType) {
            LotteryType.SUPER_LOTTO -> SUPER_LOTTO_ISSUE_LENGTH
            LotteryType.DOUBLE_COLOR_BALL -> DOUBLE_COLOR_BALL_ISSUE_LENGTH
        }
    if (issue.value.length != issueLength || !issue.value.all(Char::isDigit)) return DrawResultProblem.INVALID_ISSUE
    if (!DRAW_DATE_PATTERN.matches(drawDate)) return DrawResultProblem.INVALID_DATE
    val numbersValid =
        primaryNumbers.isValidArea(lotteryType, LotteryTrendArea.PRIMARY) &&
            secondaryNumbers.isValidArea(lotteryType, LotteryTrendArea.SECONDARY)
    if (!numbersValid) return DrawResultProblem.INVALID_NUMBERS
    if (RuleVersionSelector().select(lotteryType, issue)?.code != ruleVersion) {
        return DrawResultProblem.RULE_VERSION_MISMATCH
    }
    if (revision < 1) return DrawResultProblem.INVALID_REVISION
    if (!(listOf(evidence) + supportingEvidence).all { it.isValid() }) return DrawResultProblem.INVALID_EVIDENCE
    val tiersValid =
        prizeTiers.all { tier ->
            tier.code.isNotBlank() &&
                tier.displayName.isNotBlank() &&
                listOfNotNull(
                    tier.singlePrizeFen,
                    tier.additionalPrizeFen,
                    tier.winnerCount,
                    tier.additionalWinnerCount,
                ).all { it >= 0L }
        } &&
            prizeTiers.map { it.code }.toSet().size == prizeTiers.size
    if (!tiersValid) return DrawResultProblem.INVALID_PRIZE_TIERS
    return null
}

/** 号码数量、范围、唯一性和升序都符合该区域规则。 */
private fun List<Int>.isValidArea(
    lotteryType: LotteryType,
    area: LotteryTrendArea,
): Boolean {
    val spec = lotteryType.trendAreaSpec(area)
    return size == spec.drawnNumberCount &&
        all { it in spec.numberRange } &&
        zipWithNext().all { (earlier, later) -> earlier < later }
}

/** 来源名称、HTTPS 地址、SHA-256 和获取时间都完整。 */
private fun SourceEvidence.isValid(): Boolean =
    sourceName.isNotBlank() &&
        sourceUrl.startsWith("https://") &&
        SHA256_PATTERN.matches(contentSha256) &&
        fetchedAtEpochMillis >= 0L

/** 大乐透五位期号。 */
private const val SUPER_LOTTO_ISSUE_LENGTH = 5

/** 双色球七位期号。 */
private const val DOUBLE_COLOR_BALL_ISSUE_LENGTH = 7

/** `YYYY-MM-DD` 日期文本。 */
private val DRAW_DATE_PATTERN = Regex("""\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])""")

/** 小写或大写十六进制 SHA-256。 */
private val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")

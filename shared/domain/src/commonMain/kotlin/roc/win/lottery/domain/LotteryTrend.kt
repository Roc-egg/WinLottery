package roc.win.lottery.domain

/** 走势图中的号码区域。 */
enum class LotteryTrendArea {
    /** 大乐透前区或双色球红球。 */
    PRIMARY,

    /** 大乐透后区或双色球蓝球。 */
    SECONDARY,
}

/**
 * 号码区域的固定规则。
 *
 * @property area 号码区域。
 * @property numberRange 区域内允许的完整号码范围。
 * @property drawnNumberCount 每期开奖该区域的号码数量。
 */
data class LotteryTrendAreaSpec(
    val area: LotteryTrendArea,
    val numberRange: IntRange,
    val drawnNumberCount: Int,
)

/**
 * 返回彩种对应的号码区域规则。
 *
 * @param area 需要展示的号码区域。
 * @return 与正式彩票规则一致的区域规格。
 */
fun LotteryType.trendAreaSpec(area: LotteryTrendArea): LotteryTrendAreaSpec =
    when (this) {
        LotteryType.SUPER_LOTTO -> {
            when (area) {
                LotteryTrendArea.PRIMARY -> {
                    LotteryTrendAreaSpec(area, numberRange = 1..35, drawnNumberCount = 5)
                }

                LotteryTrendArea.SECONDARY -> {
                    LotteryTrendAreaSpec(area, numberRange = 1..12, drawnNumberCount = 2)
                }
            }
        }

        LotteryType.DOUBLE_COLOR_BALL -> {
            when (area) {
                LotteryTrendArea.PRIMARY -> {
                    LotteryTrendAreaSpec(area, numberRange = 1..33, drawnNumberCount = 6)
                }

                LotteryTrendArea.SECONDARY -> {
                    LotteryTrendAreaSpec(area, numberRange = 1..16, drawnNumberCount = 1)
                }
            }
        }
    }

/** V1.3 真实历史开奖走势样本范围。 */
enum class TrendSampleSize(
    /** 最近开奖期数。 */
    val count: Int,
) {
    /** 最近 50 期。 */
    LAST_50(50),

    /** 最近 80 期。 */
    LAST_80(80),

    /** 最近 120 期。 */
    LAST_120(120),

    /** 最近 300 期。 */
    LAST_300(300),

    /** 最近 500 期。 */
    LAST_500(500),
}

/**
 * 经过来源层规范化的单期历史开奖。
 *
 * @property lotteryType 彩种。
 * @property issue 开奖期号。
 * @property drawDate 开奖日期，采用 `YYYY-MM-DD` 文本。
 * @property primaryNumbers 大乐透前区或双色球红球号码。
 * @property secondaryNumbers 大乐透后区或双色球蓝球号码。
 */
data class HistoricalDraw(
    val lotteryType: LotteryType,
    val issue: Issue,
    val drawDate: String,
    val primaryNumbers: List<Int>,
    val secondaryNumbers: List<Int>,
)

/** 走势图输入问题的稳定编码。 */
enum class TrendDataProblemCode {
    /** 没有可分析的历史开奖。 */
    EMPTY_DATA,

    /** 输入中混入了其他彩种。 */
    MIXED_LOTTERY,

    /** 期号格式不符合对应彩种规则。 */
    INVALID_ISSUE,

    /** 同一期出现内容完全相同的重复记录。 */
    DUPLICATE_ISSUE,

    /** 同一期出现互相冲突的记录。 */
    CONFLICTING_ISSUE,

    /** 主号码数量、范围、顺序或唯一性不合法。 */
    INVALID_PRIMARY_NUMBERS,

    /** 次号码数量、范围、顺序或唯一性不合法。 */
    INVALID_SECONDARY_NUMBERS,
}

/**
 * 一条走势图输入问题。
 *
 * @property code 稳定问题编码。
 * @property issue 能定位到单期时对应的期号。
 * @property message 面向用户的简体中文说明。
 */
data class TrendDataProblem(
    val code: TrendDataProblemCode,
    val issue: Issue?,
    val message: String,
)

/**
 * 同一年度期号中可证明的空档。
 *
 * @property previousIssue 空档前一期。
 * @property nextIssue 空档后一期。
 * @property missingCount 两期之间缺少的期数。
 */
data class TrendIssueGap(
    val previousIssue: Issue,
    val nextIssue: Issue,
    val missingCount: Int,
)

/**
 * 走势图矩阵中的一个号码格。
 *
 * @property number 当前号码列。
 * @property isHit 当期是否开出该号码。
 * @property omission 截至当期的样本内连续遗漏值，命中时为 `0`。
 */
data class TrendNumberCell(
    val number: Int,
    val isHit: Boolean,
    val omission: Int,
)

/**
 * 走势图中的单期开奖行。
 *
 * @property issue 开奖期号。
 * @property drawDate 开奖日期。
 * @property cells 按号码升序排列的完整区域格子。
 */
data class TrendDrawRow(
    val issue: Issue,
    val drawDate: String,
    val cells: List<TrendNumberCell>,
)

/**
 * 一个号码在当前样本内的统计。
 *
 * @property number 号码。
 * @property hitCount 出现次数。
 * @property currentOmission 截至最后一期的连续遗漏值。
 * @property maxOmission 当前样本内达到过的最大连续遗漏值。
 */
data class TrendNumberStatistics(
    val number: Int,
    val hitCount: Int,
    val currentOmission: Int,
    val maxOmission: Int,
)

/**
 * 可直接用于跨平台界面绘制的走势快照。
 *
 * @property lotteryType 彩种。
 * @property area 号码区域。
 * @property requestedSampleSize 用户选择的样本范围。
 * @property rows 按开奖先后顺序排列的走势行。
 * @property statistics 按号码升序排列的样本统计。
 * @property issueGaps 当前样本中可证明的同年度期号空档。
 */
data class LotteryTrendSnapshot(
    val lotteryType: LotteryType,
    val area: LotteryTrendArea,
    val requestedSampleSize: TrendSampleSize,
    val rows: List<TrendDrawRow>,
    val statistics: List<TrendNumberStatistics>,
    val issueGaps: List<TrendIssueGap>,
) {
    /** 实际进入计算的有效期数。 */
    val actualSampleCount: Int
        get() = rows.size

    /** 当前样本最早期号。 */
    val firstIssue: Issue
        get() = rows.first().issue

    /** 当前样本最晚期号。 */
    val lastIssue: Issue
        get() = rows.last().issue
}

/** 走势计算结果。 */
sealed interface LotteryTrendCalculationResult {
    /**
     * 已生成可绘制快照。
     *
     * @property snapshot 经过完整校验的走势快照。
     */
    data class Success(
        val snapshot: LotteryTrendSnapshot,
    ) : LotteryTrendCalculationResult

    /**
     * 输入不满足失败关闭边界。
     *
     * @property problems 按输入检查顺序排列的问题。
     */
    data class InvalidData(
        val problems: List<TrendDataProblem>,
    ) : LotteryTrendCalculationResult
}

/** 将规范化历史开奖计算为基本走势矩阵。 */
class LotteryTrendCalculator {
    /**
     * 校验、排序并计算指定号码区域的最近期开奖走势。
     *
     * @param lotteryType 需要分析的彩种。
     * @param area 需要分析的号码区域。
     * @param sampleSize 最近开奖样本范围。
     * @param draws 候选历史开奖记录。
     * @return 可绘制快照或明确的数据问题。
     */
    fun calculate(
        lotteryType: LotteryType,
        area: LotteryTrendArea,
        sampleSize: TrendSampleSize,
        draws: List<HistoricalDraw>,
    ): LotteryTrendCalculationResult {
        if (draws.isEmpty()) {
            return invalid(
                TrendDataProblemCode.EMPTY_DATA,
                issue = null,
                message = "没有可用于走势图的历史开奖",
            )
        }

        val problems = validateDraws(lotteryType, draws)
        if (problems.isNotEmpty()) {
            return LotteryTrendCalculationResult.InvalidData(problems)
        }

        val selectedDraws = draws.sortedBy { it.issue.value }.takeLast(sampleSize.count)
        val spec = lotteryType.trendAreaSpec(area)
        return LotteryTrendCalculationResult.Success(
            snapshot = buildSnapshot(lotteryType, area, sampleSize, spec, selectedDraws),
        )
    }

    /** 校验全部输入记录，并保留可同时发现的问题。 */
    private fun validateDraws(
        lotteryType: LotteryType,
        draws: List<HistoricalDraw>,
    ): List<TrendDataProblem> {
        val problems = mutableListOf<TrendDataProblem>()
        val expectedIssueLength = expectedIssueLength(lotteryType)
        draws.forEach { draw ->
            if (draw.lotteryType != lotteryType) {
                problems +=
                    TrendDataProblem(
                        code = TrendDataProblemCode.MIXED_LOTTERY,
                        issue = draw.issue,
                        message = "期号 ${draw.issue.value} 不属于当前彩种",
                    )
                return@forEach
            }
            if (!isValidIssue(draw.issue, expectedIssueLength)) {
                problems +=
                    TrendDataProblem(
                        code = TrendDataProblemCode.INVALID_ISSUE,
                        issue = draw.issue,
                        message = "期号 ${draw.issue.value} 格式不合法",
                    )
            }
            problems += validateNumbers(draw, LotteryTrendArea.PRIMARY)
            problems += validateNumbers(draw, LotteryTrendArea.SECONDARY)
        }
        problems += validateDuplicateIssues(draws)
        return problems
    }

    /** 校验单期指定号码区域的数量、范围、升序和唯一性。 */
    private fun validateNumbers(
        draw: HistoricalDraw,
        area: LotteryTrendArea,
    ): List<TrendDataProblem> {
        val spec = draw.lotteryType.trendAreaSpec(area)
        val numbers = draw.numbers(area)
        val isValid =
            numbers.size == spec.drawnNumberCount &&
                numbers.all { it in spec.numberRange } &&
                numbers == numbers.sorted() &&
                numbers.distinct().size == numbers.size
        if (isValid) return emptyList()

        val code =
            when (area) {
                LotteryTrendArea.PRIMARY -> TrendDataProblemCode.INVALID_PRIMARY_NUMBERS
                LotteryTrendArea.SECONDARY -> TrendDataProblemCode.INVALID_SECONDARY_NUMBERS
            }
        val areaName =
            when (area) {
                LotteryTrendArea.PRIMARY -> "主号码"
                LotteryTrendArea.SECONDARY -> "次号码"
            }
        return listOf(
            TrendDataProblem(
                code = code,
                issue = draw.issue,
                message = "期号 ${draw.issue.value} 的${areaName}不合法",
            ),
        )
    }

    /** 区分完全重复期号与同一期内容冲突。 */
    private fun validateDuplicateIssues(draws: List<HistoricalDraw>): List<TrendDataProblem> =
        draws
            .groupBy { it.issue.value }
            .filterValues { it.size > 1 }
            .map { (issueValue, sameIssueDraws) ->
                val isExactDuplicate = sameIssueDraws.distinct().size == 1
                TrendDataProblem(
                    code =
                        if (isExactDuplicate) {
                            TrendDataProblemCode.DUPLICATE_ISSUE
                        } else {
                            TrendDataProblemCode.CONFLICTING_ISSUE
                        },
                    issue = Issue(issueValue),
                    message =
                        if (isExactDuplicate) {
                            "期号 $issueValue 存在重复记录"
                        } else {
                            "期号 $issueValue 存在内容冲突"
                        },
                )
            }

    /** 计算走势图矩阵、号码统计与可证明的期号空档。 */
    private fun buildSnapshot(
        lotteryType: LotteryType,
        area: LotteryTrendArea,
        sampleSize: TrendSampleSize,
        spec: LotteryTrendAreaSpec,
        draws: List<HistoricalDraw>,
    ): LotteryTrendSnapshot {
        val hitCounts = IntArray(spec.numberRange.last + 1)
        val currentOmissions = IntArray(spec.numberRange.last + 1)
        val maxOmissions = IntArray(spec.numberRange.last + 1)
        val rows =
            draws.map { draw ->
                val hitNumbers = draw.numbers(area).toSet()
                val cells =
                    spec.numberRange.map { number ->
                        val isHit = number in hitNumbers
                        if (isHit) {
                            hitCounts[number] += 1
                            currentOmissions[number] = 0
                        } else {
                            currentOmissions[number] += 1
                            maxOmissions[number] =
                                maxOf(maxOmissions[number], currentOmissions[number])
                        }
                        TrendNumberCell(
                            number = number,
                            isHit = isHit,
                            omission = currentOmissions[number],
                        )
                    }
                TrendDrawRow(issue = draw.issue, drawDate = draw.drawDate, cells = cells)
            }
        val statistics =
            spec.numberRange.map { number ->
                TrendNumberStatistics(
                    number = number,
                    hitCount = hitCounts[number],
                    currentOmission = currentOmissions[number],
                    maxOmission = maxOmissions[number],
                )
            }
        return LotteryTrendSnapshot(
            lotteryType = lotteryType,
            area = area,
            requestedSampleSize = sampleSize,
            rows = rows,
            statistics = statistics,
            issueGaps = findIssueGaps(lotteryType, draws),
        )
    }

    /** 只在相同年度前缀内报告能够由数字期号证明的空档。 */
    private fun findIssueGaps(
        lotteryType: LotteryType,
        draws: List<HistoricalDraw>,
    ): List<TrendIssueGap> =
        draws.zipWithNext().mapNotNull { (previous, next) ->
            val previousParts = parseIssue(lotteryType, previous.issue)
            val nextParts = parseIssue(lotteryType, next.issue)
            if (
                previousParts.yearPrefix == nextParts.yearPrefix &&
                nextParts.ordinal > previousParts.ordinal + 1
            ) {
                TrendIssueGap(
                    previousIssue = previous.issue,
                    nextIssue = next.issue,
                    missingCount = nextParts.ordinal - previousParts.ordinal - 1,
                )
            } else {
                null
            }
        }

    /** 返回对应彩种固定长度的数字期号规则。 */
    private fun expectedIssueLength(lotteryType: LotteryType): Int =
        when (lotteryType) {
            LotteryType.SUPER_LOTTO -> SUPER_LOTTO_ISSUE_LENGTH
            LotteryType.DOUBLE_COLOR_BALL -> DOUBLE_COLOR_BALL_ISSUE_LENGTH
        }

    /** 判断期号是否满足固定长度、全数字和非零期次要求。 */
    private fun isValidIssue(
        issue: Issue,
        expectedLength: Int,
    ): Boolean =
        issue.value.length == expectedLength &&
            issue.value.all(Char::isDigit) &&
            issue.value.takeLast(ISSUE_ORDINAL_LENGTH).toInt() > 0

    /** 拆分已经通过格式校验的期号。 */
    private fun parseIssue(
        lotteryType: LotteryType,
        issue: Issue,
    ): ParsedIssue {
        val yearPrefixLength = expectedIssueLength(lotteryType) - ISSUE_ORDINAL_LENGTH
        return ParsedIssue(
            yearPrefix = issue.value.take(yearPrefixLength),
            ordinal = issue.value.takeLast(ISSUE_ORDINAL_LENGTH).toInt(),
        )
    }

    /** 返回只包含一个问题的失败结果。 */
    private fun invalid(
        code: TrendDataProblemCode,
        issue: Issue?,
        message: String,
    ): LotteryTrendCalculationResult.InvalidData =
        LotteryTrendCalculationResult.InvalidData(
            problems = listOf(TrendDataProblem(code = code, issue = issue, message = message)),
        )

    /** 拆分后的年度前缀与三位期次序号。 */
    private data class ParsedIssue(
        /** 年度前缀。 */
        val yearPrefix: String,
        /** 年内期次序号。 */
        val ordinal: Int,
    )

    /** 走势图期号与号码范围常量。 */
    private companion object {
        /** 大乐透期号总长度。 */
        const val SUPER_LOTTO_ISSUE_LENGTH = 5

        /** 双色球期号总长度。 */
        const val DOUBLE_COLOR_BALL_ISSUE_LENGTH = 7

        /** 两种彩票的年内期次均为三位数字。 */
        const val ISSUE_ORDINAL_LENGTH = 3
    }
}

/** 返回单期指定区域的开奖号码。 */
private fun HistoricalDraw.numbers(area: LotteryTrendArea): List<Int> =
    when (area) {
        LotteryTrendArea.PRIMARY -> primaryNumbers
        LotteryTrendArea.SECONDARY -> secondaryNumbers
    }

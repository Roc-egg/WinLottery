package roc.win.lottery.domain

/** V1.3 首发数学研究协议常量。 */
object LotteryPredictionProtocol {
    /** 冻结的策略稳定标识。 */
    const val STRATEGY_ID = "frequency-omission-ranking-v1"

    /** 面向用户展示的策略名称。 */
    const val STRATEGY_NAME = "频次遗漏排序"

    /** 冻结的策略版本。 */
    const val STRATEGY_VERSION = 1

    /** 每次生成候选所使用的最近期开奖期数。 */
    const val TRAINING_WINDOW_SIZE = 50

    /** 形成可展示回测至少需要的样本外目标期数。 */
    const val MINIMUM_BACKTEST_TARGET_COUNT = 100

    /** 训练窗口与最少样本外目标期合计所需的最少历史期数。 */
    const val MINIMUM_HISTORY_SIZE = TRAINING_WINDOW_SIZE + MINIMUM_BACKTEST_TARGET_COUNT

    /** 当前会话允许进入数学研究的最多历史期数。 */
    const val MAXIMUM_HISTORY_SIZE = 500
}

/** 数学研究无法形成结果时的稳定编码。 */
enum class LotteryPredictionFailureCode {
    /** 历史期数不足以形成冻结的训练与回测范围。 */
    INSUFFICIENT_HISTORY,

    /** 历史开奖记录未通过完整性校验。 */
    INVALID_HISTORY,

    /** 历史期号中存在可证明的空档。 */
    ISSUE_GAP,
}

/**
 * 一注基于历史排序生成的下一期候选。
 *
 * @property lotteryType 当前彩种。
 * @property strategyId 策略稳定标识。
 * @property strategyName 面向用户展示的策略名称。
 * @property strategyVersion 策略版本。
 * @property trainingWindowSize 固定训练窗口期数。
 * @property trainingFirstIssue 训练窗口最早期号。
 * @property trainingLastIssue 训练窗口最晚期号。
 * @property line 合法且按区域升序排列的一注候选号码。
 */
data class LotteryPredictionCandidate(
    val lotteryType: LotteryType,
    val strategyId: String,
    val strategyName: String,
    val strategyVersion: Int,
    val trainingWindowSize: Int,
    val trainingFirstIssue: Issue,
    val trainingLastIssue: Issue,
    val line: GeneratedNumberLine,
)

/**
 * 一个号码区域的时间前推回测汇总。
 *
 * @property area 当前号码区域。
 * @property targetCount 样本外目标期数。
 * @property totalMatches 策略候选在全部目标期的累计命中数。
 * @property uniformRandomExpectedAverageMatches 均匀无放回选号的每期理论平均命中数。
 */
data class LotteryPredictionAreaBacktest(
    val area: LotteryTrendArea,
    val targetCount: Int,
    val totalMatches: Int,
    val uniformRandomExpectedAverageMatches: Double,
) {
    init {
        require(targetCount > 0) { "回测目标期数必须大于 0" }
        require(totalMatches >= 0) { "回测累计命中数不得为负数" }
    }

    /** 策略候选在全部样本外目标期的每期平均命中数。 */
    val averageMatches: Double
        get() = totalMatches.toDouble() / targetCount

    /** 策略平均命中数减去均匀随机理论平均命中数。 */
    val differenceFromUniformRandom: Double
        get() = averageMatches - uniformRandomExpectedAverageMatches
}

/**
 * 固定协议形成的完整时间前推回测。
 *
 * @property trainingWindowSize 每个目标期使用的历史训练期数。
 * @property targetCount 样本外目标期数。
 * @property firstTargetIssue 最早参与回测的目标期号。
 * @property lastTargetIssue 最晚参与回测的目标期号。
 * @property primary 主号码区域回测汇总。
 * @property secondary 次号码区域回测汇总。
 */
data class LotteryPredictionBacktest(
    val trainingWindowSize: Int,
    val targetCount: Int,
    val firstTargetIssue: Issue,
    val lastTargetIssue: Issue,
    val primary: LotteryPredictionAreaBacktest,
    val secondary: LotteryPredictionAreaBacktest,
)

/**
 * 可直接进入跨平台界面的数学研究结果。
 *
 * @property candidate 基于最新训练窗口生成的下一期候选。
 * @property backtest 使用相同策略实现形成的时间前推回测。
 */
data class LotteryPredictionAnalysis(
    val candidate: LotteryPredictionCandidate,
    val backtest: LotteryPredictionBacktest,
)

/** 数学研究领域计算结果。 */
sealed interface LotteryPredictionResult {
    /**
     * 已形成确定性候选和样本外回测。
     *
     * @property analysis 完整数学研究结果。
     */
    data class Success(
        val analysis: LotteryPredictionAnalysis,
    ) : LotteryPredictionResult

    /**
     * 当前历史数据不满足冻结协议。
     *
     * @property code 稳定失败编码。
     * @property message 面向用户的简体中文阻断说明。
     */
    data class Unavailable(
        val code: LotteryPredictionFailureCode,
        val message: String,
    ) : LotteryPredictionResult
}

/** 确定性彩票候选策略。 */
interface LotteryPredictionStrategy {
    /** 策略稳定标识。 */
    val id: String

    /** 面向用户展示的策略名称。 */
    val displayName: String

    /** 策略版本。 */
    val version: Int

    /** 每次预测必须提供的连续历史期数。 */
    val trainingWindowSize: Int

    /**
     * 从已经校验并按期号升序排列的固定窗口生成一注候选。
     *
     * @param lotteryType 当前彩种。
     * @param trainingDraws 不包含目标期的连续训练窗口。
     * @return 符合当前彩种单式规则的一注候选。
     */
    fun predict(
        lotteryType: LotteryType,
        trainingDraws: List<HistoricalDraw>,
    ): GeneratedNumberLine
}

/** 按冻结的出现次数、当前遗漏和号码值顺序生成候选。 */
class FrequencyOmissionRankingStrategy : LotteryPredictionStrategy {
    /** 策略稳定标识。 */
    override val id: String = LotteryPredictionProtocol.STRATEGY_ID

    /** 面向用户展示的策略名称。 */
    override val displayName: String = LotteryPredictionProtocol.STRATEGY_NAME

    /** 策略版本。 */
    override val version: Int = LotteryPredictionProtocol.STRATEGY_VERSION

    /** 固定使用最近 50 期。 */
    override val trainingWindowSize: Int = LotteryPredictionProtocol.TRAINING_WINDOW_SIZE

    /** 根据两个号码区域各自的冻结排序生成合法候选。 */
    override fun predict(
        lotteryType: LotteryType,
        trainingDraws: List<HistoricalDraw>,
    ): GeneratedNumberLine {
        require(trainingDraws.size == trainingWindowSize) {
            "策略训练窗口必须恰好为 $trainingWindowSize 期"
        }
        require(trainingDraws.all { it.lotteryType == lotteryType }) {
            "策略训练窗口不得混入其他彩种"
        }
        return GeneratedNumberLine(
            primaryNumbers = selectNumbers(lotteryType, LotteryTrendArea.PRIMARY, trainingDraws),
            secondaryNumbers = selectNumbers(lotteryType, LotteryTrendArea.SECONDARY, trainingDraws),
        )
    }

    /** 计算指定区域的出现次数和当前遗漏，并按冻结顺序选出号码。 */
    private fun selectNumbers(
        lotteryType: LotteryType,
        area: LotteryTrendArea,
        trainingDraws: List<HistoricalDraw>,
    ): List<Int> {
        val spec = lotteryType.trendAreaSpec(area)
        val hitCounts = IntArray(spec.numberRange.last + 1)
        val currentOmissions = IntArray(spec.numberRange.last + 1)
        trainingDraws.forEach { draw ->
            val hitNumbers = draw.numbers(area).toSet()
            spec.numberRange.forEach { number ->
                if (number in hitNumbers) {
                    hitCounts[number] += 1
                    currentOmissions[number] = 0
                } else {
                    currentOmissions[number] += 1
                }
            }
        }
        return spec.numberRange
            .sortedWith(
                compareBy<Int> { number -> hitCounts[number] }
                    .thenByDescending { number -> currentOmissions[number] }
                    .thenBy { number -> number },
            ).take(spec.drawnNumberCount)
            .sorted()
    }
}

/**
 * 使用同一策略生成下一期候选并执行严格时间前推回测。
 *
 * @property strategy 生产候选和全部回测目标共同调用的策略实现。
 * @property trendCalculator 复用走势图领域校验与连续性检查。
 */
class LotteryPredictionEngine(
    private val strategy: LotteryPredictionStrategy = FrequencyOmissionRankingStrategy(),
    private val trendCalculator: LotteryTrendCalculator = LotteryTrendCalculator(),
) {
    /**
     * 校验历史开奖、生成最新候选并回测全部可用目标期。
     *
     * @param lotteryType 当前彩种。
     * @param draws 当前会话内取得的规范化历史开奖。
     * @return 完整研究结果或明确失败状态。
     */
    fun analyze(
        lotteryType: LotteryType,
        draws: List<HistoricalDraw>,
    ): LotteryPredictionResult {
        val selectedDraws =
            draws.sortedBy { draw -> draw.issue.value }.takeLast(LotteryPredictionProtocol.MAXIMUM_HISTORY_SIZE)
        val requiredHistorySize =
            strategy.trainingWindowSize + LotteryPredictionProtocol.MINIMUM_BACKTEST_TARGET_COUNT
        if (selectedDraws.size < requiredHistorySize) {
            return unavailable(
                LotteryPredictionFailureCode.INSUFFICIENT_HISTORY,
                "数学研究至少需要连续 $requiredHistorySize 期历史开奖",
            )
        }

        val validation =
            trendCalculator.calculate(
                lotteryType = lotteryType,
                area = LotteryTrendArea.PRIMARY,
                sampleSize = TrendSampleSize.LAST_500,
                draws = selectedDraws,
            )
        val snapshot =
            when (validation) {
                is LotteryTrendCalculationResult.Success -> {
                    validation.snapshot
                }

                is LotteryTrendCalculationResult.InvalidData -> {
                    return unavailable(
                        LotteryPredictionFailureCode.INVALID_HISTORY,
                        "官方历史开奖未通过数学研究完整性校验",
                    )
                }
            }
        if (snapshot.issueGaps.isNotEmpty()) {
            return unavailable(
                LotteryPredictionFailureCode.ISSUE_GAP,
                "历史期号存在空档，不能执行时间前推回测",
            )
        }

        val primarySpec = lotteryType.trendAreaSpec(LotteryTrendArea.PRIMARY)
        val secondarySpec = lotteryType.trendAreaSpec(LotteryTrendArea.SECONDARY)
        var primaryMatches = 0
        var secondaryMatches = 0
        for (targetIndex in strategy.trainingWindowSize until selectedDraws.size) {
            val trainingDraws =
                selectedDraws.subList(targetIndex - strategy.trainingWindowSize, targetIndex)
            val targetDraw = selectedDraws[targetIndex]
            val predicted = strategy.predict(lotteryType, trainingDraws)
            primaryMatches += predicted.primaryNumbers.count { number -> number in targetDraw.primaryNumbers }
            secondaryMatches +=
                predicted.secondaryNumbers.count { number -> number in targetDraw.secondaryNumbers }
        }

        val latestTrainingDraws = selectedDraws.takeLast(strategy.trainingWindowSize)
        val candidateLine = strategy.predict(lotteryType, latestTrainingDraws)
        val targetCount = selectedDraws.size - strategy.trainingWindowSize
        val firstTarget = selectedDraws[strategy.trainingWindowSize]
        val lastTarget = selectedDraws.last()
        return LotteryPredictionResult.Success(
            analysis =
                LotteryPredictionAnalysis(
                    candidate =
                        LotteryPredictionCandidate(
                            lotteryType = lotteryType,
                            strategyId = strategy.id,
                            strategyName = strategy.displayName,
                            strategyVersion = strategy.version,
                            trainingWindowSize = strategy.trainingWindowSize,
                            trainingFirstIssue = latestTrainingDraws.first().issue,
                            trainingLastIssue = latestTrainingDraws.last().issue,
                            line = candidateLine,
                        ),
                    backtest =
                        LotteryPredictionBacktest(
                            trainingWindowSize = strategy.trainingWindowSize,
                            targetCount = targetCount,
                            firstTargetIssue = firstTarget.issue,
                            lastTargetIssue = lastTarget.issue,
                            primary =
                                areaBacktest(
                                    LotteryTrendArea.PRIMARY,
                                    targetCount,
                                    primaryMatches,
                                    primarySpec,
                                ),
                            secondary =
                                areaBacktest(
                                    LotteryTrendArea.SECONDARY,
                                    targetCount,
                                    secondaryMatches,
                                    secondarySpec,
                                ),
                        ),
                ),
        )
    }

    /** 创建指定号码区域的命中汇总和精确均匀随机理论基线。 */
    private fun areaBacktest(
        area: LotteryTrendArea,
        targetCount: Int,
        totalMatches: Int,
        spec: LotteryTrendAreaSpec,
    ): LotteryPredictionAreaBacktest {
        val selectedCount = spec.drawnNumberCount.toDouble()
        val populationSize = spec.numberRange.count().toDouble()
        return LotteryPredictionAreaBacktest(
            area = area,
            targetCount = targetCount,
            totalMatches = totalMatches,
            uniformRandomExpectedAverageMatches = selectedCount * selectedCount / populationSize,
        )
    }

    /** 创建不包含上游原始内容的失败结果。 */
    private fun unavailable(
        code: LotteryPredictionFailureCode,
        message: String,
    ): LotteryPredictionResult.Unavailable = LotteryPredictionResult.Unavailable(code, message)
}

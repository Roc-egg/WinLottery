package roc.win.lottery.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** V1.3 固定数学策略与时间前推回测领域契约测试。 */
class LotteryPredictionEngineTest {
    /** 冻结策略必须按出现次数、当前遗漏和号码值稳定生成同一候选。 */
    @Test
    fun ranksFrequencyThenCurrentOmissionDeterministically() {
        val draws =
            (1..50).map { ordinal ->
                doubleColorBallDraw(
                    ordinal = ordinal,
                    primary = listOf(28, 29, 30, 31, 32, 33),
                    secondary = listOf(if (ordinal <= 48) (ordinal - 1) % 16 + 1 else ordinal - 48),
                )
            }
        val strategy = FrequencyOmissionRankingStrategy()

        val first = strategy.predict(LotteryType.DOUBLE_COLOR_BALL, draws)
        val second = strategy.predict(LotteryType.DOUBLE_COLOR_BALL, draws)

        assertEquals(listOf(1, 2, 3, 4, 5, 6), first.primaryNumbers)
        assertEquals(listOf(3), first.secondaryNumbers)
        assertEquals(first, second)
    }

    /** 150 期必须形成 100 个样本外目标和合法的下一期候选。 */
    @Test
    fun createsCandidateAndBacktestFromMinimumHistory() {
        val result =
            success(
                (1..150).map { ordinal ->
                    superLottoDraw(
                        ordinal = ordinal,
                        primary = listOf(31, 32, 33, 34, 35),
                        secondary = listOf(11, 12),
                    )
                },
            )

        assertEquals(listOf(1, 2, 3, 4, 5), result.candidate.line.primaryNumbers)
        assertEquals(listOf(1, 2), result.candidate.line.secondaryNumbers)
        assertEquals("26101", result.candidate.trainingFirstIssue.value)
        assertEquals("26150", result.candidate.trainingLastIssue.value)
        assertEquals(100, result.backtest.targetCount)
        assertEquals("26051", result.backtest.firstTargetIssue.value)
        assertEquals("26150", result.backtest.lastTargetIssue.value)
        assertEquals(0.0, result.backtest.primary.averageMatches)
        assertEquals(0.0, result.backtest.secondary.averageMatches)
        assertEquals(25.0 / 35.0, result.backtest.primary.uniformRandomExpectedAverageMatches)
        assertEquals(4.0 / 12.0, result.backtest.secondary.uniformRandomExpectedAverageMatches)
    }

    /** 当前会话完整 500 期必须形成冻结的 450 个样本外目标期。 */
    @Test
    fun backtestsFourHundredFiftyTargetsFromFullSessionHistory() {
        val result = success((1..500).map(::superLottoDraw))

        assertEquals(450, result.backtest.targetCount)
        assertEquals("26051", result.backtest.firstTargetIssue.value)
        assertEquals("26500", result.backtest.lastTargetIssue.value)
        assertEquals("26451", result.candidate.trainingFirstIssue.value)
        assertEquals("26500", result.candidate.trainingLastIssue.value)
    }

    /** 每个回测目标只能使用此前 50 期，最新候选才能使用最后一期。 */
    @Test
    fun neverLeaksTargetDrawIntoTrainingWindow() {
        val strategy = RecordingPredictionStrategy()
        val result =
            assertIs<LotteryPredictionResult.Success>(
                LotteryPredictionEngine(strategy = strategy).analyze(
                    LotteryType.SUPER_LOTTO,
                    (1..150).map(::superLottoDraw),
                ),
            ).analysis

        assertEquals(101, strategy.trainingIssueWindows.size)
        assertEquals("26001", strategy.trainingIssueWindows.first().first())
        assertEquals("26050", strategy.trainingIssueWindows.first().last())
        assertEquals("26100", strategy.trainingIssueWindows[99].first())
        assertEquals("26149", strategy.trainingIssueWindows[99].last())
        assertEquals("26101", strategy.trainingIssueWindows.last().first())
        assertEquals("26150", strategy.trainingIssueWindows.last().last())
        assertEquals(100, result.backtest.targetCount)
    }

    /** 输入顺序不得改变候选、回测范围或命中汇总。 */
    @Test
    fun sortsHistoryBeforePredictionAndBacktest() {
        val draws = (1..150).map(::superLottoDraw)
        val chronological = success(draws)
        val reversed = success(draws.reversed())

        assertEquals(chronological, reversed)
    }

    /** 双色球主次区域必须使用各自规则计算精确均匀随机理论基线。 */
    @Test
    fun calculatesDoubleColorBallUniformRandomBaseline() {
        val result =
            assertIs<LotteryPredictionResult.Success>(
                engine.analyze(
                    LotteryType.DOUBLE_COLOR_BALL,
                    (1..150).map(::doubleColorBallDraw),
                ),
            ).analysis

        assertEquals(36.0 / 33.0, result.backtest.primary.uniformRandomExpectedAverageMatches)
        assertEquals(1.0 / 16.0, result.backtest.secondary.uniformRandomExpectedAverageMatches)
    }

    /** 历史不足、记录非法或存在可证明空档时都必须失败关闭。 */
    @Test
    fun rejectsInsufficientInvalidAndGappedHistory() {
        val insufficient = engine.analyze(LotteryType.SUPER_LOTTO, (1..149).map(::superLottoDraw))
        val invalid =
            engine.analyze(
                LotteryType.SUPER_LOTTO,
                (1..149).map(::superLottoDraw) + doubleColorBallDraw(150),
            )
        val gapped =
            engine.analyze(
                LotteryType.SUPER_LOTTO,
                (1..150).filterNot { it == 80 }.map(::superLottoDraw) + superLottoDraw(151),
            )

        assertEquals(
            LotteryPredictionFailureCode.INSUFFICIENT_HISTORY,
            assertIs<LotteryPredictionResult.Unavailable>(insufficient).code,
        )
        assertEquals(
            LotteryPredictionFailureCode.INVALID_HISTORY,
            assertIs<LotteryPredictionResult.Unavailable>(invalid).code,
        )
        assertEquals(
            LotteryPredictionFailureCode.ISSUE_GAP,
            assertIs<LotteryPredictionResult.Unavailable>(gapped).code,
        )
    }

    /** 从成功结果中提取完整数学研究。 */
    private fun success(draws: List<HistoricalDraw>): LotteryPredictionAnalysis =
        assertIs<LotteryPredictionResult.Success>(
            engine.analyze(LotteryType.SUPER_LOTTO, draws),
        ).analysis

    /** 创建大乐透历史开奖测试记录。 */
    private fun superLottoDraw(
        ordinal: Int,
        primary: List<Int> = listOf(1, 2, 3, 4, 5),
        secondary: List<Int> = listOf(1, 2),
    ): HistoricalDraw =
        HistoricalDraw(
            lotteryType = LotteryType.SUPER_LOTTO,
            issue = Issue("26${ordinal.toString().padStart(3, '0')}"),
            drawDate = "2026-01-01",
            primaryNumbers = primary,
            secondaryNumbers = secondary,
        )

    /** 创建双色球历史开奖测试记录。 */
    private fun doubleColorBallDraw(
        ordinal: Int,
        primary: List<Int> = listOf(1, 2, 3, 4, 5, 6),
        secondary: List<Int> = listOf(1),
    ): HistoricalDraw =
        HistoricalDraw(
            lotteryType = LotteryType.DOUBLE_COLOR_BALL,
            issue = Issue("2026${ordinal.toString().padStart(3, '0')}"),
            drawDate = "2026-01-01",
            primaryNumbers = primary,
            secondaryNumbers = secondary,
        )

    /** 被测默认数学研究引擎。 */
    private val engine = LotteryPredictionEngine()

    /** 记录每次策略调用的训练期号窗口，用于证明目标期没有泄漏。 */
    private class RecordingPredictionStrategy : LotteryPredictionStrategy {
        /** 策略稳定标识。 */
        override val id: String = "recording-test"

        /** 测试显示名称。 */
        override val displayName: String = "测试策略"

        /** 测试策略版本。 */
        override val version: Int = 1

        /** 与冻结协议相同的训练窗口。 */
        override val trainingWindowSize: Int = LotteryPredictionProtocol.TRAINING_WINDOW_SIZE

        /** 按调用顺序保存的全部训练期号窗口。 */
        val trainingIssueWindows = mutableListOf<List<String>>()

        /** 记录训练窗口并返回固定合法号码。 */
        override fun predict(
            lotteryType: LotteryType,
            trainingDraws: List<HistoricalDraw>,
        ): GeneratedNumberLine {
            trainingIssueWindows += trainingDraws.map { draw -> draw.issue.value }
            return GeneratedNumberLine(
                primaryNumbers = listOf(1, 2, 3, 4, 5),
                secondaryNumbers = listOf(1, 2),
            )
        }
    }
}

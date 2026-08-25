package roc.win.lottery.domain

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** V1.2 随机号码生成领域契约测试。 */
class RandomNumberGeneratorTest {
    /** 大乐透结果必须始终满足五前区、两后区、范围、升序和唯一性。 */
    @Test
    fun superLottoLinesAlwaysMatchNumberRules() {
        val generator = LotteryRandomNumberGenerator(KotlinRandomIntSource(Random(1201)))

        repeat(1_000) {
            val result =
                generator.generate(
                    request(
                        lotteryType = LotteryType.SUPER_LOTTO,
                        periodCount = 20,
                        betsPerPeriod = 10,
                    ),
                )
            val plan = assertIs<RandomNumberGenerationResult.Success>(result).plan

            assertEquals(200, plan.totalBetCount)
            plan.periods.flatMap(GeneratedPeriodNumbers::lines).forEach { line ->
                assertLegalNumbers(line.primaryNumbers, expectedCount = 5, allowedRange = 1..35)
                assertLegalNumbers(line.secondaryNumbers, expectedCount = 2, allowedRange = 1..12)
            }
        }
    }

    /** 双色球结果必须始终满足六红球、一蓝球、范围、升序和唯一性。 */
    @Test
    fun doubleColorBallLinesAlwaysMatchNumberRules() {
        val generator = LotteryRandomNumberGenerator(KotlinRandomIntSource(Random(1202)))

        repeat(1_000) {
            val result =
                generator.generate(
                    request(
                        lotteryType = LotteryType.DOUBLE_COLOR_BALL,
                        periodCount = 20,
                        betsPerPeriod = 10,
                        mode = CrossPeriodRandomMode.INDEPENDENT_PER_PERIOD,
                    ),
                )
            val plan = assertIs<RandomNumberGenerationResult.Success>(result).plan

            assertEquals(200, plan.totalBetCount)
            plan.periods.flatMap(GeneratedPeriodNumbers::lines).forEach { line ->
                assertLegalNumbers(line.primaryNumbers, expectedCount = 6, allowedRange = 1..33)
                assertLegalNumbers(line.secondaryNumbers, expectedCount = 1, allowedRange = 1..16)
            }
        }
    }

    /** 复用模式下每一期必须持有完全相同且顺序一致的整批注单。 */
    @Test
    fun reuseModeKeepsTheSameLinesAcrossAllPeriods() {
        val generator = LotteryRandomNumberGenerator(KotlinRandomIntSource(Random(1203)))

        val result =
            generator.generate(
                request(
                    lotteryType = LotteryType.DOUBLE_COLOR_BALL,
                    periodCount = 8,
                    betsPerPeriod = 6,
                ),
            )
        val plan = assertIs<RandomNumberGenerationResult.Success>(result).plan

        assertEquals(8, plan.periods.size)
        plan.periods.forEachIndexed { index, period ->
            assertEquals(index + 1, period.periodIndex)
            assertEquals(plan.periods.first().lines, period.lines)
        }
    }

    /** 独立模式不得为了排除跨期偶然相同而额外重抽。 */
    @Test
    fun independentModeAllowsCoincidentallyEqualPeriods() {
        val generator = LotteryRandomNumberGenerator(RandomIntSource { 0 })

        val result =
            generator.generate(
                request(
                    lotteryType = LotteryType.DOUBLE_COLOR_BALL,
                    periodCount = 3,
                    betsPerPeriod = 2,
                    mode = CrossPeriodRandomMode.INDEPENDENT_PER_PERIOD,
                ),
            )
        val plan = assertIs<RandomNumberGenerationResult.Success>(result).plan

        assertEquals(plan.periods[0].lines, plan.periods[1].lines)
        assertEquals(plan.periods[1].lines, plan.periods[2].lines)
    }

    /** 大乐透不得出现逐期独立随机的可达领域结果。 */
    @Test
    fun superLottoRejectsIndependentMode() {
        val result =
            LotteryRandomNumberGenerator().generate(
                request(
                    lotteryType = LotteryType.SUPER_LOTTO,
                    periodCount = 2,
                    betsPerPeriod = 1,
                    mode = CrossPeriodRandomMode.INDEPENDENT_PER_PERIOD,
                ),
            )

        assertEquals(
            "大乐透多期只支持复用同一批随机号码",
            assertIs<RandomNumberGenerationResult.InvalidRequest>(result).message,
        )
    }

    /** 期数和每期注数超出冻结边界时必须返回稳定错误。 */
    @Test
    fun outOfRangeCountsAreRejected() {
        val generator = LotteryRandomNumberGenerator()

        val invalidPeriod =
            generator.generate(
                request(
                    lotteryType = LotteryType.SUPER_LOTTO,
                    periodCount = 21,
                    betsPerPeriod = 1,
                ),
            )
        val invalidBets =
            generator.generate(
                request(
                    lotteryType = LotteryType.DOUBLE_COLOR_BALL,
                    periodCount = 1,
                    betsPerPeriod = 11,
                ),
            )

        assertEquals(
            "期数必须在 1 至 20 之间",
            assertIs<RandomNumberGenerationResult.InvalidRequest>(invalidPeriod).message,
        )
        assertEquals(
            "每期注数必须在 1 至 10 之间",
            assertIs<RandomNumberGenerationResult.InvalidRequest>(invalidBets).message,
        )
    }

    /** 固定种子的大样本频次不得出现明显偏斜，防止错误抽样实现长期漏号。 */
    @Test
    fun seededSampleHasSaneNumberFrequencies() {
        val generator = LotteryRandomNumberGenerator(KotlinRandomIntSource(Random(1204)))
        val primaryFrequencies = IntArray(36)
        val secondaryFrequencies = IntArray(13)

        repeat(1_000) {
            val plan =
                assertIs<RandomNumberGenerationResult.Success>(
                    generator.generate(
                        request(
                            lotteryType = LotteryType.SUPER_LOTTO,
                            periodCount = 1,
                            betsPerPeriod = 10,
                        ),
                    ),
                ).plan
            plan.periods.single().lines.forEach { line ->
                line.primaryNumbers.forEach { primaryFrequencies[it] += 1 }
                line.secondaryNumbers.forEach { secondaryFrequencies[it] += 1 }
            }
        }

        assertFrequencySpread(primaryFrequencies.sliceArray(1..35), maxToMinRatio = 1.2)
        assertFrequencySpread(secondaryFrequencies.sliceArray(1..12), maxToMinRatio = 1.15)
    }

    /** 创建使用复用模式作为默认值的测试请求。 */
    private fun request(
        lotteryType: LotteryType,
        periodCount: Int,
        betsPerPeriod: Int,
        mode: CrossPeriodRandomMode = CrossPeriodRandomMode.REUSE_SAME_LINES,
    ): RandomNumberGenerationRequest =
        RandomNumberGenerationRequest(
            lotteryType = lotteryType,
            periodCount = periodCount,
            betsPerPeriod = betsPerPeriod,
            crossPeriodMode = mode,
        )

    /** 断言一个号码区域满足数量、范围、升序与唯一性。 */
    private fun assertLegalNumbers(
        numbers: List<Int>,
        expectedCount: Int,
        allowedRange: IntRange,
    ) {
        assertEquals(expectedCount, numbers.size)
        assertTrue(numbers.all { it in allowedRange })
        assertEquals(numbers.sorted(), numbers)
        assertEquals(numbers.size, numbers.distinct().size)
    }

    /** 断言每个合法号码均出现，且最大最小频次比不超过保守阈值。 */
    private fun assertFrequencySpread(
        frequencies: IntArray,
        maxToMinRatio: Double,
    ) {
        val minimum = frequencies.min()
        val maximum = frequencies.max()
        assertTrue(minimum > 0)
        assertTrue(maximum.toDouble() / minimum.toDouble() <= maxToMinRatio)
    }
}

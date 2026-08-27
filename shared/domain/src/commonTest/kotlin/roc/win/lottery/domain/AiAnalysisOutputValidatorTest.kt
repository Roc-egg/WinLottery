package roc.win.lottery.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** V1.4 AI 模型输出本地校验测试。 */
class AiAnalysisOutputValidatorTest {
    /** 完整合法的输出必须转换为可展示结果并规范化首尾空白。 */
    @Test
    fun acceptsCompleteValidOutput() {
        val output =
            validOutput(
                candidates =
                    listOf(
                        candidate(listOf(1, 8, 15, 22, 35), listOf(2, 11), " 第一注说明 "),
                        candidate(listOf(3, 9, 16, 24, 31), listOf(4, 12), "第二注说明"),
                    ),
            ).copy(summary = " 样本内只观察到历史分布，不代表未来概率。 ")

        val result = assertIs<AiAnalysisValidationResult.Success>(validator.validate(request, output)).result

        assertEquals(LotteryType.SUPER_LOTTO, result.lotteryType)
        assertEquals(request.snapshot.id, result.snapshotId)
        assertEquals("样本内只观察到历史分布，不代表未来概率。", result.summary)
        assertEquals("第一注说明", result.candidates.first().reason)
        assertEquals(
            listOf(1, 8, 15, 22, 35),
            result.candidates
                .first()
                .line.primaryNumbers,
        )
    }

    /** 双色球输出必须按六个红球和一个蓝球的独立规则通过校验。 */
    @Test
    fun acceptsDoubleColorBallOutputWithItsOwnRules() {
        val doubleColorBallSnapshot = doubleColorBallSnapshot()
        val doubleColorBallRequest =
            AiAnalysisRequest(
                snapshot = doubleColorBallSnapshot,
                template = AiAnalysisTemplate.DISTRIBUTION_STRUCTURE,
                candidateCount = 1,
            )
        val output =
            UntrustedAiAnalysisOutput(
                schemaVersion = AiAnalysisProtocol.OUTPUT_SCHEMA_VERSION,
                lotteryType = LotteryType.DOUBLE_COLOR_BALL.name,
                snapshotId = doubleColorBallSnapshot.id,
                summary = "双色球历史样本摘要",
                candidates =
                    listOf(
                        candidate(
                            primary = listOf(1, 6, 12, 18, 25, 33),
                            secondary = listOf(16),
                        ),
                    ),
            )

        val result =
            assertIs<AiAnalysisValidationResult.Success>(
                validator.validate(doubleColorBallRequest, output),
            ).result

        assertEquals(LotteryType.DOUBLE_COLOR_BALL, result.lotteryType)
        assertEquals(
            listOf(16),
            result.candidates
                .single()
                .line.secondaryNumbers,
        )
    }

    /** 格式版本、彩种和快照回显不一致时必须同时报告。 */
    @Test
    fun rejectsMismatchedOutputIdentity() {
        val result =
            invalid(
                validOutput().copy(
                    schemaVersion = 2,
                    lotteryType = LotteryType.DOUBLE_COLOR_BALL.name,
                    snapshotId = "0".repeat(64),
                ),
            )

        assertEquals(
            listOf(
                AiAnalysisValidationProblemCode.SCHEMA_VERSION_MISMATCH,
                AiAnalysisValidationProblemCode.LOTTERY_TYPE_MISMATCH,
                AiAnalysisValidationProblemCode.SNAPSHOT_ID_MISMATCH,
            ),
            result.problems.map { problem -> problem.code },
        )
    }

    /** 候选数量与确认值不一致时不得展示现有部分。 */
    @Test
    fun rejectsPartialCandidateList() {
        val result = invalid(validOutput(candidates = listOf(candidate())))

        assertEquals(
            AiAnalysisValidationProblemCode.CANDIDATE_COUNT_MISMATCH,
            result.problems.single().code,
        )
    }

    /** 主次号码的数量、范围、唯一性和升序必须全部符合当前彩种。 */
    @Test
    fun rejectsInvalidCandidateNumbers() {
        val output =
            validOutput(
                candidates =
                    listOf(
                        candidate(primary = listOf(1, 2, 3, 3, 36)),
                        candidate(primary = listOf(2, 4, 6, 8, 10), secondary = listOf(12, 1)),
                    ),
            )

        val result = invalid(output)

        assertEquals(
            listOf(
                AiAnalysisValidationProblemCode.INVALID_PRIMARY_NUMBERS,
                AiAnalysisValidationProblemCode.INVALID_SECONDARY_NUMBERS,
            ),
            result.problems.map { problem -> problem.code },
        )
        assertEquals(listOf(0, 1), result.problems.map { problem -> problem.candidateIndex })
    }

    /** 完全重复候选必须整批失败，即使说明文本不同。 */
    @Test
    fun rejectsDuplicateCandidates() {
        val first = candidate(reason = "说明甲")
        val duplicate = first.copy(reason = "说明乙")

        val result = invalid(validOutput(candidates = listOf(first, duplicate)))

        assertEquals(AiAnalysisValidationProblemCode.DUPLICATE_CANDIDATE, result.problems.single().code)
        assertEquals(1, result.problems.single().candidateIndex)
    }

    /** 空摘要、空说明和超长文本必须失败关闭。 */
    @Test
    fun rejectsInvalidTextFields() {
        val result =
            invalid(
                validOutput(
                    candidates =
                        listOf(
                            candidate(reason = " "),
                            candidate(
                                primary = listOf(2, 4, 6, 8, 10),
                                secondary = listOf(3, 4),
                                reason = "a".repeat(AiAnalysisProtocol.MAX_REASON_LENGTH + 1),
                            ),
                        ),
                ).copy(summary = " "),
            )

        assertEquals(
            listOf(
                AiAnalysisValidationProblemCode.INVALID_SUMMARY,
                AiAnalysisValidationProblemCode.INVALID_REASON,
                AiAnalysisValidationProblemCode.INVALID_REASON,
            ),
            result.problems.map { problem -> problem.code },
        )
    }

    /** 请求构造必须执行冻结的 1 至 5 注边界。 */
    @Test
    fun enforcesCandidateCountLimits() {
        assertFailsWith<IllegalArgumentException> {
            AiAnalysisRequest(snapshot, AiAnalysisTemplate.COMPREHENSIVE, candidateCount = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            AiAnalysisRequest(snapshot, AiAnalysisTemplate.COMPREHENSIVE, candidateCount = 6)
        }
    }

    /** 首发模板标识必须唯一且带显式版本。 */
    @Test
    fun exposesUniqueVersionedTemplateIds() {
        val ids = AiAnalysisTemplate.entries.map { template -> template.id }

        assertEquals(ids.size, ids.distinct().size)
        assertEquals(true, ids.all { id -> id.endsWith("-v1") })
    }

    /** 提取输出校验失败状态。 */
    private fun invalid(output: UntrustedAiAnalysisOutput): AiAnalysisValidationResult.Invalid =
        assertIs<AiAnalysisValidationResult.Invalid>(validator.validate(request, output))

    /** 构造与当前请求身份一致的模型输出。 */
    private fun validOutput(
        candidates: List<UntrustedAiAnalysisCandidate> =
            listOf(
                candidate(),
                candidate(primary = listOf(2, 4, 6, 8, 10), secondary = listOf(3, 4)),
            ),
    ): UntrustedAiAnalysisOutput =
        UntrustedAiAnalysisOutput(
            schemaVersion = AiAnalysisProtocol.OUTPUT_SCHEMA_VERSION,
            lotteryType = request.snapshot.lotteryType.name,
            snapshotId = request.snapshot.id,
            summary = "样本内历史分布摘要",
            candidates = candidates,
        )

    /** 构造一注默认合法的大乐透候选。 */
    private fun candidate(
        primary: List<Int> = listOf(1, 3, 5, 7, 9),
        secondary: List<Int> = listOf(1, 2),
        reason: String = "只说明历史样本特征",
    ): UntrustedAiAnalysisCandidate =
        UntrustedAiAnalysisCandidate(
            primaryNumbers = primary,
            secondaryNumbers = secondary,
            reason = reason,
        )

    /** 构造 50 期合法大乐透历史开奖。 */
    private fun snapshotDraws(): List<HistoricalDraw> =
        (1..50).map { ordinal ->
            HistoricalDraw(
                lotteryType = LotteryType.SUPER_LOTTO,
                issue = Issue("26${ordinal.toString().padStart(3, '0')}"),
                drawDate = "2026-08-10",
                primaryNumbers = listOf(1, 2, 3, 4, 5),
                secondaryNumbers = listOf(1, 2),
            )
        }

    /** 构造 50 期合法双色球历史开奖快照。 */
    private fun doubleColorBallSnapshot(): AiHistorySnapshot =
        assertIs<AiHistorySnapshotBuildResult.Success>(
            AiHistorySnapshotBuilder().build(
                lotteryType = LotteryType.DOUBLE_COLOR_BALL,
                sampleSize = TrendSampleSize.LAST_50,
                draws =
                    (1..50).map { ordinal ->
                        HistoricalDraw(
                            lotteryType = LotteryType.DOUBLE_COLOR_BALL,
                            issue = Issue("2026${ordinal.toString().padStart(3, '0')}"),
                            drawDate = "2026-08-10",
                            primaryNumbers = listOf(1, 2, 3, 4, 5, 6),
                            secondaryNumbers = listOf(1),
                        )
                    },
            ),
        ).snapshot

    /** 被测输出校验器。 */
    private val validator = AiAnalysisOutputValidator()

    /** 当前测试使用的稳定历史快照。 */
    private val snapshot =
        assertIs<AiHistorySnapshotBuildResult.Success>(
            AiHistorySnapshotBuilder().build(
                lotteryType = LotteryType.SUPER_LOTTO,
                sampleSize = TrendSampleSize.LAST_50,
                draws = snapshotDraws(),
            ),
        ).snapshot

    /** 当前测试使用的两注确认请求。 */
    private val request =
        AiAnalysisRequest(
            snapshot = snapshot,
            template = AiAnalysisTemplate.COMPREHENSIVE,
            candidateCount = 2,
        )
}

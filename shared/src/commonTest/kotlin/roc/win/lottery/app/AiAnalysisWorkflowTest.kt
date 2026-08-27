package roc.win.lottery.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import roc.win.lottery.data.AiAnalysisProvider
import roc.win.lottery.data.AiProviderConfiguration
import roc.win.lottery.data.AiProviderPreviewResult
import roc.win.lottery.data.AiProviderRequestPreview
import roc.win.lottery.data.AiProviderResult
import roc.win.lottery.domain.AiAnalysisCandidate
import roc.win.lottery.domain.AiAnalysisProtocol
import roc.win.lottery.domain.AiAnalysisRequest
import roc.win.lottery.domain.AiAnalysisResult
import roc.win.lottery.domain.GeneratedNumberLine
import roc.win.lottery.domain.HistoricalDraw
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.TrendSampleSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** AI 分析共享工作流的密钥、确认和单请求边界测试。 */
class AiAnalysisWorkflowTest {
    /** 合法配置必须形成精确预览，且可打印状态绝不能包含会话密钥。 */
    @Test
    fun preparesPreviewWithoutExposingSessionSecret() {
        val provider = RecordingAiProvider()
        val workflow = configuredWorkflow(provider)

        assertTrue(workflow.preparePreview(TEST_SESSION_SECRET))

        val operation = assertIs<AiAnalysisOperation.AwaitingConfirmation>(workflow.state.operation)
        assertEquals(1, provider.previewCount)
        assertEquals(50, operation.preview.sampleCount)
        assertFalse(workflow.state.toString().contains(TEST_SESSION_SECRET))
    }

    /** 非法密钥或不足样本必须在联网前阻断，不能进入 Provider 请求。 */
    @Test
    fun blocksInvalidConfigurationAndInsufficientHistoryLocally() {
        val provider = RecordingAiProvider()
        val workflow = configuredWorkflow(provider)

        assertFalse(workflow.preparePreview(""))
        workflow.updateSettings(workflow.state.settings.copy(sampleSize = TrendSampleSize.LAST_80))
        assertFalse(workflow.preparePreview(TEST_SESSION_SECRET))

        assertEquals(0, provider.previewCount)
        assertEquals(0, provider.requestCount)
        assertTrue(workflow.state.errorMessage?.contains("80") == true)
    }

    /** 配置变化必须立即销毁旧确认，旧指纹不能触发网络请求。 */
    @Test
    fun invalidatesOldConfirmationWhenSettingsChange() =
        runTest {
            val provider = RecordingAiProvider()
            val workflow = configuredWorkflow(provider)
            assertTrue(workflow.preparePreview(TEST_SESSION_SECRET))
            val fingerprint =
                assertIs<AiAnalysisOperation.AwaitingConfirmation>(workflow.state.operation)
                    .preview
                    .requestFingerprint

            workflow.updateSettings(workflow.state.settings.copy(model = "changed-model-v1"))

            assertFalse(workflow.confirmPreview(fingerprint))
            assertEquals(0, provider.requestCount)
            assertIs<AiAnalysisOperation.Idle>(workflow.state.operation)
        }

    /** 一次确认只能发起一次请求，加载和成功结果必须按顺序发布。 */
    @Test
    fun consumesConfirmationOnceAndPublishesValidatedResult() =
        runTest {
            val provider = RecordingAiProvider(blockRequest = true)
            val published = mutableListOf<AiAnalysisWorkspaceState>()
            val workflow = configuredWorkflow(provider, published::add)
            assertTrue(workflow.preparePreview(TEST_SESSION_SECRET))
            val fingerprint =
                assertIs<AiAnalysisOperation.AwaitingConfirmation>(workflow.state.operation)
                    .preview
                    .requestFingerprint

            val first = async { workflow.confirmPreview(fingerprint) }
            provider.requestStarted.await()

            assertIs<AiAnalysisOperation.Requesting>(workflow.state.operation)
            assertFalse(workflow.confirmPreview(fingerprint))
            provider.completeRequest()
            assertTrue(first.await())

            assertEquals(1, provider.requestCount)
            assertEquals(FIXED_NOW.toEpochMilliseconds(), workflow.state.lastResult?.generatedAtEpochMillis)
            assertEquals("测试历史来源", workflow.state.lastResult?.sourceName)
            assertNull(workflow.state.errorMessage)
            assertTrue(published.any { state -> state.operation is AiAnalysisOperation.Requesting })
        }

    /** 逻辑取消后即使 Provider 返回成功，迟到结果也不能覆盖取消状态。 */
    @Test
    fun discardsLateResultAfterCancellation() =
        runTest {
            val provider = RecordingAiProvider(blockRequest = true)
            val workflow = configuredWorkflow(provider)
            assertTrue(workflow.preparePreview(TEST_SESSION_SECRET))
            val fingerprint =
                assertIs<AiAnalysisOperation.AwaitingConfirmation>(workflow.state.operation)
                    .preview
                    .requestFingerprint
            val request = async { workflow.confirmPreview(fingerprint) }
            provider.requestStarted.await()

            workflow.cancelActiveRequest()
            provider.completeRequest()

            assertFalse(request.await())
            assertNull(workflow.state.lastResult)
            assertEquals("已取消本次 AI 请求，未自动重试", workflow.state.errorMessage)
        }

    /** Provider 失败只展示一次错误，不能复用已消费确认自动重试。 */
    @Test
    fun doesNotRetryProviderFailure() =
        runTest {
            val provider =
                RecordingAiProvider(
                    fixedResult =
                        AiProviderResult.Failure(
                            code = roc.win.lottery.data.AiProviderFailureCode.RATE_LIMITED,
                            message = "AI 服务当前限流，请稍后由你主动重试",
                        ),
                )
            val workflow = configuredWorkflow(provider)
            assertTrue(workflow.preparePreview(TEST_SESSION_SECRET))
            val fingerprint =
                assertIs<AiAnalysisOperation.AwaitingConfirmation>(workflow.state.operation)
                    .preview
                    .requestFingerprint

            assertTrue(workflow.confirmPreview(fingerprint))
            assertFalse(workflow.confirmPreview(fingerprint))

            assertEquals(1, provider.requestCount)
            assertTrue(workflow.state.errorMessage?.contains("限流") == true)
        }

    /** 上层协程取消必须继续传播，同时清除正在请求的界面状态。 */
    @Test
    fun propagatesCoroutineCancellation() =
        runTest {
            val provider = RecordingAiProvider(throwsCancellation = true)
            val workflow = configuredWorkflow(provider)
            assertTrue(workflow.preparePreview(TEST_SESSION_SECRET))
            val fingerprint =
                assertIs<AiAnalysisOperation.AwaitingConfirmation>(workflow.state.operation)
                    .preview
                    .requestFingerprint

            assertFailsWith<CancellationException> {
                workflow.confirmPreview(fingerprint)
            }

            assertIs<AiAnalysisOperation.Idle>(workflow.state.operation)
            assertEquals("已取消本次 AI 请求，未自动重试", workflow.state.errorMessage)
        }

    /** 构造已填写非敏感配置并取得 50 期会话历史的工作流。 */
    private fun configuredWorkflow(
        provider: RecordingAiProvider,
        onStateChanged: (AiAnalysisWorkspaceState) -> Unit = {},
    ): AiAnalysisWorkflow {
        val workflow =
            AiAnalysisWorkflow(
                provider = provider,
                clock = FixedClock,
                onStateChanged = onStateChanged,
            )
        workflow.updateSettings(
            workflow.state.settings.copy(
                providerName = "测试服务",
                endpointUrl = "https://api.example.test/v1/responses",
                model = "test-model-v1",
            ),
        )
        workflow.showHistoryReady(
            draws = historicalDraws(50),
            sourceName = "测试历史来源",
            fetchedAtEpochMillis = 1_787_776_000_000L,
        )
        return workflow
    }

    /** 构造连续期号且符合大乐透范围的历史开奖。 */
    private fun historicalDraws(count: Int): List<HistoricalDraw> =
        (1..count).map { ordinal ->
            HistoricalDraw(
                lotteryType = LotteryType.SUPER_LOTTO,
                issue = Issue("23${ordinal.toString().padStart(3, '0')}"),
                drawDate = "2023-08-10",
                primaryNumbers = listOf(1, 2, 3, 4, 5),
                secondaryNumbers = listOf(1, 2),
            )
        }

    /** 共享工作流测试使用的固定时钟。 */
    private object FixedClock : Clock {
        /** 返回固定结果生成时间。 */
        override fun now(): Instant = FIXED_NOW
    }

    /** 记录预览和单次请求次数，并可控制请求完成时机的测试 Provider。 */
    private class RecordingAiProvider(
        /** 是否等待测试显式释放请求。 */
        private val blockRequest: Boolean = false,
        /** 固定失败等覆盖结果；为空时按当前请求构造合法成功结果。 */
        private val fixedResult: AiProviderResult? = null,
        /** 是否在请求时模拟上层取消。 */
        private val throwsCancellation: Boolean = false,
    ) : AiAnalysisProvider {
        /** 已执行的本地预览次数。 */
        var previewCount: Int = 0
            private set

        /** 已发起的模型请求次数。 */
        var requestCount: Int = 0
            private set

        /** 请求进入 Provider 后完成的同步信号。 */
        val requestStarted = CompletableDeferred<Unit>()

        /** 测试允许阻塞请求返回的同步信号。 */
        private val requestCompletion = CompletableDeferred<Unit>()

        /** 构造与当前请求绑定的无密钥预览。 */
        override fun preview(
            configuration: AiProviderConfiguration,
            request: AiAnalysisRequest,
        ): AiProviderPreviewResult {
            previewCount += 1
            return AiProviderPreviewResult.Success(
                AiProviderRequestPreview(
                    providerName = configuration.displayName,
                    endpointHost = configuration.endpointHost,
                    model = configuration.model,
                    snapshotId = request.snapshot.id,
                    sampleCount = request.snapshot.sampleCount,
                    firstIssue = request.snapshot.firstIssue.value,
                    lastIssue = request.snapshot.lastIssue.value,
                    templateName = request.template.displayName,
                    candidateCount = request.candidateCount,
                    sentFields = listOf("彩种", "期号", "开奖日期", "主号码", "次号码", "分析模板", "候选数量"),
                    requestBodyByteCount = request.snapshot.byteCount + 1024,
                    maxOutputTokens = AiAnalysisProtocol.MAX_OUTPUT_TOKENS,
                    requestFingerprint = "fingerprint-${configuration.model}-${request.snapshot.id}",
                ),
            )
        }

        /** 记录一次请求并返回固定失败或合法的领域成功结果。 */
        override suspend fun analyze(
            configuration: AiProviderConfiguration,
            request: AiAnalysisRequest,
        ): AiProviderResult {
            requestCount += 1
            requestStarted.complete(Unit)
            if (throwsCancellation) throw CancellationException("测试取消")
            if (blockRequest) requestCompletion.await()
            return fixedResult ?: AiProviderResult.Success(validAnalysis(request))
        }

        /** 允许当前阻塞请求返回。 */
        fun completeRequest() {
            requestCompletion.complete(Unit)
        }

        /** 构造与请求彩种、快照和候选数量一致的合法结果。 */
        private fun validAnalysis(request: AiAnalysisRequest): AiAnalysisResult =
            AiAnalysisResult(
                schemaVersion = AiAnalysisProtocol.OUTPUT_SCHEMA_VERSION,
                lotteryType = request.snapshot.lotteryType,
                snapshotId = request.snapshot.id,
                summary = "只描述当前历史样本内的可观察分布",
                candidates =
                    List(request.candidateCount) { index ->
                        AiAnalysisCandidate(
                            line =
                                GeneratedNumberLine(
                                    primaryNumbers = listOf(1 + index, 8 + index, 15 + index, 22 + index, 29 + index),
                                    secondaryNumbers = listOf(1 + index, 7 + index),
                                ),
                            reason = "候选 ${index + 1} 的历史样本说明",
                        )
                    },
            )
    }
}

/** 测试使用且不会进入生产代码的会话密钥。 */
private const val TEST_SESSION_SECRET = "test-workflow-session-secret"

/** 测试结果固定生成时间。 */
private val FIXED_NOW = Instant.parse("2026-08-27T12:00:00Z")

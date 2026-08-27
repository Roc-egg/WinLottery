package roc.win.lottery.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import roc.win.lottery.Platform
import roc.win.lottery.data.AiAnalysisProvider
import roc.win.lottery.data.AiProviderConfiguration
import roc.win.lottery.data.AiProviderPreviewResult
import roc.win.lottery.data.AiProviderRequestPreview
import roc.win.lottery.data.AiProviderResult
import roc.win.lottery.data.FakeDrawRepository
import roc.win.lottery.data.HistoricalDrawQueryResult
import roc.win.lottery.data.HistoricalDrawRepository
import roc.win.lottery.domain.AiAnalysisCandidate
import roc.win.lottery.domain.AiAnalysisProtocol
import roc.win.lottery.domain.AiAnalysisRequest
import roc.win.lottery.domain.AiAnalysisResult
import roc.win.lottery.domain.GeneratedNumberLine
import roc.win.lottery.domain.HistoricalDraw
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryPrizeCalculator
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.TicketValidator
import roc.win.lottery.recognition.ConservativeTicketParser
import roc.win.lottery.recognition.FakeAppPaths
import roc.win.lottery.recognition.FakeImageAcquirer
import roc.win.lottery.recognition.FakeTicketRecognizer
import roc.win.lottery.recognition.ImageDimensionQualityAnalyzer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AI 一级页面、走势缓存和单次请求的控制器集成测试。 */
class AiAnalysisControllerIntegrationTest {
    /** AI 必须复用走势取得的 500 期缓存，并在当前进程保留合法结果。 */
    @Test
    fun reusesTrendHistoryAndKeepsValidatedResultInSession() =
        runTest {
            val historyRepository = CountingHistoryRepository()
            val aiProvider = RecordingAiProvider()
            val controller = createController(historyRepository, aiProvider)

            controller.showTrendChart()
            controller.showAiAnalysis()

            var screen = assertIs<AppScreen.AiAnalysis>(controller.uiState.value.screen)
            assertIs<AiHistoryAvailability.Ready>(screen.workspace.history)
            assertEquals(1, historyRepository.queryCount(LotteryType.SUPER_LOTTO))
            controller.updateAiAnalysisSettings(
                screen.workspace.settings.copy(
                    providerName = "测试服务",
                    endpointUrl = "https://api.example.test/v1/responses",
                    model = "test-model-v1",
                ),
            )
            assertTrue(controller.prepareAiAnalysisPreview(TEST_CONTROLLER_AI_SECRET))
            screen = assertIs(controller.uiState.value.screen)
            val fingerprint =
                assertIs<AiAnalysisOperation.AwaitingConfirmation>(screen.workspace.operation)
                    .preview
                    .requestFingerprint

            assertTrue(controller.confirmAiAnalysisPreview(fingerprint))

            screen = assertIs(controller.uiState.value.screen)
            assertEquals(1, aiProvider.requestCount)
            assertEquals(
                2,
                screen.workspace.lastResult
                    ?.analysis
                    ?.candidates
                    ?.size,
            )
            assertFalse(screen.workspace.toString().contains(TEST_CONTROLLER_AI_SECRET))
            controller.navigateHome()
            controller.showAiAnalysis()

            screen = assertIs(controller.uiState.value.screen)
            assertEquals(
                2,
                screen.workspace.lastResult
                    ?.analysis
                    ?.candidates
                    ?.size,
            )
            assertEquals(1, historyRepository.queryCount(LotteryType.SUPER_LOTTO))
        }

    /** 离开 AI 页面后 Provider 的迟到成功结果不得写回首页或后续会话。 */
    @Test
    fun discardsLateAiResultAfterLeavingWorkspace() =
        runTest {
            val historyRepository = CountingHistoryRepository()
            val aiProvider = RecordingAiProvider(blockRequest = true)
            val controller = createController(historyRepository, aiProvider)
            controller.showAiAnalysis()
            var screen = assertIs<AppScreen.AiAnalysis>(controller.uiState.value.screen)
            controller.updateAiAnalysisSettings(
                screen.workspace.settings.copy(
                    providerName = "测试服务",
                    endpointUrl = "https://api.example.test/v1/responses",
                    model = "test-model-v1",
                ),
            )
            assertTrue(controller.prepareAiAnalysisPreview(TEST_CONTROLLER_AI_SECRET))
            screen = assertIs(controller.uiState.value.screen)
            val fingerprint =
                assertIs<AiAnalysisOperation.AwaitingConfirmation>(screen.workspace.operation)
                    .preview
                    .requestFingerprint
            val request = launch { controller.confirmAiAnalysisPreview(fingerprint) }
            aiProvider.requestStarted.await()

            controller.cancelAiAnalysisRequest()
            controller.navigateHome()
            aiProvider.completeRequest()
            request.join()

            assertEquals(AppScreen.Home, controller.uiState.value.screen)
            controller.showAiAnalysis()
            screen = assertIs(controller.uiState.value.screen)
            assertNull(screen.workspace.lastResult)
            assertEquals(1, aiProvider.requestCount)
            assertEquals(1, historyRepository.queryCount(LotteryType.SUPER_LOTTO))
        }

    /** 创建同时接入官方历史仓库和测试 AI Provider 的共享控制器。 */
    private fun createController(
        historyRepository: HistoricalDrawRepository,
        aiProvider: AiAnalysisProvider,
    ): LotteryAppController =
        LotteryAppController(
            AppContainer(
                platform = TestPlatform,
                imageAcquirer = FakeImageAcquirer(supportsCamera = false),
                imageQualityAnalyzer = ImageDimensionQualityAnalyzer(),
                ticketRecognizer = FakeTicketRecognizer(),
                ticketParser = ConservativeTicketParser(),
                drawRepository = FakeDrawRepository(),
                historicalDrawRepository = historyRepository,
                aiAnalysisProvider = aiProvider,
                prizeCalculator = LotteryPrizeCalculator(),
                appPaths = FakeAppPaths(),
                ticketValidator = TicketValidator(),
                isDemo = true,
                usesRealImageAcquisition = false,
                usesRealRecognition = false,
                usesRealDrawData = false,
            ),
        )

    /** 控制器集成测试使用的最小平台能力。 */
    private object TestPlatform : Platform {
        /** 测试平台名称。 */
        override val name: String = "测试平台"

        /** 测试不使用相机。 */
        override val supportsCamera: Boolean = false
    }

    /** 按彩种返回完整 500 期数据并记录实际查询次数。 */
    private class CountingHistoryRepository : HistoricalDrawRepository {
        /** 当前测试中每个彩种的查询次数。 */
        private val queryCounts = mutableMapOf<LotteryType, Int>()

        /** 返回指定彩种的完整规范化历史开奖。 */
        override suspend fun getLatestDraws(
            lotteryType: LotteryType,
            count: Int,
        ): HistoricalDrawQueryResult {
            queryCounts[lotteryType] = queryCount(lotteryType) + 1
            return HistoricalDrawQueryResult.Success(
                draws = historicalDraws(lotteryType).takeLast(count),
                sourceName = "测试官网",
                fetchedAtEpochMillis = 1_787_689_800_000L,
            )
        }

        /** 返回指定彩种已经触发的查询次数。 */
        fun queryCount(lotteryType: LotteryType): Int = queryCounts[lotteryType] ?: 0

        /** 创建按期号正序排列的完整测试历史开奖。 */
        private fun historicalDraws(lotteryType: LotteryType): List<HistoricalDraw> =
            (1..HistoricalDrawRepository.MAXIMUM_DRAW_COUNT).map { ordinal ->
                when (lotteryType) {
                    LotteryType.SUPER_LOTTO -> {
                        HistoricalDraw(
                            lotteryType = lotteryType,
                            issue = Issue("26${ordinal.toString().padStart(3, '0')}"),
                            drawDate = "2026-01-01",
                            primaryNumbers = listOf(1, 2, 3, 4, 5),
                            secondaryNumbers = listOf(1, 2),
                        )
                    }

                    LotteryType.DOUBLE_COLOR_BALL -> {
                        HistoricalDraw(
                            lotteryType = lotteryType,
                            issue = Issue("2026${ordinal.toString().padStart(3, '0')}"),
                            drawDate = "2026-01-01",
                            primaryNumbers = listOf(1, 2, 3, 4, 5, 6),
                            secondaryNumbers = listOf(1),
                        )
                    }
                }
            }
    }

    /** 记录单次调用并可由测试控制完成时机的 AI Provider。 */
    private class RecordingAiProvider(
        /** 是否等待测试显式释放当前请求。 */
        private val blockRequest: Boolean = false,
    ) : AiAnalysisProvider {
        /** 实际发起的模型请求次数。 */
        var requestCount: Int = 0
            private set

        /** 请求已经进入 Provider 的同步信号。 */
        val requestStarted = CompletableDeferred<Unit>()

        /** 允许阻塞请求返回的同步信号。 */
        private val requestCompletion = CompletableDeferred<Unit>()

        /** 构造与当前请求绑定的无密钥发送预览。 */
        override fun preview(
            configuration: AiProviderConfiguration,
            request: AiAnalysisRequest,
        ): AiProviderPreviewResult =
            AiProviderPreviewResult.Success(
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
                    requestBodyByteCount = request.snapshot.byteCount + 1_024,
                    maxOutputTokens = AiAnalysisProtocol.MAX_OUTPUT_TOKENS,
                    requestFingerprint = "controller-${configuration.model}-${request.snapshot.id}",
                ),
            )

        /** 记录请求并返回与领域请求严格匹配的合法结果。 */
        override suspend fun analyze(
            configuration: AiProviderConfiguration,
            request: AiAnalysisRequest,
        ): AiProviderResult {
            requestCount += 1
            requestStarted.complete(Unit)
            if (blockRequest) requestCompletion.await()
            return AiProviderResult.Success(validAnalysis(request))
        }

        /** 允许当前阻塞请求继续返回。 */
        fun completeRequest() {
            requestCompletion.complete(Unit)
        }

        /** 构造当前请求候选数量的合法大乐透结果。 */
        private fun validAnalysis(request: AiAnalysisRequest): AiAnalysisResult =
            AiAnalysisResult(
                schemaVersion = AiAnalysisProtocol.OUTPUT_SCHEMA_VERSION,
                lotteryType = request.snapshot.lotteryType,
                snapshotId = request.snapshot.id,
                summary = "当前历史样本内的可观察分布摘要",
                candidates =
                    List(request.candidateCount) { index ->
                        AiAnalysisCandidate(
                            line =
                                GeneratedNumberLine(
                                    primaryNumbers = listOf(1 + index, 8 + index, 15 + index, 22 + index, 29 + index),
                                    secondaryNumbers = listOf(1 + index, 7 + index),
                                ),
                            reason = "第 ${index + 1} 注的历史样本说明",
                        )
                    },
            )
    }
}

/** 控制器集成测试使用的固定会话密钥。 */
private const val TEST_CONTROLLER_AI_SECRET = "test-controller-session-secret"

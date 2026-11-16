package roc.win.lottery.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import roc.win.lottery.Platform
import roc.win.lottery.data.AiAnalysisProvider
import roc.win.lottery.data.AiApiFormat
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
import roc.win.lottery.persistence.AiAnalysisRecord
import roc.win.lottery.persistence.AiAnalysisRecordStore
import roc.win.lottery.persistence.AiConnectionStore
import roc.win.lottery.persistence.HistoricalDrawStore
import roc.win.lottery.persistence.SavedAiConnection
import roc.win.lottery.recognition.ConservativeTicketParser
import roc.win.lottery.recognition.FakeAppPaths
import roc.win.lottery.recognition.FakeImageAcquirer
import roc.win.lottery.recognition.FakeTicketRecognizer
import roc.win.lottery.recognition.ImageDimensionQualityAnalyzer
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

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
                    baseUrl = "https://api.example.test/v1",
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

    /** 连接配置和密钥保存在本机：重启后恢复，修改后写回，切换页面不丢失，界面状态不含密钥。 */
    @Test
    fun restoresAndPersistsSavedConnection() =
        runTest {
            val store =
                InMemoryAiConnectionStore(
                    savesApiKey = true,
                    saved =
                        SavedAiConnection(
                            providerName = "测试服务",
                            baseUrl = "https://api.example.test/v1",
                            model = "test-model-v1",
                            apiKey = TEST_CONTROLLER_AI_SECRET,
                        ),
                )
            val controller =
                createController(CountingHistoryRepository(), RecordingAiProvider(), aiConnectionStore = store)

            controller.showAiAnalysis()

            var screen = assertIs<AppScreen.AiAnalysis>(controller.uiState.value.screen)
            assertEquals("测试服务", screen.workspace.settings.providerName)
            assertEquals("test-model-v1", screen.workspace.settings.model)
            // 早期保存的配置没有接口格式，按当时唯一支持的 OpenAI Responses 恢复。
            assertEquals(AiApiFormat.OPENAI_RESPONSES, screen.workspace.settings.apiFormat)
            assertEquals(TEST_CONTROLLER_AI_SECRET, controller.aiApiKey.value)
            assertTrue(controller.savesAiApiKeyLocally)
            assertFalse(
                controller.uiState.value
                    .toString()
                    .contains(TEST_CONTROLLER_AI_SECRET),
            )

            // 早期保存的配置没有输出上限，使用默认值。
            assertEquals(AiAnalysisProtocol.DEFAULT_OUTPUT_TOKEN_LIMIT, screen.workspace.settings.maxOutputTokens)
            controller.updateAiAnalysisSettings(
                screen.workspace.settings.copy(
                    model = "test-model-v2",
                    apiFormat = AiApiFormat.ANTHROPIC_MESSAGES,
                    maxOutputTokens = 32768,
                ),
            )
            controller.updateAiApiKey("new-secret")
            controller.saveAiConnection()
            assertEquals("test-model-v2", store.saved?.model)
            assertEquals(AiApiFormat.ANTHROPIC_MESSAGES.name, store.saved?.apiFormat)
            assertEquals(32768, store.saved?.maxOutputTokens)
            assertEquals("new-secret", store.saved?.apiKey)

            controller.navigateHome()
            controller.showAiAnalysis()
            assertEquals("new-secret", controller.aiApiKey.value)

            val restarted =
                createController(CountingHistoryRepository(), RecordingAiProvider(), aiConnectionStore = store)
            restarted.showAiAnalysis()
            screen = assertIs(restarted.uiState.value.screen)
            assertEquals("test-model-v2", screen.workspace.settings.model)
            assertEquals(AiApiFormat.ANTHROPIC_MESSAGES, screen.workspace.settings.apiFormat)
            assertEquals(32768, screen.workspace.settings.maxOutputTokens)
            assertEquals("new-secret", restarted.aiApiKey.value)
            assertTrue(restarted.prepareAiAnalysisPreview(restarted.aiApiKey.value))
        }

    /** 不能加密保存密钥的平台只保存服务商、接口格式、Base URL 和模型，密钥只在本次运行有效。 */
    @Test
    fun platformWithoutSecureStorageKeepsKeyInMemoryOnly() =
        runTest {
            val store = InMemoryAiConnectionStore(savesApiKey = false)
            val controller =
                createController(CountingHistoryRepository(), RecordingAiProvider(), aiConnectionStore = store)
            controller.showAiAnalysis()
            val screen = assertIs<AppScreen.AiAnalysis>(controller.uiState.value.screen)

            controller.updateAiAnalysisSettings(screen.workspace.settings.copy(providerName = "测试服务"))
            controller.updateAiApiKey(TEST_CONTROLLER_AI_SECRET)
            controller.saveAiConnection()

            assertFalse(controller.savesAiApiKeyLocally)
            assertEquals("测试服务", store.saved?.providerName)
            assertEquals("", store.saved?.apiKey)
            controller.navigateHome()
            controller.showAiAnalysis()
            assertEquals(TEST_CONTROLLER_AI_SECRET, controller.aiApiKey.value)
        }

    /** 清除本机配置会删除保存内容，并清空已填写的连接字段和密钥。 */
    @Test
    fun clearingSavedConnectionEmptiesFieldsAndStore() =
        runTest {
            val store =
                InMemoryAiConnectionStore(
                    savesApiKey = true,
                    saved = SavedAiConnection(providerName = "测试服务", model = "test-model-v1", apiKey = "secret"),
                )
            val controller =
                createController(CountingHistoryRepository(), RecordingAiProvider(), aiConnectionStore = store)
            controller.showAiAnalysis()

            controller.clearSavedAiConnection()

            val screen = assertIs<AppScreen.AiAnalysis>(controller.uiState.value.screen)
            assertEquals("", screen.workspace.settings.providerName)
            assertEquals("", screen.workspace.settings.model)
            assertEquals("", controller.aiApiKey.value)
            assertEquals(null, store.saved)
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
                    baseUrl = "https://api.example.test/v1",
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

    /** 先进入 AI 页再进入走势页时必须复用同一份历史开奖，只发起一次请求。 */
    @Test
    fun aiThenTrendReusesHistoryWithoutRefetch() =
        runTest {
            val historyRepository = CountingHistoryRepository()
            val controller = createController(historyRepository, RecordingAiProvider())

            controller.showAiAnalysis()
            controller.showTrendChart()

            val chart = assertIs<AppScreen.Trends>(controller.uiState.value.screen).chart
            assertIs<TrendChartContent.Ready>(chart.content)
            assertEquals(1, historyRepository.queryCount(LotteryType.SUPER_LOTTO))
        }

    /** 后台核对取得新开奖时不得作废用户已经确认、正在进行的 AI 请求，只提示稍后刷新。 */
    @Test
    fun backgroundRefreshDoesNotInvalidateRequestingAi() =
        runTest {
            val clock = MutableTestClock()
            val history = ScriptedHistoryRepository(clock, newestOrdinal = 503)
            val gate = CompletableDeferred<Unit>()
            history.gate = gate
            val store =
                InMemoryHistoricalDrawStore(
                    listOf(
                        testStoredHistory(
                            LotteryType.SUPER_LOTTO,
                            newestOrdinal = 500,
                            fetchedAtEpochMillis = clock.nowEpochMillis - 31L * 60L * 1_000L,
                        ),
                    ),
                )
            val aiProvider = RecordingAiProvider(blockRequest = true)
            val controller = createController(history, aiProvider, store, clock)

            val entering = launch { controller.showAiAnalysis() }
            history.started.await()
            var screen = assertIs<AppScreen.AiAnalysis>(controller.uiState.value.screen)
            assertIs<AiHistoryAvailability.Ready>(screen.workspace.history)
            assertEquals(HistoricalDrawRefreshStatus.Refreshing, screen.workspace.historyRefresh)
            controller.updateAiAnalysisSettings(
                screen.workspace.settings.copy(
                    providerName = "测试服务",
                    baseUrl = "https://api.example.test/v1",
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

            gate.complete(Unit)
            entering.join()
            screen = assertIs(controller.uiState.value.screen)
            assertIs<AiAnalysisOperation.Requesting>(screen.workspace.operation)
            assertIs<HistoricalDrawRefreshStatus.Notice>(screen.workspace.historyRefresh)
            aiProvider.completeRequest()
            request.join()

            screen = assertIs(controller.uiState.value.screen)
            assertEquals(
                2,
                screen.workspace.lastResult
                    ?.analysis
                    ?.candidates
                    ?.size,
            )
            assertEquals(listOf(LotteryType.SUPER_LOTTO to 100), history.requests)
        }

    /** 新结果写入本机；重启后进入 AI 页直接显示保存的结果和查询时间，不重新请求。 */
    @Test
    fun persistsResultAndRestoresAfterRestart() =
        runTest {
            val store = InMemoryAiAnalysisRecordStore()
            val controller =
                createController(CountingHistoryRepository(), RecordingAiProvider(), aiAnalysisRecordStore = store)
            assertTrue(controller.savesAiResultsLocally)

            controller.showAiAnalysis()
            val record = controller.analyzeOnce()
            assertEquals(record, store.records[LotteryType.SUPER_LOTTO])
            assertEquals("测试服务", record.providerName)
            assertEquals("test-model-v1", record.model)

            val restartedProvider = RecordingAiProvider()
            val restarted =
                createController(CountingHistoryRepository(), restartedProvider, aiAnalysisRecordStore = store)
            restarted.showAiAnalysis()

            val screen = assertIs<AppScreen.AiAnalysis>(restarted.uiState.value.screen)
            assertEquals(record, screen.workspace.lastResult)
            assertEquals(0, restartedProvider.requestCount)
        }

    /** 每个彩种显示自己的结果；删除只影响当前彩种，本机内容同步删除。 */
    @Test
    fun eachLotteryShowsItsOwnResultAndDeleteRemovesIt() =
        runTest {
            val superLotto = testAiRecord(LotteryType.SUPER_LOTTO)
            val doubleColorBall = testAiRecord(LotteryType.DOUBLE_COLOR_BALL)
            val store = InMemoryAiAnalysisRecordStore(superLotto, doubleColorBall)
            val controller =
                createController(CountingHistoryRepository(), RecordingAiProvider(), aiAnalysisRecordStore = store)

            controller.showAiAnalysis()
            assertEquals(superLotto, controller.aiWorkspace().lastResult)
            controller.updateAiAnalysisSettings(
                controller.aiWorkspace().settings.copy(lotteryType = LotteryType.DOUBLE_COLOR_BALL),
            )
            assertEquals(doubleColorBall, controller.aiWorkspace().lastResult)
            controller.updateAiAnalysisSettings(
                controller.aiWorkspace().settings.copy(lotteryType = LotteryType.SUPER_LOTTO),
            )
            assertEquals(superLotto, controller.aiWorkspace().lastResult)

            controller.deleteAiAnalysisRecord()
            assertNull(controller.aiWorkspace().lastResult)
            assertEquals(mapOf(LotteryType.DOUBLE_COLOR_BALL to doubleColorBall), store.records)
            controller.navigateHome()
            controller.showAiAnalysis()
            assertNull(controller.aiWorkspace().lastResult)
            controller.updateAiAnalysisSettings(
                controller.aiWorkspace().settings.copy(lotteryType = LotteryType.DOUBLE_COLOR_BALL),
            )
            assertEquals(doubleColorBall, controller.aiWorkspace().lastResult)
        }

    /** 本机读取失败时视为没有保存；写入失败时本次运行仍显示新结果。 */
    @Test
    fun recordStoreFailuresDoNotBlockAnalysis() =
        runTest {
            val controller =
                createController(
                    CountingHistoryRepository(),
                    RecordingAiProvider(),
                    aiAnalysisRecordStore = FailingAiAnalysisRecordStore,
                )

            controller.showAiAnalysis()
            assertNull(controller.aiWorkspace().lastResult)
            val record = controller.analyzeOnce()
            assertEquals(record, controller.aiWorkspace().lastResult)
            controller.deleteAiAnalysisRecord()
            assertNull(controller.aiWorkspace().lastResult)
        }

    /** 当前 AI 页状态。 */
    private fun LotteryAppController.aiWorkspace(): AiAnalysisWorkspaceState =
        assertIs<AppScreen.AiAnalysis>(uiState.value.screen).workspace

    /** 填写连接配置后预览并确认一次请求，返回得到的结果。 */
    private suspend fun LotteryAppController.analyzeOnce(): AiAnalysisRecord {
        updateAiAnalysisSettings(
            aiWorkspace().settings.copy(
                providerName = "测试服务",
                baseUrl = "https://api.example.test/v1",
                model = "test-model-v1",
            ),
        )
        assertTrue(prepareAiAnalysisPreview(TEST_CONTROLLER_AI_SECRET))
        val fingerprint =
            assertIs<AiAnalysisOperation.AwaitingConfirmation>(aiWorkspace().operation)
                .preview
                .requestFingerprint
        assertTrue(confirmAiAnalysisPreview(fingerprint))
        return assertNotNull(aiWorkspace().lastResult)
    }

    /** 构造指定彩种的一条合法结果。 */
    private fun testAiRecord(lotteryType: LotteryType): AiAnalysisRecord =
        AiAnalysisRecord(
            providerName = "已保存服务",
            model = "saved-model",
            templateName = "综合历史分析",
            sourceName = "测试官方来源",
            sampleCount = 50,
            firstIssue = if (lotteryType == LotteryType.SUPER_LOTTO) "26101" else "2026101",
            lastIssue = if (lotteryType == LotteryType.SUPER_LOTTO) "26150" else "2026150",
            generatedAtEpochMillis = 1_789_000_000_000L,
            analysis =
                AiAnalysisResult(
                    schemaVersion = AiAnalysisProtocol.OUTPUT_SCHEMA_VERSION,
                    lotteryType = lotteryType,
                    snapshotId = "b".repeat(64),
                    summary = "已保存的分析摘要",
                    candidates =
                        listOf(
                            AiAnalysisCandidate(
                                line =
                                    if (lotteryType == LotteryType.SUPER_LOTTO) {
                                        GeneratedNumberLine(listOf(3, 11, 19, 25, 33), listOf(4, 10))
                                    } else {
                                        GeneratedNumberLine(listOf(2, 9, 15, 21, 27, 33), listOf(12))
                                    },
                                reason = "已保存的候选说明",
                            ),
                        ),
                ),
        )

    /** 创建同时接入官方历史仓库和测试 AI Provider 的共享控制器。 */
    private fun createController(
        historyRepository: HistoricalDrawRepository,
        aiProvider: AiAnalysisProvider,
        historicalDrawStore: HistoricalDrawStore? = null,
        clock: Clock = MutableTestClock(),
        aiConnectionStore: AiConnectionStore? = null,
        aiAnalysisRecordStore: AiAnalysisRecordStore? = null,
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
                historicalDrawStore = historicalDrawStore,
                aiAnalysisProvider = aiProvider,
                aiConnectionStore = aiConnectionStore,
                aiAnalysisRecordStore = aiAnalysisRecordStore,
                prizeCalculator = LotteryPrizeCalculator(),
                appPaths = FakeAppPaths(),
                ticketValidator = TicketValidator(),
                isDemo = true,
                usesRealImageAcquisition = false,
                usesRealRecognition = false,
                usesRealDrawData = false,
            ),
            computationContext = EmptyCoroutineContext,
            clock = clock,
        )

    /** 控制器集成测试使用的最小平台能力。 */
    private object TestPlatform : Platform {
        /** 测试平台名称。 */
        override val name: String = "测试平台"

        /** 测试不使用相机。 */
        override val supportsCamera: Boolean = false
    }

    /** 内存中的 AI 连接配置仓库，模拟各平台本机保存。 */
    private class InMemoryAiConnectionStore(
        override val savesApiKey: Boolean,
        /** 当前保存的配置。 */
        var saved: SavedAiConnection? = null,
    ) : AiConnectionStore {
        override suspend fun load(): SavedAiConnection? = saved

        override suspend fun save(connection: SavedAiConnection) {
            saved = connection.takeUnless { it.isEmpty }
        }

        override suspend fun clear() {
            saved = null
        }
    }

    /** 内存中的 AI 分析结果仓库，每个彩种保存一条。 */
    private class InMemoryAiAnalysisRecordStore(
        vararg initial: AiAnalysisRecord,
    ) : AiAnalysisRecordStore {
        /** 当前保存的结果。 */
        val records = initial.associateBy { it.lotteryType }.toMutableMap()

        override suspend fun load(lotteryType: LotteryType): AiAnalysisRecord? = records[lotteryType]

        override suspend fun save(record: AiAnalysisRecord) {
            records[record.lotteryType] = record
        }

        override suspend fun delete(lotteryType: LotteryType): Boolean = records.remove(lotteryType) != null
    }

    /** 读写都失败的 AI 分析结果仓库。 */
    private object FailingAiAnalysisRecordStore : AiAnalysisRecordStore {
        override suspend fun load(lotteryType: LotteryType): AiAnalysisRecord? = error("读取失败")

        override suspend fun save(record: AiAnalysisRecord): Unit = error("写入失败")

        override suspend fun delete(lotteryType: LotteryType): Boolean = error("删除失败")
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

                    LotteryType.HAPPY_8 -> {
                        HistoricalDraw(
                            lotteryType = lotteryType,
                            issue = Issue("2026${ordinal.toString().padStart(3, '0')}"),
                            drawDate = "2026-01-01",
                            primaryNumbers = (1..20).toList(),
                            secondaryNumbers = emptyList(),
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
                    apiFormat = configuration.apiFormat,
                    endpointHost = configuration.endpointHost,
                    usesHttps = configuration.usesHttps,
                    model = configuration.model,
                    snapshotId = request.snapshot.id,
                    sampleCount = request.snapshot.sampleCount,
                    firstIssue = request.snapshot.firstIssue.value,
                    lastIssue = request.snapshot.lastIssue.value,
                    templateName = request.template.displayName,
                    candidateCount = request.candidateCount,
                    sentFields = listOf("彩种", "期号", "开奖日期", "主号码", "次号码", "分析模板", "候选数量"),
                    requestBodyByteCount = request.snapshot.byteCount + 1_024,
                    maxOutputTokens = AiAnalysisProtocol.DEFAULT_OUTPUT_TOKEN_LIMIT,
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

/** 控制器集成测试使用的固定密钥。 */
private const val TEST_CONTROLLER_AI_SECRET = "test-controller-session-secret"

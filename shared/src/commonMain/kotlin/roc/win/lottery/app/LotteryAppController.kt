package roc.win.lottery.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import roc.win.lottery.data.DrawQueryResult
import roc.win.lottery.domain.ConfirmedTicket
import roc.win.lottery.domain.DrawResult
import roc.win.lottery.domain.DrawStatus
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.IssueSequenceResolver
import roc.win.lottery.domain.IssueSequenceResult
import roc.win.lottery.domain.LotteryPredictionEngine
import roc.win.lottery.domain.LotteryPredictionResult
import roc.win.lottery.domain.LotteryRandomNumberGenerator
import roc.win.lottery.domain.LotteryTrendCalculationResult
import roc.win.lottery.domain.LotteryTrendCalculator
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.RandomNumberGenerationResult
import roc.win.lottery.persistence.TICKET_RECORD_EXPORT_EXTENSION
import roc.win.lottery.persistence.TicketAcquisitionSource
import roc.win.lottery.persistence.TicketRecordImportConflictPolicy
import roc.win.lottery.persistence.TicketRecordImportException
import roc.win.lottery.persistence.TicketRecordImportResult
import roc.win.lottery.recognition.ImageAcquisitionResult
import roc.win.lottery.recognition.ImageAcquisitionSource
import roc.win.lottery.recognition.ImageQualityResult
import roc.win.lottery.recognition.ImageRef
import roc.win.lottery.recognition.ProgressiveTicketRecognizer
import roc.win.lottery.recognition.RecognitionResult
import roc.win.lottery.recognition.TicketFieldCandidate
import roc.win.lottery.recognition.TicketFieldRegion
import roc.win.lottery.recognition.TicketParseResult
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Instant

/** 驱动本地图片识别、票面人工校正和开奖查询流程的应用状态持有者。 */
class LotteryAppController(
    /** 应用依赖容器。 */
    private val container: AppContainer,
    /** 图片质量检查、本地识别、票面解析和走势研究使用的计算上下文，避免阻塞界面线程。 */
    private val computationContext: CoroutineContext = Dispatchers.Default,
    /** 判断本机历史开奖是否需要与官网核对的时钟。 */
    private val clock: Clock = Clock.System,
) {
    /** 内部可变 UI 状态。 */
    private val mutableUiState = MutableStateFlow(AppUiState(isDemo = container.isDemo))

    /** 只读 UI 状态。 */
    val uiState: StateFlow<AppUiState> = mutableUiState.asStateFlow()

    /** 当前流程是否已被用户取消，用于丢弃迟到结果。 */
    private var flowGeneration: Long = 0L

    /** 当前流程中正在执行、可被返回或新流程立即取消的识别或开奖查询任务。 */
    private var activeFlowTask: Job? = null

    /** [activeFlowTask] 在协程上下文中的标记，用于识别从任务内部发起的流程切换。 */
    private var activeFlowTaskMarker: FlowTaskMarker? = null

    /** 最近一次走势重算请求的序号，较早请求迟到时不得覆盖较新的配置。 */
    private var trendCalculationSequence: Long = 0L

    /** 当前流程持有的临时图片，确认或退出后立即清理。 */
    private var activeImageRef: ImageRef? = null

    /** 最近一次提交查询的来源页，可以是已清理图片的确认页或记录列表。 */
    private var lastQuerySourceScreen: AppScreen? = null

    /** 当前查询被用户返回时需要恢复的来源页面。 */
    private var queryReturnScreen: AppScreen? = null

    /** 当前新确认流程对应的票据采集方式。 */
    private var currentAcquisitionSource: TicketAcquisitionSource? = null

    /** 当前新确认流程已经保存的记录 UUID，未保存时为 `null`。 */
    private var savedCurrentFlowRecordId: String? = null

    /** 当前新确认流程最近一次成功写入记录的票据，用于识别返回校正后的变化。 */
    private var savedCurrentFlowTicket: ConfirmedTicket? = null

    /** 正在执行确认的流程代次，用于阻止并发双击重复保存。 */
    private var confirmingGeneration: Long? = null

    /** 正在读取记录并发起查询的流程代次，用于阻止重复点击。 */
    private var recordQueryGeneration: Long? = null

    /** 正在执行删除、清空或文件操作的记录页流程代次。 */
    private var recordManagementGeneration: Long? = null

    /** 已通过导入预检、只在用户确认前保留于内存的原始文件字节。 */
    private var pendingTicketRecordImportContent: ByteArray? = null

    /** 将多期票限制在已验证期号边界并展开为逐期查询。 */
    private val issueSequenceResolver = IssueSequenceResolver()

    /** 使用领域冻结规则生成合法随机号码。 */
    private val randomNumberGenerator = LotteryRandomNumberGenerator()

    /** 只在当前控制器会话中保留的随机选号配置与结果。 */
    private var randomNumberPickerState = RandomNumberPickerState()

    /** 使用领域规则从规范化历史开奖生成走势矩阵。 */
    private val trendCalculator = LotteryTrendCalculator()

    /** 使用冻结策略生成候选并执行时间前推回测。 */
    private val predictionEngine = LotteryPredictionEngine()

    /** 当前控制器会话按彩种保留的最多 500 期规范化历史开奖。 */
    private val historyCache =
        HistoricalDrawCache(
            repository = container.historicalDrawRepository,
            store = container.historicalDrawStore,
            clock = clock,
            computationContext = computationContext,
        )

    /** 查询开奖时已开奖期次的本机结果；演示数据不落库。 */
    private val drawResultCache =
        DrawResultCache(container.drawResultStore.takeIf { container.usesRealDrawData })

    /** 走势页当前展示的历史开奖内容版本；为 `null` 时下一份数据必须重算。 */
    private var trendChartRevision: Long? = null

    /** AI 页当前展示的历史开奖内容版本；为 `null` 时下一份数据必须重新交给工作流。 */
    private var aiHistoryRevision: Long? = null

    /** 当前控制器会话内保留的走势图配置与加载状态。 */
    private var trendChartState = TrendChartState.create()

    /** 当前移动平台启用时使用的 AI 单次确认共享工作流。 */
    private val aiAnalysisWorkflow =
        container.aiAnalysisProvider?.let { provider ->
            AiAnalysisWorkflow(
                provider = provider,
                onStateChanged = ::publishAiWorkspace,
            )
        }

    /**
     * 从拍照或导图开始一次全新的本地分析。
     *
     * @param source 用户选择的图片来源。
     */
    suspend fun startAnalysis(source: ImageAcquisitionSource) {
        clearTemporaryImage()
        lastQuerySourceScreen = null
        queryReturnScreen = null
        val generation = advanceFlowGeneration()
        resetConfirmationPersistence(source.toTicketAcquisitionSource())
        mutableUiState.value =
            AppUiState(
                screen = AppScreen.Analysis("准备图片", "图片只会进入应用私有临时目录", 0.12f),
                isDemo = container.isDemo,
            )

        when (val acquisition = container.imageAcquirer.acquire(source)) {
            ImageAcquisitionResult.Cancelled -> {
                navigateHome()
            }

            is ImageAcquisitionResult.Failure -> {
                showError("无法读取图片", acquisition.message, generation)
            }

            is ImageAcquisitionResult.Unavailable -> {
                showError("当前能力不可用", acquisition.message, generation)
            }

            is ImageAcquisitionResult.Success -> {
                if (generation == flowGeneration) {
                    activeImageRef = acquisition.imageRef
                    runCancellableFlow { inspectImage(acquisition, generation) }
                } else {
                    container.appPaths.deleteTemporaryImage(acquisition.imageRef)
                }
            }
        }
    }

    /** 直接进入不依赖 OCR 的单期单式彩票手动录入页。 */
    suspend fun startManualEntry() {
        if (!container.usesRealDrawData) return
        advanceFlowGeneration()
        clearTemporaryImage()
        lastQuerySourceScreen = null
        queryReturnScreen = null
        resetConfirmationPersistence(TicketAcquisitionSource.MANUAL_ENTRY)
        val editor = TicketReviewState.createManual()
        mutableUiState.value =
            AppUiState(
                screen = createReviewScreen(editor, imageRef = null, fieldRegions = emptyList()),
                isDemo = container.isDemo,
            )
    }

    /** 在 OCR 前执行本地质量闸门，并在拒绝时立即清理临时图片。 */
    private suspend fun inspectImage(
        acquisition: ImageAcquisitionResult.Success,
        generation: Long,
    ) {
        if (generation != flowGeneration) return
        mutableUiState.update {
            it.copy(screen = AppScreen.Analysis("图片检查", "正在检查图片是否清晰可读", 0.32f))
        }

        val quality =
            withContext(computationContext) { container.imageQualityAnalyzer.analyze(acquisition.imageRef) }
        when (quality) {
            is ImageQualityResult.Failure -> {
                showError("无法检查图片", quality.message, generation)
            }

            is ImageQualityResult.PoorImage -> {
                showError("图片质量不足", quality.issues.joinToString(separator = "；"), generation)
            }

            ImageQualityResult.Passed -> {
                recognize(acquisition, generation)
            }
        }
    }

    /** 取消当前流程并清除页面状态。 */
    suspend fun navigateHome() {
        advanceFlowGeneration()
        aiAnalysisWorkflow?.clearTransientRequest()
        clearTemporaryImage()
        lastQuerySourceScreen = null
        queryReturnScreen = null
        resetConfirmationPersistence()
        resetTicketRecordManagement()
        mutableUiState.value = AppUiState(isDemo = container.isDemo)
    }

    /**
     * 处理系统手势、实体返回键或顶部返回箭头。
     *
     * 首页不调用本方法；查询中返回会先使当前异步流程失效，再恢复查询来源页面。
     */
    suspend fun navigateBack() {
        when (val screen = mutableUiState.value.screen) {
            AppScreen.Home -> {
                return
            }

            is AppScreen.NumberPicker,
            is AppScreen.Trends,
            is AppScreen.AiAnalysis,
            is AppScreen.Analysis,
            is AppScreen.Review,
            is AppScreen.Records,
            is AppScreen.About,
            -> {
                navigateHome()
            }

            is AppScreen.DrawQuery -> {
                restorePreviousScreen(queryReturnScreen ?: lastQuerySourceScreen)
            }

            is AppScreen.DemoComplete,
            is AppScreen.VerificationResult,
            is AppScreen.DrawUnavailable,
            is AppScreen.MultiPeriodVerificationResult,
            is AppScreen.Error,
            -> {
                restorePreviousScreen(lastQuerySourceScreen)
            }
        }
    }

    /** 打开随机选号一级页面并结束此前的临时票面流程。 */
    suspend fun showRandomNumberPicker() {
        advanceFlowGeneration()
        aiAnalysisWorkflow?.clearTransientRequest()
        clearTemporaryImage()
        lastQuerySourceScreen = null
        queryReturnScreen = null
        resetConfirmationPersistence()
        resetTicketRecordManagement()
        mutableUiState.update { it.copy(screen = AppScreen.NumberPicker(randomNumberPickerState)) }
    }

    /** 更新选号配置，或使用当前配置生成一套新的会话内号码。 */
    fun updateRandomNumberPicker(action: RandomNumberPickerAction) {
        val screen = mutableUiState.value.screen as? AppScreen.NumberPicker ?: return
        val updatedPicker =
            when (action) {
                RandomNumberPickerAction.Generate -> {
                    when (val result = randomNumberGenerator.generate(screen.picker.toGenerationRequest())) {
                        is RandomNumberGenerationResult.Success -> {
                            screen.picker.copy(plan = result.plan, errorMessage = null)
                        }

                        is RandomNumberGenerationResult.InvalidRequest -> {
                            screen.picker.copy(plan = null, errorMessage = result.message)
                        }
                    }
                }

                else -> {
                    screen.picker.applyConfiguration(action)
                }
            }
        randomNumberPickerState = updatedPicker
        mutableUiState.update { state ->
            if (state.screen is AppScreen.NumberPicker) {
                state.copy(screen = AppScreen.NumberPicker(updatedPicker))
            } else {
                state
            }
        }
    }

    /** 打开走势与数学研究一级页面并结束此前的临时票面流程；本机已有数据时立即展示。 */
    suspend fun showTrendChart() {
        val generation = advanceFlowGeneration()
        aiAnalysisWorkflow?.clearTransientRequest()
        clearTemporaryImage()
        lastQuerySourceScreen = null
        queryReturnScreen = null
        resetConfirmationPersistence()
        resetTicketRecordManagement()
        trendChartState = trendChartState.copy(historyRefresh = HistoricalDrawRefreshStatus.Idle)
        mutableUiState.update { it.copy(screen = AppScreen.Trends(trendChartState)) }
        // 首次加载失败后保持失败页，只在用户明确重试时重新联网。
        if (trendChartState.content is TrendChartContent.Failed) return
        syncTrendHistory(trendChartState.lotteryType, generation, force = false)
    }

    /** 更新走势内部视图、彩种、号码区域、样本范围、失败重试或手动刷新。 */
    suspend fun updateTrendChart(action: TrendChartAction) {
        val screen = mutableUiState.value.screen as? AppScreen.Trends ?: return
        when (action) {
            is TrendChartAction.ChangeView -> {
                if (action.view == screen.chart.view) return
                publishTrendChart(screen.chart.copy(view = action.view))
            }

            is TrendChartAction.ChangeLotteryType -> {
                if (action.lotteryType == screen.chart.lotteryType) return
                val generation = advanceFlowGeneration()
                trendCalculationSequence += 1L
                trendChartRevision = null
                publishTrendChart(
                    screen.chart.copy(
                        lotteryType = action.lotteryType,
                        content = TrendChartContent.Loading,
                        researchContent = TrendResearchContent.Loading,
                        historyRefresh = HistoricalDrawRefreshStatus.Idle,
                    ),
                )
                syncTrendHistory(action.lotteryType, generation, force = false)
            }

            is TrendChartAction.ChangeArea -> {
                if (action.area == screen.chart.area) return
                recalculateTrendChart(screen.chart.copy(area = action.area))
            }

            is TrendChartAction.ChangeSampleSize -> {
                if (action.sampleSize == screen.chart.sampleSize) return
                recalculateTrendChart(screen.chart.copy(sampleSize = action.sampleSize))
            }

            TrendChartAction.Retry -> {
                if (
                    screen.chart.content !is TrendChartContent.Failed &&
                    screen.chart.researchContent !is TrendResearchContent.Failed
                ) {
                    return
                }
                val generation = advanceFlowGeneration()
                historyCache.discard(screen.chart.lotteryType)
                trendCalculationSequence += 1L
                trendChartRevision = null
                publishTrendChart(
                    screen.chart.copy(
                        content = TrendChartContent.Loading,
                        researchContent = TrendResearchContent.Loading,
                        historyRefresh = HistoricalDrawRefreshStatus.Idle,
                    ),
                )
                syncTrendHistory(screen.chart.lotteryType, generation, force = false)
            }

            TrendChartAction.Refresh -> {
                if (
                    screen.chart.content !is TrendChartContent.Ready ||
                    screen.chart.historyRefresh is HistoricalDrawRefreshStatus.Refreshing
                ) {
                    return
                }
                syncTrendHistory(screen.chart.lotteryType, flowGeneration, force = true)
            }
        }
    }

    /** 当前彩种已有历史开奖时在计算上下文中重算，否则只保留新的筛选配置。 */
    private suspend fun recalculateTrendChart(configured: TrendChartState) {
        val cached = historyCache.current(configured.lotteryType)
        if (cached == null) {
            trendCalculationSequence += 1L
            publishTrendChart(configured)
        } else {
            publishCalculatedTrendChart(configured, cached)
        }
    }

    /**
     * 在计算上下文中生成走势与研究结果，并在没有更新的重算请求时发布。
     *
     * 计算期间切换的内部视图和核对进度会保留；被更新请求取代的结果只返回给调用方，不再发布。
     */
    private suspend fun publishCalculatedTrendChart(
        configured: TrendChartState,
        entry: HistoricalDrawEntry,
    ): TrendChartState {
        val sequence = ++trendCalculationSequence
        val calculated = withContext(computationContext) { calculateTrendChart(configured, entry) }
        if (sequence == trendCalculationSequence) {
            publishTrendChart(
                calculated.copy(view = trendChartState.view, historyRefresh = trendChartState.historyRefresh),
            )
        }
        return calculated
    }

    /**
     * 走势页取得历史开奖的统一入口：先展示内存或本机数据，再按需与官网增量核对。
     *
     * 没有本机数据时完整加载；本机数据未过期且不是手动刷新时不联网。核对期间离开页面或切换彩种时，
     * 结果仍写入缓存但不再发布到界面。
     *
     * @param force 手动刷新时即使本机数据未过期也做一次增量核对。
     */
    private suspend fun syncTrendHistory(
        lotteryType: LotteryType,
        generation: Long,
        force: Boolean,
    ) {
        val cached = historyCache.peek(lotteryType)
        if (!isCurrentTrendLoad(lotteryType, generation)) return
        if (cached != null) {
            if (cached.revision != trendChartRevision) showTrendHistory(cached)
            if (!force && !historyCache.isStale(cached)) return
            publishTrendRefresh(HistoricalDrawRefreshStatus.Refreshing)
        }
        when (val result = historyCache.sync(lotteryType, force)) {
            is HistoricalDrawSyncResult.Ready -> {
                if (!isCurrentTrendLoad(lotteryType, generation)) return
                val entry = result.entry
                if (entry.revision != trendChartRevision) {
                    showTrendHistory(entry)
                } else {
                    publishTrendFetchedAt(entry)
                }
                publishTrendRefresh(
                    result.notice?.let(HistoricalDrawRefreshStatus::Notice) ?: HistoricalDrawRefreshStatus.Idle,
                )
            }

            is HistoricalDrawSyncResult.Failed -> {
                if (!isCurrentTrendLoad(lotteryType, generation)) return
                if (trendChartState.content is TrendChartContent.Ready) {
                    publishTrendRefresh(HistoricalDrawRefreshStatus.Failed(result.message))
                } else {
                    publishTrendFailureIfCurrent(lotteryType, generation, result.message)
                }
            }
        }
    }

    /** 用新的历史开奖重算走势和数学研究；集合未通过计算时丢弃，下次进入重新加载。 */
    private suspend fun showTrendHistory(entry: HistoricalDrawEntry) {
        trendChartRevision = entry.revision
        val calculated =
            publishCalculatedTrendChart(
                trendChartState.copy(researchContent = TrendResearchContent.Loading),
                entry,
            )
        if (calculated.content is TrendChartContent.Failed) {
            trendChartRevision = null
            historyCache.discard(entry.lotteryType)
        }
    }

    /** 官网没有新开奖时只更新展示的来源和核对时间。 */
    private fun publishTrendFetchedAt(entry: HistoricalDrawEntry) {
        val content = trendChartState.content as? TrendChartContent.Ready ?: return
        publishTrendChart(
            trendChartState.copy(
                content =
                    content.copy(
                        sourceName = entry.sourceName,
                        fetchedAtEpochMillis = entry.fetchedAtEpochMillis,
                    ),
            ),
        )
    }

    /** 发布走势页与官网核对的进度。 */
    private fun publishTrendRefresh(status: HistoricalDrawRefreshStatus) {
        if (trendChartState.historyRefresh == status) return
        publishTrendChart(trendChartState.copy(historyRefresh = status))
    }

    /** 使用当前配置从历史开奖生成走势图与数学研究结果。 */
    private fun calculateTrendChart(
        configured: TrendChartState,
        entry: HistoricalDrawEntry,
    ): TrendChartState {
        val trendContent =
            when (
                val result =
                    trendCalculator.calculate(
                        lotteryType = configured.lotteryType,
                        area = configured.area,
                        sampleSize = configured.sampleSize,
                        draws = entry.draws,
                    )
            ) {
                is LotteryTrendCalculationResult.Success -> {
                    TrendChartContent.Ready(
                        snapshot = result.snapshot,
                        sourceName = entry.sourceName,
                        fetchedAtEpochMillis = entry.fetchedAtEpochMillis,
                    )
                }

                is LotteryTrendCalculationResult.InvalidData -> {
                    return configured.copy(
                        content =
                            TrendChartContent.Failed(
                                "官方历史开奖未通过完整性校验，请主动重试",
                            ),
                        researchContent =
                            TrendResearchContent.Failed(
                                "官方历史开奖未通过完整性校验，请主动重试",
                            ),
                    )
                }
            }
        val researchContent =
            if (configured.researchContent is TrendResearchContent.Loading) {
                when (val result = predictionEngine.analyze(configured.lotteryType, entry.draws)) {
                    is LotteryPredictionResult.Success -> {
                        TrendResearchContent.Ready(result.analysis)
                    }

                    is LotteryPredictionResult.Unavailable -> {
                        TrendResearchContent.Failed(result.message)
                    }
                }
            } else {
                configured.researchContent
            }
        return configured.copy(content = trendContent, researchContent = researchContent)
    }

    /** 只在当前走势加载仍有效时发布失败状态。 */
    private fun publishTrendFailureIfCurrent(
        lotteryType: LotteryType,
        generation: Long,
        message: String,
    ) {
        if (!isCurrentTrendLoad(lotteryType, generation)) return
        publishTrendChart(
            trendChartState.copy(
                content = TrendChartContent.Failed(message),
                researchContent = TrendResearchContent.Failed(message),
            ),
        )
    }

    /** 判断迟到的历史开奖响应是否仍属于当前走势图。 */
    private fun isCurrentTrendLoad(
        lotteryType: LotteryType,
        generation: Long,
    ): Boolean {
        val screen = mutableUiState.value.screen as? AppScreen.Trends ?: return false
        return generation == flowGeneration &&
            screen.chart.lotteryType == lotteryType &&
            trendChartState.lotteryType == lotteryType
    }

    /** 保存走势图会话状态，并在页面仍可见时同步发布。 */
    private fun publishTrendChart(chart: TrendChartState) {
        trendChartState = chart
        mutableUiState.update { state ->
            if (state.screen is AppScreen.Trends) {
                state.copy(screen = AppScreen.Trends(chart))
            } else {
                state
            }
        }
    }

    /** 打开 AI 历史分析一级页面，并优先复用走势或本机已经取得的 500 期官方历史开奖。 */
    suspend fun showAiAnalysis() {
        val workflow = aiAnalysisWorkflow ?: return
        val generation = advanceFlowGeneration()
        clearTemporaryImage()
        lastQuerySourceScreen = null
        queryReturnScreen = null
        resetConfirmationPersistence()
        resetTicketRecordManagement()
        workflow.clearTransientRequest()
        workflow.showHistoryRefresh(HistoricalDrawRefreshStatus.Idle)
        mutableUiState.update { it.copy(screen = AppScreen.AiAnalysis(workflow.state)) }
        syncAiHistory(workflow.state.settings.lotteryType, generation, force = false)
    }

    /** 更新 AI 非敏感配置；切换彩种时复用本机数据或加载新的完整历史开奖。 */
    suspend fun updateAiAnalysisSettings(settings: AiAnalysisSettings) {
        val workflow = aiAnalysisWorkflow ?: return
        if (mutableUiState.value.screen !is AppScreen.AiAnalysis) return
        val previousLotteryType = workflow.state.settings.lotteryType
        workflow.updateSettings(settings)
        if (workflow.state.settings != settings || settings.lotteryType == previousLotteryType) return
        val generation = advanceFlowGeneration()
        aiHistoryRevision = null
        syncAiHistory(settings.lotteryType, generation, force = false)
    }

    /** 使用界面当前会话密钥构建精确发送预览，不发起网络请求。 */
    fun prepareAiAnalysisPreview(apiKey: String): Boolean {
        val workflow = aiAnalysisWorkflow ?: return false
        if (mutableUiState.value.screen !is AppScreen.AiAnalysis) return false
        return workflow.preparePreview(apiKey)
    }

    /** 关闭尚未消费的 AI 发送预览。 */
    fun dismissAiAnalysisPreview() {
        if (mutableUiState.value.screen !is AppScreen.AiAnalysis) return
        aiAnalysisWorkflow?.dismissPreview()
    }

    /** 消费匹配指纹的单次确认，并把 Provider 结果发布到当前 AI 页面。 */
    suspend fun confirmAiAnalysisPreview(requestFingerprint: String): Boolean {
        val workflow = aiAnalysisWorkflow ?: return false
        if (mutableUiState.value.screen !is AppScreen.AiAnalysis) return false
        return workflow.confirmPreview(requestFingerprint)
    }

    /** 逻辑取消正在执行的 AI 请求，使取消后的迟到响应失效。 */
    fun cancelAiAnalysisRequest() {
        aiAnalysisWorkflow?.cancelActiveRequest()
    }

    /** 用户明确重试当前彩种的官方历史开奖，不自动重试 AI 请求。 */
    suspend fun retryAiAnalysisHistory() {
        val workflow = aiAnalysisWorkflow ?: return
        val screen = mutableUiState.value.screen as? AppScreen.AiAnalysis ?: return
        if (screen.workspace.history !is AiHistoryAvailability.Failed) return
        val lotteryType = workflow.state.settings.lotteryType
        val generation = advanceFlowGeneration()
        historyCache.discard(lotteryType)
        aiHistoryRevision = null
        syncAiHistory(lotteryType, generation, force = false)
    }

    /** 用户明确要求立即与官网增量核对 AI 当前彩种的本机历史开奖。 */
    suspend fun refreshAiAnalysisHistory() {
        val workflow = aiAnalysisWorkflow ?: return
        val screen = mutableUiState.value.screen as? AppScreen.AiAnalysis ?: return
        if (
            screen.workspace.history !is AiHistoryAvailability.Ready ||
            screen.workspace.historyRefresh is HistoricalDrawRefreshStatus.Refreshing ||
            !workflow.canReplaceHistory
        ) {
            return
        }
        syncAiHistory(workflow.state.settings.lotteryType, flowGeneration, force = true)
    }

    /**
     * AI 页取得历史开奖的统一入口，与走势页共用缓存和核对锁。
     *
     * 存在待确认预览或进行中的请求时不替换历史数据，避免作废用户已经确认的请求。
     */
    private suspend fun syncAiHistory(
        lotteryType: LotteryType,
        generation: Long,
        force: Boolean,
    ) {
        val workflow = aiAnalysisWorkflow ?: return
        val cached = historyCache.peek(lotteryType)
        if (!isCurrentAiHistoryLoad(lotteryType, generation)) return
        if (cached != null) {
            val isShowingCached =
                cached.revision == aiHistoryRevision && workflow.state.history is AiHistoryAvailability.Ready
            if (!isShowingCached && workflow.canReplaceHistory) showAiHistory(cached)
            if (!force && !historyCache.isStale(cached)) return
            workflow.showHistoryRefresh(HistoricalDrawRefreshStatus.Refreshing)
        } else {
            aiHistoryRevision = null
            workflow.showHistoryLoading()
        }
        when (val result = historyCache.sync(lotteryType, force)) {
            is HistoricalDrawSyncResult.Ready -> {
                if (!isCurrentAiHistoryLoad(lotteryType, generation)) return
                val entry = result.entry
                when {
                    entry.revision == aiHistoryRevision -> {
                        workflow.updateHistoryFetchedAt(entry.sourceName, entry.fetchedAtEpochMillis)
                    }

                    workflow.canReplaceHistory -> {
                        showAiHistory(entry)
                    }

                    else -> {
                        workflow.showHistoryRefresh(
                            HistoricalDrawRefreshStatus.Notice("已取得最新开奖，当前预览或请求结束后点刷新即可使用"),
                        )
                        return
                    }
                }
                workflow.showHistoryRefresh(
                    result.notice?.let(HistoricalDrawRefreshStatus::Notice) ?: HistoricalDrawRefreshStatus.Idle,
                )
            }

            is HistoricalDrawSyncResult.Failed -> {
                if (!isCurrentAiHistoryLoad(lotteryType, generation)) return
                if (workflow.state.history is AiHistoryAvailability.Ready) {
                    workflow.showHistoryRefresh(HistoricalDrawRefreshStatus.Failed(result.message))
                } else {
                    workflow.showHistoryFailure(result.message)
                }
            }
        }
    }

    /** 把历史开奖交给 AI 工作流并记录展示的内容版本。 */
    private fun showAiHistory(entry: HistoricalDrawEntry) {
        aiHistoryRevision = entry.revision
        aiAnalysisWorkflow?.showHistoryReady(entry.draws, entry.sourceName, entry.fetchedAtEpochMillis)
    }

    /** 判断迟到的历史开奖响应是否仍属于当前 AI 页面和彩种。 */
    private fun isCurrentAiHistoryLoad(
        lotteryType: LotteryType,
        generation: Long,
    ): Boolean {
        val screen = mutableUiState.value.screen as? AppScreen.AiAnalysis ?: return false
        return generation == flowGeneration && screen.workspace.settings.lotteryType == lotteryType
    }

    /**
     * 在关于与隐私页清除本机历史开奖和已开奖查询结果，不影响票据记录。
     *
     * 下次进入走势或 AI 页会重新完整加载，查询开奖会重新联网核对每一期。
     */
    suspend fun clearHistoricalDrawCache() {
        val screen = mutableUiState.value.screen as? AppScreen.About ?: return
        if (screen.isClearingHistory) return
        mutableUiState.update { it.copy(screen = screen.copy(isClearingHistory = true, historyMessage = null)) }
        val historyCleared = historyCache.clearAll()
        val drawResultsCleared = drawResultCache.clearAll()
        val cleared = historyCleared && drawResultsCleared
        trendCalculationSequence += 1L
        trendChartRevision = null
        aiHistoryRevision = null
        trendChartState =
            trendChartState.copy(
                content = TrendChartContent.Loading,
                researchContent = TrendResearchContent.Loading,
                historyRefresh = HistoricalDrawRefreshStatus.Idle,
            )
        aiAnalysisWorkflow?.showHistoryLoading()
        val message =
            if (cleared) {
                "已清除本机开奖数据，走势、AI 分析和查询开奖会重新联网核对"
            } else {
                "本机开奖数据清除失败，请稍后重试"
            }
        mutableUiState.update { state ->
            if (state.screen is AppScreen.About) {
                state.copy(screen = AppScreen.About(isClearingHistory = false, historyMessage = message))
            } else {
                state
            }
        }
    }

    /** 只在 AI 页面可见时把共享工作流状态同步到应用顶层状态。 */
    private fun publishAiWorkspace(workspace: AiAnalysisWorkspaceState) {
        mutableUiState.update { state ->
            if (state.screen is AppScreen.AiAnalysis) {
                state.copy(screen = AppScreen.AiAnalysis(workspace))
            } else {
                state
            }
        }
    }

    /** 打开本机记录列表并结束此前的临时确认流程。 */
    suspend fun showTicketRecords() {
        if (container.ticketRecordStore == null) return
        advanceFlowGeneration()
        aiAnalysisWorkflow?.clearTransientRequest()
        clearTemporaryImage()
        lastQuerySourceScreen = null
        queryReturnScreen = null
        resetConfirmationPersistence()
        resetTicketRecordManagement()
        mutableUiState.update { it.copy(screen = AppScreen.Records()) }
    }

    /** 修改记录页的彩种筛选。 */
    fun updateTicketRecordFilter(filter: TicketRecordFilter) {
        val records = mutableUiState.value.screen as? AppScreen.Records ?: return
        mutableUiState.update {
            it.copy(
                screen =
                    records.copy(
                        filter = filter,
                        operationError = null,
                        operationNotice = null,
                    ),
            )
        }
    }

    /** 修改记录页的名称或期号搜索文本。 */
    fun updateTicketRecordSearch(query: String) {
        val records = mutableUiState.value.screen as? AppScreen.Records ?: return
        mutableUiState.update {
            it.copy(
                screen =
                    records.copy(
                        searchQuery = query,
                        operationError = null,
                        operationNotice = null,
                    ),
            )
        }
    }

    /** 重命名一条仍存在的本机记录，并把失败保留在列表页内。 */
    suspend fun renameTicketRecord(
        id: String,
        displayName: String,
    ) {
        val store = container.ticketRecordStore ?: return
        val generation = beginTicketRecordManagement() ?: return
        try {
            val renamed =
                try {
                    store.rename(id, displayName)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: IllegalArgumentException) {
                    showTicketRecordOperationError(error.message ?: "记录名称不合法", generation)
                    return
                } catch (_: Exception) {
                    showTicketRecordOperationError("重命名失败，请稍后重试", generation)
                    return
                }
            if (renamed) {
                showTicketRecordOperationNotice("记录名称已更新", generation)
            } else {
                showTicketRecordOperationError("这条记录已不存在，列表已自动刷新", generation)
            }
        } finally {
            finishTicketRecordManagement(generation)
        }
    }

    /** 删除一条仍存在的本机记录，并把结果保留在列表页内。 */
    suspend fun deleteTicketRecord(id: String) {
        val store = container.ticketRecordStore ?: return
        val generation = beginTicketRecordManagement() ?: return
        try {
            val deleted =
                try {
                    store.delete(id)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    showTicketRecordOperationError("删除失败，请稍后重试", generation)
                    return
                }
            if (deleted) {
                showTicketRecordOperationNotice("记录已从本机删除", generation)
            } else {
                showTicketRecordOperationError("这条记录已不存在，列表已自动刷新", generation)
            }
        } finally {
            finishTicketRecordManagement(generation)
        }
    }

    /** 清空当前数据库中的全部结构化票据记录。 */
    suspend fun clearTicketRecords() {
        val store = container.ticketRecordStore ?: return
        val generation = beginTicketRecordManagement() ?: return
        try {
            val deletedCount =
                try {
                    store.clear()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    showTicketRecordOperationError("清空失败，请稍后重试", generation)
                    return
                }
            val message =
                if (deletedCount == 0) {
                    "本机记录已经为空"
                } else {
                    "已清空 $deletedCount 条本机记录"
                }
            showTicketRecordOperationNotice(message, generation)
        } finally {
            finishTicketRecordManagement(generation)
        }
    }

    /** 生成规范化逻辑包，并在系统保存选择器中写入用户确认的位置。 */
    suspend fun exportTicketRecords() {
        val store = container.ticketRecordStore ?: return
        val fileExchange = container.ticketRecordFileExchange ?: return
        val generation = beginTicketRecordManagement() ?: return
        try {
            val exported =
                try {
                    store.exportPackage()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    showTicketRecordOperationError("无法生成导出文件，请稍后重试", generation)
                    return
                }
            if (generation != flowGeneration) return
            val fileName = "win-lottery-tickets-${exported.exportedAtEpochMillis}$TICKET_RECORD_EXPORT_EXTENSION"
            when (val result = fileExchange.writeExportPackage(fileName, exported.content)) {
                TicketRecordFileWriteResult.Cancelled -> {
                    // 用户取消系统保存后保持记录页不变。
                }

                TicketRecordFileWriteResult.Success -> {
                    showTicketRecordOperationNotice("已导出 ${exported.recordCount} 条记录", generation)
                }

                is TicketRecordFileWriteResult.Failure -> {
                    showTicketRecordOperationError(result.message, generation)
                }
            }
        } finally {
            finishTicketRecordManagement(generation)
        }
    }

    /** 从系统文件选择器读取逻辑包，完整预检后等待用户确认。 */
    suspend fun selectTicketRecordImport() {
        val store = container.ticketRecordStore ?: return
        val fileExchange = container.ticketRecordFileExchange ?: return
        val generation = beginTicketRecordManagement(clearPendingImport = true) ?: return
        try {
            when (val fileResult = fileExchange.readImportPackage()) {
                TicketRecordFileReadResult.Cancelled -> {
                    // 用户取消系统选择后保持记录页不变。
                }

                is TicketRecordFileReadResult.Failure -> {
                    showTicketRecordOperationError(fileResult.message, generation)
                }

                is TicketRecordFileReadResult.Success -> {
                    if (generation != flowGeneration) return
                    val preview =
                        try {
                            store.inspectImport(fileResult.content)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: TicketRecordImportException) {
                            showTicketRecordOperationError(error.message ?: "导入文件校验失败", generation)
                            return
                        } catch (_: Exception) {
                            showTicketRecordOperationError("无法校验导入文件，请稍后重试", generation)
                            return
                        }
                    if (generation != flowGeneration) return
                    pendingTicketRecordImportContent = fileResult.content
                    updateTicketRecordScreen(generation) { records ->
                        records.copy(importPreview = preview)
                    }
                }
            }
        } finally {
            finishTicketRecordManagement(generation)
        }
    }

    /**
     * 提交已经预检的逻辑包。
     *
     * @param keepLocalConflicts 是否由用户明确选择保留本机冲突项并导入其余记录。
     */
    suspend fun confirmTicketRecordImport(keepLocalConflicts: Boolean) {
        val store = container.ticketRecordStore ?: return
        val content = pendingTicketRecordImportContent ?: return
        val generation = beginTicketRecordManagement(clearPendingImport = false) ?: return
        try {
            val policy =
                if (keepLocalConflicts) {
                    TicketRecordImportConflictPolicy.KEEP_LOCAL_AND_IMPORT_REST
                } else {
                    TicketRecordImportConflictPolicy.REJECT_ALL
                }
            when (val result = store.importPackage(content, policy)) {
                is TicketRecordImportResult.Conflicts -> {
                    updateTicketRecordScreen(generation) { records ->
                        records.copy(importPreview = result.preview)
                    }
                }

                is TicketRecordImportResult.Completed -> {
                    pendingTicketRecordImportContent = null
                    showTicketRecordOperationNotice(result.toUserMessage(), generation, clearImportPreview = true)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: TicketRecordImportException) {
            showTicketRecordOperationError(error.message ?: "导入文件校验失败", generation)
        } catch (_: Exception) {
            showTicketRecordOperationError("导入失败，数据库未写入不完整数据", generation)
        } finally {
            finishTicketRecordManagement(generation)
        }
    }

    /** 取消当前导入确认并立即丢弃内存中的文件字节。 */
    fun dismissTicketRecordImport() {
        if (recordManagementGeneration != null) return
        pendingTicketRecordImportContent = null
        val generation = flowGeneration
        updateTicketRecordScreen(generation) { records -> records.copy(importPreview = null) }
    }

    /** 直接读取一条已保存票据并查询开奖，不触发图片采集、OCR 或再次保存。 */
    suspend fun queryTicketRecord(id: String) {
        val store = container.ticketRecordStore ?: return
        val generation = flowGeneration
        if (recordManagementGeneration != null) return
        if (recordQueryGeneration == generation) return
        val recordsScreen = mutableUiState.value.screen as? AppScreen.Records ?: return
        if (recordsScreen.importPreview != null) return
        recordQueryGeneration = generation
        try {
            val record =
                try {
                    store.findById(id)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    showTicketRecordOperationError("无法读取这条本机记录，请稍后重试", generation)
                    return
                }
            if (generation != flowGeneration) return
            if (record == null) {
                showTicketRecordOperationError("这条记录已不存在，列表已自动刷新", generation)
                return
            }
            val sourceScreen = mutableUiState.value.screen as? AppScreen.Records ?: return
            lastQuerySourceScreen = sourceScreen.copy(operationError = null, operationNotice = null)
            queryReturnScreen = lastQuerySourceScreen
            runCancellableFlow { queryDraw(record.ticket, generation) }
        } finally {
            if (recordQueryGeneration == generation) {
                recordQueryGeneration = null
            }
        }
    }

    /** 打开关于与隐私页。 */
    fun showAbout() {
        mutableUiState.update { it.copy(screen = AppScreen.About()) }
    }

    /** 应用一次人工校正动作并立即刷新领域问题。 */
    fun updateTicketReview(action: TicketReviewAction) {
        if (confirmingGeneration == flowGeneration) return
        val review = mutableUiState.value.screen as? AppScreen.Review ?: return
        val editor = review.editor.applyAction(action)
        mutableUiState.update {
            it.copy(
                screen =
                    review.copy(
                        editor = editor,
                        evaluation = editor.evaluate(container.ticketValidator),
                        saveError = null,
                    ),
            )
        }
    }

    /** 用户确认当前合法票据后，清理临时图片并执行一次精确期号查询。 */
    suspend fun confirmTicket() {
        val review = mutableUiState.value.screen as? AppScreen.Review ?: return
        val ticket = review.evaluation.ticket
        if (!review.evaluation.canConfirm || ticket == null) return
        val generation = flowGeneration
        if (confirmingGeneration == generation) return
        confirmingGeneration = generation
        try {
            val store = container.ticketRecordStore
            // 同一流程只保留一条记录：票面未变时跳过，返回校正后再次确认时原地替换。
            if (store != null && ticket != savedCurrentFlowTicket) {
                val acquisitionSource = currentAcquisitionSource
                if (acquisitionSource == null) {
                    showReviewSaveError("无法确定票据采集方式，请返回首页后重试", generation)
                    return
                }
                val savedRecordId = savedCurrentFlowRecordId
                val record =
                    try {
                        savedRecordId?.let { id -> store.replaceTicket(id, ticket) }
                            ?: store.save(ticket, acquisitionSource)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        showReviewSaveError("无法保存本机记录，请检查设备存储后重试", generation)
                        return
                    }
                if (generation != flowGeneration) return
                savedCurrentFlowRecordId = record.id
                savedCurrentFlowTicket = ticket
            }
            if (generation != flowGeneration) return
            lastQuerySourceScreen = review.forReturnNavigation()
            queryReturnScreen = lastQuerySourceScreen
            clearTemporaryImage()
            runCancellableFlow { queryDraw(ticket, generation) }
        } finally {
            if (confirmingGeneration == generation) {
                confirmingGeneration = null
            }
        }
    }

    /**
     * 从结果页或不可用页保留用户确认票据并主动重新查询开奖。
     *
     * 单期结果页和全部完成的多期结果页总是重新联网核对；其余情况保留本机已完成期次，只核对未完成期次。
     *
     * @param forceRefresh 为 `true` 时忽略本机结果和开奖日程，全部重新联网核对。
     */
    suspend fun retryDrawQuery(forceRefresh: Boolean = false) {
        when (val screen = mutableUiState.value.screen) {
            is AppScreen.DrawUnavailable -> {
                queryReturnScreen = screen
                val mode = if (forceRefresh) DrawQueryMode.FORCE else DrawQueryMode.RETRY
                runCancellableFlow { queryDraw(screen.ticket, flowGeneration, mode) }
            }

            is AppScreen.VerificationResult -> {
                queryReturnScreen = screen
                runCancellableFlow { queryDraw(screen.ticket, flowGeneration, DrawQueryMode.FORCE) }
            }

            is AppScreen.MultiPeriodVerificationResult -> {
                queryReturnScreen = screen
                val mode =
                    if (forceRefresh || screen.unresolvedPeriodCount == 0) DrawQueryMode.FORCE else DrawQueryMode.RETRY
                runCancellableFlow {
                    queryMultipleDraws(
                        ticket = screen.ticket,
                        generation = flowGeneration,
                        previousResults = screen.periodResults,
                        mode = mode,
                    )
                }
            }

            else -> {
                return
            }
        }
    }

    /** 根据票面期数分派单期或多期查询，并把真实数据交给本地规则引擎。 */
    private suspend fun queryDraw(
        ticket: ConfirmedTicket,
        generation: Long,
        mode: DrawQueryMode = DrawQueryMode.ENTRY,
    ) {
        if (container.usesRealDrawData && ticket.periodCount.value > 1) {
            queryMultipleDraws(ticket, generation, mode = mode)
            return
        }
        querySingleDraw(ticket, generation, mode)
    }

    /**
     * 查询一个精确期号，并保持既有单期结果交互。
     *
     * 本机结果对本票已完成时直接展示；按开奖日程判断未到时间时不联网；联网未取得新证据时保留本机结果。
     */
    private suspend fun querySingleDraw(
        ticket: ConfirmedTicket,
        generation: Long,
        mode: DrawQueryMode,
    ) {
        if (generation != flowGeneration) return
        val lotteryType = ticket.lotteryType.value
        val issue = ticket.issue.value
        val epoch = drawResultCache.clearEpoch
        val storedPeriod =
            drawResultCache
                .load(lotteryType, listOf(issue))[issue]
                ?.let { verifiedPeriod(ticket, issue, it, isFromLocalStore = true) }
        if (generation != flowGeneration) return
        if (mode != DrawQueryMode.FORCE && storedPeriod != null && !storedPeriod.requiresRetry) {
            showVerificationResult(ticket, storedPeriod)
            return
        }
        if (mode == DrawQueryMode.ENTRY && container.usesRealDrawData) {
            val earliest = earliestDrawTime(lotteryType, issue, emptyList())
            if (generation != flowGeneration) return
            if (earliest != null && clock.now() < earliest) {
                queryReturnScreen = null
                mutableUiState.update {
                    it.copy(
                        screen =
                            AppScreen.DrawUnavailable(
                                ticket = ticket,
                                status = DrawStatus.NOT_PUBLISHED,
                                message = notYetDrawnMessage(earliest),
                                wasQueried = false,
                            ),
                    )
                }
                return
            }
        }
        mutableUiState.update { it.copy(screen = AppScreen.DrawQuery(ticket)) }
        when (val result = container.drawRepository.getDraw(lotteryType, issue)) {
            is DrawQueryResult.Success -> {
                if (!container.usesRealDrawData) {
                    if (generation == flowGeneration) {
                        queryReturnScreen = null
                        mutableUiState.update { it.copy(screen = AppScreen.DemoComplete(result.drawResult)) }
                    }
                    return
                }
                val saved = drawResultCache.save(result.drawResult, epoch)
                if (generation == flowGeneration) {
                    showVerificationResult(ticket, verifiedPeriod(ticket, issue, saved, isFromLocalStore = false))
                }
            }

            is DrawQueryResult.Unavailable -> {
                if (result.status == DrawStatus.CONFLICT) drawResultCache.delete(lotteryType, issue)
                if (generation != flowGeneration) return
                if (storedPeriod != null && result.status in PRESERVE_VERIFIED_STATUSES) {
                    showVerificationResult(
                        ticket = ticket,
                        period = storedPeriod,
                        refreshNotice = "本次联网未取得新证据（${result.message}），显示本机保存的开奖",
                    )
                    return
                }
                queryReturnScreen = null
                mutableUiState.update {
                    it.copy(
                        screen =
                            AppScreen.DrawUnavailable(
                                ticket = ticket,
                                status = result.status,
                                message = result.message,
                            ),
                    )
                }
            }
        }
    }

    /** 展示单期真实中奖测算结果页。 */
    private fun showVerificationResult(
        ticket: ConfirmedTicket,
        period: PeriodVerification.Verified,
        refreshNotice: String? = null,
    ) {
        queryReturnScreen = null
        mutableUiState.update {
            it.copy(
                screen =
                    AppScreen.VerificationResult(
                        ticket = ticket,
                        drawResult = period.drawResult,
                        prizeCheckResult = period.prizeCheckResult,
                        isFromLocalStore = period.isFromLocalStore,
                        refreshNotice = refreshNotice,
                    ),
            )
        }
    }

    /**
     * 按时间顺序查询一张多期票，并保留每一期独立状态。
     *
     * 期号优先采用官网已开奖的实际期号，因此可以跨越年度换期；尚未开奖的剩余期次按本年度序号顺延预估。
     * 本机或上一轮结果对本票已完成的期次不再联网；按开奖日程未到时间、明确尚未开奖、网络断开或数据源整体
     * 不可用时停止本轮后续请求；发布核对中和单期冲突只影响对应期次。
     */
    private suspend fun queryMultipleDraws(
        ticket: ConfirmedTicket,
        generation: Long,
        previousResults: List<PeriodVerification>? = null,
        mode: DrawQueryMode = DrawQueryMode.ENTRY,
    ) {
        if (generation != flowGeneration) return
        val lotteryType = ticket.lotteryType.value
        val periodCount = ticket.periodCount.value
        val epoch = drawResultCache.clearEpoch
        val usesSchedule = mode == DrawQueryMode.ENTRY && container.usesRealDrawData
        val knownPeriods = mutableMapOf<Issue, PeriodVerification.Verified>()

        /** 读取本机结果并与上一轮结果合并；上一轮在本会话内取得，优先使用。 */
        suspend fun loadKnown(issues: Collection<Issue>) {
            val missing = issues.filter { it !in knownPeriods }
            drawResultCache.load(lotteryType, missing).forEach { (issue, drawResult) ->
                knownPeriods[issue] = verifiedPeriod(ticket, issue, drawResult, isFromLocalStore = true)
            }
        }
        previousResults.orEmpty().filterIsInstance<PeriodVerification.Verified>().forEach {
            knownPeriods[it.issue] = it
        }
        val sameYearIssues =
            (
                issueSequenceResolver.resolve(lotteryType, ticket.issue.value, periodCount) as?
                    IssueSequenceResult.Success
            )?.issues.orEmpty()
        loadKnown(sameYearIssues)
        if (generation != flowGeneration) return
        // 同年度期号连续，已开奖链可以安全代替官网期号列表；跨年度必须以官网列表为准。
        val knownChain = sameYearIssues.takeWhile { it in knownPeriods }
        val needsOfficialSequence =
            when {
                mode == DrawQueryMode.FORCE -> {
                    true
                }

                knownChain.size >= periodCount -> {
                    false
                }

                !usesSchedule || knownChain.isEmpty() -> {
                    true
                }

                else -> {
                    val last = checkNotNull(knownPeriods[knownChain.last()]).drawResult
                    val next = sameYearIssues[knownChain.size]
                    val earliest = DrawSchedule.earliestDrawTime(lotteryType, last.issue, last.drawDate, next)
                    earliest == null || clock.now() >= earliest
                }
            }
        val drawnIssues =
            if (needsOfficialSequence) {
                container.drawRepository.getDrawnIssues(lotteryType, ticket.issue.value, periodCount) ?: knownChain
            } else {
                knownChain
            }
        if (generation != flowGeneration) return
        val issues =
            when (
                val resolution =
                    issueSequenceResolver.resolve(
                        lotteryType = lotteryType,
                        firstIssue = ticket.issue.value,
                        periodCount = periodCount,
                        drawnIssues = drawnIssues,
                    )
            ) {
                is IssueSequenceResult.Success -> {
                    resolution.issues
                }

                is IssueSequenceResult.Unsupported -> {
                    queryReturnScreen = null
                    mutableUiState.update {
                        it.copy(screen = AppScreen.Error("无法展开多期期号", resolution.message))
                    }
                    return
                }
            }
        loadKnown(issues)
        if (generation != flowGeneration) return

        val networkPeriodCount =
            issues.count { issue -> mode == DrawQueryMode.FORCE || knownPeriods[issue]?.requiresRetry != false }
        val updatedByIssue = mutableMapOf<Issue, PeriodVerification>()
        var stopResult: DrawQueryResult.Unavailable? = null
        var retryNotice: MultiPeriodRetryNotice? = null
        var completedCount = 0

        for (issue in issues) {
            if (generation != flowGeneration) return
            val known = knownPeriods[issue]
            // 已完成期次先于停止判断，保证前序期次失败时不会把它们标成未查询。
            if (mode != DrawQueryMode.FORCE && known != null && !known.requiresRetry) {
                updatedByIssue[issue] = known
                continue
            }
            val stopped = stopResult
            if (stopped != null) {
                updatedByIssue[issue] =
                    known
                        ?: PeriodVerification.Unavailable(
                            issue = issue,
                            status = stopped.status,
                            message = stopped.skippedPeriodMessage(),
                            wasQueried = false,
                        )
                completedCount += 1
                continue
            }
            if (usesSchedule) {
                val earlierDraws =
                    updatedByIssue.values.filterIsInstance<PeriodVerification.Verified>().map {
                        it.drawResult
                    }
                val earliest = earliestDrawTime(lotteryType, issue, earlierDraws)
                if (generation != flowGeneration) return
                if (earliest != null && clock.now() < earliest) {
                    val notDue = DrawQueryResult.Unavailable(DrawStatus.NOT_PUBLISHED, notYetDrawnMessage(earliest))
                    updatedByIssue[issue] =
                        known
                            ?: PeriodVerification.Unavailable(
                                issue = issue,
                                status = notDue.status,
                                message = notDue.message,
                                wasQueried = false,
                            )
                    stopResult = notDue
                    completedCount += 1
                    continue
                }
            }
            mutableUiState.update {
                it.copy(
                    screen =
                        AppScreen.DrawQuery(
                            ticket = ticket,
                            currentIssue = issue,
                            completedPeriodCount = completedCount,
                            totalPeriodCount = networkPeriodCount,
                        ),
                )
            }

            when (val result = container.drawRepository.getDraw(lotteryType, issue)) {
                is DrawQueryResult.Success -> {
                    val saved = drawResultCache.save(result.drawResult, epoch)
                    updatedByIssue[issue] = verifiedPeriod(ticket, issue, saved, isFromLocalStore = false)
                }

                is DrawQueryResult.Unavailable -> {
                    if (result.status == DrawStatus.CONFLICT) drawResultCache.delete(lotteryType, issue)
                    if (known != null && result.status in PRESERVE_VERIFIED_STATUSES) {
                        updatedByIssue[issue] = known
                        retryNotice =
                            retryNotice
                                ?: MultiPeriodRetryNotice(
                                    status = result.status,
                                    message = result.message,
                                )
                    } else {
                        updatedByIssue[issue] =
                            PeriodVerification.Unavailable(
                                issue = issue,
                                status = result.status,
                                message = result.message,
                                wasQueried = true,
                            )
                    }
                    if (result.status in STOP_REMAINING_PERIOD_STATUSES) {
                        stopResult = result
                    }
                }
            }
            completedCount += 1
        }
        if (generation != flowGeneration) return
        val periodResults =
            issues.map { issue ->
                updatedByIssue[issue]
                    ?: PeriodVerification.Unavailable(
                        issue = issue,
                        status = DrawStatus.SOURCE_UNAVAILABLE,
                        message = "本轮未能生成该期查询状态，请主动重试",
                        wasQueried = false,
                    )
            }
        queryReturnScreen = null
        mutableUiState.update {
            it.copy(
                screen =
                    AppScreen.MultiPeriodVerificationResult(
                        ticket = ticket,
                        periodResults = periodResults,
                        retryNotice = retryNotice,
                    ),
            )
        }
    }

    /** 用本机规则为指定期次重新判级。 */
    private fun verifiedPeriod(
        ticket: ConfirmedTicket,
        issue: Issue,
        drawResult: DrawResult,
        isFromLocalStore: Boolean,
    ): PeriodVerification.Verified {
        val periodTicket = ticket.copy(issue = ticket.issue.copy(value = issue))
        return PeriodVerification.Verified(
            issue = issue,
            drawResult = drawResult,
            prizeCheckResult = container.prizeCalculator.calculate(periodTicket, drawResult),
            isFromLocalStore = isFromLocalStore,
        )
    }

    /**
     * 根据不需要联网的已知开奖推算目标期最早开奖时刻，取最晚的下界。
     *
     * 已知开奖依次来自本票更早期次、本机保存的同年度上一期和本机历史开奖的最新一期；都没有时返回 `null`。
     */
    private suspend fun earliestDrawTime(
        lotteryType: LotteryType,
        target: Issue,
        earlierDraws: List<DrawResult>,
    ): Instant? {
        val references = earlierDraws.map { it.issue to it.drawDate }.toMutableList()
        target.previousSameYearIssue(lotteryType)?.let { previous ->
            drawResultCache.load(lotteryType, listOf(previous))[previous]?.let { references += it.issue to it.drawDate }
        }
        historyCache
            .peek(lotteryType)
            ?.draws
            ?.lastOrNull()
            ?.let { references += it.issue to it.drawDate }
        return references
            .mapNotNull { (issue, drawDate) -> DrawSchedule.earliestDrawTime(lotteryType, issue, drawDate, target) }
            .maxOrNull()
    }

    /** 返回同年度上一期期号；首期或格式不合法时为 `null`。 */
    private fun Issue.previousSameYearIssue(lotteryType: LotteryType): Issue? {
        val prefixLength = if (lotteryType == LotteryType.SUPER_LOTTO) 2 else 4
        if (value.length != prefixLength + 3 || !value.all(Char::isDigit)) return null
        val ordinal = value.takeLast(3).toInt()
        if (ordinal <= 1) return null
        return Issue(value.take(prefixLength) + (ordinal - 1).toString().padStart(3, '0'))
    }

    /** 按北京时间说明预计最早开奖时刻。 */
    private fun notYetDrawnMessage(earliest: Instant): String {
        val time = earliest.toLocalDateTime(TimeZone.of("Asia/Shanghai"))
        val weekday = "一二三四五六日"[time.dayOfWeek.ordinal]
        return "未到开奖时间，预计最早 ${(time.month.ordinal + 1).toString().padStart(2, '0')}-" +
            "${time.day.toString().padStart(2, '0')}（周$weekday）" +
            "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')} 后开奖，本次未联网查询"
    }

    /** 为因前序状态停止查询的后续期次生成明确说明。 */
    private fun DrawQueryResult.Unavailable.skippedPeriodMessage(): String =
        when (status) {
            DrawStatus.NOT_PUBLISHED -> "前一期次尚未发布，本期本轮未发起官网查询"
            DrawStatus.NETWORK_UNAVAILABLE -> "前一期次查询时网络不可用，本期本轮未发起官网查询"
            DrawStatus.SOURCE_UNAVAILABLE -> "前一期次查询时官网数据源不可用，本期本轮未发起查询"
            else -> "前一期次未完成，本期本轮未发起官网查询"
        }

    /** 对已取得的临时图片执行本地 OCR 和结构解析。 */
    private suspend fun recognize(
        acquisition: ImageAcquisitionResult.Success,
        generation: Long,
    ) {
        if (generation != flowGeneration) return
        mutableUiState.update {
            it.copy(screen = AppScreen.Analysis("本地识别", "正在读取票面文字，不会上传图片", 0.52f))
        }

        val recognizer = container.ticketRecognizer
        // 平台识别器首次调用会加载模型和字典，整体放入计算上下文，避免阻塞界面线程。
        val recognition =
            withContext(computationContext) {
                if (recognizer is ProgressiveTicketRecognizer) {
                    recognizer.recognize(acquisition.imageRef) { progress ->
                        if (generation == flowGeneration) {
                            mutableUiState.update { state ->
                                state.copy(
                                    screen =
                                        AppScreen.Analysis(
                                            title = "本地识别",
                                            detail = progress.message,
                                            progress =
                                                OCR_ANALYSIS_START_PROGRESS +
                                                    progress.fraction * OCR_ANALYSIS_PROGRESS_SPAN,
                                        ),
                                )
                            }
                        }
                    }
                } else {
                    recognizer.recognize(acquisition.imageRef)
                }
            }

        when (recognition) {
            is RecognitionResult.Failure -> {
                showError("识别失败", recognition.message, generation)
            }

            is RecognitionResult.PoorImage -> {
                showError("图片质量不足", recognition.issues.joinToString(separator = "；"), generation)
            }

            is RecognitionResult.ManualEntryRequired -> {
                if (generation == flowGeneration) {
                    mutableUiState.update {
                        it.copy(
                            screen =
                                createReviewScreen(
                                    editor = TicketReviewState.createManual(),
                                    imageRef = acquisition.imageRef,
                                    fieldRegions = emptyList(),
                                    manualEntryReason = recognition.message,
                                ),
                        )
                    }
                }
            }

            is RecognitionResult.Success -> {
                if (generation != flowGeneration) return
                mutableUiState.update {
                    it.copy(screen = AppScreen.Analysis("结构解析", "正在整理期号、号码和投注属性", 0.82f))
                }
                val parsed = withContext(computationContext) { container.ticketParser.parse(recognition.document) }
                if (generation != flowGeneration) return
                container.ocrConfidenceDiagnostics.record(
                    recognition.document.engineName,
                    parsed.toOcrConfidenceSample(),
                )
                when (parsed) {
                    is TicketParseResult.NeedsCorrection -> {
                        val draft = parsed.draft
                        if (draft == null) {
                            mutableUiState.update {
                                it.copy(
                                    screen =
                                        createReviewScreen(
                                            editor = TicketReviewState.createManual(),
                                            imageRef = acquisition.imageRef,
                                            fieldRegions = parsed.fieldRegions,
                                            fieldCandidates = parsed.fieldCandidates,
                                            ocrEngineName = recognition.document.engineName,
                                            manualEntryReason = parsed.message,
                                        ),
                                )
                            }
                        } else if (generation == flowGeneration) {
                            mutableUiState.update {
                                it.copy(
                                    screen =
                                        createReviewScreen(
                                            editor = TicketReviewState.fromDraft(draft),
                                            imageRef = acquisition.imageRef,
                                            fieldRegions = parsed.fieldRegions,
                                            fieldCandidates = parsed.fieldCandidates,
                                            ocrEngineName = recognition.document.engineName,
                                        ),
                                )
                            }
                        }
                    }

                    is TicketParseResult.Unsupported -> {
                        showError("暂不支持这张彩票", parsed.message, generation)
                    }

                    is TicketParseResult.ReadyForReview -> {
                        if (generation == flowGeneration) {
                            mutableUiState.update {
                                it.copy(
                                    screen =
                                        createReviewScreen(
                                            editor = TicketReviewState.fromDraft(parsed.draft),
                                            imageRef = acquisition.imageRef,
                                            fieldRegions = parsed.fieldRegions,
                                            ocrEngineName = recognition.document.engineName,
                                        ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /** 从编辑状态创建带实时领域评估的校正页。 */
    private fun createReviewScreen(
        editor: TicketReviewState,
        imageRef: ImageRef?,
        fieldRegions: List<TicketFieldRegion>,
        fieldCandidates: List<TicketFieldCandidate> = emptyList(),
        ocrEngineName: String? = null,
        manualEntryReason: String? = null,
    ): AppScreen.Review =
        AppScreen.Review(
            editor = editor,
            imageRef = imageRef,
            evaluation = editor.evaluate(container.ticketValidator),
            fieldRegions = fieldRegions,
            fieldCandidates = fieldCandidates,
            ocrEngineName = ocrEngineName,
            manualEntryReason = manualEntryReason,
        )

    /** 创建返回导航使用的确认页快照，避免引用已经按隐私策略删除的图片。 */
    private fun AppScreen.Review.forReturnNavigation(): AppScreen.Review =
        copy(
            imageRef = null,
            fieldRegions = emptyList(),
            fieldCandidates = emptyList(),
            ocrEngineName = null,
            manualEntryReason = null,
            saveError = null,
        )

    /** 只在当前确认流程仍有效时显示本机保存错误。 */
    private fun showReviewSaveError(
        message: String,
        generation: Long,
    ) {
        if (generation != flowGeneration) return
        val review = mutableUiState.value.screen as? AppScreen.Review ?: return
        mutableUiState.update { it.copy(screen = review.copy(saveError = message)) }
    }

    /**
     * 开始一次互斥的记录管理操作，并清理上一次操作提示。
     *
     * @param clearPendingImport 是否同时取消尚未提交的导入确认。
     * @return 当前记录页流程代次；页面失效或已有操作时返回 `null`。
     */
    private fun beginTicketRecordManagement(clearPendingImport: Boolean = true): Long? {
        val generation = flowGeneration
        val records = mutableUiState.value.screen as? AppScreen.Records ?: return null
        if (recordManagementGeneration != null || recordQueryGeneration != null) return null
        recordManagementGeneration = generation
        if (clearPendingImport) {
            pendingTicketRecordImportContent = null
        }
        mutableUiState.update {
            it.copy(
                screen =
                    records.copy(
                        operationError = null,
                        operationNotice = null,
                        isOperationInProgress = true,
                        importPreview = if (clearPendingImport) null else records.importPreview,
                    ),
            )
        }
        return generation
    }

    /** 结束仍属于当前记录页代次的管理操作。 */
    private fun finishTicketRecordManagement(generation: Long) {
        if (recordManagementGeneration != generation) return
        recordManagementGeneration = null
        updateTicketRecordScreen(generation) { records ->
            records.copy(isOperationInProgress = false)
        }
    }

    /** 清除记录管理互斥状态和仅供导入确认使用的原始文件字节。 */
    private fun resetTicketRecordManagement() {
        recordManagementGeneration = null
        pendingTicketRecordImportContent = null
    }

    /** 只在指定记录页流程仍有效时应用页面更新。 */
    private fun updateTicketRecordScreen(
        generation: Long,
        transform: (AppScreen.Records) -> AppScreen.Records,
    ) {
        if (generation != flowGeneration) return
        val records = mutableUiState.value.screen as? AppScreen.Records ?: return
        mutableUiState.update { it.copy(screen = transform(records)) }
    }

    /** 只在当前记录页仍有效时显示操作成功摘要。 */
    private fun showTicketRecordOperationNotice(
        message: String,
        generation: Long,
        clearImportPreview: Boolean = false,
    ) {
        updateTicketRecordScreen(generation) { records ->
            records.copy(
                operationError = null,
                operationNotice = message,
                importPreview = if (clearImportPreview) null else records.importPreview,
            )
        }
    }

    /** 只在当前记录页仍有效时显示列表操作错误。 */
    private fun showTicketRecordOperationError(
        message: String,
        generation: Long,
    ) {
        updateTicketRecordScreen(generation) { records ->
            records.copy(operationError = message, operationNotice = null)
        }
    }

    /** 把一次成功导入转换为不暴露票面内容的用户摘要。 */
    private fun TicketRecordImportResult.Completed.toUserMessage(): String {
        val parts = mutableListOf<String>()
        parts += if (importedCount > 0) "已导入 $importedCount 条记录" else "没有新增记录"
        if (duplicateCount > 0) {
            parts += "跳过 $duplicateCount 条相同记录"
        }
        if (skippedConflictCount > 0) {
            parts += "保留本机 $skippedConflictCount 条冲突记录"
        }
        return parts.joinToString(separator = "，")
    }

    /** 重置一次确认流程的采集来源和幂等保存状态。 */
    private fun resetConfirmationPersistence(source: TicketAcquisitionSource? = null) {
        currentAcquisitionSource = source
        savedCurrentFlowRecordId = null
        savedCurrentFlowTicket = null
    }

    /** 把图片采集来源映射为长期记录使用的稳定枚举。 */
    private fun ImageAcquisitionSource.toTicketAcquisitionSource(): TicketAcquisitionSource =
        when (this) {
            ImageAcquisitionSource.CAMERA -> TicketAcquisitionSource.CAMERA
            ImageAcquisitionSource.SYSTEM_PICKER -> TicketAcquisitionSource.SYSTEM_PICKER
        }

    /** 恢复有效上一级页面；没有可恢复页面时清理流程并回到首页。 */
    private suspend fun restorePreviousScreen(screen: AppScreen?) {
        if (screen == null || screen == AppScreen.Home) {
            navigateHome()
            return
        }
        advanceFlowGeneration()
        clearTemporaryImage()
        queryReturnScreen = null
        mutableUiState.update { it.copy(screen = screen) }
    }

    /**
     * 让当前流程失效，并取消仍在执行的识别或开奖查询任务。
     *
     * 从该任务内部切换页面时只让流程失效，不取消正在执行页面切换的任务自身。
     *
     * @return 新的流程代次。
     */
    private suspend fun advanceFlowGeneration(): Long {
        val task = activeFlowTask
        if (task != null && currentCoroutineContext()[FlowTaskMarker] !== activeFlowTaskMarker) {
            task.cancel()
        }
        activeFlowTask = null
        activeFlowTaskMarker = null
        return ++flowGeneration
    }

    /**
     * 在可取消的子任务中执行识别或开奖查询，任务结束或被取消后返回。
     *
     * 返回、回到首页或开始新流程只取消该子任务，不会取消发起操作的界面协程。
     */
    private suspend fun runCancellableFlow(block: suspend () -> Unit) {
        coroutineScope {
            val marker = FlowTaskMarker()
            val task = launch(marker, start = CoroutineStart.UNDISPATCHED) { block() }
            if (task.isActive) {
                activeFlowTask = task
                activeFlowTaskMarker = marker
            }
            try {
                task.join()
            } finally {
                if (activeFlowTask === task) {
                    activeFlowTask = null
                    activeFlowTaskMarker = null
                }
            }
        }
    }

    /** 只在当前流程仍有效时展示错误。 */
    private suspend fun showError(
        title: String,
        message: String,
        generation: Long,
    ) {
        if (generation == flowGeneration) {
            clearTemporaryImage()
            mutableUiState.update { it.copy(screen = AppScreen.Error(title, message)) }
        }
    }

    /** 删除当前流程持有的临时图片并清空引用。 */
    private suspend fun clearTemporaryImage() {
        activeImageRef?.let { container.appPaths.deleteTemporaryImage(it) }
        activeImageRef = null
    }

    /** 标记协程属于哪一次可取消流程任务；任务内部的嵌套上下文会继承该标记。 */
    private class FlowTaskMarker : AbstractCoroutineContextElement(Key) {
        /** 在协程上下文中查找流程任务标记的键。 */
        companion object Key : CoroutineContext.Key<FlowTaskMarker>
    }

    /** 多期查询和重试使用的状态集合。 */
    private companion object {
        /** OCR 内部阶段映射到应用分析页时的起始进度。 */
        const val OCR_ANALYSIS_START_PROGRESS = 0.52f

        /** OCR 内部阶段在应用分析页中占用的进度区间。 */
        const val OCR_ANALYSIS_PROGRESS_SPAN = 0.28f

        /** 遇到这些状态后继续请求更晚期次没有可靠收益。 */
        val STOP_REMAINING_PERIOD_STATUSES =
            setOf(
                DrawStatus.NOT_PUBLISHED,
                DrawStatus.NETWORK_UNAVAILABLE,
                DrawStatus.SOURCE_UNAVAILABLE,
            )

        /** 瞬时失败没有提供更强新证据，不得覆盖此前已经确认的开奖号码和测算结果。 */
        val PRESERVE_VERIFIED_STATUSES =
            setOf(
                DrawStatus.NETWORK_UNAVAILABLE,
                DrawStatus.SOURCE_UNAVAILABLE,
            )
    }
}

/** 一次开奖查询如何使用本机结果和开奖日程。 */
private enum class DrawQueryMode {
    /** 新票确认或记录页发起：使用本机已完成结果，并按开奖日程跳过未到时间的期次。 */
    ENTRY,

    /** 用户在结果页或不可用页主动重试：使用本机已完成结果，未完成期次直接联网核对。 */
    RETRY,

    /** 用户要求重新联网核对：忽略本机结果和开奖日程，全部重新联网并覆盖本机。 */
    FORCE,
}

package roc.win.lottery.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import roc.win.lottery.data.DrawQueryResult
import roc.win.lottery.domain.ConfirmedTicket
import roc.win.lottery.domain.DrawStatus
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.IssueSequenceResolver
import roc.win.lottery.domain.IssueSequenceResult
import roc.win.lottery.recognition.ImageAcquisitionResult
import roc.win.lottery.recognition.ImageAcquisitionSource
import roc.win.lottery.recognition.ImageQualityResult
import roc.win.lottery.recognition.ImageRef
import roc.win.lottery.recognition.ProgressiveTicketRecognizer
import roc.win.lottery.recognition.RecognitionResult
import roc.win.lottery.recognition.TicketFieldCandidate
import roc.win.lottery.recognition.TicketFieldRegion
import roc.win.lottery.recognition.TicketParseResult

/** 驱动本地图片识别、票面人工校正和开奖查询流程的应用状态持有者。 */
class LotteryAppController(
    /** 应用依赖容器。 */
    private val container: AppContainer,
) {
    /** 内部可变 UI 状态。 */
    private val mutableUiState = MutableStateFlow(AppUiState(isDemo = container.isDemo))

    /** 只读 UI 状态。 */
    val uiState: StateFlow<AppUiState> = mutableUiState.asStateFlow()

    /** 当前流程是否已被用户取消，用于丢弃迟到结果。 */
    private var flowGeneration: Long = 0L

    /** 当前流程持有的临时图片，确认或退出后立即清理。 */
    private var activeImageRef: ImageRef? = null

    /** 最近一次提交查询的票面确认页，不再持有已删除的临时图片。 */
    private var lastReviewScreen: AppScreen.Review? = null

    /** 当前查询被用户返回时需要恢复的来源页面。 */
    private var queryReturnScreen: AppScreen? = null

    /** 将多期票限制在已验证期号边界并展开为逐期查询。 */
    private val issueSequenceResolver = IssueSequenceResolver()

    /**
     * 从拍照或导图开始一次全新的本地分析。
     *
     * @param source 用户选择的图片来源。
     */
    suspend fun startAnalysis(source: ImageAcquisitionSource) {
        clearTemporaryImage()
        lastReviewScreen = null
        queryReturnScreen = null
        val generation = ++flowGeneration
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
                    inspectImage(acquisition, generation)
                } else {
                    container.appPaths.deleteTemporaryImage(acquisition.imageRef)
                }
            }
        }
    }

    /** 直接进入不依赖 OCR 的单期单式彩票手动录入页。 */
    suspend fun startManualEntry() {
        if (!container.usesRealDrawData) return
        flowGeneration += 1L
        clearTemporaryImage()
        lastReviewScreen = null
        queryReturnScreen = null
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

        when (val quality = container.imageQualityAnalyzer.analyze(acquisition.imageRef)) {
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
        flowGeneration += 1L
        clearTemporaryImage()
        lastReviewScreen = null
        queryReturnScreen = null
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

            is AppScreen.Analysis,
            is AppScreen.Review,
            AppScreen.About,
            -> {
                navigateHome()
            }

            is AppScreen.DrawQuery -> {
                restorePreviousScreen(queryReturnScreen ?: lastReviewScreen)
            }

            is AppScreen.DemoComplete,
            is AppScreen.VerificationResult,
            is AppScreen.DrawUnavailable,
            is AppScreen.MultiPeriodVerificationResult,
            is AppScreen.Error,
            -> {
                restorePreviousScreen(lastReviewScreen)
            }
        }
    }

    /** 打开关于与隐私页。 */
    fun showAbout() {
        mutableUiState.update { it.copy(screen = AppScreen.About) }
    }

    /** 应用一次人工校正动作并立即刷新领域问题。 */
    fun updateTicketReview(action: TicketReviewAction) {
        val review = mutableUiState.value.screen as? AppScreen.Review ?: return
        val editor = review.editor.applyAction(action)
        mutableUiState.update {
            it.copy(
                screen =
                    review.copy(
                        editor = editor,
                        evaluation = editor.evaluate(container.ticketValidator),
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
        lastReviewScreen = review.forReturnNavigation()
        queryReturnScreen = lastReviewScreen
        clearTemporaryImage()
        queryDraw(ticket, generation)
    }

    /** 从结果页或不可用页保留用户确认票据并主动重新查询开奖。 */
    suspend fun retryDrawQuery() {
        when (val screen = mutableUiState.value.screen) {
            is AppScreen.DrawUnavailable -> {
                queryReturnScreen = screen
                queryDraw(screen.ticket, flowGeneration)
            }

            is AppScreen.VerificationResult -> {
                queryReturnScreen = screen
                queryDraw(screen.ticket, flowGeneration)
            }

            is AppScreen.MultiPeriodVerificationResult -> {
                queryReturnScreen = screen
                queryMultipleDraws(
                    ticket = screen.ticket,
                    generation = flowGeneration,
                    previousResults = screen.periodResults,
                )
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
    ) {
        if (container.usesRealDrawData && ticket.periodCount.value > 1) {
            queryMultipleDraws(ticket, generation)
            return
        }
        querySingleDraw(ticket, generation)
    }

    /** 查询一个精确期号，并保持既有单期结果交互。 */
    private suspend fun querySingleDraw(
        ticket: ConfirmedTicket,
        generation: Long,
    ) {
        if (generation != flowGeneration) return
        mutableUiState.update { it.copy(screen = AppScreen.DrawQuery(ticket)) }
        when (val result = container.drawRepository.getDraw(ticket.lotteryType.value, ticket.issue.value)) {
            is DrawQueryResult.Success -> {
                if (generation == flowGeneration) {
                    val nextScreen =
                        if (container.usesRealDrawData) {
                            AppScreen.VerificationResult(
                                ticket = ticket,
                                drawResult = result.drawResult,
                                prizeCheckResult = container.prizeCalculator.calculate(ticket, result.drawResult),
                            )
                        } else {
                            AppScreen.DemoComplete(result.drawResult)
                        }
                    queryReturnScreen = null
                    mutableUiState.update { it.copy(screen = nextScreen) }
                }
            }

            is DrawQueryResult.Unavailable -> {
                if (generation == flowGeneration) {
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
    }

    /**
     * 按时间顺序查询一张多期票，并保留每一期独立状态。
     *
     * 明确尚未开奖、网络断开或数据源整体不可用时停止本轮后续请求；发布核对中和单期冲突只影响对应期次。
     */
    private suspend fun queryMultipleDraws(
        ticket: ConfirmedTicket,
        generation: Long,
        previousResults: List<PeriodVerification>? = null,
    ) {
        if (generation != flowGeneration) return
        val issues =
            when (
                val resolution =
                    issueSequenceResolver.resolve(
                        lotteryType = ticket.lotteryType.value,
                        firstIssue = ticket.issue.value,
                        periodCount = ticket.periodCount.value,
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
        val previousByIssue = previousResults.orEmpty().associateBy { it.issue }
        val targetIssues = selectMultiPeriodRetryTargets(issues, previousResults)
        val updatedByIssue = previousByIssue.toMutableMap()
        var stopResult: DrawQueryResult.Unavailable? = null
        var retryNotice: MultiPeriodRetryNotice? = null
        var completedCount = 0

        for (issue in issues) {
            if (generation != flowGeneration) return
            if (issue !in targetIssues) continue
            mutableUiState.update {
                it.copy(
                    screen =
                        AppScreen.DrawQuery(
                            ticket = ticket,
                            currentIssue = issue,
                            completedPeriodCount = completedCount,
                            totalPeriodCount = targetIssues.size,
                        ),
                )
            }
            val stopped = stopResult
            if (stopped != null) {
                if (previousByIssue[issue] !is PeriodVerification.Verified) {
                    updatedByIssue[issue] =
                        PeriodVerification.Unavailable(
                            issue = issue,
                            status = stopped.status,
                            message = stopped.skippedPeriodMessage(),
                            wasQueried = false,
                        )
                }
                completedCount += 1
                continue
            }

            when (val result = container.drawRepository.getDraw(ticket.lotteryType.value, issue)) {
                is DrawQueryResult.Success -> {
                    val periodTicket = ticket.copy(issue = ticket.issue.copy(value = issue))
                    updatedByIssue[issue] =
                        PeriodVerification.Verified(
                            issue = issue,
                            drawResult = result.drawResult,
                            prizeCheckResult = container.prizeCalculator.calculate(periodTicket, result.drawResult),
                        )
                }

                is DrawQueryResult.Unavailable -> {
                    val previous = previousByIssue[issue]
                    if (previous is PeriodVerification.Verified && result.status in PRESERVE_VERIFIED_STATUSES) {
                        updatedByIssue[issue] = previous
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

    /** 选择多期结果页本轮需要重新查询的期号。 */
    private fun selectMultiPeriodRetryTargets(
        issues: List<Issue>,
        previousResults: List<PeriodVerification>?,
    ): Set<Issue> {
        if (previousResults == null) return issues.toSet()
        val unfinished =
            previousResults
                .filter { it.requiresRetry }
                .mapTo(mutableSetOf()) { it.issue }
        return unfinished.ifEmpty { issues.toMutableSet() }
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
        val recognition =
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

        when (recognition) {
            is RecognitionResult.Failure -> {
                showError("识别失败", recognition.message, generation)
            }

            is RecognitionResult.PoorImage -> {
                showError("图片质量不足", recognition.issues.joinToString(separator = "；"), generation)
            }

            is RecognitionResult.Success -> {
                if (generation != flowGeneration) return
                mutableUiState.update {
                    it.copy(screen = AppScreen.Analysis("结构解析", "正在整理期号、号码和投注属性", 0.82f))
                }
                val parsed = container.ticketParser.parse(recognition.document)
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
        )

    /** 恢复有效上一级页面；没有可恢复页面时清理流程并回到首页。 */
    private suspend fun restorePreviousScreen(screen: AppScreen?) {
        if (screen == null || screen == AppScreen.Home) {
            navigateHome()
            return
        }
        flowGeneration += 1L
        clearTemporaryImage()
        queryReturnScreen = null
        mutableUiState.update { it.copy(screen = screen) }
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

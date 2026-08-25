package roc.win.lottery.app

import kotlinx.coroutines.CancellationException
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

    /** 最近一次提交查询的来源页，可以是已清理图片的确认页或记录列表。 */
    private var lastQuerySourceScreen: AppScreen? = null

    /** 当前查询被用户返回时需要恢复的来源页面。 */
    private var queryReturnScreen: AppScreen? = null

    /** 当前新确认流程对应的票据采集方式。 */
    private var currentAcquisitionSource: TicketAcquisitionSource? = null

    /** 当前新确认流程是否已经成功保存过一条记录。 */
    private var hasSavedCurrentFlow: Boolean = false

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

    /**
     * 从拍照或导图开始一次全新的本地分析。
     *
     * @param source 用户选择的图片来源。
     */
    suspend fun startAnalysis(source: ImageAcquisitionSource) {
        clearTemporaryImage()
        lastQuerySourceScreen = null
        queryReturnScreen = null
        val generation = ++flowGeneration
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

            AppScreen.NumberPicker,
            is AppScreen.Analysis,
            is AppScreen.Review,
            is AppScreen.Records,
            AppScreen.About,
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
        flowGeneration += 1L
        clearTemporaryImage()
        lastQuerySourceScreen = null
        queryReturnScreen = null
        resetConfirmationPersistence()
        resetTicketRecordManagement()
        mutableUiState.update { it.copy(screen = AppScreen.NumberPicker) }
    }

    /** 打开本机记录列表并结束此前的临时确认流程。 */
    suspend fun showTicketRecords() {
        if (container.ticketRecordStore == null) return
        flowGeneration += 1L
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
            queryDraw(record.ticket, generation)
        } finally {
            if (recordQueryGeneration == generation) {
                recordQueryGeneration = null
            }
        }
    }

    /** 打开关于与隐私页。 */
    fun showAbout() {
        mutableUiState.update { it.copy(screen = AppScreen.About) }
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
            if (store != null && !hasSavedCurrentFlow) {
                val acquisitionSource = currentAcquisitionSource
                if (acquisitionSource == null) {
                    showReviewSaveError("无法确定票据采集方式，请返回首页后重试", generation)
                    return
                }
                try {
                    store.save(ticket, acquisitionSource)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    showReviewSaveError("无法保存本机记录，请检查设备存储后重试", generation)
                    return
                }
                if (generation != flowGeneration) return
                hasSavedCurrentFlow = true
            }
            if (generation != flowGeneration) return
            lastQuerySourceScreen = review.forReturnNavigation()
            queryReturnScreen = lastQuerySourceScreen
            clearTemporaryImage()
            queryDraw(ticket, generation)
        } finally {
            if (confirmingGeneration == generation) {
                confirmingGeneration = null
            }
        }
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
        hasSavedCurrentFlow = false
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

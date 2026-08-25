package roc.win.lottery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import roc.win.lottery.app.AppContainer
import roc.win.lottery.app.AppScreen
import roc.win.lottery.app.LotteryAppController
import roc.win.lottery.app.MainDestination
import roc.win.lottery.app.TicketReviewAction
import roc.win.lottery.app.theme.LotteryTheme
import roc.win.lottery.app.ui.AboutScreen
import roc.win.lottery.app.ui.AnalysisScreen
import roc.win.lottery.app.ui.DemoCompleteScreen
import roc.win.lottery.app.ui.DrawQueryScreen
import roc.win.lottery.app.ui.DrawUnavailableScreen
import roc.win.lottery.app.ui.ErrorScreen
import roc.win.lottery.app.ui.HomeScreen
import roc.win.lottery.app.ui.MultiPeriodVerificationScreen
import roc.win.lottery.app.ui.RandomNumberScreen
import roc.win.lottery.app.ui.ReviewScreen
import roc.win.lottery.app.ui.TicketRecordsScreen
import roc.win.lottery.app.ui.VerificationResultScreen
import roc.win.lottery.persistence.StoredTicketRecord
import roc.win.lottery.recognition.ImageAcquisitionSource

/**
 * 四端共享应用入口。
 *
 * @param container 可由平台宿主替换的应用依赖，预览和未接入平台默认使用开发 Fake。
 */
@Composable
@Preview
fun App(container: AppContainer = remember { AppContainer.createDemo(getPlatform()) }) {
    val controller = remember(container) { LotteryAppController(container) }
    val uiState by controller.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val ticketRecordStore = container.ticketRecordStore
    val availableMainDestinations =
        remember(ticketRecordStore) {
            MainDestination.entries.filter { destination ->
                destination != MainDestination.RECORDS || ticketRecordStore != null
            }
        }
    val onMainDestinationSelected: (MainDestination) -> Unit = { destination ->
        scope.launch {
            when (destination) {
                MainDestination.VERIFICATION -> controller.navigateHome()
                MainDestination.NUMBER_PICKER -> controller.showRandomNumberPicker()
                MainDestination.RECORDS -> controller.showTicketRecords()
            }
        }
    }
    val recordCollection by
        produceState<TicketRecordCollectionState>(
            initialValue =
                if (ticketRecordStore == null) {
                    TicketRecordCollectionState.Unavailable
                } else {
                    TicketRecordCollectionState.Loading
                },
            key1 = ticketRecordStore,
        ) {
            if (ticketRecordStore == null) return@produceState
            try {
                ticketRecordStore.records.collect { records ->
                    value = TicketRecordCollectionState.Ready(records)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                value =
                    TicketRecordCollectionState.Failed(
                        "本机记录读取失败，请重新启动应用后重试",
                    )
            }
        }

    DisposableEffect(container) {
        onDispose { container.close() }
    }

    SystemBackHandler(enabled = uiState.screen != AppScreen.Home) {
        scope.launch { controller.navigateBack() }
    }

    LotteryTheme {
        when (val screen = uiState.screen) {
            AppScreen.Home -> {
                HomeScreen(
                    supportsCamera = container.imageAcquirer.supportsCamera,
                    isDemo = uiState.isDemo,
                    usesRealImageAcquisition = container.usesRealImageAcquisition,
                    usesRealRecognition = container.usesRealRecognition,
                    usesRealDrawData = container.usesRealDrawData,
                    availableMainDestinations = availableMainDestinations,
                    onMainDestinationSelected = onMainDestinationSelected,
                    onCamera = {
                        scope.launch { controller.startAnalysis(ImageAcquisitionSource.CAMERA) }
                    },
                    onImport = {
                        scope.launch { controller.startAnalysis(ImageAcquisitionSource.SYSTEM_PICKER) }
                    },
                    onManualEntry = {
                        scope.launch { controller.startManualEntry() }
                    },
                    onAbout = controller::showAbout,
                )
            }

            AppScreen.NumberPicker -> {
                RandomNumberScreen(
                    availableMainDestinations = availableMainDestinations,
                    onMainDestinationSelected = onMainDestinationSelected,
                )
            }

            is AppScreen.Records -> {
                TicketRecordsScreen(
                    screen = screen,
                    records =
                        (recordCollection as? TicketRecordCollectionState.Ready)
                            ?.records
                            .orEmpty(),
                    isLoading = recordCollection is TicketRecordCollectionState.Loading,
                    loadError =
                        (recordCollection as? TicketRecordCollectionState.Failed)
                            ?.message,
                    supportsFileExchange = container.ticketRecordFileExchange != null,
                    availableMainDestinations = availableMainDestinations,
                    onMainDestinationSelected = onMainDestinationSelected,
                    onFilterChange = controller::updateTicketRecordFilter,
                    onSearchChange = controller::updateTicketRecordSearch,
                    onRename = { id, displayName ->
                        scope.launch { controller.renameTicketRecord(id, displayName) }
                    },
                    onDelete = { id -> scope.launch { controller.deleteTicketRecord(id) } },
                    onQuery = { id -> scope.launch { controller.queryTicketRecord(id) } },
                    onClearAll = { scope.launch { controller.clearTicketRecords() } },
                    onExport = { scope.launch { controller.exportTicketRecords() } },
                    onImport = { scope.launch { controller.selectTicketRecordImport() } },
                    onConfirmImport = { keepLocalConflicts ->
                        scope.launch { controller.confirmTicketRecordImport(keepLocalConflicts) }
                    },
                    onDismissImport = controller::dismissTicketRecordImport,
                )
            }

            is AppScreen.Analysis -> {
                AnalysisScreen(
                    title = screen.title,
                    detail = screen.detail,
                    progress = screen.progress,
                    onCancel = { scope.launch { controller.navigateBack() } },
                )
            }

            is AppScreen.Review -> {
                ReviewScreen(
                    editor = screen.editor,
                    evaluation = screen.evaluation,
                    imageRef = screen.imageRef,
                    fieldRegions = screen.fieldRegions,
                    fieldCandidates = screen.fieldCandidates,
                    manualEntryReason = screen.manualEntryReason,
                    saveError = screen.saveError,
                    isDemo = uiState.isDemo,
                    usesRealRecognition = container.usesRealRecognition,
                    usesRealDrawData = container.usesRealDrawData,
                    onBack = { scope.launch { controller.navigateBack() } },
                    onLotteryTypeChange = {
                        controller.updateTicketReview(TicketReviewAction.ChangeLotteryType(it))
                    },
                    onIssueChange = {
                        controller.updateTicketReview(TicketReviewAction.ChangeIssue(it))
                    },
                    onNumberToggle = { lineIndex, area, number ->
                        controller.updateTicketReview(
                            TicketReviewAction.ToggleNumber(lineIndex, area, number),
                        )
                    },
                    onAddBetLine = {
                        controller.updateTicketReview(TicketReviewAction.AddBetLine)
                    },
                    onRemoveBetLine = { lineIndex ->
                        controller.updateTicketReview(TicketReviewAction.RemoveBetLine(lineIndex))
                    },
                    onMultiplierChange = {
                        controller.updateTicketReview(TicketReviewAction.ChangeMultiplier(it))
                    },
                    onPeriodCountChange = {
                        controller.updateTicketReview(TicketReviewAction.ChangePeriodCount(it))
                    },
                    onAdditionalChange = {
                        controller.updateTicketReview(TicketReviewAction.ChangeAdditional(it))
                    },
                    onPaidAmountChange = {
                        controller.updateTicketReview(TicketReviewAction.ChangePaidAmount(it))
                    },
                    onUseCalculatedAmount = {
                        controller.updateTicketReview(TicketReviewAction.UseCalculatedAmount)
                    },
                    onConfirm = { scope.launch { controller.confirmTicket() } },
                )
            }

            is AppScreen.DrawQuery -> {
                DrawQueryScreen(
                    issue = screen.currentIssue.value,
                    completedPeriodCount = screen.completedPeriodCount,
                    totalPeriodCount = screen.totalPeriodCount,
                    usesRealDrawData = container.usesRealDrawData,
                    onCancel = { scope.launch { controller.navigateBack() } },
                )
            }

            is AppScreen.DemoComplete -> {
                DemoCompleteScreen(
                    drawResult = screen.drawResult,
                    usesRealRecognition = container.usesRealRecognition,
                    onBack = { scope.launch { controller.navigateBack() } },
                    onDone = { scope.launch { controller.navigateHome() } },
                )
            }

            is AppScreen.VerificationResult -> {
                VerificationResultScreen(
                    ticket = screen.ticket,
                    drawResult = screen.drawResult,
                    prizeCheckResult = screen.prizeCheckResult,
                    onRetry = { scope.launch { controller.retryDrawQuery() } },
                    onBack = { scope.launch { controller.navigateBack() } },
                    onDone = { scope.launch { controller.navigateHome() } },
                )
            }

            is AppScreen.DrawUnavailable -> {
                DrawUnavailableScreen(
                    ticket = screen.ticket,
                    status = screen.status,
                    message = screen.message,
                    onRetry = { scope.launch { controller.retryDrawQuery() } },
                    onBack = { scope.launch { controller.navigateBack() } },
                    onDone = { scope.launch { controller.navigateHome() } },
                )
            }

            is AppScreen.MultiPeriodVerificationResult -> {
                MultiPeriodVerificationScreen(
                    result = screen,
                    onRetry = { scope.launch { controller.retryDrawQuery() } },
                    onBack = { scope.launch { controller.navigateBack() } },
                    onDone = { scope.launch { controller.navigateHome() } },
                )
            }

            is AppScreen.Error -> {
                ErrorScreen(
                    title = screen.title,
                    message = screen.message,
                    onBack = { scope.launch { controller.navigateBack() } },
                    onDone = { scope.launch { controller.navigateHome() } },
                )
            }

            AppScreen.About -> {
                AboutScreen(onBack = { scope.launch { controller.navigateBack() } })
            }
        }
    }
}

/** 共享票据仓库在 Compose 根节点中的只读收集状态。 */
private sealed interface TicketRecordCollectionState {
    /** 当前平台没有接入本机票据仓库。 */
    data object Unavailable : TicketRecordCollectionState

    /** 正在等待数据库首次返回记录。 */
    data object Loading : TicketRecordCollectionState

    /**
     * 数据库已经返回合法记录。
     *
     * @property records 按创建时间倒序排列的全部记录。
     */
    data class Ready(
        val records: List<StoredTicketRecord>,
    ) : TicketRecordCollectionState

    /**
     * 数据库读取失败且当前会话停止继续展示记录。
     *
     * @property message 不含敏感数据的恢复说明。
     */
    data class Failed(
        val message: String,
    ) : TicketRecordCollectionState
}

/** 在非首页注册系统返回事件，首页继续交由宿主决定是否退出应用。 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SystemBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    if (enabled) {
        BackHandler(onBack = onBack)
    }
}

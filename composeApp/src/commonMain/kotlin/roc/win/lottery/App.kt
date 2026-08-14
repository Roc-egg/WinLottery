package roc.win.lottery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.launch
import roc.win.lottery.app.AppContainer
import roc.win.lottery.app.AppScreen
import roc.win.lottery.app.LotteryAppController
import roc.win.lottery.app.TicketReviewAction
import roc.win.lottery.app.theme.LotteryTheme
import roc.win.lottery.app.ui.AboutScreen
import roc.win.lottery.app.ui.AnalysisScreen
import roc.win.lottery.app.ui.DemoCompleteScreen
import roc.win.lottery.app.ui.DrawQueryScreen
import roc.win.lottery.app.ui.DrawUnavailableScreen
import roc.win.lottery.app.ui.ErrorScreen
import roc.win.lottery.app.ui.HomeScreen
import roc.win.lottery.app.ui.ReviewScreen
import roc.win.lottery.app.ui.VerificationResultScreen
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

    LotteryTheme {
        when (val screen = uiState.screen) {
            AppScreen.Home -> {
                HomeScreen(
                    platformName = container.platform.name,
                    supportsCamera = container.imageAcquirer.supportsCamera,
                    isDemo = uiState.isDemo,
                    usesRealImageAcquisition = container.usesRealImageAcquisition,
                    usesRealRecognition = container.usesRealRecognition,
                    usesRealDrawData = container.usesRealDrawData,
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

            is AppScreen.Analysis -> {
                AnalysisScreen(
                    title = screen.title,
                    detail = screen.detail,
                    progress = screen.progress,
                    onCancel = { scope.launch { controller.navigateHome() } },
                )
            }

            is AppScreen.Review -> {
                ReviewScreen(
                    editor = screen.editor,
                    evaluation = screen.evaluation,
                    imageRef = screen.imageRef,
                    fieldRegions = screen.fieldRegions,
                    isDemo = uiState.isDemo,
                    usesRealRecognition = container.usesRealRecognition,
                    usesRealDrawData = container.usesRealDrawData,
                    onBack = { scope.launch { controller.navigateHome() } },
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
                    issue = screen.ticket.issue.value.value,
                    usesRealDrawData = container.usesRealDrawData,
                    onCancel = { scope.launch { controller.navigateHome() } },
                )
            }

            is AppScreen.DemoComplete -> {
                DemoCompleteScreen(
                    drawResult = screen.drawResult,
                    usesRealRecognition = container.usesRealRecognition,
                    onDone = { scope.launch { controller.navigateHome() } },
                )
            }

            is AppScreen.VerificationResult -> {
                VerificationResultScreen(
                    ticket = screen.ticket,
                    drawResult = screen.drawResult,
                    prizeCheckResult = screen.prizeCheckResult,
                    onRetry = { scope.launch { controller.retryDrawQuery() } },
                    onDone = { scope.launch { controller.navigateHome() } },
                )
            }

            is AppScreen.DrawUnavailable -> {
                DrawUnavailableScreen(
                    ticket = screen.ticket,
                    status = screen.status,
                    message = screen.message,
                    onRetry = { scope.launch { controller.retryDrawQuery() } },
                    onDone = { scope.launch { controller.navigateHome() } },
                )
            }

            is AppScreen.Error -> {
                ErrorScreen(
                    title = screen.title,
                    message = screen.message,
                    onBack = { scope.launch { controller.navigateHome() } },
                )
            }

            AppScreen.About -> {
                AboutScreen(onBack = { scope.launch { controller.navigateHome() } })
            }
        }
    }
}

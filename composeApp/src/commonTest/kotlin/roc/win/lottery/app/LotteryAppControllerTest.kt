package roc.win.lottery.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import roc.win.lottery.Platform
import roc.win.lottery.data.DrawQueryResult
import roc.win.lottery.data.DrawRepository
import roc.win.lottery.data.FakeDrawRepository
import roc.win.lottery.domain.BetLineDraft
import roc.win.lottery.domain.DrawPolicy
import roc.win.lottery.domain.DrawResult
import roc.win.lottery.domain.DrawStatus
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryPrizeCalculator
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.PrizeCheckStatus
import roc.win.lottery.domain.PrizeTier
import roc.win.lottery.domain.PrizeTierCodes
import roc.win.lottery.domain.RuleVersion
import roc.win.lottery.domain.SourceEvidence
import roc.win.lottery.domain.TicketDraft
import roc.win.lottery.domain.TicketValidator
import roc.win.lottery.recognition.AppPaths
import roc.win.lottery.recognition.ConservativeTicketParser
import roc.win.lottery.recognition.FakeAppPaths
import roc.win.lottery.recognition.FakeImageAcquirer
import roc.win.lottery.recognition.FakeTicketRecognizer
import roc.win.lottery.recognition.ImageAcquirer
import roc.win.lottery.recognition.ImageAcquisitionResult
import roc.win.lottery.recognition.ImageAcquisitionSource
import roc.win.lottery.recognition.ImageDimensionQualityAnalyzer
import roc.win.lottery.recognition.ImageQualityAnalyzer
import roc.win.lottery.recognition.ImageRef
import roc.win.lottery.recognition.NormalizedBounds
import roc.win.lottery.recognition.RecognitionResult
import roc.win.lottery.recognition.TicketFieldCandidate
import roc.win.lottery.recognition.TicketFieldReference
import roc.win.lottery.recognition.TicketFieldRegion
import roc.win.lottery.recognition.TicketParseResult
import roc.win.lottery.recognition.TicketParser
import roc.win.lottery.recognition.TicketRecognizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 应用确认闸门和流程状态测试。 */
class LotteryAppControllerTest {
    /** 手动大乐透必须逐项通过确认闸门，并按精确期号查询且不触碰图片生命周期。 */
    @Test
    fun manualSuperLottoRequiresExplicitFieldsBeforeExactQuery() =
        runTest {
            val repository = SequenceDrawRepository(DrawQueryResult.Success(verifiedDraw()))
            val paths = TrackingAppPaths()
            val imageAcquirer = TrackingImageAcquirer()
            val controller =
                createController(
                    repository = repository,
                    appPaths = paths,
                    usesRealDrawData = true,
                    imageAcquirer = imageAcquirer,
                )

            controller.startManualEntry()

            val initialReview = assertIs<AppScreen.Review>(controller.uiState.value.screen)
            assertNull(initialReview.imageRef)
            assertTrue(initialReview.fieldRegions.isEmpty())
            assertNull(initialReview.ocrEngineName)
            assertFalse(initialReview.evaluation.canConfirm)
            controller.updateTicketReview(TicketReviewAction.ChangeLotteryType(LotteryType.SUPER_LOTTO))
            controller.updateTicketReview(TicketReviewAction.ChangeIssue("26091"))
            enterManualLine(
                controller = controller,
                lineIndex = 0,
                primaryNumbers = listOf(2, 7, 14, 21, 33),
                secondaryNumbers = listOf(4, 9),
            )

            controller.confirmTicket()
            assertEquals(0, repository.queryCount)

            controller.updateTicketReview(TicketReviewAction.ChangeMultiplier(1))
            controller.confirmTicket()
            assertEquals(0, repository.queryCount)

            controller.updateTicketReview(TicketReviewAction.ChangeAdditional(true))
            controller.confirmTicket()
            assertEquals(0, repository.queryCount)

            controller.updateTicketReview(TicketReviewAction.UseCalculatedAmount)
            assertTrue(assertIs<AppScreen.Review>(controller.uiState.value.screen).evaluation.canConfirm)
            controller.confirmTicket()

            val result = assertIs<AppScreen.VerificationResult>(controller.uiState.value.screen)
            assertEquals(PrizeCheckStatus.WIN, result.prizeCheckResult.status)
            assertEquals(LotteryType.SUPER_LOTTO, repository.lastLotteryType)
            assertEquals(Issue("26091"), repository.lastIssue)
            assertEquals(1, repository.queryCount)
            assertEquals(0, imageAcquirer.acquisitionCount)
            assertTrue(paths.deletedImageIds.isEmpty())
        }

    /** 完整手动双色球应自动固定非追加属性，并按七位精确期号进入真实测算。 */
    @Test
    fun manualDoubleColorBallQueriesExactIssueWithoutImage() =
        runTest {
            val repository =
                SequenceDrawRepository(
                    DrawQueryResult.Success(verifiedDoubleColorBallDraw()),
                )
            val paths = TrackingAppPaths()
            val imageAcquirer = TrackingImageAcquirer()
            val controller =
                createController(
                    repository = repository,
                    appPaths = paths,
                    usesRealDrawData = true,
                    imageAcquirer = imageAcquirer,
                )
            controller.startManualEntry()
            controller.updateTicketReview(TicketReviewAction.ChangeLotteryType(LotteryType.DOUBLE_COLOR_BALL))
            controller.updateTicketReview(TicketReviewAction.ChangeIssue("2026092"))
            val lines =
                listOf(
                    listOf(1, 2, 3, 4, 5, 6) to listOf(7),
                    listOf(7, 8, 9, 10, 11, 12) to listOf(8),
                    listOf(13, 14, 15, 16, 17, 18) to listOf(9),
                    listOf(19, 20, 21, 22, 23, 24) to listOf(10),
                    listOf(25, 26, 27, 28, 29, 30) to listOf(11),
                )
            lines.forEachIndexed { index, (primaryNumbers, secondaryNumbers) ->
                if (index > 0) {
                    controller.updateTicketReview(TicketReviewAction.AddBetLine)
                }
                enterManualLine(
                    controller = controller,
                    lineIndex = index,
                    primaryNumbers = primaryNumbers,
                    secondaryNumbers = secondaryNumbers,
                )
            }
            controller.updateTicketReview(TicketReviewAction.ChangeMultiplier(1))
            controller.updateTicketReview(TicketReviewAction.UseCalculatedAmount)

            val review = assertIs<AppScreen.Review>(controller.uiState.value.screen)
            assertFalse(checkNotNull(review.editor.isAdditional))
            assertEquals(5, review.editor.betLines.size)
            assertEquals(1_000L, review.editor.calculatedAmountFen)
            assertTrue(review.evaluation.canConfirm)
            controller.confirmTicket()

            val result = assertIs<AppScreen.VerificationResult>(controller.uiState.value.screen)
            assertEquals(PrizeCheckStatus.WIN, result.prizeCheckResult.status)
            assertEquals(5, result.ticket.betLines.size)
            assertEquals(1_000L, result.ticket.paidAmountFen.value)
            assertEquals(
                PrizeTierCodes.FIRST,
                result.prizeCheckResult.lineResults
                    .first()
                    .prizeTierCode,
            )
            assertEquals(LotteryType.DOUBLE_COLOR_BALL, repository.lastLotteryType)
            assertEquals(Issue("2026092"), repository.lastIssue)
            assertEquals(1, repository.queryCount)
            assertEquals(0, imageAcquirer.acquisitionCount)
            assertTrue(paths.deletedImageIds.isEmpty())
        }

    /** 导入分析完成后必须停留在人工确认页，不能自动查询开奖。 */
    @Test
    fun analysisStopsAtReviewGate() =
        runTest {
            val repository = CountingDrawRepository()
            val controller = createController(repository)

            controller.startAnalysis(ImageAcquisitionSource.SYSTEM_PICKER)

            assertIs<AppScreen.Review>(controller.uiState.value.screen)
            assertEquals(0, repository.queryCount)
        }

    /** 用户确认且领域校验通过后才允许触发开奖查询。 */
    @Test
    fun confirmationTriggersSingleDrawQuery() =
        runTest {
            val repository = CountingDrawRepository()
            val controller = createController(repository)
            controller.startAnalysis(ImageAcquisitionSource.SYSTEM_PICKER)

            controller.confirmTicket()

            assertEquals(1, repository.queryCount)
            assertIs<AppScreen.DemoComplete>(controller.uiState.value.screen)
        }

    /** 真实 OCR 草稿通过人工校正闸门后可以继续到明确标注的演示开奖流程。 */
    @Test
    fun realRecognitionCanEnterCorrectionAndDemoDrawQuery() =
        runTest {
            val repository = CountingDrawRepository()
            val paths = TrackingAppPaths()
            val diagnostics = TrackingOcrConfidenceDiagnostics()
            val controller =
                createController(
                    repository = repository,
                    appPaths = paths,
                    usesRealRecognition = true,
                    ocrConfidenceDiagnostics = diagnostics,
                )
            controller.startAnalysis(ImageAcquisitionSource.SYSTEM_PICKER)

            val review = assertIs<AppScreen.Review>(controller.uiState.value.screen)
            assertTrue(review.evaluation.canConfirm)
            assertEquals("B1 Fake OCR", review.ocrEngineName)
            assertTrue(review.fieldRegions.all { it.rawConfidence == 1.0f })
            val diagnosticRecord = diagnostics.records.single()
            assertEquals("B1 Fake OCR", diagnosticRecord.first)
            assertEquals(OcrConfidenceOutcome.READY, diagnosticRecord.second.outcome)
            assertTrue(diagnosticRecord.second.fields.isNotEmpty())
            controller.confirmTicket()

            assertEquals(1, repository.queryCount)
            assertEquals(listOf("b1-demo-ticket"), paths.deletedImageIds)
            assertIs<AppScreen.DemoComplete>(controller.uiState.value.screen)
        }

    /** 真实开奖查询成功后必须执行本地规则计算并展示逐注结果。 */
    @Test
    fun verifiedDrawProducesRealPrizeResult() =
        runTest {
            val repository = SequenceDrawRepository(DrawQueryResult.Success(verifiedDraw()))
            val controller = createController(repository, usesRealDrawData = true)
            controller.startAnalysis(ImageAcquisitionSource.SYSTEM_PICKER)

            controller.confirmTicket()

            val screen = assertIs<AppScreen.VerificationResult>(controller.uiState.value.screen)
            assertEquals(PrizeCheckStatus.WIN, screen.prizeCheckResult.status)
            assertEquals(
                PrizeTierCodes.FIRST,
                screen.prizeCheckResult.lineResults
                    .single()
                    .prizeTierCode,
            )
            assertEquals(1_800_000L, screen.prizeCheckResult.estimatedPrizeFen)
            assertEquals("26091", screen.ticket.issue.value.value)
            assertEquals(1, repository.queryCount)
            assertEquals(LotteryType.SUPER_LOTTO, repository.lastLotteryType)
            assertEquals(Issue("26091"), repository.lastIssue)
        }

    /** 号码终态可以判断奖级，但奖金未终态时绝不能展示确定金额。 */
    @Test
    fun finalNumbersKeepsWinningAmountUnknown() =
        runTest {
            val draw = verifiedDraw().copy(status = DrawStatus.FINAL_NUMBERS)
            val controller =
                createController(
                    repository = SequenceDrawRepository(DrawQueryResult.Success(draw)),
                    usesRealDrawData = true,
                )
            controller.startAnalysis(ImageAcquisitionSource.SYSTEM_PICKER)

            controller.confirmTicket()

            val screen = assertIs<AppScreen.VerificationResult>(controller.uiState.value.screen)
            assertEquals(PrizeCheckStatus.WIN, screen.prizeCheckResult.status)
            assertEquals(null, screen.prizeCheckResult.estimatedPrizeFen)
            assertTrue(
                screen.prizeCheckResult.message
                    .orEmpty()
                    .contains("待官方数据确认"),
            )
        }

    /** 所有开奖不可用状态都必须保留票据，不能变成中奖结果或普通错误页。 */
    @Test
    fun unavailableDrawStatusesKeepTicketWithoutPrizeConclusion() =
        runTest {
            val statuses =
                listOf(
                    DrawStatus.NOT_PUBLISHED,
                    DrawStatus.PUBLISHING,
                    DrawStatus.NETWORK_UNAVAILABLE,
                    DrawStatus.SOURCE_UNAVAILABLE,
                    DrawStatus.CONFLICT,
                )

            statuses.forEach { status ->
                val repository = SequenceDrawRepository(DrawQueryResult.Unavailable(status, "测试不可用状态"))
                val controller = createController(repository, usesRealDrawData = true)
                controller.startAnalysis(ImageAcquisitionSource.SYSTEM_PICKER)

                controller.confirmTicket()

                val screen = assertIs<AppScreen.DrawUnavailable>(controller.uiState.value.screen)
                assertEquals(status, screen.status)
                assertEquals("26091", screen.ticket.issue.value.value)
                assertEquals(1, repository.queryCount)
            }
        }

    /** 官网暂时不可用后应使用原确认票据重试，并在成功后完成真实测算。 */
    @Test
    fun unavailableDrawCanRetryWithConfirmedTicket() =
        runTest {
            val repository =
                SequenceDrawRepository(
                    DrawQueryResult.Unavailable(DrawStatus.NETWORK_UNAVAILABLE, "测试网络不可用"),
                    DrawQueryResult.Success(verifiedDraw()),
                )
            val controller = createController(repository, usesRealDrawData = true)
            controller.startAnalysis(ImageAcquisitionSource.SYSTEM_PICKER)
            controller.confirmTicket()
            assertIs<AppScreen.DrawUnavailable>(controller.uiState.value.screen)

            controller.retryDrawQuery()

            val screen = assertIs<AppScreen.VerificationResult>(controller.uiState.value.screen)
            assertEquals("26091", screen.ticket.issue.value.value)
            assertEquals(PrizeCheckStatus.WIN, screen.prizeCheckResult.status)
            assertEquals(2, repository.queryCount)
        }

    /** 十期大乐透应逐期保留状态，并在首个未开奖期后停止后续官网请求。 */
    @Test
    fun multiPeriodTicketKeepsPerIssueResultsAndStopsAfterNotPublished() =
        runTest {
            val repository =
                SequenceDrawRepository(
                    DrawQueryResult.Unavailable(DrawStatus.PUBLISHING, "历史期辅助证据仍在核对"),
                    DrawQueryResult.Success(verifiedDraw()),
                    DrawQueryResult.Unavailable(DrawStatus.NOT_PUBLISHED, "该期开奖结果尚未发布"),
                )
            val draft =
                validDraft().copy(
                    issue = "26090",
                    periodCount = 10,
                    paidAmountFen = 3_000L,
                )
            val controller =
                createController(
                    repository = repository,
                    usesRealDrawData = true,
                    ticketParser = TicketParser { TicketParseResult.ReadyForReview(draft) },
                )
            controller.startAnalysis(ImageAcquisitionSource.SYSTEM_PICKER)

            controller.confirmTicket()

            val screen = assertIs<AppScreen.MultiPeriodVerificationResult>(controller.uiState.value.screen)
            assertEquals(10, screen.periodResults.size)
            assertEquals(1, screen.verifiedPeriodCount)
            assertEquals(9, screen.unresolvedPeriodCount)
            assertFalse(screen.isConclusive)
            assertNull(screen.estimatedPrizeFen)
            assertEquals(listOf(Issue("26090"), Issue("26091"), Issue("26092")), repository.queriedIssues)
            assertTrue(assertIs<PeriodVerification.Unavailable>(screen.periodResults[0]).wasQueried)
            assertIs<PeriodVerification.Verified>(screen.periodResults[1])
            assertTrue(assertIs<PeriodVerification.Unavailable>(screen.periodResults[2]).wasQueried)
            assertFalse(assertIs<PeriodVerification.Unavailable>(screen.periodResults[3]).wasQueried)
        }

    /** 多期查询断网恢复时只补查未完成期，并在全部完成后支持再次全量刷新。 */
    @Test
    fun multiPeriodNetworkRecoveryPreservesCompletedIssuesAndResumesInOrder() =
        runTest {
            val repository = RecoverableMultiPeriodRepository()
            val draft =
                validDraft().copy(
                    issue = "26090",
                    periodCount = 3,
                    paidAmountFen = 900L,
                )
            val controller =
                createController(
                    repository = repository,
                    usesRealDrawData = true,
                    ticketParser = TicketParser { TicketParseResult.ReadyForReview(draft) },
                )
            controller.startAnalysis(ImageAcquisitionSource.SYSTEM_PICKER)

            controller.confirmTicket()

            val interrupted = assertIs<AppScreen.MultiPeriodVerificationResult>(controller.uiState.value.screen)
            val completedFirstPeriod = assertIs<PeriodVerification.Verified>(interrupted.periodResults[0])
            assertEquals(1, interrupted.verifiedPeriodCount)
            assertEquals(2, interrupted.unresolvedPeriodCount)
            assertTrue(assertIs<PeriodVerification.Unavailable>(interrupted.periodResults[1]).wasQueried)
            assertFalse(assertIs<PeriodVerification.Unavailable>(interrupted.periodResults[2]).wasQueried)
            assertEquals(listOf("26090", "26091"), repository.queriedIssues.map { it.value })

            controller.retryDrawQuery()

            val recovered = assertIs<AppScreen.MultiPeriodVerificationResult>(controller.uiState.value.screen)
            assertTrue(recovered.isConclusive)
            assertEquals(3, recovered.verifiedPeriodCount)
            assertEquals(completedFirstPeriod, recovered.periodResults[0])
            assertEquals(
                listOf("26090", "26091", "26091", "26092"),
                repository.queriedIssues.map { it.value },
            )

            repository.scheduleNetworkFailure()
            controller.retryDrawQuery()

            val preserved = assertIs<AppScreen.MultiPeriodVerificationResult>(controller.uiState.value.screen)
            assertTrue(preserved.isConclusive)
            assertEquals(recovered.periodResults, preserved.periodResults)
            assertEquals(DrawStatus.NETWORK_UNAVAILABLE, preserved.retryNotice?.status)
            assertEquals(
                listOf("26090", "26091", "26091", "26092", "26090"),
                repository.queriedIssues.map { it.value },
            )

            controller.retryDrawQuery()

            val refreshed = assertIs<AppScreen.MultiPeriodVerificationResult>(controller.uiState.value.screen)
            assertTrue(refreshed.isConclusive)
            assertNull(refreshed.retryNotice)
            assertEquals(
                listOf("26090", "26091", "26091", "26092", "26090", "26090", "26091", "26092"),
                repository.queriedIssues.map { it.value },
            )
        }

    /** 所有多期期次均完成时才允许安全汇总整票奖金。 */
    @Test
    fun completedMultiPeriodTicketAggregatesAllPeriodPrizes() =
        runTest {
            val repository = IssueAwareSuccessRepository()
            val draft =
                validDraft().copy(
                    issue = "26090",
                    periodCount = 3,
                    paidAmountFen = 900L,
                )
            val controller =
                createController(
                    repository = repository,
                    usesRealDrawData = true,
                    ticketParser = TicketParser { TicketParseResult.ReadyForReview(draft) },
                )
            controller.startAnalysis(ImageAcquisitionSource.SYSTEM_PICKER)

            controller.confirmTicket()

            val screen = assertIs<AppScreen.MultiPeriodVerificationResult>(controller.uiState.value.screen)
            assertTrue(screen.isConclusive)
            assertTrue(screen.hasWinningPeriod)
            assertEquals(5_400_000L, screen.estimatedPrizeFen)
            assertEquals(listOf("26090", "26091", "26092"), repository.queriedIssues.map { it.value })
        }

    /** 多期票已确认奖级但奖金未完整时，断网重查不得抹掉既有证据。 */
    @Test
    fun multiPeriodWinningTiersWithoutPayoutRemainRetryable() =
        runTest {
            val repository = IssueAwareSuccessRepository(DrawStatus.FINAL_NUMBERS)
            val draft =
                validDraft().copy(
                    issue = "26090",
                    periodCount = 2,
                    paidAmountFen = 600L,
                )
            val controller =
                createController(
                    repository = repository,
                    usesRealDrawData = true,
                    ticketParser = TicketParser { TicketParseResult.ReadyForReview(draft) },
                )
            controller.startAnalysis(ImageAcquisitionSource.SYSTEM_PICKER)
            controller.confirmTicket()

            val firstResult = assertIs<AppScreen.MultiPeriodVerificationResult>(controller.uiState.value.screen)
            assertTrue(firstResult.isConclusive)
            assertEquals(2, firstResult.unresolvedPeriodCount)
            assertNull(firstResult.estimatedPrizeFen)

            repository.scheduleUnavailable(DrawStatus.NETWORK_UNAVAILABLE)
            controller.retryDrawQuery()

            val preserved = assertIs<AppScreen.MultiPeriodVerificationResult>(controller.uiState.value.screen)
            assertEquals(firstResult.periodResults, preserved.periodResults)
            assertEquals(DrawStatus.NETWORK_UNAVAILABLE, preserved.retryNotice?.status)
            assertEquals(
                listOf("26090", "26091", "26090"),
                repository.queriedIssues.map { it.value },
            )

            controller.retryDrawQuery()

            val recovered = assertIs<AppScreen.MultiPeriodVerificationResult>(controller.uiState.value.screen)
            assertNull(recovered.retryNotice)
            assertEquals(
                listOf("26090", "26091", "26090", "26090", "26091"),
                repository.queriedIssues.map { it.value },
            )
        }

    /** 非法人工校正必须停留在当前页面，且不得查询开奖或清理票图。 */
    @Test
    fun invalidCorrectionStaysAtReviewGate() =
        runTest {
            val repository = CountingDrawRepository()
            val paths = TrackingAppPaths()
            val controller = createController(repository, paths, usesRealRecognition = true)
            controller.startAnalysis(ImageAcquisitionSource.SYSTEM_PICKER)

            controller.updateTicketReview(TicketReviewAction.ChangeIssue("2609"))
            val review = assertIs<AppScreen.Review>(controller.uiState.value.screen)
            assertFalse(review.evaluation.canConfirm)
            assertTrue(review.evaluation.problems.any { it.field == "issue" })

            controller.confirmTicket()

            assertEquals(0, repository.queryCount)
            assertTrue(paths.deletedImageIds.isEmpty())
            assertIs<AppScreen.Review>(controller.uiState.value.screen)
        }

    /** 返回首页应清除当前流程页面状态。 */
    @Test
    fun navigatingHomeClearsFlowState() =
        runTest {
            val paths = TrackingAppPaths()
            val controller = createController(CountingDrawRepository(), paths)
            controller.startAnalysis(ImageAcquisitionSource.SYSTEM_PICKER)

            controller.navigateHome()

            assertIs<AppScreen.Home>(controller.uiState.value.screen)
            assertTrue(controller.uiState.value.isDemo)
            assertEquals(listOf("b1-demo-ticket"), paths.deletedImageIds)
        }

    /** 结果页返回应恢复票面确认页，但不能重新引用已经清理的临时图片。 */
    @Test
    fun resultBackRestoresReviewWithoutDeletedImage() =
        runTest {
            val paths = TrackingAppPaths()
            val controller =
                createController(
                    repository = SequenceDrawRepository(DrawQueryResult.Success(verifiedDraw())),
                    appPaths = paths,
                    usesRealDrawData = true,
                )
            controller.startAnalysis(ImageAcquisitionSource.SYSTEM_PICKER)
            assertIs<AppScreen.Review>(controller.uiState.value.screen)
            controller.confirmTicket()
            assertIs<AppScreen.VerificationResult>(controller.uiState.value.screen)

            controller.navigateBack()

            val restored = assertIs<AppScreen.Review>(controller.uiState.value.screen)
            assertEquals("26091", restored.editor.issue.value)
            assertTrue(restored.evaluation.canConfirm)
            assertNull(restored.imageRef)
            assertTrue(restored.fieldRegions.isEmpty())
            assertNull(restored.ocrEngineName)
            assertEquals(listOf("b1-demo-ticket"), paths.deletedImageIds)
        }

    /** 查询中返回应恢复确认页，并丢弃随后到达的网络结果。 */
    @Test
    fun queryBackRestoresReviewAndIgnoresLateResult() =
        runTest {
            val repository = SuspendedDrawRepository()
            val controller = createController(repository, usesRealDrawData = true)
            controller.startAnalysis(ImageAcquisitionSource.SYSTEM_PICKER)
            val queryJob = launch { controller.confirmTicket() }
            repository.started.await()
            assertIs<AppScreen.DrawQuery>(controller.uiState.value.screen)

            controller.navigateBack()
            assertIs<AppScreen.Review>(controller.uiState.value.screen)
            repository.completion.complete(DrawQueryResult.Success(verifiedDraw()))
            queryJob.join()

            assertIs<AppScreen.Review>(controller.uiState.value.screen)
        }

    /** 多期查询中返回应丢弃在途结果，并且不得继续请求后续期次。 */
    @Test
    fun multiPeriodQueryBackStopsLaterIssuesAndIgnoresLateResult() =
        runTest {
            val repository = SuspendedDrawRepository()
            val draft =
                validDraft().copy(
                    issue = "26090",
                    periodCount = 3,
                    paidAmountFen = 900L,
                )
            val controller =
                createController(
                    repository = repository,
                    usesRealDrawData = true,
                    ticketParser = TicketParser { TicketParseResult.ReadyForReview(draft) },
                )
            controller.startAnalysis(ImageAcquisitionSource.SYSTEM_PICKER)
            val queryJob = launch { controller.confirmTicket() }
            repository.started.await()
            val query = assertIs<AppScreen.DrawQuery>(controller.uiState.value.screen)
            assertEquals(Issue("26090"), query.currentIssue)

            controller.navigateBack()
            val restored = assertIs<AppScreen.Review>(controller.uiState.value.screen)
            assertEquals("26090", restored.editor.issue.value)
            repository.completion.complete(
                DrawQueryResult.Success(verifiedDraw().copy(issue = Issue("26090"))),
            )
            queryJob.join()

            assertEquals(restored, assertIs<AppScreen.Review>(controller.uiState.value.screen))
            assertEquals(listOf(Issue("26090")), repository.queriedIssues)
            assertEquals(1, repository.queryCount)
        }

    /** 结果页发起重查后返回应恢复原结果，而不是跳过上一级回到确认页。 */
    @Test
    fun retryQueryBackRestoresPreviousResult() =
        runTest {
            val repository =
                SuspendedDrawRepository(
                    initialResult = DrawQueryResult.Success(verifiedDraw()),
                )
            val controller = createController(repository, usesRealDrawData = true)
            controller.startAnalysis(ImageAcquisitionSource.SYSTEM_PICKER)
            controller.confirmTicket()
            val previousResult = assertIs<AppScreen.VerificationResult>(controller.uiState.value.screen)
            val retryJob = launch { controller.retryDrawQuery() }
            repository.started.await()
            assertIs<AppScreen.DrawQuery>(controller.uiState.value.screen)

            controller.navigateBack()
            assertEquals(previousResult, assertIs<AppScreen.VerificationResult>(controller.uiState.value.screen))
            repository.completion.complete(DrawQueryResult.Success(verifiedDraw()))
            retryJob.join()

            assertEquals(previousResult, assertIs<AppScreen.VerificationResult>(controller.uiState.value.screen))
        }

    /** 关于页返回应回到首页，首页内的返回调用不改变状态。 */
    @Test
    fun aboutBackReturnsHomeAndHomeBackIsNoOp() =
        runTest {
            val controller = createController(CountingDrawRepository())
            controller.showAbout()
            assertIs<AppScreen.About>(controller.uiState.value.screen)

            controller.navigateBack()
            assertIs<AppScreen.Home>(controller.uiState.value.screen)
            controller.navigateBack()

            assertIs<AppScreen.Home>(controller.uiState.value.screen)
        }

    /** OCR 失败进入错误页前必须清理已经落盘的临时图片。 */
    @Test
    fun recognitionFailureClearsTemporaryImage() =
        runTest {
            val paths = TrackingAppPaths()
            val recognizer = TicketRecognizer { RecognitionResult.Failure("脱敏测试失败") }
            val controller = createController(CountingDrawRepository(), paths, ticketRecognizer = recognizer)

            controller.startAnalysis(ImageAcquisitionSource.SYSTEM_PICKER)

            assertIs<AppScreen.Error>(controller.uiState.value.screen)
            assertEquals(listOf("b1-demo-ticket"), paths.deletedImageIds)
        }

    /** 分辨率不足时必须在 OCR 前阻断，并清理已经落盘的临时图片。 */
    @Test
    fun lowResolutionStopsBeforeRecognitionAndClearsTemporaryImage() =
        runTest {
            val paths = TrackingAppPaths()
            var recognitionCount = 0
            val recognizer =
                TicketRecognizer {
                    recognitionCount += 1
                    RecognitionResult.Failure("不应执行到 OCR")
                }
            val lowResolutionAcquirer =
                object : ImageAcquirer {
                    /** 测试采集器不提供相机。 */
                    override val supportsCamera: Boolean = false

                    /** 返回短边低于质量下限的虚拟图片。 */
                    override suspend fun acquire(source: ImageAcquisitionSource): ImageAcquisitionResult =
                        ImageAcquisitionResult.Success(
                            ImageRef(
                                id = "low-resolution-image",
                                localPath = "memory://low-resolution-image.jpg",
                                mimeType = "image/jpeg",
                                widthPixels = 640,
                                heightPixels = 1280,
                            ),
                        )
                }
            val controller =
                createController(
                    repository = CountingDrawRepository(),
                    appPaths = paths,
                    ticketRecognizer = recognizer,
                    imageAcquirer = lowResolutionAcquirer,
                )

            controller.startAnalysis(ImageAcquisitionSource.SYSTEM_PICKER)

            val error = assertIs<AppScreen.Error>(controller.uiState.value.screen)
            assertEquals("图片质量不足", error.title)
            assertEquals(0, recognitionCount)
            assertEquals(listOf("low-resolution-image"), paths.deletedImageIds)
        }

    /** 可恢复解析结果应进入校正页并保留临时图片，补齐字段后才能确认。 */
    @Test
    fun recoverableParseResultKeepsImageAtReviewGate() =
        runTest {
            val paths = TrackingAppPaths()
            val parser =
                TicketParser {
                    TicketParseResult.NeedsCorrection(
                        message = "期号缺失，请人工补充",
                        draft = validDraft().copy(issue = ""),
                        fieldRegions =
                            listOf(
                                TicketFieldRegion(
                                    field = TicketFieldReference.BetLine(0),
                                    bounds = NormalizedBounds(0.1f, 0.4f, 0.9f, 0.5f),
                                ),
                            ),
                    )
                }
            val controller =
                createController(
                    repository = CountingDrawRepository(),
                    appPaths = paths,
                    ticketParser = parser,
                )

            controller.startAnalysis(ImageAcquisitionSource.SYSTEM_PICKER)

            val review = assertIs<AppScreen.Review>(controller.uiState.value.screen)
            assertFalse(review.evaluation.canConfirm)
            assertTrue(review.evaluation.problems.any { it.field == "issue" })
            assertEquals(TicketFieldReference.BetLine(0), review.fieldRegions.single().field)
            assertTrue(paths.deletedImageIds.isEmpty())

            controller.updateTicketReview(TicketReviewAction.ChangeIssue("26091"))

            assertTrue(assertIs<AppScreen.Review>(controller.uiState.value.screen).evaluation.canConfirm)
            assertTrue(paths.deletedImageIds.isEmpty())
        }

    /** 倍数和追加均待确认时，控制器必须在两项都由用户选择后才解除确认闸门。 */
    @Test
    fun unresolvedTicketPropertiesRequireSeparateUserSelections() =
        runTest {
            val baseDraft = validDraft()
            val unresolvedDraft =
                baseDraft.copy(
                    betLines = baseDraft.betLines.map { it.copy(isAdditional = null) },
                    multiplier = null,
                )
            val parser =
                TicketParser {
                    TicketParseResult.NeedsCorrection(
                        message = "倍数和追加属性待确认",
                        draft = unresolvedDraft,
                    )
                }
            val controller =
                createController(
                    repository = CountingDrawRepository(),
                    ticketParser = parser,
                )

            controller.startAnalysis(ImageAcquisitionSource.SYSTEM_PICKER)

            val initialReview = assertIs<AppScreen.Review>(controller.uiState.value.screen)
            assertFalse(initialReview.evaluation.canConfirm)
            assertTrue(initialReview.evaluation.problems.any { it.field == "multiplier" })
            assertTrue(initialReview.evaluation.problems.any { it.field == "isAdditional" })

            controller.updateTicketReview(TicketReviewAction.ChangeMultiplier(1))

            val multiplierReview = assertIs<AppScreen.Review>(controller.uiState.value.screen)
            assertFalse(multiplierReview.evaluation.canConfirm)
            assertFalse(multiplierReview.evaluation.problems.any { it.field == "multiplier" })
            assertTrue(multiplierReview.evaluation.problems.any { it.field == "isAdditional" })

            controller.updateTicketReview(TicketReviewAction.ChangeAdditional(true))

            assertTrue(assertIs<AppScreen.Review>(controller.uiState.value.screen).evaluation.canConfirm)
        }

    /** 不带安全草稿的人工修正结果应保留原图并转为空白手动录入。 */
    @Test
    fun unsafeDraftFallsBackToImageAssistedManualEntry() =
        runTest {
            val paths = TrackingAppPaths()
            val parser =
                TicketParser {
                    TicketParseResult.NeedsCorrection(
                        message = "识别到多个开奖期号，请对照原图明确选择",
                        fieldRegions =
                            listOf(
                                TicketFieldRegion(
                                    field = TicketFieldReference.Issue,
                                    bounds = NormalizedBounds(0.1f, 0.2f, 0.8f, 0.3f),
                                ),
                            ),
                        fieldCandidates =
                            listOf(
                                TicketFieldCandidate.Issue("26091"),
                                TicketFieldCandidate.Issue("26092"),
                            ),
                    )
                }
            val controller =
                createController(
                    repository = CountingDrawRepository(),
                    appPaths = paths,
                    ticketParser = parser,
                )

            controller.startAnalysis(ImageAcquisitionSource.SYSTEM_PICKER)

            val review = assertIs<AppScreen.Review>(controller.uiState.value.screen)
            assertEquals("识别到多个开奖期号，请对照原图明确选择", review.manualEntryReason)
            assertEquals("b1-demo-ticket", review.imageRef?.id)
            assertNull(review.editor.lotteryType.value)
            assertEquals(1, review.editor.betLines.size)
            assertEquals(TicketFieldReference.Issue, review.fieldRegions.single().field)
            assertNull(review.fieldRegions.single().rawConfidence)
            assertEquals("B1 Fake OCR", review.ocrEngineName)
            assertEquals(
                listOf(TicketFieldCandidate.Issue("26091"), TicketFieldCandidate.Issue("26092")),
                review.fieldCandidates,
            )
            assertFalse(review.evaluation.canConfirm)
            assertTrue(paths.deletedImageIds.isEmpty())

            controller.updateTicketReview(TicketReviewAction.ChangeIssue("26091"))
            val selectedReview = assertIs<AppScreen.Review>(controller.uiState.value.screen)
            assertEquals("26091", selectedReview.editor.issue.value)
            assertFalse(selectedReview.evaluation.canConfirm)

            controller.updateTicketReview(TicketReviewAction.AddBetLine)
            assertEquals(2, assertIs<AppScreen.Review>(controller.uiState.value.screen).editor.betLines.size)

            controller.navigateHome()
            assertEquals(listOf("b1-demo-ticket"), paths.deletedImageIds)
        }

    /** 创建使用可计数仓库和真实保守解析器的开发控制器。 */
    private fun createController(
        repository: DrawRepository,
        appPaths: AppPaths = FakeAppPaths(),
        usesRealRecognition: Boolean = false,
        usesRealDrawData: Boolean = false,
        ticketRecognizer: TicketRecognizer = FakeTicketRecognizer(),
        imageAcquirer: ImageAcquirer = FakeImageAcquirer(supportsCamera = false),
        imageQualityAnalyzer: ImageQualityAnalyzer = ImageDimensionQualityAnalyzer(),
        ticketParser: TicketParser = ConservativeTicketParser(),
        ocrConfidenceDiagnostics: OcrConfidenceDiagnostics = OcrConfidenceDiagnostics.Disabled,
    ): LotteryAppController {
        val platform =
            object : Platform {
                /** 测试平台名称。 */
                override val name: String = "测试平台"

                /** 测试平台仅走系统图片导入。 */
                override val supportsCamera: Boolean = false
            }
        return LotteryAppController(
            AppContainer(
                platform = platform,
                imageAcquirer = imageAcquirer,
                imageQualityAnalyzer = imageQualityAnalyzer,
                ticketRecognizer = ticketRecognizer,
                ticketParser = ticketParser,
                drawRepository = repository,
                prizeCalculator = LotteryPrizeCalculator(),
                appPaths = appPaths,
                ticketValidator = TicketValidator(),
                isDemo = true,
                usesRealImageAcquisition = false,
                usesRealRecognition = usesRealRecognition,
                usesRealDrawData = usesRealDrawData,
                ocrConfidenceDiagnostics = ocrConfidenceDiagnostics,
            ),
        )
    }

    /** 创建控制器测试共用的合法大乐透草稿。 */
    private fun validDraft(): TicketDraft =
        TicketDraft(
            lotteryType = LotteryType.SUPER_LOTTO,
            issue = "26091",
            betLines =
                listOf(
                    BetLineDraft(
                        primaryNumbers = listOf(2, 7, 14, 21, 33),
                        secondaryNumbers = listOf(4, 9),
                        isAdditional = true,
                        originalText = "02 07 14 21 33 + 04 09",
                    ),
                ),
            multiplier = 1,
            periodCount = 1,
            paidAmountFen = 300L,
        )

    /** 向手动录入页的指定行写入一注号码。 */
    private fun enterManualLine(
        controller: LotteryAppController,
        lineIndex: Int,
        primaryNumbers: List<Int>,
        secondaryNumbers: List<Int>,
    ) {
        primaryNumbers.forEach { number ->
            controller.updateTicketReview(
                TicketReviewAction.ToggleNumber(lineIndex, TicketNumberArea.PRIMARY, number),
            )
        }
        secondaryNumbers.forEach { number ->
            controller.updateTicketReview(
                TicketReviewAction.ToggleNumber(lineIndex, TicketNumberArea.SECONDARY, number),
            )
        }
    }

    /** 创建与合法测试票完全匹配的双证据大乐透开奖结果。 */
    private fun verifiedDraw(): DrawResult =
        DrawResult(
            lotteryType = LotteryType.SUPER_LOTTO,
            issue = Issue("26091"),
            drawDate = "2026-08-12",
            primaryNumbers = listOf(2, 7, 14, 21, 33),
            secondaryNumbers = listOf(4, 9),
            status = DrawStatus.FINAL_PAYOUT,
            revision = 1,
            ruleVersion = RuleVersion.DLT_2026_01.code,
            policy = DrawPolicy.STANDARD,
            prizeTiers =
                listOf(
                    PrizeTier(
                        code = PrizeTierCodes.FIRST,
                        displayName = "一等奖",
                        singlePrizeFen = 1_000_000L,
                        additionalPrizeFen = 800_000L,
                    ),
                    PrizeTier(
                        code = PrizeTierCodes.SECOND,
                        displayName = "二等奖",
                        singlePrizeFen = 500_000L,
                        additionalPrizeFen = 400_000L,
                    ),
                    PrizeTier(PrizeTierCodes.THIRD, "三等奖", 666_600L, null),
                    PrizeTier(PrizeTierCodes.FOURTH, "四等奖", 38_000L, null),
                    PrizeTier(PrizeTierCodes.FIFTH, "五等奖", 20_000L, null),
                    PrizeTier(PrizeTierCodes.SIXTH, "六等奖", 1_800L, null),
                    PrizeTier(PrizeTierCodes.SEVENTH, "七等奖", 700L, null),
                ),
            evidence =
                SourceEvidence(
                    sourceName = "测试主源",
                    sourceUrl = "https://example.invalid/main",
                    fetchedAtEpochMillis = 1L,
                    contentSha256 = "main-hash",
                ),
            supportingEvidence =
                listOf(
                    SourceEvidence(
                        sourceName = "测试辅助源",
                        sourceUrl = "https://example.invalid/supporting",
                        fetchedAtEpochMillis = 1L,
                        contentSha256 = "supporting-hash",
                    ),
                ),
        )

    /** 创建与手动测试票完全匹配的双证据双色球开奖结果。 */
    private fun verifiedDoubleColorBallDraw(): DrawResult =
        DrawResult(
            lotteryType = LotteryType.DOUBLE_COLOR_BALL,
            issue = Issue("2026092"),
            drawDate = "2026-08-13",
            primaryNumbers = listOf(1, 2, 3, 4, 5, 6),
            secondaryNumbers = listOf(7),
            status = DrawStatus.FINAL_PAYOUT,
            revision = 1,
            ruleVersion = RuleVersion.SSQ_2026_01.code,
            policy = DrawPolicy.STANDARD,
            prizeTiers =
                listOf(
                    PrizeTier(
                        code = PrizeTierCodes.FIRST,
                        displayName = "一等奖",
                        singlePrizeFen = 1_000_000_000L,
                        additionalPrizeFen = null,
                    ),
                ),
            evidence =
                SourceEvidence(
                    sourceName = "测试主源",
                    sourceUrl = "https://example.invalid/main",
                    fetchedAtEpochMillis = 1L,
                    contentSha256 = "main-hash",
                ),
            supportingEvidence =
                listOf(
                    SourceEvidence(
                        sourceName = "测试辅助源",
                        sourceUrl = "https://example.invalid/supporting",
                        fetchedAtEpochMillis = 1L,
                        contentSha256 = "supporting-hash",
                    ),
                ),
        )

    /** 按顺序返回预置查询结果并记录查询次数。 */
    private class SequenceDrawRepository(
        vararg results: DrawQueryResult,
    ) : DrawRepository {
        /** 查询时依次消费的预置结果。 */
        private val results = results.toList().also { require(it.isNotEmpty()) }

        /** 已触发的单期查询次数。 */
        var queryCount: Int = 0
            private set

        /** 最近一次查询收到的彩种。 */
        var lastLotteryType: LotteryType? = null
            private set

        /** 最近一次查询收到的精确期号。 */
        var lastIssue: Issue? = null
            private set

        /** 按实际调用顺序保存的精确期号。 */
        val queriedIssues = mutableListOf<Issue>()

        /** 返回当前预置结果，超出数量时继续返回最后一项。 */
        override suspend fun getDraw(
            lotteryType: LotteryType,
            issue: Issue,
        ): DrawQueryResult {
            lastLotteryType = lotteryType
            lastIssue = issue
            queriedIssues += issue
            val result = results[minOf(queryCount, results.lastIndex)]
            queryCount += 1
            return result
        }
    }

    /** 由测试控制完成时机的开奖仓库。 */
    private class SuspendedDrawRepository(
        /** 首次查询无需暂停时立即返回的结果。 */
        private val initialResult: DrawQueryResult? = null,
    ) : DrawRepository {
        /** 已收到的查询次数。 */
        var queryCount: Int = 0
            private set

        /** 按实际调用顺序保存的精确期号。 */
        val queriedIssues = mutableListOf<Issue>()

        /** 第一次查询已经进入仓库的信号。 */
        val started = CompletableDeferred<Unit>()

        /** 测试稍后提供的查询结果。 */
        val completion = CompletableDeferred<DrawQueryResult>()

        /** 发出开始信号并等待测试提供结果。 */
        override suspend fun getDraw(
            lotteryType: LotteryType,
            issue: Issue,
        ): DrawQueryResult {
            queryCount += 1
            queriedIssues += issue
            if (queryCount == 1 && initialResult != null) return initialResult
            started.complete(Unit)
            return completion.await()
        }
    }

    /** 为收到的每个期号生成匹配的双证据成功结果。 */
    private inner class IssueAwareSuccessRepository(
        /** 每一期返回的开奖完整状态。 */
        private val drawStatus: DrawStatus = DrawStatus.FINAL_PAYOUT,
    ) : DrawRepository {
        /** 按实际调用顺序保存的精确期号。 */
        val queriedIssues = mutableListOf<Issue>()

        /** 下一次调用需要返回的瞬时不可用状态。 */
        private var nextUnavailableStatus: DrawStatus? = null

        /** 安排下一次仓库调用返回指定的不可用状态。 */
        fun scheduleUnavailable(status: DrawStatus) {
            nextUnavailableStatus = status
        }

        /** 返回期号与请求严格一致的测试开奖。 */
        override suspend fun getDraw(
            lotteryType: LotteryType,
            issue: Issue,
        ): DrawQueryResult {
            queriedIssues += issue
            nextUnavailableStatus?.let { status ->
                nextUnavailableStatus = null
                return DrawQueryResult.Unavailable(status, "测试瞬时不可用")
            }
            return DrawQueryResult.Success(verifiedDraw().copy(issue = issue, status = drawStatus))
        }
    }

    /** 首轮第二次查询模拟断网，后续查询按请求期号恢复成功。 */
    private inner class RecoverableMultiPeriodRepository : DrawRepository {
        /** 按实际调用顺序保存的精确期号。 */
        val queriedIssues = mutableListOf<Issue>()

        /** 已收到的查询次数，用于只在首轮第二期制造一次网络失败。 */
        private var queryCount: Int = 0

        /** 是否需要让下一次主动重查模拟网络不可用。 */
        private var shouldFailNextQuery: Boolean = false

        /** 安排下一次仓库调用返回网络不可用。 */
        fun scheduleNetworkFailure() {
            shouldFailNextQuery = true
        }

        /** 首轮第二期返回网络不可用，其余调用返回与请求期号匹配的双证据结果。 */
        override suspend fun getDraw(
            lotteryType: LotteryType,
            issue: Issue,
        ): DrawQueryResult {
            queriedIssues += issue
            queryCount += 1
            if (queryCount == 2 || shouldFailNextQuery) {
                shouldFailNextQuery = false
                return DrawQueryResult.Unavailable(DrawStatus.NETWORK_UNAVAILABLE, "测试网络不可用")
            }
            return DrawQueryResult.Success(verifiedDraw().copy(issue = issue))
        }
    }

    /** 记录调用次数并委托给固定演示仓库。 */
    private class CountingDrawRepository : DrawRepository {
        /** 实际返回演示结果的仓库。 */
        private val delegate = FakeDrawRepository()

        /** 已触发的单期查询次数。 */
        var queryCount: Int = 0
            private set

        /** 记录调用后返回固定演示开奖结果。 */
        override suspend fun getDraw(
            lotteryType: roc.win.lottery.domain.LotteryType,
            issue: roc.win.lottery.domain.Issue,
        ): roc.win.lottery.data.DrawQueryResult {
            queryCount += 1
            return delegate.getDraw(lotteryType, issue)
        }
    }

    /** 记录是否意外进入图片采集流程。 */
    private class TrackingImageAcquirer : ImageAcquirer {
        /** 手动流程测试不提供相机。 */
        override val supportsCamera: Boolean = false

        /** 实际收到的图片采集次数。 */
        var acquisitionCount: Int = 0
            private set

        /** 记录异常采集并返回取消结果。 */
        override suspend fun acquire(source: ImageAcquisitionSource): ImageAcquisitionResult {
            acquisitionCount += 1
            return ImageAcquisitionResult.Cancelled
        }
    }

    /** 记录控制器提交的匿名 OCR 诊断调用。 */
    private class TrackingOcrConfidenceDiagnostics : OcrConfidenceDiagnostics {
        /** 按调用顺序保存的引擎名和匿名样本。 */
        val records = mutableListOf<Pair<String, OcrConfidenceSample>>()

        /** 保存一次诊断调用，不读取图片或 OCR 文本。 */
        override fun record(
            engineName: String,
            sample: OcrConfidenceSample,
        ) {
            records += engineName to sample
        }
    }

    /** 记录控制器请求清理的临时图片。 */
    private class TrackingAppPaths : AppPaths {
        /** 测试使用的虚拟临时目录。 */
        override val temporaryImageDirectory: String = "memory://test-images"

        /** 按调用顺序记录已清理的图片标识。 */
        val deletedImageIds = mutableListOf<String>()

        /** 记录清理请求并模拟删除成功。 */
        override suspend fun deleteTemporaryImage(imageRef: ImageRef): Boolean {
            deletedImageIds += imageRef.id
            return true
        }
    }
}

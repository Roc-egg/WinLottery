package roc.win.lottery.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import roc.win.lottery.Platform
import roc.win.lottery.data.DrawQueryResult
import roc.win.lottery.data.DrawRepository
import roc.win.lottery.data.FakeDrawRepository
import roc.win.lottery.data.HistoricalDrawFailureReason
import roc.win.lottery.data.HistoricalDrawQueryResult
import roc.win.lottery.data.HistoricalDrawRepository
import roc.win.lottery.domain.BetLineDraft
import roc.win.lottery.domain.ConfirmedTicket
import roc.win.lottery.domain.CrossPeriodRandomMode
import roc.win.lottery.domain.DrawPolicy
import roc.win.lottery.domain.DrawResult
import roc.win.lottery.domain.DrawStatus
import roc.win.lottery.domain.HistoricalDraw
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryPrizeCalculator
import roc.win.lottery.domain.LotteryTrendArea
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.PrizeCheckStatus
import roc.win.lottery.domain.PrizeTier
import roc.win.lottery.domain.PrizeTierCodes
import roc.win.lottery.domain.RuleVersion
import roc.win.lottery.domain.SourceEvidence
import roc.win.lottery.domain.TicketDraft
import roc.win.lottery.domain.TicketValidator
import roc.win.lottery.domain.TrendSampleSize
import roc.win.lottery.persistence.CURRENT_TICKET_RECORD_DATA_VERSION
import roc.win.lottery.persistence.DrawResultStore
import roc.win.lottery.persistence.HistoricalDrawStore
import roc.win.lottery.persistence.StoredTicketRecord
import roc.win.lottery.persistence.TicketAcquisitionSource
import roc.win.lottery.persistence.TicketRecordExport
import roc.win.lottery.persistence.TicketRecordImportConflict
import roc.win.lottery.persistence.TicketRecordImportConflictPolicy
import roc.win.lottery.persistence.TicketRecordImportPreview
import roc.win.lottery.persistence.TicketRecordImportResult
import roc.win.lottery.persistence.TicketRecordStore
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
import roc.win.lottery.recognition.ProgressiveTicketRecognizer
import roc.win.lottery.recognition.RecognitionProgress
import roc.win.lottery.recognition.RecognitionResult
import roc.win.lottery.recognition.TicketFieldCandidate
import roc.win.lottery.recognition.TicketFieldReference
import roc.win.lottery.recognition.TicketFieldRegion
import roc.win.lottery.recognition.TicketParseResult
import roc.win.lottery.recognition.TicketParser
import roc.win.lottery.recognition.TicketRecognizer
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/** 应用确认闸门和流程状态测试。 */
class LotteryAppControllerTest {
    /** 手动大乐透必须逐项通过确认闸门，并按精确期号查询且不触碰图片生命周期。 */
    @Test
    fun manualSuperLottoRequiresExplicitFieldsBeforeExactQuery() =
        runTest {
            val repository = SequenceDrawRepository(DrawQueryResult.Success(verifiedDraw()))
            val paths = TrackingAppPaths()
            val imageAcquirer = TrackingImageAcquirer()
            val ticketRecordStore = TrackingTicketRecordStore()
            val controller =
                createController(
                    repository = repository,
                    appPaths = paths,
                    usesRealDrawData = true,
                    imageAcquirer = imageAcquirer,
                    ticketRecordStore = ticketRecordStore,
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
            assertEquals(1, ticketRecordStore.savedRecords.size)
            assertEquals(
                TicketAcquisitionSource.MANUAL_ENTRY,
                ticketRecordStore.savedRecords.single().acquisitionSource,
            )
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

    /** 新确认流程只保存一次，结果重试和返回后再次确认都不得重复写入。 */
    @Test
    fun confirmedTicketIsSavedOnceAcrossRetryAndReturn() =
        runTest {
            val repository =
                SequenceDrawRepository(
                    DrawQueryResult.Unavailable(DrawStatus.NETWORK_UNAVAILABLE, "测试网络不可用"),
                    DrawQueryResult.Success(verifiedDraw()),
                    DrawQueryResult.Success(verifiedDraw()),
                )
            val ticketRecordStore = TrackingTicketRecordStore()
            val controller =
                createController(
                    repository = repository,
                    usesRealDrawData = true,
                    ticketRecordStore = ticketRecordStore,
                )
            controller.startAnalysis(ImageAcquisitionSource.SYSTEM_PICKER)

            controller.confirmTicket()
            assertIs<AppScreen.DrawUnavailable>(controller.uiState.value.screen)
            controller.retryDrawQuery()
            assertIs<AppScreen.VerificationResult>(controller.uiState.value.screen)
            controller.navigateBack()
            assertIs<AppScreen.Review>(controller.uiState.value.screen)
            controller.confirmTicket()

            assertIs<AppScreen.VerificationResult>(controller.uiState.value.screen)
            assertEquals(3, repository.queryCount)
            assertEquals(1, ticketRecordStore.saveCallCount)
            assertEquals(1, ticketRecordStore.savedRecords.size)
            assertEquals(
                TicketAcquisitionSource.SYSTEM_PICKER,
                ticketRecordStore.savedRecords.single().acquisitionSource,
            )
        }

    /** 返回校正票面后再次确认时必须原地替换同一条记录，不能保留旧票面或另存一条。 */
    @Test
    fun correctedTicketReplacesSavedRecordAfterReturn() =
        runTest {
            val repository = SequenceDrawRepository(DrawQueryResult.Success(verifiedDraw()))
            val ticketRecordStore = TrackingTicketRecordStore()
            val controller =
                createController(
                    repository = repository,
                    usesRealDrawData = true,
                    ticketRecordStore = ticketRecordStore,
                )
            controller.startAnalysis(ImageAcquisitionSource.SYSTEM_PICKER)

            controller.confirmTicket()
            assertIs<AppScreen.VerificationResult>(controller.uiState.value.screen)
            val savedRecordId = ticketRecordStore.savedRecords.single().id
            controller.navigateBack()
            assertIs<AppScreen.Review>(controller.uiState.value.screen)
            controller.updateTicketReview(TicketReviewAction.ChangeMultiplier(2))
            controller.updateTicketReview(TicketReviewAction.UseCalculatedAmount)
            val correctedReview = assertIs<AppScreen.Review>(controller.uiState.value.screen)
            assertTrue(correctedReview.evaluation.canConfirm)
            val correctedTicket = checkNotNull(correctedReview.evaluation.ticket)
            controller.confirmTicket()

            assertIs<AppScreen.VerificationResult>(controller.uiState.value.screen)
            assertEquals(1, ticketRecordStore.saveCallCount)
            assertEquals(1, ticketRecordStore.replaceCallCount)
            val savedRecord = ticketRecordStore.currentRecords.single()
            assertEquals(savedRecordId, savedRecord.id)
            assertEquals(correctedTicket, savedRecord.ticket)
            assertEquals(2, savedRecord.ticket.multiplier.value)
        }

    /** 并发点击确认时第二次调用必须被流程代次闸门拒绝。 */
    @Test
    fun concurrentConfirmOnlySavesAndQueriesOnce() =
        runTest {
            val repository = SequenceDrawRepository(DrawQueryResult.Success(verifiedDraw()))
            val ticketRecordStore = TrackingTicketRecordStore(suspendSave = true)
            val controller =
                createController(
                    repository = repository,
                    usesRealDrawData = true,
                    ticketRecordStore = ticketRecordStore,
                    imageAcquirer = FakeImageAcquirer(supportsCamera = true),
                )
            controller.startAnalysis(ImageAcquisitionSource.CAMERA)

            val firstConfirmation = launch { controller.confirmTicket() }
            ticketRecordStore.saveStarted.await()
            val secondConfirmation = launch { controller.confirmTicket() }
            secondConfirmation.join()

            assertEquals(1, ticketRecordStore.saveCallCount)
            ticketRecordStore.releaseSave()
            firstConfirmation.join()

            assertEquals(1, ticketRecordStore.savedRecords.size)
            assertEquals(TicketAcquisitionSource.CAMERA, ticketRecordStore.savedRecords.single().acquisitionSource)
            assertEquals(1, repository.queryCount)
        }

    /** 本机保存失败必须停留在确认页，并且不得向官网发起查询。 */
    @Test
    fun saveFailureBlocksDrawQuery() =
        runTest {
            val repository = SequenceDrawRepository(DrawQueryResult.Success(verifiedDraw()))
            val ticketRecordStore = TrackingTicketRecordStore(failSave = true)
            val controller =
                createController(
                    repository = repository,
                    usesRealDrawData = true,
                    ticketRecordStore = ticketRecordStore,
                )
            controller.startAnalysis(ImageAcquisitionSource.SYSTEM_PICKER)

            controller.confirmTicket()

            val review = assertIs<AppScreen.Review>(controller.uiState.value.screen)
            assertEquals("无法保存本机记录，请检查设备存储后重试", review.saveError)
            assertEquals(0, repository.queryCount)
            assertEquals(1, ticketRecordStore.saveCallCount)
            assertTrue(ticketRecordStore.savedRecords.isEmpty())
        }

    /** 记录重命名和二次查询必须直接复用保存票据，并在结果返回时恢复列表筛选。 */
    @Test
    fun storedTicketCanBeRenamedAndQueriedWithoutImageOrOcr() =
        runTest {
            val ticket =
                checkNotNull(
                    TicketReviewState
                        .fromDraft(validDraft())
                        .evaluate(TicketValidator())
                        .ticket,
                )
            val storedRecord = storedTicketRecord(ticket)
            val ticketRecordStore = TrackingTicketRecordStore(listOf(storedRecord))
            val imageAcquirer = TrackingImageAcquirer()
            val repository = SequenceDrawRepository(DrawQueryResult.Success(verifiedDraw()))
            val controller =
                createController(
                    repository = repository,
                    usesRealDrawData = true,
                    ticketRecordStore = ticketRecordStore,
                    imageAcquirer = imageAcquirer,
                    ticketRecognizer = TicketRecognizer { error("记录查询不得调用 OCR") },
                )
            controller.showTicketRecords()
            controller.updateTicketRecordFilter(TicketRecordFilter.SUPER_LOTTO)
            controller.updateTicketRecordSearch("26091")
            controller.renameTicketRecord(storedRecord.id, "  收藏票  ")

            controller.queryTicketRecord(storedRecord.id)

            val result = assertIs<AppScreen.VerificationResult>(controller.uiState.value.screen)
            assertEquals(ticket, result.ticket)
            assertEquals(1, repository.queryCount)
            assertEquals(0, imageAcquirer.acquisitionCount)
            assertEquals(0, ticketRecordStore.saveCallCount)
            assertEquals("收藏票", ticketRecordStore.currentRecords.single().displayName)

            controller.navigateBack()

            val records = assertIs<AppScreen.Records>(controller.uiState.value.screen)
            assertEquals(TicketRecordFilter.SUPER_LOTTO, records.filter)
            assertEquals("26091", records.searchQuery)
        }

    /** 删除和清空必须更新本机仓库，并在记录页保留明确的成功摘要。 */
    @Test
    fun storedTicketsCanBeDeletedAndCleared() =
        runTest {
            val ticket =
                checkNotNull(
                    TicketReviewState
                        .fromDraft(validDraft())
                        .evaluate(TicketValidator())
                        .ticket,
                )
            val firstRecord = storedTicketRecord(ticket)
            val secondRecord =
                firstRecord.copy(
                    id = "20000000-0000-4000-8000-000000000002",
                    displayName = "第二张票",
                )
            val ticketRecordStore = TrackingTicketRecordStore(listOf(firstRecord, secondRecord))
            val controller =
                createController(
                    repository = CountingDrawRepository(),
                    ticketRecordStore = ticketRecordStore,
                )
            controller.showTicketRecords()

            controller.deleteTicketRecord(firstRecord.id)

            assertEquals(listOf(secondRecord), ticketRecordStore.currentRecords)
            var records = assertIs<AppScreen.Records>(controller.uiState.value.screen)
            assertEquals("记录已从本机删除", records.operationNotice)
            assertFalse(records.isOperationInProgress)

            controller.clearTicketRecords()

            assertTrue(ticketRecordStore.currentRecords.isEmpty())
            records = assertIs(controller.uiState.value.screen)
            assertEquals("已清空 1 条本机记录", records.operationNotice)
            assertFalse(records.isOperationInProgress)
        }

    /** 导出必须使用固定扩展名，并把规范化字节原样交给系统文件接口。 */
    @Test
    fun storedTicketsCanBeExportedThroughSystemFileExchange() =
        runTest {
            val exportedContent = byteArrayOf(1, 3, 5, 7)
            val ticketRecordStore =
                TrackingTicketRecordStore(
                    exportResult =
                        TicketRecordExport(
                            content = exportedContent,
                            recordCount = 2,
                            exportedAtEpochMillis = 123_456L,
                            payloadSha256 = "test-sha256",
                        ),
                )
            val fileExchange =
                TrackingTicketRecordFileExchange(
                    writeResult = TicketRecordFileWriteResult.Success,
                )
            val controller =
                createController(
                    repository = CountingDrawRepository(),
                    ticketRecordStore = ticketRecordStore,
                    ticketRecordFileExchange = fileExchange,
                )
            controller.showTicketRecords()

            controller.exportTicketRecords()

            assertEquals("win-lottery-tickets-123456.wltickets.json", fileExchange.writtenFileName)
            assertContentEquals(exportedContent, fileExchange.writtenContent)
            val records = assertIs<AppScreen.Records>(controller.uiState.value.screen)
            assertEquals("已导出 2 条记录", records.operationNotice)
            assertFalse(records.isOperationInProgress)
        }

    /** 冲突导入必须先整批拒绝，只有用户确认后才保留本机并导入其余记录。 */
    @Test
    fun ticketRecordImportRequiresExplicitConflictDecision() =
        runTest {
            val importContent = byteArrayOf(2, 4, 6, 8)
            val preview =
                TicketRecordImportPreview(
                    exportedAtEpochMillis = 123_456L,
                    recordCount = 3,
                    importableCount = 1,
                    duplicateCount = 1,
                    conflicts =
                        listOf(
                            TicketRecordImportConflict(
                                id = TEST_RECORD_ID,
                                localDisplayName = "本机票",
                                importedDisplayName = "导入票",
                            ),
                        ),
                    payloadSha256 = "test-sha256",
                )
            val ticketRecordStore =
                TrackingTicketRecordStore(
                    inspectImportResult = preview,
                    importResults =
                        listOf(
                            TicketRecordImportResult.Conflicts(preview),
                            TicketRecordImportResult.Completed(
                                importedCount = 1,
                                duplicateCount = 1,
                                skippedConflictCount = 1,
                            ),
                        ),
                )
            val fileExchange =
                TrackingTicketRecordFileExchange(
                    readResult = TicketRecordFileReadResult.Success(importContent),
                )
            val controller =
                createController(
                    repository = CountingDrawRepository(),
                    ticketRecordStore = ticketRecordStore,
                    ticketRecordFileExchange = fileExchange,
                )
            controller.showTicketRecords()

            controller.selectTicketRecordImport()

            var records = assertIs<AppScreen.Records>(controller.uiState.value.screen)
            assertEquals(preview, records.importPreview)
            assertFalse(records.isOperationInProgress)
            assertContentEquals(importContent, ticketRecordStore.inspectedImportContent)

            controller.confirmTicketRecordImport(keepLocalConflicts = false)

            records = assertIs(controller.uiState.value.screen)
            assertEquals(preview, records.importPreview)
            assertEquals(
                listOf(TicketRecordImportConflictPolicy.REJECT_ALL),
                ticketRecordStore.importPolicies,
            )

            controller.confirmTicketRecordImport(keepLocalConflicts = true)

            records = assertIs(controller.uiState.value.screen)
            assertNull(records.importPreview)
            assertEquals(
                "已导入 1 条记录，跳过 1 条相同记录，保留本机 1 条冲突记录",
                records.operationNotice,
            )
            assertEquals(
                listOf(
                    TicketRecordImportConflictPolicy.REJECT_ALL,
                    TicketRecordImportConflictPolicy.KEEP_LOCAL_AND_IMPORT_REST,
                ),
                ticketRecordStore.importPolicies,
            )
            assertContentEquals(importContent, ticketRecordStore.importedContents.last())
        }

    /** 离开记录页后，迟到的文件选择结果不得触发预检或保留待确认字节。 */
    @Test
    fun lateTicketRecordFileSelectionIsDiscardedAfterLeavingRecords() =
        runTest {
            val ticketRecordStore = TrackingTicketRecordStore()
            val fileExchange = SuspendedTicketRecordFileExchange()
            val controller =
                createController(
                    repository = CountingDrawRepository(),
                    ticketRecordStore = ticketRecordStore,
                    ticketRecordFileExchange = fileExchange,
                )
            controller.showTicketRecords()

            val selection = launch { controller.selectTicketRecordImport() }
            fileExchange.readStarted.await()
            assertTrue(assertIs<AppScreen.Records>(controller.uiState.value.screen).isOperationInProgress)

            controller.navigateHome()
            fileExchange.completeRead(TicketRecordFileReadResult.Success(byteArrayOf(9, 8, 7)))
            selection.join()

            assertEquals(AppScreen.Home, controller.uiState.value.screen)
            assertEquals(0, ticketRecordStore.inspectImportCallCount)
            controller.showTicketRecords()
            controller.confirmTicketRecordImport(keepLocalConflicts = true)
            assertTrue(ticketRecordStore.importPolicies.isEmpty())
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

    /**
     * 年度末起始的多期票不能在确认时被固定期次上限阻断；年度结束后应按官网实际期号接续新年度，
     * 此前按本年度顺延的预估期号被替换后，重查要补查新年度期号。
     */
    @Test
    fun multiPeriodTicketFollowsOfficialIssuesAcrossYearBoundary() =
        runTest {
            val repository = YearBoundaryDrawRepository()
            val draft =
                validDraft().copy(
                    issue = "26149",
                    periodCount = 4,
                    paidAmountFen = 1_200L,
                )
            val controller =
                createController(
                    repository = repository,
                    usesRealDrawData = true,
                    ticketParser = TicketParser { TicketParseResult.ReadyForReview(draft) },
                )
            controller.startAnalysis(ImageAcquisitionSource.SYSTEM_PICKER)

            controller.confirmTicket()

            val beforeNewYear = assertIs<AppScreen.MultiPeriodVerificationResult>(controller.uiState.value.screen)
            assertEquals(listOf("26149", "26150", "26151", "26152"), beforeNewYear.periodResults.map { it.issue.value })
            assertEquals(2, beforeNewYear.verifiedPeriodCount)
            assertEquals(listOf("26149", "26150", "26151"), repository.queriedIssues.map { it.value })

            repository.drawnIssues = listOf(Issue("26149"), Issue("26150"), Issue("27001"), Issue("27002"))
            controller.retryDrawQuery()

            val afterNewYear = assertIs<AppScreen.MultiPeriodVerificationResult>(controller.uiState.value.screen)
            assertEquals(listOf("26149", "26150", "27001", "27002"), afterNewYear.periodResults.map { it.issue.value })
            assertTrue(afterNewYear.isConclusive)
            assertEquals(beforeNewYear.periodResults.take(2), afterNewYear.periodResults.take(2))
            assertEquals(
                listOf("26149", "26150", "26151", "27001", "27002"),
                repository.queriedIssues.map { it.value },
            )
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

    /** 官网数据源整体异常时应保留全部已验证期次，恢复后再完整刷新。 */
    @Test
    fun multiPeriodSourceFailurePreservesVerifiedResultsAndRecovers() =
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

            val completed = assertIs<AppScreen.MultiPeriodVerificationResult>(controller.uiState.value.screen)
            assertTrue(completed.isConclusive)
            assertEquals(3, completed.verifiedPeriodCount)

            repository.scheduleUnavailable(DrawStatus.SOURCE_UNAVAILABLE)
            controller.retryDrawQuery()

            val preserved = assertIs<AppScreen.MultiPeriodVerificationResult>(controller.uiState.value.screen)
            assertEquals(completed.periodResults, preserved.periodResults)
            assertTrue(preserved.isConclusive)
            assertEquals(DrawStatus.SOURCE_UNAVAILABLE, preserved.retryNotice?.status)
            assertEquals(
                listOf("26090", "26091", "26092", "26090"),
                repository.queriedIssues.map { it.value },
            )

            controller.retryDrawQuery()

            val recovered = assertIs<AppScreen.MultiPeriodVerificationResult>(controller.uiState.value.screen)
            assertTrue(recovered.isConclusive)
            assertNull(recovered.retryNotice)
            assertEquals(
                listOf("26090", "26091", "26092", "26090", "26090", "26091", "26092"),
                repository.queriedIssues.map { it.value },
            )
        }

    /** 新证据冲突必须撤销对应期次结论，且恢复时只补查该期。 */
    @Test
    fun multiPeriodConflictInvalidatesOnlyAffectedIssueUntilRecovery() =
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

            repository.scheduleUnavailable(DrawStatus.CONFLICT)
            controller.retryDrawQuery()

            val conflicted = assertIs<AppScreen.MultiPeriodVerificationResult>(controller.uiState.value.screen)
            assertFalse(conflicted.isConclusive)
            assertEquals(2, conflicted.verifiedPeriodCount)
            assertEquals(1, conflicted.unresolvedPeriodCount)
            assertNull(conflicted.estimatedPrizeFen)
            assertNull(conflicted.retryNotice)
            val affected = assertIs<PeriodVerification.Unavailable>(conflicted.periodResults[0])
            assertEquals(DrawStatus.CONFLICT, affected.status)
            assertTrue(affected.wasQueried)
            assertIs<PeriodVerification.Verified>(conflicted.periodResults[1])
            assertIs<PeriodVerification.Verified>(conflicted.periodResults[2])
            assertEquals(
                listOf("26090", "26091", "26092", "26090", "26091", "26092"),
                repository.queriedIssues.map { it.value },
            )

            controller.retryDrawQuery()

            val recovered = assertIs<AppScreen.MultiPeriodVerificationResult>(controller.uiState.value.screen)
            assertTrue(recovered.isConclusive)
            assertEquals(3, recovered.verifiedPeriodCount)
            assertNull(recovered.retryNotice)
            assertEquals(
                listOf("26090", "26091", "26092", "26090", "26091", "26092", "26090"),
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

    /** 切换到选号一级页面必须清理临时票图，系统返回再回到默认核对页面。 */
    @Test
    fun numberPickerIsAnIndependentMainDestination() =
        runTest {
            val paths = TrackingAppPaths()
            val controller = createController(CountingDrawRepository(), paths)
            controller.startAnalysis(ImageAcquisitionSource.SYSTEM_PICKER)

            controller.showRandomNumberPicker()

            assertIs<AppScreen.NumberPicker>(controller.uiState.value.screen)
            assertEquals(listOf("b1-demo-ticket"), paths.deletedImageIds)

            controller.navigateBack()

            assertEquals(AppScreen.Home, controller.uiState.value.screen)
        }

    /** 走势与数学研究必须共享真实 500 期，并在配置切换和再次进入时复用会话缓存。 */
    @Test
    fun trendChartIsAnIndependentMainDestination() =
        runTest {
            val paths = TrackingAppPaths()
            val historyRepository = CountingHistoricalDrawRepository()
            val controller =
                createController(
                    repository = CountingDrawRepository(),
                    appPaths = paths,
                    historicalDrawRepository = historyRepository,
                )
            controller.startAnalysis(ImageAcquisitionSource.SYSTEM_PICKER)

            controller.showTrendChart()

            val initial = assertIs<AppScreen.Trends>(controller.uiState.value.screen).chart
            val initialReady = assertIs<TrendChartContent.Ready>(initial.content)
            val initialResearch = assertIs<TrendResearchContent.Ready>(initial.researchContent)
            assertEquals(listOf("b1-demo-ticket"), paths.deletedImageIds)
            assertEquals(50, initialReady.snapshot.actualSampleCount)
            assertEquals("26451", initialReady.snapshot.firstIssue.value)
            assertEquals("26500", initialReady.snapshot.lastIssue.value)
            assertEquals(450, initialResearch.analysis.backtest.targetCount)
            assertEquals("26451", initialResearch.analysis.candidate.trainingFirstIssue.value)
            assertEquals("26500", initialResearch.analysis.candidate.trainingLastIssue.value)
            assertEquals(1, historyRepository.queryCount(LotteryType.SUPER_LOTTO))
            controller.updateTrendChart(
                TrendChartAction.ChangeSampleSize(TrendSampleSize.LAST_500),
            )
            controller.updateTrendChart(TrendChartAction.ChangeArea(LotteryTrendArea.SECONDARY))

            val expanded = assertIs<AppScreen.Trends>(controller.uiState.value.screen).chart
            val expandedReady = assertIs<TrendChartContent.Ready>(expanded.content)
            assertEquals(500, expandedReady.snapshot.actualSampleCount)
            assertEquals(12, expandedReady.snapshot.statistics.size)
            assertEquals(1, historyRepository.queryCount(LotteryType.SUPER_LOTTO))
            controller.updateTrendChart(
                TrendChartAction.ChangeLotteryType(LotteryType.DOUBLE_COLOR_BALL),
            )
            controller.updateTrendChart(
                TrendChartAction.ChangeSampleSize(TrendSampleSize.LAST_80),
            )
            controller.updateTrendChart(
                TrendChartAction.ChangeView(TrendWorkspaceView.MATHEMATICAL_RESEARCH),
            )

            val researchView = assertIs<AppScreen.Trends>(controller.uiState.value.screen).chart
            assertEquals(TrendWorkspaceView.MATHEMATICAL_RESEARCH, researchView.view)
            assertIs<TrendResearchContent.Ready>(researchView.researchContent)
            assertEquals(1, historyRepository.queryCount(LotteryType.DOUBLE_COLOR_BALL))
            controller.navigateHome()
            controller.showTrendChart()

            val restored = assertIs<AppScreen.Trends>(controller.uiState.value.screen).chart
            assertEquals(LotteryType.DOUBLE_COLOR_BALL, restored.lotteryType)
            assertEquals(TrendWorkspaceView.MATHEMATICAL_RESEARCH, restored.view)
            assertEquals(LotteryTrendArea.SECONDARY, restored.area)
            assertEquals(TrendSampleSize.LAST_80, restored.sampleSize)
            assertEquals(80, assertIs<TrendChartContent.Ready>(restored.content).snapshot.actualSampleCount)
            assertEquals(
                450,
                assertIs<TrendResearchContent.Ready>(restored.researchContent).analysis.backtest.targetCount,
            )
            assertEquals(1, historyRepository.queryCount(LotteryType.DOUBLE_COLOR_BALL))

            controller.navigateBack()

            assertEquals(AppScreen.Home, controller.uiState.value.screen)
        }

    /** 官方历史开奖失败后必须保留失败状态，并只在用户重试时再次联网。 */
    @Test
    fun trendChartRetriesOnlyAfterExplicitAction() =
        runTest {
            val historyRepository = CountingHistoricalDrawRepository(failureMessage = "测试网络不可用")
            val controller =
                createController(
                    repository = CountingDrawRepository(),
                    historicalDrawRepository = historyRepository,
                )

            controller.showTrendChart()

            val failed = assertIs<AppScreen.Trends>(controller.uiState.value.screen).chart
            assertEquals("测试网络不可用", assertIs<TrendChartContent.Failed>(failed.content).message)
            assertEquals(
                "测试网络不可用",
                assertIs<TrendResearchContent.Failed>(failed.researchContent).message,
            )
            assertEquals(1, historyRepository.queryCount(LotteryType.SUPER_LOTTO))
            historyRepository.failureMessage = null
            controller.updateTrendChart(TrendChartAction.Retry)

            val retried = assertIs<AppScreen.Trends>(controller.uiState.value.screen).chart
            assertEquals(50, assertIs<TrendChartContent.Ready>(retried.content).snapshot.actualSampleCount)
            assertEquals(
                450,
                assertIs<TrendResearchContent.Ready>(retried.researchContent).analysis.backtest.targetCount,
            )
            assertEquals(2, historyRepository.queryCount(LotteryType.SUPER_LOTTO))
        }

    /** 选号配置和结果在一级页面切换后仍应保留，但不得写入票据仓库。 */
    @Test
    fun randomNumberPlanStaysInCurrentControllerSession() =
        runTest {
            val ticketRecordStore = TrackingTicketRecordStore()
            val controller =
                createController(
                    repository = CountingDrawRepository(),
                    ticketRecordStore = ticketRecordStore,
                )
            controller.showRandomNumberPicker()
            controller.updateRandomNumberPicker(
                RandomNumberPickerAction.ChangeLotteryType(LotteryType.DOUBLE_COLOR_BALL),
            )
            controller.updateRandomNumberPicker(RandomNumberPickerAction.ChangePeriodCount(3))
            controller.updateRandomNumberPicker(RandomNumberPickerAction.ChangeBetsPerPeriod(2))
            controller.updateRandomNumberPicker(
                RandomNumberPickerAction.ChangeCrossPeriodMode(
                    CrossPeriodRandomMode.INDEPENDENT_PER_PERIOD,
                ),
            )
            controller.updateRandomNumberPicker(RandomNumberPickerAction.Generate)

            val generated = assertIs<AppScreen.NumberPicker>(controller.uiState.value.screen).picker
            val plan = requireNotNull(generated.plan)
            assertEquals(LotteryType.DOUBLE_COLOR_BALL, plan.lotteryType)
            assertEquals(3, plan.periods.size)
            assertEquals(2, plan.betsPerPeriod)
            assertEquals(6, plan.totalBetCount)
            assertTrue(ticketRecordStore.currentRecords.isEmpty())

            controller.navigateHome()
            controller.showRandomNumberPicker()

            val restored = assertIs<AppScreen.NumberPicker>(controller.uiState.value.screen).picker
            assertEquals(plan, restored.plan)
            assertTrue(ticketRecordStore.currentRecords.isEmpty())
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

    /** 桌面 OCR 尚不可用时应保留私有图片并进入对照原图录入，不得落入 Fake 识别。 */
    @Test
    fun unavailableRecognitionFallsBackToImageAssistedManualEntry() =
        runTest {
            val paths = TrackingAppPaths()
            val recognizer =
                TicketRecognizer {
                    RecognitionResult.ManualEntryRequired("桌面 OCR 正在适配")
                }
            val controller =
                createController(
                    repository = CountingDrawRepository(),
                    appPaths = paths,
                    ticketRecognizer = recognizer,
                )

            controller.startAnalysis(ImageAcquisitionSource.SYSTEM_PICKER)

            val review = assertIs<AppScreen.Review>(controller.uiState.value.screen)
            assertEquals("桌面 OCR 正在适配", review.manualEntryReason)
            assertEquals("b1-demo-ticket", review.imageRef?.id)
            assertTrue(paths.deletedImageIds.isEmpty())
        }

    /** 渐进式 OCR 回调必须持续更新分析页百分比和阶段说明，不得停留在固定中点。 */
    @Test
    fun progressiveRecognitionUpdatesAnalysisScreen() =
        runTest {
            val progressReported = CompletableDeferred<Unit>()
            val continueRecognition = CompletableDeferred<Unit>()
            val recognizer =
                object : ProgressiveTicketRecognizer {
                    /** 报告中间阶段并等待测试读取 UI 状态后结束识别。 */
                    override suspend fun recognize(
                        imageRef: ImageRef,
                        onProgress: (RecognitionProgress) -> Unit,
                    ): RecognitionResult {
                        onProgress(RecognitionProgress(0.5f, "正在校准投注号码 2/4"))
                        progressReported.complete(Unit)
                        continueRecognition.await()
                        return RecognitionResult.Failure("进度测试结束")
                    }
                }
            val controller =
                createController(
                    repository = CountingDrawRepository(),
                    ticketRecognizer = recognizer,
                )

            val analysisJob = launch { controller.startAnalysis(ImageAcquisitionSource.SYSTEM_PICKER) }
            progressReported.await()

            val analysis = assertIs<AppScreen.Analysis>(controller.uiState.value.screen)
            assertEquals("正在校准投注号码 2/4", analysis.detail)
            assertEquals(0.66f, analysis.progress, absoluteTolerance = 0.001f)

            continueRecognition.complete(Unit)
            analysisJob.join()
        }

    /** 识别中返回必须真正取消识别协程，且不取消发起操作的界面协程。 */
    @Test
    fun navigatingBackDuringRecognitionCancelsRecognizer() =
        runTest {
            val recognitionStarted = CompletableDeferred<Unit>()
            var recognitionCancelled = false
            val recognizer =
                TicketRecognizer {
                    recognitionStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        recognitionCancelled = true
                    }
                }
            val controller =
                createController(
                    repository = CountingDrawRepository(),
                    ticketRecognizer = recognizer,
                )

            val analysisJob = launch { controller.startAnalysis(ImageAcquisitionSource.SYSTEM_PICKER) }
            recognitionStarted.await()
            controller.navigateBack()
            analysisJob.join()

            assertTrue(recognitionCancelled)
            assertFalse(analysisJob.isCancelled)
            assertEquals(AppScreen.Home, controller.uiState.value.screen)
        }

    /** 开奖查询中返回必须真正取消网络请求，并恢复查询来源的确认页。 */
    @Test
    fun navigatingBackDuringDrawQueryCancelsRequest() =
        runTest {
            val queryStarted = CompletableDeferred<Unit>()
            var queryCancelled = false
            val repository =
                DrawRepository { _, _ ->
                    queryStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        queryCancelled = true
                    }
                }
            val controller = createController(repository = repository, usesRealDrawData = true)
            controller.startAnalysis(ImageAcquisitionSource.SYSTEM_PICKER)
            assertIs<AppScreen.Review>(controller.uiState.value.screen)

            val confirmJob = launch { controller.confirmTicket() }
            queryStarted.await()
            assertIs<AppScreen.DrawQuery>(controller.uiState.value.screen)
            controller.navigateBack()
            confirmJob.join()

            assertTrue(queryCancelled)
            assertFalse(confirmJob.isCancelled)
            assertIs<AppScreen.Review>(controller.uiState.value.screen)
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

    /** 本机历史开奖未过期时进入走势页直接展示，不联网。 */
    @Test
    fun trendUsesFreshPersistedHistoryWithoutNetwork() =
        runTest {
            val clock = MutableTestClock()
            val history = ScriptedHistoryRepository(clock)
            val controller =
                createHistoryController(
                    clock = clock,
                    history = history,
                    store =
                        InMemoryHistoricalDrawStore(
                            listOf(storedAt(clock, LotteryType.SUPER_LOTTO, ageMillis = 0L)),
                        ),
                )

            controller.showTrendChart()

            val chart = currentTrendChart(controller)
            assertEquals("26500", assertIs<TrendChartContent.Ready>(chart.content).snapshot.lastIssue.value)
            assertIs<TrendResearchContent.Ready>(chart.researchContent)
            assertEquals(HistoricalDrawRefreshStatus.Idle, chart.historyRefresh)
            assertTrue(history.requests.isEmpty())
        }

    /** 本机数据过期时先展示本机走势，再在同一次操作中只用一次请求补入最新开奖。 */
    @Test
    fun staleTrendShowsCachedDataThenAppendsNewDraws() =
        runTest {
            val clock = MutableTestClock()
            val history = ScriptedHistoryRepository(clock, newestOrdinal = 503)
            val gate = CompletableDeferred<Unit>()
            history.gate = gate
            val controller =
                createHistoryController(
                    clock = clock,
                    history = history,
                    store =
                        InMemoryHistoricalDrawStore(
                            listOf(storedAt(clock, LotteryType.SUPER_LOTTO, STALE_HISTORY_AGE)),
                        ),
                )

            val entering = launch { controller.showTrendChart() }
            history.started.await()

            val refreshing = currentTrendChart(controller)
            assertEquals("26500", assertIs<TrendChartContent.Ready>(refreshing.content).snapshot.lastIssue.value)
            assertEquals(HistoricalDrawRefreshStatus.Refreshing, refreshing.historyRefresh)
            gate.complete(Unit)
            entering.join()

            val refreshed = currentTrendChart(controller)
            assertEquals("26503", assertIs<TrendChartContent.Ready>(refreshed.content).snapshot.lastIssue.value)
            val research = assertIs<TrendResearchContent.Ready>(refreshed.researchContent)
            assertEquals("26503", research.analysis.candidate.trainingLastIssue.value)
            assertEquals(HistoricalDrawRefreshStatus.Idle, refreshed.historyRefresh)
            assertEquals(listOf(LotteryType.SUPER_LOTTO to 100), history.requests)
        }

    /** 增量核对断网时继续展示本机走势，并标注核对失败原因。 */
    @Test
    fun offlineRefreshKeepsCachedTrendWithFailureStatus() =
        runTest {
            val clock = MutableTestClock()
            val history = ScriptedHistoryRepository(clock)
            history.failureMessage = "测试断网"
            val controller =
                createHistoryController(
                    clock = clock,
                    history = history,
                    store =
                        InMemoryHistoricalDrawStore(
                            listOf(storedAt(clock, LotteryType.SUPER_LOTTO, STALE_HISTORY_AGE)),
                        ),
                )

            controller.showTrendChart()

            val chart = currentTrendChart(controller)
            assertEquals("26500", assertIs<TrendChartContent.Ready>(chart.content).snapshot.lastIssue.value)
            assertEquals(HistoricalDrawRefreshStatus.Failed("测试断网"), chart.historyRefresh)
        }

    /** 手动刷新在本机数据未过期时也只做一次增量核对。 */
    @Test
    fun manualRefreshRequestsIncrementalEvenWhenFresh() =
        runTest {
            val clock = MutableTestClock()
            val history = ScriptedHistoryRepository(clock)
            val controller =
                createHistoryController(
                    clock = clock,
                    history = history,
                    store =
                        InMemoryHistoricalDrawStore(
                            listOf(storedAt(clock, LotteryType.SUPER_LOTTO, ageMillis = 0L)),
                        ),
                )
            controller.showTrendChart()
            assertTrue(history.requests.isEmpty())
            history.newestOrdinal = 501

            controller.updateTrendChart(TrendChartAction.Refresh)

            assertEquals(listOf(LotteryType.SUPER_LOTTO to 100), history.requests)
            val chart = currentTrendChart(controller)
            assertEquals("26501", assertIs<TrendChartContent.Ready>(chart.content).snapshot.lastIssue.value)
        }

    /** 增量核对期间切换彩种不污染界面；切回时直接使用已刷新的本机数据，不再联网。 */
    @Test
    fun lotterySwitchDuringRefreshKeepsRefreshedCache() =
        runTest {
            val clock = MutableTestClock()
            val history = ScriptedHistoryRepository(clock, newestOrdinal = 503)
            val gate = CompletableDeferred<Unit>()
            history.gate = gate
            val controller =
                createHistoryController(
                    clock = clock,
                    history = history,
                    store =
                        InMemoryHistoricalDrawStore(
                            listOf(
                                storedAt(clock, LotteryType.SUPER_LOTTO, STALE_HISTORY_AGE),
                                storedAt(clock, LotteryType.DOUBLE_COLOR_BALL, ageMillis = 0L),
                            ),
                        ),
                )

            val entering = launch { controller.showTrendChart() }
            history.started.await()
            controller.updateTrendChart(TrendChartAction.ChangeLotteryType(LotteryType.DOUBLE_COLOR_BALL))
            gate.complete(Unit)
            entering.join()

            val switched = currentTrendChart(controller)
            assertEquals(LotteryType.DOUBLE_COLOR_BALL, switched.lotteryType)
            assertEquals("2026500", assertIs<TrendChartContent.Ready>(switched.content).snapshot.lastIssue.value)
            controller.updateTrendChart(TrendChartAction.ChangeLotteryType(LotteryType.SUPER_LOTTO))

            val back = currentTrendChart(controller)
            assertEquals("26503", assertIs<TrendChartContent.Ready>(back.content).snapshot.lastIssue.value)
            assertEquals(listOf(LotteryType.SUPER_LOTTO to 100), history.requests)
        }

    /** 在关于页清除本机历史开奖后不影响其他状态，再次进入走势页会完整重新加载。 */
    @Test
    fun clearingHistoricalDrawCacheFromAboutForcesFullReload() =
        runTest {
            val clock = MutableTestClock()
            val history = ScriptedHistoryRepository(clock)
            val store = InMemoryHistoricalDrawStore(listOf(storedAt(clock, LotteryType.SUPER_LOTTO, ageMillis = 0L)))
            val controller = createHistoryController(clock = clock, history = history, store = store)
            controller.showTrendChart()
            controller.showAbout()

            controller.clearHistoricalDrawCache()

            val about = assertIs<AppScreen.About>(controller.uiState.value.screen)
            assertFalse(about.isClearingHistory)
            assertTrue(about.historyMessage.orEmpty().contains("已清除"))
            assertTrue(store.sets.isEmpty())
            controller.showTrendChart()
            assertEquals(listOf(LotteryType.SUPER_LOTTO to 500), history.requests)
            val chart = currentTrendChart(controller)
            assertEquals("26500", assertIs<TrendChartContent.Ready>(chart.content).snapshot.lastIssue.value)
        }

    /** 创建接入本机历史缓存和可脚本官网仓库的控制器。 */
    private fun createHistoryController(
        clock: MutableTestClock,
        history: ScriptedHistoryRepository,
        store: InMemoryHistoricalDrawStore,
    ): LotteryAppController =
        createController(
            repository = CountingDrawRepository(),
            historicalDrawRepository = history,
            historicalDrawStore = store,
            clock = clock,
        )

    /** 返回当前走势页状态。 */
    private fun currentTrendChart(controller: LotteryAppController): TrendChartState =
        assertIs<AppScreen.Trends>(controller.uiState.value.screen).chart

    /** 生成以第 500 期结尾、按指定年龄核对过的本机集合。 */
    private fun storedAt(
        clock: MutableTestClock,
        lotteryType: LotteryType,
        ageMillis: Long,
    ) = testStoredHistory(lotteryType, newestOrdinal = 500, fetchedAtEpochMillis = clock.nowEpochMillis - ageMillis)

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
        ticketRecordStore: TicketRecordStore? = null,
        ticketRecordFileExchange: TicketRecordFileExchange? = null,
        ocrConfidenceDiagnostics: OcrConfidenceDiagnostics = OcrConfidenceDiagnostics.Disabled,
        historicalDrawRepository: HistoricalDrawRepository? = null,
        historicalDrawStore: HistoricalDrawStore? = null,
        clock: Clock = MutableTestClock(),
        drawResultStore: DrawResultStore? = null,
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
                historicalDrawRepository = historicalDrawRepository,
                historicalDrawStore = historicalDrawStore,
                drawResultStore = drawResultStore,
                prizeCalculator = LotteryPrizeCalculator(),
                appPaths = appPaths,
                ticketValidator = TicketValidator(),
                isDemo = true,
                usesRealImageAcquisition = false,
                usesRealRecognition = usesRealRecognition,
                usesRealDrawData = usesRealDrawData,
                ticketRecordStore = ticketRecordStore,
                ticketRecordFileExchange = ticketRecordFileExchange,
                ocrConfidenceDiagnostics = ocrConfidenceDiagnostics,
            ),
            // 测试在调用协程内同步执行计算，保持虚拟时间调度与断言时序稳定。
            computationContext = EmptyCoroutineContext,
            clock = clock,
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

    /** 创建一条由测试预置、可直接用于二次查询的本机记录。 */
    private fun storedTicketRecord(ticket: ConfirmedTicket): StoredTicketRecord =
        StoredTicketRecord(
            id = TEST_RECORD_ID,
            displayName = "测试票",
            ticket = ticket,
            acquisitionSource = TicketAcquisitionSource.SYSTEM_PICKER,
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            dataVersion = CURRENT_TICKET_RECORD_DATA_VERSION,
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

    /** 第二次查询时已开奖期次直接取自本机，只补查未开奖期次；全部完成后不再联网。 */
    @Test
    fun multiPeriodRequeryUsesStoredResultsAndOnlyQueriesPendingIssues() =
        runTest {
            val store = InMemoryDrawResultStore()
            val repository =
                StagedDrawRepository(
                    "26090" to DrawQueryResult.Success(drawFor("26090")),
                    "26091" to DrawQueryResult.Success(drawFor("26091")),
                )
            val draft = validDraft().copy(issue = "26090", periodCount = 4, paidAmountFen = 1_200L)

            val first = confirmWithDrawResultStore(draft, repository, store)
            val firstScreen = assertIs<AppScreen.MultiPeriodVerificationResult>(first.uiState.value.screen)
            assertEquals(2, firstScreen.verifiedPeriodCount)
            assertEquals(listOf("26090", "26091", "26092"), repository.queriedIssues)
            assertEquals(1, repository.drawnIssuesCalls)
            assertEquals(listOf("26090", "26091"), store.all.map { it.issue.value }.sorted())
            assertTrue(
                firstScreen.periodResults.filterIsInstance<PeriodVerification.Verified>().none { it.isFromLocalStore },
            )

            repository.resetCounters()
            val second = confirmWithDrawResultStore(draft, repository, store)
            val secondScreen = assertIs<AppScreen.MultiPeriodVerificationResult>(second.uiState.value.screen)
            assertEquals(listOf("26092"), repository.queriedIssues)
            assertEquals(1, repository.drawnIssuesCalls)
            assertTrue(
                secondScreen.periodResults
                    .take(2)
                    .all { assertIs<PeriodVerification.Verified>(it).isFromLocalStore },
            )

            repository.results["26092"] = DrawQueryResult.Success(drawFor("26092"))
            repository.results["26093"] = DrawQueryResult.Success(drawFor("26093"))
            repository.resetCounters()
            val third = confirmWithDrawResultStore(draft, repository, store)
            assertTrue(assertIs<AppScreen.MultiPeriodVerificationResult>(third.uiState.value.screen).isConclusive)
            assertEquals(listOf("26092", "26093"), repository.queriedIssues)

            repository.resetCounters()
            val fourth = confirmWithDrawResultStore(draft, repository, store)
            val fourthScreen = assertIs<AppScreen.MultiPeriodVerificationResult>(fourth.uiState.value.screen)
            assertTrue(fourthScreen.isConclusive)
            assertEquals(emptyList(), repository.queriedIssues)
            assertEquals(0, repository.drawnIssuesCalls)
            assertTrue(fourthScreen.periodResults.all { assertIs<PeriodVerification.Verified>(it).isFromLocalStore })
        }

    /** 按开奖日程未到最早开奖时刻时不联网，并停止后续期次；用户主动重试或到点后才联网。 */
    @Test
    fun scheduleSkipsNetworkUntilEarliestDrawTime() =
        runTest {
            // 26091 期于 2026-08-12（周三）开奖，下一期最早为 08-15（周六）21:25。
            val clock = MutableTestClock(beijingMillis(2026, 8, 15, 21, 24))
            val store = InMemoryDrawResultStore(listOf(drawFor("26091")))
            val single = StagedDrawRepository()

            val controller = confirmWithDrawResultStore(validDraft().copy(issue = "26092"), single, store, clock)

            val notDue = assertIs<AppScreen.DrawUnavailable>(controller.uiState.value.screen)
            assertEquals(DrawStatus.NOT_PUBLISHED, notDue.status)
            assertFalse(notDue.wasQueried)
            assertTrue(notDue.message.contains("未到开奖时间"), notDue.message)
            assertTrue(notDue.message.contains("08-15（周六）21:25"), notDue.message)
            assertEquals(emptyList(), single.queriedIssues)

            controller.retryDrawQuery()
            assertTrue(assertIs<AppScreen.DrawUnavailable>(controller.uiState.value.screen).wasQueried)
            assertEquals(listOf("26092"), single.queriedIssues)

            val multi = StagedDrawRepository()
            val multiController =
                confirmWithDrawResultStore(
                    validDraft().copy(issue = "26091", periodCount = 3, paidAmountFen = 900L),
                    multi,
                    store,
                    clock,
                )
            val screen = assertIs<AppScreen.MultiPeriodVerificationResult>(multiController.uiState.value.screen)
            assertTrue(assertIs<PeriodVerification.Verified>(screen.periodResults[0]).isFromLocalStore)
            val skipped = assertIs<PeriodVerification.Unavailable>(screen.periodResults[1])
            assertFalse(skipped.wasQueried)
            assertTrue(skipped.message.contains("未到开奖时间"), skipped.message)
            assertFalse(assertIs<PeriodVerification.Unavailable>(screen.periodResults[2]).wasQueried)
            assertEquals(emptyList(), multi.queriedIssues)
            assertEquals(0, multi.drawnIssuesCalls)

            clock.nowEpochMillis = beijingMillis(2026, 8, 15, 21, 25)
            val due = StagedDrawRepository()
            confirmWithDrawResultStore(validDraft().copy(issue = "26092"), due, store, clock)
            assertEquals(listOf("26092"), due.queriedIssues)
        }

    /** 本票命中但金额待定的期次每次重查直到确认；号码已定且本票未中奖的期次直接使用本机结果。 */
    @Test
    fun pendingPayoutIsRequeriedUntilConfirmedWhileNonWinningNumbersAreFinal() =
        runTest {
            val store = InMemoryDrawResultStore(listOf(drawFor("26091", status = DrawStatus.FINAL_NUMBERS)))
            val repository = StagedDrawRepository("26091" to DrawQueryResult.Success(drawFor("26091")))

            val first = confirmWithDrawResultStore(validDraft(), repository, store)
            assertFalse(assertIs<AppScreen.VerificationResult>(first.uiState.value.screen).isFromLocalStore)
            assertEquals(listOf("26091"), repository.queriedIssues)
            assertEquals(DrawStatus.FINAL_PAYOUT, store.get(LotteryType.SUPER_LOTTO, "26091")?.status)

            repository.resetCounters()
            val second = confirmWithDrawResultStore(validDraft(), repository, store)
            assertTrue(assertIs<AppScreen.VerificationResult>(second.uiState.value.screen).isFromLocalStore)
            assertEquals(emptyList(), repository.queriedIssues)

            val missed =
                drawFor("26091", status = DrawStatus.FINAL_NUMBERS)
                    .copy(primaryNumbers = listOf(1, 3, 5, 8, 10), secondaryNumbers = listOf(1, 2))
            val untouched = StagedDrawRepository()
            val third = confirmWithDrawResultStore(validDraft(), untouched, InMemoryDrawResultStore(listOf(missed)))
            val result = assertIs<AppScreen.VerificationResult>(third.uiState.value.screen)
            assertEquals(PrizeCheckStatus.NO_WIN, result.prizeCheckResult.status)
            assertTrue(result.isFromLocalStore)
            assertEquals(emptyList(), untouched.queriedIssues)
        }

    /** 断网时保留本机结果并提示；尚未发布不删除本机结果；官网冲突时删除本机结果。 */
    @Test
    fun failedRefreshKeepsStoredResultAndConflictDeletesIt() =
        runTest {
            val store = InMemoryDrawResultStore(listOf(drawFor("26091", status = DrawStatus.FINAL_NUMBERS)))
            val repository =
                StagedDrawRepository(
                    "26091" to DrawQueryResult.Unavailable(DrawStatus.NETWORK_UNAVAILABLE, "测试网络不可用"),
                )

            val controller = confirmWithDrawResultStore(validDraft(), repository, store)
            val kept = assertIs<AppScreen.VerificationResult>(controller.uiState.value.screen)
            assertTrue(kept.isFromLocalStore)
            assertTrue(kept.refreshNotice.orEmpty().contains("测试网络不可用"))

            repository.results["26091"] = DrawQueryResult.Unavailable(DrawStatus.NOT_PUBLISHED, "该期开奖结果尚未发布")
            controller.retryDrawQuery(forceRefresh = true)
            assertEquals(
                DrawStatus.NOT_PUBLISHED,
                assertIs<AppScreen.DrawUnavailable>(controller.uiState.value.screen).status,
            )
            assertTrue(store.get(LotteryType.SUPER_LOTTO, "26091") != null)

            repository.results["26091"] = DrawQueryResult.Unavailable(DrawStatus.CONFLICT, "两个官方数据面号码不一致")
            controller.retryDrawQuery()
            assertEquals(
                DrawStatus.CONFLICT,
                assertIs<AppScreen.DrawUnavailable>(controller.uiState.value.screen).status,
            )
            assertNull(store.get(LotteryType.SUPER_LOTTO, "26091"))
            assertEquals(listOf(Issue("26091")), store.deletedIssues)
        }

    /** 重新联网核对忽略本机结果并覆盖；全部完成的多期票重查时全部期次重新联网。 */
    @Test
    fun forceRefreshIgnoresStoredResultsAndOverwritesThem() =
        runTest {
            val stored = drawFor("26091")
            val refreshed = stored.copy(evidence = stored.evidence.copy(fetchedAtEpochMillis = 99L))
            val store = InMemoryDrawResultStore(listOf(stored))
            val repository = StagedDrawRepository("26091" to DrawQueryResult.Success(refreshed))

            val controller = confirmWithDrawResultStore(validDraft(), repository, store)
            assertTrue(assertIs<AppScreen.VerificationResult>(controller.uiState.value.screen).isFromLocalStore)
            assertEquals(emptyList(), repository.queriedIssues)

            controller.retryDrawQuery(forceRefresh = true)
            assertFalse(assertIs<AppScreen.VerificationResult>(controller.uiState.value.screen).isFromLocalStore)
            assertEquals(listOf("26091"), repository.queriedIssues)
            assertEquals(refreshed, store.get(LotteryType.SUPER_LOTTO, "26091"))

            val multiStore = InMemoryDrawResultStore(listOf(drawFor("26090"), drawFor("26091")))
            val multi =
                StagedDrawRepository(
                    "26090" to DrawQueryResult.Success(drawFor("26090")),
                    "26091" to DrawQueryResult.Success(drawFor("26091")),
                )
            val multiController =
                confirmWithDrawResultStore(
                    validDraft().copy(issue = "26090", periodCount = 2, paidAmountFen = 600L),
                    multi,
                    multiStore,
                )
            assertTrue(
                assertIs<AppScreen.MultiPeriodVerificationResult>(multiController.uiState.value.screen).isConclusive,
            )
            assertEquals(emptyList(), multi.queriedIssues)
            assertEquals(0, multi.drawnIssuesCalls)

            multiController.retryDrawQuery()
            assertEquals(listOf("26090", "26091"), multi.queriedIssues)
            assertEquals(1, multi.drawnIssuesCalls)
        }

    /** 演示数据不写入本机开奖缓存。 */
    @Test
    fun demoDrawDataIsNeverStored() =
        runTest {
            val store = InMemoryDrawResultStore()
            val controller =
                createController(
                    repository = CountingDrawRepository(),
                    ticketParser = TicketParser { TicketParseResult.ReadyForReview(validDraft()) },
                    drawResultStore = store,
                )
            controller.startAnalysis(ImageAcquisitionSource.SYSTEM_PICKER)

            controller.confirmTicket()

            assertIs<AppScreen.DemoComplete>(controller.uiState.value.screen)
            assertEquals(0, store.saveCount)
        }

    /** 关于页清除同时删除已开奖查询结果，提示保留“已清除”。 */
    @Test
    fun clearingFromAboutAlsoClearsStoredDrawResults() =
        runTest {
            val store = InMemoryDrawResultStore(listOf(drawFor("26091")))
            val controller =
                createController(
                    repository = StagedDrawRepository(),
                    usesRealDrawData = true,
                    drawResultStore = store,
                )
            controller.showAbout()

            controller.clearHistoricalDrawCache()

            assertEquals(emptyList(), store.all)
            val about = assertIs<AppScreen.About>(controller.uiState.value.screen)
            assertTrue(about.historyMessage.orEmpty().contains("已清除"))
        }

    /** 以真实数据模式和指定本机缓存完成一次新票确认查询，模拟每次重新打开应用。 */
    private suspend fun confirmWithDrawResultStore(
        draft: TicketDraft,
        repository: DrawRepository,
        store: InMemoryDrawResultStore,
        clock: Clock = MutableTestClock(),
    ): LotteryAppController {
        val controller =
            createController(
                repository = repository,
                usesRealDrawData = true,
                ticketParser = TicketParser { TicketParseResult.ReadyForReview(draft) },
                clock = clock,
                drawResultStore = store,
            )
        controller.startAnalysis(ImageAcquisitionSource.SYSTEM_PICKER)
        controller.confirmTicket()
        return controller
    }

    /** 以测试票号码为基础构造指定期次的开奖。 */
    private fun drawFor(
        issue: String,
        status: DrawStatus = DrawStatus.FINAL_PAYOUT,
    ): DrawResult = verifiedDraw().copy(issue = Issue(issue), status = status)

    /** 返回北京时间对应的 Unix 毫秒。 */
    private fun beijingMillis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ): Long =
        LocalDateTime(year, month, day, hour, minute)
            .toInstant(TimeZone.of("Asia/Shanghai"))
            .toEpochMilliseconds()

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

    /** 按期号返回预置结果、其余期号视为尚未发布的仓库，记录单期查询和期号列表调用。 */
    private class StagedDrawRepository(
        vararg initial: Pair<String, DrawQueryResult>,
    ) : DrawRepository {
        /** 按期号预置的查询结果，测试可随时修改。 */
        val results = mutableMapOf(*initial)

        /** 按调用顺序记录的单期查询期号。 */
        val queriedIssues = mutableListOf<String>()

        /** 已开奖期号列表的调用次数。 */
        var drawnIssuesCalls: Int = 0
            private set

        /** 清空调用记录。 */
        fun resetCounters() {
            queriedIssues.clear()
            drawnIssuesCalls = 0
        }

        /** 返回预置结果或尚未发布。 */
        override suspend fun getDraw(
            lotteryType: LotteryType,
            issue: Issue,
        ): DrawQueryResult {
            queriedIssues += issue.value
            return results[issue.value]
                ?: DrawQueryResult.Unavailable(DrawStatus.NOT_PUBLISHED, "该期开奖结果尚未发布")
        }

        /** 返回从起始期号开始、预置为成功的连续期号。 */
        override suspend fun getDrawnIssues(
            lotteryType: LotteryType,
            firstIssue: Issue,
            maxCount: Int,
        ): List<Issue> {
            drawnIssuesCalls += 1
            return results
                .filterValues { it is DrawQueryResult.Success }
                .keys
                .sorted()
                .dropWhile { it != firstIssue.value }
                .take(maxCount)
                .map(::Issue)
        }
    }

    /** 模拟年度换期前后官网已开奖期次变化的多期仓库。 */
    private inner class YearBoundaryDrawRepository : DrawRepository {
        /** 官网当前已开奖的期号，测试可模拟新年度开奖。 */
        var drawnIssues = listOf(Issue("26149"), Issue("26150"))

        /** 按实际调用顺序保存的精确期号。 */
        val queriedIssues = mutableListOf<Issue>()

        /** 已开奖期号返回核验结果，其余期号视为尚未发布。 */
        override suspend fun getDraw(
            lotteryType: LotteryType,
            issue: Issue,
        ): DrawQueryResult {
            queriedIssues += issue
            return if (issue in drawnIssues) {
                DrawQueryResult.Success(verifiedDraw().copy(issue = issue))
            } else {
                DrawQueryResult.Unavailable(DrawStatus.NOT_PUBLISHED, "该期开奖结果尚未发布")
            }
        }

        /** 返回从起始期号开始的已开奖期号。 */
        override suspend fun getDrawnIssues(
            lotteryType: LotteryType,
            firstIssue: Issue,
            maxCount: Int,
        ): List<Issue> = drawnIssues.dropWhile { it != firstIssue }.take(maxCount)
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

    /** 记录控制器读写调用，并可暂停或拒绝保存的最小内存票据仓库。 */
    private class TrackingTicketRecordStore(
        /** 初始可供记录页读取的票据。 */
        initialRecords: List<StoredTicketRecord> = emptyList(),
        /** 是否暂停保存直到测试显式放行。 */
        private val suspendSave: Boolean = false,
        /** 是否让每次保存抛出受控异常。 */
        private val failSave: Boolean = false,
        /** 测试配置的逻辑导出结果。 */
        private val exportResult: TicketRecordExport? = null,
        /** 测试配置的逻辑导入预检结果。 */
        private val inspectImportResult: TicketRecordImportPreview? = null,
        /** 每次导入事务按调用顺序返回的结果。 */
        private val importResults: List<TicketRecordImportResult> = emptyList(),
    ) : TicketRecordStore {
        /** 内部可变记录流。 */
        private val mutableRecords = MutableStateFlow(initialRecords)

        /** 测试可观察的只读记录流。 */
        override val records: Flow<List<StoredTicketRecord>> = mutableRecords

        /** 当前内存中的记录快照。 */
        val currentRecords: List<StoredTicketRecord>
            get() = mutableRecords.value

        /** 只保存控制器本轮新建的记录，不包含初始数据。 */
        val savedRecords = mutableListOf<StoredTicketRecord>()

        /** 已收到的保存调用次数。 */
        var saveCallCount: Int = 0
            private set

        /** 已收到的票面替换调用次数。 */
        var replaceCallCount: Int = 0
            private set

        /** 已收到的导入预检调用次数。 */
        var inspectImportCallCount: Int = 0
            private set

        /** 最近一次导入预检收到的文件字节。 */
        var inspectedImportContent: ByteArray? = null
            private set

        /** 导入事务按调用顺序收到的冲突策略。 */
        val importPolicies = mutableListOf<TicketRecordImportConflictPolicy>()

        /** 导入事务按调用顺序收到的文件字节。 */
        val importedContents = mutableListOf<ByteArray>()

        /** 下一次导入调用对应的预置结果索引。 */
        private var importResultIndex: Int = 0

        /** 首次保存已经进入仓库的信号。 */
        val saveStarted = CompletableDeferred<Unit>()

        /** 暂停保存时由测试控制的放行信号。 */
        private val saveRelease = CompletableDeferred<Unit>()

        /** 当前仓库是否已收到关闭调用。 */
        var isClosed: Boolean = false
            private set

        /** 保存一条调用记录，并按测试配置选择等待或失败。 */
        override suspend fun save(
            ticket: ConfirmedTicket,
            acquisitionSource: TicketAcquisitionSource,
            displayName: String?,
        ): StoredTicketRecord {
            saveCallCount += 1
            saveStarted.complete(Unit)
            if (suspendSave) {
                saveRelease.await()
            }
            if (failSave) {
                throw IllegalStateException("测试保存失败")
            }
            val record =
                StoredTicketRecord(
                    id = "10000000-0000-4000-8000-${saveCallCount.toString().padStart(12, '0')}",
                    displayName = displayName?.trim() ?: "测试保存票",
                    ticket = ticket,
                    acquisitionSource = acquisitionSource,
                    createdAtEpochMillis = saveCallCount.toLong(),
                    updatedAtEpochMillis = saveCallCount.toLong(),
                    dataVersion = CURRENT_TICKET_RECORD_DATA_VERSION,
                )
            savedRecords += record
            mutableRecords.value = listOf(record) + mutableRecords.value
            return record
        }

        /** 按 UUID 替换内存记录的票面，不存在时返回 `null`。 */
        override suspend fun replaceTicket(
            id: String,
            ticket: ConfirmedTicket,
        ): StoredTicketRecord? {
            replaceCallCount += 1
            if (failSave) {
                throw IllegalStateException("测试替换失败")
            }
            val existing = mutableRecords.value.firstOrNull { it.id == id } ?: return null
            val replaced = existing.copy(ticket = ticket, updatedAtEpochMillis = existing.updatedAtEpochMillis + 1L)
            mutableRecords.value = mutableRecords.value.map { record -> if (record.id == id) replaced else record }
            savedRecords.indices
                .filter { index -> savedRecords[index].id == id }
                .forEach { index -> savedRecords[index] = replaced }
            return replaced
        }

        /** 返回指定 UUID 的当前内存记录。 */
        override suspend fun findById(id: String): StoredTicketRecord? =
            mutableRecords.value.firstOrNull { it.id == id }

        /** 修改指定记录名称，不存在时返回 `false`。 */
        override suspend fun rename(
            id: String,
            displayName: String,
        ): Boolean {
            var renamed = false
            mutableRecords.value =
                mutableRecords.value.map { record ->
                    if (record.id == id) {
                        renamed = true
                        record.copy(
                            displayName = displayName.trim(),
                            updatedAtEpochMillis =
                                record.updatedAtEpochMillis + 1L,
                        )
                    } else {
                        record
                    }
                }
            return renamed
        }

        /** 删除指定记录，不存在时返回 `false`。 */
        override suspend fun delete(id: String): Boolean {
            val previous = mutableRecords.value
            mutableRecords.value = previous.filterNot { it.id == id }
            return mutableRecords.value.size != previous.size
        }

        /** 清空内存记录并返回删除数量。 */
        override suspend fun clear(): Int {
            val count = mutableRecords.value.size
            mutableRecords.value = emptyList()
            return count
        }

        /** 返回测试配置的逻辑导出；未配置时拒绝意外调用。 */
        override suspend fun exportPackage(): TicketRecordExport = exportResult ?: error("当前测试未配置逻辑导出")

        /** 记录预检字节并返回测试配置的摘要。 */
        override suspend fun inspectImport(content: ByteArray): TicketRecordImportPreview {
            inspectImportCallCount += 1
            inspectedImportContent = content.copyOf()
            return inspectImportResult ?: error("当前测试未配置逻辑导入预检")
        }

        /** 记录冲突策略和文件字节，并按调用顺序返回预置事务结果。 */
        override suspend fun importPackage(
            content: ByteArray,
            conflictPolicy: TicketRecordImportConflictPolicy,
        ): TicketRecordImportResult {
            importPolicies += conflictPolicy
            importedContents += content.copyOf()
            val result = importResults.getOrNull(importResultIndex) ?: error("当前测试未配置逻辑导入事务")
            importResultIndex += 1
            return result
        }

        /** 结束暂停中的保存调用。 */
        fun releaseSave() {
            saveRelease.complete(Unit)
        }

        /** 记录仓库生命周期已经结束。 */
        override fun close() {
            isClosed = true
        }
    }

    /** 记录系统文件接口调用并返回预置结果。 */
    private class TrackingTicketRecordFileExchange(
        /** 导入选择器需要返回的结果。 */
        private val readResult: TicketRecordFileReadResult = TicketRecordFileReadResult.Cancelled,
        /** 导出选择器需要返回的结果。 */
        private val writeResult: TicketRecordFileWriteResult = TicketRecordFileWriteResult.Cancelled,
    ) : TicketRecordFileExchange {
        /** 最近一次导出收到的建议文件名。 */
        var writtenFileName: String? = null
            private set

        /** 最近一次导出收到的完整文件字节。 */
        var writtenContent: ByteArray? = null
            private set

        /** 返回预置的导入文件选择结果。 */
        override suspend fun readImportPackage(): TicketRecordFileReadResult = readResult

        /** 记录导出参数并返回预置的系统保存结果。 */
        override suspend fun writeExportPackage(
            suggestedFileName: String,
            content: ByteArray,
        ): TicketRecordFileWriteResult {
            writtenFileName = suggestedFileName
            writtenContent = content.copyOf()
            return writeResult
        }
    }

    /** 由测试控制导入文件选择完成时机的系统文件接口。 */
    private class SuspendedTicketRecordFileExchange : TicketRecordFileExchange {
        /** 导入读取已经开始的信号。 */
        val readStarted = CompletableDeferred<Unit>()

        /** 测试稍后提供的导入读取结果。 */
        private val readCompletion = CompletableDeferred<TicketRecordFileReadResult>()

        /** 发出开始信号并等待测试提供文件选择结果。 */
        override suspend fun readImportPackage(): TicketRecordFileReadResult {
            readStarted.complete(Unit)
            return readCompletion.await()
        }

        /** 当前测试不允许触发导出选择器。 */
        override suspend fun writeExportPackage(
            suggestedFileName: String,
            content: ByteArray,
        ): TicketRecordFileWriteResult = error("当前测试不应触发逻辑导出")

        /** 完成正在等待的导入文件读取。 */
        fun completeRead(result: TicketRecordFileReadResult) {
            readCompletion.complete(result)
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

    /** 按彩种返回 500 期规范化开奖并记录查询次数的走势图测试仓库。 */
    private class CountingHistoricalDrawRepository(
        /** 非空时让所有查询返回该测试错误。 */
        var failureMessage: String? = null,
    ) : HistoricalDrawRepository {
        /** 每个彩种在当前测试中的查询次数。 */
        private val queryCounts = mutableMapOf<LotteryType, Int>()

        /** 返回测试用完整历史开奖或当前指定错误。 */
        override suspend fun getLatestDraws(
            lotteryType: LotteryType,
            count: Int,
        ): HistoricalDrawQueryResult {
            queryCounts[lotteryType] = queryCount(lotteryType) + 1
            val message = failureMessage
            if (message != null) {
                return HistoricalDrawQueryResult.Unavailable(
                    reason = HistoricalDrawFailureReason.NETWORK_UNAVAILABLE,
                    message = message,
                )
            }
            return HistoricalDrawQueryResult.Success(
                draws = historicalDraws(lotteryType).takeLast(count),
                sourceName = "测试官网",
                fetchedAtEpochMillis = 1_787_689_800_000L,
            )
        }

        /** 返回指定彩种已经触发的查询次数。 */
        fun queryCount(lotteryType: LotteryType): Int = queryCounts[lotteryType] ?: 0

        /** 创建按开奖时间正序排列的 500 期合法测试数据。 */
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

        /** 测试清扫不额外记录单张图片删除。 */
        override fun clearTemporaryImages() = Unit

        /** 记录清理请求并模拟删除成功。 */
        override suspend fun deleteTemporaryImage(imageRef: ImageRef): Boolean {
            deletedImageIds += imageRef.id
            return true
        }
    }

    /** 测试记录使用的稳定标识。 */
    private companion object {
        /** 二次查询夹具使用的标准 UUID。 */
        const val TEST_RECORD_ID = "00000000-0000-4000-8000-000000000001"
    }
}

/** 超过自动核对间隔的本机历史开奖年龄，单位为毫秒。 */
private const val STALE_HISTORY_AGE = 31L * 60L * 1_000L

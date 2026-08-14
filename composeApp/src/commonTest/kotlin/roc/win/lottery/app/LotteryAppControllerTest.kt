package roc.win.lottery.app

import kotlinx.coroutines.test.runTest
import roc.win.lottery.Platform
import roc.win.lottery.data.DrawRepository
import roc.win.lottery.data.FakeDrawRepository
import roc.win.lottery.domain.BetLineDraft
import roc.win.lottery.domain.LotteryType
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
import roc.win.lottery.recognition.RecognitionResult
import roc.win.lottery.recognition.TicketParseResult
import roc.win.lottery.recognition.TicketParser
import roc.win.lottery.recognition.TicketRecognizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 应用确认闸门和流程状态测试。 */
class LotteryAppControllerTest {
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
            val controller = createController(repository, paths, usesRealRecognition = true)
            controller.startAnalysis(ImageAcquisitionSource.SYSTEM_PICKER)

            val review = assertIs<AppScreen.Review>(controller.uiState.value.screen)
            assertTrue(review.evaluation.canConfirm)
            controller.confirmTicket()

            assertEquals(1, repository.queryCount)
            assertEquals(listOf("b1-demo-ticket"), paths.deletedImageIds)
            assertIs<AppScreen.DemoComplete>(controller.uiState.value.screen)
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
            assertTrue(paths.deletedImageIds.isEmpty())

            controller.updateTicketReview(TicketReviewAction.ChangeIssue("26091"))

            assertTrue(assertIs<AppScreen.Review>(controller.uiState.value.screen).evaluation.canConfirm)
            assertTrue(paths.deletedImageIds.isEmpty())
        }

    /** 不带安全草稿的人工修正结果仍应进入错误页并立即清理临时图片。 */
    @Test
    fun unrecoverableParseResultClearsTemporaryImage() =
        runTest {
            val paths = TrackingAppPaths()
            val parser = TicketParser { TicketParseResult.NeedsCorrection("无法安全划分投注行") }
            val controller =
                createController(
                    repository = CountingDrawRepository(),
                    appPaths = paths,
                    ticketParser = parser,
                )

            controller.startAnalysis(ImageAcquisitionSource.SYSTEM_PICKER)

            val error = assertIs<AppScreen.Error>(controller.uiState.value.screen)
            assertEquals("需要人工修正", error.title)
            assertEquals(listOf("b1-demo-ticket"), paths.deletedImageIds)
        }

    /** 创建使用可计数仓库和真实保守解析器的开发控制器。 */
    private fun createController(
        repository: DrawRepository,
        appPaths: AppPaths = FakeAppPaths(),
        usesRealRecognition: Boolean = false,
        ticketRecognizer: TicketRecognizer = FakeTicketRecognizer(),
        imageAcquirer: ImageAcquirer = FakeImageAcquirer(supportsCamera = false),
        imageQualityAnalyzer: ImageQualityAnalyzer = ImageDimensionQualityAnalyzer(),
        ticketParser: TicketParser = ConservativeTicketParser(),
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
                appPaths = appPaths,
                ticketValidator = TicketValidator(),
                isDemo = true,
                usesRealImageAcquisition = false,
                usesRealRecognition = usesRealRecognition,
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

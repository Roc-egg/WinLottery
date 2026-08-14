package roc.win.lottery.app

import kotlinx.coroutines.test.runTest
import roc.win.lottery.Platform
import roc.win.lottery.data.DrawRepository
import roc.win.lottery.data.FakeDrawRepository
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
import roc.win.lottery.recognition.TicketRecognizer
import kotlin.test.Test
import kotlin.test.assertEquals
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

            controller.confirmDemoTicket()

            assertEquals(1, repository.queryCount)
            assertIs<AppScreen.DemoComplete>(controller.uiState.value.screen)
        }

    /** 真实 OCR 草稿在人工编辑和真实开奖接入前不得进入 Fake 开奖流程。 */
    @Test
    fun realRecognitionCannotEnterDemoDrawQuery() =
        runTest {
            val repository = CountingDrawRepository()
            val controller = createController(repository, usesRealRecognition = true)
            controller.startAnalysis(ImageAcquisitionSource.SYSTEM_PICKER)

            controller.confirmDemoTicket()

            assertEquals(0, repository.queryCount)
            val error = assertIs<AppScreen.Error>(controller.uiState.value.screen)
            assertEquals("识别 PoC 已完成", error.title)
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

    /** 创建使用可计数仓库和真实保守解析器的开发控制器。 */
    private fun createController(
        repository: DrawRepository,
        appPaths: AppPaths = FakeAppPaths(),
        usesRealRecognition: Boolean = false,
        ticketRecognizer: TicketRecognizer = FakeTicketRecognizer(),
        imageAcquirer: ImageAcquirer = FakeImageAcquirer(supportsCamera = false),
        imageQualityAnalyzer: ImageQualityAnalyzer = ImageDimensionQualityAnalyzer(),
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
                ticketParser = ConservativeTicketParser(),
                drawRepository = repository,
                appPaths = appPaths,
                ticketValidator = TicketValidator(),
                isDemo = true,
                usesRealImageAcquisition = false,
                usesRealRecognition = usesRealRecognition,
            ),
        )
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

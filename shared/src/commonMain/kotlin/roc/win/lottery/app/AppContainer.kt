package roc.win.lottery.app

import roc.win.lottery.Platform
import roc.win.lottery.data.DrawRepository
import roc.win.lottery.data.FakeDrawRepository
import roc.win.lottery.domain.LotteryPrizeCalculator
import roc.win.lottery.domain.PrizeCalculator
import roc.win.lottery.domain.TicketValidator
import roc.win.lottery.persistence.TicketRecordStore
import roc.win.lottery.recognition.AppPaths
import roc.win.lottery.recognition.ConservativeTicketParser
import roc.win.lottery.recognition.FakeAppPaths
import roc.win.lottery.recognition.FakeImageAcquirer
import roc.win.lottery.recognition.FakeTicketRecognizer
import roc.win.lottery.recognition.ImageAcquirer
import roc.win.lottery.recognition.ImageDimensionQualityAnalyzer
import roc.win.lottery.recognition.ImageQualityAnalyzer
import roc.win.lottery.recognition.TicketParser
import roc.win.lottery.recognition.TicketRecognizer

/**
 * 应用级依赖容器，使用构造注入保持平台实现可替换。
 *
 * @property platform 当前平台能力。
 * @property imageAcquirer 图片采集能力。
 * @property imageQualityAnalyzer OCR 前的本地图片质量检查能力。
 * @property ticketRecognizer 本地 OCR 能力。
 * @property ticketParser 票面结构解析能力。
 * @property drawRepository 开奖查询能力。
 * @property prizeCalculator 本地中奖规则计算能力。
 * @property appPaths 临时文件管理能力。
 * @property ticketValidator 票面领域校验器。
 * @property isDemo 是否展示开发阶段能力边界。
 * @property usesRealImageAcquisition 图片采集是否由真实平台实现提供。
 * @property usesRealRecognition 本地 OCR 是否由真实平台实现提供。
 * @property usesRealDrawData 开奖查询是否使用真实官网数据。
 * @property ticketRecordStore 当前平台的本机结构化票据仓库；未接入的平台为 `null`。
 * @property ticketRecordFileExchange 当前平台的系统逻辑包文件接口；未接入的平台为 `null`。
 * @property ocrConfidenceDiagnostics Debug 包使用的匿名字段置信度诊断，默认禁用。
 */
class AppContainer(
    val platform: Platform,
    val imageAcquirer: ImageAcquirer,
    val imageQualityAnalyzer: ImageQualityAnalyzer,
    val ticketRecognizer: TicketRecognizer,
    val ticketParser: TicketParser,
    val drawRepository: DrawRepository,
    val prizeCalculator: PrizeCalculator,
    val appPaths: AppPaths,
    val ticketValidator: TicketValidator,
    val isDemo: Boolean,
    val usesRealImageAcquisition: Boolean,
    val usesRealRecognition: Boolean,
    val usesRealDrawData: Boolean,
    val ticketRecordStore: TicketRecordStore? = null,
    val ticketRecordFileExchange: TicketRecordFileExchange? = null,
    val ocrConfidenceDiagnostics: OcrConfidenceDiagnostics = OcrConfidenceDiagnostics.Disabled,
) {
    /** 关闭容器持有的本机数据库连接。 */
    fun close() {
        ticketRecordStore?.close()
    }

    /** 创建无相机、无真实 OCR、无网络也能演示状态流的开发容器。 */
    companion object {
        /**
         * 创建开发演示依赖。
         *
         * @param platform 当前运行平台能力。
         * @return 外部能力使用 Fake、票面结构使用真实保守解析器的容器。
         */
        fun createDemo(platform: Platform): AppContainer =
            AppContainer(
                platform = platform,
                imageAcquirer = FakeImageAcquirer(platform.supportsCamera),
                imageQualityAnalyzer = ImageDimensionQualityAnalyzer(),
                ticketRecognizer = FakeTicketRecognizer(),
                ticketParser = ConservativeTicketParser(),
                drawRepository = FakeDrawRepository(),
                prizeCalculator = LotteryPrizeCalculator(),
                appPaths = FakeAppPaths(),
                ticketValidator = TicketValidator(),
                isDemo = true,
                usesRealImageAcquisition = false,
                usesRealRecognition = false,
                usesRealDrawData = false,
            )
    }
}

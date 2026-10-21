package roc.win.lottery

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import roc.win.lottery.app.AndroidTicketRecordFileExchange
import roc.win.lottery.app.AppContainer
import roc.win.lottery.app.LogOcrConfidenceDiagnostics
import roc.win.lottery.app.LotteryAppSession
import roc.win.lottery.app.OcrConfidenceDiagnostics
import roc.win.lottery.app.withOneShotConflictInjection
import roc.win.lottery.data.AndroidSuperLottoPdfTextExtractor
import roc.win.lottery.data.OfficialDrawRepository
import roc.win.lottery.data.ResponsesAiAnalysisProvider
import roc.win.lottery.domain.LotteryPrizeCalculator
import roc.win.lottery.domain.TicketValidator
import roc.win.lottery.persistence.createAndroidLocalStorage
import roc.win.lottery.recognition.AndroidAppPaths
import roc.win.lottery.recognition.AndroidLuminanceImageDecoder
import roc.win.lottery.recognition.AndroidPhotoPickerImageAcquirer
import roc.win.lottery.recognition.AndroidPpOcrTicketRecognizer
import roc.win.lottery.recognition.ConservativeTicketParser
import roc.win.lottery.recognition.ImageDimensionQualityAnalyzer
import roc.win.lottery.recognition.ImageQualityAnalyzerChain
import roc.win.lottery.recognition.PixelImageQualityAnalyzer

/** Android 平台能力。 */
class AndroidPlatform : Platform {
    /** 当前 Android 系统版本。 */
    override val name: String = "Android ${Build.VERSION.SDK_INT}"

    /** Android V1 支持应用内拍照。 */
    override val supportsCamera: Boolean = true
}

/** 返回 Android 平台能力。 */
actual fun getPlatform(): Platform = AndroidPlatform()

/**
 * 跨 Android 配置变更保留应用会话的 ViewModel。
 *
 * 识别会话、网络客户端和数据库只在首次创建界面时建立一次；Activity 最终结束时统一释放。
 * 系统选择器回调与具体 Activity 绑定，每个新的界面 Activity 都要通过 [attach] 重新注册。
 *
 * @param application 访问私有目录、模型资源和系统内容提供方的应用对象。
 * @param debugConflictIssue Debug 包一次性冲突验收注入的期号，Release 包忽略。
 */
class AndroidLotteryAppViewModel(
    application: Application,
    debugConflictIssue: String?,
) : ViewModel() {
    /** 应用私有临时票图目录。 */
    private val appPaths = AndroidAppPaths(application)

    /** 跨 Activity 重建保留的系统选图与拍照采集器。 */
    private val imageAcquirer = AndroidPhotoPickerImageAcquirer(application, appPaths)

    /** 跨 Activity 重建保留的逻辑票据包文件交换器。 */
    private val ticketRecordFileExchange = AndroidTicketRecordFileExchange(application)

    /** 跨配置变更保留的应用会话，界面操作在 ViewModel 作用域内执行。 */
    val session: LotteryAppSession =
        LotteryAppSession(
            container = createAndroidContainer(application, debugConflictIssue),
            scope = viewModelScope,
        )

    /** 把系统选择器回调注册到新的界面 Activity，必须在其进入启动状态前调用。 */
    fun attach(activity: ComponentActivity) {
        imageAcquirer.attach(activity)
        ticketRecordFileExchange.attach(activity)
    }

    /** Activity 最终结束时释放识别会话、网络客户端、数据库连接和临时票图。 */
    override fun onCleared() {
        session.close()
    }

    /** 创建使用真实本地识别、保守解析器、官网开奖仓库和本地规则引擎的应用容器。 */
    private fun createAndroidContainer(
        application: Application,
        debugConflictIssue: String?,
    ): AppContainer {
        val isDebuggable = application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val localStorage = createAndroidLocalStorage(application)
        val officialDrawRepository =
            OfficialDrawRepository(
                superLottoPdfTextExtractor = AndroidSuperLottoPdfTextExtractor(application),
            )
        return AppContainer(
            platform = AndroidPlatform(),
            imageAcquirer = imageAcquirer,
            imageQualityAnalyzer =
                ImageQualityAnalyzerChain(
                    listOf(
                        ImageDimensionQualityAnalyzer(),
                        PixelImageQualityAnalyzer(AndroidLuminanceImageDecoder()),
                    ),
                ),
            ticketRecognizer = AndroidPpOcrTicketRecognizer(application),
            ticketParser = ConservativeTicketParser(),
            drawRepository =
                officialDrawRepository.withOneShotConflictInjection(
                    rawIssue = debugConflictIssue,
                    isDebugEnabled = isDebuggable,
                ),
            historicalDrawRepository = officialDrawRepository,
            aiAnalysisProvider = ResponsesAiAnalysisProvider(),
            prizeCalculator = LotteryPrizeCalculator(),
            appPaths = appPaths,
            ticketValidator = TicketValidator(),
            isDemo = true,
            usesRealImageAcquisition = true,
            usesRealRecognition = true,
            usesRealDrawData = true,
            ticketRecordStore = localStorage.ticketRecordStore,
            historicalDrawStore = localStorage.historicalDrawStore,
            drawResultStore = localStorage.drawResultStore,
            ticketRecordFileExchange = ticketRecordFileExchange,
            ocrConfidenceDiagnostics = createAndroidOcrConfidenceDiagnostics(isDebuggable),
        )
    }
}

/**
 * 取得当前 Activity 跨配置变更保留的应用会话，并把系统选择器回调注册到该 Activity。
 *
 * 必须在 Activity 的 `onCreate` 中调用。
 */
fun ComponentActivity.attachLotteryAppSession(): LotteryAppSession {
    val application = application
    val debugConflictIssue = intent.getStringExtra(DEBUG_CONFLICT_ISSUE_EXTRA)
    val factory =
        viewModelFactory {
            initializer { AndroidLotteryAppViewModel(application, debugConflictIssue) }
        }
    val viewModel = ViewModelProvider.create(this, factory)[AndroidLotteryAppViewModel::class]
    viewModel.attach(this)
    return viewModel.session
}

/** 只为可调试 Android 包创建匿名置信度日志，Release 保持完全禁用。 */
private fun createAndroidOcrConfidenceDiagnostics(isDebuggable: Boolean): OcrConfidenceDiagnostics =
    if (isDebuggable) {
        LogOcrConfidenceDiagnostics { line -> Log.i(OCR_CONFIDENCE_LOG_TAG, line) }
    } else {
        OcrConfidenceDiagnostics.Disabled
    }

/** Android 匿名 OCR 置信度日志的专用标签。 */
private const val OCR_CONFIDENCE_LOG_TAG = "WinLotteryOcrConfidence"

/** Android Debug 包一次性冲突验收使用的 Intent extra。 */
private const val DEBUG_CONFLICT_ISSUE_EXTRA = "roc.win.lottery.debug.CONFLICT_ISSUE"

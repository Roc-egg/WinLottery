package roc.win.lottery

import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import roc.win.lottery.app.AppContainer
import roc.win.lottery.app.LogOcrConfidenceDiagnostics
import roc.win.lottery.app.OcrConfidenceDiagnostics
import roc.win.lottery.app.withOneShotConflictInjection
import roc.win.lottery.data.AndroidSuperLottoPdfTextExtractor
import roc.win.lottery.data.OfficialDrawRepository
import roc.win.lottery.domain.LotteryPrizeCalculator
import roc.win.lottery.domain.TicketValidator
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
 * 创建已接入 Android 图片采集、本地 OCR 和真实开奖查询的移动容器。
 *
 * @param activity 用于注册系统选图结果和读取应用私有目录的宿主 Activity。
 * @return 使用真实本地识别、保守解析器、官网开奖仓库和本地规则引擎的应用容器。
 */
fun createAndroidRecognitionContainer(activity: ComponentActivity): AppContainer {
    val appPaths = AndroidAppPaths(activity.applicationContext)
    val isDebuggable = activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    val officialDrawRepository =
        OfficialDrawRepository(
            superLottoPdfTextExtractor =
                AndroidSuperLottoPdfTextExtractor(activity.applicationContext),
        )
    return AppContainer(
        platform = AndroidPlatform(),
        imageAcquirer = AndroidPhotoPickerImageAcquirer(activity, appPaths),
        imageQualityAnalyzer =
            ImageQualityAnalyzerChain(
                listOf(
                    ImageDimensionQualityAnalyzer(),
                    PixelImageQualityAnalyzer(AndroidLuminanceImageDecoder()),
                ),
            ),
        ticketRecognizer = AndroidPpOcrTicketRecognizer(activity.applicationContext),
        ticketParser = ConservativeTicketParser(),
        drawRepository =
            officialDrawRepository.withOneShotConflictInjection(
                rawIssue = activity.intent.getStringExtra(DEBUG_CONFLICT_ISSUE_EXTRA),
                isDebugEnabled = isDebuggable,
            ),
        prizeCalculator = LotteryPrizeCalculator(),
        appPaths = appPaths,
        ticketValidator = TicketValidator(),
        isDemo = true,
        usesRealImageAcquisition = true,
        usesRealRecognition = true,
        usesRealDrawData = true,
        ocrConfidenceDiagnostics = createAndroidOcrConfidenceDiagnostics(activity),
    )
}

/** 只为可调试 Android 包创建匿名置信度日志，Release 保持完全禁用。 */
private fun createAndroidOcrConfidenceDiagnostics(activity: ComponentActivity): OcrConfidenceDiagnostics =
    if (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
        LogOcrConfidenceDiagnostics { line -> Log.i(OCR_CONFIDENCE_LOG_TAG, line) }
    } else {
        OcrConfidenceDiagnostics.Disabled
    }

/** Android 匿名 OCR 置信度日志的专用标签。 */
private const val OCR_CONFIDENCE_LOG_TAG = "WinLotteryOcrConfidence"

/** Android Debug 包一次性冲突验收使用的 Intent extra。 */
private const val DEBUG_CONFLICT_ISSUE_EXTRA = "roc.win.lottery.debug.CONFLICT_ISSUE"

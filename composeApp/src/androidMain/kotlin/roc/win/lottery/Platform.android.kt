package roc.win.lottery

import android.os.Build
import androidx.activity.ComponentActivity
import roc.win.lottery.app.AppContainer
import roc.win.lottery.data.FakeDrawRepository
import roc.win.lottery.domain.TicketValidator
import roc.win.lottery.recognition.AndroidAppPaths
import roc.win.lottery.recognition.AndroidPhotoPickerImageAcquirer
import roc.win.lottery.recognition.ConservativeTicketParser
import roc.win.lottery.recognition.ImageDimensionQualityAnalyzer
import roc.win.lottery.recognition.MlKitChineseTicketRecognizer

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
 * 创建已接入 Android Photo Picker 和 ML Kit OCR 的 B3 PoC 容器。
 *
 * @param activity 用于注册系统选图结果和读取应用私有目录的宿主 Activity。
 * @return 使用真实本地识别、保守解析器和 Fake 开奖仓库的应用容器。
 */
fun createAndroidRecognitionContainer(activity: ComponentActivity): AppContainer {
    val appPaths = AndroidAppPaths(activity.applicationContext)
    return AppContainer(
        platform = AndroidPlatform(),
        imageAcquirer = AndroidPhotoPickerImageAcquirer(activity, appPaths),
        imageQualityAnalyzer = ImageDimensionQualityAnalyzer(),
        ticketRecognizer = MlKitChineseTicketRecognizer(activity.applicationContext),
        ticketParser = ConservativeTicketParser(),
        drawRepository = FakeDrawRepository(),
        appPaths = appPaths,
        ticketValidator = TicketValidator(),
        isDemo = true,
        usesRealImageAcquisition = true,
        usesRealRecognition = true,
    )
}

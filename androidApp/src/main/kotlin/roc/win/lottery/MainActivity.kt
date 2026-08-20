package roc.win.lottery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

/** Android 应用宿主，只负责装配共享 Compose 界面。 */
class MainActivity : ComponentActivity() {
    /** 创建 Activity 并启用边到边共享界面。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        triggerDebugCrashAcceptance(intent)
        enableEdgeToEdge()
        val container = createAndroidRecognitionContainer(this)

        setContent {
            App(container)
        }
    }

    /** 接收系统内存收紧通知，并仅向 Debug 验收记录匿名级别。 */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        recordDebugTrimMemoryAcceptance(level)
    }
}

/** Android Studio 中的共享首页预览。 */
@Preview
@Composable
fun AppAndroidPreview() {
    App()
}

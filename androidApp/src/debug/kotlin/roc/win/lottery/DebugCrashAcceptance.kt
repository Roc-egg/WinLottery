package roc.win.lottery

import android.content.Intent
import android.util.Log

/** 仅在 Debug 包收到显式验收参数时制造主线程未捕获异常。 */
internal fun triggerDebugCrashAcceptance(intent: Intent) {
    if (intent.getBooleanExtra(DEBUG_CRASH_ON_CREATE_EXTRA, false)) {
        error(CONTROLLED_CRASH_MARKER)
    }
}

/** 仅在 Debug 包记录系统实际送达的内存收紧级别。 */
internal fun recordDebugTrimMemoryAcceptance(level: Int) {
    Log.i(DEBUG_MEMORY_PRESSURE_LOG_TAG, "$MEMORY_PRESSURE_MARKER:$level")
}

/** Android Debug 崩溃验收使用的 Intent extra。 */
private const val DEBUG_CRASH_ON_CREATE_EXTRA = "roc.win.lottery.debug.CRASH_ON_CREATE"

/** 两端崩溃证据共用的匿名固定标记。 */
private const val CONTROLLED_CRASH_MARKER = "WinLotteryControlledCrashAcceptance"

/** Android Debug 内存压力验收使用的日志标签。 */
private const val DEBUG_MEMORY_PRESSURE_LOG_TAG = "WinLotteryMemoryPressure"

/** 两端内存压力回调共用的匿名固定标记。 */
private const val MEMORY_PRESSURE_MARKER = "WinLotteryMemoryPressureAcceptance"

package roc.win.lottery

import android.content.Intent

/** 仅在 Debug 包收到显式验收参数时制造主线程未捕获异常。 */
internal fun triggerDebugCrashAcceptance(intent: Intent) {
    if (intent.getBooleanExtra(DEBUG_CRASH_ON_CREATE_EXTRA, false)) {
        error(CONTROLLED_CRASH_MARKER)
    }
}

/** Android Debug 崩溃验收使用的 Intent extra。 */
private const val DEBUG_CRASH_ON_CREATE_EXTRA = "roc.win.lottery.debug.CRASH_ON_CREATE"

/** 两端崩溃证据共用的匿名固定标记。 */
private const val CONTROLLED_CRASH_MARKER = "WinLotteryControlledCrashAcceptance"

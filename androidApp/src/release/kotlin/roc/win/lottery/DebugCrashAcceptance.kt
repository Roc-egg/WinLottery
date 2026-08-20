package roc.win.lottery

import android.content.Intent

/** Release 包始终忽略外部崩溃验收参数。 */
@Suppress("UNUSED_PARAMETER")
internal fun triggerDebugCrashAcceptance(intent: Intent) = Unit

/** Release 包不记录内存压力验收标记。 */
@Suppress("UNUSED_PARAMETER")
internal fun recordDebugTrimMemoryAcceptance(level: Int) = Unit

package roc.win.lottery

import android.content.Intent

/** Release 包始终忽略外部崩溃验收参数。 */
@Suppress("UNUSED_PARAMETER")
internal fun triggerDebugCrashAcceptance(intent: Intent) = Unit

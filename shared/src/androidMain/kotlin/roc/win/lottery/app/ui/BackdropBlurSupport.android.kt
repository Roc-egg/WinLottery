package roc.win.lottery.app.ui

import android.os.Build

/** Android 12 起图层才支持模糊效果。 */
internal actual val supportsBackdropBlur: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

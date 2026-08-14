package roc.win.lottery.app.ui

import androidx.compose.ui.graphics.ImageBitmap
import roc.win.lottery.recognition.ImageRef

/** 桌面 OCR 尚未进入 V1 范围，因此桌面端不创建票图校正预览。 */
@Suppress("UNUSED_PARAMETER")
internal actual suspend fun decodeTicketPreview(
    imageRef: ImageRef,
    maxEdgePixels: Int,
): ImageBitmap? = null

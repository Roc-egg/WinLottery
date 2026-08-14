package roc.win.lottery.app.ui

import androidx.compose.ui.graphics.ImageBitmap
import roc.win.lottery.recognition.ImageRef

/**
 * 从当前流程的私有临时文件解码受尺寸限制的票图预览。
 *
 * @param imageRef 当前流程图片引用。
 * @param maxEdgePixels 预览位图允许的最长边。
 * @return 可显示的预览位图；文件失效或解码失败时为 `null`。
 */
internal expect suspend fun decodeTicketPreview(
    imageRef: ImageRef,
    maxEdgePixels: Int,
): ImageBitmap?

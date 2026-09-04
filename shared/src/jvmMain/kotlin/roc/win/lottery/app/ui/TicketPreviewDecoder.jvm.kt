package roc.win.lottery.app.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import roc.win.lottery.recognition.ImageRef
import java.io.File

/** 桌面端使用 Skia 解码私有图片，供 OCR 校正或对照手动录入。 */
internal actual suspend fun decodeTicketPreview(
    imageRef: ImageRef,
    maxEdgePixels: Int,
): ImageBitmap? =
    withContext(Dispatchers.IO) {
        runCatching { decodeScaledTicketPreview(imageRef, maxEdgePixels) }.getOrNull()
    }

/** 读取私有图片，并按二次幂缩小到预览尺寸。 */
private fun decodeScaledTicketPreview(
    imageRef: ImageRef,
    maxEdgePixels: Int,
): ImageBitmap? {
    if (maxEdgePixels <= 0) return null
    val file = File(imageRef.localPath)
    if (!file.isFile) return null
    val data = Data.makeFromBytes(file.readBytes())
    try {
        val codec = Codec.makeFromData(data)
        try {
            val sourceInfo = codec.imageInfo
            if (sourceInfo.width <= 0 || sourceInfo.height <= 0) return null
            val divisor = calculatePreviewDivisor(sourceInfo.width, sourceInfo.height, maxEdgePixels)
            val targetInfo =
                sourceInfo.withWidthHeight(
                    width = (sourceInfo.width / divisor).coerceAtLeast(1),
                    height = (sourceInfo.height / divisor).coerceAtLeast(1),
                )
            val bitmap = Bitmap()
            if (!bitmap.allocPixels(targetInfo)) {
                bitmap.close()
                return null
            }
            return try {
                codec.readPixels(bitmap)
                bitmap.asComposeImageBitmap()
            } catch (error: Throwable) {
                bitmap.close()
                throw error
            }
        } finally {
            codec.close()
        }
    } finally {
        data.close()
    }
}

/** 返回图片解码器稳定支持的二次幂缩放除数。 */
private fun calculatePreviewDivisor(
    width: Int,
    height: Int,
    maxEdgePixels: Int,
): Int {
    var divisor = 1
    val longestEdge = maxOf(width, height)
    while (longestEdge / divisor > maxEdgePixels) {
        divisor *= 2
    }
    return divisor
}

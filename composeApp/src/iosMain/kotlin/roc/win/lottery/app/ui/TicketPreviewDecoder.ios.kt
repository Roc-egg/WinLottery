package roc.win.lottery.app.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import roc.win.lottery.recognition.ImageRef

/** iOS 使用 Skia 编解码器直接生成受尺寸限制的票图预览。 */
internal actual suspend fun decodeTicketPreview(
    imageRef: ImageRef,
    maxEdgePixels: Int,
): ImageBitmap? =
    withContext(Dispatchers.Default) {
        runCatching { decodeScaledTicketPreview(imageRef, maxEdgePixels) }.getOrNull()
    }

/** 读取私有 JPEG，并按二次幂缩小到预览尺寸。 */
private fun decodeScaledTicketPreview(
    imageRef: ImageRef,
    maxEdgePixels: Int,
): ImageBitmap? {
    if (maxEdgePixels <= 0) return null
    val path = imageRef.localPath.toPath()
    if (!FileSystem.SYSTEM.exists(path)) return null
    val encodedBytes = FileSystem.SYSTEM.read(path) { readByteArray() }
    val data = Data.makeFromBytes(encodedBytes)
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

/** 返回 JPEG 解码器稳定支持的二次幂缩放除数。 */
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

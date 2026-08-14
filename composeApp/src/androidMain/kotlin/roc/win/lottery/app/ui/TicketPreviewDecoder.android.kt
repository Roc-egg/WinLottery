package roc.win.lottery.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import roc.win.lottery.recognition.ImageRef
import java.io.File

/** Android 使用采样解码生成不超过指定最长边的票图预览。 */
internal actual suspend fun decodeTicketPreview(
    imageRef: ImageRef,
    maxEdgePixels: Int,
): ImageBitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            val file = File(imageRef.localPath)
            if (!file.isFile || maxEdgePixels <= 0) return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

            val options =
                BitmapFactory.Options().apply {
                    inSampleSize = calculatePreviewSampleSize(bounds.outWidth, bounds.outHeight, maxEdgePixels)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return@runCatching null
            val scaled = decoded.scaleDownTo(maxEdgePixels)
            if (scaled !== decoded) decoded.recycle()
            scaled.asImageBitmap()
        }.getOrNull()
    }

/** 计算保持最长边接近目标值的二次幂采样率。 */
private fun calculatePreviewSampleSize(
    width: Int,
    height: Int,
    maxEdgePixels: Int,
): Int {
    var sampleSize = 1
    val longestEdge = maxOf(width, height)
    while (longestEdge / (sampleSize * 2) >= maxEdgePixels) {
        sampleSize *= 2
    }
    return sampleSize
}

/** 仅在采样结果仍超过上限时执行一次精确缩放。 */
private fun Bitmap.scaleDownTo(maxEdgePixels: Int): Bitmap {
    val longestEdge = maxOf(width, height)
    if (longestEdge <= maxEdgePixels) return this
    val scale = maxEdgePixels.toFloat() / longestEdge.toFloat()
    val targetWidth = (width * scale).toInt().coerceAtLeast(1)
    val targetHeight = (height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
}

package roc.win.lottery.recognition

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** 使用 Android BitmapFactory 生成受尺寸限制的本地亮度样本。 */
class AndroidLuminanceImageDecoder : LuminanceImageDecoder {
    /** 在默认计算调度器解码私有图片，不阻塞 Compose 主线程。 */
    override suspend fun decode(
        imageRef: ImageRef,
        maximumLongEdgePixels: Int,
    ): LuminanceImageSample? =
        withContext(Dispatchers.Default) {
            runCatching { decodeLocalImage(imageRef, maximumLongEdgePixels) }.getOrNull()
        }

    /** 先使用二次幂采样限制内存，再精确缩放并转换为亮度字节。 */
    private fun decodeLocalImage(
        imageRef: ImageRef,
        maximumLongEdgePixels: Int,
    ): LuminanceImageSample? {
        if (maximumLongEdgePixels <= 0) return null
        val file = File(imageRef.localPath)
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > maximumLongEdgePixels * 2) {
            sampleSize *= 2
        }
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        val scale = minOf(1f, maximumLongEdgePixels.toFloat() / maxOf(decoded.width, decoded.height))
        val targetWidth = (decoded.width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (decoded.height * scale).roundToInt().coerceAtLeast(1)
        val sampled =
            if (targetWidth == decoded.width && targetHeight == decoded.height) {
                decoded
            } else {
                Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
            }
        return try {
            val colors = IntArray(sampled.width * sampled.height)
            sampled.getPixels(colors, 0, sampled.width, 0, 0, sampled.width, sampled.height)
            val luminance =
                ByteArray(colors.size) { index ->
                    val color = colors[index]
                    val red = color shr RED_SHIFT_BITS and COLOR_CHANNEL_MASK
                    val green = color shr GREEN_SHIFT_BITS and COLOR_CHANNEL_MASK
                    val blue = color and COLOR_CHANNEL_MASK
                    (
                        (
                            red * RED_LUMINANCE_WEIGHT +
                                green * GREEN_LUMINANCE_WEIGHT +
                                blue * BLUE_LUMINANCE_WEIGHT +
                                LUMINANCE_ROUNDING_OFFSET
                        ) shr LUMINANCE_WEIGHT_SHIFT_BITS
                    ).toByte()
                }
            LuminanceImageSample(sampled.width, sampled.height, luminance)
        } finally {
            if (sampled !== decoded) sampled.recycle()
            decoded.recycle()
        }
    }

    /** Android ARGB 转换为整数亮度的固定参数。 */
    private companion object {
        /** 红色通道位移。 */
        const val RED_SHIFT_BITS = 16

        /** 绿色通道位移。 */
        const val GREEN_SHIFT_BITS = 8

        /** 单个颜色通道掩码。 */
        const val COLOR_CHANNEL_MASK = 0xFF

        /** 红色亮度整数权重。 */
        const val RED_LUMINANCE_WEIGHT = 77

        /** 绿色亮度整数权重。 */
        const val GREEN_LUMINANCE_WEIGHT = 150

        /** 蓝色亮度整数权重。 */
        const val BLUE_LUMINANCE_WEIGHT = 29

        /** 整数除法前的四舍五入偏移。 */
        const val LUMINANCE_ROUNDING_OFFSET = 128

        /** 三个亮度权重总和为 256，对应右移位数。 */
        const val LUMINANCE_WEIGHT_SHIFT_BITS = 8
    }
}

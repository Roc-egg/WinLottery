package roc.win.lottery.recognition

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/** 使用 JVM ImageIO 生成受尺寸限制的本地亮度样本。 */
class DesktopLuminanceImageDecoder : LuminanceImageDecoder {
    /** 在默认计算调度器解码私有图片，不阻塞 Compose 主线程。 */
    override suspend fun decode(
        imageRef: ImageRef,
        maximumLongEdgePixels: Int,
    ): LuminanceImageSample? =
        withContext(Dispatchers.Default) {
            runCatching { decodeLocalImage(imageRef, maximumLongEdgePixels) }.getOrNull()
        }

    /** 缩放私有 JPEG 副本并转换为与移动端相同权重的亮度字节。 */
    private fun decodeLocalImage(
        imageRef: ImageRef,
        maximumLongEdgePixels: Int,
    ): LuminanceImageSample? {
        if (maximumLongEdgePixels <= 0) return null
        val file = File(imageRef.localPath)
        if (!file.isFile) return null
        val decoded = ImageIO.read(file) ?: return null
        val scale = minOf(1.0, maximumLongEdgePixels.toDouble() / maxOf(decoded.width, decoded.height))
        val targetWidth = (decoded.width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (decoded.height * scale).roundToInt().coerceAtLeast(1)
        val sampled = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
        return try {
            val graphics = sampled.createGraphics()
            try {
                graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR,
                )
                graphics.drawImage(decoded, 0, 0, targetWidth, targetHeight, null)
            } finally {
                graphics.dispose()
            }
            val colors = IntArray(targetWidth * targetHeight)
            sampled.getRGB(0, 0, targetWidth, targetHeight, colors, 0, targetWidth)
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
            LuminanceImageSample(targetWidth, targetHeight, luminance)
        } finally {
            sampled.flush()
            decoded.flush()
        }
    }

    /** JVM ARGB 转换为整数亮度的固定参数。 */
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

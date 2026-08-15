package roc.win.lottery.recognition

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceGray
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIImage
import kotlin.math.roundToInt

/** 使用 iOS CoreGraphics 生成受尺寸限制的本地亮度样本。 */
@OptIn(ExperimentalForeignApi::class)
class IOSLuminanceImageDecoder : LuminanceImageDecoder {
    /** 在默认计算调度器解码私有图片，不阻塞 Compose 主线程。 */
    override suspend fun decode(
        imageRef: ImageRef,
        maximumLongEdgePixels: Int,
    ): LuminanceImageSample? =
        withContext(Dispatchers.Default) {
            runCatching { decodeLocalImage(imageRef, maximumLongEdgePixels) }.getOrNull()
        }

    /** 把归一化 JPEG 缩放并绘制到每像素一字节的灰度位图上下文。 */
    private fun decodeLocalImage(
        imageRef: ImageRef,
        maximumLongEdgePixels: Int,
    ): LuminanceImageSample? {
        if (maximumLongEdgePixels <= 0) return null
        val sourceImage = UIImage.imageWithContentsOfFile(imageRef.localPath) ?: return null
        val cgImage = sourceImage.CGImage ?: return null
        val sourceWidth = CGImageGetWidth(cgImage).toInt()
        val sourceHeight = CGImageGetHeight(cgImage).toInt()
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        val scale = minOf(1.0, maximumLongEdgePixels.toDouble() / maxOf(sourceWidth, sourceHeight))
        val targetWidth = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
        val luminance = ByteArray(targetWidth * targetHeight)
        val colorSpace = CGColorSpaceCreateDeviceGray() ?: return null
        return try {
            luminance.usePinned { pinned ->
                val context =
                    CGBitmapContextCreate(
                        data = pinned.addressOf(0),
                        width = targetWidth.toULong(),
                        height = targetHeight.toULong(),
                        bitsPerComponent = BITS_PER_COMPONENT.toULong(),
                        bytesPerRow = targetWidth.toULong(),
                        space = colorSpace,
                        bitmapInfo = CGImageAlphaInfo.kCGImageAlphaNone.value,
                    ) ?: return@usePinned null
                try {
                    CGContextDrawImage(
                        context,
                        CGRectMake(0.0, 0.0, targetWidth.toDouble(), targetHeight.toDouble()),
                        cgImage,
                    )
                    LuminanceImageSample(targetWidth, targetHeight, luminance)
                } finally {
                    CGContextRelease(context)
                }
            }
        } finally {
            CGColorSpaceRelease(colorSpace)
        }
    }

    /** iOS 灰度位图固定参数。 */
    private companion object {
        /** 每个灰度分量使用 8 位。 */
        const val BITS_PER_COMPONENT = 8
    }
}

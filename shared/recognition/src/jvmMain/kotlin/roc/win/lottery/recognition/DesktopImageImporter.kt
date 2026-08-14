package roc.win.lottery.recognition

import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.File
import java.util.UUID
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** 把桌面端选中的图片转换为方向归一且不含原始元数据的私有 JPEG。 */
internal class DesktopImageImporter(
    /** 私有临时图片路径管理器。 */
    private val appPaths: DesktopAppPaths,
) {
    /** 解码受支持图片、应用 EXIF 方向并重新编码；失败时删除半成品。 */
    fun importPrivateCopy(source: File): ImageAcquisitionResult {
        if (!source.isFile) return ImageAcquisitionResult.Failure("所选图片不存在或无法读取")
        var output: File? = null
        return try {
            val decoded = decodeSampledImage(source) ?: return ImageAcquisitionResult.Failure("无法解码所选图片")
            val normalized = orientImage(decoded, DesktopExifOrientationReader.read(source))
            if (normalized !== decoded) decoded.flush()
            output = File(appPaths.temporaryImageDirectory, "${UUID.randomUUID()}$JPEG_EXTENSION")
            if (!writeJpeg(normalized, output)) {
                normalized.flush()
                output.delete()
                return ImageAcquisitionResult.Failure("无法创建私有临时图片")
            }
            val width = normalized.width
            val height = normalized.height
            normalized.flush()
            ImageAcquisitionResult.Success(
                ImageRef(
                    id = output.nameWithoutExtension,
                    localPath = output.absolutePath,
                    mimeType = JPEG_MIME_TYPE,
                    widthPixels = width,
                    heightPixels = height,
                ),
            )
        } catch (_: Exception) {
            output?.delete()
            ImageAcquisitionResult.Failure("所选图片无法处理")
        }
    }

    /** 使用 ImageIO 读取图片，并通过整数采样限制解码后的最长边。 */
    private fun decodeSampledImage(source: File): BufferedImage? {
        ImageIO.createImageInputStream(source)?.use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) return null
            val reader = readers.next()
            return try {
                reader.input = input
                val width = reader.getWidth(FIRST_IMAGE_INDEX)
                val height = reader.getHeight(FIRST_IMAGE_INDEX)
                if (width <= 0 || height <= 0) return null
                var sampleSize = 1
                while (ceilDiv(maxOf(width, height), sampleSize) > MAX_IMAGE_EDGE_PIXELS) {
                    sampleSize *= 2
                }
                val parameters = reader.defaultReadParam
                parameters.setSourceSubsampling(sampleSize, sampleSize, 0, 0)
                reader.read(FIRST_IMAGE_INDEX, parameters)
            } finally {
                reader.dispose()
            }
        }
        return null
    }

    /** 对正整数执行不溢出的向上取整除法。 */
    private fun ceilDiv(
        dividend: Int,
        divisor: Int,
    ): Int = ((dividend.toLong() + divisor - 1L) / divisor).toInt()

    /** 按 EXIF 1 至 8 的定义执行旋转或镜像，并将透明区域铺为白色。 */
    internal fun orientImage(
        source: BufferedImage,
        orientation: Int,
    ): BufferedImage {
        val swapsDimensions = orientation in DIMENSION_SWAPPING_ORIENTATIONS
        val outputWidth = if (swapsDimensions) source.height else source.width
        val outputHeight = if (swapsDimensions) source.width else source.height
        val output = BufferedImage(outputWidth, outputHeight, BufferedImage.TYPE_INT_RGB)
        val graphics = output.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, outputWidth, outputHeight)
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.drawImage(source, orientationTransform(source, orientation), null)
        } finally {
            graphics.dispose()
        }
        return output
    }

    /** 创建把源像素坐标映射到归一化输出坐标的仿射变换。 */
    private fun orientationTransform(
        source: BufferedImage,
        orientation: Int,
    ): AffineTransform {
        val width = source.width.toDouble()
        val height = source.height.toDouble()
        return when (orientation) {
            2 -> AffineTransform(-1.0, 0.0, 0.0, 1.0, width, 0.0)
            3 -> AffineTransform(-1.0, 0.0, 0.0, -1.0, width, height)
            4 -> AffineTransform(1.0, 0.0, 0.0, -1.0, 0.0, height)
            5 -> AffineTransform(0.0, 1.0, 1.0, 0.0, 0.0, 0.0)
            6 -> AffineTransform(0.0, 1.0, -1.0, 0.0, height, 0.0)
            7 -> AffineTransform(0.0, -1.0, -1.0, 0.0, height, width)
            8 -> AffineTransform(0.0, -1.0, 1.0, 0.0, 0.0, width)
            else -> AffineTransform()
        }
    }

    /** 使用明确质量参数写入不携带原图元数据的 JPEG。 */
    private fun writeJpeg(
        image: BufferedImage,
        output: File,
    ): Boolean {
        val writers = ImageIO.getImageWritersByFormatName(JPEG_FORMAT_NAME)
        if (!writers.hasNext()) return false
        val writer = writers.next()
        return try {
            ImageIO.createImageOutputStream(output)?.use { stream ->
                writer.output = stream
                val parameters = writer.defaultWriteParam
                if (parameters.canWriteCompressed()) {
                    parameters.compressionMode = ImageWriteParam.MODE_EXPLICIT
                    parameters.compressionQuality = JPEG_QUALITY
                }
                writer.write(null, IIOImage(image, null, null), parameters)
                true
            } ?: false
        } finally {
            writer.dispose()
        }
    }

    /** 桌面图片处理常量。 */
    private companion object {
        /** OCR PoC 解码后允许的最长图片边。 */
        const val MAX_IMAGE_EDGE_PIXELS = 4096

        /** 多页格式只读取第一张图片。 */
        const val FIRST_IMAGE_INDEX = 0

        /** 会交换图片宽高的 EXIF 方向值。 */
        val DIMENSION_SWAPPING_ORIENTATIONS = 5..8

        /** 私有副本 JPEG 编码质量。 */
        const val JPEG_QUALITY = 0.95f

        /** 私有副本文件扩展名。 */
        const val JPEG_EXTENSION = ".jpg"

        /** 私有副本 MIME 类型。 */
        const val JPEG_MIME_TYPE = "image/jpeg"

        /** ImageIO 使用的 JPEG 格式名。 */
        const val JPEG_FORMAT_NAME = "jpeg"
    }
}

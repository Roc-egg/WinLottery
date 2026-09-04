package roc.win.lottery.recognition

import kotlinx.coroutines.test.runTest
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 桌面图片私有副本与路径边界测试。 */
class DesktopImageImporterTest {
    /** EXIF 1 至 8 的旋转和镜像都应把每个源像素映射到标准方向位置。 */
    @Test
    fun everyExifOrientationMapsPixelsToCanonicalPositions() {
        val root = createIsolatedDirectory("all-orientations")
        try {
            val importer = DesktopImageImporter(DesktopAppPaths(root))
            val source = createOrientationPattern()
            val expectedRows =
                mapOf(
                    1 to listOf("AB", "CD", "EF"),
                    2 to listOf("BA", "DC", "FE"),
                    3 to listOf("FE", "DC", "BA"),
                    4 to listOf("EF", "CD", "AB"),
                    5 to listOf("ACE", "BDF"),
                    6 to listOf("ECA", "FDB"),
                    7 to listOf("FDB", "ECA"),
                    8 to listOf("BDF", "ACE"),
                )

            expectedRows.forEach { (orientation, rows) ->
                val result = importer.orientImage(source, orientation)
                assertEquals(rows, result.toPatternRows(), "EXIF 方向 $orientation 的像素映射错误")
                result.flush()
            }
            source.flush()
        } finally {
            root.deleteRecursively()
        }
    }

    /** 带 EXIF 旋转的 JPEG 应归一宽高，并在重新编码后移除 EXIF。 */
    @Test
    fun exifOrientationIsAppliedAndMetadataIsRemoved() {
        val root = createIsolatedDirectory("orientation")
        try {
            val paths = DesktopAppPaths(root)
            val source = File(root, "source.jpg")
            source.writeBytes(withExifOrientation(createJpeg(width = 2, height = 3), orientation = 6))

            val result = DesktopImageImporter(paths).importPrivateCopy(source)

            val imageRef = assertIs<ImageAcquisitionResult.Success>(result).imageRef
            assertEquals(3, imageRef.widthPixels)
            assertEquals(2, imageRef.heightPixels)
            assertEquals("image/jpeg", imageRef.mimeType)
            val output = File(imageRef.localPath)
            assertEquals(File(paths.temporaryImageDirectory).canonicalFile, output.parentFile.canonicalFile)
            assertEquals(1, DesktopExifOrientationReader.read(output))
            assertFalse(output.readBytes().containsSequence(EXIF_IDENTIFIER))
        } finally {
            root.deleteRecursively()
        }
    }

    /** PNG `eXIf` 块中的方向字段也应被读取并用于归一宽高。 */
    @Test
    fun pngExifOrientationIsApplied() {
        val root = createIsolatedDirectory("png-orientation")
        try {
            val paths = DesktopAppPaths(root)
            val source = File(root, "source.png")
            val png = createPng(width = 2, height = 3)
            source.writeBytes(withPngExifOrientation(png, orientation = 8))

            val result = DesktopImageImporter(paths).importPrivateCopy(source)

            val imageRef = assertIs<ImageAcquisitionResult.Success>(result).imageRef
            assertEquals(3, imageRef.widthPixels)
            assertEquals(2, imageRef.heightPixels)
        } finally {
            root.deleteRecursively()
        }
    }

    /** 超过上限的图片应以整数采样缩小，且不得超过 4096 像素最长边。 */
    @Test
    fun oversizedImageIsSampledBeforePrivateEncoding() {
        val root = createIsolatedDirectory("large")
        try {
            val paths = DesktopAppPaths(root)
            val source = File(root, "large.png")
            val image = BufferedImage(8193, 8, BufferedImage.TYPE_INT_RGB)
            assertTrue(ImageIO.write(image, "png", source))
            image.flush()

            val result = DesktopImageImporter(paths).importPrivateCopy(source)

            val imageRef = assertIs<ImageAcquisitionResult.Success>(result).imageRef
            assertTrue(maxOf(imageRef.widthPixels ?: 0, imageRef.heightPixels ?: 0) <= 4096)
            assertEquals(2049, imageRef.widthPixels)
        } finally {
            root.deleteRecursively()
        }
    }

    /** 路径实现只能删除受控目录直属文件，不能删除外部文件。 */
    @Test
    fun deletionRejectsFilesOutsideTemporaryDirectory() =
        runTest {
            val root = createIsolatedDirectory("delete")
            try {
                val paths = DesktopAppPaths(root)
                val outside = File(root, "outside.jpg").apply { writeText("脱敏测试内容") }
                val imageRef =
                    ImageRef(
                        id = "outside",
                        localPath = outside.absolutePath,
                        mimeType = "image/jpeg",
                        widthPixels = null,
                        heightPixels = null,
                    )

                assertFalse(paths.deleteTemporaryImage(imageRef))
                assertTrue(outside.exists())
            } finally {
                root.deleteRecursively()
            }
        }

    /** 应用退出清扫应删除受控目录内的全部票图，同时保留目录本身。 */
    @Test
    fun clearTemporaryImagesRemovesAllOwnedFiles() {
        val root = createIsolatedDirectory("clear-all")
        try {
            val paths = DesktopAppPaths(root)
            val first = File(paths.temporaryImageDirectory, "first.jpg").apply { writeText("测试") }
            val second = File(paths.temporaryImageDirectory, "second.jpg").apply { writeText("测试") }

            paths.clearTemporaryImages()

            assertFalse(first.exists())
            assertFalse(second.exists())
            assertTrue(File(paths.temporaryImageDirectory).isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    /** 创建不含真实票面内容的纯色 JPEG。 */
    private fun createJpeg(
        width: Int,
        height: Int,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color.RED
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()
        return ByteArrayOutputStream().use { output ->
            assertTrue(ImageIO.write(image, "jpeg", output))
            image.flush()
            output.toByteArray()
        }
    }

    /** 创建不含真实票面内容的纯色 PNG。 */
    private fun createPng(
        width: Int,
        height: Int,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        return ByteArrayOutputStream().use { output ->
            assertTrue(ImageIO.write(image, "png", output))
            image.flush()
            output.toByteArray()
        }
    }

    /** 创建六个像素均使用不同颜色的方向测试图。 */
    private fun createOrientationPattern(): BufferedImage =
        BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB).apply {
            PATTERN_LABELS.forEachIndexed { index, label ->
                val x = index % width
                val y = index / width
                setRGB(x, y, PATTERN_COLORS.getValue(label))
            }
        }

    /** 把方向测试图还原为稳定的字母行，便于断言旋转和镜像结果。 */
    private fun BufferedImage.toPatternRows(): List<String> =
        (0 until height).map { y ->
            buildString {
                (0 until width).forEach { x ->
                    val color = getRGB(x, y) and RGB_MASK
                    append(PATTERN_COLORS.entries.single { it.value == color }.key)
                }
            }
        }

    /** 在 JPEG 的 SOI 后插入只包含方向字段的最小 EXIF APP1 段。 */
    private fun withExifOrientation(
        jpeg: ByteArray,
        orientation: Int,
    ): ByteArray {
        require(jpeg.size >= 2 && jpeg[0] == JPEG_MARKER_PREFIX && jpeg[1] == JPEG_START_OF_IMAGE)
        require(orientation in 1..8)
        val payload =
            byteArrayOf(
                'E'.code.toByte(),
                'x'.code.toByte(),
                'i'.code.toByte(),
                'f'.code.toByte(),
                0,
                0,
                'I'.code.toByte(),
                'I'.code.toByte(),
                42,
                0,
                8,
                0,
                0,
                0,
                1,
                0,
                0x12,
                0x01,
                3,
                0,
                1,
                0,
                0,
                0,
                orientation.toByte(),
                0,
                0,
                0,
                0,
                0,
                0,
                0,
            )
        val segmentLength = payload.size + JPEG_SEGMENT_LENGTH_BYTES
        return ByteArrayOutputStream().use { output ->
            output.write(jpeg, 0, JPEG_SOI_LENGTH)
            output.write(JPEG_MARKER_PREFIX.toInt())
            output.write(JPEG_APP1_MARKER.toInt())
            output.write(segmentLength ushr BYTE_BITS)
            output.write(segmentLength and BYTE_MASK)
            output.write(payload)
            output.write(jpeg, JPEG_SOI_LENGTH, jpeg.size - JPEG_SOI_LENGTH)
            output.toByteArray()
        }
    }

    /** 在 PNG 的 IHDR 后插入带正确 CRC 的标准 `eXIf` 块。 */
    private fun withPngExifOrientation(
        png: ByteArray,
        orientation: Int,
    ): ByteArray {
        require(png.size > PNG_AFTER_IHDR_OFFSET)
        val tiff = createLittleEndianTiffOrientation(orientation)
        val crc =
            CRC32()
                .apply {
                    update(PNG_EXIF_CHUNK_TYPE)
                    update(tiff)
                }.value
        return ByteArrayOutputStream().use { output ->
            output.write(png, 0, PNG_AFTER_IHDR_OFFSET)
            output.writeIntBigEndian(tiff.size.toLong())
            output.write(PNG_EXIF_CHUNK_TYPE)
            output.write(tiff)
            output.writeIntBigEndian(crc)
            output.write(png, PNG_AFTER_IHDR_OFFSET, png.size - PNG_AFTER_IHDR_OFFSET)
            output.toByteArray()
        }
    }

    /** 创建只包含 IFD0 方向字段且不包含任何子目录指针的最小 TIFF 数据。 */
    private fun createLittleEndianTiffOrientation(orientation: Int): ByteArray {
        require(orientation in 1..8)
        return byteArrayOf(
            'I'.code.toByte(),
            'I'.code.toByte(),
            42,
            0,
            8,
            0,
            0,
            0,
            1,
            0,
            0x12,
            0x01,
            3,
            0,
            1,
            0,
            0,
            0,
            orientation.toByte(),
            0,
            0,
            0,
            0,
            0,
            0,
            0,
        )
    }

    /** 按 PNG 固定大端序写入四字节无符号整数。 */
    private fun ByteArrayOutputStream.writeIntBigEndian(value: Long) {
        repeat(INT_BYTES) { index ->
            write((value ushr ((INT_BYTES - index - 1) * BYTE_BITS)).toInt() and BYTE_MASK)
        }
    }

    /** 判断字节数组是否包含给定连续字节。 */
    private fun ByteArray.containsSequence(sequence: ByteArray): Boolean =
        indices.any { start ->
            start + sequence.size <= size && sequence.indices.all { offset -> this[start + offset] == sequence[offset] }
        }

    /** 创建当前测试专用且可在结束后递归删除的目录。 */
    private fun createIsolatedDirectory(suffix: String): File =
        kotlin.io.path
            .createTempDirectory("winlottery-desktop-$suffix-")
            .toFile()

    /** 测试 JPEG 字节布局常量。 */
    private companion object {
        /** JPEG 标记前缀。 */
        const val JPEG_MARKER_PREFIX: Byte = -1

        /** JPEG SOI 标记值。 */
        const val JPEG_START_OF_IMAGE: Byte = -40

        /** JPEG APP1 标记值。 */
        const val JPEG_APP1_MARKER: Byte = -31

        /** JPEG SOI 固定字节数。 */
        const val JPEG_SOI_LENGTH = 2

        /** JPEG 段长度字段自身占用的字节数。 */
        const val JPEG_SEGMENT_LENGTH_BYTES = 2

        /** 单字节位数。 */
        const val BYTE_BITS = 8

        /** 单字节无符号掩码。 */
        const val BYTE_MASK = 0xff

        /** 四字节无符号整数字节数。 */
        const val INT_BYTES = 4

        /** PNG 签名、IHDR 块及其 CRC 之后的固定偏移。 */
        const val PNG_AFTER_IHDR_OFFSET = 33

        /** JPEG APP1 使用的 EXIF 标识。 */
        val EXIF_IDENTIFIER =
            byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)

        /** PNG 标准 EXIF 块类型。 */
        val PNG_EXIF_CHUNK_TYPE =
            byteArrayOf('e'.code.toByte(), 'X'.code.toByte(), 'I'.code.toByte(), 'f'.code.toByte())

        /** 忽略 BufferedImage 返回颜色中的 Alpha 通道。 */
        const val RGB_MASK = 0x00ffffff

        /** 源方向测试图按行排列的像素标签。 */
        val PATTERN_LABELS = listOf('A', 'B', 'C', 'D', 'E', 'F')

        /** 每个方向测试像素对应的唯一 RGB 颜色。 */
        val PATTERN_COLORS =
            mapOf(
                'A' to 0xff0000,
                'B' to 0x00ff00,
                'C' to 0x0000ff,
                'D' to 0xffff00,
                'E' to 0x00ffff,
                'F' to 0xff00ff,
            )
    }
}

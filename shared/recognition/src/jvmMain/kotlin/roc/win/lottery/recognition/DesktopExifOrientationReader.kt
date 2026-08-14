package roc.win.lottery.recognition

import java.io.File
import java.io.RandomAccessFile

/** 只读取 JPEG 或 PNG 中 TIFF IFD0 的方向字段，不解析 GPS 等其他元数据目录。 */
internal object DesktopExifOrientationReader {
    /**
     * 返回标准 EXIF 方向 `1..8`；文件不支持、字段缺失或结构损坏时保守返回标准方向。
     */
    fun read(source: File): Int =
        runCatching {
            RandomAccessFile(source, READ_ONLY_MODE).use { input ->
                when {
                    input.hasJpegSignature() -> readJpegOrientation(input)
                    input.hasPngSignature() -> readPngOrientation(input)
                    else -> NORMAL_ORIENTATION
                }
            }
        }.getOrDefault(NORMAL_ORIENTATION)

    /** 扫描 JPEG 段，只读取带 `Exif` 标识的 APP1 段。 */
    private fun readJpegOrientation(input: RandomAccessFile): Int {
        input.seek(JPEG_SIGNATURE.size.toLong())
        while (input.filePointer + JPEG_SEGMENT_HEADER_BYTES <= input.length()) {
            var prefix = input.readUnsignedByte()
            while (prefix != JPEG_MARKER_PREFIX && input.filePointer < input.length()) {
                prefix = input.readUnsignedByte()
            }
            if (prefix != JPEG_MARKER_PREFIX) return NORMAL_ORIENTATION
            var marker = input.readUnsignedByte()
            while (marker == JPEG_MARKER_PREFIX && input.filePointer < input.length()) {
                marker = input.readUnsignedByte()
            }
            if (marker == JPEG_END_OF_IMAGE || marker == JPEG_START_OF_SCAN) return NORMAL_ORIENTATION
            if (marker == JPEG_START_OF_IMAGE || marker == JPEG_TEMPORARY_MARKER || marker in JPEG_RESTART_MARKERS) {
                continue
            }

            val segmentLength = input.readUnsignedShort()
            if (segmentLength < JPEG_LENGTH_FIELD_BYTES) return NORMAL_ORIENTATION
            val payloadStart = input.filePointer
            val payloadLength = segmentLength.toLong() - JPEG_LENGTH_FIELD_BYTES
            val payloadEnd = payloadStart + payloadLength
            if (payloadEnd > input.length()) return NORMAL_ORIENTATION
            if (marker == JPEG_APP1_MARKER && payloadLength >= EXIF_IDENTIFIER.size + MINIMUM_TIFF_BYTES) {
                val identifier = ByteArray(EXIF_IDENTIFIER.size)
                input.readFully(identifier)
                if (identifier.contentEquals(EXIF_IDENTIFIER)) {
                    return readTiffOrientation(
                        input = input,
                        tiffStart = input.filePointer,
                        tiffLength = payloadEnd - input.filePointer,
                    )
                }
            }
            input.seek(payloadEnd)
        }
        return NORMAL_ORIENTATION
    }

    /** 扫描 PNG 块，只读取标准 `eXIf` 块中的 TIFF 数据。 */
    private fun readPngOrientation(input: RandomAccessFile): Int {
        input.seek(PNG_SIGNATURE.size.toLong())
        while (input.filePointer + PNG_CHUNK_HEADER_BYTES <= input.length()) {
            val chunkLength = input.readUnsignedIntBigEndian() ?: return NORMAL_ORIENTATION
            val chunkType = ByteArray(PNG_CHUNK_TYPE_BYTES)
            input.readFully(chunkType)
            val payloadStart = input.filePointer
            val payloadEnd = payloadStart + chunkLength
            val chunkEnd = payloadEnd + PNG_CRC_BYTES
            if (payloadEnd < payloadStart || chunkEnd > input.length()) return NORMAL_ORIENTATION
            if (chunkType.contentEquals(PNG_EXIF_CHUNK_TYPE)) {
                return readTiffOrientation(input, payloadStart, chunkLength)
            }
            if (chunkType.contentEquals(PNG_END_CHUNK_TYPE)) return NORMAL_ORIENTATION
            input.seek(chunkEnd)
        }
        return NORMAL_ORIENTATION
    }

    /** 读取 TIFF 头和 IFD0，遇到方向标签后立即返回，不跟随任何子目录指针。 */
    private fun readTiffOrientation(
        input: RandomAccessFile,
        tiffStart: Long,
        tiffLength: Long,
    ): Int {
        if (tiffLength < MINIMUM_TIFF_BYTES || !input.rangeFits(tiffStart, MINIMUM_TIFF_BYTES, tiffStart, tiffLength)) {
            return NORMAL_ORIENTATION
        }
        input.seek(tiffStart)
        val byteOrderBytes = ByteArray(TIFF_BYTE_ORDER_BYTES)
        input.readFully(byteOrderBytes)
        val littleEndian =
            when {
                byteOrderBytes.contentEquals(TIFF_LITTLE_ENDIAN) -> true
                byteOrderBytes.contentEquals(TIFF_BIG_ENDIAN) -> false
                else -> return NORMAL_ORIENTATION
            }
        if (input.readUnsignedShort(littleEndian) != TIFF_MAGIC_NUMBER) return NORMAL_ORIENTATION
        val firstIfdOffset = input.readUnsignedInt(littleEndian) ?: return NORMAL_ORIENTATION
        val ifdStart = tiffStart + firstIfdOffset
        if (!input.rangeFits(ifdStart, IFD_ENTRY_COUNT_BYTES, tiffStart, tiffLength)) return NORMAL_ORIENTATION
        input.seek(ifdStart)
        val entryCount = input.readUnsignedShort(littleEndian)
        val entriesStart = input.filePointer
        val entriesLength = entryCount.toLong() * IFD_ENTRY_BYTES
        if (!input.rangeFits(entriesStart, entriesLength, tiffStart, tiffLength)) return NORMAL_ORIENTATION

        repeat(entryCount) {
            val entryStart = entriesStart + it * IFD_ENTRY_BYTES
            input.seek(entryStart)
            val tag = input.readUnsignedShort(littleEndian)
            if (tag != EXIF_ORIENTATION_TAG) return@repeat
            val type = input.readUnsignedShort(littleEndian)
            val count = input.readUnsignedInt(littleEndian) ?: return NORMAL_ORIENTATION
            if (type != TIFF_SHORT_TYPE || count != SINGLE_VALUE_COUNT) return NORMAL_ORIENTATION
            val orientation = input.readUnsignedShort(littleEndian)
            return orientation.takeIf { value -> value in MIN_ORIENTATION..MAX_ORIENTATION } ?: NORMAL_ORIENTATION
        }
        return NORMAL_ORIENTATION
    }

    /** 判断文件起始字节是否与 JPEG 签名一致，并恢复读取位置。 */
    private fun RandomAccessFile.hasJpegSignature(): Boolean = hasSignature(JPEG_SIGNATURE)

    /** 判断文件起始字节是否与 PNG 签名一致，并恢复读取位置。 */
    private fun RandomAccessFile.hasPngSignature(): Boolean = hasSignature(PNG_SIGNATURE)

    /** 判断文件是否包含给定签名。 */
    private fun RandomAccessFile.hasSignature(signature: ByteArray): Boolean {
        if (length() < signature.size) return false
        seek(0L)
        val actual = ByteArray(signature.size)
        readFully(actual)
        return actual.contentEquals(signature)
    }

    /** 按 TIFF 字节序读取无符号短整数。 */
    private fun RandomAccessFile.readUnsignedShort(littleEndian: Boolean): Int {
        val first = readUnsignedByte()
        val second = readUnsignedByte()
        return if (littleEndian) first or (second shl BYTE_BITS) else (first shl BYTE_BITS) or second
    }

    /** 按 TIFF 字节序读取可安全表示为 Long 的无符号整数。 */
    private fun RandomAccessFile.readUnsignedInt(littleEndian: Boolean): Long? {
        val bytes = LongArray(TIFF_UNSIGNED_INT_BYTES) { readUnsignedByte().toLong() }
        return if (littleEndian) {
            bytes.indices.fold(0L) { value, index -> value or (bytes[index] shl (index * BYTE_BITS)) }
        } else {
            bytes.indices.fold(0L) { value, index -> (value shl BYTE_BITS) or bytes[index] }
        }
    }

    /** 按 PNG 固定大端字节序读取无符号整数。 */
    private fun RandomAccessFile.readUnsignedIntBigEndian(): Long? = readUnsignedInt(littleEndian = false)

    /** 判断待读取范围是否完整位于当前 TIFF 数据范围和文件范围内。 */
    private fun RandomAccessFile.rangeFits(
        start: Long,
        size: Long,
        containerStart: Long,
        containerSize: Long,
    ): Boolean {
        if (start < containerStart || size < 0L || containerSize < 0L) return false
        val containerEnd = containerStart + containerSize
        val end = start + size
        if (containerEnd < containerStart || end < start) return false
        return end <= containerEnd && end <= length()
    }

    /** JPEG、PNG 和 TIFF 二进制格式常量。 */
    private const val READ_ONLY_MODE = "r"

    /** 未声明或无法验证方向时使用的标准方向。 */
    private const val NORMAL_ORIENTATION = 1

    /** EXIF 最小合法方向值。 */
    private const val MIN_ORIENTATION = 1

    /** EXIF 最大合法方向值。 */
    private const val MAX_ORIENTATION = 8

    /** 一个字节包含的位数。 */
    private const val BYTE_BITS = 8

    /** JPEG 标记前缀。 */
    private const val JPEG_MARKER_PREFIX = 0xff

    /** JPEG 图像开始标记。 */
    private const val JPEG_START_OF_IMAGE = 0xd8

    /** JPEG 图像结束标记。 */
    private const val JPEG_END_OF_IMAGE = 0xd9

    /** JPEG 扫描数据开始标记。 */
    private const val JPEG_START_OF_SCAN = 0xda

    /** JPEG 临时无长度标记。 */
    private const val JPEG_TEMPORARY_MARKER = 0x01

    /** JPEG EXIF 使用的 APP1 标记。 */
    private const val JPEG_APP1_MARKER = 0xe1

    /** JPEG 重启标记范围。 */
    private val JPEG_RESTART_MARKERS = 0xd0..0xd7

    /** JPEG SOI 文件签名。 */
    private val JPEG_SIGNATURE = byteArrayOf(0xff.toByte(), JPEG_START_OF_IMAGE.toByte())

    /** JPEG 标记和长度字段最少占用字节数。 */
    private const val JPEG_SEGMENT_HEADER_BYTES = 4L

    /** JPEG 段长度字段自身占用字节数。 */
    private const val JPEG_LENGTH_FIELD_BYTES = 2L

    /** JPEG APP1 EXIF 标识。 */
    private val EXIF_IDENTIFIER =
        byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)

    /** PNG 固定文件签名。 */
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

    /** PNG 块长度和类型字段占用字节数。 */
    private const val PNG_CHUNK_HEADER_BYTES = 8L

    /** PNG 块类型字段字节数。 */
    private const val PNG_CHUNK_TYPE_BYTES = 4

    /** PNG 块末尾 CRC 字节数。 */
    private const val PNG_CRC_BYTES = 4L

    /** PNG 标准 EXIF 块类型。 */
    private val PNG_EXIF_CHUNK_TYPE =
        byteArrayOf('e'.code.toByte(), 'X'.code.toByte(), 'I'.code.toByte(), 'f'.code.toByte())

    /** PNG 图像结束块类型。 */
    private val PNG_END_CHUNK_TYPE =
        byteArrayOf('I'.code.toByte(), 'E'.code.toByte(), 'N'.code.toByte(), 'D'.code.toByte())

    /** TIFF 头和首个 IFD 偏移所需的最小字节数。 */
    private const val MINIMUM_TIFF_BYTES = 8L

    /** TIFF 字节序标识长度。 */
    private const val TIFF_BYTE_ORDER_BYTES = 2

    /** TIFF 小端字节序标识。 */
    private val TIFF_LITTLE_ENDIAN = byteArrayOf('I'.code.toByte(), 'I'.code.toByte())

    /** TIFF 大端字节序标识。 */
    private val TIFF_BIG_ENDIAN = byteArrayOf('M'.code.toByte(), 'M'.code.toByte())

    /** TIFF 固定魔数。 */
    private const val TIFF_MAGIC_NUMBER = 42

    /** TIFF 无符号整数占用字节数。 */
    private const val TIFF_UNSIGNED_INT_BYTES = 4

    /** IFD 条目计数字段占用字节数。 */
    private const val IFD_ENTRY_COUNT_BYTES = 2L

    /** 单个 IFD 条目占用字节数。 */
    private const val IFD_ENTRY_BYTES = 12L

    /** EXIF 方向标签编号。 */
    private const val EXIF_ORIENTATION_TAG = 0x0112

    /** TIFF SHORT 数据类型编号。 */
    private const val TIFF_SHORT_TYPE = 3

    /** 方向标签只允许单个值。 */
    private const val SINGLE_VALUE_COUNT = 1L
}

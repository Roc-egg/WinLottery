package roc.win.lottery.app

import okio.Buffer
import okio.BufferedSource
import roc.win.lottery.persistence.MAX_TICKET_RECORD_IMPORT_BYTES
import roc.win.lottery.persistence.TICKET_RECORD_EXPORT_EXTENSION

/** 系统文件选择器读取逻辑票据包的结果。 */
sealed interface TicketRecordFileReadResult {
    /** 用户没有选择文件。 */
    data object Cancelled : TicketRecordFileReadResult

    /**
     * 已读取一个仍需共享持久化层完整校验的文件。
     *
     * @property content 不超过共享导入上限的原始文件字节。
     */
    data class Success(
        val content: ByteArray,
    ) : TicketRecordFileReadResult

    /**
     * 系统选择器、文件权限或本地读取失败。
     *
     * @property message 不包含文件路径或投注内容的安全说明。
     */
    data class Failure(
        val message: String,
    ) : TicketRecordFileReadResult
}

/** 系统文件选择器写出逻辑票据包的结果。 */
sealed interface TicketRecordFileWriteResult {
    /** 用户没有选择导出位置。 */
    data object Cancelled : TicketRecordFileWriteResult

    /** 文件已经完整写入用户选择的位置。 */
    data object Success : TicketRecordFileWriteResult

    /**
     * 系统选择器、文件权限或本地写入失败。
     *
     * @property message 不包含文件路径或投注内容的安全说明。
     */
    data class Failure(
        val message: String,
    ) : TicketRecordFileWriteResult
}

/** Android 和 iOS 主动导入导出逻辑票据包的系统文件接口。 */
interface TicketRecordFileExchange {
    /** 打开系统文件选择器并即时读取一个逻辑票据包。 */
    suspend fun readImportPackage(): TicketRecordFileReadResult

    /**
     * 打开系统保存选择器并写入一个逻辑票据包。
     *
     * @param suggestedFileName 带固定 `.wltickets.json` 扩展名的建议名称。
     * @param content 已通过共享编码器生成的完整文件字节。
     */
    suspend fun writeExportPackage(
        suggestedFileName: String,
        content: ByteArray,
    ): TicketRecordFileWriteResult
}

/** 检查文件名只包含一个非空基础名称和固定逻辑包扩展名。 */
internal fun isTicketRecordPackageFileName(fileName: String): Boolean =
    fileName.length > TICKET_RECORD_EXPORT_EXTENSION.length &&
        fileName.endsWith(TICKET_RECORD_EXPORT_EXTENSION) &&
        '/' !in fileName &&
        '\\' !in fileName

/**
 * 从平台文件源最多读取 5 MiB 加一个探测字节。
 *
 * @return 未超过上限时的完整字节；超过上限时返回 `null`。
 */
internal fun BufferedSource.readTicketRecordImportContent(): ByteArray? {
    val collected = Buffer()
    val detectionLimit = MAX_TICKET_RECORD_IMPORT_BYTES.toLong() + 1L
    while (collected.size < detectionLimit) {
        val read = read(collected, minOf(FILE_READ_CHUNK_BYTES, detectionLimit - collected.size))
        if (read == -1L) break
    }
    if (collected.size > MAX_TICKET_RECORD_IMPORT_BYTES) return null
    return collected.readByteArray()
}

/** 平台文件源每次读取的稳定块大小。 */
private const val FILE_READ_CHUNK_BYTES = 8L * 1024L

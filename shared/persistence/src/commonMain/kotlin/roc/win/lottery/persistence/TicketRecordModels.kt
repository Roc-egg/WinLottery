package roc.win.lottery.persistence

import kotlinx.coroutines.flow.Flow
import roc.win.lottery.domain.ConfirmedTicket

/** 用户确认票据进入结构化记录前的采集方式。 */
enum class TicketAcquisitionSource {
    /** 应用内相机拍摄。 */
    CAMERA,

    /** 系统图片选择器导入。 */
    SYSTEM_PICKER,

    /** 不使用图片的手动录入。 */
    MANUAL_ENTRY,
}

/**
 * 本机保存的一条结构化彩票记录。
 *
 * @property id 跨设备导入导出时保持稳定的 UUID。
 * @property displayName 用户可修改的显示名称。
 * @property ticket 已确认且重新通过领域校验的票据。
 * @property acquisitionSource 首次创建记录时的采集方式。
 * @property createdAtEpochMillis 创建时间，Unix 毫秒。
 * @property updatedAtEpochMillis 最近修改名称的时间，Unix 毫秒。
 * @property dataVersion 记录逻辑结构版本，与 Room 模式版本相互独立。
 */
data class StoredTicketRecord(
    val id: String,
    val displayName: String,
    val ticket: ConfirmedTicket,
    val acquisitionSource: TicketAcquisitionSource,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val dataVersion: Int,
)

/** 已保存票据的数据损坏或不受支持时抛出的失败关闭异常。 */
class TicketRecordCorruptionException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * 本机结构化票据记录的业务接口。
 *
 * 实现必须在保存和读取边界重复执行领域校验，不得返回未经校验的数据库内容。
 */
interface TicketRecordStore {
    /** 按创建时间倒序观察全部合法记录。 */
    val records: Flow<List<StoredTicketRecord>>

    /**
     * 保存一次新的用户确认流程。
     *
     * @param ticket 已由确认页提交的票据。
     * @param acquisitionSource 本次流程的采集方式。
     * @param displayName 可选显示名称；为空时按彩种和起始期号生成。
     * @return 已持久化且可立即读取的记录。
     */
    suspend fun save(
        ticket: ConfirmedTicket,
        acquisitionSource: TicketAcquisitionSource,
        displayName: String? = null,
    ): StoredTicketRecord

    /** 返回指定 UUID 的合法记录，不存在时返回 `null`。 */
    suspend fun findById(id: String): StoredTicketRecord?

    /** 修改记录名称；记录不存在时返回 `false`。 */
    suspend fun rename(
        id: String,
        displayName: String,
    ): Boolean

    /** 删除单条记录；记录不存在时返回 `false`。 */
    suspend fun delete(id: String): Boolean

    /** 删除全部记录并返回删除数量。 */
    suspend fun clear(): Int

    /** 关闭底层数据库并释放连接。 */
    fun close()
}

/**
 * 校验并规范化用户可见记录名称。
 *
 * @throws IllegalArgumentException 名称为空或超过 40 个字符时抛出。
 */
fun normalizeTicketRecordName(value: String): String {
    val normalized = value.trim()
    require(normalized.isNotEmpty()) { "记录名称不能为空" }
    require(normalized.length <= MAX_TICKET_RECORD_NAME_LENGTH) {
        "记录名称不能超过 $MAX_TICKET_RECORD_NAME_LENGTH 个字符"
    }
    return normalized
}

/** 记录名称允许的最大字符数。 */
const val MAX_TICKET_RECORD_NAME_LENGTH = 40

/** 当前结构化票据记录版本。 */
const val CURRENT_TICKET_RECORD_DATA_VERSION = 1

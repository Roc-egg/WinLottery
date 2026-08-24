package roc.win.lottery.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8
import roc.win.lottery.domain.BetLine
import roc.win.lottery.domain.ConfirmedTicket
import roc.win.lottery.domain.ConfirmedValue
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.TicketFieldOrigin
import roc.win.lottery.domain.TicketValidationStatus
import roc.win.lottery.domain.TicketValidator
import kotlin.uuid.Uuid

/**
 * 一次规范化逻辑导出的结果。
 *
 * @property content 完整 `.wltickets.json` 文件字节。
 * @property recordCount 文件包含的结构化票据数量。
 * @property exportedAtEpochMillis 导出时间，Unix 毫秒。
 * @property payloadSha256 规范化 `payload` 的 SHA-256 小写十六进制摘要。
 */
data class TicketRecordExport(
    val content: ByteArray,
    val recordCount: Int,
    val exportedAtEpochMillis: Long,
    val payloadSha256: String,
)

/**
 * 同一 UUID 在本机和导入包中的内容差异。
 *
 * @property id 发生冲突的稳定 UUID。
 * @property localDisplayName 本机记录名称。
 * @property importedDisplayName 导入包中的记录名称。
 */
data class TicketRecordImportConflict(
    val id: String,
    val localDisplayName: String,
    val importedDisplayName: String,
)

/**
 * 逻辑导入包通过完整校验后的只读预检摘要。
 *
 * @property exportedAtEpochMillis 文件声明的导出时间，Unix 毫秒。
 * @property recordCount 文件中的合法记录总数。
 * @property importableCount 当前可以新增的记录数。
 * @property duplicateCount UUID 和规范化内容均相同、将被跳过的记录数。
 * @property conflicts UUID 相同但规范化内容不同的冲突列表。
 * @property payloadSha256 已核对的规范化负载摘要。
 */
data class TicketRecordImportPreview(
    val exportedAtEpochMillis: Long,
    val recordCount: Int,
    val importableCount: Int,
    val duplicateCount: Int,
    val conflicts: List<TicketRecordImportConflict>,
    val payloadSha256: String,
)

/** 导入遇到 UUID 内容冲突时允许的处理策略。 */
enum class TicketRecordImportConflictPolicy {
    /** 默认整批拒绝，数据库保持不变。 */
    REJECT_ALL,

    /** 用户明确确认后保留本机冲突记录，并导入其余新记录。 */
    KEEP_LOCAL_AND_IMPORT_REST,
}

/** 事务导入的封闭结果。 */
sealed interface TicketRecordImportResult {
    /**
     * 导入事务已经成功提交。
     *
     * @property importedCount 新写入的记录数。
     * @property duplicateCount 内容完全相同并跳过的记录数。
     * @property skippedConflictCount 按用户选择保留本机并跳过的冲突数。
     */
    data class Completed(
        val importedCount: Int,
        val duplicateCount: Int,
        val skippedConflictCount: Int,
    ) : TicketRecordImportResult

    /**
     * 检测到 UUID 内容冲突，默认没有执行任何写入。
     *
     * @property preview 事务内重新计算的最新冲突摘要。
     */
    data class Conflicts(
        val preview: TicketRecordImportPreview,
    ) : TicketRecordImportResult
}

/** 文件内容损坏、超限或不符合当前逻辑格式时抛出的失败关闭异常。 */
class TicketRecordImportException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/** 严格编码、解析并校验 V1.1 结构化票据逻辑包。 */
internal class TicketRecordTransferCodec(
    /** 导入边界使用的完整领域校验器。 */
    private val ticketValidator: TicketValidator = TicketValidator(),
) {
    /** 把全部合法记录按 UUID 排序并编码为规范化逻辑包。 */
    fun encode(
        records: List<StoredTicketRecord>,
        exportedAtEpochMillis: Long,
    ): TicketRecordExport {
        require(exportedAtEpochMillis >= 0L) { "导出时间不能早于 Unix 纪元" }
        val payload =
            TicketTransferPayload(
                format = TICKET_RECORD_TRANSFER_FORMAT,
                formatVersion = CURRENT_TICKET_RECORD_TRANSFER_FORMAT_VERSION,
                roomSchemaVersion = WIN_LOTTERY_DATABASE_VERSION,
                exportedAtEpochMillis = exportedAtEpochMillis,
                recordCount = records.size,
                records = records.sortedBy(StoredTicketRecord::id).map(::toTransferRecord),
            )
        val canonicalPayload = TicketTransferJson.encodeToString(payload)
        val payloadSha256 = canonicalPayload.sha256Hex()
        val content =
            TicketTransferJson
                .encodeToString(TicketTransferEnvelope(payload, payloadSha256))
                .encodeToByteArray()
        return TicketRecordExport(
            content = content,
            recordCount = records.size,
            exportedAtEpochMillis = exportedAtEpochMillis,
            payloadSha256 = payloadSha256,
        )
    }

    /** 解析原始文件字节，完成结构、领域和 SHA-256 校验。 */
    fun decode(content: ByteArray): DecodedTicketRecordPackage {
        if (content.size > MAX_TICKET_RECORD_IMPORT_BYTES) {
            throw TicketRecordImportException("导入文件不能超过 5 MiB")
        }
        val text =
            try {
                content.decodeToString(throwOnInvalidSequence = true)
            } catch (error: Exception) {
                throw TicketRecordImportException("导入文件不是有效的 UTF-8 文本", error)
            }
        val envelope =
            try {
                TicketTransferJson.decodeFromString<TicketTransferEnvelope>(text)
            } catch (error: Exception) {
                throw TicketRecordImportException("导入文件不是受支持的票据 JSON 包", error)
            }
        val payload = envelope.payload
        importRequire(payload.format == TICKET_RECORD_TRANSFER_FORMAT) { "导入文件格式标识不受支持" }
        importRequire(payload.formatVersion == CURRENT_TICKET_RECORD_TRANSFER_FORMAT_VERSION) {
            "不支持导入格式版本 ${payload.formatVersion}"
        }
        importRequire(payload.roomSchemaVersion == WIN_LOTTERY_DATABASE_VERSION) {
            "不支持导入文件的数据库模式版本 ${payload.roomSchemaVersion}"
        }
        importRequire(payload.exportedAtEpochMillis >= 0L) { "导出时间不合法" }
        importRequire(payload.recordCount in 0..MAX_TICKET_RECORD_IMPORT_COUNT) {
            "导入记录不能超过 $MAX_TICKET_RECORD_IMPORT_COUNT 条"
        }
        importRequire(payload.recordCount == payload.records.size) { "导入记录数量摘要不一致" }
        val ids = payload.records.map(TicketTransferRecord::id)
        importRequire(ids == ids.sorted()) { "导入记录必须按 UUID 排序" }
        importRequire(ids.distinct().size == ids.size) { "导入文件包含重复 UUID" }
        val records = payload.records.map(::toStoredRecord)
        importRequire(SHA_256_PATTERN.matches(envelope.payloadSha256)) { "导入文件的 SHA-256 格式不合法" }
        val canonicalPayload = TicketTransferJson.encodeToString(payload)
        importRequire(canonicalPayload.sha256Hex() == envelope.payloadSha256) { "导入文件完整性校验失败" }
        return DecodedTicketRecordPackage(
            exportedAtEpochMillis = payload.exportedAtEpochMillis,
            records = records,
            payloadSha256 = envelope.payloadSha256,
        )
    }

    /** 把一条业务记录转换为不含 OCR 原文的传输记录。 */
    private fun toTransferRecord(record: StoredTicketRecord): TicketTransferRecord {
        validateStoredRecord(record, "导出记录 ${record.id}")
        return TicketTransferRecord(
            id = record.id,
            displayName = record.displayName,
            acquisitionSource = record.acquisitionSource.name,
            createdAtEpochMillis = record.createdAtEpochMillis,
            updatedAtEpochMillis = record.updatedAtEpochMillis,
            dataVersion = record.dataVersion,
            ticket = record.ticket.toTransferTicket(),
        )
    }

    /** 把严格 DTO 恢复为已通过完整校验的业务记录。 */
    private fun toStoredRecord(record: TicketTransferRecord): StoredTicketRecord {
        val storedRecord =
            StoredTicketRecord(
                id = validateUuid(record.id),
                displayName = record.displayName,
                ticket = record.ticket.toConfirmedTicket(),
                acquisitionSource = parseEnum(record.acquisitionSource, "采集方式"),
                createdAtEpochMillis = record.createdAtEpochMillis,
                updatedAtEpochMillis = record.updatedAtEpochMillis,
                dataVersion = record.dataVersion,
            )
        validateStoredRecord(storedRecord, "导入记录 ${record.id}")
        return storedRecord
    }

    /** 校验记录版本、名称、时间以及完整票据领域约束。 */
    private fun validateStoredRecord(
        record: StoredTicketRecord,
        label: String,
    ) {
        importRequire(validateUuid(record.id) == record.id) { "$label 的 UUID 不规范" }
        val normalizedName =
            try {
                normalizeTicketRecordName(record.displayName)
            } catch (error: IllegalArgumentException) {
                throw TicketRecordImportException("$label 的名称不合法", error)
            }
        importRequire(normalizedName == record.displayName) { "$label 的名称不是规范化文本" }
        importRequire(record.dataVersion == CURRENT_TICKET_RECORD_DATA_VERSION) {
            "$label 的数据版本 ${record.dataVersion} 不受支持"
        }
        importRequire(record.createdAtEpochMillis >= 0L) { "$label 的创建时间不合法" }
        importRequire(record.updatedAtEpochMillis >= record.createdAtEpochMillis) { "$label 的更新时间早于创建时间" }
        importRequire(record.ticket.betLines.all { it.originalText.isEmpty() }) { "$label 不得包含 OCR 原文" }
        val validation = ticketValidator.validate(record.ticket)
        importRequire(validation.status == TicketValidationStatus.VALID && validation.problems.isEmpty()) {
            validation.problems.joinToString(separator = "；") { it.message }.ifEmpty { "$label 未通过领域校验" }
        }
    }

    /** 把领域票据转换为固定字段顺序的传输票据。 */
    private fun ConfirmedTicket.toTransferTicket(): TicketTransferTicket =
        TicketTransferTicket(
            lotteryType = lotteryType.value.name,
            lotteryTypeOrigin = lotteryType.origin.name,
            issue = issue.value.value,
            issueOrigin = issue.origin.name,
            betLines = betLines.map { it.toTransferBetLine() },
            multiplier = multiplier.value,
            multiplierOrigin = multiplier.origin.name,
            periodCount = periodCount.value,
            periodCountOrigin = periodCount.origin.name,
            paidAmountFen = paidAmountFen.value,
            paidAmountOrigin = paidAmountFen.origin.name,
        )

    /** 把领域投注行转换为不含原文的传输投注行。 */
    private fun BetLine.toTransferBetLine(): TicketTransferBetLine =
        TicketTransferBetLine(
            primaryNumbers = primaryNumbers.value,
            primaryNumbersOrigin = primaryNumbers.origin.name,
            secondaryNumbers = secondaryNumbers.value,
            secondaryNumbersOrigin = secondaryNumbers.origin.name,
            isAdditional = isAdditional.value,
            isAdditionalOrigin = isAdditional.origin.name,
        )

    /** 把传输票据恢复为字段来源完整的领域票据。 */
    private fun TicketTransferTicket.toConfirmedTicket(): ConfirmedTicket =
        ConfirmedTicket(
            lotteryType = ConfirmedValue(parseEnum(lotteryType, "彩种"), parseEnum(lotteryTypeOrigin, "彩种来源")),
            issue = ConfirmedValue(Issue(issue), parseEnum(issueOrigin, "期号来源")),
            betLines = betLines.map { it.toBetLine() },
            multiplier = ConfirmedValue(multiplier, parseEnum(multiplierOrigin, "倍数来源")),
            periodCount = ConfirmedValue(periodCount, parseEnum(periodCountOrigin, "期数来源")),
            paidAmountFen = ConfirmedValue(paidAmountFen, parseEnum(paidAmountOrigin, "金额来源")),
        )

    /** 把传输投注行恢复为不含 OCR 原文的领域投注行。 */
    private fun TicketTransferBetLine.toBetLine(): BetLine =
        BetLine(
            primaryNumbers = ConfirmedValue(primaryNumbers, parseEnum(primaryNumbersOrigin, "主号码来源")),
            secondaryNumbers = ConfirmedValue(secondaryNumbers, parseEnum(secondaryNumbersOrigin, "次号码来源")),
            isAdditional = ConfirmedValue(isAdditional, parseEnum(isAdditionalOrigin, "追加来源")),
            originalText = "",
        )

    /** 解析并要求 UUID 使用标准小写连字符格式。 */
    private fun validateUuid(value: String): String {
        val normalized =
            try {
                Uuid.parse(value).toString()
            } catch (error: IllegalArgumentException) {
                throw TicketRecordImportException("记录 UUID 不合法", error)
            }
        importRequire(value == normalized) { "记录 UUID 必须使用标准小写格式" }
        return normalized
    }

    /** 把固定枚举名称恢复为当前枚举值。 */
    private inline fun <reified T : Enum<T>> parseEnum(
        value: String,
        label: String,
    ): T =
        try {
            enumValueOf<T>(value)
        } catch (error: IllegalArgumentException) {
            throw TicketRecordImportException("$label 不受支持", error)
        }
}

/** 已完成内容校验、等待数据库冲突分类的逻辑包。 */
internal data class DecodedTicketRecordPackage(
    /** 文件声明的导出时间，Unix 毫秒。 */
    val exportedAtEpochMillis: Long,
    /** 按 UUID 排序且已经通过领域校验的记录。 */
    val records: List<StoredTicketRecord>,
    /** 已核对的规范化负载摘要。 */
    val payloadSha256: String,
)

/** 逻辑导出文件的最外层结构。 */
@Serializable
private data class TicketTransferEnvelope(
    /** 被规范化和计算摘要的业务负载。 */
    val payload: TicketTransferPayload,
    /** 规范化负载 UTF-8 字节的 SHA-256。 */
    val payloadSha256: String,
)

/** 逻辑导出文件中参与完整性摘要的业务负载。 */
@Serializable
private data class TicketTransferPayload(
    /** 固定文件格式标识。 */
    val format: String,
    /** 逻辑文件格式版本。 */
    val formatVersion: Int,
    /** 导出端 Room 模式版本。 */
    val roomSchemaVersion: Int,
    /** 导出时间，Unix 毫秒。 */
    val exportedAtEpochMillis: Long,
    /** 记录数量摘要。 */
    val recordCount: Int,
    /** 按 UUID 排序的全部结构化票据。 */
    val records: List<TicketTransferRecord>,
)

/** 逻辑包中的一条完整结构化票据记录。 */
@Serializable
private data class TicketTransferRecord(
    /** 稳定 UUID。 */
    val id: String,
    /** 用户可见名称。 */
    val displayName: String,
    /** 首次采集方式枚举名称。 */
    val acquisitionSource: String,
    /** 创建时间，Unix 毫秒。 */
    val createdAtEpochMillis: Long,
    /** 最近更新时间，Unix 毫秒。 */
    val updatedAtEpochMillis: Long,
    /** 结构化记录数据版本。 */
    val dataVersion: Int,
    /** 已确认的完整票据。 */
    val ticket: TicketTransferTicket,
)

/** 逻辑包中保留字段来源的已确认票据。 */
@Serializable
private data class TicketTransferTicket(
    /** 彩种枚举名称。 */
    val lotteryType: String,
    /** 彩种字段来源。 */
    val lotteryTypeOrigin: String,
    /** 保留前导零的起始期号。 */
    val issue: String,
    /** 期号字段来源。 */
    val issueOrigin: String,
    /** 保留票面顺序与重复项的投注行。 */
    val betLines: List<TicketTransferBetLine>,
    /** 投注倍数。 */
    val multiplier: Int,
    /** 倍数字段来源。 */
    val multiplierOrigin: String,
    /** 连续投注期数。 */
    val periodCount: Int,
    /** 期数字段来源。 */
    val periodCountOrigin: String,
    /** 票面金额，单位为分。 */
    val paidAmountFen: Long,
    /** 金额字段来源。 */
    val paidAmountOrigin: String,
)

/** 逻辑包中不含 OCR 原文的一行单式投注。 */
@Serializable
private data class TicketTransferBetLine(
    /** 前区或红球号码。 */
    val primaryNumbers: List<Int>,
    /** 主号码字段来源。 */
    val primaryNumbersOrigin: String,
    /** 后区或蓝球号码。 */
    val secondaryNumbers: List<Int>,
    /** 次号码字段来源。 */
    val secondaryNumbersOrigin: String,
    /** 大乐透是否追加。 */
    val isAdditional: Boolean,
    /** 追加字段来源。 */
    val isAdditionalOrigin: String,
)

/** 逻辑票据包使用的严格、紧凑 JSON 配置。 */
private val TicketTransferJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        isLenient = false
        prettyPrint = false
    }

/** 计算规范化 UTF-8 文本的 SHA-256 小写十六进制摘要。 */
private fun String.sha256Hex(): String = encodeUtf8().sha256().hex()

/** 在导入边界以稳定异常类型拒绝不合法内容。 */
private inline fun importRequire(
    condition: Boolean,
    lazyMessage: () -> String,
) {
    if (!condition) throw TicketRecordImportException(lazyMessage())
}

/** 用户级导出文件的固定扩展名。 */
const val TICKET_RECORD_EXPORT_EXTENSION = ".wltickets.json"

/** 逻辑包内的固定格式标识。 */
const val TICKET_RECORD_TRANSFER_FORMAT = "win-lottery-tickets"

/** 当前逻辑导入导出格式版本。 */
const val CURRENT_TICKET_RECORD_TRANSFER_FORMAT_VERSION = 1

/** 单个导入文件允许的最大原始字节数。 */
const val MAX_TICKET_RECORD_IMPORT_BYTES = 5 * 1024 * 1024

/** 单个导入文件允许的最大票据记录数。 */
const val MAX_TICKET_RECORD_IMPORT_COUNT = 2_000

/** 导入摘要必须使用的 64 位小写 SHA-256 格式。 */
private val SHA_256_PATTERN = Regex("^[0-9a-f]{64}$")

package roc.win.lottery.persistence

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import roc.win.lottery.domain.BetLine
import roc.win.lottery.domain.ConfirmedTicket
import roc.win.lottery.domain.ConfirmedValue
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.TicketFieldOrigin
import roc.win.lottery.domain.TicketValidationStatus
import roc.win.lottery.domain.TicketValidator
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** 使用 Room 3 保存并严格恢复结构化票据的记录仓库。 */
internal class RoomTicketRecordStore(
    /** 由平台创建且归本仓库管理生命周期的数据库。 */
    private val database: WinLotteryDatabase,
    /** 保存和读取边界复用的领域校验器。 */
    private val ticketValidator: TicketValidator = TicketValidator(),
    /** 可在测试中替换的墙钟。 */
    private val clock: Clock = Clock.System,
    /** 可在测试中替换的 UUID 生成器。 */
    private val idGenerator: () -> String = { Uuid.random().toString() },
) : TicketRecordStore {
    /** Room 数据访问接口。 */
    private val dao = database.ticketRecordDao()

    /** 按创建时间倒序观察并校验全部记录。 */
    override val records: Flow<List<StoredTicketRecord>> =
        dao.observeAll().map { rows -> rows.map(::mapFromDatabase) }

    /** 保存新的合法确认票据。 */
    override suspend fun save(
        ticket: ConfirmedTicket,
        acquisitionSource: TicketAcquisitionSource,
        displayName: String?,
    ): StoredTicketRecord {
        requireValidTicket(ticket)
        val now = clock.now().toEpochMilliseconds()
        require(now >= 0L) { "记录时间不能早于 Unix 纪元" }
        val record =
            StoredTicketRecord(
                id = validateUuid(idGenerator()),
                displayName = normalizeTicketRecordName(displayName ?: ticket.defaultDisplayName()),
                ticket = ticket.withoutOriginalText(),
                acquisitionSource = acquisitionSource,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
                dataVersion = CURRENT_TICKET_RECORD_DATA_VERSION,
            )
        dao.insert(record.toDatabaseRows())
        return record
    }

    /** 返回指定 UUID 的合法记录。 */
    override suspend fun findById(id: String): StoredTicketRecord? =
        dao.findById(validateUuid(id))?.let(::mapFromDatabase)

    /** 修改合法记录名称。 */
    override suspend fun rename(
        id: String,
        displayName: String,
    ): Boolean {
        val normalizedId = validateUuid(id)
        val normalizedName = normalizeTicketRecordName(displayName)
        val now = clock.now().toEpochMilliseconds()
        require(now >= 0L) { "记录时间不能早于 Unix 纪元" }
        return dao.rename(normalizedId, normalizedName, now) == 1
    }

    /** 删除单条记录。 */
    override suspend fun delete(id: String): Boolean = dao.delete(validateUuid(id)) == 1

    /** 删除全部记录。 */
    override suspend fun clear(): Int = dao.clear()

    /** 关闭底层 Room 数据库。 */
    override fun close() {
        database.close()
    }

    /** 从 Room 关系结果恢复并校验业务记录。 */
    private fun mapFromDatabase(rows: TicketRecordWithLines): StoredTicketRecord =
        try {
            val entity = rows.record
            val orderedLines = rows.betLines.sortedBy { it.lineIndex }
            require(orderedLines.map { it.lineIndex } == orderedLines.indices.toList()) {
                "投注行序号不连续"
            }
            require(entity.dataVersion == CURRENT_TICKET_RECORD_DATA_VERSION) {
                "不支持的数据版本 ${entity.dataVersion}"
            }
            require(entity.createdAtEpochMillis >= 0L) { "创建时间不合法" }
            require(entity.updatedAtEpochMillis >= entity.createdAtEpochMillis) { "更新时间早于创建时间" }
            val ticket =
                ConfirmedTicket(
                    lotteryType =
                        ConfirmedValue(
                            parseEnum<LotteryType>(entity.lotteryType),
                            parseEnum<TicketFieldOrigin>(entity.lotteryTypeOrigin),
                        ),
                    issue =
                        ConfirmedValue(
                            Issue(entity.firstIssue),
                            parseEnum<TicketFieldOrigin>(entity.issueOrigin),
                        ),
                    betLines = orderedLines.map { it.toDomain() },
                    multiplier =
                        ConfirmedValue(
                            entity.multiplier,
                            parseEnum<TicketFieldOrigin>(entity.multiplierOrigin),
                        ),
                    periodCount =
                        ConfirmedValue(
                            entity.periodCount,
                            parseEnum<TicketFieldOrigin>(entity.periodCountOrigin),
                        ),
                    paidAmountFen =
                        ConfirmedValue(
                            entity.paidAmountFen,
                            parseEnum<TicketFieldOrigin>(entity.paidAmountOrigin),
                        ),
                )
            requireValidTicket(ticket)
            StoredTicketRecord(
                id = validateUuid(entity.id),
                displayName = normalizeTicketRecordName(entity.displayName),
                ticket = ticket,
                acquisitionSource = parseEnum(entity.acquisitionSource),
                createdAtEpochMillis = entity.createdAtEpochMillis,
                updatedAtEpochMillis = entity.updatedAtEpochMillis,
                dataVersion = entity.dataVersion,
            )
        } catch (error: TicketRecordCorruptionException) {
            throw error
        } catch (error: Exception) {
            throw TicketRecordCorruptionException("本机记录 ${rows.record.id} 已损坏，已停止读取", error)
        }

    /** 把业务记录转换为 Room 主记录和投注行。 */
    private fun StoredTicketRecord.toDatabaseRows(): TicketRecordWithLines =
        TicketRecordWithLines(
            record =
                TicketRecordEntity(
                    id = id,
                    displayName = displayName,
                    lotteryType = ticket.lotteryType.value.name,
                    firstIssue = ticket.issue.value.value,
                    periodCount = ticket.periodCount.value,
                    multiplier = ticket.multiplier.value,
                    paidAmountFen = ticket.paidAmountFen.value,
                    acquisitionSource = acquisitionSource.name,
                    lotteryTypeOrigin = ticket.lotteryType.origin.name,
                    issueOrigin = ticket.issue.origin.name,
                    periodCountOrigin = ticket.periodCount.origin.name,
                    multiplierOrigin = ticket.multiplier.origin.name,
                    paidAmountOrigin = ticket.paidAmountFen.origin.name,
                    createdAtEpochMillis = createdAtEpochMillis,
                    updatedAtEpochMillis = updatedAtEpochMillis,
                    dataVersion = dataVersion,
                ),
            betLines =
                ticket.betLines.mapIndexed { index, line ->
                    TicketBetLineEntity(
                        recordId = id,
                        lineIndex = index,
                        primaryNumbers = line.primaryNumbers.value,
                        secondaryNumbers = line.secondaryNumbers.value,
                        isAdditional = line.isAdditional.value,
                        primaryNumbersOrigin = line.primaryNumbers.origin.name,
                        secondaryNumbersOrigin = line.secondaryNumbers.origin.name,
                        isAdditionalOrigin = line.isAdditional.origin.name,
                    )
                },
        )

    /** 把 Room 投注行恢复为不含 OCR 原文的领域对象。 */
    private fun TicketBetLineEntity.toDomain(): BetLine =
        BetLine(
            primaryNumbers = ConfirmedValue(primaryNumbers, parseEnum(primaryNumbersOrigin)),
            secondaryNumbers = ConfirmedValue(secondaryNumbers, parseEnum(secondaryNumbersOrigin)),
            isAdditional = ConfirmedValue(isAdditional, parseEnum(isAdditionalOrigin)),
            originalText = "",
        )

    /** 要求票据满足当前完整领域约束。 */
    private fun requireValidTicket(ticket: ConfirmedTicket) {
        val validation = ticketValidator.validate(ticket)
        require(validation.status == TicketValidationStatus.VALID && validation.problems.isEmpty()) {
            validation.problems.joinToString(separator = "；") { it.message }.ifEmpty { "票据未通过领域校验" }
        }
    }

    /** 确认 UUID 使用标准小写连字符格式。 */
    private fun validateUuid(value: String): String {
        val parsed = Uuid.parse(value)
        val normalized = parsed.toString()
        require(value == normalized) { "记录 UUID 必须使用标准小写格式" }
        return normalized
    }

    /** 把持久化枚举名称安全恢复为当前枚举。 */
    private inline fun <reified T : Enum<T>> parseEnum(value: String): T = enumValueOf(value)

    /** 生成不包含 OCR 原始文本的持久化领域对象。 */
    private fun ConfirmedTicket.withoutOriginalText(): ConfirmedTicket =
        copy(betLines = betLines.map { it.copy(originalText = "") })

    /** 生成首个用户可见名称。 */
    private fun ConfirmedTicket.defaultDisplayName(): String {
        val lotteryName =
            when (lotteryType.value) {
                LotteryType.SUPER_LOTTO -> "大乐透"
                LotteryType.DOUBLE_COLOR_BALL -> "双色球"
            }
        return "$lotteryName ${issue.value.value}"
    }
}

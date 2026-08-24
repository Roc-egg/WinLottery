package roc.win.lottery.persistence

import androidx.room3.ColumnInfo
import androidx.room3.ColumnTypeConverter
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.Relation
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Room 中保存票据级字段的主记录。 */
@Entity(
    tableName = "ticket_records",
    indices = [
        Index(value = ["created_at_epoch_millis"]),
        Index(value = ["lottery_type"]),
        Index(value = ["first_issue"]),
    ],
)
internal data class TicketRecordEntity(
    /** 稳定 UUID 主键。 */
    @androidx.room3.PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    /** 用户可见名称。 */
    @ColumnInfo(name = "display_name")
    val displayName: String,
    /** 彩种枚举名称。 */
    @ColumnInfo(name = "lottery_type")
    val lotteryType: String,
    /** 起始期号。 */
    @ColumnInfo(name = "first_issue")
    val firstIssue: String,
    /** 连续投注期数。 */
    @ColumnInfo(name = "period_count")
    val periodCount: Int,
    /** 投注倍数。 */
    @ColumnInfo(name = "multiplier")
    val multiplier: Int,
    /** 票面金额，单位为分。 */
    @ColumnInfo(name = "paid_amount_fen")
    val paidAmountFen: Long,
    /** 首次采集方式枚举名称。 */
    @ColumnInfo(name = "acquisition_source")
    val acquisitionSource: String,
    /** 彩种字段来源枚举名称。 */
    @ColumnInfo(name = "lottery_type_origin")
    val lotteryTypeOrigin: String,
    /** 期号字段来源枚举名称。 */
    @ColumnInfo(name = "issue_origin")
    val issueOrigin: String,
    /** 期数字段来源枚举名称。 */
    @ColumnInfo(name = "period_count_origin")
    val periodCountOrigin: String,
    /** 倍数字段来源枚举名称。 */
    @ColumnInfo(name = "multiplier_origin")
    val multiplierOrigin: String,
    /** 金额字段来源枚举名称。 */
    @ColumnInfo(name = "paid_amount_origin")
    val paidAmountOrigin: String,
    /** 创建时间，Unix 毫秒。 */
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    /** 最近修改时间，Unix 毫秒。 */
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
    /** 逻辑记录版本。 */
    @ColumnInfo(name = "data_version")
    val dataVersion: Int,
)

/** Room 中按票面顺序保存的一行单式投注。 */
@Entity(
    tableName = "ticket_bet_lines",
    primaryKeys = ["record_id", "line_index"],
    foreignKeys = [
        ForeignKey(
            entity = TicketRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["record_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["record_id"])],
)
internal data class TicketBetLineEntity(
    /** 所属记录 UUID。 */
    @ColumnInfo(name = "record_id")
    val recordId: String,
    /** 从零开始的票面行序号。 */
    @ColumnInfo(name = "line_index")
    val lineIndex: Int,
    /** 前区或红球号码。 */
    @ColumnInfo(name = "primary_numbers")
    val primaryNumbers: List<Int>,
    /** 后区或蓝球号码。 */
    @ColumnInfo(name = "secondary_numbers")
    val secondaryNumbers: List<Int>,
    /** 大乐透是否追加。 */
    @ColumnInfo(name = "is_additional")
    val isAdditional: Boolean,
    /** 主号码字段来源枚举名称。 */
    @ColumnInfo(name = "primary_numbers_origin")
    val primaryNumbersOrigin: String,
    /** 次号码字段来源枚举名称。 */
    @ColumnInfo(name = "secondary_numbers_origin")
    val secondaryNumbersOrigin: String,
    /** 追加字段来源枚举名称。 */
    @ColumnInfo(name = "is_additional_origin")
    val isAdditionalOrigin: String,
)

/** Room 关系查询返回的完整票据记录。 */
internal data class TicketRecordWithLines(
    /** 票据级主记录。 */
    @Embedded
    val record: TicketRecordEntity,
    /** 关联的全部投注行；业务映射时按行序号重新排序。 */
    @Relation(parentColumns = ["id"], entityColumns = ["record_id"])
    val betLines: List<TicketBetLineEntity>,
)

/** 把号码列表以 JSON 数组写入单个 TEXT 字段。 */
internal class IntListConverter {
    /** 把号码列表编码为结构化 JSON。 */
    @ColumnTypeConverter
    fun encode(value: List<Int>): String = Json.encodeToString(value)

    /** 从结构化 JSON 恢复号码列表。 */
    @ColumnTypeConverter
    fun decode(value: String): List<Int> = Json.decodeFromString(value)
}

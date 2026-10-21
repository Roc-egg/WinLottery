package roc.win.lottery.persistence

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import kotlinx.serialization.Serializable

/**
 * Room 中一期已开奖官方结果，按彩种和期号由所有票据共用。
 *
 * 号码、奖级和证据都以 JSON 文本保存，由仓库逐行解码，单行损坏时只删除该行。
 */
@Entity(tableName = "draw_results", primaryKeys = ["lottery_type", "issue"])
internal data class DrawResultEntity(
    /** 彩种枚举名称。 */
    @ColumnInfo(name = "lottery_type")
    val lotteryType: String,
    /** 固定长度数字期号。 */
    @ColumnInfo(name = "issue")
    val issue: String,
    /** 开奖日期，格式为 `YYYY-MM-DD`。 */
    @ColumnInfo(name = "draw_date")
    val drawDate: String,
    /** 前区或红球号码的 JSON 数组。 */
    @ColumnInfo(name = "primary_numbers")
    val primaryNumbers: String,
    /** 后区或蓝球号码的 JSON 数组。 */
    @ColumnInfo(name = "secondary_numbers")
    val secondaryNumbers: String,
    /** 开奖状态枚举名称，只允许号码已确认的两种状态。 */
    @ColumnInfo(name = "status")
    val status: String,
    /** 本机累计的内容修订号。 */
    @ColumnInfo(name = "revision")
    val revision: Int,
    /** 保存时选中的规则版本编码。 */
    @ColumnInfo(name = "rule_version")
    val ruleVersion: String,
    /** 当期政策枚举名称。 */
    @ColumnInfo(name = "policy")
    val policy: String,
    /** 奖级金额与注数的 JSON 数组。 */
    @ColumnInfo(name = "prize_tiers")
    val prizeTiers: String,
    /** 主查询来源证据的 JSON 对象。 */
    @ColumnInfo(name = "evidence")
    val evidence: String,
    /** 辅助核对来源证据的 JSON 数组。 */
    @ColumnInfo(name = "supporting_evidence")
    val supportingEvidence: String,
    /** 逻辑数据版本。 */
    @ColumnInfo(name = "data_version")
    val dataVersion: Int,
)

/** 本机保存的奖级字段，与领域模型逐项对应。 */
@Serializable
internal data class StoredPrizeTier(
    /** 稳定奖级编码。 */
    val code: String,
    /** 奖级中文名称。 */
    val displayName: String,
    /** 基本投注单注税前奖金。 */
    val singlePrizeFen: Long?,
    /** 追加单注奖金。 */
    val additionalPrizeFen: Long?,
    /** 基本投注中奖注数。 */
    val winnerCount: Long?,
    /** 追加中奖注数。 */
    val additionalWinnerCount: Long?,
    /** 基本投注奖金官网原文。 */
    val singlePrizeRaw: String?,
    /** 追加奖金官网原文。 */
    val additionalPrizeRaw: String?,
)

/** 本机保存的来源证据，与领域模型逐项对应。 */
@Serializable
internal data class StoredSourceEvidence(
    /** 来源展示名称。 */
    val sourceName: String,
    /** 官方页面或请求地址。 */
    val sourceUrl: String,
    /** 获取时间，Unix 毫秒。 */
    val fetchedAtEpochMillis: Long,
    /** 规范化必要字段的 SHA-256。 */
    val contentSha256: String,
)

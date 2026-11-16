package roc.win.lottery.persistence

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import kotlinx.serialization.Serializable

/**
 * Room 中一个彩种最近一次 AI 分析结果，按 ADR-036 每个彩种只有一行。
 *
 * 候选号码与说明以 JSON 文本保存，由仓库解码并按彩票规则复核，损坏时只删除该行。
 */
@Entity(tableName = "ai_analysis_records", primaryKeys = ["lottery_type"])
internal data class AiAnalysisRecordEntity(
    /** 彩种枚举名称。 */
    @ColumnInfo(name = "lottery_type")
    val lotteryType: String,
    /** 服务商展示名称。 */
    @ColumnInfo(name = "provider_name")
    val providerName: String,
    /** 模型标识。 */
    @ColumnInfo(name = "model")
    val model: String,
    /** 分析模板名称。 */
    @ColumnInfo(name = "template_name")
    val templateName: String,
    /** 历史开奖官方来源名称。 */
    @ColumnInfo(name = "source_name")
    val sourceName: String,
    /** 发送的历史期数。 */
    @ColumnInfo(name = "sample_count")
    val sampleCount: Int,
    /** 发送的最早期号。 */
    @ColumnInfo(name = "first_issue")
    val firstIssue: String,
    /** 发送的最晚期号。 */
    @ColumnInfo(name = "last_issue")
    val lastIssue: String,
    /** 查询时间。 */
    @ColumnInfo(name = "generated_at_epoch_millis")
    val generatedAtEpochMillis: Long,
    /** 模型输出格式版本。 */
    @ColumnInfo(name = "schema_version")
    val schemaVersion: Int,
    /** 历史快照标识。 */
    @ColumnInfo(name = "snapshot_id")
    val snapshotId: String,
    /** 分析摘要。 */
    @ColumnInfo(name = "summary")
    val summary: String,
    /** 候选号码与说明的 JSON 数组。 */
    @ColumnInfo(name = "candidates")
    val candidates: String,
    /** 逻辑数据版本。 */
    @ColumnInfo(name = "data_version")
    val dataVersion: Int,
)

/**
 * 本机保存的一注 AI 候选。
 *
 * @property primaryNumbers 前区或红球号码。
 * @property secondaryNumbers 后区或蓝球号码。
 * @property reason 候选说明。
 */
@Serializable
internal data class StoredAiCandidate(
    val primaryNumbers: List<Int>,
    val secondaryNumbers: List<Int>,
    val reason: String,
)

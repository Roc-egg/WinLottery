package roc.win.lottery.persistence

import androidx.room3.ColumnInfo
import androidx.room3.Entity

/** Room 中每个彩种一行的本机官方历史开奖集合元数据，与票据表完全隔离。 */
@Entity(tableName = "historical_draw_sets")
internal data class HistoricalDrawSetEntity(
    /** 彩种枚举名称。 */
    @androidx.room3.PrimaryKey
    @ColumnInfo(name = "lottery_type")
    val lotteryType: String,
    /** 官方来源展示名称。 */
    @ColumnInfo(name = "source_name")
    val sourceName: String,
    /** 最近一次与官网核对成功的时间，Unix 毫秒。 */
    @ColumnInfo(name = "fetched_at_epoch_millis")
    val fetchedAtEpochMillis: Long,
    /** 写入时的开奖期数，用于读取时核对行数是否完整。 */
    @ColumnInfo(name = "draw_count")
    val drawCount: Int,
    /** 集合中最新一期期号，用于读取核对和只更新核对时间。 */
    @ColumnInfo(name = "newest_issue")
    val newestIssue: String,
    /** 逻辑数据版本。 */
    @ColumnInfo(name = "data_version")
    val dataVersion: Int,
)

/** Room 中一期规范化官方开奖号码，不保存官网原始响应。 */
@Entity(tableName = "historical_draws", primaryKeys = ["lottery_type", "issue"])
internal data class HistoricalDrawEntity(
    /** 彩种枚举名称。 */
    @ColumnInfo(name = "lottery_type")
    val lotteryType: String,
    /** 固定长度数字期号。 */
    @ColumnInfo(name = "issue")
    val issue: String,
    /** 开奖日期，格式为 `YYYY-MM-DD`。 */
    @ColumnInfo(name = "draw_date")
    val drawDate: String,
    /** 前区或红球号码。 */
    @ColumnInfo(name = "primary_numbers")
    val primaryNumbers: List<Int>,
    /** 后区或蓝球号码。 */
    @ColumnInfo(name = "secondary_numbers")
    val secondaryNumbers: List<Int>,
)

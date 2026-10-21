package roc.win.lottery.persistence

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

/** 本机官方历史开奖的 Room 数据访问接口；调用方负责在事务中组合读写。 */
@Dao
internal interface HistoricalDrawDao {
    /** 返回指定彩种的集合元数据。 */
    @Query("SELECT * FROM historical_draw_sets WHERE lottery_type = :lotteryType LIMIT 1")
    suspend fun findSet(lotteryType: String): HistoricalDrawSetEntity?

    /** 按期号升序返回指定彩种的全部开奖。 */
    @Query("SELECT * FROM historical_draws WHERE lottery_type = :lotteryType ORDER BY issue ASC")
    suspend fun findDraws(lotteryType: String): List<HistoricalDrawEntity>

    /** 插入集合元数据，冲突时失败。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSet(set: HistoricalDrawSetEntity)

    /** 插入一组开奖，冲突时失败。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDraws(draws: List<HistoricalDrawEntity>)

    /** 只在最新期号仍一致时更新来源和核对时间，返回受影响行数。 */
    @Query(
        "UPDATE historical_draw_sets " +
            "SET source_name = :sourceName, fetched_at_epoch_millis = :fetchedAtEpochMillis " +
            "WHERE lottery_type = :lotteryType AND newest_issue = :newestIssue",
    )
    suspend fun touchSet(
        lotteryType: String,
        newestIssue: String,
        sourceName: String,
        fetchedAtEpochMillis: Long,
    ): Int

    /** 删除指定彩种的集合元数据。 */
    @Query("DELETE FROM historical_draw_sets WHERE lottery_type = :lotteryType")
    suspend fun deleteSet(lotteryType: String): Int

    /** 删除指定彩种的全部开奖。 */
    @Query("DELETE FROM historical_draws WHERE lottery_type = :lotteryType")
    suspend fun deleteDraws(lotteryType: String): Int

    /** 删除全部彩种的集合元数据。 */
    @Query("DELETE FROM historical_draw_sets")
    suspend fun deleteAllSets(): Int

    /** 删除全部彩种的开奖。 */
    @Query("DELETE FROM historical_draws")
    suspend fun deleteAllDraws(): Int
}

package roc.win.lottery.persistence

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

/** 本机已开奖官方结果的 Room 数据访问接口。 */
@Dao
internal interface DrawResultDao {
    /** 返回指定彩种中给定期号已保存的行。 */
    @Query("SELECT * FROM draw_results WHERE lottery_type = :lotteryType AND issue IN (:issues)")
    suspend fun find(
        lotteryType: String,
        issues: List<String>,
    ): List<DrawResultEntity>

    /** 返回指定彩种和期号的一行。 */
    @Query("SELECT * FROM draw_results WHERE lottery_type = :lotteryType AND issue = :issue LIMIT 1")
    suspend fun findOne(
        lotteryType: String,
        issue: String,
    ): DrawResultEntity?

    /** 插入或整体替换同一期的行。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DrawResultEntity)

    /** 删除指定彩种和期号的一行，返回受影响行数。 */
    @Query("DELETE FROM draw_results WHERE lottery_type = :lotteryType AND issue = :issue")
    suspend fun delete(
        lotteryType: String,
        issue: String,
    ): Int

    /** 删除全部行，返回受影响行数。 */
    @Query("DELETE FROM draw_results")
    suspend fun deleteAll(): Int
}

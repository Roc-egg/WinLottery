package roc.win.lottery.persistence

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

/** 本机 AI 分析结果的 Room 数据访问接口。 */
@Dao
internal interface AiAnalysisRecordDao {
    /** 返回指定彩种保存的结果。 */
    @Query("SELECT * FROM ai_analysis_records WHERE lottery_type = :lotteryType LIMIT 1")
    suspend fun find(lotteryType: String): AiAnalysisRecordEntity?

    /** 插入或整体替换同一彩种的结果。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AiAnalysisRecordEntity)

    /** 删除指定彩种的结果，返回受影响行数。 */
    @Query("DELETE FROM ai_analysis_records WHERE lottery_type = :lotteryType")
    suspend fun delete(lotteryType: String): Int
}

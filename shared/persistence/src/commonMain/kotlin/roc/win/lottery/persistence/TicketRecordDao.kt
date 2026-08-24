package roc.win.lottery.persistence

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import kotlinx.coroutines.flow.Flow

/** 结构化票据记录的 Room 数据访问接口。 */
@Dao
internal interface TicketRecordDao {
    /** 按创建时间倒序观察全部主记录与投注行。 */
    @Transaction
    @Query("SELECT * FROM ticket_records ORDER BY created_at_epoch_millis DESC, id DESC")
    fun observeAll(): Flow<List<TicketRecordWithLines>>

    /** 返回当前全部主记录与投注行的一致性快照。 */
    @Transaction
    @Query("SELECT * FROM ticket_records ORDER BY created_at_epoch_millis DESC, id DESC")
    suspend fun findAll(): List<TicketRecordWithLines>

    /** 返回指定 UUID 的主记录与投注行。 */
    @Transaction
    @Query("SELECT * FROM ticket_records WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): TicketRecordWithLines?

    /** 插入一条新的票据级主记录，冲突时失败。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRecord(record: TicketRecordEntity)

    /** 插入一条记录的全部投注行，冲突时失败。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBetLines(lines: List<TicketBetLineEntity>)

    /** 在单个事务中插入主记录和全部投注行。 */
    @Transaction
    suspend fun insert(record: TicketRecordWithLines) {
        insertRecord(record.record)
        insertBetLines(record.betLines)
    }

    /** 修改名称与更新时间，返回受影响行数。 */
    @Query(
        "UPDATE ticket_records " +
            "SET display_name = :displayName, updated_at_epoch_millis = :updatedAtEpochMillis " +
            "WHERE id = :id",
    )
    suspend fun rename(
        id: String,
        displayName: String,
        updatedAtEpochMillis: Long,
    ): Int

    /** 删除一条主记录，投注行由外键级联删除。 */
    @Query("DELETE FROM ticket_records WHERE id = :id")
    suspend fun delete(id: String): Int

    /** 删除全部主记录，投注行由外键级联删除。 */
    @Query("DELETE FROM ticket_records")
    suspend fun clear(): Int
}

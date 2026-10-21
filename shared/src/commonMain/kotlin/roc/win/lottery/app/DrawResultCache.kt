package roc.win.lottery.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import roc.win.lottery.domain.DrawResult
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.persistence.DrawResultStore

/**
 * 查询开奖使用的本机已开奖结果缓存。
 *
 * 本机读写失败只影响缓存效果，不影响本次展示；用户清除后，清除前发起的查询迟到的写入会被丢弃。
 */
internal class DrawResultCache(
    /** 本机仓库；演示数据或未接入的平台为 `null`，此时缓存不生效。 */
    private val store: DrawResultStore?,
) {
    /** 串行化写入与清除，保证清除代次检查和落库之间不被打断。 */
    private val writeLock = Mutex()

    /** 每次清除递增，查询开始时记录，写入时不一致即丢弃。 */
    var clearEpoch: Long = 0L
        private set

    /** 是否启用本机缓存。 */
    val isEnabled: Boolean
        get() = store != null

    /** 读取给定期号中本机已保存的合法结果；读取失败时按没有缓存处理。 */
    suspend fun load(
        lotteryType: LotteryType,
        issues: Collection<Issue>,
    ): Map<Issue, DrawResult> {
        val target = store ?: return emptyMap()
        if (issues.isEmpty()) return emptyMap()
        return try {
            target.load(lotteryType, issues)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * 保存一期官网结果并返回实际落库的结果；清除后迟到或写入失败时原样返回。
     *
     * @param epoch 查询开始时的 [clearEpoch]。
     */
    suspend fun save(
        drawResult: DrawResult,
        epoch: Long,
    ): DrawResult {
        val target = store ?: return drawResult
        return writeLock.withLock {
            if (epoch != clearEpoch) return@withLock drawResult
            try {
                target.save(drawResult)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                drawResult
            }
        }
    }

    /** 官网对已保存期次给出冲突时删除本机结果；失败时忽略。 */
    suspend fun delete(
        lotteryType: LotteryType,
        issue: Issue,
    ) {
        val target = store ?: return
        writeLock.withLock {
            try {
                target.delete(lotteryType, issue)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                Unit
            }
        }
    }

    /** 清除全部本机结果并推进清除代次；成功时返回 `true`。 */
    suspend fun clearAll(): Boolean {
        val target = store ?: return true
        return writeLock.withLock {
            clearEpoch += 1L
            try {
                target.clearAll()
                true
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
        }
    }
}

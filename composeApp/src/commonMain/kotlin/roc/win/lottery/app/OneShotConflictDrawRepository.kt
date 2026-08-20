package roc.win.lottery.app

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import roc.win.lottery.data.DrawQueryResult
import roc.win.lottery.data.DrawRepository
import roc.win.lottery.domain.DrawStatus
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryType

/**
 * 只为 Debug 应用级验收注入一次冲突状态的开奖仓库装饰器。
 *
 * @property delegate 保持真实官网查询与 TLS 校验的原始仓库。
 * @property targetIssue 需要在第二次成功查询时注入冲突的期号。
 */
internal class OneShotConflictDrawRepository(
    private val delegate: DrawRepository,
    private val targetIssue: Issue,
) : DrawRepository {
    /** 保护目标期号查询计数和一次性注入状态。 */
    private val stateMutex = Mutex()

    /** 目标期号已经从真实仓库取得成功结果的次数。 */
    private var successfulQueryCount = 0

    /** 是否已经完成本轮唯一一次冲突注入。 */
    private var hasInjectedConflict = false

    /**
     * 始终先执行真实查询，仅把目标期号的第二次成功结果替换为一次冲突状态。
     *
     * @param lotteryType 用户确认的彩种。
     * @param issue 用户确认的期号。
     * @return 原始查询结果，或一次性受控冲突状态。
     */
    override suspend fun getDraw(
        lotteryType: LotteryType,
        issue: Issue,
    ): DrawQueryResult {
        val result = delegate.getDraw(lotteryType, issue)
        if (issue != targetIssue || result !is DrawQueryResult.Success) return result

        val shouldInject =
            stateMutex.withLock {
                successfulQueryCount += 1
                if (successfulQueryCount == CONFLICT_QUERY_NUMBER && !hasInjectedConflict) {
                    hasInjectedConflict = true
                    true
                } else {
                    false
                }
            }
        return if (shouldInject) {
            DrawQueryResult.Unavailable(
                status = DrawStatus.CONFLICT,
                message = "受控验收检测到官方证据冲突，请重新查询恢复真实结果",
            )
        } else {
            result
        }
    }

    /** 一次性冲突注入使用的固定查询序号。 */
    private companion object {
        /** 初查成功后，在第一次主动重查时注入冲突。 */
        const val CONFLICT_QUERY_NUMBER = 2
    }
}

/**
 * 按 Debug 开关和期号格式决定是否装配一次性冲突验收仓库。
 *
 * @param rawIssue 平台调试入口提供的原始目标期号。
 * @param isDebugEnabled 当前包是否允许验收注入。
 * @return 未启用时返回当前仓库本身，启用时返回一次性冲突装饰器。
 */
internal fun DrawRepository.withOneShotConflictInjection(
    rawIssue: String?,
    isDebugEnabled: Boolean,
): DrawRepository {
    if (!isDebugEnabled) return this
    val issue = rawIssue?.trim()?.takeIf(::isSupportedIssueShape) ?: return this
    return OneShotConflictDrawRepository(delegate = this, targetIssue = Issue(issue))
}

/** 只接受 V1 两种彩票的纯数字期号形态。 */
private fun isSupportedIssueShape(issue: String): Boolean =
    issue.length in SUPPORTED_ISSUE_LENGTHS && issue.all { character -> character in '0'..'9' }

/** V1 大乐透和双色球期号的字符长度。 */
private val SUPPORTED_ISSUE_LENGTHS = setOf(5, 7)

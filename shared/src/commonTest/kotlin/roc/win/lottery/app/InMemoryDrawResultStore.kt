package roc.win.lottery.app

import roc.win.lottery.domain.DrawResult
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.persistence.DrawResultStore

/** 记录调用的内存版已开奖结果缓存，可模拟读写失败。 */
internal class InMemoryDrawResultStore(
    /** 预置的本机结果。 */
    initial: List<DrawResult> = emptyList(),
) : DrawResultStore {
    /** 按彩种和期号保存的结果。 */
    private val results = initial.associateBy { it.lotteryType to it.issue }.toMutableMap()

    /** 已执行的保存次数。 */
    var saveCount: Int = 0
        private set

    /** 已删除的期号。 */
    val deletedIssues = mutableListOf<Issue>()

    /** 为 `true` 时读取抛出受控异常。 */
    var failLoad: Boolean = false

    /** 为 `true` 时保存抛出受控异常。 */
    var failSave: Boolean = false

    /** 当前保存的全部结果。 */
    val all: List<DrawResult>
        get() = results.values.toList()

    /** 返回指定期号中已保存的结果。 */
    override suspend fun load(
        lotteryType: LotteryType,
        issues: Collection<Issue>,
    ): Map<Issue, DrawResult> {
        if (failLoad) error("测试读取失败")
        return issues.mapNotNull { issue -> results[lotteryType to issue]?.let { issue to it } }.toMap()
    }

    /** 保存或替换一期结果。 */
    override suspend fun save(drawResult: DrawResult): DrawResult {
        if (failSave) error("测试保存失败")
        saveCount += 1
        results[drawResult.lotteryType to drawResult.issue] = drawResult
        return drawResult
    }

    /** 删除一期结果。 */
    override suspend fun delete(
        lotteryType: LotteryType,
        issue: Issue,
    ): Boolean {
        deletedIssues += issue
        return results.remove(lotteryType to issue) != null
    }

    /** 删除全部结果。 */
    override suspend fun clearAll(): Int {
        val count = results.size
        results.clear()
        return count
    }

    /** 返回指定期号的结果。 */
    fun get(
        lotteryType: LotteryType,
        issue: String,
    ): DrawResult? = results[lotteryType to Issue(issue)]
}

package roc.win.lottery.domain

/** 多期期号展开结果。 */
sealed interface IssueSequenceResult {
    /**
     * 已安全展开全部期号。
     *
     * @property issues 按开奖先后顺序排列的期号。
     */
    data class Success(
        val issues: List<Issue>,
    ) : IssueSequenceResult

    /**
     * 当前版本无法安全展开。
     *
     * @property message 面向用户的阻断原因。
     */
    data class Unsupported(
        val message: String,
    ) : IssueSequenceResult
}

/**
 * 展开多期票的连续开奖期号。
 *
 * 官网已开奖的期号按实际顺序采用，因此可以跨越年度换期；尚未开奖的剩余期次按最后一个已知期号在本年度内顺延预估，
 * 待官网开奖后重新查询即可替换为实际期号。
 */
class IssueSequenceResolver {
    /**
     * 根据起始期号、投注期数和官网已开奖期号生成逐期查询列表。
     *
     * @param lotteryType 彩票玩法。
     * @param firstIssue 票面首个开奖期号。
     * @param periodCount 连续投注期数。
     * @param drawnIssues 官网从起始期号开始已开奖的连续期号；未取得时为空，按本年度序号顺延。
     * @return 完整期号列表或明确阻断原因。
     */
    fun resolve(
        lotteryType: LotteryType,
        firstIssue: Issue,
        periodCount: Int,
        drawnIssues: List<Issue> = emptyList(),
    ): IssueSequenceResult {
        if (periodCount !in MIN_PERIOD_COUNT..MAX_PERIOD_COUNT) {
            return IssueSequenceResult.Unsupported("当前版本只支持 1 至 $MAX_PERIOD_COUNT 期连续投注")
        }
        val prefixLength =
            when (lotteryType) {
                LotteryType.SUPER_LOTTO -> SUPER_LOTTO_YEAR_PREFIX_LENGTH
                LotteryType.DOUBLE_COLOR_BALL -> DOUBLE_COLOR_BALL_YEAR_PREFIX_LENGTH
            }
        val expectedLength = prefixLength + ISSUE_ORDINAL_LENGTH
        val firstOrdinal =
            firstIssue.value
                .takeIf { value -> value.length == expectedLength && value.all(Char::isDigit) }
                ?.takeLast(ISSUE_ORDINAL_LENGTH)
                ?.toInt()
                ?.takeIf { it >= MIN_ISSUE_ORDINAL }
                ?: return IssueSequenceResult.Unsupported("起始期号格式不合法，无法展开多期查询")
        if (periodCount == MIN_PERIOD_COUNT) {
            return IssueSequenceResult.Success(listOf(firstIssue))
        }

        val isUsableOfficialSequence =
            drawnIssues.firstOrNull() == firstIssue &&
                drawnIssues.size <= periodCount &&
                drawnIssues.all { it.value.length == expectedLength && it.value.all(Char::isDigit) } &&
                drawnIssues.zipWithNext().all { (earlier, later) -> earlier.value < later.value }
        val officialIssues = if (isUsableOfficialSequence) drawnIssues else emptyList()
        val anchor = officialIssues.lastOrNull()
        val yearPrefix = (anchor ?: firstIssue).value.take(prefixLength)
        val nextOrdinal =
            if (anchor == null) firstOrdinal else anchor.value.takeLast(ISSUE_ORDINAL_LENGTH).toInt() + 1
        val predictedCount = periodCount - officialIssues.size
        if (nextOrdinal + predictedCount - 1 > MAX_ISSUE_ORDINAL) {
            return IssueSequenceResult.Unsupported("期号序号超出三位期次范围，无法展开多期查询")
        }
        val predictedIssues =
            (nextOrdinal until nextOrdinal + predictedCount).map { ordinal ->
                Issue(yearPrefix + ordinal.toString().padStart(ISSUE_ORDINAL_LENGTH, '0'))
            }
        return IssueSequenceResult.Success(officialIssues + predictedIssues)
    }

    /** 多期展开的范围常量。 */
    companion object {
        /** 最少投注期数。 */
        const val MIN_PERIOD_COUNT = 1

        /** 移动首版允许逐期查询的最大投注期数。 */
        const val MAX_PERIOD_COUNT = 20

        /** 大乐透两位年份前缀长度。 */
        private const val SUPER_LOTTO_YEAR_PREFIX_LENGTH = 2

        /** 双色球四位年份前缀长度。 */
        private const val DOUBLE_COLOR_BALL_YEAR_PREFIX_LENGTH = 4

        /** 两种彩票均使用三位年度期次序号。 */
        private const val ISSUE_ORDINAL_LENGTH = 3

        /** 合法年度期次的最小序号。 */
        private const val MIN_ISSUE_ORDINAL = 1

        /** 三位年度期次的最大序号。 */
        private const val MAX_ISSUE_ORDINAL = 999
    }
}

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

/** 在移动首版已验证边界内展开连续开奖期号。 */
class IssueSequenceResolver {
    /**
     * 根据起始期号和投注期数生成逐期查询列表。
     *
     * 单期查询不受年中安全边界限制；多期查询在接入官方跨年销售期次日历前，只开放年中连续序号。
     *
     * @param lotteryType 彩票玩法。
     * @param firstIssue 票面首个开奖期号。
     * @param periodCount 连续投注期数。
     * @return 完整期号列表或明确阻断原因。
     */
    fun resolve(
        lotteryType: LotteryType,
        firstIssue: Issue,
        periodCount: Int,
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
        val value = firstIssue.value
        if (value.length != expectedLength || value.any { !it.isDigit() }) {
            return IssueSequenceResult.Unsupported("起始期号格式不合法，无法展开多期查询")
        }
        if (periodCount == MIN_PERIOD_COUNT) {
            return IssueSequenceResult.Success(listOf(firstIssue))
        }

        val ordinal =
            value.takeLast(ISSUE_ORDINAL_LENGTH).toIntOrNull()
                ?: return IssueSequenceResult.Unsupported("起始期号格式不合法，无法展开多期查询")
        val lastOrdinal = ordinal + periodCount - 1
        if (ordinal < MIN_ISSUE_ORDINAL || lastOrdinal > MAX_VERIFIED_MULTI_PERIOD_ORDINAL) {
            return IssueSequenceResult.Unsupported(
                "该多期票超出当前已验证的年度中段范围，尚未接入官方跨年度期次日历",
            )
        }
        val yearPrefix = value.take(prefixLength)
        return IssueSequenceResult.Success(
            issues =
                (ordinal..lastOrdinal).map { currentOrdinal ->
                    Issue(yearPrefix + currentOrdinal.toString().padStart(ISSUE_ORDINAL_LENGTH, '0'))
                },
        )
    }

    /** 多期首版的范围常量。 */
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

        /** 尚未接入官方跨年日历前开放的年中安全序号上限。 */
        private const val MAX_VERIFIED_MULTI_PERIOD_ORDINAL = 120
    }
}

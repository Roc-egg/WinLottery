package roc.win.lottery.app

import kotlinx.coroutines.test.runTest
import roc.win.lottery.data.DrawQueryResult
import roc.win.lottery.data.DrawRepository
import roc.win.lottery.domain.DrawPolicy
import roc.win.lottery.domain.DrawResult
import roc.win.lottery.domain.DrawStatus
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.SourceEvidence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/** Debug 应用级一次性冲突仓库测试。 */
class OneShotConflictDrawRepositoryTest {
    /** 非 Debug 包即使提供期号也必须保留原始仓库。 */
    @Test
    fun releasePathNeverEnablesInjection() {
        val delegate = successRepository()

        val result = delegate.withOneShotConflictInjection(TARGET_ISSUE.value, isDebugEnabled = false)

        assertSame(delegate, result)
    }

    /** 非法期号不得创建验收装饰器。 */
    @Test
    fun invalidIssueNeverEnablesInjection() {
        val delegate = successRepository()

        val result = delegate.withOneShotConflictInjection("2026-094", isDebugEnabled = true)

        assertSame(delegate, result)
    }

    /** 目标期第二次成功查询只冲突一次，其他期和后续恢复查询保持真实结果。 */
    @Test
    fun secondSuccessfulTargetQueryConflictsOnceAndThenRecovers() =
        runTest {
            val queriedIssues = mutableListOf<Issue>()
            val delegate = successRepository(queriedIssues)
            val repository =
                delegate.withOneShotConflictInjection(TARGET_ISSUE.value, isDebugEnabled = true)

            assertIs<DrawQueryResult.Success>(repository.getDraw(LotteryType.DOUBLE_COLOR_BALL, TARGET_ISSUE))
            assertIs<DrawQueryResult.Success>(repository.getDraw(LotteryType.DOUBLE_COLOR_BALL, OTHER_ISSUE))

            val conflicted = repository.getDraw(LotteryType.DOUBLE_COLOR_BALL, TARGET_ISSUE)
            assertEquals(DrawStatus.CONFLICT, assertIs<DrawQueryResult.Unavailable>(conflicted).status)

            assertIs<DrawQueryResult.Success>(repository.getDraw(LotteryType.DOUBLE_COLOR_BALL, TARGET_ISSUE))
            assertEquals(listOf(TARGET_ISSUE, OTHER_ISSUE, TARGET_ISSUE, TARGET_ISSUE), queriedIssues)
        }

    /** 创建始终返回结构合法开奖结果的记录型测试仓库。 */
    private fun successRepository(queriedIssues: MutableList<Issue> = mutableListOf()): DrawRepository =
        DrawRepository { lotteryType, issue ->
            queriedIssues += issue
            DrawQueryResult.Success(
                DrawResult(
                    lotteryType = lotteryType,
                    issue = issue,
                    drawDate = "2026-08-18",
                    primaryNumbers = listOf(1, 2, 3, 4, 5, 6),
                    secondaryNumbers = listOf(7),
                    status = DrawStatus.FINAL_PAYOUT,
                    revision = 1,
                    ruleVersion = "SSQ_2026_01",
                    policy = DrawPolicy.STANDARD,
                    prizeTiers = emptyList(),
                    evidence =
                        SourceEvidence(
                            sourceName = "测试主源",
                            sourceUrl = "https://example.invalid/main",
                            fetchedAtEpochMillis = 1L,
                            contentSha256 = "a".repeat(64),
                        ),
                    supportingEvidence = emptyList(),
                ),
            )
        }

    /** 测试使用的冲突目标期号。 */
    private companion object {
        /** 第二次成功查询需要返回冲突的期号。 */
        val TARGET_ISSUE = Issue("2026094")

        /** 用于证明其他期不受影响的期号。 */
        val OTHER_ISSUE = Issue("2026095")
    }
}

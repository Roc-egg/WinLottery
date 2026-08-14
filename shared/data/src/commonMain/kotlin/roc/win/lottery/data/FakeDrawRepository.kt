package roc.win.lottery.data

import kotlinx.coroutines.delay
import roc.win.lottery.domain.DrawPolicy
import roc.win.lottery.domain.DrawResult
import roc.win.lottery.domain.DrawStatus
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.PrizeTier
import roc.win.lottery.domain.SourceEvidence

/**
 * B1 页面状态演示使用的内存开奖仓库。
 *
 * 该实现不联网，也不得用于真实中奖判断。
 */
class FakeDrawRepository : DrawRepository {
    /** 返回一份明确标注为演示数据的固定开奖结果。 */
    override suspend fun getDraw(
        lotteryType: LotteryType,
        issue: Issue,
    ): DrawQueryResult {
        delay(DEMO_DELAY_MILLIS)
        return DrawQueryResult.Success(
            DrawResult(
                lotteryType = lotteryType,
                issue = issue,
                drawDate = "2026-08-13",
                primaryNumbers = listOf(2, 7, 14, 21, 33),
                secondaryNumbers = listOf(4, 9),
                status = DrawStatus.FINAL_PAYOUT,
                revision = 1,
                ruleVersion = "B1_DEMO_ONLY",
                policy = DrawPolicy.STANDARD,
                prizeTiers =
                    listOf(
                        PrizeTier(
                            code = "DEMO_FIRST",
                            displayName = "演示奖级",
                            singlePrizeFen = 10_000L,
                            additionalPrizeFen = 8_000L,
                        ),
                    ),
                evidence =
                    SourceEvidence(
                        sourceName = "B1 本地演示数据",
                        sourceUrl = "about:blank",
                        fetchedAtEpochMillis = 0L,
                        contentSha256 = "demo-only",
                    ),
                supportingEvidence = emptyList(),
            ),
        )
    }

    /** Fake 查询的短暂延迟，用于验证加载状态。 */
    private companion object {
        /** 演示延迟毫秒数。 */
        const val DEMO_DELAY_MILLIS = 350L
    }
}

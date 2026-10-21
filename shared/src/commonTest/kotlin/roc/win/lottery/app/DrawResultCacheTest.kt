package roc.win.lottery.app

import kotlinx.coroutines.test.runTest
import roc.win.lottery.domain.DrawPolicy
import roc.win.lottery.domain.DrawResult
import roc.win.lottery.domain.DrawStatus
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.SourceEvidence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 验证查询开奖缓存的失败隔离和清除代次。 */
class DrawResultCacheTest {
    /** 清除之后，清除前开始的查询迟到的写入被丢弃；新代次的写入正常落库。 */
    @Test
    fun writesStartedBeforeClearAreDropped() =
        runTest {
            val store = InMemoryDrawResultStore()
            val cache = DrawResultCache(store)
            val staleEpoch = cache.clearEpoch

            assertTrue(cache.clearAll())
            cache.save(draw("26090"), staleEpoch)
            assertNull(store.get(LotteryType.SUPER_LOTTO, "26090"))

            cache.save(draw("26091"), cache.clearEpoch)
            assertEquals(draw("26091"), store.get(LotteryType.SUPER_LOTTO, "26091"))
        }

    /** 本机读写失败只影响缓存效果：读取按没有缓存处理，写入返回原结果。 */
    @Test
    fun storeFailuresAreIsolated() =
        runTest {
            val store = InMemoryDrawResultStore(listOf(draw("26090")))
            val cache = DrawResultCache(store)
            store.failLoad = true
            store.failSave = true

            assertEquals(emptyMap(), cache.load(LotteryType.SUPER_LOTTO, listOf(Issue("26090"))))
            assertEquals(draw("26091"), cache.save(draw("26091"), cache.clearEpoch))
        }

    /** 没有本机仓库时缓存不生效。 */
    @Test
    fun disabledCacheIsNoOp() =
        runTest {
            val cache = DrawResultCache(null)

            assertFalse(cache.isEnabled)
            assertEquals(emptyMap(), cache.load(LotteryType.SUPER_LOTTO, listOf(Issue("26090"))))
            assertEquals(draw("26090"), cache.save(draw("26090"), cache.clearEpoch))
            assertTrue(cache.clearAll())
        }

    /** 构造一期最小大乐透开奖。 */
    private fun draw(issue: String): DrawResult =
        DrawResult(
            lotteryType = LotteryType.SUPER_LOTTO,
            issue = Issue(issue),
            drawDate = "2026-08-12",
            primaryNumbers = listOf(1, 2, 3, 4, 5),
            secondaryNumbers = listOf(1, 2),
            status = DrawStatus.FINAL_PAYOUT,
            revision = 1,
            ruleVersion = "DLT_2026_01",
            policy = DrawPolicy.STANDARD,
            prizeTiers = emptyList(),
            evidence = SourceEvidence("测试主源", "https://example.invalid/main", 1L, "a".repeat(64)),
            supportingEvidence = emptyList(),
        )
}

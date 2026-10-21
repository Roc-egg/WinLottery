package roc.win.lottery.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryType
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** 本机历史开奖缓存与官网增量核对规则测试。 */
class HistoricalDrawCacheTest {
    /** 本机数据未过期时直接复用，不发起任何请求。 */
    @Test
    fun freshPersistedEntryNeedsNoNetwork() =
        runTest {
            val fixture = Fixture(storedNewestOrdinal = 500, storedAgeMillis = 0L)

            val result =
                assertIs<HistoricalDrawSyncResult.Ready>(fixture.cache.sync(LotteryType.SUPER_LOTTO, force = false))

            assertEquals(
                Issue("26500"),
                result.entry.draws
                    .last()
                    .issue,
            )
            assertTrue(fixture.repository.requests.isEmpty())
        }

    /** 过期后只取最新一页并补入新开奖，保留最新 500 期。 */
    @Test
    fun staleEntryAppendsNewDrawsWithSingleWindowRequest() =
        runTest {
            val fixture = Fixture(storedNewestOrdinal = 500, storedAgeMillis = STALE_AGE)
            fixture.repository.newestOrdinal = 503

            val result =
                assertIs<HistoricalDrawSyncResult.Ready>(fixture.cache.sync(LotteryType.SUPER_LOTTO, force = false))

            assertEquals(
                listOf(LotteryType.SUPER_LOTTO to HistoricalDrawCache.INCREMENTAL_WINDOW_SIZE),
                fixture.repository.requests,
            )
            assertEquals(
                Issue("26004"),
                result.entry.draws
                    .first()
                    .issue,
            )
            assertEquals(
                Issue("26503"),
                result.entry.draws
                    .last()
                    .issue,
            )
            assertEquals(500, result.entry.draws.size)
            assertEquals(
                result.entry.draws,
                fixture.store.sets
                    .getValue(LotteryType.SUPER_LOTTO)
                    .draws,
            )
            assertEquals(1, fixture.store.replaceCount)
        }

    /** 官网没有新开奖时只更新核对时间，内容版本不变。 */
    @Test
    fun unchangedWindowOnlyTouchesFetchedAt() =
        runTest {
            val fixture = Fixture(storedNewestOrdinal = 500, storedAgeMillis = STALE_AGE)
            val before = assertNotNull(fixture.cache.peek(LotteryType.SUPER_LOTTO))

            val result =
                assertIs<HistoricalDrawSyncResult.Ready>(fixture.cache.sync(LotteryType.SUPER_LOTTO, force = false))

            assertEquals(before.revision, result.entry.revision)
            assertEquals(fixture.clock.nowEpochMillis, result.entry.fetchedAtEpochMillis)
            assertEquals(1, fixture.store.touchCount)
            assertEquals(0, fixture.store.replaceCount)
            assertEquals(
                fixture.clock.nowEpochMillis,
                fixture.store.sets
                    .getValue(LotteryType.SUPER_LOTTO)
                    .fetchedAtEpochMillis,
            )
        }

    /** 重叠期次号码与本机不一致时视为官网修订，完整重新加载并提示。 */
    @Test
    fun overlapMismatchReloadsFullHistory() =
        runTest {
            val fixture = Fixture(storedNewestOrdinal = 500, storedAgeMillis = STALE_AGE)
            fixture.repository.revisedOrdinals = setOf(450)

            val result =
                assertIs<HistoricalDrawSyncResult.Ready>(fixture.cache.sync(LotteryType.SUPER_LOTTO, force = false))

            assertEquals(listOf(100, 500), fixture.repository.requests.map { it.second })
            assertTrue(result.notice.orEmpty().contains("修订"))
            assertEquals(testHistoricalDraw(LotteryType.SUPER_LOTTO, 450, revised = true), result.entry.draws[449])
            assertEquals(
                result.entry.draws,
                fixture.store.sets
                    .getValue(LotteryType.SUPER_LOTTO)
                    .draws,
            )
        }

    /** 本机落后超过一页、没有重叠时完整重新加载。 */
    @Test
    fun windowWithoutOverlapReloadsFullHistory() =
        runTest {
            val fixture = Fixture(storedNewestOrdinal = 500, storedAgeMillis = STALE_AGE)
            fixture.repository.newestOrdinal = 700

            val result =
                assertIs<HistoricalDrawSyncResult.Ready>(fixture.cache.sync(LotteryType.SUPER_LOTTO, force = false))

            assertEquals(listOf(100, 500), fixture.repository.requests.map { it.second })
            assertEquals(
                Issue("26201"),
                result.entry.draws
                    .first()
                    .issue,
            )
            assertEquals(
                Issue("26700"),
                result.entry.draws
                    .last()
                    .issue,
            )
        }

    /** 官网最新期早于本机最新期时不信任拼接，完整重新加载。 */
    @Test
    fun regressedOfficialNewestReloadsFullHistory() =
        runTest {
            val fixture = Fixture(storedNewestOrdinal = 600, storedAgeMillis = STALE_AGE)
            fixture.repository.newestOrdinal = 598

            val result =
                assertIs<HistoricalDrawSyncResult.Ready>(fixture.cache.sync(LotteryType.SUPER_LOTTO, force = false))

            assertEquals(listOf(100, 500), fixture.repository.requests.map { it.second })
            assertEquals(
                Issue("26598"),
                result.entry.draws
                    .last()
                    .issue,
            )
        }

    /** 网络失败时保留本机数据，不删除也不改写。 */
    @Test
    fun networkFailureKeepsCachedEntry() =
        runTest {
            val fixture = Fixture(storedNewestOrdinal = 500, storedAgeMillis = STALE_AGE)
            fixture.repository.failureMessage = "测试断网"

            val result =
                assertIs<HistoricalDrawSyncResult.Failed>(fixture.cache.sync(LotteryType.SUPER_LOTTO, force = false))

            assertEquals("测试断网", result.message)
            assertEquals(
                Issue("26500"),
                result.cached
                    ?.draws
                    ?.last()
                    ?.issue,
            )
            assertEquals(
                Issue("26500"),
                fixture.store.sets
                    .getValue(LotteryType.SUPER_LOTTO)
                    .draws
                    .last()
                    .issue,
            )
            assertEquals(0, fixture.store.replaceCount)
        }

    /** 没有本机数据时完整加载并写入本机。 */
    @Test
    fun missingEntryLoadsFullHistoryAndPersists() =
        runTest {
            val fixture = Fixture(storedNewestOrdinal = null, storedAgeMillis = 0L)

            assertIs<HistoricalDrawSyncResult.Ready>(fixture.cache.sync(LotteryType.DOUBLE_COLOR_BALL, force = false))

            assertEquals(listOf(LotteryType.DOUBLE_COLOR_BALL to 500), fixture.repository.requests)
            assertEquals(
                Issue("2026500"),
                fixture.store.sets
                    .getValue(LotteryType.DOUBLE_COLOR_BALL)
                    .draws
                    .last()
                    .issue,
            )
        }

    /** 官网样本存在同年度空档时只放入内存，不写入本机。 */
    @Test
    fun fullLoadWithIssueGapStaysInMemoryOnly() =
        runTest {
            val fixture = Fixture(storedNewestOrdinal = null, storedAgeMillis = 0L)
            fixture.repository.newestOrdinal = 600
            fixture.repository.missingOrdinals = setOf(550)

            val result =
                assertIs<HistoricalDrawSyncResult.Ready>(fixture.cache.sync(LotteryType.SUPER_LOTTO, force = false))

            assertTrue(result.notice.orEmpty().contains("空档"))
            assertEquals(0, fixture.store.replaceCount)
            assertNotNull(fixture.cache.current(LotteryType.SUPER_LOTTO))
        }

    /** 本机写入失败时仍返回本次数据并提示仅在运行期间有效。 */
    @Test
    fun persistFailureStillReturnsDataWithNotice() =
        runTest {
            val fixture = Fixture(storedNewestOrdinal = null, storedAgeMillis = 0L)
            fixture.store.failWrites = true

            val result =
                assertIs<HistoricalDrawSyncResult.Ready>(fixture.cache.sync(LotteryType.SUPER_LOTTO, force = false))

            assertTrue(result.notice.orEmpty().contains("本机保存失败"))
            assertEquals(500, result.entry.draws.size)
        }

    /** 同一彩种并发核对只发起一次请求。 */
    @Test
    fun concurrentSyncSharesOneRequest() =
        runTest {
            val fixture = Fixture(storedNewestOrdinal = 500, storedAgeMillis = STALE_AGE)
            val gate = CompletableDeferred<Unit>()
            fixture.repository.gate = gate
            fixture.repository.newestOrdinal = 502

            val first = async { fixture.cache.sync(LotteryType.SUPER_LOTTO, force = false) }
            fixture.repository.started.await()
            val second = async { fixture.cache.sync(LotteryType.SUPER_LOTTO, force = false) }
            gate.complete(Unit)

            assertIs<HistoricalDrawSyncResult.Ready>(first.await())
            val shared = assertIs<HistoricalDrawSyncResult.Ready>(second.await())
            assertEquals(
                Issue("26502"),
                shared.entry.draws
                    .last()
                    .issue,
            )
            assertEquals(1, fixture.repository.requests.size)
        }

    /** 核对进行中清除本机数据后，迟到结果不得写回内存或本机。 */
    @Test
    fun clearAllDuringInFlightRefreshDoesNotResurrectData() =
        runTest {
            val fixture = Fixture(storedNewestOrdinal = 500, storedAgeMillis = STALE_AGE)
            val gate = CompletableDeferred<Unit>()
            fixture.repository.gate = gate
            fixture.repository.newestOrdinal = 502

            val refresh = async { fixture.cache.sync(LotteryType.SUPER_LOTTO, force = false) }
            fixture.repository.started.await()
            assertTrue(fixture.cache.clearAll())
            gate.complete(Unit)

            assertIs<HistoricalDrawSyncResult.Failed>(refresh.await())
            assertNull(fixture.cache.current(LotteryType.SUPER_LOTTO))
            assertTrue(fixture.store.sets.isEmpty())
        }

    /** 时钟回拨或超过间隔都视为需要核对，间隔内视为新鲜。 */
    @Test
    fun stalenessFollowsRefreshInterval() =
        runTest {
            val fixture = Fixture(storedNewestOrdinal = 500, storedAgeMillis = 0L)
            val entry = assertNotNull(fixture.cache.peek(LotteryType.SUPER_LOTTO))

            fixture.clock.nowEpochMillis = entry.fetchedAtEpochMillis + 29.minutes.inWholeMilliseconds
            assertTrue(!fixture.cache.isStale(entry))
            fixture.clock.nowEpochMillis = entry.fetchedAtEpochMillis + 30.minutes.inWholeMilliseconds
            assertTrue(fixture.cache.isStale(entry))
            fixture.clock.nowEpochMillis = entry.fetchedAtEpochMillis - 1L
            assertTrue(fixture.cache.isStale(entry))
        }

    /** 组装测试时钟、本机缓存和官网仓库。 */
    private class Fixture(
        storedNewestOrdinal: Int?,
        storedAgeMillis: Long,
    ) {
        /** 测试时钟。 */
        val clock = MutableTestClock()

        /** 内存版本机缓存。 */
        val store =
            InMemoryHistoricalDrawStore(
                listOfNotNull(
                    storedNewestOrdinal?.let { ordinal ->
                        testStoredHistory(LotteryType.SUPER_LOTTO, ordinal, clock.nowEpochMillis - storedAgeMillis)
                    },
                ),
            )

        /** 可脚本控制的官网仓库。 */
        val repository = ScriptedHistoryRepository(clock, newestOrdinal = storedNewestOrdinal ?: 500)

        /** 待测缓存。 */
        val cache = HistoricalDrawCache(repository, store, clock, EmptyCoroutineContext)
    }

    /** 测试固定数据。 */
    private companion object {
        /** 超过自动核对间隔的本机数据年龄。 */
        val STALE_AGE: Long = 31.minutes.inWholeMilliseconds
    }
}

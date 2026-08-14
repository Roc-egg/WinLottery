package roc.win.lottery.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import roc.win.lottery.domain.BetLine
import roc.win.lottery.domain.ConfirmedTicket
import roc.win.lottery.domain.ConfirmedValue
import roc.win.lottery.domain.DrawStatus
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryPrizeCalculator
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.PrizeCheckStatus
import roc.win.lottery.domain.RuleVersion
import roc.win.lottery.domain.TicketFieldOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** 官网仓库请求范围、状态映射、稳定性、修订和冲突测试。 */
class OfficialDrawRepositoryTest {
    /** 历史双色球应在同一次用户调用内完成双源核对并返回最终奖金状态。 */
    @Test
    fun historicalDoubleColorBallReturnsFinalPayout() =
        runTest {
            val requests = mutableListOf<String>()
            val repository =
                repository(clock = MutableTestClock("2026-08-13T01:00:00Z")) { request ->
                    requests += request.url.toString()
                    when (request.url.encodedPath) {
                        SSQ_MAIN_PATH -> jsonResponse(DrawContractFixtures.doubleColorBallMain())
                        SSQ_SUPPORTING_PATH -> jsonResponse(DrawContractFixtures.doubleColorBallSupporting())
                        else -> error("收到未预期请求：${request.url}")
                    }
                }

            val result = repository.getDraw(LotteryType.DOUBLE_COLOR_BALL, Issue("2026091"))
            val draw = assertIs<DrawQueryResult.Success>(result).drawResult

            assertEquals(DrawStatus.FINAL_PAYOUT, draw.status)
            assertEquals(RuleVersion.SSQ_2026_01.code, draw.ruleVersion)
            assertEquals(1, draw.revision)
            assertEquals(64, draw.evidence.contentSha256.length)
            assertEquals(1, draw.supportingEvidence.size)
            assertEquals(2, requests.size)
            assertTrue(requests[0].contains("issueStart=2026091"))
            assertTrue(requests[0].contains("issueEnd=2026091"))
            assertTrue(requests[0].contains("pageSize=1"))
            assertTrue(requests[1].contains("code=2026091"))
        }

    /** 奖金缺失时双源仍可确认号码，但仓库不得返回确定金额状态。 */
    @Test
    fun missingFloatingPayoutReturnsFinalNumbers() =
        runTest {
            val main = DrawContractFixtures.doubleColorBallMain(firstPrizeAmount = "奖金待定")
            val supporting = DrawContractFixtures.doubleColorBallSupporting(firstPrizeAmount = "奖金待定")
            val repository = ssqRepository(main, supporting, MutableTestClock("2026-08-13T01:00:00Z"))

            val result = repository.getDraw(LotteryType.DOUBLE_COLOR_BALL, Issue("2026091"))

            assertEquals(DrawStatus.FINAL_NUMBERS, assertIs<DrawQueryResult.Success>(result).drawResult.status)
        }

    /** 发布窗口首次一致结果只能进入候选，60 秒后再次主动调用才可放行。 */
    @Test
    fun publishingWindowRequiresSecondUserRefreshAfterSixtySeconds() =
        runTest {
            val clock = MutableTestClock("2026-08-09T13:00:00Z")
            val repository =
                ssqRepository(
                    DrawContractFixtures.doubleColorBallMain(),
                    DrawContractFixtures.doubleColorBallSupporting(),
                    clock,
                )

            val first = repository.getDraw(LotteryType.DOUBLE_COLOR_BALL, Issue("2026091"))
            assertEquals(DrawStatus.PUBLISHING, assertIs<DrawQueryResult.Unavailable>(first).status)

            clock.advance(Duration.parse("59s"))
            val tooEarly = repository.getDraw(LotteryType.DOUBLE_COLOR_BALL, Issue("2026091"))
            assertEquals(DrawStatus.PUBLISHING, assertIs<DrawQueryResult.Unavailable>(tooEarly).status)

            clock.advance(Duration.parse("1s"))
            val stable = repository.getDraw(LotteryType.DOUBLE_COLOR_BALL, Issue("2026091"))
            assertEquals(DrawStatus.FINAL_PAYOUT, assertIs<DrawQueryResult.Success>(stable).drawResult.status)
        }

    /** 主、辅助号码不一致必须进入冲突，绝不能输出开奖或未中奖结论。 */
    @Test
    fun inconsistentSourcesReturnConflict() =
        runTest {
            val repository =
                ssqRepository(
                    DrawContractFixtures.doubleColorBallMain(),
                    DrawContractFixtures.doubleColorBallSupporting(red = "01,03,08,16,22,30"),
                    MutableTestClock("2026-08-13T01:00:00Z"),
                )

            val result = repository.getDraw(LotteryType.DOUBLE_COLOR_BALL, Issue("2026091"))

            assertEquals(DrawStatus.CONFLICT, assertIs<DrawQueryResult.Unavailable>(result).status)
        }

    /** 同一期主内容在主动刷新后变化必须增加修订并永久保持冲突。 */
    @Test
    fun changedMainContentReturnsConflict() =
        runTest {
            var queryRound = 0
            val repository =
                repository(clock = MutableTestClock("2026-08-13T01:00:00Z")) { request ->
                    when (request.url.encodedPath) {
                        SSQ_MAIN_PATH -> {
                            queryRound += 1
                            val red = if (queryRound == 1) DrawContractFixtures.DEFAULT_SSQ_RED else "01,03,08,16,22,30"
                            jsonResponse(DrawContractFixtures.doubleColorBallMain(red = red))
                        }

                        SSQ_SUPPORTING_PATH -> {
                            jsonResponse(DrawContractFixtures.doubleColorBallSupporting())
                        }

                        else -> {
                            error("收到未预期请求：${request.url}")
                        }
                    }
                }

            assertIs<DrawQueryResult.Success>(
                repository.getDraw(LotteryType.DOUBLE_COLOR_BALL, Issue("2026091")),
            )
            val changed = repository.getDraw(LotteryType.DOUBLE_COLOR_BALL, Issue("2026091"))
            assertEquals(DrawStatus.CONFLICT, assertIs<DrawQueryResult.Unavailable>(changed).status)

            val stillConflicted = repository.getDraw(LotteryType.DOUBLE_COLOR_BALL, Issue("2026091"))
            assertEquals(DrawStatus.CONFLICT, assertIs<DrawQueryResult.Unavailable>(stillConflicted).status)
        }

    /** 奖金从待定到官方金额属于正常发布进展，应增加修订而不是进入冲突。 */
    @Test
    fun payoutCompletionCreatesNewRevisionWithoutConflict() =
        runTest {
            var queryRound = 0
            val repository =
                repository(clock = MutableTestClock("2026-08-13T01:00:00Z")) { request ->
                    when (request.url.encodedPath) {
                        SSQ_MAIN_PATH -> {
                            queryRound += 1
                            jsonResponse(
                                DrawContractFixtures.doubleColorBallMain(
                                    firstPrizeAmount = if (queryRound == 1) "奖金待定" else "10000000",
                                ),
                            )
                        }

                        SSQ_SUPPORTING_PATH -> {
                            jsonResponse(
                                DrawContractFixtures.doubleColorBallSupporting(
                                    firstPrizeAmount = if (queryRound == 1) "奖金待定" else "10000000",
                                ),
                            )
                        }

                        else -> {
                            error("收到未预期请求：${request.url}")
                        }
                    }
                }

            val first =
                assertIs<DrawQueryResult.Success>(
                    repository.getDraw(LotteryType.DOUBLE_COLOR_BALL, Issue("2026091")),
                ).drawResult
            val completed =
                assertIs<DrawQueryResult.Success>(
                    repository.getDraw(LotteryType.DOUBLE_COLOR_BALL, Issue("2026091")),
                ).drawResult

            assertEquals(DrawStatus.FINAL_NUMBERS, first.status)
            assertEquals(1, first.revision)
            assertEquals(DrawStatus.FINAL_PAYOUT, completed.status)
            assertEquals(2, completed.revision)
        }

    /** 单边奖金先发布时应保持发布中，双边同步后才生成新修订并放行。 */
    @Test
    fun oneSidedPayoutCompletionWaitsForBothSources() =
        runTest {
            var queryRound = 0
            val repository =
                repository(clock = MutableTestClock("2026-08-13T01:00:00Z")) { request ->
                    when (request.url.encodedPath) {
                        SSQ_MAIN_PATH -> {
                            queryRound += 1
                            jsonResponse(
                                DrawContractFixtures.doubleColorBallMain(
                                    firstPrizeAmount = if (queryRound == 1) "奖金待定" else "10000000",
                                ),
                            )
                        }

                        SSQ_SUPPORTING_PATH -> {
                            jsonResponse(DrawContractFixtures.doubleColorBallSupporting(firstPrizeAmount = "10000000"))
                        }

                        else -> {
                            error("收到未预期请求：${request.url}")
                        }
                    }
                }

            val first = repository.getDraw(LotteryType.DOUBLE_COLOR_BALL, Issue("2026091"))
            assertEquals(DrawStatus.PUBLISHING, assertIs<DrawQueryResult.Unavailable>(first).status)

            val synchronized =
                assertIs<DrawQueryResult.Success>(
                    repository.getDraw(LotteryType.DOUBLE_COLOR_BALL, Issue("2026091")),
                ).drawResult
            assertEquals(DrawStatus.FINAL_PAYOUT, synchronized.status)
            assertEquals(2, synchronized.revision)
        }

    /** 最小官网夹具应完整走通仓库、统一模型和双色球规则金额计算。 */
    @Test
    fun repositoryResultFeedsPrizeCalculatorEndToEnd() =
        runTest {
            val repository =
                ssqRepository(
                    DrawContractFixtures.doubleColorBallMain(),
                    DrawContractFixtures.doubleColorBallSupporting(),
                    MutableTestClock("2026-08-13T01:00:00Z"),
                )
            val draw =
                assertIs<DrawQueryResult.Success>(
                    repository.getDraw(LotteryType.DOUBLE_COLOR_BALL, Issue("2026091")),
                ).drawResult
            val ticket =
                ConfirmedTicket(
                    lotteryType = confirmed(LotteryType.DOUBLE_COLOR_BALL),
                    issue = confirmed(Issue("2026091")),
                    betLines =
                        listOf(
                            BetLine(
                                primaryNumbers = confirmed(listOf(2, 13, 14, 16, 20, 30)),
                                secondaryNumbers = confirmed(listOf(5)),
                                isAdditional = confirmed(false),
                                originalText = "端到端测试投注行",
                            ),
                        ),
                    multiplier = confirmed(2),
                    periodCount = confirmed(1),
                    paidAmountFen = confirmed(400L),
                )

            val result = LotteryPrizeCalculator().calculate(ticket, draw)

            assertEquals(PrizeCheckStatus.WIN, result.status)
            assertEquals(600_000L, result.estimatedPrizeFen)
        }

    /** 已观察到记录后刷新为空必须进入冲突。 */
    @Test
    fun publishedRecordDisappearingReturnsConflict() =
        runTest {
            var queryRound = 0
            val repository =
                repository(clock = MutableTestClock("2026-08-13T01:00:00Z")) { request ->
                    when (request.url.encodedPath) {
                        SSQ_MAIN_PATH -> {
                            queryRound += 1
                            jsonResponse(
                                DrawContractFixtures.doubleColorBallMain(includeRecord = queryRound == 1),
                            )
                        }

                        SSQ_SUPPORTING_PATH -> {
                            jsonResponse(DrawContractFixtures.doubleColorBallSupporting())
                        }

                        else -> {
                            error("收到未预期请求：${request.url}")
                        }
                    }
                }

            assertIs<DrawQueryResult.Success>(
                repository.getDraw(LotteryType.DOUBLE_COLOR_BALL, Issue("2026091")),
            )
            val result = repository.getDraw(LotteryType.DOUBLE_COLOR_BALL, Issue("2026091"))

            assertEquals(DrawStatus.CONFLICT, assertIs<DrawQueryResult.Unavailable>(result).status)
        }

    /** 大乐透当前最新期可用聚合接口交叉核对并返回最终奖金。 */
    @Test
    fun latestSuperLottoUsesAggregateSupportingSource() =
        runTest {
            val requests = mutableListOf<String>()
            val repository =
                repository(clock = MutableTestClock("2026-08-13T01:00:00Z")) { request ->
                    requests += request.url.toString()
                    when (request.url.encodedPath) {
                        DLT_MAIN_PATH -> {
                            jsonResponse(
                                DrawContractFixtures.superLottoMain(
                                    firstAdditionalCount = "1",
                                    firstAdditionalAmount = "8,000,000",
                                ),
                            )
                        }

                        DLT_SUPPORTING_PATH -> {
                            jsonResponse(
                                DrawContractFixtures.superLottoSupporting(
                                    firstAdditionalCount = "1",
                                    firstAdditionalAmount = "8,000,000",
                                ),
                            )
                        }

                        else -> {
                            error("收到未预期请求：${request.url}")
                        }
                    }
                }

            val result = repository.getDraw(LotteryType.SUPER_LOTTO, Issue("26091"))
            val draw = assertIs<DrawQueryResult.Success>(result).drawResult

            assertEquals(DrawStatus.FINAL_PAYOUT, draw.status)
            assertEquals(RuleVersion.DLT_2026_01.code, draw.ruleVersion)
            assertEquals(2, requests.size)
            assertTrue(requests[0].contains("startTerm=26091"))
            assertTrue(requests[0].contains("endTerm=26091"))
            assertTrue(requests[1].contains("param=85%2C0") || requests[1].contains("param=85,0"))
        }

    /** 大乐透非最新历史期缺少可解析公告证据时必须保守停留在发布中。 */
    @Test
    fun historicalSuperLottoWithoutIndependentEvidenceIsPublishing() =
        runTest {
            val repository =
                repository(clock = MutableTestClock("2026-08-13T01:00:00Z")) { request ->
                    when (request.url.encodedPath) {
                        DLT_MAIN_PATH -> {
                            jsonResponse(
                                DrawContractFixtures.superLottoMain(
                                    issue = "26090",
                                    numbers = "09 14 17 19 24 02 09",
                                    drawDate = "2026-08-10",
                                ),
                            )
                        }

                        DLT_SUPPORTING_PATH -> {
                            jsonResponse(DrawContractFixtures.superLottoSupporting(issue = "26091"))
                        }

                        else -> {
                            error("收到未预期请求：${request.url}")
                        }
                    }
                }

            val result = repository.getDraw(LotteryType.SUPER_LOTTO, Issue("26090"))

            assertEquals(DrawStatus.PUBLISHING, assertIs<DrawQueryResult.Unavailable>(result).status)
        }

    /** 主源空结果不得触发辅助查询，也不得被解释为未中奖。 */
    @Test
    fun emptyMainResultDoesNotQuerySupportingSource() =
        runTest {
            var requestCount = 0
            val repository =
                repository(clock = MutableTestClock("2026-08-13T01:00:00Z")) {
                    requestCount += 1
                    jsonResponse(DrawContractFixtures.doubleColorBallMain(includeRecord = false))
                }

            val result = repository.getDraw(LotteryType.DOUBLE_COLOR_BALL, Issue("2026091"))

            assertEquals(DrawStatus.NOT_PUBLISHED, assertIs<DrawQueryResult.Unavailable>(result).status)
            assertEquals(1, requestCount)
        }

    /** HTTP 业务通道失败必须映射为数据源不可用。 */
    @Test
    fun httpFailureIsSourceUnavailable() =
        runTest {
            val repository =
                repository(clock = MutableTestClock("2026-08-13T01:00:00Z")) {
                    respondError(HttpStatusCode.ServiceUnavailable)
                }

            val result = repository.getDraw(LotteryType.DOUBLE_COLOR_BALL, Issue("2026091"))

            assertEquals(DrawStatus.SOURCE_UNAVAILABLE, assertIs<DrawQueryResult.Unavailable>(result).status)
        }

    /** 连接类 I/O 异常必须映射为网络不可用。 */
    @Test
    fun ioFailureIsNetworkUnavailable() =
        runTest {
            val repository =
                repository(clock = MutableTestClock("2026-08-13T01:00:00Z")) {
                    throw IOException("测试网络断开")
                }

            val result = repository.getDraw(LotteryType.DOUBLE_COLOR_BALL, Issue("2026091"))

            assertEquals(DrawStatus.NETWORK_UNAVAILABLE, assertIs<DrawQueryResult.Unavailable>(result).status)
        }

    /** 规则不支持的期号必须在发起网络请求前阻断。 */
    @Test
    fun unsupportedRuleDoesNotRequestNetwork() =
        runTest {
            var requestCount = 0
            val repository =
                repository(clock = MutableTestClock("2026-08-13T01:00:00Z")) {
                    requestCount += 1
                    jsonResponse("{}")
                }

            val result = repository.getDraw(LotteryType.SUPER_LOTTO, Issue("26013"))

            assertEquals(DrawStatus.PUBLISHING, assertIs<DrawQueryResult.Unavailable>(result).status)
            assertEquals(0, requestCount)
        }

    /** 北京时间次日九点是历史期免 60 秒等待的严格边界。 */
    @Test
    fun historicalCutoffUsesChinaTime() {
        val before = Instant.parse("2026-08-10T00:59:59Z").toEpochMilliseconds()
        val atCutoff = Instant.parse("2026-08-10T01:00:00Z").toEpochMilliseconds()

        assertEquals(false, isPastHistoricalCutoff("2026-08-09", before))
        assertEquals(true, isPastHistoricalCutoff("2026-08-09", atCutoff))
    }

    /** 创建固定双色球主、辅助响应的仓库。 */
    private fun ssqRepository(
        main: String,
        supporting: String,
        clock: MutableTestClock,
    ): OfficialDrawRepository =
        repository(clock) { request ->
            when (request.url.encodedPath) {
                SSQ_MAIN_PATH -> jsonResponse(main)
                SSQ_SUPPORTING_PATH -> jsonResponse(supporting)
                else -> error("收到未预期请求：${request.url}")
            }
        }

    /** 创建带 MockEngine 的官网仓库。 */
    private fun repository(
        clock: MutableTestClock,
        handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) ->
        io.ktor.client.request.HttpResponseData,
    ): OfficialDrawRepository {
        val client = HttpClient(MockEngine(handler))
        return OfficialDrawRepository(httpClient = client, clock = clock)
    }

    /** 返回 JSON 200 响应。 */
    private fun MockRequestHandleScope.jsonResponse(body: String) =
        respond(
            content = body,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )

    /** 创建人工确认字段。 */
    private fun <T> confirmed(value: T): ConfirmedValue<T> = ConfirmedValue(value, TicketFieldOrigin.USER)

    /** 可由测试显式推进的时钟。 */
    private class MutableTestClock(
        /** 当前测试时间。 */
        private var current: Instant,
    ) : Clock {
        /** 使用 ISO 时间创建测试时钟。 */
        constructor(isoInstant: String) : this(Instant.parse(isoInstant))

        /** 返回当前测试时间。 */
        override fun now(): Instant = current

        /** 将测试时钟向前推进指定时长。 */
        fun advance(duration: Duration) {
            current += duration
        }
    }

    /** 官网请求路径常量。 */
    private companion object {
        /** 双色球列表主接口路径。 */
        const val SSQ_MAIN_PATH = "/cwl_admin/front/cwlkj/search/kjxx/findDrawNotice"

        /** 双色球详情辅助接口路径。 */
        const val SSQ_SUPPORTING_PATH = "/cwl_admin/front/cwlkj/search/kjxx/findKjxx/forIssue"

        /** 大乐透历史主接口路径。 */
        const val DLT_MAIN_PATH = "/gateway/lottery/getHistoryPageListV1.qry"

        /** 大乐透聚合辅助接口路径。 */
        const val DLT_SUPPORTING_PATH = "/gateway/lottery/getDigitalDrawInfoV1.qry"
    }
}

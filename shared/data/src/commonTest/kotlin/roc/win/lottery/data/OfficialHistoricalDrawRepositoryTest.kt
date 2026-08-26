package roc.win.lottery.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import roc.win.lottery.domain.LotteryType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** 官网真实历史开奖仓库测试。 */
class OfficialHistoricalDrawRepositoryTest {
    /** 大乐透必须按官网每页 100 条分页并精确截取请求期数。 */
    @Test
    fun loadsSuperLottoHistoryAcrossPages() =
        runTest {
            val requests = mutableListOf<String>()
            val allIssues = (1..205).map { "26${it.toString().padStart(3, '0')}" }.reversed()
            val repository =
                repository { request ->
                    requests += request.url.toString()
                    val pageNo = requireNotNull(request.url.parameters["pageNo"]?.toIntOrNull())
                    val pageIssues = allIssues.drop((pageNo - 1) * 100).take(100)
                    jsonResponse(superLottoPage(pageNo, total = allIssues.size, issues = pageIssues))
                }

            val result = repository.getLatestDraws(LotteryType.SUPER_LOTTO, count = 205)
            val success = assertIs<HistoricalDrawQueryResult.Success>(result)

            assertEquals(205, success.draws.size)
            assertEquals(
                "26001",
                success.draws
                    .first()
                    .issue.value,
            )
            assertEquals(
                "26205",
                success.draws
                    .last()
                    .issue.value,
            )
            assertEquals("中国体彩网历史开奖", success.sourceName)
            assertEquals(3, requests.size)
            assertTrue(requests.all { it.contains("pageSize=100") })
        }

    /** 双色球应使用单页能力加载完整 500 期并携带官网来源页请求头。 */
    @Test
    fun loadsFiveHundredDoubleColorBallDrawsInOneRequest() =
        runTest {
            var requestCount = 0
            var referer: String? = null
            val issues = (1..500).map { "2026${it.toString().padStart(3, '0')}" }.reversed()
            val repository =
                repository { request ->
                    requestCount += 1
                    referer = request.headers[HttpHeaders.Referrer]
                    assertEquals("500", request.url.parameters["pageSize"])
                    jsonResponse(doubleColorBallPage(issues))
                }

            val result = repository.getLatestDraws(LotteryType.DOUBLE_COLOR_BALL, count = 500)
            val success = assertIs<HistoricalDrawQueryResult.Success>(result)

            assertEquals(500, success.draws.size)
            assertEquals(
                "2026001",
                success.draws
                    .first()
                    .issue.value,
            )
            assertEquals(
                "2026500",
                success.draws
                    .last()
                    .issue.value,
            )
            assertEquals("中国福彩网开奖公告", success.sourceName)
            assertEquals("https://www.cwl.gov.cn/", referer)
            assertEquals(1, requestCount)
        }

    /** 超过 500 期的请求必须在联网前被拒绝。 */
    @Test
    fun rejectsCountAboveSessionBoundaryBeforeNetwork() =
        runTest {
            var requestCount = 0
            val repository =
                repository {
                    requestCount += 1
                    jsonResponse("{}")
                }

            val result = repository.getLatestDraws(LotteryType.SUPER_LOTTO, count = 501)

            assertEquals(
                HistoricalDrawFailureReason.SOURCE_UNAVAILABLE,
                assertIs<HistoricalDrawQueryResult.Unavailable>(result).reason,
            )
            assertEquals(0, requestCount)
        }

    /** 历史开奖连接类异常必须映射为网络不可用。 */
    @Test
    fun mapsHistoryIoFailureToNetworkUnavailable() =
        runTest {
            val repository = repository { throw IOException("测试网络断开") }

            val result = repository.getLatestDraws(LotteryType.DOUBLE_COLOR_BALL, count = 50)

            assertEquals(
                HistoricalDrawFailureReason.NETWORK_UNAVAILABLE,
                assertIs<HistoricalDrawQueryResult.Unavailable>(result).reason,
            )
        }

    /** 创建固定时钟和 MockEngine 的官网仓库。 */
    private fun repository(
        handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) ->
        io.ktor.client.request.HttpResponseData,
    ): OfficialDrawRepository =
        OfficialDrawRepository(
            httpClient = HttpClient(MockEngine(handler)),
            clock = FixedClock,
        )

    /** 返回 JSON 200 响应。 */
    private fun MockRequestHandleScope.jsonResponse(body: String) =
        respond(
            content = body,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )

    /** 构造大乐透历史分页响应。 */
    private fun superLottoPage(
        pageNo: Int,
        total: Int,
        issues: List<String>,
    ): String =
        buildJsonObject {
            put("success", true)
            put("errorCode", "0")
            put(
                "value",
                buildJsonObject {
                    put("pageNo", pageNo)
                    put("pageSize", 100)
                    put("total", total)
                    put(
                        "list",
                        buildJsonArray {
                            issues.forEach { issue -> add(superLottoRecord(issue)) }
                        },
                    )
                },
            )
        }.toString()

    /** 构造一条大乐透历史记录。 */
    private fun superLottoRecord(issue: String) =
        buildJsonObject {
            put("lotteryGameNum", "85")
            put("verify", 1)
            put("lotteryDrawStatus", 20)
            put("lotteryNotice", 1)
            put("lotteryDrawNum", issue)
            put("lotteryDrawTime", "2026-01-01")
            put("lotteryDrawResult", "01 02 03 04 05 01 02")
        }

    /** 构造双色球历史分页响应。 */
    private fun doubleColorBallPage(issues: List<String>): String =
        buildJsonObject {
            put("state", 0)
            put("pageNo", 1)
            put("pageSize", issues.size)
            put("total", issues.size)
            put(
                "result",
                buildJsonArray {
                    issues.forEach { issue ->
                        add(
                            buildJsonObject {
                                put("name", "双色球")
                                put("code", issue)
                                put("date", "2026-01-01(日)")
                                put("detailsLink", "/c/2026/01/01/$issue.shtml")
                                put("red", "01,02,03,04,05,06")
                                put("blue", "01")
                            },
                        )
                    }
                },
            )
        }.toString()

    /** 测试使用的固定北京时间年末时钟。 */
    private object FixedClock : Clock {
        /** 返回测试固定时间。 */
        override fun now(): Instant = Instant.parse("2026-12-31T12:00:00Z")
    }
}

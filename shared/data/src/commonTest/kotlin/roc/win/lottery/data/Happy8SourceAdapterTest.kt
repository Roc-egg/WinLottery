package roc.win.lottery.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import roc.win.lottery.domain.DrawStatus
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryPrizeRules
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.RuleVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** 快乐8 官网适配器与仓库接入测试；响应按福彩接口字段构造，号码与金额为虚构值。 */
class Happy8SourceAdapterTest {
    /** 主响应解析出升序的 20 个号码和全部 39 个奖级，金额完整时可确定奖金。 */
    @Test
    fun parsesMainResponse() {
        val snapshot =
            assertIs<SourceParseResult.Success<MainDrawSnapshot>>(
                Happy8SourceAdapter.parseMain(mainResponse(), ISSUE, SOURCE_URL),
            ).value

        assertEquals(DRAWN_NUMBERS.sorted(), snapshot.primaryNumbers)
        assertTrue(snapshot.secondaryNumbers.isEmpty())
        assertEquals("2026-05-03", snapshot.drawDate)
        assertEquals(LotteryPrizeRules.HAPPY_8_PRIZE_TIER_CODES, snapshot.prizeTiers.map { it.code })
        val x1z1 = snapshot.prizeTiers.single { it.code == "X1Z1" }
        assertEquals("选一中一", x1z1.displayName)
        assertEquals(450L, x1z1.singlePrizeFen)
        assertTrue(snapshot.payoutFieldsComplete)
        assertEquals("https://www.cwl.gov.cn/c/2026/05/03/1.shtml", snapshot.detailUrl)
    }

    /** 奖级缺失或存在未建模的派奖字段时仍给出号码，但奖金待定。 */
    @Test
    fun incompletePayoutKeepsNumbers() {
        val missingTier =
            assertIs<SourceParseResult.Success<MainDrawSnapshot>>(
                Happy8SourceAdapter.parseMain(mainResponse(dropTier = "x5z3"), ISSUE, SOURCE_URL),
            ).value
        assertTrue(missingTier.prizeTiers.isEmpty())
        assertEquals(false, missingTier.payoutFieldsComplete)

        val promotion =
            assertIs<SourceParseResult.Success<MainDrawSnapshot>>(
                Happy8SourceAdapter.parseMain(mainResponse(message = "派奖进行中"), ISSUE, SOURCE_URL),
            ).value
        assertEquals(false, promotion.payoutFieldsComplete)
    }

    /** 号码、玩法名或期号不符时数据源不可用；接口说明没有数据时为未发布。 */
    @Test
    fun rejectsMalformedResponses() {
        assertIs<SourceParseResult.SourceUnavailable>(
            Happy8SourceAdapter.parseMain(mainResponse(red = DRAWN_NUMBERS.drop(1)), ISSUE, SOURCE_URL),
        )
        assertIs<SourceParseResult.SourceUnavailable>(
            Happy8SourceAdapter.parseMain(mainResponse(blue = "05"), ISSUE, SOURCE_URL),
        )
        assertIs<SourceParseResult.SourceUnavailable>(
            Happy8SourceAdapter.parseMain(mainResponse(name = "双色球"), ISSUE, SOURCE_URL),
        )
        assertIs<SourceParseResult.SourceUnavailable>(
            Happy8SourceAdapter.parseMain(mainResponse(), "2026124", SOURCE_URL),
        )
        assertEquals(
            SourceParseResult.NotPublished,
            Happy8SourceAdapter.parseMain("""{"state":1,"message":"没有查到数据","result":[]}""", ISSUE, SOURCE_URL),
        )
    }

    /** 详情响应同样解析号码和奖级，供主数据面交叉核对。 */
    @Test
    fun parsesSupportingResponse() {
        val snapshot =
            assertIs<SourceParseResult.Success<SupportingDrawSnapshot>>(
                Happy8SourceAdapter.parseSupporting(mainResponse(name = "kl8"), ISSUE, SOURCE_URL),
            ).value
        assertEquals(DRAWN_NUMBERS.sorted(), snapshot.primaryNumbers)
        assertEquals(39, snapshot.prizeTiers.size)
    }

    /** 期号区间列表只用于确认多期票实际开奖期号。 */
    @Test
    fun parsesIssueRangePage() {
        val page =
            buildJsonObject {
                put("state", 0)
                put("message", "查询成功")
                put("total", 2)
                put("pageNo", 1)
                put("pageSize", 2)
                put("result", JsonArray(listOf(record(issue = "2026124", date = "2026-05-04"), record())))
            }.toString()

        val parsed = assertIs<HistoricalPageParseResult.Success>(HistoricalDrawSourceAdapter.parseHappy8Page(page, 1))
        assertEquals(listOf("2026124", "2026123"), parsed.drawsNewestFirst.map { it.issue.value })
        assertTrue(
            parsed.drawsNewestFirst.all { it.lotteryType == LotteryType.HAPPY_8 && it.primaryNumbers.size == 20 },
        )
    }

    /** 仓库按玩法名 kl8 请求福彩开奖公告和单期详情，两源一致时可确定奖金。 */
    @Test
    fun repositoryQueriesWelfareLotteryEndpoints() =
        runTest {
            val requests = mutableListOf<String>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        requests += request.url.toString()
                        val body =
                            when {
                                request.url.encodedPath.endsWith("findDrawNotice") -> mainResponse()
                                request.url.encodedPath.endsWith("forIssue") -> mainResponse(name = "kl8")
                                else -> error("收到未预期请求：${request.url}")
                            }
                        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                )
            val repository =
                OfficialDrawRepository(httpClient = client, clock = FixedClock(Instant.parse("2026-05-04T01:00:00Z")))

            val draw =
                assertIs<DrawQueryResult.Success>(repository.getDraw(LotteryType.HAPPY_8, Issue(ISSUE))).drawResult

            assertEquals(DrawStatus.FINAL_PAYOUT, draw.status)
            assertEquals(RuleVersion.KL8_2024_01.code, draw.ruleVersion)
            assertEquals(1, draw.supportingEvidence.size)
            assertEquals(2, requests.size)
            assertTrue(requests.all { "name=kl8" in it })
            assertIs<HistoricalDrawQueryResult.Unavailable>(repository.getLatestDraws(LotteryType.HAPPY_8, 50))
        }

    /** 构造一条快乐8 开奖记录。 */
    private fun record(
        issue: String = ISSUE,
        date: String = "2026-05-03(日)",
        name: String = "快乐8",
        red: List<Int> = DRAWN_NUMBERS,
        blue: String = "",
        message: String = "",
        dropTier: String? = null,
    ): JsonObject =
        buildJsonObject {
            put("name", name)
            put("code", issue)
            put("detailsLink", "/c/2026/05/03/1.shtml")
            put("date", date)
            put("red", red.joinToString(",") { it.toString().padStart(2, '0') })
            put("blue", blue)
            listOf("addmoney", "addmoney2", "z2add", "m2add").forEach { field -> put(field, "") }
            put("msg", message)
            put(
                "prizegrades",
                buildJsonArray {
                    LotteryPrizeRules.HAPPY_8_PRIZE_TIER_CODES
                        .map { it.lowercase() }
                        .filter { it != dropTier }
                        .forEach { type ->
                            add(
                                buildJsonObject {
                                    put("type", type)
                                    put("typenum", "12")
                                    put("typemoney", if (type == "x1z1") "4.5" else "3")
                                },
                            )
                        }
                },
            )
        }

    /** 构造只含目标期的主响应。 */
    private fun mainResponse(
        name: String = "快乐8",
        red: List<Int> = DRAWN_NUMBERS,
        blue: String = "",
        message: String = "",
        dropTier: String? = null,
    ): String =
        buildJsonObject {
            put("state", 0)
            put("message", "查询成功")
            put(
                "result",
                JsonArray(listOf(record(name = name, red = red, blue = blue, message = message, dropTier = dropTier))),
            )
        }.toString()

    /**
     * 固定测试时钟。
     *
     * @property current 当前测试时间。
     */
    private class FixedClock(
        private val current: Instant,
    ) : Clock {
        /** 返回当前测试时间。 */
        override fun now(): Instant = current
    }

    /** 测试固定值。 */
    private companion object {
        /** 虚构期号。 */
        const val ISSUE = "2026123"

        /** 虚构请求地址。 */
        const val SOURCE_URL = "https://www.cwl.gov.cn/cwl_admin/front/cwlkj/search/kjxx/findDrawNotice"

        /** 乱序给出的 20 个虚构开奖号码。 */
        val DRAWN_NUMBERS = listOf(80, 3, 7, 12, 15, 21, 26, 30, 33, 38, 41, 44, 49, 52, 57, 61, 66, 70, 73, 77)
    }
}

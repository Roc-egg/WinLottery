package roc.win.lottery.data

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 官网历史开奖分页适配器测试。 */
class HistoricalDrawSourceAdapterTest {
    /** 大乐透页面必须解析已审核发布的记录并保留最新期在前顺序。 */
    @Test
    fun parsesPublishedSuperLottoPage() {
        val result =
            HistoricalDrawSourceAdapter.parseSuperLottoPage(
                rawJson = superLottoPage(issues = listOf("26002", "26001")),
                expectedPageNo = 1,
            )
        val page = assertIs<HistoricalPageParseResult.Success>(result)

        assertEquals(listOf("26002", "26001"), page.drawsNewestFirst.map { it.issue.value })
        assertEquals(listOf(1, 2, 3, 4, 5), page.drawsNewestFirst.first().primaryNumbers)
        assertEquals(listOf(1, 2), page.drawsNewestFirst.first().secondaryNumbers)
    }

    /** 双色球页面必须解析正式详情公告绑定的红蓝球记录。 */
    @Test
    fun parsesPublishedDoubleColorBallPage() {
        val result =
            HistoricalDrawSourceAdapter.parseDoubleColorBallPage(
                rawJson = doubleColorBallPage(issues = listOf("2026002", "2026001")),
                expectedPageNo = 1,
            )
        val page = assertIs<HistoricalPageParseResult.Success>(result)

        assertEquals(listOf("2026002", "2026001"), page.drawsNewestFirst.map { it.issue.value })
        assertEquals(listOf(1, 2, 3, 4, 5, 6), page.drawsNewestFirst.first().primaryNumbers)
        assertEquals(listOf(1), page.drawsNewestFirst.first().secondaryNumbers)
    }

    /** 页面包含未审核大乐透记录时必须失败关闭。 */
    @Test
    fun rejectsUnverifiedSuperLottoRecord() {
        val result =
            HistoricalDrawSourceAdapter.parseSuperLottoPage(
                rawJson = superLottoPage(issues = listOf("26001"), verify = 0),
                expectedPageNo = 1,
            )

        assertTrue(assertIs<HistoricalPageParseResult.Failure>(result).message.contains("字段不合法"))
    }

    /** 官网页面不按最新期在前排列时不得自行猜测排序。 */
    @Test
    fun rejectsUnexpectedIssueOrder() {
        val result =
            HistoricalDrawSourceAdapter.parseDoubleColorBallPage(
                rawJson = doubleColorBallPage(issues = listOf("2026001", "2026002")),
                expectedPageNo = 1,
            )

        assertTrue(assertIs<HistoricalPageParseResult.Failure>(result).message.contains("排列"))
    }

    /** 构造大乐透历史分页响应。 */
    private fun superLottoPage(
        issues: List<String>,
        verify: Int = 1,
    ): String =
        buildJsonObject {
            put("success", true)
            put("errorCode", "0")
            put(
                "value",
                buildJsonObject {
                    put("pageNo", 1)
                    put("pageSize", issues.size)
                    put("total", issues.size)
                    put(
                        "list",
                        buildJsonArray {
                            issues.forEach { issue ->
                                add(
                                    buildJsonObject {
                                        put("lotteryGameNum", "85")
                                        put("verify", verify)
                                        put("lotteryDrawStatus", 20)
                                        put("lotteryNotice", 1)
                                        put("lotteryDrawNum", issue)
                                        put("lotteryDrawTime", "20${issue.take(2)}-01-01")
                                        put("lotteryDrawResult", "01 02 03 04 05 01 02")
                                    },
                                )
                            }
                        },
                    )
                },
            )
        }.toString()

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
                                put("date", "${issue.take(4)}-01-01(日)")
                                put("detailsLink", "/c/${issue.take(4)}/01/01/$issue.shtml")
                                put("red", "01,02,03,04,05,06")
                                put("blue", "01")
                            },
                        )
                    }
                },
            )
        }.toString()
}

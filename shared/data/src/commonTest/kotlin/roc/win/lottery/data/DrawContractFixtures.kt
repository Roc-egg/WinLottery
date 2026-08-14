package roc.win.lottery.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 人工最小化的官网同结构测试夹具，不包含任何保存的原始响应。 */
internal object DrawContractFixtures {
    /**
     * 构造大乐透精确单期主响应。
     *
     * 参数只用于覆盖契约异常和状态边界，不用于模拟未实现的接口字段。
     */
    fun superLottoMain(
        issue: String = DEFAULT_DLT_ISSUE,
        numbers: String = DEFAULT_DLT_NUMBERS,
        drawDate: String = DEFAULT_DLT_DATE,
        includeRecord: Boolean = true,
        duplicateRecord: Boolean = false,
        businessSuccess: Boolean = true,
        verify: Int = 1,
        drawStatus: Int = 20,
        notice: Int = 1,
        promotionFlag: Int = 0,
        detailUrl: String = defaultDltDetailUrl(issue),
        firstAdditionalCount: String = "0",
        firstAdditionalAmount: String = "---",
        thirdPrizeAmount: String = "6666",
        extraPrizeName: String? = null,
    ): String =
        buildJsonObject {
            put("success", businessSuccess)
            put("errorCode", if (businessSuccess) "0" else "500")
            put(
                "value",
                buildJsonObject {
                    put(
                        "list",
                        buildJsonArray {
                            if (includeRecord) {
                                add(
                                    superLottoRecord(
                                        issue = issue,
                                        numbers = numbers,
                                        drawDate = drawDate,
                                        verify = verify,
                                        drawStatus = drawStatus,
                                        notice = notice,
                                        promotionFlag = promotionFlag,
                                        detailUrl = detailUrl,
                                        firstAdditionalCount = firstAdditionalCount,
                                        firstAdditionalAmount = firstAdditionalAmount,
                                        thirdPrizeAmount = thirdPrizeAmount,
                                        extraPrizeName = extraPrizeName,
                                    ),
                                )
                                if (duplicateRecord) {
                                    add(
                                        superLottoRecord(
                                            issue = issue,
                                            numbers = numbers,
                                            drawDate = drawDate,
                                            verify = verify,
                                            drawStatus = drawStatus,
                                            notice = notice,
                                            promotionFlag = promotionFlag,
                                            detailUrl = detailUrl,
                                            firstAdditionalCount = firstAdditionalCount,
                                            firstAdditionalAmount = firstAdditionalAmount,
                                            thirdPrizeAmount = thirdPrizeAmount,
                                            extraPrizeName = extraPrizeName,
                                        ),
                                    )
                                }
                            }
                        },
                    )
                },
            )
        }.toString()

    /** 构造大乐透最新开奖辅助响应。 */
    fun superLottoSupporting(
        issue: String = DEFAULT_DLT_ISSUE,
        numbers: String = DEFAULT_DLT_NUMBERS,
        drawDate: String = DEFAULT_DLT_DATE,
        verify: Int = 1,
        notice: Int = 1,
        detailUrl: String = defaultDltDetailUrl(issue),
        firstAdditionalCount: String = "0",
        firstAdditionalAmount: String = "0",
        thirdPrizeAmount: String = "6666",
        extraPrizeName: String? = null,
        businessSuccess: Boolean = true,
        includeDraw: Boolean = true,
    ): String =
        buildJsonObject {
            put("success", businessSuccess)
            put("errorCode", if (businessSuccess) "0" else "500")
            put(
                "value",
                buildJsonObject {
                    put(
                        "dlt",
                        if (includeDraw) {
                            buildJsonObject {
                                put(
                                    "lastPoolDraw",
                                    superLottoRecord(
                                        issue = issue,
                                        numbers = numbers,
                                        drawDate = "$drawDate 21:18:19",
                                        verify = verify,
                                        drawStatus = 20,
                                        notice = notice,
                                        promotionFlag = 0,
                                        detailUrl = detailUrl,
                                        firstAdditionalCount = firstAdditionalCount,
                                        firstAdditionalAmount = firstAdditionalAmount,
                                        thirdPrizeAmount = thirdPrizeAmount,
                                        extraPrizeName = extraPrizeName,
                                    ),
                                )
                            }
                        } else {
                            buildJsonObject {}
                        },
                    )
                },
            )
        }.toString()

    /** 构造双色球精确单期列表主响应。 */
    fun doubleColorBallMain(
        issue: String = DEFAULT_SSQ_ISSUE,
        red: String = DEFAULT_SSQ_RED,
        blue: String = DEFAULT_SSQ_BLUE,
        drawDate: String = DEFAULT_SSQ_DATE,
        state: Int = 0,
        message: String = "查询成功",
        includeRecord: Boolean = true,
        duplicateRecord: Boolean = false,
        detailsLink: String = defaultSsqDetailLink(issue),
        fortuneCount: String = "",
        fortuneMoney: String = "",
        specialRuleInfo: String = "",
        firstPrizeAmount: String = "10000000",
        thirdPrizeAmount: String = "3000",
        extraPrizeType: Int? = null,
    ): String =
        buildJsonObject {
            put("state", state)
            put("message", message)
            put(
                "result",
                buildJsonArray {
                    if (includeRecord) {
                        add(
                            doubleColorBallRecord(
                                name = "双色球",
                                issue = issue,
                                red = red,
                                blue = blue,
                                drawDate = "$drawDate(日)",
                                detailsLink = detailsLink,
                                fortuneCount = fortuneCount,
                                fortuneMoney = fortuneMoney,
                                specialRuleInfo = specialRuleInfo,
                                firstPrizeAmount = firstPrizeAmount,
                                thirdPrizeAmount = thirdPrizeAmount,
                                extraPrizeType = extraPrizeType,
                            ),
                        )
                        if (duplicateRecord) {
                            add(
                                doubleColorBallRecord(
                                    name = "双色球",
                                    issue = issue,
                                    red = red,
                                    blue = blue,
                                    drawDate = "$drawDate(日)",
                                    detailsLink = detailsLink,
                                    fortuneCount = fortuneCount,
                                    fortuneMoney = fortuneMoney,
                                    specialRuleInfo = specialRuleInfo,
                                    firstPrizeAmount = firstPrizeAmount,
                                    thirdPrizeAmount = thirdPrizeAmount,
                                    extraPrizeType = extraPrizeType,
                                ),
                            )
                        }
                    }
                },
            )
        }.toString()

    /** 构造双色球详情辅助响应。 */
    fun doubleColorBallSupporting(
        issue: String = DEFAULT_SSQ_ISSUE,
        red: String = DEFAULT_SSQ_RED,
        blue: String = DEFAULT_SSQ_BLUE,
        drawDate: String = DEFAULT_SSQ_DATE,
        state: Int = 0,
        message: String = "查询成功",
        includeRecord: Boolean = true,
        duplicateRecord: Boolean = false,
        detailsLink: String = defaultSsqDetailLink(issue),
        firstPrizeAmount: String = "10000000",
        thirdPrizeAmount: String = "3000",
        extraPrizeType: Int? = null,
    ): String =
        buildJsonObject {
            put("state", state)
            put("message", message)
            put(
                "result",
                buildJsonArray {
                    if (includeRecord) {
                        add(
                            doubleColorBallRecord(
                                name = "ssq",
                                issue = issue,
                                red = red,
                                blue = blue,
                                drawDate = drawDate,
                                detailsLink = detailsLink,
                                fortuneCount = "",
                                fortuneMoney = "",
                                specialRuleInfo = "",
                                firstPrizeAmount = firstPrizeAmount,
                                thirdPrizeAmount = thirdPrizeAmount,
                                extraPrizeType = extraPrizeType,
                            ),
                        )
                        if (duplicateRecord) {
                            add(
                                doubleColorBallRecord(
                                    name = "ssq",
                                    issue = issue,
                                    red = red,
                                    blue = blue,
                                    drawDate = drawDate,
                                    detailsLink = detailsLink,
                                    fortuneCount = "",
                                    fortuneMoney = "",
                                    specialRuleInfo = "",
                                    firstPrizeAmount = firstPrizeAmount,
                                    thirdPrizeAmount = thirdPrizeAmount,
                                    extraPrizeType = extraPrizeType,
                                ),
                            )
                        }
                    }
                },
            )
        }.toString()

    /** 构造大乐透一条开奖记录。 */
    private fun superLottoRecord(
        issue: String,
        numbers: String,
        drawDate: String,
        verify: Int,
        drawStatus: Int,
        notice: Int,
        promotionFlag: Int,
        detailUrl: String,
        firstAdditionalCount: String,
        firstAdditionalAmount: String,
        thirdPrizeAmount: String,
        extraPrizeName: String?,
    ): JsonObject =
        buildJsonObject {
            put("lotteryGameNum", "85")
            put("lotteryDrawNum", issue)
            put("lotteryDrawResult", numbers)
            put("lotteryDrawTime", drawDate)
            put("verify", verify)
            put("lotteryDrawStatus", drawStatus)
            put("lotteryNotice", notice)
            put("lotteryPromotionFlag", promotionFlag)
            put("drawPdfUrl", detailUrl)
            put(
                "prizeLevelList",
                superLottoPrizeRows(
                    firstAdditionalCount = firstAdditionalCount,
                    firstAdditionalAmount = firstAdditionalAmount,
                    thirdPrizeAmount = thirdPrizeAmount,
                    extraPrizeName = extraPrizeName,
                ),
            )
        }

    /** 构造大乐透必要奖级行。 */
    private fun superLottoPrizeRows(
        firstAdditionalCount: String,
        firstAdditionalAmount: String,
        thirdPrizeAmount: String,
        extraPrizeName: String?,
    ): JsonArray =
        buildJsonArray {
            add(superLottoPrizeRow("一等奖", "3", "10,000,000"))
            add(superLottoPrizeRow("一等奖(追加)", firstAdditionalCount, firstAdditionalAmount))
            add(superLottoPrizeRow("二等奖", "65", "343,183"))
            add(superLottoPrizeRow("二等奖(追加)", "15", "274,546"))
            add(superLottoPrizeRow("三等奖", "780", thirdPrizeAmount))
            add(superLottoPrizeRow("四等奖", "13,907", "380"))
            add(superLottoPrizeRow("五等奖", "57,982", "200"))
            add(superLottoPrizeRow("六等奖", "648,165", "18"))
            add(superLottoPrizeRow("七等奖", "7,001,956", "7"))
            extraPrizeName?.let { add(superLottoPrizeRow(it, "1", "10")) }
        }

    /** 构造大乐透单条奖级字段。 */
    private fun superLottoPrizeRow(
        name: String,
        count: String,
        amount: String,
    ): JsonObject =
        buildJsonObject {
            put("prizeLevel", name)
            put("stakeCount", count)
            put("stakeAmount", amount)
        }

    /** 构造双色球一条开奖记录。 */
    private fun doubleColorBallRecord(
        name: String,
        issue: String,
        red: String,
        blue: String,
        drawDate: String,
        detailsLink: String,
        fortuneCount: String,
        fortuneMoney: String,
        specialRuleInfo: String,
        firstPrizeAmount: String,
        thirdPrizeAmount: String,
        extraPrizeType: Int?,
    ): JsonObject =
        buildJsonObject {
            put("name", name)
            put("code", issue)
            put("date", drawDate)
            put("red", red)
            put("blue", blue)
            put("detailsLink", detailsLink)
            put("fyjCount", fortuneCount)
            put("fyjMoney", fortuneMoney)
            put("specialRuleInfo", specialRuleInfo)
            put("prizegrades", doubleColorBallPrizeRows(firstPrizeAmount, thirdPrizeAmount, extraPrizeType))
        }

    /** 构造双色球基础奖级与空第七类型。 */
    private fun doubleColorBallPrizeRows(
        firstPrizeAmount: String,
        thirdPrizeAmount: String,
        extraPrizeType: Int?,
    ): JsonArray =
        buildJsonArray {
            add(doubleColorBallPrizeRow(1, "4", firstPrizeAmount))
            add(doubleColorBallPrizeRow(2, "79", "328709"))
            add(doubleColorBallPrizeRow(3, "952", thirdPrizeAmount))
            add(doubleColorBallPrizeRow(4, "54095", "200"))
            add(doubleColorBallPrizeRow(5, "1136984", "10"))
            add(doubleColorBallPrizeRow(6, "10218077", "5"))
            add(doubleColorBallPrizeRow(7, "", ""))
            extraPrizeType?.let { add(doubleColorBallPrizeRow(it, "1", "10")) }
        }

    /** 构造双色球单条奖级字段，类型使用数字以覆盖官网类型漂移。 */
    private fun doubleColorBallPrizeRow(
        type: Int,
        count: String,
        amount: String,
    ): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive(type))
            put("typenum", count)
            put("typemoney", amount)
        }

    /** 返回大乐透官方公告夹具地址。 */
    private fun defaultDltDetailUrl(issue: String): String = "https://pdf.sporttery.cn/33800/$issue/$issue.pdf"

    /** 返回双色球官方详情夹具相对地址。 */
    private fun defaultSsqDetailLink(issue: String): String = "/c/2026/08/09/$issue.shtml"

    /** 大乐透默认期号。 */
    const val DEFAULT_DLT_ISSUE = "26091"

    /** 大乐透默认开奖号码。 */
    const val DEFAULT_DLT_NUMBERS = "03 04 07 12 32 01 02"

    /** 大乐透默认开奖日期。 */
    const val DEFAULT_DLT_DATE = "2026-08-12"

    /** 双色球默认期号。 */
    const val DEFAULT_SSQ_ISSUE = "2026091"

    /** 双色球默认红球。 */
    const val DEFAULT_SSQ_RED = "02,13,14,16,20,24"

    /** 双色球默认蓝球。 */
    const val DEFAULT_SSQ_BLUE = "05"

    /** 双色球默认开奖日期。 */
    const val DEFAULT_SSQ_DATE = "2026-08-09"
}

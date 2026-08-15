package roc.win.lottery.data

import kotlinx.serialization.json.JsonObject
import roc.win.lottery.domain.DrawPolicy
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.PrizeTier
import roc.win.lottery.domain.PrizeTierCodes

/** 中国福利彩票双色球官网 JSON 适配器。 */
internal object DoubleColorBallSourceAdapter {
    /**
     * 解析精确单期列表主响应。
     *
     * 主响应是双色球特别规定和福运奖字段的唯一 JSON 数据面，不能被详情响应替代。
     */
    fun parseMain(
        rawJson: String,
        targetIssue: String,
        sourceUrl: String,
    ): SourceParseResult<MainDrawSnapshot> =
        parseSafely {
            val root =
                DrawJson.parseToJsonElement(rawJson).objectOrNull()
                    ?: return@parseSafely SourceParseResult.SourceUnavailable("双色球主响应不是 JSON 对象")
            val state =
                root.int("state")
                    ?: return@parseSafely SourceParseResult.SourceUnavailable("双色球主响应缺少业务状态")
            val message = root.text("message").orEmpty()
            if (state != SUCCESS_STATE) {
                return@parseSafely if (message.contains(NO_DATA_MESSAGE)) {
                    SourceParseResult.NotPublished
                } else {
                    SourceParseResult.SourceUnavailable("双色球官网返回业务失败")
                }
            }
            val recordElements =
                root["result"].arrayOrNull()
                    ?: return@parseSafely SourceParseResult.SourceUnavailable("双色球主响应缺少开奖记录数组")
            if (recordElements.any { it.objectOrNull() == null }) {
                return@parseSafely SourceParseResult.SourceUnavailable("双色球开奖记录类型发生变化")
            }
            val records = recordElements.map { requireNotNull(it.objectOrNull()) }
            if (records.isEmpty()) return@parseSafely SourceParseResult.NotPublished
            val matches = records.filter { it.text("code") == targetIssue }
            if (matches.size > 1) {
                return@parseSafely SourceParseResult.Conflict("双色球主响应包含重复目标期号")
            }
            val record =
                matches.singleOrNull()
                    ?: return@parseSafely SourceParseResult.SourceUnavailable("双色球主响应未返回目标期号")
            if (records.size != 1) {
                return@parseSafely SourceParseResult.SourceUnavailable("双色球主响应返回了目标期号之外的记录")
            }
            parseMainRecord(record, targetIssue, sourceUrl)
        }

    /** 解析仅用于号码和基础奖级交叉核对的详情响应。 */
    fun parseSupporting(
        rawJson: String,
        targetIssue: String,
        sourceUrl: String,
    ): SourceParseResult<SupportingDrawSnapshot> =
        parseSafely {
            val root =
                DrawJson.parseToJsonElement(rawJson).objectOrNull()
                    ?: return@parseSafely SourceParseResult.SourceUnavailable("双色球详情响应不是 JSON 对象")
            val state =
                root.int("state")
                    ?: return@parseSafely SourceParseResult.SourceUnavailable("双色球详情响应缺少业务状态")
            val message = root.text("message").orEmpty()
            if (state != SUCCESS_STATE) {
                return@parseSafely if (message.contains(NO_DATA_MESSAGE)) {
                    SourceParseResult.NotPublished
                } else {
                    SourceParseResult.SourceUnavailable("双色球详情接口返回业务失败")
                }
            }
            val recordElements =
                root["result"].arrayOrNull()
                    ?: return@parseSafely SourceParseResult.SourceUnavailable("双色球详情响应缺少开奖记录数组")
            if (recordElements.any { it.objectOrNull() == null }) {
                return@parseSafely SourceParseResult.SourceUnavailable("双色球详情记录类型发生变化")
            }
            val records = recordElements.map { requireNotNull(it.objectOrNull()) }
            if (records.isEmpty()) return@parseSafely SourceParseResult.NotPublished
            val matches = records.filter { it.text("code") == targetIssue }
            if (matches.size > 1) {
                return@parseSafely SourceParseResult.Conflict("双色球详情响应包含重复目标期号")
            }
            val record =
                matches.singleOrNull()
                    ?: return@parseSafely SourceParseResult.SourceUnavailable("双色球详情响应未返回目标期号")
            if (records.size != 1) {
                return@parseSafely SourceParseResult.SourceUnavailable("双色球详情响应返回了目标期号之外的记录")
            }
            parseSupportingRecord(record, targetIssue, sourceUrl)
        }

    /** 解析并严格校验主响应中的一条记录。 */
    private fun parseMainRecord(
        record: JsonObject,
        targetIssue: String,
        sourceUrl: String,
    ): SourceParseResult<MainDrawSnapshot> {
        val name = record.text("name")?.lowercase()
        if (name !in DOUBLE_COLOR_BALL_MAIN_NAMES) {
            return SourceParseResult.SourceUnavailable("双色球主响应玩法标识异常")
        }
        val numbers =
            parseDoubleColorBallNumbers(record)
                ?: return SourceParseResult.SourceUnavailable("双色球开奖号码不合法")
        val drawDate =
            normalizeDrawDate(record.text("date"))
                ?: return SourceParseResult.SourceUnavailable("双色球开奖日期不合法")
        if (drawDate.take(4) != targetIssue.take(4)) {
            return SourceParseResult.SourceUnavailable("双色球开奖日期与期号年份不一致")
        }
        val detailUrl =
            normalizeDetailUrl(record.text("detailsLink"))
                ?: return SourceParseResult.Publishing("双色球详情公告链接尚未发布")
        val basicTiers =
            when (val parsed = parseBasicPrizeTiers(record)) {
                is PrizeTierParseResult.Success -> parsed.tiers
                is PrizeTierParseResult.Failure -> return SourceParseResult.SourceUnavailable(parsed.message)
            }
        if (PROMOTION_FIELDS.any { !record.text(it).isNullOrEmpty() }) {
            return SourceParseResult.Publishing("双色球当期存在 V1 尚未验证的派奖活动")
        }
        val policyResult = parsePolicy(record, targetIssue)
        if (policyResult is PolicyParseResult.Unknown) {
            return SourceParseResult.Publishing(policyResult.message)
        }
        val policy = (policyResult as PolicyParseResult.Known).policy
        val tiers = basicTiers + policyResult.additionalTiers
        val payoutFieldsComplete = tiers.all { it.singlePrizeFen != null }
        val canonical =
            canonicalMainDraw(
                lotteryType = LotteryType.DOUBLE_COLOR_BALL,
                issue = targetIssue,
                drawDate = drawDate,
                primaryNumbers = numbers.first,
                secondaryNumbers = numbers.second,
                policy = policy,
                prizeTiers = tiers,
                publicationFieldsComplete = true,
                detailUrl = detailUrl,
            )
        return SourceParseResult.Success(
            MainDrawSnapshot(
                lotteryType = LotteryType.DOUBLE_COLOR_BALL,
                issue = targetIssue,
                drawDate = drawDate,
                primaryNumbers = numbers.first,
                secondaryNumbers = numbers.second,
                policy = policy,
                prizeTiers = tiers,
                publicationFieldsComplete = true,
                payoutFieldsComplete = payoutFieldsComplete,
                evidence = EvidenceDraft("中国福彩网开奖公告", sourceUrl, canonical),
                detailUrl = detailUrl,
            ),
        )
    }

    /** 解析并严格校验详情响应中的一条记录。 */
    private fun parseSupportingRecord(
        record: JsonObject,
        targetIssue: String,
        sourceUrl: String,
    ): SourceParseResult<SupportingDrawSnapshot> {
        val name = record.text("name")?.lowercase()
        if (name != DOUBLE_COLOR_BALL_NAME) {
            return SourceParseResult.SourceUnavailable("双色球详情响应玩法标识异常")
        }
        val numbers =
            parseDoubleColorBallNumbers(record)
                ?: return SourceParseResult.SourceUnavailable("双色球详情开奖号码不合法")
        val drawDate =
            normalizeDrawDate(record.text("date"))
                ?: return SourceParseResult.SourceUnavailable("双色球详情开奖日期不合法")
        if (drawDate.take(4) != targetIssue.take(4)) {
            return SourceParseResult.SourceUnavailable("双色球详情日期与期号年份不一致")
        }
        val tiers =
            when (val parsed = parseBasicPrizeTiers(record)) {
                is PrizeTierParseResult.Success -> parsed.tiers
                is PrizeTierParseResult.Failure -> return SourceParseResult.SourceUnavailable(parsed.message)
            }
        val detailUrl =
            normalizeDetailUrl(record.text("detailsLink"))
                ?: return SourceParseResult.Publishing("双色球详情公告链接尚未发布")
        val canonical =
            canonicalSupportingDraw(
                issue = targetIssue,
                drawDate = drawDate,
                primaryNumbers = numbers.first,
                secondaryNumbers = numbers.second,
                prizeTiers = tiers,
                detailUrl = detailUrl,
            )
        return SourceParseResult.Success(
            SupportingDrawSnapshot(
                issue = targetIssue,
                drawDate = drawDate,
                primaryNumbers = numbers.first,
                secondaryNumbers = numbers.second,
                prizeTiers = tiers,
                detailUrl = detailUrl,
                evidence = EvidenceDraft("中国福彩网开奖详情", sourceUrl, canonical),
            ),
        )
    }

    /** 解析双色球红球和蓝球号码。 */
    private fun parseDoubleColorBallNumbers(record: JsonObject): Pair<List<Int>, List<Int>>? {
        val red = parseNumberArea(record.text("red"), RED_NUMBER_COUNT, 1..RED_MAX_NUMBER) ?: return null
        val blue = parseNumberArea(record.text("blue"), BLUE_NUMBER_COUNT, 1..BLUE_MAX_NUMBER) ?: return null
        return red to blue
    }

    /** 解析一至六等奖并验证固定奖金额。 */
    private fun parseBasicPrizeTiers(record: JsonObject): PrizeTierParseResult {
        val rowElements =
            record["prizegrades"].arrayOrNull()
                ?: return PrizeTierParseResult.Failure("双色球奖级列表字段缺失")
        if (rowElements.any { it.objectOrNull() == null }) {
            return PrizeTierParseResult.Failure("双色球奖级列表类型发生变化")
        }
        val rows = rowElements.map { requireNotNull(it.objectOrNull()) }
        if (rows.isEmpty()) return PrizeTierParseResult.Failure("双色球奖级列表为空")
        val tiers = mutableMapOf<String, PrizeTier>()
        for (row in rows) {
            val type = row.int("type") ?: return PrizeTierParseResult.Failure("双色球奖级类型不合法")
            if (type == UNUSED_FORTUNE_TYPE && row.text("typenum").isNullOrEmpty() &&
                row.text("typemoney").isNullOrEmpty()
            ) {
                continue
            }
            val code =
                DOUBLE_COLOR_BALL_TIER_TYPES[type]
                    ?: return PrizeTierParseResult.Failure("双色球出现未知奖级或活动字段")
            val countRaw = row.text("typenum") ?: return PrizeTierParseResult.Failure("双色球奖级注数缺失")
            val winnerCount =
                parseCount(countRaw)
                    ?: return PrizeTierParseResult.Failure("双色球奖级注数无法解析")
            if (winnerCount < 0L) return PrizeTierParseResult.Failure("双色球奖级注数不能为负数")
            val prizeRaw = row.text("typemoney")
            val prizeFen = parseYuanToFen(prizeRaw)
            val expectedFixedPrizeFen = DOUBLE_COLOR_BALL_FIXED_PRIZES[code]
            if (expectedFixedPrizeFen != null && prizeFen != expectedFixedPrizeFen) {
                return PrizeTierParseResult.Failure("双色球固定奖金额与现行规则不一致")
            }
            if (
                tiers.put(
                    code,
                    PrizeTier(
                        code = code,
                        displayName = DOUBLE_COLOR_BALL_TIER_NAMES.getValue(code),
                        singlePrizeFen = prizeFen,
                        additionalPrizeFen = null,
                        winnerCount = winnerCount,
                        singlePrizeRaw = prizeRaw,
                    ),
                ) != null
            ) {
                return PrizeTierParseResult.Failure("双色球奖级列表包含重复奖级")
            }
        }
        if (tiers.keys != REQUIRED_BASIC_TIER_CODES) {
            return PrizeTierParseResult.Failure("双色球基础奖级列表不完整")
        }
        return PrizeTierParseResult.Success(REQUIRED_BASIC_TIER_CODES.map(tiers::getValue))
    }

    /** 按已核验期号边界选择特别规定状态。 */
    private fun parsePolicy(
        record: JsonObject,
        issue: String,
    ): PolicyParseResult {
        val fortuneCountRaw = record.text("fyjCount")
        val fortuneMoneyRaw = record.text("fyjMoney")
        return when {
            issue in FORTUNE_FIRST_ISSUE..FORTUNE_LAST_ISSUE -> {
                val count =
                    parseCount(fortuneCountRaw)
                        ?: return PolicyParseResult.Unknown("双色球福运奖注数尚未发布或无法解析")
                val amountFen =
                    parseYuanToFen(fortuneMoneyRaw)
                        ?: return PolicyParseResult.Unknown("双色球福运奖金额尚未发布或无法解析")
                if (count < 0L) return PolicyParseResult.Unknown("双色球福运奖注数不合法")
                PolicyParseResult.Known(
                    policy = DrawPolicy.DOUBLE_COLOR_BALL_FORTUNE,
                    additionalTiers =
                        listOf(
                            PrizeTier(
                                code = PrizeTierCodes.FORTUNE,
                                displayName = "福运奖",
                                singlePrizeFen = amountFen,
                                additionalPrizeFen = null,
                                winnerCount = count,
                                singlePrizeRaw = fortuneMoneyRaw,
                            ),
                        ),
                )
            }

            issue in FIRST_CONFIRMED_STANDARD_ISSUE..LAST_CONFIRMED_STANDARD_ISSUE -> {
                if (
                    !fortuneCountRaw.isNullOrEmpty() ||
                    !fortuneMoneyRaw.isNullOrEmpty() ||
                    !record.text("specialRuleInfo").isNullOrEmpty()
                ) {
                    PolicyParseResult.Unknown("双色球普通期次出现未识别的福运奖字段")
                } else {
                    PolicyParseResult.Known(DrawPolicy.STANDARD, emptyList())
                }
            }

            else -> {
                PolicyParseResult.Unknown("双色球当期特别规定状态尚无固化官方证据")
            }
        }
    }

    /** 将福彩相对详情链接归一化为官网绝对地址。 */
    private fun normalizeDetailUrl(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            value.startsWith("https://www.cwl.gov.cn/") -> value
            value.startsWith("/") -> "$CHINA_WELFARE_LOTTERY_BASE_URL$value"
            else -> null
        }
    }

    /** 将 JSON 解析异常收敛为数据源不可用，不泄漏原始响应。 */
    private inline fun <T> parseSafely(block: () -> SourceParseResult<T>): SourceParseResult<T> =
        try {
            block()
        } catch (_: IllegalArgumentException) {
            SourceParseResult.SourceUnavailable("双色球官网响应无法解析")
        }

    /** 基础奖级列表解析状态。 */
    private sealed interface PrizeTierParseResult {
        /** 基础奖级可用。 */
        data class Success(
            /** 按一至六等奖顺序排列的奖级。 */
            val tiers: List<PrizeTier>,
        ) : PrizeTierParseResult

        /** 奖级列表不满足当前契约。 */
        data class Failure(
            /** 不包含原始响应的错误说明。 */
            val message: String,
        ) : PrizeTierParseResult
    }

    /** 当期特别规定解析状态。 */
    private sealed interface PolicyParseResult {
        /** 政策状态已有固化证据。 */
        data class Known(
            /** 影响命中矩阵的政策。 */
            val policy: DrawPolicy,
            /** 政策额外引入的奖级。 */
            val additionalTiers: List<PrizeTier>,
        ) : PolicyParseResult

        /** 政策状态或所需字段尚不能确定。 */
        data class Unknown(
            /** 面向用户的保守说明。 */
            val message: String,
        ) : PolicyParseResult
    }

    /** 福彩接口成功状态值。 */
    private const val SUCCESS_STATE = 0

    /** 福彩接口无记录提示的稳定片段。 */
    private const val NO_DATA_MESSAGE = "没有查到数据"

    /** 详情接口中的双色球玩法名。 */
    private const val DOUBLE_COLOR_BALL_NAME = "ssq"

    /** 主列表可能返回的双色球玩法名称。 */
    private val DOUBLE_COLOR_BALL_MAIN_NAMES = setOf("双色球", DOUBLE_COLOR_BALL_NAME)

    /** 非空即代表存在尚未建模活动金额的字段。 */
    private val PROMOTION_FIELDS = setOf("addmoney", "addmoney2", "z2add", "m2add", "msg")

    /** 福彩网绝对地址前缀。 */
    private const val CHINA_WELFARE_LOTTERY_BASE_URL = "https://www.cwl.gov.cn"

    /** 双色球红球数量。 */
    private const val RED_NUMBER_COUNT = 6

    /** 双色球蓝球数量。 */
    private const val BLUE_NUMBER_COUNT = 1

    /** 双色球红球最大号码。 */
    private const val RED_MAX_NUMBER = 33

    /** 双色球蓝球最大号码。 */
    private const val BLUE_MAX_NUMBER = 16

    /** 奖级列表中不承载福运奖字段的空第七类型。 */
    private const val UNUSED_FORTUNE_TYPE = 7

    /** 已确认特别规定首期。 */
    private const val FORTUNE_FIRST_ISSUE = "2026014"

    /** 已确认特别规定末期。 */
    private const val FORTUNE_LAST_ISSUE = "2026075"

    /** 本轮特别规定退出后的首个普通期号。 */
    private const val FIRST_CONFIRMED_STANDARD_ISSUE = "2026076"

    /** 截至核查日已由前期奖池、主详情接口和规则阈值共同确认的普通末期。 */
    private const val LAST_CONFIRMED_STANDARD_ISSUE = "2026093"

    /** 福彩奖级类型到领域编码的映射。 */
    private val DOUBLE_COLOR_BALL_TIER_TYPES =
        mapOf(
            1 to PrizeTierCodes.FIRST,
            2 to PrizeTierCodes.SECOND,
            3 to PrizeTierCodes.THIRD,
            4 to PrizeTierCodes.FOURTH,
            5 to PrizeTierCodes.FIFTH,
            6 to PrizeTierCodes.SIXTH,
        )

    /** 双色球基础奖级的稳定顺序。 */
    private val REQUIRED_BASIC_TIER_CODES =
        linkedSetOf(
            PrizeTierCodes.FIRST,
            PrizeTierCodes.SECOND,
            PrizeTierCodes.THIRD,
            PrizeTierCodes.FOURTH,
            PrizeTierCodes.FIFTH,
            PrizeTierCodes.SIXTH,
        )

    /** 双色球奖级展示名称。 */
    private val DOUBLE_COLOR_BALL_TIER_NAMES =
        mapOf(
            PrizeTierCodes.FIRST to "一等奖",
            PrizeTierCodes.SECOND to "二等奖",
            PrizeTierCodes.THIRD to "三等奖",
            PrizeTierCodes.FOURTH to "四等奖",
            PrizeTierCodes.FIFTH to "五等奖",
            PrizeTierCodes.SIXTH to "六等奖",
        )

    /** 双色球三至六等奖的固定金额，单位为分。 */
    private val DOUBLE_COLOR_BALL_FIXED_PRIZES =
        mapOf(
            PrizeTierCodes.THIRD to 300_000L,
            PrizeTierCodes.FOURTH to 20_000L,
            PrizeTierCodes.FIFTH to 1_000L,
            PrizeTierCodes.SIXTH to 500L,
        )
}

package roc.win.lottery.data

import kotlinx.serialization.json.JsonObject
import roc.win.lottery.domain.DrawPolicy
import roc.win.lottery.domain.LotteryPrizeRules
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.PrizeTier
import roc.win.lottery.domain.PrizeTierCodes

/**
 * 中国福利彩票快乐8 官网 JSON 适配器。
 *
 * 与双色球共用福彩网开奖公告列表（主数据面）和单期详情（辅助数据面）两个接口。每期开出 20 个号码；
 * 奖级列表以 `x{选几}z{中几}` 标识全部 39 个奖级，金额按当期官网公布值使用，规则调整或派奖期间无需改代码。
 */
internal object Happy8SourceAdapter {
    /** 解析精确单期列表主响应。 */
    fun parseMain(
        rawJson: String,
        targetIssue: String,
        sourceUrl: String,
    ): SourceParseResult<MainDrawSnapshot> =
        parseSafely {
            val record =
                when (val selected = selectRecord(rawJson, targetIssue, "主响应")) {
                    is RecordSelection.Found -> selected.record
                    is RecordSelection.Failed -> return@parseSafely selected.result()
                }
            if (record.text("name")?.lowercase() !in MAIN_NAMES) {
                return@parseSafely SourceParseResult.SourceUnavailable("快乐8主响应玩法标识异常")
            }
            val numbers =
                parseNumbers(record) ?: return@parseSafely SourceParseResult.SourceUnavailable("快乐8开奖号码不合法")
            val drawDate =
                normalizeDrawDate(record.text("date"))
                    ?: return@parseSafely SourceParseResult.SourceUnavailable("快乐8开奖日期不合法")
            if (drawDate.take(YEAR_LENGTH) != targetIssue.take(YEAR_LENGTH)) {
                return@parseSafely SourceParseResult.SourceUnavailable("快乐8开奖日期与期号年份不一致")
            }
            val detailUrl = normalizeDetailUrl(record.text("detailsLink"))
            val tiers = (parsePrizeTiers(record) as? PrizeTierParseResult.Success)?.tiers
            val promotionActive = PROMOTION_FIELDS.any { !record.text(it).isNullOrEmpty() }
            val payoutFieldsComplete =
                tiers != null &&
                    PROMOTION_FIELDS.all { record.text(it) != null } &&
                    !promotionActive &&
                    tiers.all { it.singlePrizeFen != null || it.winnerCount == 0L }
            val canonical =
                canonicalMainDraw(
                    lotteryType = LotteryType.HAPPY_8,
                    issue = targetIssue,
                    drawDate = drawDate,
                    primaryNumbers = numbers,
                    secondaryNumbers = emptyList(),
                    policy = DrawPolicy.STANDARD,
                    prizeTiers = tiers.orEmpty(),
                    detailUrl = detailUrl,
                )
            SourceParseResult.Success(
                MainDrawSnapshot(
                    lotteryType = LotteryType.HAPPY_8,
                    issue = targetIssue,
                    drawDate = drawDate,
                    primaryNumbers = numbers,
                    secondaryNumbers = emptyList(),
                    policy = DrawPolicy.STANDARD,
                    prizeTiers = tiers.orEmpty(),
                    payoutFieldsComplete = payoutFieldsComplete,
                    evidence = EvidenceDraft("中国福彩网开奖公告", sourceUrl, canonical),
                    detailUrl = detailUrl,
                ),
            )
        }

    /** 解析仅用于号码和奖级交叉核对的详情响应。 */
    fun parseSupporting(
        rawJson: String,
        targetIssue: String,
        sourceUrl: String,
    ): SourceParseResult<SupportingDrawSnapshot> =
        parseSafely {
            val record =
                when (val selected = selectRecord(rawJson, targetIssue, "详情响应")) {
                    is RecordSelection.Found -> selected.record
                    is RecordSelection.Failed -> return@parseSafely selected.result()
                }
            if (record.text("name")?.lowercase() != SUPPORTING_NAME) {
                return@parseSafely SourceParseResult.SourceUnavailable("快乐8详情响应玩法标识异常")
            }
            val numbers =
                parseNumbers(record)
                    ?: return@parseSafely SourceParseResult.SourceUnavailable("快乐8详情开奖号码不合法")
            val drawDate =
                normalizeDrawDate(record.text("date"))
                    ?: return@parseSafely SourceParseResult.SourceUnavailable("快乐8详情开奖日期不合法")
            if (drawDate.take(YEAR_LENGTH) != targetIssue.take(YEAR_LENGTH)) {
                return@parseSafely SourceParseResult.SourceUnavailable("快乐8详情日期与期号年份不一致")
            }
            val tiers =
                when (val parsed = parsePrizeTiers(record)) {
                    is PrizeTierParseResult.Success -> parsed.tiers

                    is PrizeTierParseResult.Failure -> return@parseSafely SourceParseResult.SourceUnavailable(
                        parsed.message,
                    )
                }
            val detailUrl =
                normalizeDetailUrl(record.text("detailsLink"))
                    ?: return@parseSafely SourceParseResult.Publishing("快乐8详情公告链接尚未发布")
            val canonical =
                canonicalSupportingDraw(
                    issue = targetIssue,
                    drawDate = drawDate,
                    primaryNumbers = numbers,
                    secondaryNumbers = emptyList(),
                    prizeTiers = tiers,
                    detailUrl = detailUrl,
                )
            SourceParseResult.Success(
                SupportingDrawSnapshot(
                    issue = targetIssue,
                    drawDate = drawDate,
                    primaryNumbers = numbers,
                    secondaryNumbers = emptyList(),
                    prizeTiers = tiers,
                    detailUrl = detailUrl,
                    evidence = EvidenceDraft("中国福彩网开奖详情", sourceUrl, canonical),
                ),
            )
        }

    /** 校验福彩响应外壳，并取出唯一的目标期记录。 */
    private fun selectRecord(
        rawJson: String,
        targetIssue: String,
        label: String,
    ): RecordSelection {
        val root =
            DrawJson.parseToJsonElement(rawJson).objectOrNull()
                ?: return RecordSelection.Failed.Unavailable("快乐8${label}不是 JSON 对象")
        val state = root.int("state") ?: return RecordSelection.Failed.Unavailable("快乐8${label}缺少业务状态")
        if (state != SUCCESS_STATE) {
            return if (root.text("message").orEmpty().contains(NO_DATA_MESSAGE)) {
                RecordSelection.Failed.NotPublished
            } else {
                RecordSelection.Failed.Unavailable("快乐8${label}返回业务失败")
            }
        }
        val elements =
            root["result"].arrayOrNull() ?: return RecordSelection.Failed.Unavailable("快乐8${label}缺少开奖记录数组")
        val records = elements.map { it.objectOrNull() ?: return RecordSelection.Failed.Unavailable("快乐8开奖记录类型发生变化") }
        if (records.isEmpty()) return RecordSelection.Failed.NotPublished
        val matches = records.filter { it.text("code") == targetIssue }
        if (matches.size > 1) return RecordSelection.Failed.Conflict("快乐8${label}包含重复目标期号")
        val record = matches.singleOrNull() ?: return RecordSelection.Failed.Unavailable("快乐8${label}未返回目标期号")
        if (records.size != 1) return RecordSelection.Failed.Unavailable("快乐8${label}返回了目标期号之外的记录")
        return RecordSelection.Found(record)
    }

    /** 解析 20 个开奖号码并按升序返回；快乐8 没有蓝球字段内容。 */
    private fun parseNumbers(record: JsonObject): List<Int>? {
        if (!record.text("blue").isNullOrEmpty()) return null
        return parseNumberArea(record.text("red"), DRAWN_NUMBER_COUNT, 1..MAX_NUMBER)?.sorted()
    }

    /** 解析全部 `x{选几}z{中几}` 奖级，必须与现行中奖矩阵的 39 个奖级一一对应。 */
    private fun parsePrizeTiers(record: JsonObject): PrizeTierParseResult {
        val rows =
            record["prizegrades"].arrayOrNull()?.map {
                it.objectOrNull() ?: return PrizeTierParseResult.Failure("快乐8奖级列表类型发生变化")
            } ?: return PrizeTierParseResult.Failure("快乐8奖级列表字段缺失")
        val tiers = mutableMapOf<String, PrizeTier>()
        for (row in rows) {
            val match =
                row.text("type")?.lowercase()?.let(TIER_TYPE_REGEX::matchEntire)
                    ?: return PrizeTierParseResult.Failure("快乐8出现未知奖级或活动字段")
            val code = PrizeTierCodes.happy8(match.groupValues[1].toInt(), match.groupValues[2].toInt())
            if (code !in REQUIRED_TIER_CODES) return PrizeTierParseResult.Failure("快乐8出现未知奖级或活动字段")
            val winnerCount =
                parseCount(row.text("typenum"))?.takeIf { it >= 0L }
                    ?: return PrizeTierParseResult.Failure("快乐8奖级注数无法解析")
            val prizeRaw = row.text("typemoney")
            val tier =
                PrizeTier(
                    code = code,
                    displayName = checkNotNull(PrizeTierCodes.happy8DisplayName(code)),
                    singlePrizeFen = parseYuanToFen(prizeRaw),
                    additionalPrizeFen = null,
                    winnerCount = winnerCount,
                    singlePrizeRaw = prizeRaw,
                )
            if (tiers.put(code, tier) != null) return PrizeTierParseResult.Failure("快乐8奖级列表包含重复奖级")
        }
        if (tiers.keys != REQUIRED_TIER_CODES.toSet()) return PrizeTierParseResult.Failure("快乐8奖级列表不完整")
        return PrizeTierParseResult.Success(REQUIRED_TIER_CODES.map(tiers::getValue))
    }

    /** 将福彩相对详情链接归一化为官网绝对地址。 */
    private fun normalizeDetailUrl(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            value.startsWith("$CHINA_WELFARE_LOTTERY_BASE_URL/") -> value
            value.startsWith("/") -> "$CHINA_WELFARE_LOTTERY_BASE_URL$value"
            else -> null
        }
    }

    /** 将 JSON 解析异常收敛为数据源不可用，不泄漏原始响应。 */
    private inline fun <T> parseSafely(block: () -> SourceParseResult<T>): SourceParseResult<T> =
        try {
            block()
        } catch (_: IllegalArgumentException) {
            SourceParseResult.SourceUnavailable("快乐8官网响应无法解析")
        }

    /** 目标期记录的选取结果。 */
    private sealed interface RecordSelection {
        /**
         * 已找到唯一目标期记录。
         *
         * @property record 目标期记录。
         */
        data class Found(
            val record: JsonObject,
        ) : RecordSelection

        /** 无法取得目标期记录。 */
        sealed interface Failed : RecordSelection {
            /** 转换为对外的解析结果。 */
            fun result(): SourceParseResult<Nothing> =
                when (this) {
                    NotPublished -> SourceParseResult.NotPublished
                    is Unavailable -> SourceParseResult.SourceUnavailable(message)
                    is Conflict -> SourceParseResult.Conflict(message)
                }

            /** 官网尚无该期记录。 */
            data object NotPublished : Failed

            /**
             * 响应结构不符合契约。
             *
             * @property message 不含原始响应的说明。
             */
            data class Unavailable(
                val message: String,
            ) : Failed

            /**
             * 响应中目标期重复。
             *
             * @property message 不含原始响应的说明。
             */
            data class Conflict(
                val message: String,
            ) : Failed
        }
    }

    /** 奖级列表解析状态。 */
    private sealed interface PrizeTierParseResult {
        /**
         * 奖级完整可用。
         *
         * @property tiers 按选十至选一、命中数从高到低排列的奖级。
         */
        data class Success(
            val tiers: List<PrizeTier>,
        ) : PrizeTierParseResult

        /**
         * 奖级列表不满足当前契约。
         *
         * @property message 不包含原始响应的错误说明。
         */
        data class Failure(
            val message: String,
        ) : PrizeTierParseResult
    }

    /** 福彩接口成功状态值。 */
    private const val SUCCESS_STATE = 0

    /** 福彩接口无记录提示的稳定片段。 */
    private const val NO_DATA_MESSAGE = "没有查到数据"

    /** 详情接口中的快乐8 玩法名。 */
    private const val SUPPORTING_NAME = "kl8"

    /** 主列表可能返回的快乐8 玩法名称。 */
    private val MAIN_NAMES = setOf("快乐8", SUPPORTING_NAME)

    /** 非空即代表存在尚未建模活动金额的字段。 */
    private val PROMOTION_FIELDS = setOf("addmoney", "addmoney2", "z2add", "m2add", "msg")

    /** 福彩网绝对地址前缀。 */
    private const val CHINA_WELFARE_LOTTERY_BASE_URL = "https://www.cwl.gov.cn"

    /** 每期开奖号码个数。 */
    private const val DRAWN_NUMBER_COUNT = 20

    /** 最大号码。 */
    private const val MAX_NUMBER = 80

    /** 期号与开奖日期中的四位年份。 */
    private const val YEAR_LENGTH = 4

    /** 官网奖级类型格式，例如 `x5z3`。 */
    private val TIER_TYPE_REGEX = Regex("x(\\d{1,2})z(\\d{1,2})")

    /** 现行中奖矩阵的全部奖级。 */
    private val REQUIRED_TIER_CODES = LotteryPrizeRules.HAPPY_8_PRIZE_TIER_CODES
}

package roc.win.lottery.data

import kotlinx.serialization.json.JsonObject
import roc.win.lottery.domain.DrawPolicy
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.PrizeTier
import roc.win.lottery.domain.PrizeTierCodes

/** 体彩超级大乐透官网 JSON 适配器。 */
internal object SuperLottoSourceAdapter {
    /**
     * 解析精确单期历史查询主响应。
     *
     * @param rawJson 只存在于当前调用内存中的官网响应。
     * @param targetIssue 用户指定的目标期号。
     * @param sourceUrl 本次精确查询地址。
     */
    fun parseMain(
        rawJson: String,
        targetIssue: String,
        sourceUrl: String,
    ): SourceParseResult<MainDrawSnapshot> =
        parseSafely {
            val root =
                DrawJson.parseToJsonElement(rawJson).objectOrNull()
                    ?: return@parseSafely SourceParseResult.SourceUnavailable("大乐透主响应不是 JSON 对象")
            if (root.boolean("success") != true || root.text("errorCode") != SUCCESS_ERROR_CODE) {
                return@parseSafely SourceParseResult.SourceUnavailable("大乐透官网返回业务失败")
            }
            val value =
                root["value"].objectOrNull()
                    ?: return@parseSafely SourceParseResult.SourceUnavailable("大乐透主响应缺少 value")
            val recordElements =
                value["list"].arrayOrNull()
                    ?: return@parseSafely SourceParseResult.SourceUnavailable("大乐透主响应缺少开奖记录数组")
            if (recordElements.any { it.objectOrNull() == null }) {
                return@parseSafely SourceParseResult.SourceUnavailable("大乐透开奖记录类型发生变化")
            }
            val records = recordElements.map { requireNotNull(it.objectOrNull()) }
            if (records.isEmpty()) return@parseSafely SourceParseResult.NotPublished
            val matches = records.filter { it.text("lotteryDrawNum") == targetIssue }
            if (matches.size > 1) {
                return@parseSafely SourceParseResult.Conflict("大乐透主响应包含重复目标期号")
            }
            val record =
                matches.singleOrNull()
                    ?: return@parseSafely SourceParseResult.SourceUnavailable("大乐透主响应未返回目标期号")
            if (records.size != 1) {
                return@parseSafely SourceParseResult.SourceUnavailable("大乐透主响应返回了目标期号之外的记录")
            }
            parseMainRecord(record, targetIssue, sourceUrl)
        }

    /**
     * 解析官网最新开奖聚合辅助响应。
     *
     * 辅助接口只允许核对其实际返回的最新期，不接受调用方把其他期号强行视为一致。
     */
    fun parseSupporting(
        rawJson: String,
        targetIssue: String,
        sourceUrl: String,
    ): SourceParseResult<SupportingDrawSnapshot> =
        parseSafely {
            val root =
                DrawJson.parseToJsonElement(rawJson).objectOrNull()
                    ?: return@parseSafely SourceParseResult.SourceUnavailable("大乐透辅助响应不是 JSON 对象")
            if (root.boolean("success") != true || root.text("errorCode") != SUCCESS_ERROR_CODE) {
                return@parseSafely SourceParseResult.SourceUnavailable("大乐透辅助接口返回业务失败")
            }
            val dlt =
                root["value"].objectOrNull()?.get("dlt").objectOrNull()
                    ?: return@parseSafely SourceParseResult.SourceUnavailable("大乐透辅助响应缺少 dlt")
            if (dlt.isEmpty()) return@parseSafely SourceParseResult.NotPublished
            val record = dlt["lastPoolDraw"].objectOrNull() ?: dlt
            val issue =
                record.text("lotteryDrawNum")
                    ?: return@parseSafely SourceParseResult.SourceUnavailable("大乐透辅助响应缺少期号")
            if (issue != targetIssue) return@parseSafely SourceParseResult.NotPublished
            if (record.text("lotteryGameNum") != SUPER_LOTTO_GAME_NUMBER) {
                return@parseSafely SourceParseResult.SourceUnavailable("大乐透辅助响应玩法标识异常")
            }
            if (record.int("verify") != VERIFIED_VALUE || record.int("lotteryNotice") != PUBLISHED_NOTICE_VALUE) {
                return@parseSafely SourceParseResult.Publishing("大乐透辅助数据尚未完成审核发布")
            }
            val numbers =
                parseSuperLottoNumbers(record.text("lotteryDrawResult"))
                    ?: return@parseSafely SourceParseResult.SourceUnavailable("大乐透辅助开奖号码不合法")
            val drawDate =
                normalizeDrawDate(record.text("lotteryDrawTime"))
                    ?: return@parseSafely SourceParseResult.SourceUnavailable("大乐透辅助开奖日期不合法")
            if (drawDate.substring(2, 4) != targetIssue.take(2)) {
                return@parseSafely SourceParseResult.SourceUnavailable("大乐透辅助日期与期号年份不一致")
            }
            val tiers =
                when (val parsed = parsePrizeTiers(record)) {
                    is PrizeTierParseResult.Success -> {
                        parsed.tiers
                    }

                    is PrizeTierParseResult.Failure -> {
                        return@parseSafely SourceParseResult.SourceUnavailable(parsed.message)
                    }
                }
            val detailUrl =
                normalizeDetailUrl(record.text("drawPdfUrl"))
                    ?: return@parseSafely SourceParseResult.Publishing("大乐透辅助公告链接尚未发布")
            val canonical =
                canonicalSupportingDraw(
                    issue = issue,
                    drawDate = drawDate,
                    primaryNumbers = numbers.first,
                    secondaryNumbers = numbers.second,
                    prizeTiers = tiers,
                    detailUrl = detailUrl,
                )
            SourceParseResult.Success(
                SupportingDrawSnapshot(
                    issue = issue,
                    drawDate = drawDate,
                    primaryNumbers = numbers.first,
                    secondaryNumbers = numbers.second,
                    prizeTiers = tiers,
                    detailUrl = detailUrl,
                    evidence = EvidenceDraft("中国体彩网最新开奖", sourceUrl, canonical),
                ),
            )
        }

    /** 解析并严格校验一条大乐透主记录。 */
    private fun parseMainRecord(
        record: JsonObject,
        targetIssue: String,
        sourceUrl: String,
    ): SourceParseResult<MainDrawSnapshot> {
        if (record.text("lotteryGameNum") != SUPER_LOTTO_GAME_NUMBER) {
            return SourceParseResult.SourceUnavailable("大乐透主响应玩法标识异常")
        }
        val verify =
            record.int("verify")
                ?: return SourceParseResult.SourceUnavailable("大乐透主响应缺少审核字段")
        val drawStatus =
            record.int("lotteryDrawStatus")
                ?: return SourceParseResult.SourceUnavailable("大乐透主响应缺少开奖状态")
        val notice =
            record.int("lotteryNotice")
                ?: return SourceParseResult.SourceUnavailable("大乐透主响应缺少公告状态")
        if (verify != VERIFIED_VALUE || drawStatus != FINAL_DRAW_STATUS || notice != PUBLISHED_NOTICE_VALUE) {
            return SourceParseResult.Publishing("大乐透目标期次仍处于发布中")
        }
        val promotionFlag =
            record.int("lotteryPromotionFlag")
                ?: return SourceParseResult.SourceUnavailable("大乐透主响应缺少派奖状态")
        if (promotionFlag != NO_PROMOTION_VALUE) {
            return SourceParseResult.Publishing("大乐透当期存在 V1 尚未验证的派奖活动")
        }
        val numbers =
            parseSuperLottoNumbers(record.text("lotteryDrawResult"))
                ?: return SourceParseResult.SourceUnavailable("大乐透开奖号码不合法")
        val drawDate =
            normalizeDrawDate(record.text("lotteryDrawTime"))
                ?: return SourceParseResult.SourceUnavailable("大乐透开奖日期不合法")
        if (drawDate.substring(2, 4) != targetIssue.take(2)) {
            return SourceParseResult.SourceUnavailable("大乐透开奖日期与期号年份不一致")
        }
        val tierResult = parsePrizeTiers(record)
        if (tierResult is PrizeTierParseResult.Failure) {
            return SourceParseResult.SourceUnavailable(tierResult.message)
        }
        val tiers = (tierResult as PrizeTierParseResult.Success).tiers
        val detailUrl = normalizeDetailUrl(record.text("drawPdfUrl"))
        val publicationFieldsComplete = !detailUrl.isNullOrBlank()
        val payoutFieldsComplete = hasCompletePayouts(tiers)
        val canonical =
            canonicalMainDraw(
                lotteryType = LotteryType.SUPER_LOTTO,
                issue = targetIssue,
                drawDate = drawDate,
                primaryNumbers = numbers.first,
                secondaryNumbers = numbers.second,
                policy = DrawPolicy.STANDARD,
                prizeTiers = tiers,
                publicationFieldsComplete = publicationFieldsComplete,
                detailUrl = detailUrl,
            )
        return SourceParseResult.Success(
            MainDrawSnapshot(
                lotteryType = LotteryType.SUPER_LOTTO,
                issue = targetIssue,
                drawDate = drawDate,
                primaryNumbers = numbers.first,
                secondaryNumbers = numbers.second,
                policy = DrawPolicy.STANDARD,
                prizeTiers = tiers,
                publicationFieldsComplete = publicationFieldsComplete,
                payoutFieldsComplete = payoutFieldsComplete,
                evidence = EvidenceDraft("中国体彩网历史开奖", sourceUrl, canonical),
                detailUrl = detailUrl,
            ),
        )
    }

    /** 将七个号码拆分并校验为大乐透前后区。 */
    private fun parseSuperLottoNumbers(raw: String?): Pair<List<Int>, List<Int>>? {
        val allNumbers =
            raw
                ?.trim()
                ?.split(DRAW_NUMBER_SEPARATOR)
                ?.filter { it.isNotBlank() }
                ?.map { it.toIntOrNull() ?: return null }
                ?: return null
        if (allNumbers.size != TOTAL_NUMBER_COUNT) return null
        val primary = allNumbers.take(PRIMARY_NUMBER_COUNT)
        val secondary = allNumbers.drop(PRIMARY_NUMBER_COUNT)
        if (primary.any { it !in 1..PRIMARY_MAX_NUMBER } || primary.distinct().size != PRIMARY_NUMBER_COUNT) {
            return null
        }
        if (secondary.any { it !in 1..SECONDARY_MAX_NUMBER } || secondary.distinct().size != SECONDARY_NUMBER_COUNT) {
            return null
        }
        return primary to secondary
    }

    /** 按奖级名称聚合基本投注与追加投注字段。 */
    private fun parsePrizeTiers(record: JsonObject): PrizeTierParseResult {
        val rowElements =
            record["prizeLevelList"].arrayOrNull()
                ?: return PrizeTierParseResult.Failure("大乐透奖级列表字段缺失")
        if (rowElements.any { it.objectOrNull() == null }) {
            return PrizeTierParseResult.Failure("大乐透奖级列表类型发生变化")
        }
        val rows = rowElements.map { requireNotNull(it.objectOrNull()) }
        if (rows.isEmpty()) return PrizeTierParseResult.Failure("大乐透奖级列表为空")
        val baseRows = mutableMapOf<String, ParsedPrizeRow>()
        val additionalRows = mutableMapOf<String, ParsedPrizeRow>()
        for (row in rows) {
            val name = row.text("prizeLevel") ?: return PrizeTierParseResult.Failure("大乐透奖级名称缺失")
            val mapping =
                SUPER_LOTTO_PRIZE_NAMES[name]
                    ?: return PrizeTierParseResult.Failure("大乐透出现未知奖级或派奖字段")
            val parsed = parsePrizeRow(row, name) ?: return PrizeTierParseResult.Failure("大乐透奖级数据不合法")
            val target = if (mapping.isAdditional) additionalRows else baseRows
            if (target.put(mapping.code, parsed) != null) {
                return PrizeTierParseResult.Failure("大乐透奖级列表包含重复奖级")
            }
        }
        if (baseRows.keys != REQUIRED_BASE_TIER_CODES || additionalRows.keys != REQUIRED_ADDITIONAL_TIER_CODES) {
            return PrizeTierParseResult.Failure("大乐透奖级列表不完整")
        }
        val tiers =
            REQUIRED_BASE_TIER_CODES.map { code ->
                val base = requireNotNull(baseRows[code])
                val additional = additionalRows[code]
                PrizeTier(
                    code = code,
                    displayName = base.name,
                    singlePrizeFen = base.prizeFen,
                    additionalPrizeFen = additional?.prizeFen,
                    winnerCount = base.winnerCount,
                    additionalWinnerCount = additional?.winnerCount,
                    singlePrizeRaw = base.prizeRaw,
                    additionalPrizeRaw = additional?.prizeRaw,
                )
            }
        return PrizeTierParseResult.Success(tiers)
    }

    /** 解析一行奖级数据并拒绝负注数。 */
    private fun parsePrizeRow(
        row: JsonObject,
        name: String,
    ): ParsedPrizeRow? {
        val countRaw = row.text("stakeCount") ?: return null
        val winnerCount = parseCount(countRaw) ?: return null
        if (winnerCount < 0L) return null
        val prizeRaw = row.text("stakeAmount")?.takeIf { it.isNotBlank() } ?: row.text("stakeAmountFormat")
        val parsedPrizeFen = parseYuanToFen(prizeRaw)
        val prizeFen = parsedPrizeFen.takeUnless { winnerCount == 0L && it == 0L }
        return ParsedPrizeRow(name, winnerCount, prizeRaw, prizeFen)
    }

    /** 判断所有基础奖级以及一、二等奖追加奖级是否已有确定单注金额。 */
    private fun hasCompletePayouts(tiers: List<PrizeTier>): Boolean =
        tiers.all { tier ->
            tier.singlePrizeFen != null &&
                (tier.code !in ADDITIONAL_TIER_CODES || tier.additionalPrizeFen != null)
        }

    /** 只接受 V1 期号使用的中国体彩网官方 PDF 公告地址。 */
    private fun normalizeDetailUrl(raw: String?): String? =
        raw
            ?.trim()
            ?.takeIf { it.startsWith(SUPER_LOTTO_DETAIL_URL_PREFIX) && it.endsWith(".pdf") }

    /** 将 JSON 解析异常收敛为数据源不可用，不泄漏原始响应。 */
    private inline fun <T> parseSafely(block: () -> SourceParseResult<T>): SourceParseResult<T> =
        try {
            block()
        } catch (_: IllegalArgumentException) {
            SourceParseResult.SourceUnavailable("大乐透官网响应无法解析")
        }

    /** 单行奖级的内部解析结果。 */
    private data class ParsedPrizeRow(
        /** 官网奖级名称。 */
        val name: String,
        /** 中奖注数。 */
        val winnerCount: Long,
        /** 官网奖金原文。 */
        val prizeRaw: String?,
        /** 精确换算后的分金额。 */
        val prizeFen: Long?,
    )

    /** 奖级名称映射。 */
    private data class PrizeNameMapping(
        /** 领域稳定奖级编码。 */
        val code: String,
        /** 是否为追加投注行。 */
        val isAdditional: Boolean,
    )

    /** 奖级列表解析状态。 */
    private sealed interface PrizeTierParseResult {
        /** 奖级列表可用。 */
        data class Success(
            /** 聚合后的七个奖级。 */
            val tiers: List<PrizeTier>,
        ) : PrizeTierParseResult

        /** 奖级列表不满足当前契约。 */
        data class Failure(
            /** 不包含原始响应的错误说明。 */
            val message: String,
        ) : PrizeTierParseResult
    }

    /** 大乐透官网字段常量与奖级映射。 */
    private val SUPER_LOTTO_PRIZE_NAMES =
        mapOf(
            "一等奖" to PrizeNameMapping(PrizeTierCodes.FIRST, false),
            "一等奖(追加)" to PrizeNameMapping(PrizeTierCodes.FIRST, true),
            "二等奖" to PrizeNameMapping(PrizeTierCodes.SECOND, false),
            "二等奖(追加)" to PrizeNameMapping(PrizeTierCodes.SECOND, true),
            "三等奖" to PrizeNameMapping(PrizeTierCodes.THIRD, false),
            "四等奖" to PrizeNameMapping(PrizeTierCodes.FOURTH, false),
            "五等奖" to PrizeNameMapping(PrizeTierCodes.FIFTH, false),
            "六等奖" to PrizeNameMapping(PrizeTierCodes.SIXTH, false),
            "七等奖" to PrizeNameMapping(PrizeTierCodes.SEVENTH, false),
        )

    /** 大乐透必须具备的七个基本奖级。 */
    private val REQUIRED_BASE_TIER_CODES =
        linkedSetOf(
            PrizeTierCodes.FIRST,
            PrizeTierCodes.SECOND,
            PrizeTierCodes.THIRD,
            PrizeTierCodes.FOURTH,
            PrizeTierCodes.FIFTH,
            PrizeTierCodes.SIXTH,
            PrizeTierCodes.SEVENTH,
        )

    /** 大乐透必须具备的两个追加奖级。 */
    private val REQUIRED_ADDITIONAL_TIER_CODES = linkedSetOf(PrizeTierCodes.FIRST, PrizeTierCodes.SECOND)

    /** 需要追加单注奖金才能达到最终金额状态的奖级。 */
    private val ADDITIONAL_TIER_CODES = setOf(PrizeTierCodes.FIRST, PrizeTierCodes.SECOND)

    /** 体彩接口业务成功码。 */
    private const val SUCCESS_ERROR_CODE = "0"

    /** 大乐透玩法编号。 */
    private const val SUPER_LOTTO_GAME_NUMBER = "85"

    /** 大乐透开奖公告官方地址前缀。 */
    private const val SUPER_LOTTO_DETAIL_URL_PREFIX = "https://pdf.sporttery.cn/"

    /** 已审核字段值。 */
    private const val VERIFIED_VALUE = 1

    /** 已完成开奖的状态值。 */
    private const val FINAL_DRAW_STATUS = 20

    /** 已发布公告字段值。 */
    private const val PUBLISHED_NOTICE_VALUE = 1

    /** 无临时派奖字段值。 */
    private const val NO_PROMOTION_VALUE = 0

    /** 大乐透前区号码数量。 */
    private const val PRIMARY_NUMBER_COUNT = 5

    /** 大乐透后区号码数量。 */
    private const val SECONDARY_NUMBER_COUNT = 2

    /** 大乐透号码总数。 */
    private const val TOTAL_NUMBER_COUNT = PRIMARY_NUMBER_COUNT + SECONDARY_NUMBER_COUNT

    /** 大乐透前区最大号码。 */
    private const val PRIMARY_MAX_NUMBER = 35

    /** 大乐透后区最大号码。 */
    private const val SECONDARY_MAX_NUMBER = 12

    /** 大乐透官网号码文本分隔符。 */
    private val DRAW_NUMBER_SEPARATOR = Regex("[,\\s]+")
}

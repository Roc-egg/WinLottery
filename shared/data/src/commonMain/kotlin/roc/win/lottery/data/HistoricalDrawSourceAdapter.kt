package roc.win.lottery.data

import kotlinx.serialization.json.JsonObject
import roc.win.lottery.domain.HistoricalDraw
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryType

/** 官网历史开奖分页响应适配器。 */
internal object HistoricalDrawSourceAdapter {
    /**
     * 解析中国体彩网大乐透历史开奖分页响应。
     *
     * @param rawJson 只存在于当前调用内存中的官网响应。
     * @param expectedPageNo 本次请求的页码。
     * @return 按官网最新期在前顺序排列的规范化页面或结构错误。
     */
    fun parseSuperLottoPage(
        rawJson: String,
        expectedPageNo: Int,
    ): HistoricalPageParseResult =
        parseSafely("大乐透") {
            val root =
                DrawJson.parseToJsonElement(rawJson).objectOrNull()
                    ?: return@parseSafely failure("大乐透历史响应不是 JSON 对象")
            if (root.boolean("success") != true || root.text("errorCode") != SUCCESS_ERROR_CODE) {
                return@parseSafely failure("大乐透历史接口返回业务失败")
            }
            val value =
                root["value"].objectOrNull()
                    ?: return@parseSafely failure("大乐透历史响应缺少 value")
            val metadata =
                parsePageMetadata(value, expectedPageNo)
                    ?: return@parseSafely failure("大乐透历史响应分页字段不合法")
            val recordElements =
                value["list"].arrayOrNull()
                    ?: return@parseSafely failure("大乐透历史响应缺少开奖记录数组")
            if (recordElements.any { it.objectOrNull() == null }) {
                return@parseSafely failure("大乐透历史开奖记录类型发生变化")
            }
            val draws = mutableListOf<HistoricalDraw>()
            for (element in recordElements) {
                val record = requireNotNull(element.objectOrNull())
                val draw =
                    parseSuperLottoRecord(record)
                        ?: return@parseSafely failure("大乐透历史开奖记录字段不合法")
                draws += draw
            }
            validatePage(metadata, draws, "大乐透")
        }

    /**
     * 解析中国福彩网双色球历史开奖分页响应。
     *
     * @param rawJson 只存在于当前调用内存中的官网响应。
     * @param expectedPageNo 本次请求的页码。
     * @return 按官网最新期在前顺序排列的规范化页面或结构错误。
     */
    fun parseDoubleColorBallPage(
        rawJson: String,
        expectedPageNo: Int,
    ): HistoricalPageParseResult =
        parseSafely("双色球") {
            val root =
                DrawJson.parseToJsonElement(rawJson).objectOrNull()
                    ?: return@parseSafely failure("双色球历史响应不是 JSON 对象")
            if (root.int("state") != SUCCESS_STATE) {
                return@parseSafely failure("双色球历史接口返回业务失败")
            }
            val metadata =
                parsePageMetadata(root, expectedPageNo)
                    ?: return@parseSafely failure("双色球历史响应分页字段不合法")
            val recordElements =
                root["result"].arrayOrNull()
                    ?: return@parseSafely failure("双色球历史响应缺少开奖记录数组")
            if (recordElements.any { it.objectOrNull() == null }) {
                return@parseSafely failure("双色球历史开奖记录类型发生变化")
            }
            val draws = mutableListOf<HistoricalDraw>()
            for (element in recordElements) {
                val record = requireNotNull(element.objectOrNull())
                val draw =
                    parseDoubleColorBallRecord(record)
                        ?: return@parseSafely failure("双色球历史开奖记录字段不合法")
                draws += draw
            }
            validatePage(metadata, draws, "双色球")
        }

    /** 解析并校验通用分页字段。 */
    private fun parsePageMetadata(
        container: JsonObject,
        expectedPageNo: Int,
    ): HistoricalPageMetadata? {
        val pageNo = container.int("pageNo") ?: return null
        val pageSize = container.int("pageSize") ?: return null
        val total = container.int("total") ?: return null
        if (pageNo != expectedPageNo || pageSize <= 0 || total < 0) return null
        return HistoricalPageMetadata(pageNo = pageNo, pageSize = pageSize, total = total)
    }

    /** 将已解析记录与分页字段做一致性校验。 */
    private fun validatePage(
        metadata: HistoricalPageMetadata,
        draws: List<HistoricalDraw>,
        lotteryName: String,
    ): HistoricalPageParseResult {
        if (draws.size > metadata.pageSize || draws.size > metadata.total) {
            return failure("${lotteryName}历史响应记录数与分页字段不一致")
        }
        if (metadata.total > 0 && draws.isEmpty()) {
            return failure("${lotteryName}历史响应在有效页返回空记录")
        }
        if (draws.map { it.issue.value }.distinct().size != draws.size) {
            return failure("${lotteryName}历史响应包含重复期号")
        }
        if (!draws.isStrictlyNewestFirst()) {
            return failure("${lotteryName}历史响应未按最新期在前排列")
        }
        return HistoricalPageParseResult.Success(
            drawsNewestFirst = draws,
            pageNo = metadata.pageNo,
            pageSize = metadata.pageSize,
            total = metadata.total,
        )
    }

    /** 解析一条已审核发布的大乐透历史开奖。 */
    private fun parseSuperLottoRecord(record: JsonObject): HistoricalDraw? {
        if (
            record.text("lotteryGameNum") != SUPER_LOTTO_GAME_NUMBER ||
            record.int("verify") != VERIFIED_VALUE ||
            record.int("lotteryDrawStatus") != FINAL_DRAW_STATUS ||
            record.int("lotteryNotice") != PUBLISHED_NOTICE_VALUE
        ) {
            return null
        }
        val issue = record.text("lotteryDrawNum")?.takeIf(::isValidSuperLottoIssue) ?: return null
        val drawDate = normalizeDrawDate(record.text("lotteryDrawTime")) ?: return null
        if (drawDate.substring(2, 4) != issue.take(2)) return null
        val numbers = parseSuperLottoNumbers(record.text("lotteryDrawResult")) ?: return null
        return HistoricalDraw(
            lotteryType = LotteryType.SUPER_LOTTO,
            issue = Issue(issue),
            drawDate = drawDate,
            primaryNumbers = numbers.first,
            secondaryNumbers = numbers.second,
        )
    }

    /** 解析一条带正式详情公告的双色球历史开奖。 */
    private fun parseDoubleColorBallRecord(record: JsonObject): HistoricalDraw? {
        if (record.text("name")?.lowercase() !in DOUBLE_COLOR_BALL_NAMES) return null
        val issue = record.text("code")?.takeIf(::isValidDoubleColorBallIssue) ?: return null
        val drawDate = normalizeDrawDate(record.text("date")) ?: return null
        if (drawDate.take(4) != issue.take(4)) return null
        val detailsLink = record.text("detailsLink") ?: return null
        if (!detailsLink.startsWith(OFFICIAL_DETAIL_PATH_PREFIX)) return null
        val primary = parseNumberArea(record.text("red"), expectedCount = 6, allowedRange = 1..33) ?: return null
        val secondary = parseNumberArea(record.text("blue"), expectedCount = 1, allowedRange = 1..16) ?: return null
        if (primary != primary.sorted() || secondary != secondary.sorted()) return null
        return HistoricalDraw(
            lotteryType = LotteryType.DOUBLE_COLOR_BALL,
            issue = Issue(issue),
            drawDate = drawDate,
            primaryNumbers = primary,
            secondaryNumbers = secondary,
        )
    }

    /** 将大乐透七个号码拆分为前区和后区并严格校验。 */
    private fun parseSuperLottoNumbers(raw: String?): Pair<List<Int>, List<Int>>? {
        val numbers =
            raw
                ?.trim()
                ?.split(NUMBER_SEPARATOR)
                ?.filter(String::isNotBlank)
                ?.map { it.toIntOrNull() ?: return null }
                ?: return null
        if (numbers.size != SUPER_LOTTO_TOTAL_NUMBER_COUNT) return null
        val primary = numbers.take(SUPER_LOTTO_PRIMARY_NUMBER_COUNT)
        val secondary = numbers.drop(SUPER_LOTTO_PRIMARY_NUMBER_COUNT)
        if (
            primary.size != primary.distinct().size ||
            primary.any { it !in 1..SUPER_LOTTO_PRIMARY_MAX_NUMBER } ||
            primary != primary.sorted()
        ) {
            return null
        }
        if (
            secondary.size != secondary.distinct().size ||
            secondary.any { it !in 1..SUPER_LOTTO_SECONDARY_MAX_NUMBER } ||
            secondary != secondary.sorted()
        ) {
            return null
        }
        return primary to secondary
    }

    /** 判断大乐透期号是否为固定五位且年内期次非零。 */
    private fun isValidSuperLottoIssue(issue: String): Boolean = isValidIssue(issue, SUPER_LOTTO_ISSUE_LENGTH)

    /** 判断双色球期号是否为固定七位且年内期次非零。 */
    private fun isValidDoubleColorBallIssue(issue: String): Boolean =
        isValidIssue(issue, DOUBLE_COLOR_BALL_ISSUE_LENGTH)

    /** 判断期号长度、字符和年内期次是否有效。 */
    private fun isValidIssue(
        issue: String,
        expectedLength: Int,
    ): Boolean =
        issue.length == expectedLength &&
            issue.all(Char::isDigit) &&
            issue.takeLast(ISSUE_ORDINAL_LENGTH).toInt() > 0

    /** 将解析异常收敛为不包含原始响应的来源错误。 */
    private inline fun parseSafely(
        lotteryName: String,
        block: () -> HistoricalPageParseResult,
    ): HistoricalPageParseResult =
        try {
            block()
        } catch (_: IllegalArgumentException) {
            failure("${lotteryName}历史响应无法解析")
        }

    /** 创建历史页面解析失败。 */
    private fun failure(message: String): HistoricalPageParseResult.Failure = HistoricalPageParseResult.Failure(message)

    /** 分页字段。 */
    private data class HistoricalPageMetadata(
        /** 当前页码。 */
        val pageNo: Int,
        /** 官网实际采用的每页记录数。 */
        val pageSize: Int,
        /** 官网声明的总记录数。 */
        val total: Int,
    )

    /** 历史响应字段常量。 */
    private const val SUCCESS_ERROR_CODE = "0"

    /** 福彩接口业务成功状态。 */
    private const val SUCCESS_STATE = 0

    /** 大乐透玩法编号。 */
    private const val SUPER_LOTTO_GAME_NUMBER = "85"

    /** 大乐透已审核值。 */
    private const val VERIFIED_VALUE = 1

    /** 大乐透已形成最终开奖号码的状态。 */
    private const val FINAL_DRAW_STATUS = 20

    /** 大乐透公告已发布值。 */
    private const val PUBLISHED_NOTICE_VALUE = 1

    /** 大乐透期号长度。 */
    private const val SUPER_LOTTO_ISSUE_LENGTH = 5

    /** 双色球期号长度。 */
    private const val DOUBLE_COLOR_BALL_ISSUE_LENGTH = 7

    /** 年内期次长度。 */
    private const val ISSUE_ORDINAL_LENGTH = 3

    /** 大乐透每期总号码数。 */
    private const val SUPER_LOTTO_TOTAL_NUMBER_COUNT = 7

    /** 大乐透前区号码数。 */
    private const val SUPER_LOTTO_PRIMARY_NUMBER_COUNT = 5

    /** 大乐透前区最大号码。 */
    private const val SUPER_LOTTO_PRIMARY_MAX_NUMBER = 35

    /** 大乐透后区最大号码。 */
    private const val SUPER_LOTTO_SECONDARY_MAX_NUMBER = 12

    /** 福彩详情公告相对路径前缀。 */
    private const val OFFICIAL_DETAIL_PATH_PREFIX = "/c/"

    /** 双色球主响应允许的玩法名称。 */
    private val DOUBLE_COLOR_BALL_NAMES = setOf("双色球", "ssq")

    /** 大乐透号码分隔符。 */
    private val NUMBER_SEPARATOR = Regex("[,\\s]+")
}

/** 历史开奖单页解析结果。 */
internal sealed interface HistoricalPageParseResult {
    /**
     * 已解析并校验页面。
     *
     * @property drawsNewestFirst 当前页按最新期在前排列的开奖记录。
     * @property pageNo 当前页码。
     * @property pageSize 官网实际采用的每页记录数。
     * @property total 官网声明的总记录数。
     */
    data class Success(
        val drawsNewestFirst: List<HistoricalDraw>,
        val pageNo: Int,
        val pageSize: Int,
        val total: Int,
    ) : HistoricalPageParseResult

    /**
     * 页面结构或内容不满足已知契约。
     *
     * @property message 不包含官网原始响应的错误说明。
     */
    data class Failure(
        val message: String,
    ) : HistoricalPageParseResult
}

/** 判断一组规范化历史开奖是否严格按最新期在前排列。 */
internal fun List<HistoricalDraw>.isStrictlyNewestFirst(): Boolean =
    zipWithNext().all { (newer, older) ->
        newer.issue.value > older.issue.value && newer.drawDate >= older.drawDate
    }

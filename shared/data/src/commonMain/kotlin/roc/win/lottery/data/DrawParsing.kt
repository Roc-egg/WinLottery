package roc.win.lottery.data

import kotlinx.datetime.LocalDate
import kotlinx.datetime.parseOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okio.ByteString.Companion.encodeUtf8
import roc.win.lottery.domain.DrawPolicy
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.PrizeTier

/** 官网响应使用的宽松 JSON 解析配置。 */
internal val DrawJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

/** 官网单个数据面的规范化证据草稿。 */
internal data class EvidenceDraft(
    /** 数据来源展示名称。 */
    val sourceName: String,
    /** 包含精确单期参数的请求地址。 */
    val sourceUrl: String,
    /** 只包含必要字段的规范化内容。 */
    val canonicalContent: String,
)

/** 经单个主源解析和严格字段校验后的开奖快照。 */
internal data class MainDrawSnapshot(
    /** 彩票玩法。 */
    val lotteryType: LotteryType,
    /** 响应中的精确期号。 */
    val issue: String,
    /** 归一化后的开奖日期。 */
    val drawDate: String,
    /** 前区或红球号码。 */
    val primaryNumbers: List<Int>,
    /** 后区或蓝球号码。 */
    val secondaryNumbers: List<Int>,
    /** 当期已确认政策，无法确认时为 `null`。 */
    val policy: DrawPolicy?,
    /** 当期奖级明细。 */
    val prizeTiers: List<PrizeTier>,
    /** 主源是否满足已知的审核和发布字段。 */
    val publicationFieldsComplete: Boolean,
    /** 所有实际有中奖注的奖级金额是否完整。 */
    val payoutFieldsComplete: Boolean,
    /** 主数据面规范化证据。 */
    val evidence: EvidenceDraft,
    /** 官网公告或详情链接，无法取得时为 `null`。 */
    val detailUrl: String?,
)

/** 经官方辅助数据面解析后的开奖快照。 */
internal data class SupportingDrawSnapshot(
    /** 响应中的精确期号。 */
    val issue: String,
    /** 归一化后的开奖日期。 */
    val drawDate: String,
    /** 前区或红球号码。 */
    val primaryNumbers: List<Int>,
    /** 后区或蓝球号码。 */
    val secondaryNumbers: List<Int>,
    /** 可用于交叉核对的基础奖级；不提供时为空列表。 */
    val prizeTiers: List<PrizeTier>,
    /** 辅助源中的详情链接，未提供时为 `null`。 */
    val detailUrl: String?,
    /** 辅助数据面规范化证据。 */
    val evidence: EvidenceDraft,
)

/** 官网适配器的封闭解析结果。 */
internal sealed interface SourceParseResult<out T> {
    /** 已取得并校验指定期号。 */
    data class Success<T>(
        /** 解析后的必要字段快照。 */
        val value: T,
    ) : SourceParseResult<T>

    /** 官网明确没有目标期号记录。 */
    data object NotPublished : SourceParseResult<Nothing>

    /** 记录存在，但发布字段、政策或金额结构尚不完整。 */
    data class Publishing(
        /** 不包含原始响应的恢复说明。 */
        val message: String,
    ) : SourceParseResult<Nothing>

    /** HTTP 业务字段或 JSON 结构不符合已知契约。 */
    data class SourceUnavailable(
        /** 不包含原始响应的错误说明。 */
        val message: String,
    ) : SourceParseResult<Nothing>

    /** 单一响应内出现重复期号或互相矛盾的数据。 */
    data class Conflict(
        /** 不包含原始响应的冲突说明。 */
        val message: String,
    ) : SourceParseResult<Nothing>
}

/** 将 JSON 元素宽松读取为对象。 */
internal fun JsonElement?.objectOrNull(): JsonObject? = this as? JsonObject

/** 将 JSON 元素宽松读取为数组。 */
internal fun JsonElement?.arrayOrNull(): JsonArray? = this as? JsonArray

/** 将字符串、数字或布尔 JSON 原语读取为文本。 */
internal fun JsonElement?.textOrNull(): String? =
    when (this) {
        null,
        JsonNull,
        -> null

        is JsonPrimitive -> contentOrNull

        else -> null
    }

/** 读取对象中的宽松文本字段。 */
internal fun JsonObject.text(name: String): String? = this[name].textOrNull()?.trim()

/** 读取对象中的宽松整数值。 */
internal fun JsonObject.int(name: String): Int? = text(name)?.replace(",", "")?.toIntOrNull()

/** 读取对象中的宽松布尔值。 */
internal fun JsonObject.boolean(name: String): Boolean? {
    val element = this[name] as? JsonPrimitive ?: return null
    element.booleanOrNull?.let { return it }
    return when (element.content.trim().lowercase()) {
        "1" -> true
        "0" -> false
        else -> null
    }
}

/** 读取对象中的数组字段，缺失或类型漂移时返回空列表。 */
internal fun JsonObject.array(name: String): List<JsonElement> = this[name].arrayOrNull()?.toList().orEmpty()

/**
 * 解析并校验一个开奖号码区域。
 *
 * @param raw 官网号码文本。
 * @param expectedCount 期望的号码数量。
 * @param allowedRange 单个号码合法范围。
 * @return 保留官网顺序的号码；格式、范围或唯一性错误时为 `null`。
 */
internal fun parseNumberArea(
    raw: String?,
    expectedCount: Int,
    allowedRange: IntRange,
): List<Int>? {
    val numbers =
        raw
            ?.trim()
            ?.split(NUMBER_SEPARATOR)
            ?.filter { it.isNotBlank() }
            ?.map { it.toIntOrNull() ?: return null }
            ?: return null
    if (numbers.size != expectedCount) return null
    if (numbers.distinct().size != expectedCount) return null
    if (numbers.any { it !in allowedRange }) return null
    return numbers
}

/** 将官网日期或日期时间归一化为 `YYYY-MM-DD`。 */
internal fun normalizeDrawDate(raw: String?): String? {
    val value = raw?.trim()?.take(DATE_TEXT_LENGTH) ?: return null
    return if (LocalDate.parseOrNull(value) != null) value else null
}

/** 将官网注数文本解析为非格式化整数，无法解析时为 `null`。 */
internal fun parseCount(raw: String?): Long? = raw?.trim()?.replace(",", "")?.toLongOrNull()

/**
 * 将官网人民币元文本精确转换为分。
 *
 * 空串、横线占位和 `-1` 表示官网未提供金额；中文说明或超过两位小数也保留为未知，绝不转成零。
 */
internal fun parseYuanToFen(raw: String?): Long? {
    val normalized = raw?.trim()?.replace(",", "") ?: return null
    if (normalized.isEmpty() || normalized in MISSING_AMOUNT_MARKERS) return null
    val match = MONEY_PATTERN.matchEntire(normalized) ?: return null
    val yuan = match.groupValues[1].toLongOrNull() ?: return null
    if (yuan < 0L) return null
    val fractionText = match.groupValues[2]
    val fraction =
        when (fractionText.length) {
            0 -> 0L
            1 -> fractionText.toLong() * 10L
            2 -> fractionText.toLong()
            else -> return null
        }
    if (yuan > (Long.MAX_VALUE - fraction) / FEN_PER_YUAN) return null
    return yuan * FEN_PER_YUAN + fraction
}

/** 计算 UTF-8 规范化文本的 SHA-256 小写十六进制值。 */
internal fun sha256Hex(content: String): String = content.encodeUtf8().sha256().hex()

/** 为主开奖快照生成不含抓取时间和原始响应的稳定规范化内容。 */
internal fun canonicalMainDraw(
    lotteryType: LotteryType,
    issue: String,
    drawDate: String,
    primaryNumbers: List<Int>,
    secondaryNumbers: List<Int>,
    policy: DrawPolicy?,
    prizeTiers: List<PrizeTier>,
    publicationFieldsComplete: Boolean,
    detailUrl: String?,
): String =
    buildJsonObject {
        put("lotteryType", lotteryType.name)
        put("issue", issue)
        put("drawDate", drawDate)
        put("primaryNumbers", jsonNumbers(primaryNumbers))
        put("secondaryNumbers", jsonNumbers(secondaryNumbers))
        put("policy", policy?.name ?: "UNKNOWN")
        put("publicationFieldsComplete", publicationFieldsComplete)
        put("detailUrl", detailUrl.orEmpty())
        put("prizeTiers", jsonPrizeTiers(prizeTiers))
    }.toString()

/** 为辅助开奖快照生成稳定规范化内容。 */
internal fun canonicalSupportingDraw(
    issue: String,
    drawDate: String,
    primaryNumbers: List<Int>,
    secondaryNumbers: List<Int>,
    prizeTiers: List<PrizeTier>,
    detailUrl: String?,
): String =
    buildJsonObject {
        put("issue", issue)
        put("drawDate", drawDate)
        put("primaryNumbers", jsonNumbers(primaryNumbers))
        put("secondaryNumbers", jsonNumbers(secondaryNumbers))
        put("detailUrl", detailUrl.orEmpty())
        put("prizeTiers", jsonPrizeTiers(prizeTiers))
    }.toString()

/** 为开奖号码列表生成 JSON 数组。 */
private fun jsonNumbers(numbers: List<Int>): JsonArray = buildJsonArray { numbers.forEach { add(JsonPrimitive(it)) } }

/** 为奖级列表生成固定排序的 JSON 数组。 */
private fun jsonPrizeTiers(prizeTiers: List<PrizeTier>): JsonArray =
    buildJsonArray {
        prizeTiers.sortedBy { it.code }.forEach { tier ->
            add(
                buildJsonObject {
                    put("code", tier.code)
                    put("singlePrizeFen", tier.singlePrizeFen)
                    put("additionalPrizeFen", tier.additionalPrizeFen)
                    put("winnerCount", tier.winnerCount)
                    put("additionalWinnerCount", tier.additionalWinnerCount)
                    put("singlePrizeRaw", tier.singlePrizeRaw.takeIf { tier.singlePrizeFen == null }.orEmpty())
                    put(
                        "additionalPrizeRaw",
                        tier.additionalPrizeRaw.takeIf { tier.additionalPrizeFen == null }.orEmpty(),
                    )
                },
            )
        }
    }

/** JSON `null` 安全写入扩展。 */
private fun kotlinx.serialization.json.JsonObjectBuilder.put(
    key: String,
    value: Long?,
) {
    if (value == null) {
        put(key, JsonNull)
    } else {
        put(key, value)
    }
}

/** 号码文本分隔符。 */
private val NUMBER_SEPARATOR = Regex("[,\\s]+")

/** 严格十进制金额格式。 */
private val MONEY_PATTERN = Regex("^(\\d+)(?:\\.(\\d{1,2}))?$")

/** 官网表示金额尚不可用的占位符。 */
private val MISSING_AMOUNT_MARKERS = setOf("---", "--", "-", "-1")

/** 一元等于多少分。 */
private const val FEN_PER_YUAN = 100L

/** ISO 日期文本长度。 */
private const val DATE_TEXT_LENGTH = 10

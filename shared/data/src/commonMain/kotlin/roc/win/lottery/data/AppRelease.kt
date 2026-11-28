package roc.win.lottery.data

import kotlin.time.Instant

/**
 * 只含数字段的应用版本号，例如 `1.3.8`。
 *
 * 发版流程要求标签严格为 `v主.次.修`，因此不接受预发布或构建元数据后缀，无法解析时调用方应放弃比较。
 *
 * @property segments 去掉末尾 0 之前的原始数字段，用于展示。
 */
class AppVersion private constructor(
    val segments: List<Int>,
) : Comparable<AppVersion> {
    /** 去掉末尾 0 的数字段，保证 `1.4` 与 `1.4.0` 相等。 */
    private val normalized: List<Int> = segments.dropLastWhile { it == 0 }

    override fun compareTo(other: AppVersion): Int {
        val length = maxOf(normalized.size, other.normalized.size)
        for (index in 0 until length) {
            val difference = normalized.getOrElse(index) { 0 } - other.normalized.getOrElse(index) { 0 }
            if (difference != 0) return difference
        }
        return 0
    }

    override fun equals(other: Any?): Boolean = other is AppVersion && normalized == other.normalized

    override fun hashCode(): Int = normalized.hashCode()

    override fun toString(): String = segments.joinToString(".")

    /** 解析版本字符串。 */
    companion object {
        /** 最多接受的数字段数。 */
        private const val MAX_SEGMENTS = 4

        /** 单个数字段最多位数，避免整数溢出。 */
        private const val MAX_SEGMENT_DIGITS = 6

        /**
         * 解析应用版本号或发布标签。
         *
         * @param raw 例如 `1.3.8` 或 `v1.3.8`。
         * @return 版本号；为空、段数不在 1 至 4、含非数字字符或带后缀时为 `null`。
         */
        fun parse(raw: String): AppVersion? {
            val text = raw.trim().let { if (it.startsWith('v') || it.startsWith('V')) it.substring(1) else it }
            if (text.isEmpty()) return null
            val parts = text.split('.')
            if (parts.size > MAX_SEGMENTS) return null
            val segments =
                parts.map { part ->
                    if (part.isEmpty() || part.length > MAX_SEGMENT_DIGITS || !part.all { it in '0'..'9' }) {
                        return null
                    }
                    part.toInt()
                }
            return AppVersion(segments)
        }
    }
}

/**
 * 发布版附带的一个安装包。
 *
 * @property name 文件名，例如 `WinLottery-v1.3.8-android-arm64-v8a.apk`。
 * @property downloadUrl 已校验属于本项目发布页的下载地址。
 * @property sizeBytes 文件字节数；上游未给出或不合法时为 `null`。
 */
data class AppReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long?,
)

/**
 * 项目最新正式发布版。
 *
 * @property version 标签解析出的版本号。
 * @property tagName 原始发布标签。
 * @property title 发布标题；上游为空时使用标签。
 * @property notes 发布说明原文（Markdown），可能为空。
 * @property publishedAt 发布时间；上游缺失或格式异常时为 `null`。
 * @property pageUrl 已校验属于本项目的发布页地址。
 * @property assets 下载地址校验通过的安装包。
 */
data class AppRelease(
    val version: AppVersion,
    val tagName: String,
    val title: String,
    val notes: String,
    val publishedAt: Instant?,
    val pageUrl: String,
    val assets: List<AppReleaseAsset>,
)

/** 查询最新发布版的结果。 */
sealed interface AppReleaseQueryResult {
    /**
     * 取得并校验了最新正式发布版。
     *
     * @property release 最新发布版。
     */
    data class Success(
        val release: AppRelease,
    ) : AppReleaseQueryResult

    /**
     * 网络、上游或解析失败。
     *
     * @property message 面向用户的简短说明。
     */
    data class Failure(
        val message: String,
    ) : AppReleaseQueryResult
}

/** 查询应用最新正式发布版的来源。 */
interface AppReleaseRepository {
    /** 查询一次最新正式发布版；不重试轮询，失败时返回说明。 */
    suspend fun latestRelease(): AppReleaseQueryResult
}

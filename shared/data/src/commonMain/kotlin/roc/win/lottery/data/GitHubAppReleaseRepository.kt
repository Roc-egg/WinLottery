package roc.win.lottery.data

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlin.time.Instant

/**
 * 通过 GitHub 公开接口读取本项目最新正式发布版。
 *
 * 每次调用只发送一个不带身份信息的 GET（失败时由共享客户端最多重试一次），请求中不含票据、记录、
 * AI 配置或设备标识。返回的发布页和下载地址必须属于 [owner]/[repository] 的发布页，否则视为响应异常，
 * 避免界面打开仓库之外的链接。
 *
 * @property httpClient 使用当前平台网络栈的客户端。
 * @property owner GitHub 仓库所有者。
 * @property repository GitHub 仓库名称。
 */
class GitHubAppReleaseRepository(
    private val httpClient: HttpClient = createPlatformHttpClient(),
    private val owner: String = WIN_LOTTERY_GITHUB_OWNER,
    private val repository: String = WIN_LOTTERY_GITHUB_REPOSITORY,
) : AppReleaseRepository,
    AutoCloseable {
    /** 最新正式发布版接口地址；GitHub 只在这里返回非草稿、非预发布的版本。 */
    private val latestReleaseUrl = "https://api.github.com/repos/$owner/$repository/releases/latest"

    /** 合法发布页地址的前缀。 */
    private val releasePagePrefix = "https://github.com/$owner/$repository/releases/tag/"

    /** 合法安装包下载地址的前缀。 */
    private val downloadPrefix = "https://github.com/$owner/$repository/releases/download/"

    /** 应用会话结束时关闭网络客户端。 */
    override fun close() {
        httpClient.close()
    }

    override suspend fun latestRelease(): AppReleaseQueryResult {
        val body =
            try {
                val response =
                    httpClient.get(latestReleaseUrl) {
                        header(HttpHeaders.Accept, GITHUB_JSON_CONTENT_TYPE)
                        header(GITHUB_API_VERSION_HEADER, GITHUB_API_VERSION)
                        header(HttpHeaders.CacheControl, "no-cache")
                        header(HttpHeaders.UserAgent, USER_AGENT)
                    }
                if (!response.status.isSuccess()) {
                    val rateLimited =
                        response.status == HttpStatusCode.TooManyRequests ||
                            (
                                response.status == HttpStatusCode.Forbidden &&
                                    response.headers[RATE_LIMIT_REMAINING_HEADER] == "0"
                            )
                    return AppReleaseQueryResult.Failure(
                        when {
                            rateLimited -> "GitHub 访问次数暂时用完，请过一会儿再检查"
                            response.status == HttpStatusCode.NotFound -> "GitHub 上暂时没有正式发布版"
                            else -> "GitHub 返回 HTTP ${response.status.value}，请稍后再检查"
                        },
                    )
                }
                val bytes = response.bodyAsChannel().readRemaining(MAX_RESPONSE_BYTES + 1L).readByteArray()
                if (bytes.size > MAX_RESPONSE_BYTES) return malformed()
                bytes.decodeToString()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                return AppReleaseQueryResult.Failure("无法连接 GitHub，请检查网络后再试")
            } catch (_: Exception) {
                return AppReleaseQueryResult.Failure("GitHub 请求失败，请稍后再检查")
            }
        val release = runCatching { parseRelease(body) }.getOrNull() ?: return malformed()
        return AppReleaseQueryResult.Success(release)
    }

    /** 解析并校验发布版响应；任一必需字段不合法时返回 `null`。 */
    private fun parseRelease(body: String): AppRelease? {
        val root = DrawJson.parseToJsonElement(body).jsonObject
        if (root.strictBoolean("draft") != false || root.strictBoolean("prerelease") != false) return null
        val tagName = root.strictString("tag_name") ?: return null
        val version = AppVersion.parse(tagName) ?: return null
        val pageUrl = root.strictString("html_url")?.takeIf { isTrustedUrl(it, releasePagePrefix) } ?: return null
        val assets =
            root["assets"]?.jsonArray.orEmpty().mapNotNull { element ->
                val asset = element as? JsonObject ?: return@mapNotNull null
                val name = asset.strictString("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val url =
                    asset.strictString("browser_download_url")?.takeIf { isTrustedUrl(it, downloadPrefix) }
                        ?: return@mapNotNull null
                AppReleaseAsset(
                    name = name,
                    downloadUrl = url,
                    sizeBytes = (asset["size"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 },
                )
            }
        return AppRelease(
            version = version,
            tagName = tagName,
            title = root.strictString("name")?.takeIf { it.isNotBlank() } ?: tagName,
            notes = root.strictString("body").orEmpty().take(MAX_NOTES_CHARS),
            publishedAt = root.strictString("published_at")?.let { runCatching { Instant.parse(it) }.getOrNull() },
            pageUrl = pageUrl,
            assets = assets,
        )
    }

    /** 判断地址是否以本项目发布页前缀开头且不含空白字符。 */
    private fun isTrustedUrl(
        url: String,
        prefix: String,
    ): Boolean =
        url.startsWith(prefix, ignoreCase = true) &&
            url.length > prefix.length &&
            url.none { it.isWhitespace() } &&
            ".." !in url

    /** 响应结构异常时的统一失败。 */
    private fun malformed(): AppReleaseQueryResult = AppReleaseQueryResult.Failure("GitHub 发布信息格式异常，请稍后再检查")

    /** 请求头与上限常量。 */
    private companion object {
        /** GitHub 推荐的 JSON 响应类型。 */
        const val GITHUB_JSON_CONTENT_TYPE = "application/vnd.github+json"

        /** GitHub REST 接口版本请求头。 */
        const val GITHUB_API_VERSION_HEADER = "X-GitHub-Api-Version"

        /** 固定使用的 GitHub REST 接口版本。 */
        const val GITHUB_API_VERSION = "2022-11-28"

        /** GitHub 剩余请求次数响应头。 */
        const val RATE_LIMIT_REMAINING_HEADER = "x-ratelimit-remaining"

        /** GitHub 要求请求带 User-Agent；只标明应用名，不含版本或设备信息。 */
        const val USER_AGENT = "WinLottery-update-check"

        /** 发布版响应最多读取的字节数。 */
        const val MAX_RESPONSE_BYTES = 1_000_000

        /** 发布说明最多保留的字符数。 */
        const val MAX_NOTES_CHARS = 8_000
    }
}

/** 读取字符串字段；缺失、为 `null` 或不是字符串时为 `null`。 */
private fun JsonObject.strictString(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/** 读取布尔字段；缺失或不是布尔值时为 `null`。 */
private fun JsonObject.strictBoolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

/** 本项目在 GitHub 上的仓库所有者。 */
const val WIN_LOTTERY_GITHUB_OWNER = "Roc-egg"

/** 本项目在 GitHub 上的仓库名称。 */
const val WIN_LOTTERY_GITHUB_REPOSITORY = "WinLottery"

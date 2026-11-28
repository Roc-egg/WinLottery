package roc.win.lottery.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** 应用版本号解析与比较。 */
class AppVersionTest {
    /** 带或不带 v 前缀都能解析，末尾 0 不影响相等。 */
    @Test
    fun parsesTagsAndComparesNumerically() {
        assertEquals(AppVersion.parse("1.3.8"), AppVersion.parse("v1.3.8"))
        assertEquals(AppVersion.parse("1.4"), AppVersion.parse("1.4.0"))
        assertTrue(AppVersion.parse("1.3.10")!! > AppVersion.parse("1.3.9")!!)
        assertTrue(AppVersion.parse("2.0.0")!! > AppVersion.parse("1.99.99")!!)
        assertTrue(AppVersion.parse("1.3.8")!! < AppVersion.parse("1.3.8.1")!!)
        assertEquals("1.3.8", AppVersion.parse("V1.3.8").toString())
    }

    /** 预发布后缀、空段和非数字内容都拒绝，调用方不再比较。 */
    @Test
    fun rejectsNonNumericVersions() {
        listOf("", "v", "1..3", "1.3.8-beta", "1.3.8+5", "v1.3.8-dry-run-7-1", "abc", "1.2.3.4.5", "1.3.x")
            .forEach { assertNull(AppVersion.parse(it), it) }
    }
}

/** GitHub 最新发布版查询：只请求一次公开接口，严格校验版本和链接。 */
class GitHubAppReleaseRepositoryTest {
    /** 正常响应解析出版本、说明、时间和本仓库的安装包，并只发送一次不带凭据的 GET。 */
    @Test
    fun parsesLatestRelease() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val repository =
                repository { request ->
                    requests += request
                    json(releaseJson())
                }

            val release = assertIs<AppReleaseQueryResult.Success>(repository.latestRelease()).release

            assertEquals(1, requests.size)
            val request = requests.single()
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("https://api.github.com/repos/Roc-egg/WinLottery/releases/latest", request.url.toString())
            assertEquals("WinLottery-update-check", request.headers[HttpHeaders.UserAgent])
            assertNull(request.headers[HttpHeaders.Authorization])
            assertEquals(AppVersion.parse("1.3.9"), release.version)
            assertEquals("WinLottery 1.3.9", release.title)
            assertEquals("## 本版更新\n\n- 新功能", release.notes)
            assertEquals(Instant.parse("2026-11-30T04:22:42Z"), release.publishedAt)
            assertEquals("https://github.com/Roc-egg/WinLottery/releases/tag/v1.3.9", release.pageUrl)
            assertEquals(
                listOf("WinLottery-v1.3.9-android-arm64-v8a.apk", "WinLottery-v1.3.9-macos-arm64.dmg"),
                release.assets.map { it.name },
            )
            assertEquals(65_639_224L, release.assets.first().sizeBytes)
        }

    /** 指向仓库之外的安装包被丢弃；发布页不属于本仓库时整个响应视为异常。 */
    @Test
    fun rejectsUntrustedLinks() =
        runTest {
            val withForeignAsset =
                repository {
                    json(
                        releaseJson(
                            extraAsset =
                                """{"name":"evil.apk","size":1,"browser_download_url":"https://example.com/evil.apk"}""",
                        ),
                    )
                }
            val assets = assertIs<AppReleaseQueryResult.Success>(withForeignAsset.latestRelease()).release.assets
            assertTrue(assets.none { it.name == "evil.apk" })

            listOf(
                "https://example.com/Roc-egg/WinLottery/releases/tag/v1.3.9",
                "https://github.com/someone/WinLottery/releases/tag/v1.3.9",
                "http://github.com/Roc-egg/WinLottery/releases/tag/v1.3.9",
            ).forEach { pageUrl ->
                val foreignPage = repository { json(releaseJson(pageUrl = pageUrl)) }
                assertIs<AppReleaseQueryResult.Failure>(foreignPage.latestRelease(), pageUrl)
            }
        }

    /** 草稿、预发布、无法解析的标签和非 JSON 内容都失败关闭。 */
    @Test
    fun rejectsUnexpectedReleases() =
        runTest {
            listOf(
                releaseJson(draft = true),
                releaseJson(prerelease = true),
                releaseJson(tag = "v1.4.0-beta1"),
                releaseJson(tag = "latest"),
                "<html>rate limited</html>",
                "[]",
            ).forEach { body ->
                val repository = repository { json(body) }
                assertIs<AppReleaseQueryResult.Failure>(repository.latestRelease(), body)
            }
        }

    /** 限流、无发布版、服务异常和断网分别给出可理解的说明。 */
    @Test
    fun mapsFailures() =
        runTest {
            val rateLimited =
                repository {
                    respond("{}", HttpStatusCode.Forbidden, headersOf("x-ratelimit-remaining", "0"))
                }
            assertEquals(
                "GitHub 访问次数暂时用完，请过一会儿再检查",
                assertIs<AppReleaseQueryResult.Failure>(rateLimited.latestRelease()).message,
            )
            val notFound = repository { respond("{}", HttpStatusCode.NotFound) }
            assertEquals(
                "GitHub 上暂时没有正式发布版",
                assertIs<AppReleaseQueryResult.Failure>(notFound.latestRelease()).message,
            )
            val unavailable = repository { respond("{}", HttpStatusCode.BadGateway) }
            assertEquals(
                "GitHub 返回 HTTP 502，请稍后再检查",
                assertIs<AppReleaseQueryResult.Failure>(unavailable.latestRelease()).message,
            )
            val offline = repository { throw IOException("offline") }
            assertEquals(
                "无法连接 GitHub，请检查网络后再试",
                assertIs<AppReleaseQueryResult.Failure>(offline.latestRelease()).message,
            )
        }

    /** 创建使用 MockEngine 的仓库。 */
    private fun repository(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): GitHubAppReleaseRepository = GitHubAppReleaseRepository(httpClient = HttpClient(MockEngine(handler)))

    /** 返回 JSON 响应。 */
    private fun MockRequestHandleScope.json(body: String): HttpResponseData =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    /** 构造与 GitHub 接口字段一致的最新发布版响应。 */
    private fun releaseJson(
        tag: String = "v1.3.9",
        draft: Boolean = false,
        prerelease: Boolean = false,
        pageUrl: String = "https://github.com/Roc-egg/WinLottery/releases/tag/$tag",
        extraAsset: String? = null,
    ): String {
        val base = "https://github.com/Roc-egg/WinLottery/releases/download/$tag"
        val assets =
            listOfNotNull(
                """{"name":"WinLottery-$tag-android-arm64-v8a.apk","size":65639224,""" +
                    """"browser_download_url":"$base/WinLottery-$tag-android-arm64-v8a.apk"}""",
                """{"name":"WinLottery-$tag-macos-arm64.dmg","size":177565063,""" +
                    """"browser_download_url":"$base/WinLottery-$tag-macos-arm64.dmg"}""",
                extraAsset,
            ).joinToString(",")
        return """
            {
              "tag_name": "$tag",
              "name": "WinLottery 1.3.9",
              "html_url": "$pageUrl",
              "draft": $draft,
              "prerelease": $prerelease,
              "published_at": "2026-11-30T04:22:42Z",
              "body": "## 本版更新\n\n- 新功能",
              "assets": [$assets]
            }
            """.trimIndent()
    }
}

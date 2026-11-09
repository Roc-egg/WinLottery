package roc.win.lottery.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import roc.win.lottery.domain.AiAnalysisOutputValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 可配置接口格式的 AI Provider 单请求网络边界测试。 */
class HttpAiAnalysisProviderTest {
    /** 成功请求必须只发送一次 POST，密钥只进入认证头且请求体不含密钥。 */
    @Test
    fun sendsSinglePostAndParsesValidatedResult() =
        runTest {
            val configuration = testAiProviderConfiguration()
            val request = testAiAnalysisRequest()
            var requestCount = 0
            var capturedAuthorization: String? = null
            var capturedBody = ""
            val provider =
                provider { requestData ->
                    requestCount += 1
                    capturedAuthorization = requestData.headers[HttpHeaders.Authorization]
                    capturedBody = requestData.body.toByteArray().decodeToString()
                    assertEquals(HttpMethod.Post, requestData.method)
                    jsonResponse(completedResponsesBody(validAiOutputJson(request)))
                }

            val result = provider.analyze(configuration, request)

            assertEquals(1, requestCount)
            assertEquals("Bearer $TEST_AI_API_KEY", capturedAuthorization)
            assertTrue(TEST_AI_API_KEY !in capturedBody)
            assertEquals(2, assertIs<AiProviderResult.Success>(result).analysis.candidates.size)
        }

    /** 确认预览不得包含密钥，并必须与完整请求体字节数一致。 */
    @Test
    fun returnsSafePreviewBeforeNetwork() {
        val configuration = testAiProviderConfiguration()
        val request = testAiAnalysisRequest()
        val provider = provider { error("预览不应发起网络请求") }

        val preview = assertIs<AiProviderPreviewResult.Success>(provider.preview(configuration, request)).preview
        val prepared = AiRequestBuilder.build(configuration, request)

        assertEquals(prepared.body.encodeToByteArray().size, preview.requestBodyByteCount)
        assertTrue(TEST_AI_API_KEY !in preview.toString())
        assertEquals(configuration.endpointHost, preview.endpointHost)
    }

    /** 鉴权、限流和服务异常必须稳定映射并带状态码，5xx POST 不得自动重试，正文中的密钥必须隐藏。 */
    @Test
    fun mapsHttpFailuresWithoutRetrying() =
        runTest {
            val cases =
                listOf(
                    HttpStatusCode.Unauthorized to AiProviderFailureCode.AUTHENTICATION_FAILED,
                    HttpStatusCode.TooManyRequests to AiProviderFailureCode.RATE_LIMITED,
                    HttpStatusCode.ServiceUnavailable to AiProviderFailureCode.SERVICE_UNAVAILABLE,
                )
            cases.forEach { (status, expectedCode) ->
                var requestCount = 0
                val provider =
                    provider {
                        requestCount += 1
                        respond(
                            content = "服务端正文包含 $TEST_AI_API_KEY",
                            status = status,
                        )
                    }

                val result = provider.analyze(testAiProviderConfiguration(), testAiAnalysisRequest())
                val failure = assertIs<AiProviderResult.Failure>(result)

                assertEquals(expectedCode, failure.code)
                assertEquals(1, requestCount)
                assertTrue("HTTP ${status.value}" in failure.message)
                assertTrue(TEST_AI_API_KEY !in failure.message)
                assertTrue("<已隐藏>" in failure.message)
            }
        }

    /** 400 和 404 必须给出状态码和服务商错误说明，便于判断是参数、接口地址还是模型问题。 */
    @Test
    fun showsProviderErrorDetail() =
        runTest {
            val rejected =
                provider {
                    respond(
                        content =
                            """{"error":{"message":"Unsupported parameter: 'truncation'",""" +
                                """"type":"invalid_request_error"}}""",
                        status = HttpStatusCode.BadRequest,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }.analyze(testAiProviderConfiguration(), testAiAnalysisRequest())
            val missing =
                provider { respond(content = "<html>404 Not Found</html>", status = HttpStatusCode.NotFound) }
                    .analyze(testAiProviderConfiguration(), testAiAnalysisRequest())

            val rejectedFailure = assertIs<AiProviderResult.Failure>(rejected)
            assertEquals(AiProviderFailureCode.REQUEST_REJECTED, rejectedFailure.code)
            assertEquals(
                "AI 服务拒绝当前请求结构或参数（HTTP 400）。服务商说明：Unsupported parameter: 'truncation'",
                rejectedFailure.message,
            )
            val missingFailure = assertIs<AiProviderResult.Failure>(missing)
            assertEquals(AiProviderFailureCode.REQUEST_REJECTED, missingFailure.code)
            assertEquals("接口地址或模型不存在，请检查 Base URL、接口格式和模型（HTTP 404）", missingFailure.message)
        }

    /** 错误说明兼容常见结构，去掉换行并截断过长文本。 */
    @Test
    fun extractsProviderErrorDetail() {
        assertEquals(
            "model not found",
            providerErrorDetail(
                """{"type":"error","error":{"type":"not_found_error","message":"model not found"}}""",
                "",
            ),
        )
        assertEquals("quota exceeded", providerErrorDetail("""{"error":"quota exceeded"}""", ""))
        assertEquals("bad request", providerErrorDetail("""{"detail":"bad request"}""", ""))
        assertEquals("line one line two", providerErrorDetail("line one\nline two", ""))
        assertNull(providerErrorDetail("<!DOCTYPE html><html></html>", ""))
        assertNull(providerErrorDetail("""{"code":400}""", ""))
        assertEquals(201, providerErrorDetail("x".repeat(500), "")?.length)
    }

    /** Chat Completions 请求 /chat/completions，使用 Bearer 鉴权和 JSON 对象模式，并能解析代码块包裹的输出。 */
    @Test
    fun sendsChatCompletionsRequest() =
        runTest {
            val configuration = testAiProviderConfiguration(apiFormat = AiApiFormat.OPENAI_CHAT_COMPLETIONS)
            val request = testAiAnalysisRequest()
            var capturedBody = ""
            val provider =
                provider { requestData ->
                    assertEquals("https://api.example.test/v1/chat/completions", requestData.url.toString())
                    assertEquals("Bearer $TEST_AI_API_KEY", requestData.headers[HttpHeaders.Authorization])
                    capturedBody = requestData.body.toByteArray().decodeToString()
                    jsonResponse(completedChatCompletionsBody("```json\n${validAiOutputJson(request)}\n```"))
                }

            val result = provider.analyze(configuration, request)

            assertEquals(2, assertIs<AiProviderResult.Success>(result).analysis.candidates.size)
            assertTrue("\"messages\"" in capturedBody)
            assertTrue("\"json_object\"" in capturedBody)
            assertTrue(TEST_AI_API_KEY !in capturedBody)
        }

    /** Anthropic Messages 请求 /v1/messages，只用 x-api-key 与版本头鉴权，并忽略思考块。 */
    @Test
    fun sendsAnthropicMessagesRequest() =
        runTest {
            val configuration =
                testAiProviderConfiguration(
                    apiFormat = AiApiFormat.ANTHROPIC_MESSAGES,
                    baseUrl = "https://api.example.test",
                )
            val request = testAiAnalysisRequest()
            val provider =
                provider { requestData ->
                    assertEquals("https://api.example.test/v1/messages", requestData.url.toString())
                    assertEquals(TEST_AI_API_KEY, requestData.headers["x-api-key"])
                    assertEquals("2023-06-01", requestData.headers["anthropic-version"])
                    assertNull(requestData.headers[HttpHeaders.Authorization])
                    jsonResponse(completedAnthropicMessagesBody(validAiOutputJson(request), withThinking = true))
                }

            val result = provider.analyze(configuration, request)

            assertEquals(2, assertIs<AiProviderResult.Success>(result).analysis.candidates.size)
        }

    /** 3xx 必须停在已确认主机，不能把密钥带到 Location 指向的地址。 */
    @Test
    fun blocksRedirectWithoutFollowingIt() =
        runTest {
            var requestCount = 0
            var redirectedAuthorization: String? = null
            val provider =
                provider { requestData ->
                    requestCount += 1
                    if (requestData.url.host == "other.example.test") {
                        redirectedAuthorization = requestData.headers[HttpHeaders.Authorization]
                        jsonResponse(completedResponsesBody(validAiOutputJson(testAiAnalysisRequest())))
                    } else {
                        respond(
                            content = "",
                            status = HttpStatusCode.TemporaryRedirect,
                            headers = headersOf(HttpHeaders.Location, "https://other.example.test/v1/responses"),
                        )
                    }
                }

            val result = provider.analyze(testAiProviderConfiguration(), testAiAnalysisRequest())

            assertEquals(AiProviderFailureCode.REDIRECT_BLOCKED, assertIs<AiProviderResult.Failure>(result).code)
            assertEquals(1, requestCount)
            assertNull(redirectedAuthorization)
        }

    /** 请求超时和连接异常必须区分映射，且都不自动重试。 */
    @Test
    fun mapsTimeoutAndIoFailure() =
        runTest {
            val timeoutProvider = provider { requestData -> throw HttpRequestTimeoutException(requestData) }
            val networkProvider = provider { throw IOException("测试网络断开") }

            val timeout = timeoutProvider.analyze(testAiProviderConfiguration(), testAiAnalysisRequest())
            val network = networkProvider.analyze(testAiProviderConfiguration(), testAiAnalysisRequest())

            assertEquals(AiProviderFailureCode.TIMEOUT, assertIs<AiProviderResult.Failure>(timeout).code)
            assertEquals(AiProviderFailureCode.NETWORK_UNAVAILABLE, assertIs<AiProviderResult.Failure>(network).code)
        }

    /** 上层取消协程必须继续传播，不能伪装成模型或网络失败。 */
    @Test
    fun propagatesCallerCancellation() =
        runTest {
            val provider = provider { throw CancellationException("测试取消") }

            assertFailsWith<CancellationException> {
                provider.analyze(testAiProviderConfiguration(), testAiAnalysisRequest())
            }
        }

    /** 非 JSON 或声明超限的成功响应必须在读取和解析前失败关闭。 */
    @Test
    fun rejectsWrongContentTypeAndOversizedResponse() =
        runTest {
            val wrongTypeProvider =
                provider {
                    respond(
                        content = "not-json",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/plain"),
                    )
                }
            val oversizedProvider =
                provider {
                    respond(
                        content = "x".repeat(1024 * 1024 + 1),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val wrongType = wrongTypeProvider.analyze(testAiProviderConfiguration(), testAiAnalysisRequest())
            val oversized = oversizedProvider.analyze(testAiProviderConfiguration(), testAiAnalysisRequest())

            assertEquals(AiProviderFailureCode.INVALID_RESPONSE, assertIs<AiProviderResult.Failure>(wrongType).code)
            assertEquals(AiProviderFailureCode.RESPONSE_TOO_LARGE, assertIs<AiProviderResult.Failure>(oversized).code)
        }

    /** 模型列表只 GET 一次推导出的地址，密钥只进入认证头，结果去重排序并剔除不能通过校验的标识。 */
    @Test
    fun listsModelsWithSingleGet() =
        runTest {
            val catalog = testModelCatalogRequest(baseUrl = "http://192.168.1.20:8317/v1")
            var requestCount = 0
            val provider =
                provider { requestData ->
                    requestCount += 1
                    assertEquals(HttpMethod.Get, requestData.method)
                    assertEquals("http://192.168.1.20:8317/v1/models", requestData.url.toString())
                    assertEquals("Bearer $TEST_AI_API_KEY", requestData.headers[HttpHeaders.Authorization])
                    jsonResponse(
                        """{"object":"list","data":[{"id":"gpt-b"},{"id":"gpt-a"},{"id":"gpt-a"},""" +
                            """{"id":"含空格 的模型"},{"object":"model"}]}""",
                    )
                }

            val result = provider.listModels(catalog)

            assertEquals(1, requestCount)
            assertEquals(listOf("gpt-a", "gpt-b"), assertIs<AiModelCatalogResult.Success>(result).models)
        }

    /** Anthropic 模型列表一次取满一页，并使用 x-api-key 与版本头。 */
    @Test
    fun listsAnthropicModels() =
        runTest {
            val catalog =
                testModelCatalogRequest(
                    apiFormat = AiApiFormat.ANTHROPIC_MESSAGES,
                    baseUrl = "https://api.example.test",
                )
            val provider =
                provider { requestData ->
                    assertEquals("https://api.example.test/v1/models?limit=1000", requestData.url.toString())
                    assertEquals(TEST_AI_API_KEY, requestData.headers["x-api-key"])
                    assertEquals("2023-06-01", requestData.headers["anthropic-version"])
                    jsonResponse(
                        """{"data":[{"type":"model","id":"claude-b","display_name":"B"},""" +
                            """{"type":"model","id":"claude-a","display_name":"A"}],"has_more":false}""",
                    )
                }

            assertEquals(
                listOf("claude-a", "claude-b"),
                assertIs<AiModelCatalogResult.Success>(provider.listModels(catalog)).models,
            )
        }

    /** 鉴权失败、无接口、重定向、空列表和超大响应都明确失败。 */
    @Test
    fun modelListFailuresAreExplicit() =
        runTest {
            val catalog = testModelCatalogRequest()

            suspend fun failureOf(
                status: HttpStatusCode,
                body: String = "{}",
                headers: io.ktor.http.Headers = headersOf(HttpHeaders.ContentType, "application/json"),
            ): AiModelCatalogResult.Failure = assertIs(provider { respond(body, status, headers) }.listModels(catalog))

            assertEquals(AiProviderFailureCode.AUTHENTICATION_FAILED, failureOf(HttpStatusCode.Unauthorized).code)
            assertTrue(failureOf(HttpStatusCode.NotFound).message.contains("手动填写"))
            assertEquals(
                AiProviderFailureCode.REDIRECT_BLOCKED,
                failureOf(HttpStatusCode.Found, headers = headersOf(HttpHeaders.Location, "https://other.test/")).code,
            )
            assertEquals(
                AiProviderFailureCode.INVALID_RESPONSE,
                failureOf(HttpStatusCode.OK, body = """{"data":[]}""").code,
            )
            assertEquals(
                AiProviderFailureCode.RESPONSE_TOO_LARGE,
                failureOf(HttpStatusCode.OK, body = "x".repeat(4 * 1024 * 1024 + 1)).code,
            )
        }

    /** 兼容 data 数组、models 数组和顶层数组三种常见结构。 */
    @Test
    fun parsesCommonModelCatalogShapes() {
        assertEquals(listOf("a-1", "b/2:free"), parseModelCatalog("""{"data":[{"id":"b/2:free"},{"id":"a-1"}]}"""))
        assertEquals(listOf("m1"), parseModelCatalog("""{"models":[{"name":"m1"}]}"""))
        assertEquals(listOf("x", "y"), parseModelCatalog("""["y",{"id":"x"}]"""))
        assertFailsWith<IllegalStateException> { parseModelCatalog("""{"items":[]}""") }
    }

    /** 由生产校验器构造模型列表连接。 */
    private fun testModelCatalogRequest(
        apiFormat: AiApiFormat = AiApiFormat.OPENAI_RESPONSES,
        baseUrl: String = "https://api.example.test/v1",
    ): AiModelCatalogRequest =
        assertIs<AiModelCatalogRequestResult.Success>(
            AiProviderConfigurationValidator().validateModelCatalog(apiFormat, baseUrl, TEST_AI_API_KEY),
        ).request

    /** 创建安装生产 AI 网络策略的 MockEngine 适配器。 */
    private fun provider(
        handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) ->
        io.ktor.client.request.HttpResponseData,
    ): HttpAiAnalysisProvider =
        HttpAiAnalysisProvider(
            httpClient = HttpClient(MockEngine(handler)) { configureAiHttpClient() },
            outputValidator = AiAnalysisOutputValidator(),
        )

    /** 返回标准 JSON Mock 响应。 */
    private fun MockRequestHandleScope.jsonResponse(body: String) =
        respond(
            content = body,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
}

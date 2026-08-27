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

/** Responses AI Provider 单请求网络边界测试。 */
class ResponsesAiAnalysisProviderTest {
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
        val prepared = ResponsesAiRequestBuilder.build(configuration, request)

        assertEquals(prepared.body.encodeToByteArray().size, preview.requestBodyByteCount)
        assertTrue(TEST_AI_API_KEY !in preview.toString())
        assertEquals(configuration.endpointHost, preview.endpointHost)
    }

    /** 鉴权、限流和服务异常必须稳定映射，并且 5xx POST 不得自动重试。 */
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
                            content = "服务端正文包含 $TEST_AI_API_KEY 也不得向用户回显",
                            status = status,
                        )
                    }

                val result = provider.analyze(testAiProviderConfiguration(), testAiAnalysisRequest())
                val failure = assertIs<AiProviderResult.Failure>(result)

                assertEquals(expectedCode, failure.code)
                assertEquals(1, requestCount)
                assertTrue(TEST_AI_API_KEY !in failure.message)
            }
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
                        content = "x".repeat(64 * 1024 + 1),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val wrongType = wrongTypeProvider.analyze(testAiProviderConfiguration(), testAiAnalysisRequest())
            val oversized = oversizedProvider.analyze(testAiProviderConfiguration(), testAiAnalysisRequest())

            assertEquals(AiProviderFailureCode.INVALID_RESPONSE, assertIs<AiProviderResult.Failure>(wrongType).code)
            assertEquals(AiProviderFailureCode.RESPONSE_TOO_LARGE, assertIs<AiProviderResult.Failure>(oversized).code)
        }

    /** 创建安装生产 AI 网络策略的 MockEngine 适配器。 */
    private fun provider(
        handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) ->
        io.ktor.client.request.HttpResponseData,
    ): ResponsesAiAnalysisProvider =
        ResponsesAiAnalysisProvider(
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

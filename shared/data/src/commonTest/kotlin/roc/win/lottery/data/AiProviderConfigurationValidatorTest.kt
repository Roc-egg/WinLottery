package roc.win.lottery.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 用户自备 AI 服务配置安全边界测试。 */
class AiProviderConfigurationValidatorTest {
    /** 合法配置必须规范化展示文本，并在 `toString` 中隐藏密钥。 */
    @Test
    fun acceptsHttpsConfigurationAndRedactsSecret() {
        val result =
            validator.validate(
                displayName = " 个人服务 ",
                apiFormat = AiApiFormat.OPENAI_RESPONSES,
                baseUrl = " https://api.example.test:8443/v1 ",
                model = " model/test-v1 ",
                apiKey = TEST_AI_API_KEY,
            )
        val configuration = assertIs<AiProviderConfigurationResult.Success>(result).configuration

        assertEquals("个人服务", configuration.displayName)
        assertEquals(AiApiFormat.OPENAI_RESPONSES, configuration.apiFormat)
        assertEquals("https://api.example.test:8443/v1/responses", configuration.endpointUrl)
        assertEquals("api.example.test:8443", configuration.endpointHost)
        assertEquals("model/test-v1", configuration.model)
        assertFalse(configuration.toString().contains(TEST_AI_API_KEY))
        assertEquals(true, configuration.toString().contains("<已隐藏>"))
    }

    /** 每种接口格式由 Base URL 推导各自的请求与模型列表地址。 */
    @Test
    fun derivesEndpointsFromBaseUrlForEachFormat() {
        assertEquals(
            "https://api.example.test/v1/chat/completions" to "https://api.example.test/v1/models",
            endpoints(AiApiFormat.OPENAI_CHAT_COMPLETIONS, "https://api.example.test/v1"),
        )
        assertEquals(
            "https://api.example.test/v1/responses" to "https://api.example.test/v1/models",
            endpoints(AiApiFormat.OPENAI_RESPONSES, "https://api.example.test/v1/"),
        )
        assertEquals(
            "https://api.example.test/v1/messages" to "https://api.example.test/v1/models?limit=1000",
            endpoints(AiApiFormat.ANTHROPIC_MESSAGES, "https://api.example.test"),
        )
        // 带子路径的兼容服务：OpenAI 格式直接追加，Anthropic 格式在子路径后追加 /v1。
        assertEquals(
            "https://api.example.test/api/paas/v4/chat/completions" to "https://api.example.test/api/paas/v4/models",
            endpoints(AiApiFormat.OPENAI_CHAT_COMPLETIONS, "https://api.example.test/api/paas/v4"),
        )
        assertEquals(
            "https://api.example.test/anthropic/v1/messages" to
                "https://api.example.test/anthropic/v1/models?limit=1000",
            endpoints(AiApiFormat.ANTHROPIC_MESSAGES, "https://api.example.test/anthropic"),
        )
    }

    /** OpenAI 格式的 Base URL 没有路径时补 /v1；Anthropic 格式已带 /v1 时不重复追加。 */
    @Test
    fun normalizesVersionPath() {
        assertEquals(
            "http://192.168.1.20:11434/v1/chat/completions",
            requestUrl(AiApiFormat.OPENAI_CHAT_COMPLETIONS, "http://192.168.1.20:11434"),
        )
        assertEquals(
            "https://api.example.test/v1/messages",
            requestUrl(AiApiFormat.ANTHROPIC_MESSAGES, "https://api.example.test/v1"),
        )
    }

    /** 直接粘贴完整接口地址时去掉接口路径，兼容早期保存的 Responses 地址。 */
    @Test
    fun acceptsPastedFullEndpoint() {
        assertEquals(
            "https://api.example.test/v1/responses",
            requestUrl(AiApiFormat.OPENAI_RESPONSES, "https://api.example.test/v1/responses"),
        )
        assertEquals(
            "https://api.example.test/v1/chat/completions",
            requestUrl(AiApiFormat.OPENAI_CHAT_COMPLETIONS, "https://api.example.test/v1/responses"),
        )
        assertEquals(
            "https://api.example.test/v1/messages",
            requestUrl(AiApiFormat.ANTHROPIC_MESSAGES, "https://api.example.test/v1/messages"),
        )
        assertEquals(
            "https://api.example.test/v1/responses",
            requestUrl(AiApiFormat.OPENAI_RESPONSES, "https://api.example.test/v1/chat/completions/"),
        )
        assertNull(validator.requestUrlOrNull(AiApiFormat.OPENAI_RESPONSES, "api.example.test/v1"))
    }

    /** HTTP 地址可以使用但标记为未加密；其他协议和缺少协议的地址必须拒绝。 */
    @Test
    fun acceptsHttpButFlagsItUnencrypted() {
        val http =
            assertIs<AiProviderConfigurationResult.Success>(
                validator.validate(
                    "局域网服务",
                    AiApiFormat.OPENAI_RESPONSES,
                    "http://192.168.1.20:8317/v1",
                    "test-model-v1",
                    TEST_AI_API_KEY,
                ),
            ).configuration
        val https = testAiProviderConfiguration()

        assertFalse(http.usesHttps)
        assertEquals("192.168.1.20:8317", http.endpointHost)
        assertTrue(https.usesHttps)
        assertEquals(
            AiProviderConfigurationProblemCode.UNSUPPORTED_SCHEME,
            invalid(baseUrl = "ftp://api.example.test/v1").code,
        )
        assertEquals(
            AiProviderConfigurationProblemCode.INVALID_ENDPOINT,
            invalid(baseUrl = "api.example.test/v1").code,
        )
    }

    /** 模型列表请求带上接口格式与同协议、同主机地址，并校验密钥。 */
    @Test
    fun buildsModelCatalogRequest() {
        val https =
            assertIs<AiModelCatalogRequestResult.Success>(
                validator.validateModelCatalog(
                    AiApiFormat.OPENAI_RESPONSES,
                    "https://api.example.test:8443/v1",
                    TEST_AI_API_KEY,
                ),
            ).request
        val http =
            assertIs<AiModelCatalogRequestResult.Success>(
                validator.validateModelCatalog(
                    AiApiFormat.ANTHROPIC_MESSAGES,
                    "http://192.168.1.20:8317",
                    TEST_AI_API_KEY,
                ),
            ).request

        assertEquals("https://api.example.test:8443/v1/models", https.modelsUrl)
        assertTrue(https.usesHttps)
        assertEquals(AiApiFormat.ANTHROPIC_MESSAGES, http.apiFormat)
        assertEquals("http://192.168.1.20:8317/v1/models?limit=1000", http.modelsUrl)
        assertFalse(http.usesHttps)
        assertFalse(https.toString().contains(TEST_AI_API_KEY))
        assertEquals(
            AiProviderConfigurationProblemCode.INVALID_ENDPOINT,
            assertIs<AiModelCatalogRequestResult.Invalid>(
                validator.validateModelCatalog(AiApiFormat.OPENAI_RESPONSES, "api.example.test/v1", TEST_AI_API_KEY),
            ).code,
        )
        assertEquals(
            AiProviderConfigurationProblemCode.INVALID_API_KEY,
            assertIs<AiModelCatalogRequestResult.Invalid>(
                validator.validateModelCatalog(AiApiFormat.OPENAI_RESPONSES, "https://api.example.test/v1", ""),
            ).code,
        )
    }

    /** 地址中的账号、查询参数和片段都可能改变发送边界，必须拒绝。 */
    @Test
    fun rejectsEndpointAmbiguity() {
        val endpoints =
            listOf(
                "https://user:password@api.example.test/v1",
                "https://api.example.test/v1?route=other",
                "https://api.example.test/v1#fragment",
            )

        endpoints.forEach { endpoint ->
            assertEquals(AiProviderConfigurationProblemCode.INVALID_ENDPOINT, invalid(baseUrl = endpoint).code)
        }
    }

    /** 超长地址必须在 URL 解析前按固定边界拒绝。 */
    @Test
    fun rejectsOversizedEndpoint() {
        assertEquals(
            AiProviderConfigurationProblemCode.INVALID_ENDPOINT,
            invalid(baseUrl = "https://api.example.test/${"x".repeat(2049)}").code,
        )
    }

    /** 展示名、模型和密钥必须分别执行保守文本边界。 */
    @Test
    fun rejectsInvalidTextFields() {
        assertEquals(
            AiProviderConfigurationProblemCode.INVALID_PROVIDER_NAME,
            invalid(displayName = " ").code,
        )
        assertEquals(
            AiProviderConfigurationProblemCode.INVALID_MODEL,
            invalid(model = "model with spaces").code,
        )
        assertEquals(
            AiProviderConfigurationProblemCode.INVALID_API_KEY,
            invalid(apiKey = "secret\nheader").code,
        )
    }

    /** 返回指定格式与 Base URL 推导出的请求地址和模型列表地址。 */
    private fun endpoints(
        apiFormat: AiApiFormat,
        baseUrl: String,
    ): Pair<String, String> {
        val configuration =
            assertIs<AiProviderConfigurationResult.Success>(
                validator.validate("测试服务", apiFormat, baseUrl, "test-model-v1", TEST_AI_API_KEY),
            ).configuration
        val catalog =
            assertIs<AiModelCatalogRequestResult.Success>(
                validator.validateModelCatalog(apiFormat, baseUrl, TEST_AI_API_KEY),
            ).request
        return configuration.endpointUrl to catalog.modelsUrl
    }

    /** 返回界面会展示的实际请求地址，并要求与校验结果一致。 */
    private fun requestUrl(
        apiFormat: AiApiFormat,
        baseUrl: String,
    ): String? {
        val shown = validator.requestUrlOrNull(apiFormat, baseUrl)
        assertEquals(endpoints(apiFormat, baseUrl).first, shown)
        return shown
    }

    /** 使用默认合法字段替换单个待测配置值。 */
    private fun invalid(
        displayName: String = "测试服务",
        baseUrl: String = "https://api.example.test/v1",
        model: String = "test-model-v1",
        apiKey: String = TEST_AI_API_KEY,
    ): AiProviderConfigurationResult.Invalid =
        assertIs<AiProviderConfigurationResult.Invalid>(
            validator.validate(displayName, AiApiFormat.OPENAI_RESPONSES, baseUrl, model, apiKey),
        )

    /** 被测配置校验器。 */
    private val validator = AiProviderConfigurationValidator()
}

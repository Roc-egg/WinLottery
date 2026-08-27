package roc.win.lottery.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** 用户自备 AI 服务配置安全边界测试。 */
class AiProviderConfigurationValidatorTest {
    /** 合法配置必须规范化展示文本，并在 `toString` 中隐藏密钥。 */
    @Test
    fun acceptsHttpsConfigurationAndRedactsSecret() {
        val result =
            validator.validate(
                displayName = " 个人服务 ",
                endpointUrl = " https://api.example.test:8443/v1/responses ",
                model = " model/test-v1 ",
                apiKey = TEST_AI_API_KEY,
            )
        val configuration = assertIs<AiProviderConfigurationResult.Success>(result).configuration

        assertEquals("个人服务", configuration.displayName)
        assertEquals("api.example.test:8443", configuration.endpointHost)
        assertEquals("model/test-v1", configuration.model)
        assertFalse(configuration.toString().contains(TEST_AI_API_KEY))
        assertEquals(true, configuration.toString().contains("<已隐藏>"))
    }

    /** HTTP 地址必须作为不安全端点拒绝。 */
    @Test
    fun rejectsInsecureEndpoint() {
        assertEquals(
            AiProviderConfigurationProblemCode.INSECURE_ENDPOINT,
            invalid(endpointUrl = "http://api.example.test/v1/responses").code,
        )
    }

    /** 地址中的账号、查询参数和片段都可能改变发送边界，必须拒绝。 */
    @Test
    fun rejectsEndpointAmbiguity() {
        val endpoints =
            listOf(
                "https://user:password@api.example.test/v1/responses",
                "https://api.example.test/v1/responses?route=other",
                "https://api.example.test/v1/responses#fragment",
            )

        endpoints.forEach { endpoint ->
            assertEquals(AiProviderConfigurationProblemCode.INVALID_ENDPOINT, invalid(endpointUrl = endpoint).code)
        }
    }

    /** 超长地址必须在 URL 解析前按固定边界拒绝。 */
    @Test
    fun rejectsOversizedEndpoint() {
        assertEquals(
            AiProviderConfigurationProblemCode.INVALID_ENDPOINT,
            invalid(endpointUrl = "https://api.example.test/${"x".repeat(2049)}").code,
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

    /** 使用默认合法字段替换单个待测配置值。 */
    private fun invalid(
        displayName: String = "测试服务",
        endpointUrl: String = "https://api.example.test/v1/responses",
        model: String = "test-model-v1",
        apiKey: String = TEST_AI_API_KEY,
    ): AiProviderConfigurationResult.Invalid =
        assertIs<AiProviderConfigurationResult.Invalid>(
            validator.validate(displayName, endpointUrl, model, apiKey),
        )

    /** 被测配置校验器。 */
    private val validator = AiProviderConfigurationValidator()
}

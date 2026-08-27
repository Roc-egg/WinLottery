package roc.win.lottery.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

/** 使用 Android OkHttp 引擎创建统一配置的客户端。 */
actual fun createPlatformHttpClient(): HttpClient = HttpClient(OkHttp) { configureLotteryHttpClient() }

/** 使用 Android OkHttp 引擎创建禁止重定向和重试的 AI 客户端。 */
internal actual fun createPlatformAiHttpClient(): HttpClient =
    HttpClient(OkHttp) {
        configureAiHttpClient()
        engine {
            config {
                retryOnConnectionFailure(false)
            }
        }
    }

package roc.win.lottery.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

/** 使用桌面 JVM OkHttp 引擎创建统一配置的客户端。 */
actual fun createPlatformHttpClient(): HttpClient = HttpClient(OkHttp) { configureLotteryHttpClient() }

package roc.win.lottery.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

/** 使用 iOS Darwin 引擎创建统一配置的客户端。 */
actual fun createPlatformHttpClient(): HttpClient = HttpClient(Darwin) { configureLotteryHttpClient() }

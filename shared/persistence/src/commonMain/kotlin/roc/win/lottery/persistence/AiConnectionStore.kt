package roc.win.lottery.persistence

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 只保存在本机的 AI 连接配置。
 *
 * @property providerName 用户填写的服务商展示名称。
 * @property baseUrl 用户填写的 Base URL；沿用早期版本的 `endpointUrl` 存储键，早期保存的完整接口地址会在校验时去掉接口路径。
 * @property model 用户填写的模型标识。
 * @property apiKey 用户的服务商密钥；平台不能加密保存密钥时始终为空。
 * @property apiFormat 接口格式枚举名；早期版本没有此字段，为空时按 OpenAI Responses 处理。
 * @property maxOutputTokens 输出 token 上限；早期版本没有此字段，为 0 或不在可选范围时使用默认值。
 */
@Serializable
data class SavedAiConnection(
    val providerName: String = "",
    @SerialName("endpointUrl")
    val baseUrl: String = "",
    val model: String = "",
    val apiKey: String = "",
    val apiFormat: String = "",
    val maxOutputTokens: Int = 0,
) {
    /** 是否没有任何已填写的字段；只选了接口格式不算已填写。 */
    val isEmpty: Boolean
        get() = providerName.isEmpty() && baseUrl.isEmpty() && model.isEmpty() && apiKey.isEmpty()

    /** 不输出密钥，避免日志或断言信息泄露。 */
    override fun toString(): String =
        "SavedAiConnection(providerName=$providerName, apiFormat=$apiFormat, baseUrl=$baseUrl, model=$model, " +
            "maxOutputTokens=$maxOutputTokens, " +
            "apiKey=${if (apiKey.isEmpty()) "未填写" else "已隐藏"})"
}

/**
 * 只保存在本机的 AI 连接配置仓库。
 *
 * 配置不写入票据数据库、导出包或云端备份。读取失败或数据损坏时返回 `null` 并丢弃损坏内容，
 * 不得阻断 AI 页面；写入失败时抛出异常，由调用方决定是否提示。
 */
interface AiConnectionStore {
    /** 当前平台能否加密保存密钥；为 `false` 时只保存服务商、接口格式、Base URL 和模型。 */
    val savesApiKey: Boolean

    /** 读取本机保存的配置；从未保存或已清除时为 `null`。 */
    suspend fun load(): SavedAiConnection?

    /** 覆盖保存配置；全部字段为空时等同于清除。 */
    suspend fun save(connection: SavedAiConnection)

    /** 删除本机保存的配置和平台为它创建的加密密钥。 */
    suspend fun clear()
}

/** 配置序列化格式；忽略未知字段，便于以后增加字段时向前兼容。 */
private val aiConnectionJson = Json { ignoreUnknownKeys = true }

/** 把配置编码为平台加密前的 JSON 文本。 */
internal fun SavedAiConnection.encode(): String = aiConnectionJson.encodeToString(SavedAiConnection.serializer(), this)

/** 解码平台解密后的 JSON 文本；格式损坏时返回 `null`。 */
internal fun decodeSavedAiConnection(text: String): SavedAiConnection? =
    runCatching { aiConnectionJson.decodeFromString(SavedAiConnection.serializer(), text) }.getOrNull()

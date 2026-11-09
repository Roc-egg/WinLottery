package roc.win.lottery.persistence

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 创建用 Android Keystore 加密、只保存在应用私有目录的 AI 连接配置仓库。 */
fun createAndroidAiConnectionStore(context: Context): AiConnectionStore =
    AndroidAiConnectionStore(context.applicationContext)

/**
 * Android 本机 AI 连接配置仓库。
 *
 * 整份配置用 Android Keystore 中不可导出的 AES-256-GCM 密钥加密后写入应用私有偏好设置；
 * 应用已禁用系统备份，密文不会进入云端备份，换机或清除数据后无法解密。
 */
internal class AndroidAiConnectionStore(
    /** 应用级上下文。 */
    private val context: Context,
) : AiConnectionStore {
    override val savesApiKey: Boolean = true

    override suspend fun load(): SavedAiConnection? =
        withContext(Dispatchers.IO) {
            val stored = preferences().getString(PAYLOAD_KEY, null) ?: return@withContext null
            val connection = runCatching { decrypt(stored) }.getOrNull()?.let(::decodeSavedAiConnection)
            // 系统重置密钥库或数据损坏时无法解密，丢弃旧内容，让用户重新填写。
            if (connection == null) clearBlocking()
            connection
        }

    override suspend fun save(connection: SavedAiConnection) {
        if (connection.isEmpty) {
            clear()
            return
        }
        withContext(Dispatchers.IO) {
            val payload = encrypt(connection.encode())
            check(preferences().edit().putString(PAYLOAD_KEY, payload).commit()) { "无法保存 AI 连接配置" }
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) { clearBlocking() }
    }

    /** 删除密文和对应的 Keystore 密钥。 */
    private fun clearBlocking() {
        preferences().edit().remove(PAYLOAD_KEY).commit()
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    /** 加密明文，返回“初始向量 + 密文”的 Base64 文本。 */
    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val cipherText = cipher.doFinal(plainText.encodeToByteArray())
        return Base64.encodeToString(cipher.iv + cipherText, Base64.NO_WRAP)
    }

    /** 解密 [encrypt] 生成的文本。 */
    private fun decrypt(payload: String): String {
        val bytes = Base64.decode(payload, Base64.NO_WRAP)
        require(bytes.size > GCM_IV_LENGTH) { "AI 连接配置密文长度无效" }
        val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey ?: error("AI 连接配置密钥不存在")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, bytes, 0, GCM_IV_LENGTH))
        return cipher.doFinal(bytes, GCM_IV_LENGTH, bytes.size - GCM_IV_LENGTH).decodeToString()
    }

    /** 读取已有密钥，不存在时在 Keystore 中生成。 */
    private fun secretKey(): SecretKey {
        (keyStore().getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    /** 已加载的 Android Keystore。 */
    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    /** 应用私有偏好设置。 */
    private fun preferences() = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** Keystore 与存储常量。 */
    private companion object {
        /** Android Keystore 提供方名称。 */
        const val ANDROID_KEY_STORE = "AndroidKeyStore"

        /** 本仓库专用的 Keystore 密钥别名。 */
        const val KEY_ALIAS = "winlottery_ai_connection"

        /** 偏好设置文件名。 */
        const val PREFERENCES_NAME = "winlottery_ai_connection"

        /** 密文在偏好设置中的键名。 */
        const val PAYLOAD_KEY = "encrypted_connection"

        /** 加密算法。 */
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** AES 密钥长度。 */
        const val AES_KEY_SIZE_BITS = 256

        /** GCM 初始向量长度。 */
        const val GCM_IV_LENGTH = 12

        /** GCM 认证标签长度。 */
        const val GCM_TAG_LENGTH_BITS = 128
    }
}

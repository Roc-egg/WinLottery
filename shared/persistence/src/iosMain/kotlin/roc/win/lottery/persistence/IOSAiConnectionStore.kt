package roc.win.lottery.persistence

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.memcpy

/** 创建保存在 iOS 钥匙串、仅本设备可用的 AI 连接配置仓库。 */
fun createIOSAiConnectionStore(): AiConnectionStore = IOSAiConnectionStore()

/**
 * iOS 本机 AI 连接配置仓库。
 *
 * 整份配置作为一条通用密码保存在钥匙串，访问级别为“设备解锁后、仅本设备”：不随 iCloud 钥匙串同步，
 * 也不会通过备份迁移到其他设备。
 *
 * @property service 钥匙串条目的服务名；测试使用独立服务名，避免影响应用数据。
 */
@OptIn(ExperimentalForeignApi::class)
internal class IOSAiConnectionStore(
    private val service: String = KEYCHAIN_SERVICE,
) : AiConnectionStore {
    override val savesApiKey: Boolean = true

    override suspend fun load(): SavedAiConnection? =
        withContext(Dispatchers.Default) {
            val bytes = readItem() ?: return@withContext null
            val connection = decodeSavedAiConnection(bytes.decodeToString())
            if (connection == null) deleteItem()
            connection
        }

    override suspend fun save(connection: SavedAiConnection) {
        if (connection.isEmpty) {
            clear()
            return
        }
        withContext(Dispatchers.Default) {
            deleteItem()
            val status = addItem(connection.encode().encodeToByteArray())
            check(status == errSecSuccess) { "无法把 AI 连接配置保存到钥匙串：$status" }
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.Default) { deleteItem() }
    }

    /** 读取条目数据；不存在或读取失败时为 `null`。 */
    private fun readItem(): ByteArray? =
        memScoped {
            val result = alloc<CFTypeRefVar>()
            val status =
                withItemQuery(kSecReturnData to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitOne) { query ->
                    SecItemCopyMatching(query, result.ptr)
                }
            if (status != errSecSuccess) return@memScoped null
            val data = CFBridgingRelease(result.value) as? NSData ?: return@memScoped null
            ByteArray(data.length.toInt()).also { bytes ->
                if (bytes.isNotEmpty()) {
                    bytes.usePinned { pinned ->
                        memcpy(pinned.addressOf(0), data.bytes, data.length)
                    }
                }
            }
        }

    /** 新增条目，返回钥匙串状态码。 */
    private fun addItem(bytes: ByteArray): Int {
        val data =
            bytes.usePinned { pinned ->
                NSData.create(bytes = pinned.addressOf(0), length = bytes.size.convert())
            }
        val dataRef = CFBridgingRetain(data)
        return try {
            withItemQuery(
                kSecValueData to dataRef,
                kSecAttrAccessible to kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            ) { query -> SecItemAdd(query, null) }
        } finally {
            CFRelease(dataRef)
        }
    }

    /** 删除条目；条目不存在不算失败。 */
    private fun deleteItem() {
        val status = withItemQuery { query -> SecItemDelete(query) }
        check(status == errSecSuccess || status == errSecItemNotFound) { "无法删除钥匙串中的 AI 连接配置：$status" }
    }

    /** 以本仓库条目的类别、服务名和账户名为基础，追加属性后执行钥匙串调用。 */
    private fun <T> withItemQuery(
        vararg extra: Pair<CFStringRef?, CFTypeRef?>,
        block: (CFDictionaryRef?) -> T,
    ): T {
        val serviceRef = CFBridgingRetain(service)
        val accountRef = CFBridgingRetain(KEYCHAIN_ACCOUNT)
        try {
            val entries =
                listOf<Pair<CFStringRef?, CFTypeRef?>>(
                    kSecClass to kSecClassGenericPassword,
                    kSecAttrService to serviceRef,
                    kSecAttrAccount to accountRef,
                ) + extra
            val query =
                memScoped {
                    CFDictionaryCreate(
                        allocator = null,
                        keys = allocArrayOf(entries.map { it.first }).reinterpret(),
                        values = allocArrayOf(entries.map { it.second }).reinterpret(),
                        numValues = entries.size.convert(),
                        keyCallBacks = kCFTypeDictionaryKeyCallBacks.ptr,
                        valueCallBacks = kCFTypeDictionaryValueCallBacks.ptr,
                    )
                }
            try {
                return block(query)
            } finally {
                CFRelease(query)
            }
        } finally {
            CFRelease(serviceRef)
            CFRelease(accountRef)
        }
    }

    /** 钥匙串条目标识。 */
    private companion object {
        /** 应用 AI 连接配置的服务名。 */
        const val KEYCHAIN_SERVICE = "roc.win.lottery.ai-connection"

        /** 唯一配置条目的账户名。 */
        const val KEYCHAIN_ACCOUNT = "default"
    }
}

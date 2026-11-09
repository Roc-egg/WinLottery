package roc.win.lottery.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/** 在桌面应用数据目录中创建 AI 连接配置仓库。 */
fun createJvmAiConnectionStore(applicationDataDirectory: File): AiConnectionStore =
    JvmAiConnectionStore(File(applicationDataDirectory.absoluteFile, AI_CONNECTION_FILE_NAME))

/**
 * 桌面本机 AI 连接配置仓库。
 *
 * JVM 没有跨 macOS 与 Windows 的系统密钥存储，不能安全保存密钥，因此只保存服务商、地址和模型，
 * 密钥只在本次运行有效。支持 POSIX 权限的系统上文件仅当前用户可读写。
 *
 * @property file 配置文件位置。
 */
internal class JvmAiConnectionStore(
    private val file: File,
) : AiConnectionStore {
    override val savesApiKey: Boolean = false

    override suspend fun load(): SavedAiConnection? =
        withContext(Dispatchers.IO) {
            if (!file.isFile) return@withContext null
            val connection = runCatching { decodeSavedAiConnection(file.readText()) }.getOrNull()
            if (connection == null) file.delete()
            connection?.copy(apiKey = "")
        }

    override suspend fun save(connection: SavedAiConnection) {
        val withoutKey = connection.copy(apiKey = "")
        if (withoutKey.isEmpty) {
            clear()
            return
        }
        withContext(Dispatchers.IO) {
            val directory = checkNotNull(file.parentFile) { "AI 连接配置文件缺少目录" }
            check(directory.isDirectory || directory.mkdirs()) { "无法创建 AI 连接配置目录" }
            val temporary = File(directory, "$AI_CONNECTION_FILE_NAME.tmp")
            temporary.writeText(withoutKey.encode())
            restrictToOwner(temporary)
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) { file.delete() }
    }

    /** 在支持 POSIX 权限的系统上把文件限制为仅当前用户可读写。 */
    private fun restrictToOwner(target: File) {
        runCatching {
            Files.setPosixFilePermissions(
                target.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }
}

/** 桌面 AI 连接配置文件名。 */
private const val AI_CONNECTION_FILE_NAME = "ai-connection.json"

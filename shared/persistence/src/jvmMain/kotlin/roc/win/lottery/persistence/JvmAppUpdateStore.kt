package roc.win.lottery.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/** 在桌面应用数据目录中创建版本更新偏好仓库。 */
fun createJvmAppUpdateStore(applicationDataDirectory: File): AppUpdateStore =
    JvmAppUpdateStore(File(applicationDataDirectory.absoluteFile, APP_UPDATE_FILE_NAME))

/**
 * 桌面本机版本更新偏好仓库，使用键值文本文件并以原子替换写入。
 *
 * @property file 偏好文件位置。
 */
internal class JvmAppUpdateStore(
    private val file: File,
) : AppUpdateStore {
    override fun load(): AppUpdatePreferences {
        val properties =
            runCatching {
                Properties().apply { file.takeIf { it.isFile }?.reader(Charsets.UTF_8)?.use(::load) }
            }.getOrNull() ?: return AppUpdatePreferences()
        return AppUpdatePreferences(
            autoCheckEnabled = properties.getProperty(AUTO_CHECK_KEY) != false.toString(),
            lastCheckedAtEpochMillis = properties.getProperty(LAST_CHECKED_KEY)?.toLongOrNull(),
            skippedVersion = properties.getProperty(SKIPPED_VERSION_KEY)?.takeIf { it.isNotBlank() },
        )
    }

    override suspend fun save(preferences: AppUpdatePreferences) {
        withContext(Dispatchers.IO) {
            val directory = checkNotNull(file.parentFile) { "版本更新偏好文件缺少目录" }
            check(directory.isDirectory || directory.mkdirs()) { "无法创建版本更新偏好目录" }
            val properties = Properties()
            properties.setProperty(AUTO_CHECK_KEY, preferences.autoCheckEnabled.toString())
            preferences.lastCheckedAtEpochMillis?.let { properties.setProperty(LAST_CHECKED_KEY, it.toString()) }
            preferences.skippedVersion?.let { properties.setProperty(SKIPPED_VERSION_KEY, it) }
            val temporary = File(directory, "$APP_UPDATE_FILE_NAME.tmp")
            temporary.writer(Charsets.UTF_8).use { writer -> properties.store(writer, null) }
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }
    }
}

/** 版本更新偏好文件名。 */
private const val APP_UPDATE_FILE_NAME = "app-update.properties"

/** 自动检查开关键名。 */
private const val AUTO_CHECK_KEY = "autoCheck"

/** 最近成功检查时间键名。 */
private const val LAST_CHECKED_KEY = "lastCheckedAt"

/** 跳过的版本号键名。 */
private const val SKIPPED_VERSION_KEY = "skippedVersion"

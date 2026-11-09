package roc.win.lottery.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 在桌面应用数据目录中创建外观偏好仓库。 */
fun createJvmAppearanceStore(applicationDataDirectory: File): AppearanceStore =
    JvmAppearanceStore(File(applicationDataDirectory.absoluteFile, APPEARANCE_FILE_NAME))

/**
 * 桌面本机外观偏好仓库，文件只保存一个主题编码。
 *
 * @property file 偏好文件位置。
 */
internal class JvmAppearanceStore(
    private val file: File,
) : AppearanceStore {
    override fun loadThemePreference(): ThemePreference =
        ThemePreference.fromStorageCode(runCatching { file.takeIf { it.isFile }?.readText()?.trim() }.getOrNull())

    override suspend fun saveThemePreference(preference: ThemePreference) {
        withContext(Dispatchers.IO) {
            val directory = checkNotNull(file.parentFile) { "外观偏好文件缺少目录" }
            check(directory.isDirectory || directory.mkdirs()) { "无法创建外观偏好目录" }
            val temporary = File(directory, "$APPEARANCE_FILE_NAME.tmp")
            temporary.writeText(preference.storageCode)
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }
    }
}

/** 外观偏好文件名。 */
private const val APPEARANCE_FILE_NAME = "appearance.txt"

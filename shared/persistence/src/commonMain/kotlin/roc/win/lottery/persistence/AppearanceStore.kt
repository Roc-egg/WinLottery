package roc.win.lottery.persistence

/**
 * 应用外观主题偏好。
 *
 * @property storageCode 写入本机存储的稳定编码，不随枚举名变化。
 */
enum class ThemePreference(
    val storageCode: String,
) {
    /** 跟随系统深浅色外观。 */
    SYSTEM("system"),

    /** 始终使用浅色外观。 */
    LIGHT("light"),

    /** 始终使用深色外观。 */
    DARK("dark"),
    ;

    /** 解析本机存储编码。 */
    companion object {
        /**
         * 把存储编码还原为主题偏好。
         *
         * @param code 本机读取到的编码；从未保存时为 `null`。
         * @return 对应的主题偏好；从未保存或内容无法识别时为跟随系统。
         */
        fun fromStorageCode(code: String?): ThemePreference = entries.firstOrNull { it.storageCode == code } ?: SYSTEM
    }
}

/**
 * 本机外观偏好仓库，只保存主题模式这一项非敏感设置。
 *
 * 读取是同步的：应用启动时在首帧之前就确定深浅色，避免先按系统外观绘制再闪烁切换。
 */
interface AppearanceStore {
    /** 读取本机保存的主题偏好；从未保存或内容无法识别时为跟随系统。 */
    fun loadThemePreference(): ThemePreference

    /**
     * 覆盖保存主题偏好。
     *
     * @param preference 用户选择的主题偏好。
     */
    suspend fun saveThemePreference(preference: ThemePreference)
}

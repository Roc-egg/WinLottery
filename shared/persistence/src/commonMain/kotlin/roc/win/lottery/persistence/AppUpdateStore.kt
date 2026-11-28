package roc.win.lottery.persistence

/**
 * 版本更新检查的本机偏好。
 *
 * @property autoCheckEnabled 启动时是否自动检查新版本；默认开启。
 * @property lastCheckedAtEpochMillis 最近一次成功取得发布信息的时间；从未成功时为 `null`。
 * @property skippedVersion 用户选择跳过、自动检查不再提示的版本号；没有时为 `null`。
 */
data class AppUpdatePreferences(
    val autoCheckEnabled: Boolean = true,
    val lastCheckedAtEpochMillis: Long? = null,
    val skippedVersion: String? = null,
)

/**
 * 本机版本更新偏好仓库，只保存开关、检查时间和跳过的版本号这类非敏感设置。
 *
 * 读取是同步的，应用启动时直接决定是否需要联网检查。
 */
interface AppUpdateStore {
    /** 读取本机保存的偏好；从未保存或内容无法识别的字段使用默认值。 */
    fun load(): AppUpdatePreferences

    /**
     * 覆盖保存偏好。
     *
     * @param preferences 完整的偏好内容。
     */
    suspend fun save(preferences: AppUpdatePreferences)
}

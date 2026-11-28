package roc.win.lottery.persistence

import platform.Foundation.NSUserDefaults

/** 创建保存在 iOS 用户默认设置中的版本更新偏好仓库。 */
fun createIOSAppUpdateStore(): AppUpdateStore = IOSAppUpdateStore(NSUserDefaults.standardUserDefaults)

/**
 * iOS 本机版本更新偏好仓库。
 *
 * 各字段都以字符串保存，缺失或无法识别时使用默认值。
 *
 * @property defaults 用户默认设置；测试使用独立套件，避免影响应用数据。
 */
internal class IOSAppUpdateStore(
    private val defaults: NSUserDefaults,
) : AppUpdateStore {
    override fun load(): AppUpdatePreferences =
        AppUpdatePreferences(
            autoCheckEnabled = defaults.stringForKey(AUTO_CHECK_KEY) != AUTO_CHECK_DISABLED,
            lastCheckedAtEpochMillis = defaults.stringForKey(LAST_CHECKED_KEY)?.toLongOrNull(),
            skippedVersion = defaults.stringForKey(SKIPPED_VERSION_KEY)?.takeIf { it.isNotBlank() },
        )

    override suspend fun save(preferences: AppUpdatePreferences) {
        defaults.setObject(
            if (preferences.autoCheckEnabled) AUTO_CHECK_ENABLED else AUTO_CHECK_DISABLED,
            forKey = AUTO_CHECK_KEY,
        )
        preferences.lastCheckedAtEpochMillis
            ?.let { defaults.setObject(it.toString(), forKey = LAST_CHECKED_KEY) }
            ?: defaults.removeObjectForKey(LAST_CHECKED_KEY)
        preferences.skippedVersion
            ?.let { defaults.setObject(it, forKey = SKIPPED_VERSION_KEY) }
            ?: defaults.removeObjectForKey(SKIPPED_VERSION_KEY)
    }

    /** 用户默认设置中的键名与取值。 */
    private companion object {
        /** 自动检查开关键名。 */
        const val AUTO_CHECK_KEY = "roc.win.lottery.update.autoCheck"

        /** 最近成功检查时间键名。 */
        const val LAST_CHECKED_KEY = "roc.win.lottery.update.lastCheckedAt"

        /** 跳过的版本号键名。 */
        const val SKIPPED_VERSION_KEY = "roc.win.lottery.update.skippedVersion"

        /** 自动检查开启。 */
        const val AUTO_CHECK_ENABLED = "on"

        /** 自动检查关闭。 */
        const val AUTO_CHECK_DISABLED = "off"
    }
}

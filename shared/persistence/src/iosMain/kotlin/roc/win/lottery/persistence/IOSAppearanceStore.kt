package roc.win.lottery.persistence

import platform.Foundation.NSUserDefaults

/** 创建保存在 iOS 用户默认设置中的外观偏好仓库。 */
fun createIOSAppearanceStore(): AppearanceStore = IOSAppearanceStore(NSUserDefaults.standardUserDefaults)

/**
 * iOS 本机外观偏好仓库。
 *
 * 主题偏好不含个人或投注信息，直接保存在用户默认设置中。
 *
 * @property defaults 用户默认设置；测试使用独立套件，避免影响应用数据。
 */
internal class IOSAppearanceStore(
    private val defaults: NSUserDefaults,
) : AppearanceStore {
    override fun loadThemePreference(): ThemePreference =
        ThemePreference.fromStorageCode(defaults.stringForKey(THEME_KEY))

    override suspend fun saveThemePreference(preference: ThemePreference) {
        defaults.setObject(preference.storageCode, forKey = THEME_KEY)
    }

    /** 用户默认设置中的键名。 */
    private companion object {
        /** 主题偏好键名。 */
        const val THEME_KEY = "roc.win.lottery.appearance.theme"
    }
}

package roc.win.lottery.persistence

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 创建保存在应用私有偏好设置中的外观偏好仓库。 */
fun createAndroidAppearanceStore(context: Context): AppearanceStore = AndroidAppearanceStore(context.applicationContext)

/**
 * Android 本机外观偏好仓库，使用应用私有偏好设置；应用已禁用系统备份，偏好不会进入云端备份。
 *
 * @property context 应用级上下文。
 */
internal class AndroidAppearanceStore(
    private val context: Context,
) : AppearanceStore {
    override fun loadThemePreference(): ThemePreference =
        ThemePreference.fromStorageCode(preferences().getString(THEME_KEY, null))

    override suspend fun saveThemePreference(preference: ThemePreference) {
        withContext(Dispatchers.IO) {
            check(preferences().edit().putString(THEME_KEY, preference.storageCode).commit()) { "无法保存主题偏好" }
        }
    }

    /** 外观偏好专用的私有偏好设置文件。 */
    private fun preferences() = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** 偏好设置文件名与键名。 */
    private companion object {
        /** 偏好设置文件名。 */
        const val PREFERENCES_NAME = "winlottery_appearance"

        /** 主题偏好键名。 */
        const val THEME_KEY = "theme"
    }
}

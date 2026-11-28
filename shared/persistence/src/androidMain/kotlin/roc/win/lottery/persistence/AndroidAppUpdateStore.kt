package roc.win.lottery.persistence

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 创建保存在应用私有偏好设置中的版本更新偏好仓库。 */
fun createAndroidAppUpdateStore(context: Context): AppUpdateStore = AndroidAppUpdateStore(context.applicationContext)

/**
 * Android 本机版本更新偏好仓库，使用应用私有偏好设置；应用已禁用系统备份。
 *
 * @property context 应用级上下文。
 */
internal class AndroidAppUpdateStore(
    private val context: Context,
) : AppUpdateStore {
    override fun load(): AppUpdatePreferences {
        val preferences = preferences()
        return AppUpdatePreferences(
            autoCheckEnabled = preferences.getBoolean(AUTO_CHECK_KEY, true),
            lastCheckedAtEpochMillis =
                if (preferences.contains(LAST_CHECKED_KEY)) preferences.getLong(LAST_CHECKED_KEY, 0L) else null,
            skippedVersion = preferences.getString(SKIPPED_VERSION_KEY, null),
        )
    }

    override suspend fun save(preferences: AppUpdatePreferences) {
        withContext(Dispatchers.IO) {
            val editor =
                preferences()
                    .edit()
                    .putBoolean(AUTO_CHECK_KEY, preferences.autoCheckEnabled)
            preferences.lastCheckedAtEpochMillis
                ?.let { editor.putLong(LAST_CHECKED_KEY, it) }
                ?: editor.remove(LAST_CHECKED_KEY)
            preferences.skippedVersion
                ?.let { editor.putString(SKIPPED_VERSION_KEY, it) }
                ?: editor.remove(SKIPPED_VERSION_KEY)
            check(editor.commit()) { "无法保存版本更新偏好" }
        }
    }

    /** 版本更新偏好专用的私有偏好设置文件。 */
    private fun preferences() = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** 偏好设置文件名与键名。 */
    private companion object {
        /** 偏好设置文件名。 */
        const val PREFERENCES_NAME = "winlottery_app_update"

        /** 自动检查开关键名。 */
        const val AUTO_CHECK_KEY = "auto_check"

        /** 最近成功检查时间键名。 */
        const val LAST_CHECKED_KEY = "last_checked_at"

        /** 跳过的版本号键名。 */
        const val SKIPPED_VERSION_KEY = "skipped_version"
    }
}

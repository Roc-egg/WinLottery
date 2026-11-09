package roc.win.lottery.persistence

import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** 桌面本机外观偏好仓库测试。 */
class JvmAppearanceStoreTest {
    /** 每个测试独立的应用数据目录。 */
    private val directory: File = createTempDirectory("winlottery-appearance").toFile()

    /** 删除测试目录。 */
    @AfterTest
    fun deleteDirectory() {
        directory.deleteRecursively()
    }

    /** 从未保存时跟随系统；保存后新建仓库仍能读回同一偏好。 */
    @Test
    fun defaultsToSystemAndPersistsChoice() =
        runTest {
            assertEquals(ThemePreference.SYSTEM, createJvmAppearanceStore(directory).loadThemePreference())
            createJvmAppearanceStore(directory).saveThemePreference(ThemePreference.DARK)
            assertEquals(ThemePreference.DARK, createJvmAppearanceStore(directory).loadThemePreference())
            createJvmAppearanceStore(directory).saveThemePreference(ThemePreference.LIGHT)
            assertEquals(ThemePreference.LIGHT, createJvmAppearanceStore(directory).loadThemePreference())
        }

    /** 文件内容无法识别时回到跟随系统，不抛出异常。 */
    @Test
    fun unknownContentFallsBackToSystem() {
        File(directory, "appearance.txt").writeText("purple")
        assertEquals(ThemePreference.SYSTEM, createJvmAppearanceStore(directory).loadThemePreference())
    }
}

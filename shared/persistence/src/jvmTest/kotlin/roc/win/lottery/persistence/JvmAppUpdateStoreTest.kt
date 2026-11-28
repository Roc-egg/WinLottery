package roc.win.lottery.persistence

import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** 桌面本机版本更新偏好仓库测试。 */
class JvmAppUpdateStoreTest {
    /** 每个测试独立的应用数据目录。 */
    private val directory: File = createTempDirectory("winlottery-app-update").toFile()

    /** 删除测试目录。 */
    @AfterTest
    fun deleteDirectory() {
        directory.deleteRecursively()
    }

    /** 从未保存时默认开启自动检查；保存后新建仓库能读回，清空字段后不残留旧值。 */
    @Test
    fun defaultsAndPersistsPreferences() =
        runTest {
            assertEquals(AppUpdatePreferences(), createJvmAppUpdateStore(directory).load())
            val saved =
                AppUpdatePreferences(
                    autoCheckEnabled = false,
                    lastCheckedAtEpochMillis = 1_764_000_000_000L,
                    skippedVersion = "1.3.9",
                )
            createJvmAppUpdateStore(directory).save(saved)
            assertEquals(saved, createJvmAppUpdateStore(directory).load())
            createJvmAppUpdateStore(directory).save(AppUpdatePreferences(autoCheckEnabled = true))
            assertEquals(AppUpdatePreferences(), createJvmAppUpdateStore(directory).load())
        }

    /** 文件内容无法识别时使用默认值，不抛出异常。 */
    @Test
    fun unreadableContentFallsBackToDefaults() {
        File(directory, "app-update.properties").writeText("autoCheck=maybe\nlastCheckedAt=yesterday\n")
        assertEquals(AppUpdatePreferences(), createJvmAppUpdateStore(directory).load())
    }
}

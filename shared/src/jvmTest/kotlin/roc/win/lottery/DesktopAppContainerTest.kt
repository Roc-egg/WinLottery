package roc.win.lottery

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 桌面真实依赖容器装配测试。 */
class DesktopAppContainerTest {
    /** 桌面预览版应复用本地 OCR、官网、AI、Room 和文件交换能力。 */
    @Test
    fun desktopPreviewContainerUsesSharedProductionCapabilities() =
        runTest {
            val root = Files.createTempDirectory("win-lottery-desktop-container-").toFile()
            val container =
                createDesktopAppContainer(
                    ownerProvider = { null },
                    applicationCommand = listOf("desktop-test-launcher"),
                    applicationDataDirectory = root,
                )
            try {
                assertTrue(container.usesRealImageAcquisition)
                assertTrue(container.usesRealDrawData)
                assertTrue(container.usesRealRecognition)
                assertNotNull(container.historicalDrawRepository)
                assertNotNull(container.aiAnalysisProvider)
                val ticketRecordStore = assertNotNull(container.ticketRecordStore)
                assertNotNull(container.ticketRecordFileExchange)
                assertEquals(emptyList(), ticketRecordStore.records.first())
                assertTrue(File(root, "win-lottery-v1.db").exists())
            } finally {
                container.close()
                root.deleteRecursively()
            }
        }

    /** Windows 应优先把 Room 数据写入当前用户的 LocalAppData。 */
    @Test
    fun windowsUsesLocalApplicationDataDirectory() {
        val userHome = File("C:\\Users\\tester")
        val localAppData = File("C:\\Users\\tester\\AppData\\Local")

        val result =
            resolveDesktopApplicationDataDirectory(
                osName = "Windows 11",
                userHome = userHome,
                environment = { name ->
                    if (name == "LOCALAPPDATA") localAppData else null
                },
            )

        assertEquals(File(localAppData, "roc.win.lottery"), result)
    }

    /** Windows 缺少 LocalAppData 时应依次回退到 AppData 和用户目录。 */
    @Test
    fun windowsDataDirectoryHasSafeFallbacks() {
        val userHome = File("C:\\Users\\tester")
        val appData = File("C:\\Users\\tester\\AppData\\Roaming")

        val roamingResult =
            resolveDesktopApplicationDataDirectory(
                osName = "Windows 10",
                userHome = userHome,
                environment = { name ->
                    if (name == "APPDATA") appData else null
                },
            )
        val homeResult =
            resolveDesktopApplicationDataDirectory(
                osName = "Windows Server 2025",
                userHome = userHome,
                environment = { null },
            )

        assertEquals(File(appData, "roc.win.lottery"), roamingResult)
        assertEquals(File(userHome, "roc.win.lottery"), homeResult)
    }
}

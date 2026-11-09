package roc.win.lottery.app

import kotlinx.coroutines.test.runTest
import roc.win.lottery.Platform
import roc.win.lottery.persistence.AppearanceStore
import roc.win.lottery.persistence.ThemePreference
import kotlin.test.Test
import kotlin.test.assertEquals

/** 主题偏好：启动时读取本机保存值，切换后立即生效并写回本机。 */
class ThemePreferenceControllerTest {
    /** 启动时直接使用本机保存的偏好，首帧不需要等待异步读取。 */
    @Test
    fun loadsSavedPreferenceOnStart() {
        val store = RecordingAppearanceStore(ThemePreference.DARK)
        assertEquals(ThemePreference.DARK, controller(store).themePreference.value)
    }

    /** 切换后立即更新状态并保存；重复选择同一偏好不再写入。 */
    @Test
    fun updatesAndSavesPreference() =
        runTest {
            val store = RecordingAppearanceStore(ThemePreference.SYSTEM)
            val controller = controller(store)
            controller.updateThemePreference(ThemePreference.LIGHT)
            controller.updateThemePreference(ThemePreference.LIGHT)
            controller.updateThemePreference(ThemePreference.DARK)
            assertEquals(ThemePreference.DARK, controller.themePreference.value)
            assertEquals(listOf(ThemePreference.LIGHT, ThemePreference.DARK), store.saved)
        }

    /** 读取失败时跟随系统；写入失败时本次运行仍按新选择显示。 */
    @Test
    fun storeFailuresDoNotBreakTheme() =
        runTest {
            val controller = controller(FailingAppearanceStore)
            assertEquals(ThemePreference.SYSTEM, controller.themePreference.value)
            controller.updateThemePreference(ThemePreference.DARK)
            assertEquals(ThemePreference.DARK, controller.themePreference.value)
        }

    /** 未接入本机存储的平台默认跟随系统，切换只在本次运行有效。 */
    @Test
    fun withoutStoreDefaultsToSystem() =
        runTest {
            val controller = controller(null)
            assertEquals(ThemePreference.SYSTEM, controller.themePreference.value)
            controller.updateThemePreference(ThemePreference.LIGHT)
            assertEquals(ThemePreference.LIGHT, controller.themePreference.value)
        }

    /** 用演示依赖创建带指定外观仓库的控制器。 */
    private fun controller(store: AppearanceStore?): LotteryAppController {
        val demo =
            AppContainer.createDemo(
                object : Platform {
                    /** 测试平台名称。 */
                    override val name: String = "测试平台"

                    /** 测试平台不提供相机。 */
                    override val supportsCamera: Boolean = false
                },
            )
        return LotteryAppController(
            AppContainer(
                platform = demo.platform,
                imageAcquirer = demo.imageAcquirer,
                imageQualityAnalyzer = demo.imageQualityAnalyzer,
                ticketRecognizer = demo.ticketRecognizer,
                ticketParser = demo.ticketParser,
                drawRepository = demo.drawRepository,
                historicalDrawRepository = null,
                prizeCalculator = demo.prizeCalculator,
                appPaths = demo.appPaths,
                ticketValidator = demo.ticketValidator,
                isDemo = true,
                usesRealImageAcquisition = false,
                usesRealRecognition = false,
                usesRealDrawData = false,
                appearanceStore = store,
            ),
        )
    }

    /**
     * 记录写入顺序的内存外观仓库。
     *
     * @property initial 启动时读取到的偏好。
     */
    private class RecordingAppearanceStore(
        private val initial: ThemePreference,
    ) : AppearanceStore {
        /** 按顺序记录的写入值。 */
        val saved = mutableListOf<ThemePreference>()

        override fun loadThemePreference(): ThemePreference = initial

        override suspend fun saveThemePreference(preference: ThemePreference) {
            saved += preference
        }
    }

    /** 读写都失败的外观仓库。 */
    private object FailingAppearanceStore : AppearanceStore {
        override fun loadThemePreference(): ThemePreference = error("读取失败")

        override suspend fun saveThemePreference(preference: ThemePreference): Unit = error("写入失败")
    }
}

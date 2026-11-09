package roc.win.lottery.app.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.use
import roc.win.lottery.app.AiAnalysisSettings
import roc.win.lottery.app.AiAnalysisWorkspaceState
import roc.win.lottery.app.MainDestination
import roc.win.lottery.app.theme.LotteryTheme
import roc.win.lottery.data.AiApiFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 验证 AI 页连接配置的接口格式下拉框与 Base URL 实际请求地址。 */
@OptIn(ExperimentalComposeUiApi::class)
class AiAnalysisScreenRenderTest {
    /** Base URL 下方显示按格式推导的实际请求地址；下拉框列出三种格式，点选后回调新格式。 */
    @Test
    fun selectsApiFormatAndShowsRequestUrl() {
        val changes = mutableListOf<AiAnalysisSettings>()
        ImageComposeScene(390, 2400).use { scene ->
            scene.setContent {
                LotteryTheme {
                    AiAnalysisScreen(
                        workspace =
                            AiAnalysisWorkspaceState(
                                settings = AiAnalysisSettings(baseUrl = "https://api.example.test/v1"),
                            ),
                        availableMainDestinations = MainDestination.entries,
                        onMainDestinationSelected = {},
                        apiKey = "",
                        savesApiKeyLocally = true,
                        onSettingsChange = { settings -> changes += settings },
                        onApiKeyChange = {},
                        onClearSavedConnection = {},
                        onFetchModels = {},
                        onSelectModel = {},
                        onDismissModels = {},
                        onRetryHistory = {},
                        onRefreshHistory = {},
                        onPreparePreview = { false },
                        onDismissPreview = {},
                        onConfirmPreview = {},
                        onCancelRequest = {},
                    )
                }
            }
            scene.settle()
            assertTrue(scene.hasText("接口格式"))
            assertTrue(scene.hasText("Base URL"))
            assertTrue(scene.hasText("实际请求：https://api.example.test/v1/chat/completions"))

            scene.clickText("接口格式")
            AiApiFormat.entries.forEach { format -> assertTrue(scene.hasText(format.displayName), format.name) }
            scene.clickText(AiApiFormat.ANTHROPIC_MESSAGES.displayName)

            assertEquals(AiApiFormat.ANTHROPIC_MESSAGES, changes.last().apiFormat)
            assertEquals("https://api.example.test/v1", changes.last().baseUrl)
        }
    }

    /** 推进足够帧数，让弹出菜单动画完成。 */
    private fun ImageComposeScene.settle() {
        repeat(20) {
            frameTimeNanos += 20_000_000L
            render(frameTimeNanos).close()
        }
    }

    /** 当前渲染时间。 */
    private var frameTimeNanos = 0L

    /** 收集包含合并前语义的全部节点，含弹出菜单。 */
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap { flatten(it) }
        return semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
    }

    /** 节点上的全部文本。 */
    private fun SemanticsNode.texts(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    /** 判断是否存在完整文本。 */
    private fun ImageComposeScene.hasText(text: String): Boolean = nodes().any { text in it.texts() }

    /** 用真实指针事件点击文本所在控件中心。 */
    private fun ImageComposeScene.clickText(text: String) {
        val node = nodes().lastOrNull { text in it.texts() } ?: error("未找到文本 $text")
        val center = node.boundsInWindow.center
        sendPointerEvent(PointerEventType.Press, center)
        sendPointerEvent(PointerEventType.Release, center)
        settle()
    }
}

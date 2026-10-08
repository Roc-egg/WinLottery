package roc.win.lottery.app.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import roc.win.lottery.app.MainDestination
import roc.win.lottery.app.TrendChartAction
import roc.win.lottery.app.TrendChartContent
import roc.win.lottery.app.TrendChartState
import roc.win.lottery.app.theme.LotteryTheme
import roc.win.lottery.domain.HistoricalDraw
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryTrendArea
import roc.win.lottery.domain.LotteryTrendCalculationResult
import roc.win.lottery.domain.LotteryTrendCalculator
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.TrendSampleSize
import java.io.File
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 使用真实 Compose 渲染器离屏验收移动、横屏和桌面尺寸，不操作用户前台应用。 */
@OptIn(ExperimentalComposeUiApi::class)
class TrendScreenRenderTest {
    /** 单调递增的测试帧时钟，避免截图回退到滚动动画之前。 */
    private var frameTimeNanos = 0L

    /** 竖屏首屏显示最近开奖，切换分区、统计追踪与全屏不会丢失数据。 */
    @Test
    fun portraitWorkflowPreservesLatestAndTracksNumber() {
        withScene(390, 844) { scene ->
            scene.capture("portrait-fullscreen")
            assertTrue(scene.hasDescription("第 26150 期"))
            assertTrue(scene.hasDescription("退出全屏"))
            scene.clickText("二区 13-24")
            assertTrue(scene.hasDescription("号码走势矩阵，13至24"))
            scene.clickDescription("追踪号码 17")
            assertTrue(scene.hasText("17号"))
            scene.capture("portrait-zone-tracking")
            val beforeHeader = scene.findDescription("追踪号码 17").boundsInRoot
            scene.sendPointerEvent(PointerEventType.Scroll, Offset(200f, 300f), scrollDelta = Offset(0f, -10f))
            scene.settle()
            assertEquals(beforeHeader, scene.findDescription("追踪号码 17").boundsInRoot)
            scene.clickDescription("定位最新一期")
            assertTrue(scene.hasDescription("第 26150 期"))
            scene.clickText("号码统计")
            scene.clickText("当前遗漏")
            scene.capture("portrait-statistics")
            val item =
                scene.nodes().first {
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { text ->
                        text.startsWith("追踪 ")
                    } ==
                        true
                }
            scene.click(item)
            assertTrue(scene.hasDescription("取消号码追踪"))
            assertTrue(scene.hasDescription("第 26150 期"))
            scene.clickDescription("退出全屏")
            assertTrue(scene.hasDescription("全屏看走势"))
            scene.capture("portrait-with-navigation")
            scene.clickDescription("全屏看走势")
            assertTrue(scene.hasDescription("第 26150 期"))
            scene.clickText("开奖形态")
            scene.capture("portrait-shapes")
            assertTrue(scene.hasDescription("第 26150 期，和值"))
        }
    }

    /** 横屏完整显示首末号码，不需要将数字缩成不可读的九像素。 */
    @Test
    fun landscapeAndDesktopShowCompleteMatrix() {
        for ((width, height) in listOf(844 to 390, 1280 to 800)) {
            withScene(width, height) { scene ->
                scene.capture(if (width == 844) "landscape-fullscreen" else "desktop-fullscreen")
                val first = scene.findDescription("追踪号码 01").boundsInRoot
                val last = scene.findDescription("追踪号码 35").boundsInRoot
                assertTrue(first.left >= 64 && last.right <= width)
                assertTrue(last.width >= 20)
                assertTrue(scene.hasDescription("第 26150 期"))
                if (width == 844) {
                    val rows =
                        scene.nodes().count { node ->
                            val descriptions = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                            descriptions.any { it.startsWith("第 ") }
                        }
                    assertTrue(rows >= 9, "手机横屏应至少容纳九期开奖")
                }
            }
        }
    }

    /** 样本扩到 500 期后仍定位最新；彩种切换清除旧号码追踪并更新区域规则。 */
    @Test
    fun sampleAndLotteryChangesKeepDisplayConsistent() {
        withScene(390, 844) { scene ->
            scene.clickDescription("样本期数")
            scene.clickText("最近 500 期")
            assertTrue(scene.hasText("官方历史开奖 · 500期"))
            assertTrue(scene.hasDescription("第 26150 期"))
            scene.capture("portrait-500-periods")
            scene.clickText("三区 25-35")
            scene.clickDescription("追踪号码 35")
            scene.clickDescription("彩种")
            scene.clickText("双色球")
            assertFalse(scene.hasText("35号"))
            assertTrue(scene.hasDescription("第 2026150 期"))
            scene.clickText("三区 23-33")
            assertTrue(scene.hasDescription("追踪号码 33"))
            scene.capture("portrait-double-color-ball-primary")
            scene.clickDescription("数据来源与统计口径")
            scene.capture("portrait-source-details")
            assertTrue(scene.hasText("历史分布不代表未来规律，不构成购彩建议。"))
        }
    }

    /** 大字号、最窄手机与两个彩种次区域不出现空白页面，所有关键工具仍可进入。 */
    @Test
    fun narrowLargeTextAndSecondaryAreasRender() {
        withScene(320, 640, fontScale = 1.6f) { scene ->
            scene.capture("small-phone-large-text")
            scene.clickDescription("走势显示设置")
            scene.clickText("显示遗漏")
            scene.capture("large-text-settings")
        }
        for (type in LotteryType.entries) {
            withScene(390, 844, type = type, area = LotteryTrendArea.SECONDARY) { scene ->
                scene.capture("portrait-${type.name.lowercase()}-secondary")
                assertTrue(scene.hasDescription("第 ${if (type == LotteryType.SUPER_LOTTO) "26150" else "2026150"} 期"))
                val lastNumber = if (type == LotteryType.SUPER_LOTTO) "12" else "16"
                assertTrue(scene.findDescription("追踪号码 $lastNumber").boundsInRoot.right <= 390)
            }
        }
    }

    /** 加载和失败状态不冒充官方数据；失败重试能发出控制器动作。 */
    @Test
    fun failureOffersRetryWithoutInventedNumbers() {
        val actions = mutableListOf<TrendChartAction>()
        ImageComposeScene(390, 844).use { scene ->
            scene.setContent {
                LotteryTheme {
                    TrendScreen(
                        TrendChartState.create().copy(
                            content = TrendChartContent.Failed("官网响应超时"),
                        ),
                        MainDestination.entries,
                        {},
                        {
                            actions +=
                                it
                        },
                    )
                }
            }
            scene.settle()
            scene.capture("portrait-load-failure")
            assertFalse(scene.hasDescription("追踪号码"))
            scene.clickText("重试")
            assertEquals(listOf<TrendChartAction>(TrendChartAction.Retry), actions)
        }
    }

    /** 构造仅用于渲染测试的确定性开奖，不进入生产代码或官方来源层。 */
    private fun testState(
        type: LotteryType,
        area: LotteryTrendArea,
        sampleSize: TrendSampleSize = TrendSampleSize.LAST_50,
    ): TrendChartState {
        val primaryLimit = if (type == LotteryType.SUPER_LOTTO) 35 else 33
        val primaryCount = if (type == LotteryType.SUPER_LOTTO) 5 else 6
        val secondaryLimit = if (type == LotteryType.SUPER_LOTTO) 12 else 16
        val secondaryCount = if (type == LotteryType.SUPER_LOTTO) 2 else 1
        val draws =
            (sampleSize.count - 1 downTo 0).map { offset ->
                val year = 2026 - offset / 150
                val ordinal = (150 - offset % 150).toString().padStart(3, '0')
                val random = Random(offset + 1024)
                HistoricalDraw(
                    type,
                    Issue("${if (type == LotteryType.SUPER_LOTTO) year % 100 else year}$ordinal"),
                    "2026-09-01",
                    (1..primaryLimit).shuffled(random).take(primaryCount).sorted(),
                    (1..secondaryLimit).shuffled(random).take(secondaryCount).sorted(),
                )
            }
        val snapshot =
            (
                LotteryTrendCalculator().calculate(
                    type,
                    area,
                    sampleSize,
                    draws,
                ) as LotteryTrendCalculationResult.Success
            ).snapshot
        return TrendChartState.create().copy(
            lotteryType = type,
            area = area,
            sampleSize = sampleSize,
            content = TrendChartContent.Ready(snapshot, "仅用于渲染验收的测试样本", 1788753600000),
        )
    }

    /** 在独立渲染场景中接通筛选操作，以便测试实际 UI 状态更新。 */
    private fun withScene(
        width: Int,
        height: Int,
        fontScale: Float = 1f,
        type: LotteryType = LotteryType.SUPER_LOTTO,
        area: LotteryTrendArea = LotteryTrendArea.PRIMARY,
        block: (ImageComposeScene) -> Unit,
    ) {
        val state = mutableStateOf(testState(type, area))
        ImageComposeScene(width, height, Density(1f, fontScale)).use { scene ->
            scene.setContent {
                LotteryTheme {
                    TrendScreen(state.value, MainDestination.entries, {}, { action ->
                        state.value =
                            when (action) {
                                is TrendChartAction.ChangeLotteryType -> {
                                    testState(
                                        action.lotteryType,
                                        state.value.area,
                                        state.value.sampleSize,
                                    )
                                }

                                is TrendChartAction.ChangeArea -> {
                                    testState(
                                        state.value.lotteryType,
                                        action.area,
                                        state.value.sampleSize,
                                    )
                                }

                                is TrendChartAction.ChangeSampleSize -> {
                                    testState(
                                        state.value.lotteryType,
                                        state.value.area,
                                        action.sampleSize,
                                    )
                                }

                                is TrendChartAction.ChangeView -> {
                                    state.value.copy(view = action.view)
                                }

                                TrendChartAction.Retry -> {
                                    state.value
                                }
                            }
                    })
                }
            }
            scene.settle()
            block(scene)
        }
    }

    /** 推进布局、状态和滚动动画至稳定帧。 */
    private fun ImageComposeScene.settle() {
        repeat(50) {
            frameTimeNanos += 20_000_000L
            render(frameTimeNanos).close()
        }
    }

    /** 收集包含合并前语义的可访问节点。 */
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap { flatten(it) }
        return semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
    }

    /** 查找完整匹配的语义说明，失败时输出当前语义便于诊断。 */
    private fun ImageComposeScene.findDescription(text: String): SemanticsNode =
        nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true }
            ?: error("未找到 $text：${nodes().map { it.config }}")

    /** 判断是否出现指定前缀的语义说明。 */
    private fun ImageComposeScene.hasDescription(text: String): Boolean =
        nodes().any {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { value -> value.startsWith(text) } ==
                true
        }

    /** 判断界面中是否存在完整文本。 */
    private fun ImageComposeScene.hasText(text: String): Boolean =
        nodes().any {
            it.config.getOrNull(SemanticsProperties.Text)?.any { value ->
                value.text ==
                    text
            } ==
                true
        }

    /** 点击语义说明对应控件。 */
    private fun ImageComposeScene.clickDescription(text: String) = click(findDescription(text))

    /** 点击界面文本对应控件。 */
    private fun ImageComposeScene.clickText(text: String) =
        click(
            nodes().firstOrNull {
                it.config.getOrNull(SemanticsProperties.Text)?.any { value ->
                    value.text == text
                } == true
            }
                ?: error("未找到文本 $text"),
        )

    /** 使用真实指针事件点击可见节点中心，避免只验证状态函数。 */
    private fun ImageComposeScene.click(node: SemanticsNode) {
        val center = node.boundsInRoot.center
        sendPointerEvent(PointerEventType.Press, center)
        sendPointerEvent(PointerEventType.Release, center)
        settle()
    }

    /** 保存原生 Compose 截图并检查非空白像素，输出仅包含测试开奖。 */
    private fun ImageComposeScene.capture(name: String) {
        val file = File("build/reports/trend-redesign/$name.png")
        file.parentFile.mkdirs()
        render(frameTimeNanos).use { image -> file.writeBytes(checkNotNull(image.encodeToData()).bytes) }
        val bitmap = ImageIO.read(file)
        val colors =
            buildSet {
                for (y in 0 until bitmap.height step 4) {
                    for (x in 0 until bitmap.width step 4) {
                        add(
                            bitmap.getRGB(x, y),
                        )
                    }
                }
            }
        assertTrue(colors.size > 30, "截图不应为空白：$name")
    }
}

package roc.win.lottery.app.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.use
import roc.win.lottery.app.AppScreen
import roc.win.lottery.app.PeriodVerification
import roc.win.lottery.app.theme.LotteryTheme
import roc.win.lottery.domain.BetLine
import roc.win.lottery.domain.ConfirmedTicket
import roc.win.lottery.domain.ConfirmedValue
import roc.win.lottery.domain.DrawPolicy
import roc.win.lottery.domain.DrawResult
import roc.win.lottery.domain.DrawStatus
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryPrizeCalculator
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.PrizeTier
import roc.win.lottery.domain.PrizeTierCodes
import roc.win.lottery.domain.RuleVersion
import roc.win.lottery.domain.SourceEvidence
import roc.win.lottery.domain.TicketFieldOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 验证开奖结果页展示本机来源，并区分只补查未完成期次和全部重新联网核对。 */
@OptIn(ExperimentalComposeUiApi::class)
class VerificationScreenRenderTest {
    /** 单期结果来自本机时标注核对时间，按钮为重新联网核对。 */
    @Test
    fun singleResultFromLocalStoreShowsSourceAndForceRefresh() {
        var refreshCount = 0
        val draw = draw("26091")
        ImageComposeScene(390, 2400).use { scene ->
            scene.setContent {
                LotteryTheme {
                    VerificationResultScreen(
                        ticket = ticket(periodCount = 1),
                        drawResult = draw,
                        prizeCheckResult = LotteryPrizeCalculator().calculate(ticket(periodCount = 1), draw),
                        isFromLocalStore = true,
                        onRetry = { refreshCount += 1 },
                        onBack = {},
                        onDone = {},
                    )
                }
            }
            scene.settle()
            assertTrue(scene.hasTextStartingWith("使用本机保存的开奖 · 核对于"))
            scene.clickText("重新联网核对本期")
            assertEquals(1, refreshCount)
        }
    }

    /** 有未完成期次时同时提供补查和全部重新核对；全部完成时只保留重新核对。 */
    @Test
    fun multiPeriodResultSeparatesRetryAndForceRefresh() {
        val actions = mutableListOf<String>()
        val local =
            PeriodVerification.Verified(
                issue = Issue("26090"),
                drawResult = draw("26090"),
                prizeCheckResult = LotteryPrizeCalculator().calculate(ticket(issue = "26090"), draw("26090")),
                isFromLocalStore = true,
            )
        val pending =
            PeriodVerification.Unavailable(
                issue = Issue("26091"),
                status = DrawStatus.NOT_PUBLISHED,
                message = "未到开奖时间，预计最早 08-15（周六）21:25 后开奖，本次未联网查询",
                wasQueried = false,
            )
        render(AppScreen.MultiPeriodVerificationResult(ticket(), listOf(local, pending)), actions) { scene ->
            assertTrue(scene.hasTextStartingWith("使用本机保存的开奖 · 核对于"))
            assertTrue(scene.hasText("本期尚未发起官网查询"))
            scene.clickText("重查未完成期次")
            scene.clickText("重新联网核对全部期次")
            assertEquals(listOf("retry", "force"), actions)
        }

        actions.clear()
        val completed =
            local.copy(
                issue = Issue("26091"),
                drawResult = draw("26091"),
                prizeCheckResult = LotteryPrizeCalculator().calculate(ticket(issue = "26091"), draw("26091")),
            )
        render(AppScreen.MultiPeriodVerificationResult(ticket(), listOf(local, completed)), actions) { scene ->
            assertFalse(scene.hasText("重查未完成期次"))
            scene.clickText("重新联网核对全部期次")
            assertEquals(listOf("force"), actions)
        }
    }

    /** 渲染多期结果页并把两种操作记录到列表。 */
    private fun render(
        result: AppScreen.MultiPeriodVerificationResult,
        actions: MutableList<String>,
        block: (ImageComposeScene) -> Unit,
    ) {
        ImageComposeScene(390, 2400).use { scene ->
            scene.setContent {
                LotteryTheme {
                    MultiPeriodVerificationScreen(
                        result = result,
                        onRetry = { actions += "retry" },
                        onForceRefresh = { actions += "force" },
                        onBack = {},
                        onDone = {},
                    )
                }
            }
            scene.settle()
            block(scene)
        }
    }

    /** 两期大乐透测试票。 */
    private fun ticket(
        issue: String = "26090",
        periodCount: Int = 2,
    ): ConfirmedTicket =
        ConfirmedTicket(
            lotteryType = ConfirmedValue(LotteryType.SUPER_LOTTO, TicketFieldOrigin.USER),
            issue = ConfirmedValue(Issue(issue), TicketFieldOrigin.USER),
            betLines =
                listOf(
                    BetLine(
                        primaryNumbers = ConfirmedValue(listOf(1, 3, 5, 8, 10), TicketFieldOrigin.USER),
                        secondaryNumbers = ConfirmedValue(listOf(1, 2), TicketFieldOrigin.USER),
                        isAdditional = ConfirmedValue(false, TicketFieldOrigin.USER),
                        originalText = "",
                    ),
                ),
            multiplier = ConfirmedValue(1, TicketFieldOrigin.USER),
            periodCount = ConfirmedValue(periodCount, TicketFieldOrigin.USER),
            paidAmountFen = ConfirmedValue(200L * periodCount, TicketFieldOrigin.DERIVED),
        )

    /** 与测试票不中奖的一期开奖。 */
    private fun draw(issue: String): DrawResult =
        DrawResult(
            lotteryType = LotteryType.SUPER_LOTTO,
            issue = Issue(issue),
            drawDate = "2026-08-12",
            primaryNumbers = listOf(2, 7, 14, 21, 33),
            secondaryNumbers = listOf(4, 9),
            status = DrawStatus.FINAL_PAYOUT,
            revision = 1,
            ruleVersion = RuleVersion.DLT_2026_01.code,
            policy = DrawPolicy.STANDARD,
            prizeTiers = listOf(PrizeTier(PrizeTierCodes.SEVENTH, "七等奖", 500L, null)),
            evidence = SourceEvidence("测试主源", "https://example.invalid/main", 1_787_689_800_000L, "a".repeat(64)),
            supportingEvidence = emptyList(),
        )

    /** 推进布局与状态至稳定帧。 */
    private fun ImageComposeScene.settle() {
        repeat(20) {
            frameTimeNanos += 20_000_000L
            render(frameTimeNanos).close()
        }
    }

    /** 当前渲染时间。 */
    private var frameTimeNanos = 0L

    /** 收集包含合并前语义的全部节点。 */
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap { flatten(it) }
        return semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
    }

    /** 节点上的全部文本。 */
    private fun SemanticsNode.texts(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    /** 判断是否存在完整文本。 */
    private fun ImageComposeScene.hasText(text: String): Boolean = nodes().any { text in it.texts() }

    /** 判断是否存在以指定内容开头的文本。 */
    private fun ImageComposeScene.hasTextStartingWith(prefix: String): Boolean =
        nodes().any { node -> node.texts().any { it.startsWith(prefix) } }

    /** 用真实指针事件点击文本所在控件中心。 */
    private fun ImageComposeScene.clickText(text: String) {
        val node = nodes().firstOrNull { text in it.texts() } ?: error("未找到文本 $text")
        val center = node.boundsInRoot.center
        sendPointerEvent(PointerEventType.Press, center)
        sendPointerEvent(PointerEventType.Release, center)
        settle()
    }
}

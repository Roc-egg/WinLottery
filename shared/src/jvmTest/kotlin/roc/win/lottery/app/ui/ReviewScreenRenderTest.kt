package roc.win.lottery.app.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.use
import roc.win.lottery.app.TicketReviewAction
import roc.win.lottery.app.TicketReviewState
import roc.win.lottery.app.theme.LotteryTheme
import roc.win.lottery.domain.BetLineDraft
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.TicketDraft
import roc.win.lottery.domain.TicketValidator
import kotlin.test.Test
import kotlin.test.assertTrue

/** 票面校正页渲染测试：号码多于单式数量的复式行、快乐8 选号行和空行都能正常绘制。 */
@OptIn(ExperimentalComposeUiApi::class)
class ReviewScreenRenderTest {
    /** 快乐8 选五两注只显示一个号码区并标出玩法。 */
    @Test
    fun happy8LinesRender() {
        val scene =
            render(
                draft(
                    LotteryType.HAPPY_8,
                    "2026123",
                    listOf(listOf(3, 11, 25, 40, 77) to emptyList(), listOf(5, 19, 33, 58, 80) to emptyList()),
                    paidAmountFen = 400L,
                ),
            )
        scene.use {
            assertTrue(it.hasText("号码（已选 5/10，选五）"))
            assertTrue(it.texts().none { text -> text.startsWith("蓝球") || text.startsWith("后区") })
        }
    }

    /** 双色球复式行号码多于单式数量时不补占位、不崩溃。 */
    @Test
    fun compoundLineRenders() {
        val scene =
            render(
                draft(
                    LotteryType.DOUBLE_COLOR_BALL,
                    "2026123",
                    listOf(listOf(1, 4, 9, 13, 17, 22, 28, 31) to listOf(3, 12)),
                    paidAmountFen = 11_200L,
                ),
            )
        scene.use { assertTrue(it.hasText("红球（已选 8 个，复式）")) }
    }

    /** 手动录入快乐8 时空行显示一个占位。 */
    @Test
    fun emptyHappy8LineRenders() {
        val state =
            TicketReviewState
                .fromDraft(draft(LotteryType.SUPER_LOTTO, "26123", listOf(emptyList<Int>() to emptyList()), 200L))
                .applyAction(TicketReviewAction.ChangeLotteryType(LotteryType.HAPPY_8))
        render(state).use { assertTrue(it.hasText("号码（已选 0/10）")) }
    }

    /** 渲染指定草稿的校正页。 */
    private fun render(draft: TicketDraft): ImageComposeScene = render(TicketReviewState.fromDraft(draft))

    /** 渲染指定状态的校正页并推进到稳定帧。 */
    private fun render(state: TicketReviewState): ImageComposeScene {
        val scene = ImageComposeScene(390, 4000)
        scene.setContent {
            LotteryTheme {
                ReviewScreen(
                    editor = state,
                    evaluation = state.evaluate(TicketValidator()),
                    imageRef = null,
                    fieldRegions = emptyList(),
                    fieldCandidates = emptyList(),
                    manualEntryReason = null,
                    saveError = null,
                    isDemo = false,
                    usesRealRecognition = true,
                    usesRealDrawData = true,
                    onBack = {},
                    onLotteryTypeChange = {},
                    onIssueChange = {},
                    onNumberToggle = { _, _, _ -> },
                    onAddBetLine = {},
                    onRemoveBetLine = {},
                    onMultiplierChange = {},
                    onPeriodCountChange = {},
                    onAdditionalChange = {},
                    onPaidAmountChange = {},
                    onUseCalculatedAmount = {},
                    onConfirm = {},
                )
            }
        }
        repeat(FRAME_COUNT) { frame -> scene.render(frame * FRAME_NANOS).close() }
        return scene
    }

    /** 构造识别草稿。 */
    private fun draft(
        lotteryType: LotteryType,
        issue: String,
        lines: List<Pair<List<Int>, List<Int>>>,
        paidAmountFen: Long,
    ): TicketDraft =
        TicketDraft(
            lotteryType = lotteryType,
            issue = issue,
            betLines = lines.map { (primary, secondary) -> BetLineDraft(primary, secondary, false, "") },
            multiplier = 1,
            periodCount = 1,
            paidAmountFen = paidAmountFen,
        )

    /** 全部语义节点上的文字。 */
    private fun ImageComposeScene.texts(): List<String> {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap { flatten(it) }
        return semanticsOwners
            .flatMap { flatten(it.unmergedRootSemanticsNode) }
            .flatMap { node ->
                node.config
                    .getOrNull(SemanticsProperties.Text)
                    .orEmpty()
                    .map { it.text }
            }
    }

    /** 是否存在完整文字。 */
    private fun ImageComposeScene.hasText(text: String): Boolean = text in texts()

    /** 渲染帧参数。 */
    private companion object {
        /** 推进的帧数。 */
        const val FRAME_COUNT = 10

        /** 每帧间隔纳秒。 */
        const val FRAME_NANOS = 20_000_000L
    }
}

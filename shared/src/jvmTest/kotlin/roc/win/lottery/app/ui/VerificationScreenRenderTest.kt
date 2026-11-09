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
import roc.win.lottery.domain.LotteryPrizeRules
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
    /** 快乐8 结果页列出 20 个开奖号码，逐注显示“选N中K”和命中个数，号码不带加号。 */
    @Test
    fun happy8ResultShowsDrawNumbersAndPlayTiers() {
        val ticket =
            ConfirmedTicket(
                lotteryType = ConfirmedValue(LotteryType.HAPPY_8, TicketFieldOrigin.USER),
                issue = ConfirmedValue(Issue("2026294"), TicketFieldOrigin.USER),
                betLines =
                    listOf(listOf(3, 5, 7, 41, 60), listOf(1, 2, 41, 60, 77)).map { numbers ->
                        BetLine(
                            primaryNumbers = ConfirmedValue(numbers, TicketFieldOrigin.USER),
                            secondaryNumbers = ConfirmedValue(emptyList(), TicketFieldOrigin.USER),
                            isAdditional = ConfirmedValue(false, TicketFieldOrigin.USER),
                            originalText = "",
                        )
                    },
                multiplier = ConfirmedValue(1, TicketFieldOrigin.USER),
                periodCount = ConfirmedValue(1, TicketFieldOrigin.USER),
                paidAmountFen = ConfirmedValue(400L, TicketFieldOrigin.USER),
            )
        val draw =
            DrawResult(
                lotteryType = LotteryType.HAPPY_8,
                issue = Issue("2026294"),
                drawDate = "2026-11-04",
                primaryNumbers = (1..20).toList(),
                secondaryNumbers = emptyList(),
                status = DrawStatus.FINAL_PAYOUT,
                revision = 1,
                ruleVersion = RuleVersion.KL8_2024_01.code,
                policy = DrawPolicy.STANDARD,
                prizeTiers =
                    LotteryPrizeRules.HAPPY_8_PRIZE_TIER_CODES.map { code ->
                        PrizeTier(code, checkNotNull(PrizeTierCodes.happy8DisplayName(code)), 300L, null)
                    },
                evidence = SourceEvidence("测试主源", "https://example.invalid/main", 1L, "a".repeat(64)),
                supportingEvidence = emptyList(),
            )
        ImageComposeScene(390, 2400).use { scene ->
            scene.setContent {
                LotteryTheme {
                    VerificationResultScreen(
                        ticket = ticket,
                        drawResult = draw,
                        prizeCheckResult = LotteryPrizeCalculator().calculate(ticket, draw),
                        isFromLocalStore = false,
                        onRetry = {},
                        onBack = {},
                        onDone = {},
                    )
                }
            }
            scene.settle()
            assertTrue(scene.hasText("开奖号码"))
            assertTrue(scene.hasText("03 05 07 41 60"))
            assertTrue(scene.hasText("命中 3 个"))
            assertTrue(scene.hasTextStartingWith("选五中三"))
            assertFalse(scene.nodes().any { node -> node.texts().any { "+" in it && it.any(Char::isDigit) } })
        }
    }

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

    /** 复式行按组展示展开注数、各奖级中奖注数和本组奖金。 */
    @Test
    fun compoundLineShowsTierWinCounts() {
        val ticket =
            ticket(periodCount = 1).let { single ->
                single.copy(
                    betLines =
                        listOf(
                            single.betLines.single().copy(
                                primaryNumbers =
                                    ConfirmedValue(listOf(2, 7, 14, 20, 22, 24, 26), TicketFieldOrigin.USER),
                                secondaryNumbers = ConfirmedValue(listOf(4, 9, 11), TicketFieldOrigin.USER),
                            ),
                        ),
                    paidAmountFen = ConfirmedValue(12_600L, TicketFieldOrigin.OCR),
                )
            }
        val draw =
            draw("26090").copy(
                prizeTiers =
                    listOf(
                        PrizeTier(PrizeTierCodes.FIFTH, "五等奖", 20_000L, null),
                        PrizeTier(PrizeTierCodes.SIXTH, "六等奖", 1_800L, null),
                        PrizeTier(PrizeTierCodes.SEVENTH, "七等奖", 700L, null),
                    ),
            )
        ImageComposeScene(390, 2400).use { scene ->
            scene.setContent {
                LotteryTheme {
                    VerificationResultScreen(
                        ticket = ticket,
                        drawResult = draw,
                        prizeCheckResult = LotteryPrizeCalculator().calculate(ticket, draw),
                        isFromLocalStore = false,
                        onRetry = {},
                        onBack = {},
                        onDone = {},
                    )
                }
            }
            scene.settle()
            assertTrue(scene.hasText("第 1 组 · 复式 63 注"))
            assertTrue(scene.hasText("中奖 57 注"))
            assertTrue(scene.hasText("其中 五等奖 6 注、六等奖 24 注、七等奖 27 注"))
            assertTrue(scene.hasText("本组税前奖金 1,821.00 元"))

            // 命中号码默认收起，点开后才逐个标出命中号码，再点收起。
            val hitDescription = "前区 02 07 14 20 22 24 26，命中 02 07 14；后区 04 09 11，命中 04 09"
            assertFalse(scene.hasDescription(hitDescription))
            scene.clickText("查看命中号码")
            assertTrue(scene.hasDescription(hitDescription))
            scene.clickText("收起命中号码")
            assertFalse(scene.hasDescription(hitDescription))
            assertTrue(scene.hasText("查看命中号码"))
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
            // 只有已核对期次提供逐注命中号码，默认收起。
            val hitDescription = "前区 01 03 05 08 10，未命中；后区 01 02，未命中"
            assertEquals(1, scene.nodes().count { "查看每注命中号码" in it.texts() })
            assertFalse(scene.hasDescription(hitDescription))
            scene.clickText("查看每注命中号码")
            assertTrue(scene.hasDescription(hitDescription))
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

    /** 判断是否存在完整匹配的语义说明。 */
    private fun ImageComposeScene.hasDescription(text: String): Boolean =
        nodes().any { text in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }

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

package roc.win.lottery.app.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import roc.win.lottery.app.AppScreen
import roc.win.lottery.app.MainDestination
import roc.win.lottery.app.TicketRecordDrawSummary
import roc.win.lottery.app.theme.LotteryTheme
import roc.win.lottery.domain.BetLine
import roc.win.lottery.domain.ConfirmedTicket
import roc.win.lottery.domain.ConfirmedValue
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.TicketFieldOrigin
import roc.win.lottery.persistence.StoredTicketRecord
import roc.win.lottery.persistence.TicketAcquisitionSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** 记录页在列表中直观展示每张票已开奖、中奖期数、税前奖金和是否全部核对完。 */
@OptIn(ExperimentalComposeUiApi::class)
class TicketRecordsScreenRenderTest {
    /** 全部核对、部分核对和未查询的记录分别显示对应的统计、状态和操作。 */
    @Test
    fun showsDrawSummaryForEachRecord() {
        listOf(false, true).forEach { dark ->
            ImageComposeScene(780, 2600, Density(2f)).use { scene ->
                scene.setContent {
                    LotteryTheme(darkTheme = dark) {
                        TicketRecordsScreen(
                            screen = AppScreen.Records(),
                            records = records,
                            recordSummaries = summaries,
                            isLoading = false,
                            loadError = null,
                            supportsFileExchange = true,
                            availableMainDestinations = MainDestination.entries,
                            onMainDestinationSelected = {},
                            onFilterChange = {},
                            onSearchChange = {},
                            onRename = { _, _ -> },
                            onDelete = {},
                            onQuery = {},
                            onClearAll = {},
                            onExport = {},
                            onImport = {},
                            onConfirmImport = {},
                            onDismissImport = {},
                        )
                    }
                }
                var time = 0L
                repeat(30) {
                    time += 20_000_000L
                    scene.render(time).close()
                }
                listOf(
                    "1/3 张",
                    "1,839.00 元",
                    "已中奖",
                    "未全部核对",
                    "待核对",
                    "2/3 期",
                    "还有 1 期未核对",
                    "查看结果",
                    "继续核对",
                    "查询开奖",
                ).forEach { text -> assertTrue(scene.texts().any { it == text }, "缺少：$text") }
                val file = File("build/reports/records-redesign/${if (dark) "dark" else "light"}.png")
                file.parentFile.mkdirs()
                scene.render(time).use { image -> file.writeBytes(checkNotNull(image.encodeToData()).bytes) }
            }
        }
    }

    /** 三张测试票：一张全部核对且中奖，一张三期票核对两期，一张尚未查询。 */
    private val records =
        listOf(
            record("win", "周三大乐透", "26090", periodCount = 1),
            record("partial", "三期追号", "26091", periodCount = 3),
            record("new", "今晚开奖", "26100", periodCount = 1),
        )

    /** 与测试票对应的核对概况。 */
    private val summaries =
        mapOf(
            "win" to TicketRecordDrawSummary(1, 1, 1, 183_900L, 0),
            "partial" to TicketRecordDrawSummary(3, 2, 0, 0L, 0),
            "new" to TicketRecordDrawSummary(1, 0, 0, 0L, 0),
        )

    /** 构造一条大乐透单式记录。 */
    private fun record(
        id: String,
        name: String,
        issue: String,
        periodCount: Int,
    ): StoredTicketRecord =
        StoredTicketRecord(
            id = id,
            displayName = name,
            ticket =
                ConfirmedTicket(
                    lotteryType = ConfirmedValue(LotteryType.SUPER_LOTTO, TicketFieldOrigin.USER),
                    issue = ConfirmedValue(Issue(issue), TicketFieldOrigin.USER),
                    betLines =
                        listOf(
                            BetLine(
                                primaryNumbers = ConfirmedValue(listOf(2, 7, 15, 21, 30), TicketFieldOrigin.USER),
                                secondaryNumbers = ConfirmedValue(listOf(4, 10), TicketFieldOrigin.USER),
                                isAdditional = ConfirmedValue(false, TicketFieldOrigin.USER),
                                originalText = "",
                            ),
                        ),
                    multiplier = ConfirmedValue(1, TicketFieldOrigin.USER),
                    periodCount = ConfirmedValue(periodCount, TicketFieldOrigin.USER),
                    paidAmountFen = ConfirmedValue(200L * periodCount, TicketFieldOrigin.DERIVED),
                ),
            acquisitionSource = TicketAcquisitionSource.CAMERA,
            createdAtEpochMillis = 1_787_689_800_000L,
            updatedAtEpochMillis = 1_787_689_800_000L,
            dataVersion = 1,
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
}

package roc.win.lottery.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import roc.win.lottery.app.MainDestination
import roc.win.lottery.app.RandomNumberPickerAction
import roc.win.lottery.app.RandomNumberPickerState
import roc.win.lottery.app.theme.LotteryThemeTokens
import roc.win.lottery.domain.CrossPeriodRandomMode
import roc.win.lottery.domain.GeneratedNumberLine
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.RandomNumberGenerationLimits
import roc.win.lottery.domain.RandomNumberPlan

/** 随机选号页允许的相对期数范围。 */
private val PERIOD_COUNT_RANGE =
    RandomNumberGenerationLimits.MIN_PERIOD_COUNT..RandomNumberGenerationLimits.MAX_PERIOD_COUNT

/** 随机选号页允许的每期注数范围。 */
private val BETS_PER_PERIOD_RANGE =
    RandomNumberGenerationLimits.MIN_BETS_PER_PERIOD..RandomNumberGenerationLimits.MAX_BETS_PER_PERIOD

/**
 * V1.2 随机选号一级工作区。
 *
 * @param picker 当前会话中的选号配置与结果。
 * @param availableMainDestinations 当前平台可进入的一级目的地。
 * @param onMainDestinationSelected 一级目的地切换操作。
 * @param onAction 修改配置或生成号码。
 */
@Composable
fun RandomNumberScreen(
    picker: RandomNumberPickerState,
    availableMainDestinations: List<MainDestination>,
    onMainDestinationSelected: (MainDestination) -> Unit,
    onAction: (RandomNumberPickerAction) -> Unit,
) {
    var selectedPeriodIndex by remember(picker.plan) { mutableStateOf(0) }
    val selectedPeriod = picker.plan?.periods?.getOrNull(selectedPeriodIndex)

    AppShell(
        title = "选号",
        mainDestination = MainDestination.NUMBER_PICKER,
        availableMainDestinations = availableMainDestinations,
        onMainDestinationSelected = onMainDestinationSelected,
        scrollableContent = false,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 40.dp + LocalMainBarInset.current),
        ) {
            item(key = "configuration") {
                CenteredNumberContent {
                    PageLead("随机选号：生成结果仅供娱乐，不代表预测，不构成购彩建议。")
                    Spacer(Modifier.height(16.dp))
                    LotteryCard(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        FieldLabel("彩种")
                        SegmentedControl(
                            options = listOf(LotteryType.SUPER_LOTTO, LotteryType.DOUBLE_COLOR_BALL),
                            selected = picker.lotteryType,
                            label = { it.shortDisplayName() },
                            onSelect = { onAction(RandomNumberPickerAction.ChangeLotteryType(it)) },
                        )
                        HairlineDivider()
                        QuantityControls(
                            periodCount = picker.periodCount,
                            betsPerPeriod = picker.betsPerPeriod,
                            onPeriodCountChange = {
                                onAction(RandomNumberPickerAction.ChangePeriodCount(it))
                            },
                            onBetsPerPeriodChange = {
                                onAction(RandomNumberPickerAction.ChangeBetsPerPeriod(it))
                            },
                        )
                        HairlineDivider()
                        CrossPeriodModeSelector(
                            lotteryType = picker.lotteryType,
                            selected = picker.crossPeriodMode,
                            onSelected = {
                                onAction(RandomNumberPickerAction.ChangeCrossPeriodMode(it))
                            },
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(
                        "共 ${picker.totalBetCount} 注（${picker.periodCount} 期 × 每期 ${picker.betsPerPeriod} 注）",
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(10.dp))
                    PrimaryActionButton(
                        text = if (picker.plan == null) "生成号码" else "重新生成",
                        onClick = { onAction(RandomNumberPickerAction.Generate) },
                        icon = LotteryIcons.NumberPicker,
                    )
                    picker.errorMessage?.let { message ->
                        Spacer(Modifier.height(12.dp))
                        Notice(message = message, tone = NoticeTone.ERROR)
                    }
                }
            }

            picker.plan?.let { plan ->
                item(key = "result-header") {
                    CenteredNumberContent {
                        Spacer(Modifier.height(SectionSpacing))
                        RandomNumberResultHeader(
                            plan = plan,
                            selectedPeriodIndex = selectedPeriodIndex,
                            onPeriodSelected = { selectedPeriodIndex = it },
                        )
                    }
                }

                item(key = "period-${selectedPeriod?.periodIndex}") {
                    CenteredNumberContent {
                        Spacer(Modifier.height(SectionTitleSpacing))
                        LotteryCard(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.Top,
                        ) {
                            val lines = selectedPeriod?.lines.orEmpty()
                            lines.forEachIndexed { index, line ->
                                GeneratedNumberLineItem(
                                    lineNumber = index + 1,
                                    line = line,
                                    lotteryType = plan.lotteryType,
                                )
                                if (index != lines.lastIndex) HairlineDivider()
                            }
                        }
                    }
                }

                item(key = "result-footer") {
                    CenteredNumberContent {
                        Spacer(Modifier.height(16.dp))
                        FootnoteText("期次为当前方案内的相对顺序，不代表官方期号；号码不会保存为已购票据。")
                    }
                }
            }
        }
    }
}

/** 把选号页面内容约束到适合连续操作的最大宽度。 */
@Composable
private fun CenteredNumberContent(content: @Composable ColumnScope.() -> Unit) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
            content = content,
        )
    }
}

/** 根据宽度横排或纵排期数与每期注数控件。 */
@Composable
private fun QuantityControls(
    periodCount: Int,
    betsPerPeriod: Int,
    onPeriodCountChange: (Int) -> Unit,
    onBetsPerPeriodChange: (Int) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth >= 560.dp) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                CounterControl(
                    title = "期数",
                    value = periodCount,
                    valueSuffix = "期",
                    range = PERIOD_COUNT_RANGE,
                    onValueChange = onPeriodCountChange,
                    modifier = Modifier.weight(1f),
                )
                CounterControl(
                    title = "每期注数",
                    value = betsPerPeriod,
                    valueSuffix = "注",
                    range = BETS_PER_PERIOD_RANGE,
                    onValueChange = onBetsPerPeriodChange,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CounterControl(
                    title = "期数",
                    value = periodCount,
                    valueSuffix = "期",
                    range = PERIOD_COUNT_RANGE,
                    onValueChange = onPeriodCountChange,
                )
                CounterControl(
                    title = "每期注数",
                    value = betsPerPeriod,
                    valueSuffix = "注",
                    range = BETS_PER_PERIOD_RANGE,
                    onValueChange = onBetsPerPeriodChange,
                )
            }
        }
    }
}

/** 绘制具有稳定点击区域和明确上下限的整数步进器。 */
@Composable
private fun CounterControl(
    title: String,
    value: Int,
    valueSuffix: String,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingLine(title = title, modifier = modifier) {
        Stepper(
            valueText = "$value $valueSuffix",
            onDecrement = { onValueChange(value - 1) },
            onIncrement = { onValueChange(value + 1) },
            decrementEnabled = value > range.first,
            incrementEnabled = value < range.last,
            decrementDescription = "减少$title",
            incrementDescription = "增加$title",
        )
    }
}

/** 展示当前彩种允许的跨期生成模式。 */
@Composable
private fun CrossPeriodModeSelector(
    lotteryType: LotteryType,
    selected: CrossPeriodRandomMode,
    onSelected: (CrossPeriodRandomMode) -> Unit,
) {
    FieldLabel("跨期方式")
    if (lotteryType == LotteryType.SUPER_LOTTO) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                LotteryIcons.Success,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = LotteryThemeTokens.colors.success,
            )
            Spacer(Modifier.width(8.dp))
            Text("所有期次复用同一批号码", style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        SegmentedControl(
            options = listOf(CrossPeriodRandomMode.REUSE_SAME_LINES, CrossPeriodRandomMode.INDEPENDENT_PER_PERIOD),
            selected = selected,
            label = { it.displayName() },
            onSelect = onSelected,
        )
    }
}

/** 展示生成方案摘要、相对期次选择器和当前期次标题。 */
@Composable
private fun RandomNumberResultHeader(
    plan: RandomNumberPlan,
    selectedPeriodIndex: Int,
    onPeriodSelected: (Int) -> Unit,
) {
    SectionHeader(
        title = "生成结果",
        subtitle =
            "${plan.lotteryType.shortDisplayName()} · ${plan.periodCount} 期 · 每期 ${plan.betsPerPeriod} 注 · " +
                plan.crossPeriodMode.resultDescription(),
    )
    if (plan.periods.size > 1) {
        Spacer(Modifier.height(14.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items = plan.periods, key = { it.periodIndex }) { period ->
                val index = period.periodIndex - 1
                ChoicePill(
                    selected = selectedPeriodIndex == index,
                    onClick = { onPeriodSelected(index) },
                    label = "第 ${period.periodIndex} 期",
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        FieldLabel("第 ${selectedPeriodIndex + 1} 期号码")
    }
}

/** 展示一注随机号码，主号码与次号码使用稳定的红蓝语义。 */
@Composable
private fun GeneratedNumberLineItem(
    lineNumber: Int,
    line: GeneratedNumberLine,
    lotteryType: LotteryType,
) {
    val primaryName = if (lotteryType == LotteryType.SUPER_LOTTO) "前区" else "红球"
    val secondaryName = if (lotteryType == LotteryType.SUPER_LOTTO) "后区" else "蓝球"
    val description =
        "第 $lineNumber 注，$primaryName ${line.primaryNumbers.joinToString(" ") { it.toString().padStart(2, '0') }}，" +
            "$secondaryName ${line.secondaryNumbers.joinToString(" ") { it.toString().padStart(2, '0') }}"
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            lineNumber.toString().padStart(2, '0'),
            modifier = Modifier.width(28.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        NumberBallLine(
            primaryNumbers = line.primaryNumbers,
            secondaryNumbers = line.secondaryNumbers,
            modifier = Modifier.weight(1f),
            maxBallSize = 40.dp,
        )
    }
}

/** 返回彩种在紧凑控件中的名称。 */
private fun LotteryType.shortDisplayName(): String =
    when (this) {
        LotteryType.SUPER_LOTTO -> "大乐透"
        LotteryType.DOUBLE_COLOR_BALL -> "双色球"
        LotteryType.HAPPY_8 -> "快乐8"
    }

/** 返回跨期模式在配置控件中的名称。 */
private fun CrossPeriodRandomMode.displayName(): String =
    when (this) {
        CrossPeriodRandomMode.REUSE_SAME_LINES -> "跨期复用"
        CrossPeriodRandomMode.INDEPENDENT_PER_PERIOD -> "逐期独立"
    }

/** 返回跨期模式在生成结果中的说明。 */
private fun CrossPeriodRandomMode.resultDescription(): String =
    when (this) {
        CrossPeriodRandomMode.REUSE_SAME_LINES -> "所有期次复用同一批号码"
        CrossPeriodRandomMode.INDEPENDENT_PER_PERIOD -> "每一期按相同注数独立随机"
    }

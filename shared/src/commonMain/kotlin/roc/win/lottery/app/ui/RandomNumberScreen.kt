package roc.win.lottery.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import roc.win.lottery.app.MainDestination
import roc.win.lottery.app.RandomNumberPickerAction
import roc.win.lottery.app.RandomNumberPickerState
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
            contentPadding = PaddingValues(top = 24.dp, bottom = 32.dp),
        ) {
            item(key = "configuration") {
                CenteredNumberContent {
                    Text("随机选号", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "生成结果仅供娱乐，不代表预测，不构成购彩建议。",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(28.dp))
                    Text("彩种", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    LotteryTypeSelector(
                        selected = picker.lotteryType,
                        onSelected = {
                            onAction(RandomNumberPickerAction.ChangeLotteryType(it))
                        },
                    )
                    Spacer(Modifier.height(24.dp))
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
                    Spacer(Modifier.height(24.dp))
                    CrossPeriodModeSelector(
                        lotteryType = picker.lotteryType,
                        selected = picker.crossPeriodMode,
                        onSelected = {
                            onAction(RandomNumberPickerAction.ChangeCrossPeriodMode(it))
                        },
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "共 ${picker.totalBetCount} 注（${picker.periodCount} 期 × 每期 ${picker.betsPerPeriod} 注）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { onAction(RandomNumberPickerAction.Generate) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        shape = MaterialTheme.shapes.small,
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                    ) {
                        Icon(LotteryIcons.NumberPicker, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text(if (picker.plan == null) "生成号码" else "重新生成")
                    }
                    picker.errorMessage?.let { message ->
                        Spacer(Modifier.height(12.dp))
                        Text(
                            message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            picker.plan?.let { plan ->
                item(key = "result-header") {
                    CenteredNumberContent {
                        Spacer(Modifier.height(32.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(Modifier.height(24.dp))
                        RandomNumberResultHeader(
                            plan = plan,
                            selectedPeriodIndex = selectedPeriodIndex,
                            onPeriodSelected = { selectedPeriodIndex = it },
                        )
                    }
                }

                itemsIndexed(
                    items = selectedPeriod?.lines.orEmpty(),
                    key = { index, _ -> "period-${selectedPeriod?.periodIndex}-line-$index" },
                ) { index, line ->
                    CenteredNumberContent {
                        Spacer(Modifier.height(if (index == 0) 16.dp else 10.dp))
                        GeneratedNumberLineItem(
                            lineNumber = index + 1,
                            line = line,
                            lotteryType = plan.lotteryType,
                        )
                    }
                }

                item(key = "result-footer") {
                    CenteredNumberContent {
                        Spacer(Modifier.height(20.dp))
                        Text(
                            "期次为当前方案内的相对顺序，不代表官方期号；号码不会保存为已购票据。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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

/** 使用分段控件选择当前彩种。 */
@Composable
private fun LotteryTypeSelector(
    selected: LotteryType,
    onSelected: (LotteryType) -> Unit,
) {
    val lotteryTypes = listOf(LotteryType.SUPER_LOTTO, LotteryType.DOUBLE_COLOR_BALL)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        lotteryTypes.forEachIndexed { index, lotteryType ->
            SegmentedButton(
                selected = lotteryType == selected,
                onClick = { onSelected(lotteryType) },
                shape = SegmentedButtonDefaults.itemShape(index, lotteryTypes.size),
                label = { Text(lotteryType.shortDisplayName()) },
            )
        }
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
                horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                verticalArrangement = Arrangement.spacedBy(12.dp),
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
    Surface(
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton(
                onClick = { onValueChange(value - 1) },
                enabled = value > range.first,
            ) {
                Icon(LotteryIcons.Minus, contentDescription = "减少$title")
            }
            Box(modifier = Modifier.widthIn(min = 54.dp), contentAlignment = Alignment.Center) {
                Text("$value $valueSuffix", style = MaterialTheme.typography.labelLarge)
            }
            IconButton(
                onClick = { onValueChange(value + 1) },
                enabled = value < range.last,
            ) {
                Icon(LotteryIcons.Plus, contentDescription = "增加$title")
            }
        }
    }
}

/** 展示当前彩种允许的跨期生成模式。 */
@Composable
private fun CrossPeriodModeSelector(
    lotteryType: LotteryType,
    selected: CrossPeriodRandomMode,
    onSelected: (CrossPeriodRandomMode) -> Unit,
) {
    Text("跨期方式", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(10.dp))
    if (lotteryType == LotteryType.SUPER_LOTTO) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    LotteryIcons.Done,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.width(10.dp))
                Text("所有期次复用同一批号码", style = MaterialTheme.typography.bodyLarge)
            }
        }
    } else {
        val modes =
            listOf(
                CrossPeriodRandomMode.REUSE_SAME_LINES,
                CrossPeriodRandomMode.INDEPENDENT_PER_PERIOD,
            )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = mode == selected,
                    onClick = { onSelected(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                    label = { Text(mode.displayName()) },
                )
            }
        }
    }
}

/** 展示生成方案摘要、相对期次选择器和当前期次标题。 */
@Composable
private fun RandomNumberResultHeader(
    plan: RandomNumberPlan,
    selectedPeriodIndex: Int,
    onPeriodSelected: (Int) -> Unit,
) {
    Text("生成结果", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(6.dp))
    Text(
        "${plan.lotteryType.shortDisplayName()} · ${plan.periodCount} 期 · 每期 ${plan.betsPerPeriod} 注",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        plan.crossPeriodMode.resultDescription(),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (plan.periods.size > 1) {
        Spacer(Modifier.height(16.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items = plan.periods, key = { it.periodIndex }) { period ->
                val index = period.periodIndex - 1
                FilterChip(
                    selected = selectedPeriodIndex == index,
                    onClick = { onPeriodSelected(index) },
                    label = { Text("第 ${period.periodIndex} 期") },
                )
            }
        }
    }
    Spacer(Modifier.height(18.dp))
    Text(
        "第 ${selectedPeriodIndex + 1} 期号码",
        style = MaterialTheme.typography.titleLarge,
    )
}

/** 展示一注随机号码，主号码与次号码使用稳定的红蓝语义。 */
@Composable
private fun GeneratedNumberLineItem(
    lineNumber: Int,
    line: GeneratedNumberLine,
    lotteryType: LotteryType,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("第 $lineNumber 注", style = MaterialTheme.typography.titleMedium)
            GeneratedNumberArea(
                label = if (lotteryType == LotteryType.SUPER_LOTTO) "前区" else "红球",
                numbers = line.primaryNumbers,
                background = MaterialTheme.colorScheme.primary,
                foreground = MaterialTheme.colorScheme.onPrimary,
            )
            GeneratedNumberArea(
                label = if (lotteryType == LotteryType.SUPER_LOTTO) "后区" else "蓝球",
                numbers = line.secondaryNumbers,
                background = MaterialTheme.colorScheme.secondary,
                foreground = MaterialTheme.colorScheme.onSecondary,
            )
        }
    }
}

/** 展示一注号码中的单个规则区域。 */
@Composable
private fun GeneratedNumberArea(
    label: String,
    numbers: List<Int>,
    background: Color,
    foreground: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            numbers.forEach { number ->
                GeneratedNumberBall(
                    number = number,
                    background = background,
                    foreground = foreground,
                )
            }
        }
    }
}

/** 绘制固定尺寸的两位随机号码球。 */
@Composable
private fun GeneratedNumberBall(
    number: Int,
    background: Color,
    foreground: Color,
) {
    Surface(
        modifier = Modifier.size(48.dp),
        shape = CircleShape,
        color = background,
        contentColor = foreground,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(number.toString().padStart(2, '0'), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 返回彩种在紧凑控件中的名称。 */
private fun LotteryType.shortDisplayName(): String =
    when (this) {
        LotteryType.SUPER_LOTTO -> "大乐透"
        LotteryType.DOUBLE_COLOR_BALL -> "双色球"
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

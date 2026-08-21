package roc.win.lottery.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import roc.win.lottery.app.TicketLineReviewState
import roc.win.lottery.app.TicketNumberArea
import roc.win.lottery.app.TicketReviewEvaluation
import roc.win.lottery.app.TicketReviewState
import roc.win.lottery.domain.DrawResult
import roc.win.lottery.domain.IssueSequenceResolver
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.TicketFieldOrigin
import roc.win.lottery.domain.TicketValidationProblem
import roc.win.lottery.recognition.ImageRef
import roc.win.lottery.recognition.TicketFieldCandidate
import roc.win.lottery.recognition.TicketFieldRegion

/** 显示本地分析进度和取消入口。 */
@Composable
fun AnalysisScreen(
    title: String,
    detail: String,
    progress: Float,
    onCancel: () -> Unit,
) {
    AppShell(
        title = "分析彩票",
        navigationIcon = LotteryIcons.Back,
        onNavigate = onCancel,
    ) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(48.dp))
            CircularProgressIndicator()
            Spacer(Modifier.height(28.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(10.dp))
            Text(detail, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(28.dp))
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(32.dp))
            OutlinedButton(onClick = onCancel, shape = MaterialTheme.shapes.small) {
                Text("取消")
            }
        }
    }
}

/**
 * 显示 Android 和 iOS 共用的票面人工校正页面。
 *
 * @param editor 当前不可变编辑状态。
 * @param evaluation 当前领域评估。
 * @param imageRef OCR 流程的私有临时图片引用，手动录入时为 `null`。
 * @param fieldRegions 可在原图中定位的 OCR 字段区域。
 * @param fieldCandidates OCR 无法唯一确定、需要用户选择的字段候选。
 * @param manualEntryReason OCR 无法安全形成草稿时，转为原图辅助手动录入的原因。
 * @param isDemo 开奖等后续能力是否仍为开发演示实现。
 * @param usesRealRecognition 当前草稿是否来自真实图片导入和本地 OCR。
 * @param usesRealDrawData 确认后是否查询真实官网开奖数据。
 * @param onBack 返回并清理当前临时票图的操作。
 * @param onLotteryTypeChange 修改彩种。
 * @param onIssueChange 修改期号。
 * @param onNumberToggle 切换指定投注行的号码球。
 * @param onAddBetLine 新增一行手动单式投注。
 * @param onRemoveBetLine 删除一行手动单式投注。
 * @param onMultiplierChange 修改倍数。
 * @param onPeriodCountChange 修改连续投注期数。
 * @param onAdditionalChange 修改大乐透追加属性。
 * @param onPaidAmountChange 修改票面金额。
 * @param onUseCalculatedAmount 使用当前投注结构推导金额。
 * @param onConfirm 用户确认当前字段的操作。
 */
@Composable
fun ReviewScreen(
    editor: TicketReviewState,
    evaluation: TicketReviewEvaluation,
    imageRef: ImageRef?,
    fieldRegions: List<TicketFieldRegion>,
    fieldCandidates: List<TicketFieldCandidate>,
    manualEntryReason: String?,
    isDemo: Boolean,
    usesRealRecognition: Boolean,
    usesRealDrawData: Boolean,
    onBack: () -> Unit,
    onLotteryTypeChange: (LotteryType) -> Unit,
    onIssueChange: (String) -> Unit,
    onNumberToggle: (Int, TicketNumberArea, Int) -> Unit,
    onAddBetLine: () -> Unit,
    onRemoveBetLine: (Int) -> Unit,
    onMultiplierChange: (Int) -> Unit,
    onPeriodCountChange: (Int) -> Unit,
    onAdditionalChange: (Boolean) -> Unit,
    onPaidAmountChange: (String) -> Unit,
    onUseCalculatedAmount: () -> Unit,
    onConfirm: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val isImageAssistedManualEntry = imageRef != null && manualEntryReason != null
    val isManualEntry = imageRef == null || isImageAssistedManualEntry
    val previewState = if (usesRealRecognition && imageRef != null) rememberTicketPreviewState(imageRef) else null
    AppShell(
        title =
            when {
                isImageAssistedManualEntry -> "对照原图录入"
                isManualEntry -> "手动录入彩票"
                else -> "确认票面信息"
            },
        navigationIcon = LotteryIcons.Back,
        onNavigate = onBack,
    ) {
        if (isDemo) {
            StatusBanner(
                when {
                    isImageAssistedManualEntry -> {
                        "本地 OCR 未能安全形成完整草稿：$manualEntryReason。以下字段保持空白，请对照原图逐项录入。"
                    }

                    isManualEntry && usesRealDrawData -> {
                        "手动录入会绕过 OCR；确认后会精确查询该期官网数据并在本机测算。当前仍是未通过正式对账的移动验证版。"
                    }

                    usesRealRecognition && usesRealDrawData -> {
                        "票面来自本地 OCR；确认后会精确查询该期官网数据并在本机测算。当前仍是未通过正式对账的移动验证版。"
                    }

                    usesRealRecognition -> {
                        "票面来自本地 OCR，可逐项校正；继续后仍使用固定演示开奖数据，不用于真实中奖判断。"
                    }

                    else -> {
                        "以下字段来自固定演示票据，用于验证人工校正和确认流程。"
                    }
                },
            )
            Spacer(Modifier.height(20.dp))
        }
        previewState?.let { state ->
            TicketImagePreview(state = state, fieldRegions = fieldRegions)
            Spacer(Modifier.height(24.dp))
        }
        Text("请逐项核对", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "关键字段未经确认，不会查询开奖或输出中奖结论。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Text("彩种", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LotteryType.entries.forEach { lotteryType ->
                FilterChip(
                    selected = editor.lotteryType.value == lotteryType,
                    onClick = { onLotteryTypeChange(lotteryType) },
                    label = { Text(lotteryType.displayName()) },
                )
            }
        }
        evaluation.problems.firstMessageFor("lotteryType")?.let { message ->
            FieldProblem(message)
        }
        Spacer(Modifier.height(18.dp))
        OutlinedTextField(
            value = editor.issue.value,
            onValueChange = onIssueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("开奖期号") },
            singleLine = true,
            isError = evaluation.problems.hasField("issue"),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            trailingIcon = {
                IconButton(onClick = { focusManager.clearFocus() }) {
                    Icon(LotteryIcons.Done, contentDescription = "完成期号输入")
                }
            },
        )
        CandidateChoiceRow(
            title = "识别到多个期号，请按原图选择",
            values = fieldCandidates.filterIsInstance<TicketFieldCandidate.Issue>().map { it.value },
            selectedValue = editor.issue.value,
            onSelect = onIssueChange,
        )
        evaluation.problems.firstMessageFor("issue")?.let { message ->
            FieldProblem(message)
        }
        val selectedLotteryType = editor.lotteryType.value
        if (selectedLotteryType != null) {
            Spacer(Modifier.height(24.dp))
            Text("投注号码", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            editor.betLines.forEachIndexed { index, line ->
                val removeAction: (() -> Unit)? =
                    if (isManualEntry) {
                        { onRemoveBetLine(index) }
                    } else {
                        null
                    }
                TicketLineEditor(
                    lineIndex = index,
                    line = line,
                    lotteryType = selectedLotteryType,
                    problems = evaluation.problems,
                    canRemove = isManualEntry && editor.betLines.size > 1,
                    onRemove = removeAction,
                    onNumberToggle = onNumberToggle,
                )
                Spacer(Modifier.height(10.dp))
            }
            if (isManualEntry) {
                OutlinedButton(
                    onClick = onAddBetLine,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Icon(LotteryIcons.Plus, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("添加一注")
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("投注属性", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        MultiplierEditor(
            multiplier = editor.multiplier.value,
            onMultiplierChange = onMultiplierChange,
        )
        evaluation.problems.firstMessageFor("multiplier")?.let { message ->
            FieldProblem(message)
        }
        Spacer(Modifier.height(12.dp))
        if (editor.lotteryType.value == LotteryType.SUPER_LOTTO) {
            SettingRow(
                title = "追加投注",
                detail =
                    when (editor.isAdditional) {
                        true -> "每注增加 1 元"
                        false -> "基本投注"
                        null -> "待确认"
                    },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = editor.isAdditional == false,
                        onClick = { onAdditionalChange(false) },
                        label = { Text("基本") },
                    )
                    FilterChip(
                        selected = editor.isAdditional == true,
                        onClick = { onAdditionalChange(true) },
                        label = { Text("追加") },
                    )
                }
            }
            evaluation.problems.firstMessageFor("isAdditional")?.let { message ->
                FieldProblem(message)
            }
            Spacer(Modifier.height(12.dp))
        }
        PeriodCountEditor(
            periodCount = editor.periodCount.value,
            onPeriodCountChange = onPeriodCountChange,
        )
        evaluation.problems.firstMessageFor("periodCount")?.let { message ->
            FieldProblem(message)
        }
        Spacer(Modifier.height(18.dp))
        Text("票面金额", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = editor.paidAmountYuan.value,
            onValueChange = onPaidAmountChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("合计金额") },
            suffix = { Text("元") },
            singleLine = true,
            isError = evaluation.problems.hasField("paidAmountFen"),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            trailingIcon = {
                IconButton(onClick = { focusManager.clearFocus() }) {
                    Icon(LotteryIcons.Done, contentDescription = "完成金额输入")
                }
            },
        )
        CandidateChoiceRow(
            title = "识别到多个合计金额，请按原图选择",
            values =
                fieldCandidates
                    .filterIsInstance<TicketFieldCandidate.PaidAmount>()
                    .map { it.valueFen.toYuanInput() },
            selectedValue = editor.paidAmountYuan.value,
            valueSuffix = " 元",
            onSelect = onPaidAmountChange,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "按当前投注计算：${editor.calculatedAmountFen.toYuanText()}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = onUseCalculatedAmount,
                enabled = editor.calculatedAmountFen != null,
            ) {
                Text("使用计算金额")
            }
        }
        evaluation.problems.firstMessageFor("paidAmountFen")?.let { message ->
            FieldProblem(message)
        }
        if (evaluation.problems.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            ValidationSummary(evaluation.problems)
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onConfirm,
            enabled = evaluation.canConfirm && (previewState == null || previewState is TicketPreviewState.Ready),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = MaterialTheme.shapes.small,
        ) {
            Text("已核对，继续")
        }
    }
}

/**
 * 显示一组需要用户对照原图明确选择的 OCR 候选。
 *
 * @param title 候选字段说明。
 * @param values 可写入编辑器的候选值。
 * @param selectedValue 当前编辑值。
 * @param valueSuffix 仅用于候选标签的单位后缀。
 * @param onSelect 用户选择候选值的操作。
 */
@Composable
private fun CandidateChoiceRow(
    title: String,
    values: List<String>,
    selectedValue: String,
    valueSuffix: String = "",
    onSelect: (String) -> Unit,
) {
    if (values.isEmpty()) return
    val instructionColor =
        if (selectedValue in values) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.error
        }
    Spacer(Modifier.height(8.dp))
    Text(title, style = MaterialTheme.typography.bodyMedium, color = instructionColor)
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        values.forEach { value ->
            FilterChip(
                selected = value == selectedValue,
                onClick = { onSelect(value) },
                label = { Text(value + valueSuffix) },
            )
        }
    }
}

/**
 * 显示一行单式投注的号码球编辑器。
 *
 * @param lineIndex 投注行下标。
 * @param line 当前行编辑状态。
 * @param lotteryType 当前玩法。
 * @param problems 当前领域问题。
 * @param canRemove 当前投注行是否允许删除。
 * @param onRemove 删除当前投注行的操作；OCR 校正模式为 `null`。
 * @param onNumberToggle 切换号码球的操作。
 */
@Composable
private fun TicketLineEditor(
    lineIndex: Int,
    line: TicketLineReviewState,
    lotteryType: LotteryType,
    problems: List<TicketValidationProblem>,
    canRemove: Boolean,
    onRemove: (() -> Unit)?,
    onNumberToggle: (Int, TicketNumberArea, Int) -> Unit,
) {
    val spec = LotteryNumberSpec.forLottery(lotteryType)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("第 ${lineIndex + 1} 注", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        line.displayOrigin().displayName(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    onRemove?.let { remove ->
                        IconButton(onClick = remove, enabled = canRemove) {
                            Icon(LotteryIcons.Delete, contentDescription = "删除本注")
                        }
                    }
                }
            }
            NumberAreaEditor(
                title = spec.primaryName,
                selectedNumbers = line.primaryNumbers.value,
                expectedCount = spec.primaryCount,
                range = spec.primaryRange,
                selectedColor = MaterialTheme.colorScheme.primary,
                onToggle = { number -> onNumberToggle(lineIndex, TicketNumberArea.PRIMARY, number) },
            )
            problems.firstMessageFor("betLines[$lineIndex].primaryNumbers")?.let { message ->
                FieldProblem(message)
            }
            NumberAreaEditor(
                title = spec.secondaryName,
                selectedNumbers = line.secondaryNumbers.value,
                expectedCount = spec.secondaryCount,
                range = spec.secondaryRange,
                selectedColor = MaterialTheme.colorScheme.secondary,
                onToggle = { number -> onNumberToggle(lineIndex, TicketNumberArea.SECONDARY, number) },
            )
            problems.firstMessageFor("betLines[$lineIndex].secondaryNumbers")?.let { message ->
                FieldProblem(message)
            }
            if (line.originalText.isNotBlank()) {
                Text(
                    "OCR 原文：${line.originalText}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 显示一个可横向滚动的号码区域。 */
@Composable
private fun NumberAreaEditor(
    title: String,
    selectedNumbers: List<Int>,
    expectedCount: Int,
    range: IntRange,
    selectedColor: androidx.compose.ui.graphics.Color,
    onToggle: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "$title（已选 ${selectedNumbers.size}/$expectedCount）",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val lastVisibleNumber = maxOf(range.last, selectedNumbers.maxOrNull() ?: range.last)
            (range.first..lastVisibleNumber).forEach { number ->
                val selected = number in selectedNumbers
                NumberBall(
                    number = number,
                    selected = selected,
                    enabled = selected || (number in range && selectedNumbers.size < expectedCount),
                    selectedColor = selectedColor,
                    onToggle = { onToggle(number) },
                )
            }
        }
    }
}

/** 显示一个尺寸稳定、可选择的号码球。 */
@Composable
private fun NumberBall(
    number: Int,
    selected: Boolean,
    enabled: Boolean,
    selectedColor: androidx.compose.ui.graphics.Color,
    onToggle: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .size(48.dp)
                .selectable(
                    selected = selected,
                    enabled = enabled,
                    role = Role.Checkbox,
                    onClick = onToggle,
                ),
        shape = CircleShape,
        color =
            when {
                selected -> selectedColor
                enabled -> MaterialTheme.colorScheme.surface
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
        contentColor =
            when {
                selected -> MaterialTheme.colorScheme.onPrimary
                enabled -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        border =
            if (selected) {
                null
            } else {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(number.toString().padStart(2, '0'), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 显示投注倍数步进器。 */
@Composable
private fun MultiplierEditor(
    multiplier: Int?,
    onMultiplierChange: (Int) -> Unit,
) {
    SettingRow(title = "投注倍数", detail = if (multiplier == null) "待确认" else "可选 1 至 99 倍") {
        if (multiplier == null) {
            TextButton(onClick = { onMultiplierChange(MIN_MULTIPLIER) }) {
                Text("确认 1 倍")
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { onMultiplierChange(multiplier - 1) },
                    enabled = multiplier > MIN_MULTIPLIER,
                ) {
                    Icon(LotteryIcons.Minus, contentDescription = "减少倍数")
                }
                Text(
                    "$multiplier 倍",
                    modifier = Modifier.width(64.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
                IconButton(
                    onClick = { onMultiplierChange(multiplier + 1) },
                    enabled = multiplier < MAX_MULTIPLIER,
                ) {
                    Icon(LotteryIcons.Plus, contentDescription = "增加倍数")
                }
            }
        }
    }
}

/** 显示连续投注期数步进器。 */
@Composable
private fun PeriodCountEditor(
    periodCount: Int,
    onPeriodCountChange: (Int) -> Unit,
) {
    SettingRow(
        title = "投注期数",
        detail = if (periodCount == 1) "单期开奖核对" else "从起始期号逐期核对",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    onPeriodCountChange(
                        if (periodCount > IssueSequenceResolver.MAX_PERIOD_COUNT) {
                            IssueSequenceResolver.MAX_PERIOD_COUNT
                        } else {
                            periodCount - 1
                        },
                    )
                },
                enabled = periodCount > IssueSequenceResolver.MIN_PERIOD_COUNT,
            ) {
                Icon(LotteryIcons.Minus, contentDescription = "减少投注期数")
            }
            Text(
                "$periodCount 期",
                modifier = Modifier.width(64.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            IconButton(
                onClick = { onPeriodCountChange(periodCount + 1) },
                enabled = periodCount < IssueSequenceResolver.MAX_PERIOD_COUNT,
            ) {
                Icon(LotteryIcons.Plus, contentDescription = "增加投注期数")
            }
        }
    }
}

/** 显示一行投注设置和右侧控件。 */
@Composable
private fun SettingRow(
    title: String,
    detail: String,
    control: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            control()
        }
    }
}

/** 在对应输入下方显示一条领域问题。 */
@Composable
private fun FieldProblem(message: String) {
    Text(
        message,
        modifier = Modifier.padding(start = 16.dp, top = 4.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

/** 汇总当前仍阻断确认的领域问题。 */
@Composable
private fun ValidationSummary(problems: List<TicketValidationProblem>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("请先修正以下信息", style = MaterialTheme.typography.titleMedium)
            problems.distinctBy { it.field to it.message }.forEach { problem ->
                Text(
                    "• ${problem.message}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

/**
 * 显示开奖查询中的明确状态。
 *
 * @param issue 用户确认并正在精确查询的期号。
 * @param completedPeriodCount 本轮已完成的期次数量。
 * @param totalPeriodCount 本轮需要查询的期次数量。
 * @param usesRealDrawData 是否正在核对真实官网数据。
 * @param onCancel 取消查询并返回首页。
 */
@Composable
fun DrawQueryScreen(
    issue: String,
    completedPeriodCount: Int,
    totalPeriodCount: Int,
    usesRealDrawData: Boolean,
    onCancel: () -> Unit,
) {
    AppShell(
        title = "查询开奖",
        navigationIcon = LotteryIcons.Back,
        onNavigate = onCancel,
    ) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(64.dp))
            CircularProgressIndicator()
            Spacer(Modifier.height(24.dp))
            Text(
                if (totalPeriodCount > 1) {
                    "正在查询第 $issue 期（${completedPeriodCount + 1}/$totalPeriodCount）"
                } else {
                    "正在查询第 $issue 期"
                },
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                if (usesRealDrawData) {
                    if (totalPeriodCount > 1) {
                        "正在按顺序核对每一期的两份官方数据"
                    } else {
                        "正在核对两份官方数据，只查询你确认的单个期号"
                    }
                } else {
                    "只有用户确认后才会发起单期查询"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 显示开发状态流完成结果，不声称完成真实测算。
 *
 * @param drawResult Fake 开奖仓库返回的演示结果。
 * @param usesRealRecognition 票面是否来自移动端真实本地 OCR。
 * @param onBack 返回票面确认页。
 * @param onDone 返回首页的操作。
 */
@Composable
fun DemoCompleteScreen(
    drawResult: DrawResult,
    usesRealRecognition: Boolean,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    AppShell(
        title = "流程演示完成",
        navigationIcon = LotteryIcons.Back,
        onNavigate = onBack,
    ) {
        StatusBanner("开发链路已跑通，本页不代表真实中奖结果。")
        Spacer(Modifier.height(24.dp))
        Text(
            if (usesRealRecognition) "移动端校正链路可用" else "共享演示状态流可用",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            if (usesRealRecognition) {
                "票面已由本地 OCR、保守解析和人工校正生成；开奖数据仍为固定演示数据。"
            } else {
                "票面结构已使用保守解析器；图片采集、OCR 和开奖查询仍为固定演示实现。"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        InfoSection(
            rows =
                listOf(
                    "演示彩种" to drawResult.lotteryType.displayName(),
                    "演示期号" to drawResult.issue.value,
                    "数据来源" to drawResult.evidence.sourceName,
                    "规则标识" to drawResult.ruleVersion,
                ),
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = MaterialTheme.shapes.small,
        ) {
            Text("返回首页")
        }
    }
}

/**
 * 显示可恢复错误状态。
 *
 * @param title 错误标题。
 * @param message 不包含敏感内容的恢复说明。
 * @param onBack 返回上一级页面。
 * @param onDone 清理当前流程并返回首页。
 */
@Composable
fun ErrorScreen(
    title: String,
    message: String,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    AppShell(
        title = "无法继续",
        navigationIcon = LotteryIcons.Back,
        onNavigate = onBack,
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(28.dp))
        Button(onClick = onDone, shape = MaterialTheme.shapes.small) {
            Text("返回首页")
        }
    }
}

/** 显示关于、隐私和 V1 支持范围。 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    AppShell(
        title = "关于与隐私",
        navigationIcon = LotteryIcons.Back,
        onNavigate = onBack,
    ) {
        Text("数据如何处理", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        PolicyRow("本地处理", "图片、OCR 文本和投注号码默认只在当前设备处理。")
        PolicyRow("最少查询", "查询开奖时只发送用户确认的彩种和期号。")
        PolicyRow("不留历史", "V1 不保存票图、官网原始响应或测算历史。")
        Spacer(Modifier.height(28.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(24.dp))
        Text("V1 支持范围", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Text(
            V1_SUPPORTED_TICKET_SCOPE_TEXT,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            V1_UNSUPPORTED_TICKET_SCOPE_TEXT,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        StatusBanner("中奖测算不等于彩票验真，也不能确认撤单、作废或兑奖状态。")
    }
}

/** 展示成组键值信息。 */
@Composable
private fun InfoSection(rows: List<Pair<String, String>>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            rows.forEachIndexed { index, row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        row.first,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(24.dp))
                    Text(row.second, style = MaterialTheme.typography.titleMedium)
                }
                if (index != rows.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

/** 展示一条隐私原则。 */
@Composable
private fun PolicyRow(
    title: String,
    body: String,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Text(title, modifier = Modifier.width(92.dp), style = MaterialTheme.typography.titleMedium)
        Text(body, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

/** 展示流程内需要醒目标识的信息。 */
@Composable
private fun StatusBanner(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            message,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** 返回彩种的中文名称。 */
private fun LotteryType?.displayName(): String =
    when (this) {
        LotteryType.SUPER_LOTTO -> "超级大乐透"
        LotteryType.DOUBLE_COLOR_BALL -> "双色球"
        null -> "待确认"
    }

/** 把分转换为不参与计算的展示文本。 */
private fun Long?.toYuanText(): String =
    if (this == null) {
        "待确认"
    } else {
        "${this / 100}.${(this % 100).toString().padStart(2, '0')} 元"
    }

/** 把 OCR 金额候选转换为金额输入框使用的元文本。 */
private fun Long.toYuanInput(): String = "${this / 100}.${(this % 100).toString().padStart(2, '0')}"

/** 判断问题列表是否包含指定字段。 */
private fun List<TicketValidationProblem>.hasField(field: String): Boolean = any { it.field == field }

/** 返回指定字段的首条问题说明。 */
private fun List<TicketValidationProblem>.firstMessageFor(field: String): String? =
    firstOrNull { it.field == field }?.message

/** 返回校正字段来源的简短中文名称。 */
private fun TicketFieldOrigin.displayName(): String =
    when (this) {
        TicketFieldOrigin.OCR -> "OCR 识别"
        TicketFieldOrigin.USER -> "用户输入"
        TicketFieldOrigin.DERIVED -> "规则推导"
    }

/** 汇总一行投注当前最需要展示的字段来源。 */
private fun TicketLineReviewState.displayOrigin(): TicketFieldOrigin =
    when {
        primaryNumbers.origin == TicketFieldOrigin.USER ||
            secondaryNumbers.origin == TicketFieldOrigin.USER ||
            isAdditional.origin == TicketFieldOrigin.USER -> TicketFieldOrigin.USER

        primaryNumbers.origin == TicketFieldOrigin.DERIVED ||
            secondaryNumbers.origin == TicketFieldOrigin.DERIVED ||
            isAdditional.origin == TicketFieldOrigin.DERIVED -> TicketFieldOrigin.DERIVED

        else -> TicketFieldOrigin.OCR
    }

/**
 * 号码球编辑器使用的玩法规格。
 *
 * @property primaryName 主号码区域名称。
 * @property primaryCount 主号码数量。
 * @property primaryRange 主号码范围。
 * @property secondaryName 次号码区域名称。
 * @property secondaryCount 次号码数量。
 * @property secondaryRange 次号码范围。
 */
private data class LotteryNumberSpec(
    val primaryName: String,
    val primaryCount: Int,
    val primaryRange: IntRange,
    val secondaryName: String,
    val secondaryCount: Int,
    val secondaryRange: IntRange,
) {
    /** 创建当前玩法的 V1 单式号码规格。 */
    companion object {
        /** 返回当前玩法的号码规格。 */
        fun forLottery(lotteryType: LotteryType): LotteryNumberSpec =
            when (lotteryType) {
                LotteryType.DOUBLE_COLOR_BALL -> {
                    LotteryNumberSpec("红球", 6, 1..33, "蓝球", 1, 1..16)
                }

                LotteryType.SUPER_LOTTO -> {
                    LotteryNumberSpec("前区", 5, 1..35, "后区", 2, 1..12)
                }
            }
    }
}

/** V1 最小投注倍数。 */
private const val MIN_MULTIPLIER = 1

/** V1 最大投注倍数。 */
private const val MAX_MULTIPLIER = 99

/** 关于页展示的 V1 已支持票面范围。 */
internal const val V1_SUPPORTED_TICKET_SCOPE_TEXT =
    "单张电脑打印的超级大乐透或双色球彩票；支持 1 至 20 期受控连续投注、单式、多注单式、倍数及大乐透追加。"

/** 关于页展示的 V1 未支持票面范围。 */
internal const val V1_UNSUPPORTED_TICKET_SCOPE_TEXT =
    "不支持复式、胆拖、补打票、超过 20 期、跨年度未知期次、手写票、电子截图或未知票面版式。"

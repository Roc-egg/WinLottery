package roc.win.lottery.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import roc.win.lottery.app.AppScreen
import roc.win.lottery.app.TicketLineReviewState
import roc.win.lottery.app.TicketNumberArea
import roc.win.lottery.app.TicketReviewEvaluation
import roc.win.lottery.app.TicketReviewState
import roc.win.lottery.app.theme.LotteryThemeTokens
import roc.win.lottery.domain.BetCombinations
import roc.win.lottery.domain.DrawResult
import roc.win.lottery.domain.IssueSequenceResolver
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.PrizeTierCodes
import roc.win.lottery.domain.TicketFieldOrigin
import roc.win.lottery.domain.TicketValidationProblem
import roc.win.lottery.persistence.ThemePreference
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
        ProgressStage(icon = LotteryIcons.Scan, title = title, detail = detail) {
            Spacer(Modifier.height(28.dp))
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                drawStopIndicator = {},
                gapSize = 0.dp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
            SecondaryActionButton(text = "取消", onClick = onCancel, modifier = Modifier.widthIn(max = 220.dp))
        }
    }
}

/**
 * 进度类页面的居中舞台：带光晕的图标、标题、说明和附加内容。
 *
 * @param icon 主图标。
 * @param title 阶段标题。
 * @param detail 阶段说明。
 * @param content 说明下方的附加内容。
 */
@Composable
private fun ProgressStage(
    icon: ImageVector,
    title: String,
    detail: String,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)),
            )
            CircularProgressIndicator(
                modifier = Modifier.size(96.dp),
                strokeWidth = 3.dp,
                trackColor = MaterialTheme.colorScheme.primaryContainer,
            )
            IconTile(icon = icon, size = 64.dp, container = MaterialTheme.colorScheme.surface)
        }
        Spacer(Modifier.height(28.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        content()
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
 * @param saveError 本机结构化记录保存失败时的安全恢复说明。
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
    saveError: String?,
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
    val previewState = imageRef?.let { rememberTicketPreviewState(it) }
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
            Notice(
                message =
                    when {
                        isImageAssistedManualEntry -> {
                            "本地 OCR 未能安全形成完整草稿：$manualEntryReason。以下字段保持空白，请对照原图逐项录入。"
                        }

                        isManualEntry && usesRealDrawData -> {
                            "手动录入会绕过 OCR；确认后会精确查询该期官网数据并在本机测算。当前仍是预览验证版。"
                        }

                        usesRealRecognition && usesRealDrawData -> {
                            "票面来自本地 OCR；确认后会精确查询该期官网数据并在本机测算。当前仍是预览验证版。"
                        }

                        usesRealRecognition -> {
                            "票面来自本地 OCR，可逐项校正；继续后仍使用固定演示开奖数据，不用于真实中奖判断。"
                        }

                        else -> {
                            "以下字段来自固定演示票据，用于验证人工校正和确认流程。"
                        }
                    },
            )
            Spacer(Modifier.height(16.dp))
        }
        saveError?.let { message ->
            Notice(message = message, tone = NoticeTone.ERROR)
            Spacer(Modifier.height(16.dp))
        }
        previewState?.let { state ->
            TicketImagePreview(state = state, fieldRegions = fieldRegions)
            Spacer(Modifier.height(SectionSpacing))
        }
        SectionHeader(title = "请逐项核对", subtitle = "关键字段未经确认，不会查询开奖或输出中奖结论。")
        Spacer(Modifier.height(SectionTitleSpacing))
        LotteryCard(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FieldLabel("彩种")
            SegmentedControl<LotteryType?>(
                options = LotteryType.entries,
                selected = editor.lotteryType.value,
                label = { it.displayName() },
                onSelect = { lotteryType -> lotteryType?.let(onLotteryTypeChange) },
            )
            evaluation.problems.firstMessageFor("lotteryType")?.let { message ->
                FieldProblem(message)
            }
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = editor.issue.value,
                onValueChange = onIssueChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("开奖期号") },
                singleLine = true,
                isError = evaluation.problems.hasField("issue"),
                shape = MaterialTheme.shapes.medium,
                colors = lotteryTextFieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                trailingIcon = {
                    IconButton(onClick = { focusManager.clearFocus() }) {
                        Icon(LotteryIcons.Done, contentDescription = "完成期号输入", modifier = Modifier.size(20.dp))
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
        }
        val selectedLotteryType = editor.lotteryType.value
        if (selectedLotteryType != null) {
            val hasCompound = editor.betLines.any { it.isCompound(selectedLotteryType) }
            Spacer(Modifier.height(SectionSpacing))
            SectionHeader(
                title =
                    if (hasCompound) {
                        "投注号码（共 ${editor.betLines.size} 组 · ${editor.betCount} 注）"
                    } else {
                        "投注号码（共 ${editor.betLines.size} 注）"
                    },
                subtitle =
                    if (!isManualEntry) {
                        null
                    } else if (selectedLotteryType == LotteryType.HAPPY_8) {
                        "快乐8 每注选 1 至 10 个号码，个数即玩法（选一至选十），同一张票各注个数相同；暂不支持复式和胆拖。"
                    } else {
                        "一组号码可以多选，多于单式数量时按复式展开计算注数和金额。"
                    },
            )
            Spacer(Modifier.height(SectionTitleSpacing))
            editor.betLines.forEachIndexed { index, line ->
                val removeAction: (() -> Unit)? =
                    if (isManualEntry) {
                        { onRemoveBetLine(index) }
                    } else {
                        null
                    }
                TicketLineEditor(
                    lineIndex = index,
                    title =
                        if (hasCompound) {
                            betLineTitle(
                                lineIndex = index,
                                betCount = line.betCount(selectedLotteryType),
                                isCompound = line.isCompound(selectedLotteryType),
                                ticketHasCompound = true,
                            )
                        } else {
                            "第 ${index + 1} 注 / 共 ${editor.betLines.size} 注"
                        },
                    line = line,
                    lotteryType = selectedLotteryType,
                    problems = evaluation.problems,
                    canRemove = isManualEntry && editor.betLines.size > 1,
                    onRemove = removeAction,
                    onNumberToggle = onNumberToggle,
                )
                Spacer(Modifier.height(12.dp))
            }
            if (isManualEntry) {
                SecondaryActionButton(text = "添加一注", onClick = onAddBetLine, icon = LotteryIcons.Plus)
            }
        }
        Spacer(Modifier.height(SectionSpacing))
        SectionHeader(title = "投注属性")
        Spacer(Modifier.height(SectionTitleSpacing))
        LotteryCard(
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            MultiplierEditor(
                multiplier = editor.multiplier.value,
                onMultiplierChange = onMultiplierChange,
            )
            evaluation.problems.firstMessageFor("multiplier")?.let { message ->
                FieldProblem(message)
            }
            if (editor.lotteryType.value == LotteryType.SUPER_LOTTO) {
                HairlineDivider()
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
                        ChoicePill(
                            selected = editor.isAdditional == false,
                            onClick = { onAdditionalChange(false) },
                            label = "基本",
                        )
                        ChoicePill(
                            selected = editor.isAdditional == true,
                            onClick = { onAdditionalChange(true) },
                            label = "追加",
                        )
                    }
                }
                evaluation.problems.firstMessageFor("isAdditional")?.let { message ->
                    FieldProblem(message)
                }
            }
            HairlineDivider()
            PeriodCountEditor(
                periodCount = editor.periodCount.value,
                onPeriodCountChange = onPeriodCountChange,
            )
            evaluation.problems.firstMessageFor("periodCount")?.let { message ->
                FieldProblem(message)
            }
        }
        Spacer(Modifier.height(SectionSpacing))
        SectionHeader(title = "票面金额")
        Spacer(Modifier.height(SectionTitleSpacing))
        LotteryCard(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = editor.paidAmountYuan.value,
                onValueChange = onPaidAmountChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("合计金额") },
                suffix = { Text("元") },
                singleLine = true,
                isError = evaluation.problems.hasField("paidAmountFen"),
                shape = MaterialTheme.shapes.medium,
                colors = lotteryTextFieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                trailingIcon = {
                    IconButton(onClick = { focusManager.clearFocus() }) {
                        Icon(LotteryIcons.Done, contentDescription = "完成金额输入", modifier = Modifier.size(20.dp))
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
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                InlineTextButton(
                    text = "使用计算金额",
                    onClick = onUseCalculatedAmount,
                    enabled = editor.calculatedAmountFen != null,
                )
            }
            evaluation.problems.firstMessageFor("paidAmountFen")?.let { message ->
                FieldProblem(message)
            }
        }
        if (evaluation.problems.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            ValidationSummary(evaluation.problems)
        }
        Spacer(Modifier.height(SectionSpacing))
        PrimaryActionButton(
            text = "已核对，继续",
            onClick = onConfirm,
            enabled = evaluation.canConfirm && (previewState == null || previewState is TicketPreviewState.Ready),
        )
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
    Text(title, style = MaterialTheme.typography.bodySmall, color = instructionColor)
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        values.forEach { value ->
            ChoicePill(
                selected = value == selectedValue,
                onClick = { onSelect(value) },
                label = value + valueSuffix,
            )
        }
    }
}

/**
 * 显示一行单式或复式投注的号码球编辑器。
 *
 * @param lineIndex 投注行下标。
 * @param title 投注行标题，含编号和复式注数。
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
    title: String,
    line: TicketLineReviewState,
    lotteryType: LotteryType,
    problems: List<TicketValidationProblem>,
    canRemove: Boolean,
    onRemove: (() -> Unit)?,
    onNumberToggle: (Int, TicketNumberArea, Int) -> Unit,
) {
    val spec = LotteryNumberSpec.forLottery(lotteryType)
    LotteryCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                val origin = line.displayOrigin()
                StatusTag(
                    origin.displayName(),
                    tone = if (origin == TicketFieldOrigin.OCR) TagTone.INFO else TagTone.NEUTRAL,
                )
                onRemove?.let { remove ->
                    Spacer(Modifier.width(8.dp))
                    CircleIconButton(
                        icon = LotteryIcons.Delete,
                        contentDescription = "删除本注",
                        onClick = remove,
                        enabled = canRemove,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            SelectedNumbersLine(
                spec = spec,
                primaryNumbers = line.primaryNumbers.value,
                secondaryNumbers = line.secondaryNumbers.value,
            )
            HairlineDivider()
            NumberAreaEditor(
                title = spec.primaryName,
                selectedNumbers = line.primaryNumbers.value,
                expectedCount = spec.primaryCount,
                maxCount = spec.primaryMaxCount,
                range = spec.primaryRange,
                selectedStyle = BallStyle.RED,
                onToggle = { number -> onNumberToggle(lineIndex, TicketNumberArea.PRIMARY, number) },
            )
            problems.firstMessageFor("betLines[$lineIndex].primaryNumbers")?.let { message ->
                FieldProblem(message)
            }
            if (spec.hasSecondaryArea) {
                NumberAreaEditor(
                    title = spec.secondaryName,
                    selectedNumbers = line.secondaryNumbers.value,
                    expectedCount = spec.secondaryCount,
                    maxCount = spec.secondaryRange.count(),
                    range = spec.secondaryRange,
                    selectedStyle = BallStyle.BLUE,
                    onToggle = { number -> onNumberToggle(lineIndex, TicketNumberArea.SECONDARY, number) },
                )
            }
            problems.firstMessageFor("betLines[$lineIndex].secondaryNumbers")?.let { message ->
                FieldProblem(message)
            }
            if (line.originalText.isNotBlank()) {
                Text(
                    "OCR 原文：${line.originalText}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 完整显示本注已选号码，未选满的位置以短横线占位。
 *
 * 号码球尺寸按可用宽度缩放，保证手机竖屏无需横向滚动即可核对整注号码；复式号码较多、最小球径也排不下
 * 一行时自动换行。
 */
@Composable
private fun SelectedNumbersLine(
    spec: LotteryNumberSpec,
    primaryNumbers: List<Int>,
    secondaryNumbers: List<Int>,
) {
    // 快乐8 不设单式数量，没有号码时用一个占位提示尚未选择；复式或快乐8 号码多于占位数时不再补占位。
    val minimumPrimarySlots = if (spec.hasSecondaryArea) spec.primaryCount else 1
    val primarySlots =
        primaryNumbers.sorted() + List(maxOf(0, minimumPrimarySlots - primaryNumbers.size)) { null }
    val secondarySlots =
        secondaryNumbers.sorted() + List(maxOf(0, spec.secondaryCount - secondaryNumbers.size)) { null }
    val primaryDescription = "已选${spec.primaryName} ${primaryNumbers.sorted().joinToString("、") { it.twoDigitText() }}"
    val description =
        if (spec.hasSecondaryArea) {
            "$primaryDescription，${spec.secondaryName} ${secondaryNumbers.sorted().joinToString(
                "、",
            ) { it.twoDigitText() }}"
        } else {
            primaryDescription
        }
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
    ) {
        // 加号分隔符占用一个号码位，全部号码位与间距合计不超过当前宽度。
        val slotCount = primarySlots.size + if (spec.hasSecondaryArea) secondarySlots.size + 1 else 0
        val ballSize =
            ((maxWidth - SELECTED_LINE_SPACING * (slotCount - 1)) / slotCount)
                .coerceIn(SELECTED_LINE_MIN_BALL_SIZE, SELECTED_LINE_MAX_BALL_SIZE)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(SELECTED_LINE_SPACING),
            verticalArrangement = Arrangement.spacedBy(SELECTED_LINE_SPACING),
        ) {
            primarySlots.forEach { number -> SelectedNumberBall(number, ballSize, BallStyle.RED) }
            if (spec.hasSecondaryArea) {
                Box(modifier = Modifier.size(ballSize), contentAlignment = Alignment.Center) {
                    Text(
                        "+",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center,
                    )
                }
                secondarySlots.forEach { number -> SelectedNumberBall(number, ballSize, BallStyle.BLUE) }
            }
        }
    }
}

/** 已选号码汇总行中的一个只读号码球；`null` 表示尚未选择的位置。 */
@Composable
private fun SelectedNumberBall(
    number: Int?,
    size: Dp,
    style: BallStyle,
) {
    LotteryBall(
        text = number?.twoDigitText() ?: "--",
        style = if (number == null) BallStyle.PLACEHOLDER else style,
        size = size,
    )
}

/**
 * 按当前宽度把整个号码区排成多行网格，不再横向滚动。
 *
 * 号码球尺寸由最多可放下的列数决定，同一页面的两个号码区保持一致；行数确定后再均分各行号码，避免末行过短。
 */
@Composable
private fun NumberAreaEditor(
    title: String,
    selectedNumbers: List<Int>,
    expectedCount: Int,
    maxCount: Int,
    range: IntRange,
    selectedStyle: BallStyle,
    onToggle: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            when {
                // 快乐8 不设单式数量，号码个数即玩法。
                expectedCount == 0 -> {
                    val play = PrizeTierCodes.happy8PlayName(selectedNumbers.size)?.let { "，$it" }.orEmpty()
                    "$title（已选 ${selectedNumbers.size}/$maxCount$play）"
                }

                selectedNumbers.size > expectedCount -> {
                    "$title（已选 ${selectedNumbers.size} 个，复式）"
                }

                else -> {
                    "$title（已选 ${selectedNumbers.size}/$expectedCount）"
                }
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val lastVisibleNumber = maxOf(range.last, selectedNumbers.maxOrNull() ?: range.last)
        val numbers = (range.first..lastVisibleNumber).toList()
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val maximumColumns =
                ((maxWidth + NUMBER_GRID_SPACING) / (NUMBER_GRID_TARGET_BALL_SIZE + NUMBER_GRID_SPACING))
                    .toInt()
                    .coerceIn(1, numbers.size)
            val ballSize =
                ((maxWidth - NUMBER_GRID_SPACING * (maximumColumns - 1)) / maximumColumns)
                    .coerceAtMost(NUMBER_GRID_MAX_BALL_SIZE)
            val rowCount = (numbers.size + maximumColumns - 1) / maximumColumns
            val columns = (numbers.size + rowCount - 1) / rowCount
            Column(verticalArrangement = Arrangement.spacedBy(NUMBER_GRID_SPACING)) {
                numbers.chunked(columns).forEach { rowNumbers ->
                    Row(horizontalArrangement = Arrangement.spacedBy(NUMBER_GRID_SPACING)) {
                        rowNumbers.forEach { number ->
                            val selected = number in selectedNumbers
                            NumberBall(
                                number = number,
                                size = ballSize,
                                selected = selected,
                                enabled = selected || (number in range && selectedNumbers.size < maxCount),
                                selectedStyle = selectedStyle,
                                onToggle = { onToggle(number) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 显示一个尺寸由号码网格决定、可选择的号码球。 */
@Composable
private fun NumberBall(
    number: Int,
    size: Dp,
    selected: Boolean,
    enabled: Boolean,
    selectedStyle: BallStyle,
    onToggle: () -> Unit,
) {
    LotteryBall(
        text = number.twoDigitText(),
        style =
            when {
                selected -> selectedStyle
                enabled -> BallStyle.OUTLINE
                else -> BallStyle.DISABLED
            },
        modifier =
            Modifier
                .clip(CircleShape)
                .selectable(
                    selected = selected,
                    enabled = enabled,
                    role = Role.Checkbox,
                    onClick = onToggle,
                ),
        size = size,
        elevated = false,
    )
}

/** 号码按票面习惯补足两位显示。 */
private fun Int.twoDigitText(): String = toString().padStart(2, '0')

/** 号码网格中号码球的目标最小直径；常见手机竖屏约 340dp 的卡片内宽一行正好放下 7 个号码。 */
private val NUMBER_GRID_TARGET_BALL_SIZE = 42.dp

/** 号码网格中号码球的最大直径，宽屏下保持与原横向布局一致。 */
private val NUMBER_GRID_MAX_BALL_SIZE = 48.dp

/** 号码网格行列间距。 */
private val NUMBER_GRID_SPACING = 6.dp

/** 已选号码汇总行中号码球的最小直径。 */
private val SELECTED_LINE_MIN_BALL_SIZE = 24.dp

/** 已选号码汇总行中号码球的最大直径。 */
private val SELECTED_LINE_MAX_BALL_SIZE = 32.dp

/** 已选号码汇总行的号码间距。 */
private val SELECTED_LINE_SPACING = 4.dp

/** 显示投注倍数步进器。 */
@Composable
private fun MultiplierEditor(
    multiplier: Int?,
    onMultiplierChange: (Int) -> Unit,
) {
    SettingRow(title = "投注倍数", detail = if (multiplier == null) "待确认" else "可选 1 至 99 倍") {
        if (multiplier == null) {
            InlineTextButton(text = "确认 1 倍", onClick = { onMultiplierChange(MIN_MULTIPLIER) })
        } else {
            Stepper(
                valueText = "$multiplier 倍",
                onDecrement = { onMultiplierChange(multiplier - 1) },
                onIncrement = { onMultiplierChange(multiplier + 1) },
                decrementEnabled = multiplier > MIN_MULTIPLIER,
                incrementEnabled = multiplier < MAX_MULTIPLIER,
                decrementDescription = "减少倍数",
                incrementDescription = "增加倍数",
            )
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
        Stepper(
            valueText = "$periodCount 期",
            onDecrement = {
                onPeriodCountChange(
                    if (periodCount > IssueSequenceResolver.MAX_PERIOD_COUNT) {
                        IssueSequenceResolver.MAX_PERIOD_COUNT
                    } else {
                        periodCount - 1
                    },
                )
            },
            onIncrement = { onPeriodCountChange(periodCount + 1) },
            decrementEnabled = periodCount > IssueSequenceResolver.MIN_PERIOD_COUNT,
            incrementEnabled = periodCount < IssueSequenceResolver.MAX_PERIOD_COUNT,
            decrementDescription = "减少投注期数",
            incrementDescription = "增加投注期数",
        )
    }
}

/** 显示一行投注设置和右侧控件。 */
@Composable
private fun SettingRow(
    title: String,
    detail: String,
    control: @Composable () -> Unit,
) {
    SettingLine(
        title = title,
        detail = detail,
        modifier = Modifier.padding(vertical = 10.dp),
        control = control,
    )
}

/** 在对应输入下方显示一条领域问题。 */
@Composable
private fun FieldProblem(message: String) {
    Row(modifier = Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            LotteryIcons.Warning,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(6.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

/** 汇总当前仍阻断确认的领域问题。 */
@Composable
private fun ValidationSummary(problems: List<TicketValidationProblem>) {
    Notice(
        title = "请先修正以下信息",
        message = problems.distinctBy { it.field to it.message }.joinToString("\n") { "• ${it.message}" },
        tone = NoticeTone.ERROR,
    )
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
        ProgressStage(
            icon = LotteryIcons.Evidence,
            title =
                if (totalPeriodCount > 1) {
                    "正在查询第 $issue 期（${completedPeriodCount + 1}/$totalPeriodCount）"
                } else {
                    "正在查询第 $issue 期"
                },
            detail =
                if (usesRealDrawData) {
                    if (totalPeriodCount > 1) {
                        "正在按顺序核对每一期的两份官方数据"
                    } else {
                        "正在核对两份官方数据，只查询你确认的单个期号"
                    }
                } else {
                    "只有用户确认后才会发起单期查询"
                },
        )
    }
}

/**
 * 显示开发状态流完成结果，不声称完成真实测算。
 *
 * @param drawResult Fake 开奖仓库返回的演示结果。
 * @param usesRealRecognition 票面是否来自当前平台的真实本地 OCR。
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
        Notice("开发链路已跑通，本页不代表真实中奖结果。")
        Spacer(Modifier.height(24.dp))
        Text(
            if (usesRealRecognition) "本地识别校正链路可用" else "共享演示状态流可用",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            if (usesRealRecognition) {
                "票面已由本地 OCR、保守解析和人工校正生成；开奖数据仍为固定演示数据。"
            } else {
                "票面结构已使用保守解析器；图片采集、OCR 和开奖查询仍为固定演示实现。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        KeyValueCard(
            rows =
                listOf(
                    "演示彩种" to drawResult.lotteryType.displayName(),
                    "演示期号" to drawResult.issue.value,
                    "数据来源" to drawResult.evidence.sourceName,
                    "规则标识" to drawResult.ruleVersion,
                ),
        )
        Spacer(Modifier.height(SectionSpacing))
        PrimaryActionButton(text = "返回首页", onClick = onDone)
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
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconTile(
                icon = LotteryIcons.Warning,
                tint = MaterialTheme.colorScheme.error,
                container = MaterialTheme.colorScheme.errorContainer,
                size = 72.dp,
            )
            Spacer(Modifier.height(24.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            PrimaryActionButton(text = "返回首页", onClick = onDone, modifier = Modifier.widthIn(max = 320.dp))
        }
    }
}

/**
 * 显示关于、隐私和 V1 支持范围，并提供清除本机开奖缓存的入口。
 *
 * @param screen 当前关于页状态，包含清除进度和结果说明。
 * @param themePreference 当前主题偏好。
 * @param onThemePreferenceChange 切换跟随系统、浅色或深色主题。
 * @param onBack 返回上一页。
 * @param onClearHistory 用户确认后清除本机保存的官方历史开奖。
 */
@Composable
fun AboutScreen(
    screen: AppScreen.About,
    themePreference: ThemePreference,
    onThemePreferenceChange: (ThemePreference) -> Unit,
    onBack: () -> Unit,
    onClearHistory: () -> Unit,
) {
    var confirmsClear by remember { mutableStateOf(false) }
    AppShell(
        title = "关于与隐私",
        navigationIcon = LotteryIcons.Back,
        onNavigate = onBack,
    ) {
        LotteryCard(contentPadding = PaddingValues(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandMark(size = 56.dp)
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("给我中", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "纸质彩票识别与中奖测算工具",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(SectionSpacing))
        SectionHeader(title = "外观")
        Spacer(Modifier.height(SectionTitleSpacing))
        LotteryCard(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FieldLabel("主题")
            SegmentedControl(
                options = ThemePreference.entries,
                selected = themePreference,
                label = { it.displayName() },
                onSelect = onThemePreferenceChange,
            )
            Text(
                when (themePreference) {
                    ThemePreference.SYSTEM -> "随设备的深浅色设置自动切换。"
                    ThemePreference.LIGHT -> "始终使用浅色外观。"
                    ThemePreference.DARK -> "始终使用深色外观。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(SectionSpacing))
        SectionHeader(title = "数据如何处理")
        Spacer(Modifier.height(SectionTitleSpacing))
        LotteryCard(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PolicyRow(LotteryIcons.Privacy, "本地处理", "图片、OCR 文本和投注号码默认只在当前设备处理。")
            PolicyRow(LotteryIcons.Search, "最少查询", "查询开奖时只发送用户确认的彩种和期号。")
            PolicyRow(
                LotteryIcons.Lock,
                "本机记录",
                "确认后只保存结构化票据信息，不保存票图、OCR 全文或单张票的中奖结论。",
            )
            PolicyRow(
                LotteryIcons.Storage,
                "开奖缓存",
                "走势和 AI 分析使用的最近 500 期官方开奖只保存号码、日期、来源和核对时间；" +
                    "仅在打开页面或手动刷新时联网，距上次核对超过 30 分钟才增量补拉。" +
                    "查询开奖时，已开奖期次的官方号码和奖级金额也保存在本机，再次查询只补查未开奖或奖金待确认的期次，" +
                    "按开奖日程未到时间的期次不联网。清空票据记录不会清除这些开奖数据。",
            )
            HairlineDivider()
            OutlineActionButton(
                text = "清除本机开奖数据",
                onClick = { confirmsClear = true },
                enabled = !screen.isClearingHistory,
                loading = screen.isClearingHistory,
                contentColor = MaterialTheme.colorScheme.error,
            )
            screen.historyMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(SectionSpacing))
        SectionHeader(title = "V1 支持范围")
        Spacer(Modifier.height(SectionTitleSpacing))
        LotteryCard(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ScopeRow(supported = true, text = V1_SUPPORTED_TICKET_SCOPE_TEXT)
            HairlineDivider()
            ScopeRow(supported = false, text = V1_UNSUPPORTED_TICKET_SCOPE_TEXT)
        }
        Spacer(Modifier.height(20.dp))
        Notice("中奖测算不等于彩票验真，也不能确认撤单、作废或兑奖状态。", tone = NoticeTone.WARNING)
    }
    if (confirmsClear) {
        AlertDialog(
            onDismissRequest = { confirmsClear = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("清除本机开奖数据？") },
            text = {
                Text(
                    "删除走势和 AI 分析使用的历史开奖，以及查询开奖时保存的已开奖结果，不影响票据记录。" +
                        "之后进入走势或 AI 页会重新联网加载 500 期，查询开奖会重新核对每一期。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmsClear = false
                    onClearHistory()
                }) {
                    Text("清除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmsClear = false }) {
                    Text("取消")
                }
            },
        )
    }
}

/** 展示一条隐私原则：左侧图标底座，右侧标题与说明。 */
@Composable
private fun PolicyRow(
    icon: ImageVector,
    title: String,
    body: String,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        IconTile(
            icon = icon,
            tint = MaterialTheme.colorScheme.secondary,
            container = MaterialTheme.colorScheme.secondaryContainer,
            size = 36.dp,
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 展示已支持或未支持的票面范围。 */
@Composable
private fun ScopeRow(
    supported: Boolean,
    text: String,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        IconTile(
            icon = if (supported) LotteryIcons.Success else LotteryIcons.Clear,
            tint = if (supported) LotteryThemeTokens.colors.success else MaterialTheme.colorScheme.onSurfaceVariant,
            container =
                if (supported) {
                    LotteryThemeTokens.colors.successContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            size = 36.dp,
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (supported) "已支持" else "暂不支持", style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 返回主题偏好的简体中文名称。 */
private fun ThemePreference.displayName(): String =
    when (this) {
        ThemePreference.SYSTEM -> "跟随系统"
        ThemePreference.LIGHT -> "浅色"
        ThemePreference.DARK -> "深色"
    }

/** 返回彩种的中文名称。 */
private fun LotteryType?.displayName(): String =
    when (this) {
        LotteryType.SUPER_LOTTO -> "超级大乐透"
        LotteryType.DOUBLE_COLOR_BALL -> "双色球"
        LotteryType.HAPPY_8 -> "快乐8"
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
 * @property primaryCount 主号码单式数量；快乐8 为 0，号码个数即玩法。
 * @property primaryRange 主号码范围。
 * @property primaryMaxCount 一组最多可选的主号码个数；复式最多选满整个号码范围，快乐8 最多选十。
 * @property secondaryName 次号码区域名称。
 * @property secondaryCount 次号码单式数量；没有次号码区时为 0。
 * @property secondaryRange 次号码范围。
 */
private data class LotteryNumberSpec(
    val primaryName: String,
    val primaryCount: Int,
    val primaryRange: IntRange,
    val primaryMaxCount: Int,
    val secondaryName: String,
    val secondaryCount: Int,
    val secondaryRange: IntRange,
) {
    /** 是否有次号码区；快乐8 只有一个号码区。 */
    val hasSecondaryArea: Boolean
        get() = secondaryCount > 0

    /** 创建当前玩法的单式号码规格，复式在此基础上多选。 */
    companion object {
        /** 返回当前玩法的号码规格。 */
        fun forLottery(lotteryType: LotteryType): LotteryNumberSpec =
            when (lotteryType) {
                LotteryType.DOUBLE_COLOR_BALL -> {
                    LotteryNumberSpec("红球", 6, 1..33, 33, "蓝球", 1, 1..16)
                }

                LotteryType.SUPER_LOTTO -> {
                    LotteryNumberSpec("前区", 5, 1..35, 35, "后区", 2, 1..12)
                }

                LotteryType.HAPPY_8 -> {
                    LotteryNumberSpec("号码", 0, 1..80, BetCombinations.HAPPY_8_PICK_COUNTS.last, "", 0, IntRange.EMPTY)
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
    "单张电脑打印的超级大乐透、双色球或快乐8 彩票；支持 1 至 20 期受控连续投注、补打票、单式、多注单式、倍数及大乐透追加，" +
        "大乐透和双色球另支持复式。"

/** 关于页展示的 V1 未支持票面范围。 */
internal const val V1_UNSUPPORTED_TICKET_SCOPE_TEXT =
    "不支持胆拖、快乐8 复式、超过 20 期、跨年度未知期次、手写票、电子截图或未知票面版式。"

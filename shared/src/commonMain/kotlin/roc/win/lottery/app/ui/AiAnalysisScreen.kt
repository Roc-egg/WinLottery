package roc.win.lottery.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import roc.win.lottery.app.AiAnalysisOperation
import roc.win.lottery.app.AiAnalysisSessionResult
import roc.win.lottery.app.AiAnalysisSettings
import roc.win.lottery.app.AiAnalysisWorkspaceState
import roc.win.lottery.app.AiHistoryAvailability
import roc.win.lottery.app.MainDestination
import roc.win.lottery.data.AiProviderRequestPreview
import roc.win.lottery.domain.AiAnalysisCandidate
import roc.win.lottery.domain.AiAnalysisProtocol
import roc.win.lottery.domain.AiAnalysisTemplate
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.TrendSampleSize
import kotlin.time.Instant

/**
 * V1.4 用户逐次确认的 AI 历史数据分析工作区。
 *
 * @param workspace 不含密钥、完整请求体和原始响应的共享工作流状态。
 * @param availableMainDestinations 当前平台可进入的一级目的地。
 * @param onMainDestinationSelected 一级目的地切换操作。
 * @param onSettingsChange 修改非敏感分析配置。
 * @param onRetryHistory 主动重试当前彩种的官方历史开奖加载。
 * @param onPreparePreview 使用当前会话密钥构建精确发送预览。
 * @param onDismissPreview 返回修改并销毁本次待确认请求。
 * @param onConfirmPreview 消费当前指纹并发送至多一次请求。
 * @param onCancelRequest 主动取消正在执行的模型请求。
 */
@Composable
fun AiAnalysisScreen(
    workspace: AiAnalysisWorkspaceState,
    availableMainDestinations: List<MainDestination>,
    onMainDestinationSelected: (MainDestination) -> Unit,
    onSettingsChange: (AiAnalysisSettings) -> Unit,
    onRetryHistory: () -> Unit,
    onPreparePreview: (String) -> Boolean,
    onDismissPreview: () -> Unit,
    onConfirmPreview: (String) -> Unit,
    onCancelRequest: () -> Unit,
) {
    var apiKey by remember { mutableStateOf("") }
    var showsApiKey by remember { mutableStateOf(false) }
    val isRequesting = workspace.operation is AiAnalysisOperation.Requesting

    AppShell(
        title = "AI 分析",
        mainDestination = MainDestination.AI_ANALYSIS,
        availableMainDestinations = availableMainDestinations,
        onMainDestinationSelected = onMainDestinationSelected,
        scrollableContent = false,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 24.dp, bottom = 32.dp),
        ) {
            item(key = "configuration") {
                CenteredAiContent {
                    Text("公开历史数据分析", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "模型只观察当前会话的官方历史开奖；候选号码不代表概率优势。",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))
                    AiPrivacyBoundary()
                    Spacer(Modifier.height(28.dp))
                    Text("连接配置", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(12.dp))
                    AiConnectionFields(
                        settings = workspace.settings,
                        apiKey = apiKey,
                        showsApiKey = showsApiKey,
                        enabled = !isRequesting,
                        onSettingsChange = onSettingsChange,
                        onApiKeyChange = { value -> apiKey = value.take(MAX_API_KEY_INPUT_LENGTH) },
                        onToggleApiKeyVisibility = { showsApiKey = !showsApiKey },
                    )
                    Spacer(Modifier.height(30.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(24.dp))
                    Text("分析范围", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(16.dp))
                    AiAnalysisSelectors(
                        settings = workspace.settings,
                        enabled = !isRequesting,
                        onSettingsChange = onSettingsChange,
                    )
                    Spacer(Modifier.height(22.dp))
                    AiHistoryStatus(
                        history = workspace.history,
                        onRetry = onRetryHistory,
                    )
                    workspace.errorMessage?.let { message ->
                        Spacer(Modifier.height(14.dp))
                        AiErrorMessage(message)
                    }
                    Spacer(Modifier.height(18.dp))
                    Button(
                        onClick = { onPreparePreview(apiKey) },
                        enabled = workspace.history is AiHistoryAvailability.Ready && !isRequesting,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Icon(LotteryIcons.Send, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text("查看发送内容")
                    }
                }
            }

            workspace.lastResult?.let { result ->
                item(key = "result-header") {
                    CenteredAiContent {
                        Spacer(Modifier.height(34.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(Modifier.height(24.dp))
                        AiResultHeader(result)
                    }
                }
                itemsIndexed(
                    items = result.analysis.candidates,
                    key = { index, candidate ->
                        "ai-candidate-$index-${candidate.line.primaryNumbers.joinToString("-")}"
                    },
                ) { index, candidate ->
                    CenteredAiContent {
                        Spacer(Modifier.height(if (index == 0) 18.dp else 12.dp))
                        AiCandidateItem(
                            index = index,
                            lotteryType = result.analysis.lotteryType,
                            candidate = candidate,
                        )
                    }
                }
                item(key = "result-footer") {
                    CenteredAiContent {
                        Spacer(Modifier.height(18.dp))
                        Text(
                            "AI 结果仅供个人研究和娱乐，不构成购彩建议；开奖以发行机构公告为准。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    when (val operation = workspace.operation) {
        AiAnalysisOperation.Idle -> {}

        is AiAnalysisOperation.AwaitingConfirmation -> {
            AiRequestConfirmationDialog(
                preview = operation.preview,
                onDismiss = onDismissPreview,
                onConfirm = { onConfirmPreview(operation.preview.requestFingerprint) },
            )
        }

        is AiAnalysisOperation.Requesting -> {
            AiRequestingDialog(
                preview = operation.preview,
                onCancel = onCancelRequest,
            )
        }
    }
}

/** 把 AI 页面正文约束到适合手机和宽屏连续填写的宽度。 */
@Composable
private fun CenteredAiContent(content: @Composable ColumnScope.() -> Unit) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
            content = content,
        )
    }
}

/** 展示联网、费用和本机存储边界。 */
@Composable
private fun AiPrivacyBoundary() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                LotteryIcons.Privacy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "密钥、历史快照和分析结果不写入本机记录或导出包。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    "每次发送前单独确认；费用由你填写的服务商账户承担。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

/** 绘制服务商、HTTPS 地址、模型和会话密钥输入。 */
@Composable
private fun AiConnectionFields(
    settings: AiAnalysisSettings,
    apiKey: String,
    showsApiKey: Boolean,
    enabled: Boolean,
    onSettingsChange: (AiAnalysisSettings) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onToggleApiKeyVisibility: () -> Unit,
) {
    OutlinedTextField(
        value = settings.providerName,
        onValueChange = { value ->
            onSettingsChange(settings.copy(providerName = value.take(MAX_PROVIDER_NAME_INPUT_LENGTH)))
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        label = { Text("服务商名称") },
        placeholder = { Text("例如：个人 API 账户") },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = settings.endpointUrl,
        onValueChange = { value ->
            onSettingsChange(settings.copy(endpointUrl = value.take(MAX_ENDPOINT_INPUT_LENGTH)))
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        label = { Text("Responses HTTPS 地址") },
        placeholder = { Text("https://api.example.com/v1/responses") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = settings.model,
        onValueChange = { value ->
            onSettingsChange(settings.copy(model = value.take(MAX_MODEL_INPUT_LENGTH)))
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        label = { Text("模型") },
        placeholder = { Text("服务商支持严格结构输出的模型") },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = apiKey,
        onValueChange = onApiKeyChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        label = { Text("会话密钥") },
        leadingIcon = {
            Icon(LotteryIcons.SecretKey, contentDescription = null)
        },
        trailingIcon = {
            IconButton(
                onClick = onToggleApiKeyVisibility,
                enabled = enabled,
            ) {
                Icon(
                    imageVector = if (showsApiKey) LotteryIcons.HideSecret else LotteryIcons.ShowSecret,
                    contentDescription = if (showsApiKey) "隐藏密钥" else "显示密钥",
                )
            }
        },
        supportingText = { Text("只在当前进程会话中使用") },
        visualTransformation = if (showsApiKey) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
    )
}

/** 绘制彩种、样本、模板和候选数量控件。 */
@Composable
private fun AiAnalysisSelectors(
    settings: AiAnalysisSettings,
    enabled: Boolean,
    onSettingsChange: (AiAnalysisSettings) -> Unit,
) {
    Text("彩种", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(10.dp))
    val lotteryTypes = listOf(LotteryType.SUPER_LOTTO, LotteryType.DOUBLE_COLOR_BALL)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        lotteryTypes.forEachIndexed { index, lotteryType ->
            SegmentedButton(
                selected = lotteryType == settings.lotteryType,
                onClick = { onSettingsChange(settings.copy(lotteryType = lotteryType)) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index, lotteryTypes.size),
                label = { Text(lotteryType.aiDisplayName()) },
            )
        }
    }
    Spacer(Modifier.height(22.dp))
    Text("历史样本", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items = TrendSampleSize.entries, key = { item -> item.count }) { sampleSize ->
            FilterChip(
                selected = sampleSize == settings.sampleSize,
                onClick = { onSettingsChange(settings.copy(sampleSize = sampleSize)) },
                enabled = enabled,
                label = { Text("${sampleSize.count} 期") },
            )
        }
    }
    Spacer(Modifier.height(22.dp))
    Text("分析模板", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items = AiAnalysisTemplate.entries, key = { item -> item.id }) { template ->
            FilterChip(
                selected = template == settings.template,
                onClick = { onSettingsChange(settings.copy(template = template)) },
                enabled = enabled,
                label = { Text(template.displayName) },
            )
        }
    }
    Spacer(Modifier.height(22.dp))
    AiCandidateCountControl(
        value = settings.candidateCount,
        enabled = enabled,
        onValueChange = { value -> onSettingsChange(settings.copy(candidateCount = value)) },
    )
}

/** 绘制固定 `1..5` 的候选注数步进器。 */
@Composable
private fun AiCandidateCountControl(
    value: Int,
    enabled: Boolean,
    onValueChange: (Int) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("候选注数", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton(
                onClick = { onValueChange(value - 1) },
                enabled = enabled && value > AiAnalysisProtocol.MIN_CANDIDATE_COUNT,
            ) {
                Icon(LotteryIcons.Minus, contentDescription = "减少候选注数")
            }
            Box(modifier = Modifier.widthIn(min = 52.dp), contentAlignment = Alignment.Center) {
                Text("$value 注", style = MaterialTheme.typography.labelLarge)
            }
            IconButton(
                onClick = { onValueChange(value + 1) },
                enabled = enabled && value < AiAnalysisProtocol.MAX_CANDIDATE_COUNT,
            ) {
                Icon(LotteryIcons.Plus, contentDescription = "增加候选注数")
            }
        }
    }
}

/** 展示当前彩种的官方历史开奖加载状态。 */
@Composable
private fun AiHistoryStatus(
    history: AiHistoryAvailability,
    onRetry: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (history) {
                AiHistoryAvailability.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("正在加载完整官方历史开奖", style = MaterialTheme.typography.bodyMedium)
                }

                is AiHistoryAvailability.Ready -> {
                    Icon(
                        LotteryIcons.Done,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "已就绪 ${history.availableDrawCount} 期",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "${history.sourceName} · ${formatAiTime(history.fetchedAtEpochMillis)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                is AiHistoryAvailability.Failed -> {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("历史开奖暂不可用", style = MaterialTheme.typography.titleMedium)
                        Text(
                            history.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onRetry) {
                        Text("重试")
                    }
                }
            }
        }
    }
}

/** 展示本地阻断或单次请求失败。 */
@Composable
private fun AiErrorMessage(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

/** 展示最近一次合法结果的可复核元数据与摘要。 */
@Composable
private fun AiResultHeader(result: AiAnalysisSessionResult) {
    Text("分析结果", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(6.dp))
    Text(
        "${result.preview.providerName} · ${result.preview.model}",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "${result.sourceName} · ${result.preview.firstIssue} 至 ${result.preview.lastIssue} · ${result.preview.sampleCount} 期",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "生成于 ${formatAiTime(
            result.generatedAtEpochMillis,
        )} · 快照 ${result.preview.snapshotId.take(SHORT_SNAPSHOT_LENGTH)}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    Text(result.analysis.summary, style = MaterialTheme.typography.bodyLarge)
}

/** 绘制一注通过本地彩票规则复核的 AI 候选。 */
@Composable
private fun AiCandidateItem(
    index: Int,
    lotteryType: LotteryType,
    candidate: AiAnalysisCandidate,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("第 ${index + 1} 注", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Text(
                lotteryType.primaryAreaName(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            AiNumberRow(candidate.line.primaryNumbers, isPrimary = true)
            Spacer(Modifier.height(10.dp))
            Text(
                lotteryType.secondaryAreaName(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            AiNumberRow(candidate.line.secondaryNumbers, isPrimary = false)
            Spacer(Modifier.height(12.dp))
            Text(
                candidate.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 使用稳定尺寸和彩票区域色绘制一组升序号码。 */
@Composable
private fun AiNumberRow(
    numbers: List<Int>,
    isPrimary: Boolean,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        numbers.forEach { number ->
            Surface(
                modifier = Modifier.size(32.dp),
                shape = CircleShape,
                color =
                    if (isPrimary) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                contentColor =
                    if (isPrimary) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(number.toString().padStart(2, '0'), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** 展示与实际 POST 完全绑定的发送确认内容。 */
@Composable
private fun AiRequestConfirmationDialog(
    preview: AiProviderRequestPreview,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认发送公开历史数据") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PreviewField("服务商", preview.providerName)
                PreviewField("HTTPS 接收主机", wrapLongToken(preview.endpointHost))
                PreviewField("模型", wrapLongToken(preview.model))
                PreviewField(
                    "历史样本",
                    "${preview.firstIssue} 至 ${preview.lastIssue}，共 ${preview.sampleCount} 期",
                )
                PreviewField("历史快照", wrapLongToken(preview.snapshotId, groupSize = 8))
                PreviewField("分析模板", preview.templateName)
                PreviewField("候选数量", "${preview.candidateCount} 注")
                PreviewField("发送字段", preview.sentFields.joinToString(separator = "、"))
                PreviewField("完整请求体", "${preview.requestBodyByteCount} 字节")
                PreviewField("输出上限", "${preview.maxOutputTokens} token")
                PreviewField("确认标识", wrapLongToken(preview.requestFingerprint, groupSize = 8))
                Text(
                    "本次确认只发送一次，失败不会自动重试或切换服务商；费用由上述服务商账户承担。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, shape = MaterialTheme.shapes.small) {
                Icon(LotteryIcons.Send, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("确认并发送")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("返回修改")
            }
        },
    )
}

/** 展示单次请求进行中状态，并保留明确取消操作。 */
@Composable
private fun AiRequestingDialog(
    preview: AiProviderRequestPreview,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("正在等待 AI 服务") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    "${preview.providerName} · ${preview.model} · ${preview.sampleCount} 期",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("请求不会自动重试", style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onCancel, shape = MaterialTheme.shapes.small) {
                Icon(LotteryIcons.Stop, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("取消请求")
            }
        },
    )
}

/** 展示发送预览中的一个标签和值。 */
@Composable
private fun PreviewField(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 把过长且没有自然断点的标识分组，避免窄屏溢出。 */
private fun wrapLongToken(
    value: String,
    groupSize: Int = 20,
): String = value.chunked(groupSize).joinToString(separator = " ")

/** 返回 AI 页面使用的彩种短名称。 */
private fun LotteryType.aiDisplayName(): String =
    when (this) {
        LotteryType.SUPER_LOTTO -> "大乐透"
        LotteryType.DOUBLE_COLOR_BALL -> "双色球"
    }

/** 返回当前彩种主号码区域名称。 */
private fun LotteryType.primaryAreaName(): String =
    when (this) {
        LotteryType.SUPER_LOTTO -> "前区"
        LotteryType.DOUBLE_COLOR_BALL -> "红球"
    }

/** 返回当前彩种次号码区域名称。 */
private fun LotteryType.secondaryAreaName(): String =
    when (this) {
        LotteryType.SUPER_LOTTO -> "后区"
        LotteryType.DOUBLE_COLOR_BALL -> "蓝球"
    }

/** 按中国时区展示历史加载和结果生成时间。 */
private fun formatAiTime(epochMillis: Long): String {
    val dateTime = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(CHINA_TIME_ZONE)
    return buildString {
        append(dateTime.year.toString().padStart(4, '0'))
        append('-')
        append((dateTime.month.ordinal + 1).toString().padStart(2, '0'))
        append('-')
        append(dateTime.day.toString().padStart(2, '0'))
        append(' ')
        append(dateTime.hour.toString().padStart(2, '0'))
        append(':')
        append(dateTime.minute.toString().padStart(2, '0'))
    }
}

/** AI 页面使用的中国标准时间时区。 */
private val CHINA_TIME_ZONE = TimeZone.of("Asia/Shanghai")

/** 服务商名称输入最大字符数。 */
private const val MAX_PROVIDER_NAME_INPUT_LENGTH = 60

/** HTTPS 地址输入最大字符数。 */
private const val MAX_ENDPOINT_INPUT_LENGTH = 2_048

/** 模型标识输入最大字符数。 */
private const val MAX_MODEL_INPUT_LENGTH = 128

/** 会话密钥输入最大字符数。 */
private const val MAX_API_KEY_INPUT_LENGTH = 4_096

/** 结果页展示的快照短标识长度。 */
private const val SHORT_SNAPSHOT_LENGTH = 12

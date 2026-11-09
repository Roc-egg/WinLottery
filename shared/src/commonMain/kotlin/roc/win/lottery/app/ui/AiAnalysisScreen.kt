package roc.win.lottery.app.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import roc.win.lottery.app.AiModelCatalogState
import roc.win.lottery.app.HistoricalDrawRefreshStatus
import roc.win.lottery.app.MainDestination
import roc.win.lottery.app.theme.LotteryThemeTokens
import roc.win.lottery.data.AiApiFormat
import roc.win.lottery.data.AiProviderConfigurationValidator
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
 * @param apiKey 当前填写的服务商密钥。
 * @param savesApiKeyLocally 当前平台能否把密钥加密保存在本机。
 * @param onSettingsChange 修改非敏感分析配置。
 * @param onApiKeyChange 修改服务商密钥。
 * @param onClearSavedConnection 删除本机保存的连接配置并清空已填写的连接字段。
 * @param onFetchModels 从服务商读取模型列表。
 * @param onSelectModel 选用模型列表中的一项。
 * @param onDismissModels 关闭模型列表弹窗。
 * @param onRetryHistory 主动重试当前彩种的官方历史开奖加载。
 * @param onRefreshHistory 主动与官网增量核对本机保存的历史开奖。
 * @param onPreparePreview 使用当前密钥构建精确发送预览。
 * @param onDismissPreview 返回修改并销毁本次待确认请求。
 * @param onConfirmPreview 消费当前指纹并发送至多一次请求。
 * @param onCancelRequest 主动取消正在执行的模型请求。
 */
@Composable
fun AiAnalysisScreen(
    workspace: AiAnalysisWorkspaceState,
    availableMainDestinations: List<MainDestination>,
    onMainDestinationSelected: (MainDestination) -> Unit,
    apiKey: String,
    savesApiKeyLocally: Boolean,
    onSettingsChange: (AiAnalysisSettings) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onClearSavedConnection: () -> Unit,
    onFetchModels: () -> Unit,
    onSelectModel: (String) -> Unit,
    onDismissModels: () -> Unit,
    onRetryHistory: () -> Unit,
    onRefreshHistory: () -> Unit,
    onPreparePreview: (String) -> Boolean,
    onDismissPreview: () -> Unit,
    onConfirmPreview: (String) -> Unit,
    onCancelRequest: () -> Unit,
) {
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
            contentPadding = PaddingValues(top = 4.dp, bottom = 40.dp + LocalMainBarInset.current),
        ) {
            item(key = "configuration") {
                CenteredAiContent {
                    PageLead("公开历史数据分析：模型只观察本机保存的官方历史开奖；候选号码不代表概率优势。")
                    Spacer(Modifier.height(16.dp))
                    AiPrivacyBoundary(savesApiKeyLocally)
                    Spacer(Modifier.height(SectionSpacing))
                    SectionHeader(title = "连接配置")
                    Spacer(Modifier.height(SectionTitleSpacing))
                    LotteryCard(verticalArrangement = Arrangement.Top) {
                        AiConnectionFields(
                            settings = workspace.settings,
                            apiKey = apiKey,
                            savesApiKeyLocally = savesApiKeyLocally,
                            showsApiKey = showsApiKey,
                            enabled = !isRequesting,
                            onSettingsChange = onSettingsChange,
                            onApiKeyChange = { value -> onApiKeyChange(value.take(MAX_API_KEY_INPUT_LENGTH)) },
                            onToggleApiKeyVisibility = { showsApiKey = !showsApiKey },
                            onFetchModels = onFetchModels,
                        )
                        val hasConnection =
                            apiKey.isNotEmpty() ||
                                workspace.settings.providerName.isNotEmpty() ||
                                workspace.settings.baseUrl.isNotEmpty() ||
                                workspace.settings.model.isNotEmpty()
                        HairlineDivider(Modifier.padding(vertical = 8.dp))
                        InlineTextButton(
                            text = "清除本机保存的连接配置",
                            onClick = onClearSavedConnection,
                            enabled = hasConnection && !isRequesting,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(SectionSpacing))
                    SectionHeader(title = "分析范围")
                    Spacer(Modifier.height(SectionTitleSpacing))
                    LotteryCard(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        AiAnalysisSelectors(
                            settings = workspace.settings,
                            enabled = !isRequesting,
                            onSettingsChange = onSettingsChange,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    AiHistoryStatus(
                        history = workspace.history,
                        refresh = workspace.historyRefresh,
                        onRetry = onRetryHistory,
                        onRefresh = onRefreshHistory,
                    )
                    workspace.errorMessage?.let { message ->
                        Spacer(Modifier.height(12.dp))
                        AiErrorMessage(message)
                    }
                    Spacer(Modifier.height(20.dp))
                    PrimaryActionButton(
                        text = "查看发送内容",
                        onClick = { onPreparePreview(apiKey) },
                        enabled = workspace.history is AiHistoryAvailability.Ready && !isRequesting,
                        icon = LotteryIcons.Send,
                    )
                }
            }

            workspace.lastResult?.let { result ->
                item(key = "result-header") {
                    CenteredAiContent {
                        Spacer(Modifier.height(SectionSpacing))
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
                        Spacer(Modifier.height(12.dp))
                        AiCandidateItem(
                            index = index,
                            lotteryType = result.analysis.lotteryType,
                            candidate = candidate,
                        )
                    }
                }
                item(key = "result-footer") {
                    CenteredAiContent {
                        Spacer(Modifier.height(16.dp))
                        FootnoteText("AI 结果仅供个人研究和娱乐，不构成购彩建议；开奖以发行机构公告为准。")
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
    AiModelCatalogDialog(
        state = workspace.modelCatalog,
        currentModel = workspace.settings.model,
        onSelect = onSelectModel,
        onRetry = onFetchModels,
        onDismiss = onDismissModels,
    )
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
private fun AiPrivacyBoundary(savesApiKeyLocally: Boolean) {
    Notice(
        message =
            if (savesApiKeyLocally) {
                "连接配置和密钥只加密保存在本机，不写入票据记录、导出包或云端备份；发送快照和分析结果不保存。"
            } else {
                "服务商、地址和模型只保存在本机；密钥只在本次运行有效。发送快照和分析结果不保存。"
            },
        icon = LotteryIcons.Privacy,
    ) {
        Text(
            "每次发送前单独确认；费用由你填写的服务商账户承担。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 绘制服务商、接口格式、Base URL、模型和密钥输入；模型可从服务商读取列表后选择。 */
@Composable
private fun AiConnectionFields(
    settings: AiAnalysisSettings,
    apiKey: String,
    savesApiKeyLocally: Boolean,
    showsApiKey: Boolean,
    enabled: Boolean,
    onSettingsChange: (AiAnalysisSettings) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onToggleApiKeyVisibility: () -> Unit,
    onFetchModels: () -> Unit,
) {
    val usesCleartext = settings.baseUrl.trim().startsWith(CLEARTEXT_SCHEME_PREFIX, ignoreCase = true)
    val requestUrl =
        remember(settings.apiFormat, settings.baseUrl) {
            AiProviderConfigurationValidator().requestUrlOrNull(settings.apiFormat, settings.baseUrl)
        }
    OutlinedTextField(
        value = settings.providerName,
        onValueChange = { value ->
            onSettingsChange(settings.copy(providerName = value.take(MAX_PROVIDER_NAME_INPUT_LENGTH)))
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        colors = lotteryTextFieldColors(),
        label = { Text("服务商名称") },
        placeholder = { Text("例如：个人 API 账户") },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
    )
    Spacer(Modifier.height(12.dp))
    AiApiFormatField(
        selected = settings.apiFormat,
        enabled = enabled,
        onSelect = { format -> onSettingsChange(settings.copy(apiFormat = format)) },
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = settings.baseUrl,
        onValueChange = { value ->
            onSettingsChange(settings.copy(baseUrl = value.take(MAX_ENDPOINT_INPUT_LENGTH)))
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        colors = lotteryTextFieldColors(),
        label = { Text("Base URL") },
        placeholder = { Text(settings.apiFormat.baseUrlExample) },
        supportingText =
            if (usesCleartext || requestUrl != null) {
                {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        requestUrl?.let { url -> Text("实际请求：$url") }
                        if (usesCleartext) {
                            Text(
                                "HTTP 不加密：密钥和发送内容可能被同一网络中的他人截获，建议只用于本机或可信局域网",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            } else {
                null
            },
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
        shape = MaterialTheme.shapes.medium,
        colors = lotteryTextFieldColors(),
        label = { Text("模型") },
        placeholder = { Text("填写模型标识或从列表选择") },
        trailingIcon = {
            IconButton(
                onClick = onFetchModels,
                enabled = enabled && settings.baseUrl.isNotBlank(),
            ) {
                Icon(LotteryIcons.ModelList, contentDescription = "获取模型列表")
            }
        },
        supportingText = { Text("点右侧按钮可从服务商读取模型列表") },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
    )
    Spacer(Modifier.height(12.dp))
    AiOutputTokenLimitControl(
        selected = settings.maxOutputTokens,
        enabled = enabled,
        onSelect = { limit -> onSettingsChange(settings.copy(maxOutputTokens = limit)) },
    )
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = apiKey,
        onValueChange = onApiKeyChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        colors = lotteryTextFieldColors(),
        label = { Text("API 密钥") },
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
        supportingText = {
            Text(if (savesApiKeyLocally) "只加密保存在本机" else "桌面版密钥只在本次运行有效")
        },
        visualTransformation = if (showsApiKey) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
    )
}

/** 输出 token 上限选择：推理模型需要更大的上限，部分模型不接受较大的值。 */
@Composable
private fun AiOutputTokenLimitControl(
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    FieldLabel("输出上限")
    Spacer(Modifier.height(8.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items = AiAnalysisProtocol.OUTPUT_TOKEN_LIMITS, key = { limit -> limit }) { limit ->
            ChoicePill(
                selected = limit == selected,
                onClick = { onSelect(limit) },
                enabled = enabled,
                label = "${limit / TOKENS_PER_K}K token",
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        "推理模型的思考也计入上限，结果未完成时调高；按实际用量计费。模型不支持较大的上限时会报错，请调低。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 接口格式下拉框：决定请求路径、鉴权请求头和请求体。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiApiFormatField(
    selected: AiApiFormat,
    enabled: Boolean,
    onSelect: (AiApiFormat) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { open -> expanded = open && enabled },
    ) {
        OutlinedTextField(
            value = selected.displayName,
            onValueChange = {},
            modifier =
                Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled)
                    .fillMaxWidth(),
            enabled = enabled,
            readOnly = true,
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            colors = lotteryTextFieldColors(),
            label = { Text("接口格式") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
        )
        ExposedDropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
            shape = MaterialTheme.shapes.medium,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            AiApiFormat.entries.forEach { format ->
                DropdownMenuItem(
                    text = { Text(format.displayName) },
                    leadingIcon = {
                        if (format == selected) {
                            Icon(LotteryIcons.Done, contentDescription = "当前格式")
                        } else {
                            Spacer(Modifier.size(24.dp))
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(format)
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

/** 绘制彩种、样本、模板和候选数量控件。 */
@Composable
private fun AiAnalysisSelectors(
    settings: AiAnalysisSettings,
    enabled: Boolean,
    onSettingsChange: (AiAnalysisSettings) -> Unit,
) {
    FieldLabel("彩种")
    SegmentedControl(
        options = listOf(LotteryType.SUPER_LOTTO, LotteryType.DOUBLE_COLOR_BALL),
        selected = settings.lotteryType,
        label = { it.aiDisplayName() },
        onSelect = { lotteryType -> onSettingsChange(settings.copy(lotteryType = lotteryType)) },
        enabled = enabled,
    )
    Spacer(Modifier.height(6.dp))
    FieldLabel("历史样本")
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items = TrendSampleSize.entries, key = { item -> item.count }) { sampleSize ->
            ChoicePill(
                selected = sampleSize == settings.sampleSize,
                onClick = { onSettingsChange(settings.copy(sampleSize = sampleSize)) },
                enabled = enabled,
                label = "${sampleSize.count} 期",
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    FieldLabel("分析模板")
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items = AiAnalysisTemplate.entries, key = { item -> item.id }) { template ->
            ChoicePill(
                selected = template == settings.template,
                onClick = { onSettingsChange(settings.copy(template = template)) },
                enabled = enabled,
                label = template.displayName,
            )
        }
    }
    HairlineDivider(Modifier.padding(top = 6.dp))
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
    SettingLine(title = "候选注数") {
        Stepper(
            valueText = "$value 注",
            onDecrement = { onValueChange(value - 1) },
            onIncrement = { onValueChange(value + 1) },
            decrementEnabled = enabled && value > AiAnalysisProtocol.MIN_CANDIDATE_COUNT,
            incrementEnabled = enabled && value < AiAnalysisProtocol.MAX_CANDIDATE_COUNT,
            decrementDescription = "减少候选注数",
            incrementDescription = "增加候选注数",
        )
    }
}

/** 展示当前彩种的官方历史开奖加载状态，以及与官网增量核对的进度。 */
@Composable
private fun AiHistoryStatus(
    history: AiHistoryAvailability,
    refresh: HistoricalDrawRefreshStatus,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
) {
    LotteryCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (history) {
                AiHistoryAvailability.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("正在加载完整官方历史开奖", style = MaterialTheme.typography.bodyMedium)
                }

                is AiHistoryAvailability.Ready -> {
                    IconTile(
                        icon = LotteryIcons.Storage,
                        tint = LotteryThemeTokens.colors.success,
                        container = LotteryThemeTokens.colors.successContainer,
                        size = 40.dp,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "已就绪 ${history.availableDrawCount} 期",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "${history.sourceName} · 核对于 ${formatAiTime(history.fetchedAtEpochMillis)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        when (refresh) {
                            HistoricalDrawRefreshStatus.Idle -> {
                                Unit
                            }

                            HistoricalDrawRefreshStatus.Refreshing -> {
                                Text(
                                    "正在与官网核对最新开奖",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            is HistoricalDrawRefreshStatus.Notice -> {
                                Text(
                                    refresh.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            is HistoricalDrawRefreshStatus.Failed -> {
                                Text(
                                    "更新失败，继续使用本机数据：${refresh.message}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    if (refresh is HistoricalDrawRefreshStatus.Refreshing) {
                        CircularProgressIndicator(
                            modifier =
                                Modifier
                                    .size(24.dp)
                                    .semantics { contentDescription = "正在核对最新开奖" },
                            strokeWidth = 2.dp,
                        )
                    } else {
                        InlineTextButton(text = "刷新", onClick = onRefresh)
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
                    InlineTextButton(text = "重试", onClick = onRetry)
                }
            }
        }
    }
}

/** 展示本地阻断或单次请求失败。 */
@Composable
private fun AiErrorMessage(message: String) {
    Notice(message = message, tone = NoticeTone.ERROR)
}

/** 展示最近一次合法结果的可复核元数据与摘要。 */
@Composable
private fun AiResultHeader(result: AiAnalysisSessionResult) {
    SectionHeader(
        title = "分析结果",
        subtitle = "${result.preview.providerName} · ${result.preview.model}",
    )
    Spacer(Modifier.height(SectionTitleSpacing))
    LotteryCard(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon = LotteryIcons.Sparkles, size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${result.sourceName} · ${result.preview.firstIssue} 至 ${result.preview.lastIssue} · " +
                        "${result.preview.sampleCount} 期",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "生成于 ${formatAiTime(
                        result.generatedAtEpochMillis,
                    )} · 快照 ${result.preview.snapshotId.take(SHORT_SNAPSHOT_LENGTH)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HairlineDivider()
        Text(result.analysis.summary, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 绘制一注通过本地彩票规则复核的 AI 候选。 */
@Composable
private fun AiCandidateItem(
    index: Int,
    lotteryType: LotteryType,
    candidate: AiAnalysisCandidate,
) {
    val description =
        "第 ${index + 1} 注，${lotteryType.primaryAreaName()} " +
            candidate.line.primaryNumbers.joinToString(" ") { it.toString().padStart(2, '0') } +
            "，${lotteryType.secondaryAreaName()} " +
            candidate.line.secondaryNumbers.joinToString(" ") { it.toString().padStart(2, '0') }
    LotteryCard(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("第 ${index + 1} 注", style = MaterialTheme.typography.titleSmall)
        NumberBallLine(
            primaryNumbers = candidate.line.primaryNumbers,
            secondaryNumbers = candidate.line.secondaryNumbers,
            modifier = Modifier.clearAndSetSemantics { contentDescription = description },
        )
        Text(
            candidate.reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("确认发送公开历史数据") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PreviewField("服务商", preview.providerName)
                PreviewField("接口格式", preview.apiFormat.displayName)
                PreviewField("接收主机", wrapLongToken(preview.endpointHost))
                if (!preview.usesHttps) {
                    PreviewField("连接方式", "HTTP（不加密）")
                    Text(
                        "该服务使用不加密的 HTTP，密钥和发送内容可能被同一网络中的他人截获。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
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
            Button(onClick = onConfirm, shape = CircleShape) {
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

/** 模型列表弹窗：读取中、失败可重试；读取成功后可搜索并点选模型，当前使用的模型带勾。 */
@Composable
private fun AiModelCatalogDialog(
    state: AiModelCatalogState,
    currentModel: String,
    onSelect: (String) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        AiModelCatalogState.Hidden -> {
            Unit
        }

        AiModelCatalogState.Loading -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                containerColor = MaterialTheme.colorScheme.surface,
                title = { Text("获取模型列表") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clip(CircleShape))
                        Text("正在从服务商读取模型列表…", style = MaterialTheme.typography.bodyMedium)
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text("取消") }
                },
            )
        }

        is AiModelCatalogState.Failed -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                containerColor = MaterialTheme.colorScheme.surface,
                title = { Text("无法获取模型列表") },
                text = { Text(state.message, style = MaterialTheme.typography.bodyMedium) },
                confirmButton = {
                    TextButton(onClick = onRetry) { Text("重试") }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                },
            )
        }

        is AiModelCatalogState.Loaded -> {
            var query by remember(state) { mutableStateOf("") }
            val matches =
                remember(state, query) {
                    state.models.filter { model -> model.contains(query.trim(), ignoreCase = true) }
                }
            AlertDialog(
                onDismissRequest = onDismiss,
                containerColor = MaterialTheme.colorScheme.surface,
                title = { Text("选择模型（共 ${state.models.size} 个）") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.models.size > MODEL_SEARCH_THRESHOLD) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { value -> query = value.take(MAX_MODEL_INPUT_LENGTH) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                placeholder = { Text("搜索模型") },
                                shape = CircleShape,
                                colors = lotteryTextFieldColors(),
                                leadingIcon = { Icon(LotteryIcons.Search, contentDescription = null) },
                            )
                        }
                        if (matches.isEmpty()) {
                            Text(
                                "没有匹配的模型",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = MODEL_LIST_MAX_HEIGHT)) {
                            items(matches, key = { model -> model }) { model ->
                                val isCurrent = model == currentModel
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .clip(MaterialTheme.shapes.small)
                                            .clickable(role = Role.Button) { onSelect(model) }
                                            .padding(horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        model,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color =
                                            if (isCurrent) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                    )
                                    if (isCurrent) {
                                        Icon(
                                            LotteryIcons.Done,
                                            contentDescription = "当前使用",
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text("取消") }
                },
            )
        }
    }
}

/** 展示单次请求进行中状态，并保留明确取消操作。 */
@Composable
private fun AiRequestingDialog(
    preview: AiProviderRequestPreview,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("正在等待 AI 服务") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clip(CircleShape))
                Text(
                    "${preview.providerName} · ${preview.model} · ${preview.sampleCount} 期",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("请求不会自动重试", style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onCancel, shape = CircleShape) {
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
        LotteryType.HAPPY_8 -> "快乐8"
    }

/** 返回当前彩种主号码区域名称。 */
private fun LotteryType.primaryAreaName(): String =
    when (this) {
        LotteryType.SUPER_LOTTO -> "前区"
        LotteryType.DOUBLE_COLOR_BALL -> "红球"
        LotteryType.HAPPY_8 -> "号码"
    }

/** 返回当前彩种次号码区域名称。 */
private fun LotteryType.secondaryAreaName(): String =
    when (this) {
        LotteryType.SUPER_LOTTO -> "后区"
        LotteryType.DOUBLE_COLOR_BALL -> "蓝球"
        LotteryType.HAPPY_8 -> "无"
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

/** 输出上限按 K 展示时的换算基数。 */
private const val TOKENS_PER_K = 1024

/** 明文 HTTP 地址的协议前缀。 */
private const val CLEARTEXT_SCHEME_PREFIX = "http://"

/** 模型数量超过该值时在弹窗中显示搜索框。 */
private const val MODEL_SEARCH_THRESHOLD = 8

/** 模型列表在弹窗中的最大高度。 */
private val MODEL_LIST_MAX_HEIGHT = 360.dp

/** 接口地址输入最大字符数。 */
private const val MAX_ENDPOINT_INPUT_LENGTH = 2_048

/** 模型标识输入最大字符数。 */
private const val MAX_MODEL_INPUT_LENGTH = 128

/** 密钥输入最大字符数。 */
private const val MAX_API_KEY_INPUT_LENGTH = 4_096

/** 结果页展示的快照短标识长度。 */
private const val SHORT_SNAPSHOT_LENGTH = 12

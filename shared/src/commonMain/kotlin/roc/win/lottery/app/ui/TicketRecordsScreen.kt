package roc.win.lottery.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import roc.win.lottery.app.AppScreen
import roc.win.lottery.app.MainDestination
import roc.win.lottery.app.TicketRecordFilter
import roc.win.lottery.app.filterTicketRecords
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.persistence.MAX_TICKET_RECORD_NAME_LENGTH
import roc.win.lottery.persistence.StoredTicketRecord
import roc.win.lottery.persistence.TicketAcquisitionSource
import roc.win.lottery.persistence.TicketRecordImportPreview
import kotlin.time.Instant

/**
 * 显示可筛选、搜索、重命名和再次查询的本机结构化票据列表。
 *
 * @param screen 当前列表筛选与操作状态。
 * @param records 仓库提供的全部合法记录。
 * @param isLoading 是否仍在等待数据库首次返回。
 * @param loadError 数据库读取失败时的安全错误说明。
 * @param supportsFileExchange 当前平台是否接入逻辑包系统文件接口。
 * @param availableMainDestinations 当前平台可进入的一级目的地。
 * @param onMainDestinationSelected 一级目的地切换操作。
 * @param onFilterChange 修改彩种筛选。
 * @param onSearchChange 修改搜索文本。
 * @param onRename 保存新名称。
 * @param onDelete 删除一条记录。
 * @param onQuery 直接使用已保存票据查询开奖。
 * @param onClearAll 清空全部本机记录。
 * @param onExport 导出全部本机记录。
 * @param onImport 选择并预检一个逻辑导入包。
 * @param onConfirmImport 提交已预检的导入包。
 * @param onDismissImport 取消导入并丢弃待确认文件内容。
 */
@Composable
fun TicketRecordsScreen(
    screen: AppScreen.Records,
    records: List<StoredTicketRecord>,
    isLoading: Boolean,
    loadError: String?,
    supportsFileExchange: Boolean,
    availableMainDestinations: List<MainDestination>,
    onMainDestinationSelected: (MainDestination) -> Unit,
    onFilterChange: (TicketRecordFilter) -> Unit,
    onSearchChange: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onQuery: (String) -> Unit,
    onClearAll: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onConfirmImport: (Boolean) -> Unit,
    onDismissImport: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val visibleRecords =
        remember(records, screen.filter, screen.searchQuery) {
            filterTicketRecords(records, screen.filter, screen.searchQuery)
        }
    var renamingRecord by remember { mutableStateOf<StoredTicketRecord?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var deletingRecord by remember { mutableStateOf<StoredTicketRecord?>(null) }
    var isManagementMenuExpanded by remember { mutableStateOf(false) }
    var isClearConfirmationVisible by remember { mutableStateOf(false) }
    var isExportConfirmationVisible by remember { mutableStateOf(false) }
    val controlsEnabled = !isLoading && loadError == null && !screen.isOperationInProgress

    AppShell(
        title = "记录",
        actions = {
            TicketRecordManagementMenu(
                expanded = isManagementMenuExpanded,
                enabled = controlsEnabled,
                supportsFileExchange = supportsFileExchange,
                canClear = records.isNotEmpty(),
                onExpandedChange = { isManagementMenuExpanded = it },
                onImport = onImport,
                onRequestExport = { isExportConfirmationVisible = true },
                onRequestClear = { isClearConfirmationVisible = true },
            )
        },
        mainDestination = MainDestination.RECORDS,
        availableMainDestinations = availableMainDestinations,
        onMainDestinationSelected = onMainDestinationSelected,
        scrollableContent = false,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxWidth().height(4.dp)) {
                if (isLoading || screen.isOperationInProgress) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = screen.searchQuery,
                onValueChange = onSearchChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("名称或起始期号") },
                enabled = controlsEnabled,
                singleLine = true,
                leadingIcon = {
                    Icon(LotteryIcons.Search, contentDescription = null)
                },
                trailingIcon =
                    if (screen.searchQuery.isNotEmpty()) {
                        {
                            IconButton(onClick = { onSearchChange("") }) {
                                Icon(LotteryIcons.Clear, contentDescription = "清除搜索")
                            }
                        }
                    } else {
                        null
                    },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            )
            Spacer(Modifier.height(12.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                TicketRecordFilter.entries.forEachIndexed { index, filter ->
                    SegmentedButton(
                        selected = screen.filter == filter,
                        onClick = { onFilterChange(filter) },
                        enabled = controlsEnabled,
                        shape = SegmentedButtonDefaults.itemShape(index, TicketRecordFilter.entries.size),
                        label = { Text(filter.displayName()) },
                    )
                }
            }
            val errorMessage = loadError ?: screen.operationError
            if (errorMessage != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            screen.operationNotice?.let { notice ->
                Spacer(Modifier.height(12.dp))
                Text(
                    notice,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            if (!isLoading && loadError == null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "${visibleRecords.size} 条记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    isLoading -> {
                        item(key = "loading") {
                            Box(
                                modifier = Modifier.fillParentMaxHeight().fillMaxWidth(),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }

                    loadError != null -> {
                        item(key = "load-error") {
                            Box(
                                modifier = Modifier.fillParentMaxHeight().fillMaxWidth(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "无法显示记录",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    visibleRecords.isEmpty() -> {
                        item(key = "empty") {
                            Box(
                                modifier = Modifier.fillParentMaxHeight().fillMaxWidth(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    if (records.isEmpty()) "暂无本机记录" else "没有符合条件的记录",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    else -> {
                        items(items = visibleRecords, key = StoredTicketRecord::id) { record ->
                            TicketRecordItem(
                                record = record,
                                onRename = {
                                    renamingRecord = record
                                    renameValue = record.displayName
                                },
                                onDelete = { deletingRecord = record },
                                onQuery = { onQuery(record.id) },
                                enabled = controlsEnabled,
                            )
                        }
                    }
                }
            }
        }
    }

    renamingRecord?.let { record ->
        RenameTicketRecordDialog(
            currentName = record.displayName,
            value = renameValue,
            onValueChange = { renameValue = it },
            onDismiss = { renamingRecord = null },
            onConfirm = {
                onRename(record.id, renameValue)
                renamingRecord = null
            },
        )
    }

    deletingRecord?.let { record ->
        TicketRecordConfirmationDialog(
            title = "删除记录",
            message = "确定删除“${record.displayName}”吗？此操作无法撤销。",
            confirmLabel = "删除",
            isDestructive = true,
            onDismiss = { deletingRecord = null },
            onConfirm = {
                deletingRecord = null
                onDelete(record.id)
            },
        )
    }

    if (isClearConfirmationVisible) {
        TicketRecordConfirmationDialog(
            title = "清空全部记录",
            message = "将永久删除本机保存的 ${records.size} 条结构化票据记录。此操作无法撤销。",
            confirmLabel = "全部清空",
            isDestructive = true,
            onDismiss = { isClearConfirmationVisible = false },
            onConfirm = {
                isClearConfirmationVisible = false
                onClearAll()
            },
        )
    }

    if (isExportConfirmationVisible) {
        TicketRecordConfirmationDialog(
            title = "导出记录",
            message = "导出文件包含彩种、期号、投注号码、倍数、期数和金额等投注信息。请保存到你信任的位置。",
            confirmLabel = "选择保存位置",
            onDismiss = { isExportConfirmationVisible = false },
            onConfirm = {
                isExportConfirmationVisible = false
                onExport()
            },
        )
    }

    screen.importPreview?.let { preview ->
        TicketRecordImportDialog(
            preview = preview,
            isImporting = screen.isOperationInProgress,
            errorMessage = screen.operationError,
            onDismiss = onDismissImport,
            onConfirm = onConfirmImport,
        )
    }
}

/** 显示记录页的导入、导出和清空操作菜单。 */
@Composable
private fun TicketRecordManagementMenu(
    expanded: Boolean,
    enabled: Boolean,
    supportsFileExchange: Boolean,
    canClear: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onImport: () -> Unit,
    onRequestExport: () -> Unit,
    onRequestClear: () -> Unit,
) {
    Box {
        IconButton(
            onClick = { onExpandedChange(true) },
            enabled = enabled && (supportsFileExchange || canClear),
        ) {
            Icon(LotteryIcons.More, contentDescription = "记录管理")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            if (supportsFileExchange) {
                DropdownMenuItem(
                    text = { Text("导入记录") },
                    onClick = {
                        onExpandedChange(false)
                        onImport()
                    },
                    leadingIcon = {
                        Icon(LotteryIcons.ImportFile, contentDescription = null)
                    },
                )
                DropdownMenuItem(
                    text = { Text("导出记录") },
                    onClick = {
                        onExpandedChange(false)
                        onRequestExport()
                    },
                    leadingIcon = {
                        Icon(LotteryIcons.ExportFile, contentDescription = null)
                    },
                )
                HorizontalDivider()
            }
            DropdownMenuItem(
                text = { Text("清空全部", color = MaterialTheme.colorScheme.error) },
                onClick = {
                    onExpandedChange(false)
                    onRequestClear()
                },
                enabled = canClear,
                leadingIcon = {
                    Icon(
                        LotteryIcons.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
            )
        }
    }
}

/** 显示一条可重复操作的结构化票据摘要。 */
@Composable
private fun TicketRecordItem(
    record: StoredTicketRecord,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onQuery: () -> Unit,
    enabled: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    record.displayName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onRename, enabled = enabled) {
                    Icon(LotteryIcons.Edit, contentDescription = "重命名记录")
                }
                IconButton(onClick = onDelete, enabled = enabled) {
                    Icon(LotteryIcons.Delete, contentDescription = "删除记录")
                }
            }
            Text(
                "${record.ticket.lotteryType.value.displayName()} · ${record.ticket.issue.value.value}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "${record.ticket.periodCount.value} 期 · " +
                    "${record.ticket.multiplier.value} 倍 · " +
                    "${record.ticket.betLines.size} 注 · " +
                    record.ticket.paidAmountFen.value
                        .toYuanText(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${record.acquisitionSource.displayName()} · ${record.createdAtEpochMillis.toLocalTimeText()}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onQuery,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = MaterialTheme.shapes.small,
            ) {
                Icon(LotteryIcons.Search, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("查询开奖")
            }
        }
    }
}

/** 显示记录名称编辑对话框，并在本地完成长度校验。 */
@Composable
private fun RenameTicketRecordDialog(
    currentName: String,
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val normalized = value.trim()
    val problem =
        when {
            normalized.isEmpty() -> {
                "记录名称不能为空"
            }

            normalized.length > MAX_TICKET_RECORD_NAME_LENGTH -> {
                "记录名称不能超过 $MAX_TICKET_RECORD_NAME_LENGTH 个字符"
            }

            else -> {
                null
            }
        }
    val canConfirm = problem == null && normalized != currentName
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名记录") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("记录名称") },
                singleLine = true,
                isError = problem != null,
                supportingText = problem?.let { message -> { Text(message) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canConfirm) onConfirm() }),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = canConfirm) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

/** 显示删除、清空或导出前的明确确认。 */
@Composable
private fun TicketRecordConfirmationDialog(
    title: String,
    message: String,
    confirmLabel: String,
    isDestructive: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor =
                            if (isDestructive) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                    ),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

/** 显示逻辑导入包预检摘要，并在冲突时要求用户明确保留本机版本。 */
@Composable
private fun TicketRecordImportDialog(
    preview: TicketRecordImportPreview,
    isImporting: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: (Boolean) -> Unit,
) {
    val hasConflicts = preview.conflicts.isNotEmpty()
    AlertDialog(
        onDismissRequest = { if (!isImporting) onDismiss() },
        title = {
            Text(if (hasConflicts) "发现记录冲突" else "导入记录")
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("文件包含 ${preview.recordCount} 条合法记录。")
                Text(
                    "将新增 ${preview.importableCount} 条，跳过 ${preview.duplicateCount} 条完全相同记录。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (hasConflicts) {
                    Text(
                        "另有 ${preview.conflicts.size} 条记录与本机标识相同但内容不同。继续后将保留本机版本，不会覆盖。",
                        color = MaterialTheme.colorScheme.error,
                    )
                    preview.conflicts.take(MAX_VISIBLE_IMPORT_CONFLICTS).forEach { conflict ->
                        Text(
                            "本机：${conflict.localDisplayName}\n导入：${conflict.importedDisplayName}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (preview.conflicts.size > MAX_VISIBLE_IMPORT_CONFLICTS) {
                        Text(
                            "其余 ${preview.conflicts.size - MAX_VISIBLE_IMPORT_CONFLICTS} 条冲突不在此处展开",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                errorMessage?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(hasConflicts) },
                enabled = !isImporting,
            ) {
                if (isImporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    when {
                        isImporting -> "正在导入"
                        hasConflicts -> "保留本机并导入其余"
                        else -> "导入"
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isImporting) {
                Text("取消")
            }
        },
    )
}

/** 返回列表筛选的简体中文名称。 */
private fun TicketRecordFilter.displayName(): String =
    when (this) {
        TicketRecordFilter.ALL -> "全部"
        TicketRecordFilter.SUPER_LOTTO -> "大乐透"
        TicketRecordFilter.DOUBLE_COLOR_BALL -> "双色球"
    }

/** 返回彩种的简体中文名称。 */
private fun LotteryType.displayName(): String =
    when (this) {
        LotteryType.SUPER_LOTTO -> "超级大乐透"
        LotteryType.DOUBLE_COLOR_BALL -> "双色球"
    }

/** 返回票据首次采集方式的简体中文名称。 */
private fun TicketAcquisitionSource.displayName(): String =
    when (this) {
        TicketAcquisitionSource.CAMERA -> "拍照"
        TicketAcquisitionSource.SYSTEM_PICKER -> "系统选图"
        TicketAcquisitionSource.MANUAL_ENTRY -> "手动录入"
    }

/** 把分转换为只用于列表展示的人民币文本。 */
private fun Long.toYuanText(): String = "${this / FEN_PER_YUAN}.${(this % FEN_PER_YUAN).toString().padStart(2, '0')} 元"

/** 把 Unix 毫秒转换为当前设备时区的分钟级文本。 */
private fun Long.toLocalTimeText(): String =
    try {
        Instant
            .fromEpochMilliseconds(this)
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .toString()
            .replace('T', ' ')
            .take(LOCAL_TIME_TEXT_LENGTH)
    } catch (_: Exception) {
        "时间未知"
    }

/** 金额和时间展示常量。 */
private const val FEN_PER_YUAN = 100L

/** `yyyy-MM-dd HH:mm` 的固定文本长度。 */
private const val LOCAL_TIME_TEXT_LENGTH = 16

/** 导入冲突对话框最多直接展示的记录数量。 */
private const val MAX_VISIBLE_IMPORT_CONFLICTS = 3

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import roc.win.lottery.app.TicketRecordFilter
import roc.win.lottery.app.filterTicketRecords
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.persistence.MAX_TICKET_RECORD_NAME_LENGTH
import roc.win.lottery.persistence.StoredTicketRecord
import roc.win.lottery.persistence.TicketAcquisitionSource
import kotlin.time.Instant

/**
 * 显示可筛选、搜索、重命名和再次查询的本机结构化票据列表。
 *
 * @param screen 当前列表筛选与操作状态。
 * @param records 仓库提供的全部合法记录。
 * @param isLoading 是否仍在等待数据库首次返回。
 * @param loadError 数据库读取失败时的安全错误说明。
 * @param onBack 返回首页。
 * @param onFilterChange 修改彩种筛选。
 * @param onSearchChange 修改搜索文本。
 * @param onRename 保存新名称。
 * @param onQuery 直接使用已保存票据查询开奖。
 */
@Composable
fun TicketRecordsScreen(
    screen: AppScreen.Records,
    records: List<StoredTicketRecord>,
    isLoading: Boolean,
    loadError: String?,
    onBack: () -> Unit,
    onFilterChange: (TicketRecordFilter) -> Unit,
    onSearchChange: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onQuery: (String) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val visibleRecords =
        remember(records, screen.filter, screen.searchQuery) {
            filterTicketRecords(records, screen.filter, screen.searchQuery)
        }
    var renamingRecord by remember { mutableStateOf<StoredTicketRecord?>(null) }
    var renameValue by remember { mutableStateOf("") }

    AppShell(
        title = "本机记录",
        navigationIcon = LotteryIcons.Back,
        onNavigate = onBack,
        scrollableContent = false,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(top = 20.dp)) {
            OutlinedTextField(
                value = screen.searchQuery,
                onValueChange = onSearchChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("名称或起始期号") },
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
                                onQuery = { onQuery(record.id) },
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
}

/** 显示一条可重复操作的结构化票据摘要。 */
@Composable
private fun TicketRecordItem(
    record: StoredTicketRecord,
    onRename: () -> Unit,
    onQuery: () -> Unit,
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
                IconButton(onClick = onRename) {
                    Icon(LotteryIcons.Edit, contentDescription = "重命名记录")
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

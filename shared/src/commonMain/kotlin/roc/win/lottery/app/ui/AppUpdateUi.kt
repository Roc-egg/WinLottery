package roc.win.lottery.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import roc.win.lottery.app.AppDistribution
import roc.win.lottery.app.AppUpdateInfo
import roc.win.lottery.app.AppUpdateState
import roc.win.lottery.app.AppUpdateStatus
import kotlin.time.Instant

/**
 * 关于页的版本更新分区：自动检查开关、最近一次检查结果和手动检查入口。
 *
 * @param state 当前版本更新状态。
 * @param onCheck 立即检查更新。
 * @param onAutoCheckChange 开启或关闭启动时自动检查。
 * @param onShowUpdate 重新查看已发现的新版本。
 */
@Composable
internal fun AppUpdateSection(
    state: AppUpdateState,
    onCheck: () -> Unit,
    onAutoCheckChange: (Boolean) -> Unit,
    onShowUpdate: () -> Unit,
) {
    SectionHeader(title = "版本更新")
    Spacer(Modifier.height(SectionTitleSpacing))
    LotteryCard(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingLine(
            title = "启动时自动检查",
            detail = "距上次检查超过 24 小时才访问 GitHub，发现新版本时提示。",
        ) {
            Switch(checked = state.autoCheckEnabled, onCheckedChange = onAutoCheckChange)
        }
        HairlineDivider()
        val status = state.status
        Text(
            status.summary(state),
            style = MaterialTheme.typography.bodyMedium,
            color =
                when (status) {
                    is AppUpdateStatus.Available -> MaterialTheme.colorScheme.primary
                    is AppUpdateStatus.Failed -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
        if (status is AppUpdateStatus.Available) {
            PrimaryActionButton(
                text = "查看新版本 ${status.update.version}",
                onClick = onShowUpdate,
                icon = LotteryIcons.AppUpdate,
            )
        }
        OutlineActionButton(
            text = if (status == AppUpdateStatus.Checking) "正在检查" else "检查更新",
            onClick = onCheck,
            enabled = status != AppUpdateStatus.Checking,
            loading = status == AppUpdateStatus.Checking,
            icon = LotteryIcons.Refresh,
        )
    }
}

/**
 * 新版本提示：版本号、发布时间、本版更新内容和下载方式。
 *
 * 下载按钮用系统浏览器打开安装包或发布页；打不开时显示地址供手动访问。
 *
 * @param update 新版本信息。
 * @param currentVersion 当前安装包版本号。
 * @param onOpened 已交给浏览器打开，关闭提示。
 * @param onSkip 跳过此版本，之后的自动检查不再提示。
 * @param onDismiss 暂时关闭提示。
 */
@Composable
internal fun AppUpdateDialog(
    update: AppUpdateInfo,
    currentVersion: String,
    onOpened: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    var openFailed by remember(update) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        icon = { IconTile(icon = LotteryIcons.AppUpdate, size = 48.dp) },
        title = { Text("发现新版本 ${update.version}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    buildString {
                        append("当前版本 ")
                        append(currentVersion)
                        update.publishedAt?.let { append(" · 发布于 ").append(it.toChinaDate()) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (update.notes.isNotBlank()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Text(
                            update.notes,
                            modifier =
                                Modifier
                                    .heightIn(max = 240.dp)
                                    .verticalScroll(rememberScrollState())
                                    .padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Text(
                    update.installHint(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (openFailed) {
                    SelectionContainer {
                        Text(
                            "无法打开浏览器，请手动访问：${update.downloadUrl}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    uriHandler.openUri(update.downloadUrl)
                    onOpened()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    openFailed = true
                }
            }) {
                Text(if (update.installerName != null) "下载新版" else "前往发布页")
            }
        },
        dismissButton = {
            FlowRow {
                TextButton(onClick = onSkip) {
                    Text("跳过此版本", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onDismiss) {
                    Text("以后再说")
                }
            }
        },
    )
}

/** 返回关于页展示的检查结果说明。 */
private fun AppUpdateStatus.summary(state: AppUpdateState): String =
    when (this) {
        AppUpdateStatus.NotChecked -> {
            state.lastCheckedAt?.let { "当前版本 ${state.currentVersion}，上次检查于 ${it.toChinaDateTime()}" }
                ?: "当前版本 ${state.currentVersion}，尚未检查更新"
        }

        AppUpdateStatus.Checking -> {
            "正在查询 GitHub 上的最新发布版…"
        }

        AppUpdateStatus.UpToDate -> {
            "已是最新版本 ${state.currentVersion}" +
                (state.lastCheckedAt?.let { "，检查于 ${it.toChinaDateTime()}" } ?: "")
        }

        is AppUpdateStatus.Available -> {
            "发现新版本 ${update.version}，当前版本 ${state.currentVersion}"
        }

        is AppUpdateStatus.Failed -> {
            message
        }
    }

/** 说明下载按钮会做什么，以及拿到安装包后怎样升级。 */
private fun AppUpdateInfo.installHint(): String {
    val size = installerSizeBytes?.let { "（${it.toMegabytesText()}）" }.orEmpty()
    return if (installerName != null) {
        when (distribution) {
            AppDistribution.ANDROID -> "将用浏览器下载 APK 安装包$size，下载完成后打开即可覆盖安装。"
            AppDistribution.MACOS_ARM64 -> "将用浏览器下载 DMG 安装包$size，打开后把应用拖入“应用程序”文件夹替换旧版。"
            AppDistribution.WINDOWS_X64 -> "将用浏览器下载 MSI 安装包$size，运行后覆盖安装旧版。"
            AppDistribution.IOS, AppDistribution.OTHER -> "将用浏览器下载安装包$size。"
        }
    } else {
        when (distribution) {
            AppDistribution.IOS -> "将打开 GitHub 发布页。iOS 安装包未签名，需要用自己的证书重新签名后安装。"
            else -> "将打开 GitHub 发布页，请按说明下载适合本机的安装包。"
        }
    }
}

/** 把字节数换算为保留一位小数的 MB 文本。 */
private fun Long.toMegabytesText(): String {
    val tenths = (this * 10 + BYTES_PER_MEGABYTE / 2) / BYTES_PER_MEGABYTE
    return "${tenths / 10}.${tenths % 10} MB"
}

/** 按北京时间格式化为 `yyyy-MM-dd`。 */
private fun Instant.toChinaDate(): String {
    val dateTime = toLocalDateTime(CHINA_TIME_ZONE)
    return "${dateTime.year}-${(dateTime.month.ordinal + 1).twoDigits()}-${dateTime.day.twoDigits()}"
}

/** 按北京时间格式化为 `yyyy-MM-dd HH:mm`。 */
private fun Instant.toChinaDateTime(): String {
    val dateTime = toLocalDateTime(CHINA_TIME_ZONE)
    return "${toChinaDate()} ${dateTime.hour.twoDigits()}:${dateTime.minute.twoDigits()}"
}

/** 每 MB 字节数。 */
private const val BYTES_PER_MEGABYTE = 1_048_576L

/** 版本更新界面使用的北京时间时区。 */
private val CHINA_TIME_ZONE = TimeZone.of("Asia/Shanghai")

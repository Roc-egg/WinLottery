package roc.win.lottery.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import roc.win.lottery.app.MainDestination

/**
 * 彩票核对一级工作区。
 *
 * @param supportsCamera 当前平台是否支持拍摄彩票。
 * @param isDemo 是否展示开发阶段能力边界。
 * @param usesRealImageAcquisition 是否已接入真实图片采集。
 * @param usesRealRecognition 是否已接入真实本地 OCR。
 * @param usesRealDrawData 是否已接入真实官网开奖查询。
 * @param availableMainDestinations 当前平台可进入的一级目的地。
 * @param onMainDestinationSelected 一级目的地切换操作。
 * @param onCamera 拍照操作。
 * @param onImport 图片导入操作。
 * @param onManualEntry 手动录入彩票操作。
 * @param onAbout 关于与隐私操作。
 */
@Composable
fun HomeScreen(
    supportsCamera: Boolean,
    isDemo: Boolean,
    usesRealImageAcquisition: Boolean,
    usesRealRecognition: Boolean,
    usesRealDrawData: Boolean,
    availableMainDestinations: List<MainDestination>,
    onMainDestinationSelected: (MainDestination) -> Unit,
    onCamera: () -> Unit,
    onImport: () -> Unit,
    onManualEntry: () -> Unit,
    onAbout: () -> Unit,
) {
    AppShell(
        title = "核对",
        actionIcon = LotteryIcons.Info,
        actionDescription = "关于与隐私",
        onAction = onAbout,
        mainDestination = MainDestination.VERIFICATION,
        availableMainDestinations = availableMainDestinations,
        onMainDestinationSelected = onMainDestinationSelected,
    ) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                if (isDemo) {
                    DemoNotice(usesRealImageAcquisition, usesRealRecognition, usesRealDrawData)
                    Spacer(Modifier.height(24.dp))
                }
                Text("纸质彩票核对", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "支持超级大乐透和双色球，票面信息由你确认后再查询官方开奖数据。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(28.dp))
                Text("票面来源", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                TicketSourceActions(
                    supportsCamera = supportsCamera,
                    supportsManualEntry = usesRealDrawData,
                    onCamera = onCamera,
                    onImport = onImport,
                    onManualEntry = onManualEntry,
                )
                Spacer(Modifier.height(32.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(24.dp))
                Text("隐私与结果边界", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        LotteryIcons.Privacy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "图片不上传，确认后删除；结构化票据信息只保存在本机。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "测算结果以发行机构公告和实体彩票兑奖结果为准。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 根据可用宽度排列彩票来源操作。 */
@Composable
private fun TicketSourceActions(
    supportsCamera: Boolean,
    supportsManualEntry: Boolean,
    onCamera: () -> Unit,
    onImport: () -> Unit,
    onManualEntry: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val usesHorizontalLayout = maxWidth >= 600.dp
        if (usesHorizontalLayout) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (supportsCamera) {
                    TicketSourceButton(
                        label = "拍照",
                        icon = LotteryIcons.Camera,
                        primary = true,
                        onClick = onCamera,
                        modifier = Modifier.weight(1f),
                    )
                }
                TicketSourceButton(
                    label = "导入图片",
                    icon = LotteryIcons.Image,
                    primary = !supportsCamera,
                    onClick = onImport,
                    modifier = Modifier.weight(1f),
                )
                if (supportsManualEntry) {
                    TicketSourceButton(
                        label = "手动录入",
                        icon = LotteryIcons.Edit,
                        primary = false,
                        onClick = onManualEntry,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (supportsCamera) {
                    TicketSourceButton(
                        label = "拍照",
                        icon = LotteryIcons.Camera,
                        primary = true,
                        onClick = onCamera,
                    )
                }
                TicketSourceButton(
                    label = "导入图片",
                    icon = LotteryIcons.Image,
                    primary = !supportsCamera,
                    onClick = onImport,
                )
                if (supportsManualEntry) {
                    TicketSourceButton(
                        label = "手动录入",
                        icon = LotteryIcons.Edit,
                        primary = false,
                        onClick = onManualEntry,
                    )
                }
            }
        }
    }
}

/** 绘制单个票面来源操作，并保持主次操作语义。 */
@Composable
private fun TicketSourceButton(
    label: String,
    icon: ImageVector,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val buttonModifier = modifier.fillMaxWidth().heightIn(min = 56.dp)
    val contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    if (primary) {
        Button(
            onClick = onClick,
            modifier = buttonModifier,
            shape = MaterialTheme.shapes.small,
            contentPadding = contentPadding,
        ) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text(label)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = buttonModifier,
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.secondary),
            contentPadding = contentPadding,
        ) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text(label)
        }
    }
}

/**
 * 开发阶段能力边界提示。
 *
 * @param usesRealImageAcquisition 是否已接入真实图片采集。
 * @param usesRealRecognition 是否已接入真实本地 OCR。
 * @param usesRealDrawData 是否已接入真实官网开奖查询。
 */
@Composable
private fun DemoNotice(
    usesRealImageAcquisition: Boolean,
    usesRealRecognition: Boolean,
    usesRealDrawData: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text =
                when {
                    usesRealRecognition && usesRealDrawData -> {
                        "本地识别验证版：图片导入、本地 OCR、人工确认、官网开奖结果查询和本地中奖测算已贯通；正式对账与数据授权尚未完成。"
                    }

                    usesRealRecognition -> {
                        "识别 PoC：图片导入、本地 OCR 和保守解析已在本机运行；开奖结果仍为固定演示数据。"
                    }

                    usesRealImageAcquisition -> {
                        "导入 PoC：系统文件导入和私有图片副本已在本机运行；桌面 OCR 尚未接入。"
                    }

                    else -> {
                        "开发演示：票面解析使用保守真实逻辑，图片采集、OCR 和开奖结果仍为本地固定数据。"
                    }
                },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

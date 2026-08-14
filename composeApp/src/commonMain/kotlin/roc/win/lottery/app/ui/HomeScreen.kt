package roc.win.lottery.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp

/**
 * 应用首页。
 *
 * @param platformName 当前平台展示名称。
 * @param supportsCamera 是否显示移动端拍照操作。
 * @param isDemo 是否展示开发阶段能力边界。
 * @param usesRealImageAcquisition 是否已接入真实图片采集。
 * @param usesRealRecognition 是否已接入真实本地 OCR。
 * @param usesRealDrawData 是否已接入真实官网开奖查询。
 * @param onCamera 拍照操作。
 * @param onImport 图片导入操作。
 * @param onAbout 关于与隐私操作。
 */
@Composable
fun HomeScreen(
    platformName: String,
    supportsCamera: Boolean,
    isDemo: Boolean,
    usesRealImageAcquisition: Boolean,
    usesRealRecognition: Boolean,
    usesRealDrawData: Boolean,
    onCamera: () -> Unit,
    onImport: () -> Unit,
    onAbout: () -> Unit,
) {
    AppShell(
        title = "中奖测算",
        actionIcon = LotteryIcons.Info,
        actionDescription = "关于与隐私",
        onAction = onAbout,
    ) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth()) {
                if (isDemo) {
                    DemoNotice(usesRealImageAcquisition, usesRealRecognition, usesRealDrawData)
                    Spacer(Modifier.height(24.dp))
                }
                Text("纸质彩票中奖测算", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(12.dp))
                Text(
                    "支持超级大乐透和双色球。票面信息会在本机处理，并由你逐项确认。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(32.dp))
                if (supportsCamera) {
                    Button(
                        onClick = onCamera,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Icon(LotteryIcons.Camera, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text("拍摄彩票")
                    }
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedButton(
                    onClick = onImport,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.secondary),
                ) {
                    Icon(LotteryIcons.Image, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text("导入彩票图片")
                }
                Spacer(Modifier.height(32.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        LotteryIcons.Privacy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("图片仅在本机处理", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "不上传、不保存测算历史；查询开奖时只发送彩种和期号。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(28.dp))
                Text(
                    "当前平台：$platformName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "结果仅供核对，以发行机构公告和实体彩票兑奖结果为准。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
                        "移动验证版：本地 OCR、人工确认、官网开奖结果查询和本地中奖测算已贯通；正式对账与数据授权尚未完成。"
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

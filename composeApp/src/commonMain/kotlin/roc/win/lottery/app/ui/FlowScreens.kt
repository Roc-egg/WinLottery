package roc.win.lottery.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import roc.win.lottery.domain.DrawResult
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.TicketDraft

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
 * 显示 B1/B3 人工确认页面壳。
 *
 * @param draft 经过保守解析的票面草稿。
 * @param isDemo 开奖等后续能力是否仍为开发演示实现。
 * @param usesRealRecognition 当前草稿是否来自真实图片导入和本地 OCR。
 * @param onBack 返回并清理当前临时票图的操作。
 * @param onConfirm 用户确认当前字段的操作。
 */
@Composable
fun ReviewScreen(
    draft: TicketDraft,
    isDemo: Boolean,
    usesRealRecognition: Boolean,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
) {
    AppShell(
        title = "确认票面信息",
        navigationIcon = LotteryIcons.Back,
        onNavigate = onBack,
    ) {
        if (isDemo) {
            StatusBanner(
                if (usesRealRecognition) {
                    "以下字段来自真实本地 OCR，但编辑器尚未接入；请勿继续用于实际中奖判断。"
                } else {
                    "以下字段来自 B1 固定演示数据，真实编辑器将在 B4 接入。"
                },
            )
            Spacer(Modifier.height(20.dp))
        }
        Text("请逐项核对", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "关键字段未经确认，不会查询开奖或输出中奖结论。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        InfoSection(
            rows =
                listOf(
                    "彩种" to draft.lotteryType.displayName(),
                    "期号" to draft.issue,
                    "倍数" to "${draft.multiplier ?: "待确认"} 倍",
                    "期数" to "${draft.periodCount ?: "待确认"} 期",
                    "票面金额" to draft.paidAmountFen.toYuanText(),
                ),
        )
        Spacer(Modifier.height(20.dp))
        Text("投注号码", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        draft.betLines.forEachIndexed { index, line ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("第 ${index + 1} 注", style = MaterialTheme.typography.titleMedium)
                    Text(
                        line.primaryNumbers.joinToString("  ") { it.toString().padStart(2, '0') },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "${draft.lotteryType.secondaryAreaName()} " +
                            line.secondaryNumbers.joinToString("  ") { it.toString().padStart(2, '0') } +
                            if (line.isAdditional == true) " · 追加" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = if (usesRealRecognition) onBack else onConfirm,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = MaterialTheme.shapes.small,
        ) {
            Text(if (usesRealRecognition) "结束本次识别" else "已核对，继续")
        }
    }
}

/** 显示开奖查询中的明确状态。 */
@Composable
fun DrawQueryScreen(
    issue: String,
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
            Text("正在查询第 $issue 期", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(10.dp))
            Text(
                "只有用户确认后才会发起单期查询",
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
 * @param onDone 返回首页的操作。
 */
@Composable
fun DemoCompleteScreen(
    drawResult: DrawResult,
    onDone: () -> Unit,
) {
    AppShell(title = "流程演示完成") {
        StatusBanner("开发链路已跑通，本页不代表真实中奖结果。")
        Spacer(Modifier.height(24.dp))
        Text("四端共享状态流可用", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            "票面结构已使用保守解析器；图片采集、OCR 和开奖查询仍由 Fake 实现。",
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

/** 显示可恢复错误状态。 */
@Composable
fun ErrorScreen(
    title: String,
    message: String,
    onBack: () -> Unit,
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
        Button(onClick = onBack, shape = MaterialTheme.shapes.small) {
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
            "单张、单期、电脑打印的超级大乐透或双色球彩票；支持单式、多注单式、倍数及大乐透追加。",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "不支持复式、胆拖、多期、手写票、电子截图或未知票面版式。",
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

/** 返回当前彩种次号码区域的准确名称。 */
private fun LotteryType?.secondaryAreaName(): String =
    when (this) {
        LotteryType.SUPER_LOTTO -> "后区"
        LotteryType.DOUBLE_COLOR_BALL -> "蓝球"
        null -> "次号码"
    }

/** 把分转换为不参与计算的展示文本。 */
private fun Long?.toYuanText(): String =
    if (this == null) {
        "待确认"
    } else {
        "${this / 100}.${(this % 100).toString().padStart(2, '0')} 元"
    }

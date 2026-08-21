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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import roc.win.lottery.app.AppScreen
import roc.win.lottery.app.PeriodVerification
import roc.win.lottery.domain.BetLinePrizeResult
import roc.win.lottery.domain.ConfirmedTicket
import roc.win.lottery.domain.DrawResult
import roc.win.lottery.domain.DrawStatus
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.PrizeCheckResult
import roc.win.lottery.domain.PrizeCheckStatus
import roc.win.lottery.domain.PrizeTierCodes
import roc.win.lottery.domain.SourceEvidence
import kotlin.time.Instant

/**
 * 展示真实官网开奖与本地中奖测算结果。
 *
 * @param ticket 用户逐项确认的票据。
 * @param drawResult 经双数据面核对的开奖结果。
 * @param prizeCheckResult 本地规则引擎输出的逐注结果。
 * @param onRetry 主动重新查询同一期开奖。
 * @param onBack 返回票面确认页。
 * @param onDone 清除当前流程并返回首页。
 */
@Composable
fun VerificationResultScreen(
    ticket: ConfirmedTicket,
    drawResult: DrawResult,
    prizeCheckResult: PrizeCheckResult,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    AppShell(
        title = "中奖测算结果",
        navigationIcon = LotteryIcons.Back,
        onNavigate = onBack,
    ) {
        VerificationSummary(prizeCheckResult)
        Spacer(Modifier.height(24.dp))
        Text("开奖号码", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        DrawNumbers(drawResult)
        Spacer(Modifier.height(20.dp))
        VerificationInfo(
            rows =
                listOf(
                    "彩种" to drawResult.lotteryType.displayName(),
                    "期号" to drawResult.issue.value,
                    "开奖日期" to drawResult.drawDate,
                    "数据状态" to drawResult.status.displayName(),
                    "规则版本" to drawResult.ruleVersion,
                    "修订号" to drawResult.revision.toString(),
                ),
        )
        if (prizeCheckResult.lineResults.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            Text("逐注核对", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                prizeCheckResult.lineResults.forEach { lineResult ->
                    BetLineResult(
                        ticket = ticket,
                        drawResult = drawResult,
                        result = lineResult,
                    )
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Text("官方数据证据", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            (listOf(drawResult.evidence) + drawResult.supportingEvidence).forEach { evidence ->
                EvidenceItem(evidence)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "本结果只核对开奖号码和奖级，不验证彩票真伪、撤单、作废或兑奖状态；最终以发行机构公告和实体彩票兑奖为准。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        OutlinedButton(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = MaterialTheme.shapes.small,
        ) {
            Text("重新查询本期")
        }
        Spacer(Modifier.height(10.dp))
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
 * 展示开奖证据不足的可重试状态，且不形成中奖结论。
 *
 * @param ticket 用户逐项确认的票据。
 * @param status 本次官网查询的不可用状态。
 * @param message 仓库返回的恢复说明。
 * @param onRetry 主动重新查询同一期开奖。
 * @param onBack 返回票面确认页。
 * @param onDone 清除当前流程并返回首页。
 */
@Composable
fun DrawUnavailableScreen(
    ticket: ConfirmedTicket,
    status: DrawStatus,
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    AppShell(
        title = "开奖核对",
        navigationIcon = LotteryIcons.Back,
        onNavigate = onBack,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(status.unavailableTitle(), style = MaterialTheme.typography.headlineMedium)
                Text(
                    "当前没有取得足够的官方证据，未形成中奖或未中奖结论。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        VerificationInfo(
            rows =
                listOf(
                    "彩种" to ticket.lotteryType.value.displayName(),
                    "期号" to ticket.issue.value.value,
                    "查询状态" to status.displayName(),
                ),
        )
        Spacer(Modifier.height(20.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        Text(
            status.recoveryHint(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = MaterialTheme.shapes.small,
        ) {
            Text("重新查询")
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = MaterialTheme.shapes.small,
        ) {
            Text("返回首页")
        }
    }
}

/**
 * 展示一张多期票的逐期开奖状态和保守整票汇总。
 *
 * @param result 控制器按期号顺序保存的多期结果。
 * @param onRetry 只重查证据不足或奖金尚未完整的期次。
 * @param onBack 返回票面确认页。
 * @param onDone 清除当前流程并返回首页。
 */
@Composable
fun MultiPeriodVerificationScreen(
    result: AppScreen.MultiPeriodVerificationResult,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    AppShell(
        title = "多期开奖核对",
        navigationIcon = LotteryIcons.Back,
        onNavigate = onBack,
    ) {
        MultiPeriodSummary(result)
        result.retryNotice?.let { notice ->
            Spacer(Modifier.height(12.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("本次重查未完成", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${notice.status.displayName()}：${notice.message}。已保留此前确认的开奖号码和测算结果。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        VerificationInfo(
            rows =
                listOf(
                    "彩种" to
                        result.ticket.lotteryType.value
                            .displayName(),
                    "起始期号" to result.ticket.issue.value.value,
                    "投注期数" to "${result.ticket.periodCount.value} 期",
                    "已取得结果" to "${result.verifiedPeriodCount} 期",
                    "待继续核对" to "${result.unresolvedPeriodCount} 期",
                ),
        )
        Spacer(Modifier.height(28.dp))
        Text("逐期开奖结果", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            result.periodResults.forEach { periodResult ->
                PeriodVerificationItem(periodResult)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "任一期尚未发布、证据不足或规则结果需复核时，应用都不会把整张多期票解释为未中奖。最终以发行机构公告和实体彩票兑奖为准。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = MaterialTheme.shapes.small,
        ) {
            Text(if (result.unresolvedPeriodCount > 0) "重查未完成期次" else "重新查询全部期次")
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = MaterialTheme.shapes.small,
        ) {
            Text("返回首页")
        }
    }
}

/** 展示多期票在当前证据范围内的整票结论。 */
@Composable
private fun MultiPeriodSummary(result: AppScreen.MultiPeriodVerificationResult) {
    val estimatedPrizeFen = result.estimatedPrizeFen
    val headline =
        when {
            result.isConclusive && result.hasWinningPeriod -> "多期票有中奖记录"
            result.isConclusive -> "全部期次均未中奖"
            result.verifiedPeriodCount == result.periodResults.size -> "逐期开奖结果需要复核"
            else -> "已完成 ${result.verifiedPeriodCount}/${result.periodResults.size} 期核对"
        }
    val detail =
        when {
            !result.isConclusive -> "整票结论尚未形成，请继续核对未完成期次"
            estimatedPrizeFen != null -> "整票税前奖金合计 ${estimatedPrizeFen.toPrizeYuanText()}"
            result.hasWinningPeriod -> "已确认命中奖级，部分奖金仍待官方数据完整"
            else -> "所有期次均已使用最终开奖号码完成逐注核对"
        }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color =
            if (result.isConclusive) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(headline, style = MaterialTheme.typography.headlineMedium)
            Text(detail, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** 展示一个期次的号码、测算摘要或不可用原因。 */
@Composable
private fun PeriodVerificationItem(result: PeriodVerification) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text("第 ${result.issue.value} 期", style = MaterialTheme.typography.titleMedium)
                Text(
                    when (result) {
                        is PeriodVerification.Verified -> result.prizeCheckResult.headline()
                        is PeriodVerification.Unavailable -> result.status.displayName()
                    },
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.End,
                )
            }
            when (result) {
                is PeriodVerification.Verified -> {
                    Text(
                        "${result.drawResult.primaryNumbers.toNumberText()} + " +
                            result.drawResult.secondaryNumbers.toNumberText(),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        result.prizeCheckResult.detail(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val winningLines = result.prizeCheckResult.lineResults.filter { it.prizeTierCode != null }
                    if (winningLines.isNotEmpty()) {
                        Text(
                            winningLines.joinToString(separator = "；") { line ->
                                "第 ${line.lineIndex + 1} 注${checkNotNull(line.prizeTierCode).displayPrizeTier()}"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        "${result.drawResult.drawDate} · ${result.drawResult.status.displayName()} · " +
                            "${result.drawResult.supportingEvidence.size + 1} 份官方证据",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is PeriodVerification.Unavailable -> {
                    Text(result.message, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (result.wasQueried) "本期已发起官网查询" else "本期尚未发起官网查询",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 展示中奖测算的安全汇总结论。 */
@Composable
private fun VerificationSummary(result: PrizeCheckResult) {
    val isConclusive = result.status == PrizeCheckStatus.WIN || result.status == PrizeCheckStatus.NO_WIN
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color =
            if (isConclusive) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(result.headline(), style = MaterialTheme.typography.headlineMedium)
            Text(
                result.detail(),
                style = MaterialTheme.typography.bodyLarge,
                color =
                    if (isConclusive) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
            )
        }
    }
}

/** 展示当期主号码和次号码。 */
@Composable
private fun DrawNumbers(drawResult: DrawResult) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NumberRow(
            label = if (drawResult.lotteryType == LotteryType.SUPER_LOTTO) "前区" else "红球",
            numbers = drawResult.primaryNumbers,
            background = MaterialTheme.colorScheme.primary,
            foreground = MaterialTheme.colorScheme.onPrimary,
        )
        NumberRow(
            label = if (drawResult.lotteryType == LotteryType.SUPER_LOTTO) "后区" else "蓝球",
            numbers = drawResult.secondaryNumbers,
            background = MaterialTheme.colorScheme.secondary,
            foreground = MaterialTheme.colorScheme.onSecondary,
        )
    }
}

/** 展示一组尺寸稳定的开奖号码球。 */
@Composable
private fun NumberRow(
    label: String,
    numbers: List<Int>,
    background: Color,
    foreground: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.padding(end = 4.dp), style = MaterialTheme.typography.titleMedium)
        numbers.forEach { number ->
            Surface(
                modifier = Modifier.size(42.dp),
                shape = CircleShape,
                color = background,
                contentColor = foreground,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(number.toString().padStart(2, '0'), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** 展示一行票面投注的命中数、奖级和金额。 */
@Composable
private fun BetLineResult(
    ticket: ConfirmedTicket,
    drawResult: DrawResult,
    result: BetLinePrizeResult,
) {
    val line = ticket.betLines.getOrNull(result.lineIndex)
    val tierName =
        result.prizeTierCode?.let { code ->
            drawResult.prizeTiers.firstOrNull { it.code == code }?.displayName ?: code.displayPrizeTier()
        }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("第 ${result.lineIndex + 1} 注", style = MaterialTheme.typography.titleMedium)
                Text(
                    tierName ?: "未中奖",
                    style = MaterialTheme.typography.titleMedium,
                    color =
                        if (tierName == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                )
            }
            line?.let {
                Text(
                    "${it.primaryNumbers.value.toNumberText()} + ${it.secondaryNumbers.value.toNumberText()}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                result.hitSummary(drawResult.lotteryType),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            line?.let {
                val betType = if (it.isAdditional.value) "追加投注" else "基本投注"
                Text(
                    "$betType · ${ticket.multiplier.value} 倍",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (tierName != null) {
                Text(
                    result.estimatedPrizeFen?.let { "本注税前奖金 ${it.toPrizeYuanText()}" } ?: "本注奖金待官方确认",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** 展示一组简洁的开奖结果元数据。 */
@Composable
private fun VerificationInfo(rows: List<Pair<String, String>>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            rows.forEachIndexed { index, row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        row.first,
                        modifier = Modifier.weight(0.38f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        row.second,
                        modifier = Modifier.weight(0.62f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                if (index != rows.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

/** 展示一份官方数据源及其规范化证据摘要。 */
@Composable
private fun EvidenceItem(evidence: SourceEvidence) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(evidence.sourceName, style = MaterialTheme.typography.titleMedium)
            Text(
                evidence.sourceUrl.compactUrl(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(
                "获取时间：${evidence.fetchedAtEpochMillis.toEvidenceTime()}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "证据校验：${evidence.contentSha256.take(EVIDENCE_HASH_PREVIEW_LENGTH)}...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 返回测算状态对应的主结论。 */
private fun PrizeCheckResult.headline(): String =
    when (status) {
        PrizeCheckStatus.WIN -> "有中奖记录"
        PrizeCheckStatus.NO_WIN -> "本期未中奖"
        PrizeCheckStatus.RULE_UNSUPPORTED -> "当前规则暂不支持"
        PrizeCheckStatus.NEEDS_MANUAL_REVIEW -> "结果需要人工复核"
        PrizeCheckStatus.NOT_CALCULATED -> "尚未完成测算"
    }

/** 返回测算状态对应的安全说明。 */
private fun PrizeCheckResult.detail(): String =
    when (status) {
        PrizeCheckStatus.WIN -> {
            estimatedPrizeFen?.let { "税前奖金合计 ${it.toPrizeYuanText()}" }
                ?: message
                ?: "已确认命中奖级，奖金待官方数据完整后再显示"
        }

        PrizeCheckStatus.NO_WIN -> {
            "所有投注行均已使用最终开奖号码完成核对"
        }

        PrizeCheckStatus.RULE_UNSUPPORTED,
        PrizeCheckStatus.NEEDS_MANUAL_REVIEW,
        PrizeCheckStatus.NOT_CALCULATED,
        -> {
            message ?: "当前证据不足，不能输出中奖或未中奖结论"
        }
    }

/** 返回逐注命中数的玩法化文案。 */
private fun BetLinePrizeResult.hitSummary(lotteryType: LotteryType): String =
    when (lotteryType) {
        LotteryType.SUPER_LOTTO -> "前区命中 $primaryHitCount 个，后区命中 $secondaryHitCount 个"
        LotteryType.DOUBLE_COLOR_BALL -> "红球命中 $primaryHitCount 个，蓝球命中 $secondaryHitCount 个"
    }

/** 返回彩票玩法中文名称。 */
private fun LotteryType.displayName(): String =
    when (this) {
        LotteryType.SUPER_LOTTO -> "超级大乐透"
        LotteryType.DOUBLE_COLOR_BALL -> "双色球"
    }

/** 返回开奖状态中文名称。 */
private fun DrawStatus.displayName(): String =
    when (this) {
        DrawStatus.NOT_PUBLISHED -> "尚未发布"
        DrawStatus.PUBLISHING -> "发布核对中"
        DrawStatus.FINAL_NUMBERS -> "开奖号码已确认"
        DrawStatus.FINAL_PAYOUT -> "开奖号码和奖金已确认"
        DrawStatus.NETWORK_UNAVAILABLE -> "网络不可用"
        DrawStatus.SOURCE_UNAVAILABLE -> "官方数据源不可用"
        DrawStatus.CONFLICT -> "官方证据冲突"
    }

/** 返回不可用状态页的标题。 */
private fun DrawStatus.unavailableTitle(): String =
    when (this) {
        DrawStatus.NOT_PUBLISHED -> "该期开奖结果尚未发布"

        DrawStatus.PUBLISHING -> "开奖结果正在发布核对"

        DrawStatus.NETWORK_UNAVAILABLE -> "当前无法连接网络"

        DrawStatus.SOURCE_UNAVAILABLE -> "官方数据暂时不可用"

        DrawStatus.CONFLICT -> "官方证据需要进一步复核"

        DrawStatus.FINAL_NUMBERS,
        DrawStatus.FINAL_PAYOUT,
        -> "开奖结果状态异常"
    }

/** 返回不可用状态对应的恢复建议。 */
private fun DrawStatus.recoveryHint(): String =
    when (this) {
        DrawStatus.NOT_PUBLISHED -> "请核对期号，并在官方开奖发布后重新查询。"

        DrawStatus.PUBLISHING -> "请等待官方数据同步完整后再主动查询。"

        DrawStatus.NETWORK_UNAVAILABLE -> "请检查设备网络后重新查询，票面确认结果仍保留在当前流程中。"

        DrawStatus.SOURCE_UNAVAILABLE -> "请稍后重新查询；若持续出现，请直接核对发行机构官网公告。"

        DrawStatus.CONFLICT -> "为避免误报，应用已停止自动判断，请以发行机构公告和实体票兑奖为准。"

        DrawStatus.FINAL_NUMBERS,
        DrawStatus.FINAL_PAYOUT,
        -> "请返回首页后重新发起核对。"
    }

/** 返回稳定奖级编码的中文兜底名称。 */
private fun String.displayPrizeTier(): String =
    when (this) {
        PrizeTierCodes.FIRST -> "一等奖"
        PrizeTierCodes.SECOND -> "二等奖"
        PrizeTierCodes.THIRD -> "三等奖"
        PrizeTierCodes.FOURTH -> "四等奖"
        PrizeTierCodes.FIFTH -> "五等奖"
        PrizeTierCodes.SIXTH -> "六等奖"
        PrizeTierCodes.SEVENTH -> "七等奖"
        PrizeTierCodes.FORTUNE -> "福运奖"
        else -> this
    }

/** 把号码列表转换为固定两位的票面文本。 */
private fun List<Int>.toNumberText(): String = joinToString(separator = " ") { it.toString().padStart(2, '0') }

/** 把分转换为带千位分隔的人民币展示文本。 */
private fun Long.toPrizeYuanText(): String {
    val yuan = this / FEN_PER_YUAN
    val fen = this % FEN_PER_YUAN
    val groupedYuan =
        yuan
            .toString()
            .reversed()
            .chunked(3)
            .joinToString(separator = ",")
            .reversed()
    return "$groupedYuan.${fen.toString().padStart(2, '0')} 元"
}

/** 把 Unix 毫秒时间转换为稳定的 UTC 文本。 */
private fun Long.toEvidenceTime(): String =
    if (this <= 0L) {
        "未知"
    } else {
        Instant.fromEpochMilliseconds(this).toString()
    }

/** 限制长查询地址的展示长度，避免挤压移动端布局。 */
private fun String.compactUrl(): String =
    if (length <= MAX_SOURCE_URL_LENGTH) {
        this
    } else {
        take(SOURCE_URL_PREFIX_LENGTH) + "..." + takeLast(SOURCE_URL_SUFFIX_LENGTH)
    }

/** 金额和证据摘要的展示常量。 */
private const val FEN_PER_YUAN = 100L

/** 数据源地址最大展示长度。 */
private const val MAX_SOURCE_URL_LENGTH = 72

/** 过长数据源地址保留的前缀长度。 */
private const val SOURCE_URL_PREFIX_LENGTH = 48

/** 过长数据源地址保留的后缀长度。 */
private const val SOURCE_URL_SUFFIX_LENGTH = 16

/** 证据哈希展示的前缀长度。 */
private const val EVIDENCE_HASH_PREVIEW_LENGTH = 12

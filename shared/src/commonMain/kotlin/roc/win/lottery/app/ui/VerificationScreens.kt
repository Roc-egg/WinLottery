package roc.win.lottery.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import roc.win.lottery.app.AppScreen
import roc.win.lottery.app.PeriodVerification
import roc.win.lottery.app.theme.LotteryThemeTokens
import roc.win.lottery.domain.BetLinePrizeResult
import roc.win.lottery.domain.ConfirmedTicket
import roc.win.lottery.domain.DrawResult
import roc.win.lottery.domain.DrawStatus
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.PrizeCheckResult
import roc.win.lottery.domain.PrizeCheckStatus
import roc.win.lottery.domain.PrizeTierCodes
import roc.win.lottery.domain.SourceEvidence
import roc.win.lottery.domain.isCompound
import kotlin.time.Instant

/**
 * 展示真实官网开奖与本地中奖测算结果。
 *
 * @param ticket 用户逐项确认的票据。
 * @param drawResult 经双数据面核对的开奖结果。
 * @param prizeCheckResult 本地规则引擎输出的逐注结果。
 * @param isFromLocalStore 开奖结果是否直接取自本机保存。
 * @param refreshNotice 本次联网未取得新证据、改为展示本机结果时的说明。
 * @param onRetry 忽略本机结果，重新联网核对同一期开奖。
 * @param onBack 返回票面确认页。
 * @param onDone 清除当前流程并返回首页。
 */
@Composable
fun VerificationResultScreen(
    ticket: ConfirmedTicket,
    drawResult: DrawResult,
    prizeCheckResult: PrizeCheckResult,
    isFromLocalStore: Boolean = false,
    refreshNotice: String? = null,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    AppShell(
        title = "中奖测算结果",
        navigationIcon = LotteryIcons.Back,
        onNavigate = onBack,
    ) {
        val isConclusive =
            prizeCheckResult.status == PrizeCheckStatus.WIN || prizeCheckResult.status == PrizeCheckStatus.NO_WIN
        ResultHero(
            tone =
                when {
                    prizeCheckResult.status == PrizeCheckStatus.WIN -> ResultTone.WIN
                    isConclusive -> ResultTone.NO_WIN
                    else -> ResultTone.PENDING
                },
            headline = prizeCheckResult.headline(),
            amountFen = prizeCheckResult.estimatedPrizeFen.takeIf { prizeCheckResult.status == PrizeCheckStatus.WIN },
            detail = prizeCheckResult.detail(),
            caption =
                "${drawResult.lotteryType.displayName()} · 第 ${drawResult.issue.value} 期 · ${drawResult.drawDate}",
        )
        if (isFromLocalStore || refreshNotice != null) {
            Spacer(Modifier.height(12.dp))
            Notice(
                message = refreshNotice ?: drawResult.localSourceText(),
                icon = LotteryIcons.Storage,
            )
        }
        Spacer(Modifier.height(SectionSpacing))
        SectionHeader(title = "开奖号码")
        Spacer(Modifier.height(SectionTitleSpacing))
        LotteryCard(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            DrawNumbers(drawResult)
            HairlineDivider()
            CompactInfoRows(
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
        }
        if (prizeCheckResult.lineResults.isNotEmpty()) {
            Spacer(Modifier.height(SectionSpacing))
            SectionHeader(title = "逐注核对", subtitle = "${prizeCheckResult.lineResults.size} 组投注")
            Spacer(Modifier.height(SectionTitleSpacing))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                prizeCheckResult.lineResults.forEach { lineResult ->
                    BetLineResult(
                        ticket = ticket,
                        drawResult = drawResult,
                        result = lineResult,
                    )
                }
            }
        }
        Spacer(Modifier.height(SectionSpacing))
        SectionHeader(title = "官方数据证据")
        Spacer(Modifier.height(SectionTitleSpacing))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            (listOf(drawResult.evidence) + drawResult.supportingEvidence).forEach { evidence ->
                EvidenceItem(evidence)
            }
        }
        Spacer(Modifier.height(16.dp))
        FootnoteText(
            "本结果只核对开奖号码和奖级，不验证彩票真伪、撤单、作废或兑奖状态；最终以发行机构公告和实体彩票兑奖为准。",
        )
        Spacer(Modifier.height(SectionSpacing))
        SecondaryActionButton(text = "重新联网核对本期", onClick = onRetry, icon = LotteryIcons.Refresh)
        Spacer(Modifier.height(10.dp))
        PrimaryActionButton(text = "返回首页", onClick = onDone)
    }
}

/**
 * 展示开奖证据不足的可重试状态，且不形成中奖结论。
 *
 * @param ticket 用户逐项确认的票据。
 * @param status 本次官网查询的不可用状态。
 * @param message 仓库返回的恢复说明。
 * @param wasQueried 本次是否实际向官网发起查询。
 * @param onRetry 主动联网查询同一期开奖。
 * @param onBack 返回票面确认页。
 * @param onDone 清除当前流程并返回首页。
 */
@Composable
fun DrawUnavailableScreen(
    ticket: ConfirmedTicket,
    status: DrawStatus,
    message: String,
    wasQueried: Boolean = true,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    AppShell(
        title = "开奖核对",
        navigationIcon = LotteryIcons.Back,
        onNavigate = onBack,
    ) {
        ResultHero(
            tone = ResultTone.PENDING,
            headline = status.unavailableTitle(),
            amountFen = null,
            detail = "当前没有取得足够的官方证据，未形成中奖或未中奖结论。",
            caption = "${ticket.lotteryType.value.displayName()} · 第 ${ticket.issue.value.value} 期",
        )
        Spacer(Modifier.height(20.dp))
        KeyValueCard(
            rows =
                listOf(
                    "彩种" to ticket.lotteryType.value.displayName(),
                    "期号" to ticket.issue.value.value,
                    "查询状态" to status.displayName(),
                    "官网查询" to if (wasQueried) "本次已发起" else "本次未发起",
                ),
        )
        Spacer(Modifier.height(16.dp))
        Notice(message = status.recoveryHint(), title = message)
        Spacer(Modifier.height(SectionSpacing))
        PrimaryActionButton(text = "重新查询", onClick = onRetry, icon = LotteryIcons.Refresh)
        Spacer(Modifier.height(10.dp))
        SecondaryActionButton(text = "返回首页", onClick = onDone)
    }
}

/**
 * 展示一张多期票的逐期开奖状态和保守整票汇总。
 *
 * @param result 控制器按期号顺序保存的多期结果。
 * @param onRetry 保留已完成期次，只联网核对证据不足或奖金尚未完整的期次。
 * @param onForceRefresh 忽略本机结果，全部期次重新联网核对。
 * @param onBack 返回票面确认页。
 * @param onDone 清除当前流程并返回首页。
 */
@Composable
fun MultiPeriodVerificationScreen(
    result: AppScreen.MultiPeriodVerificationResult,
    onRetry: () -> Unit,
    onForceRefresh: () -> Unit,
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
            Notice(
                title = "本次重查未完成",
                message = "${notice.status.displayName()}：${notice.message}。已保留此前确认的开奖号码和测算结果。",
                tone = NoticeTone.WARNING,
            )
        }
        Spacer(Modifier.height(20.dp))
        KeyValueCard(
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
        Spacer(Modifier.height(SectionSpacing))
        SectionHeader(title = "逐期开奖结果")
        Spacer(Modifier.height(SectionTitleSpacing))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            result.periodResults.forEach { periodResult ->
                PeriodVerificationItem(result.ticket, periodResult)
            }
        }
        Spacer(Modifier.height(16.dp))
        FootnoteText(
            "任一期尚未发布或暂未取得官方开奖号码时，应用不会把整张多期票解释为未中奖。最终以发行机构公告和实体彩票兑奖为准。",
        )
        Spacer(Modifier.height(SectionSpacing))
        if (result.unresolvedPeriodCount > 0) {
            PrimaryActionButton(text = "重查未完成期次", onClick = onRetry, icon = LotteryIcons.Refresh)
            Spacer(Modifier.height(10.dp))
            SecondaryActionButton(text = "重新联网核对全部期次", onClick = onForceRefresh)
        } else {
            PrimaryActionButton(text = "重新联网核对全部期次", onClick = onForceRefresh, icon = LotteryIcons.Refresh)
        }
        Spacer(Modifier.height(10.dp))
        SecondaryActionButton(text = "返回首页", onClick = onDone)
    }
}

/** 结论主卡的语气。 */
private enum class ResultTone {
    /** 有中奖记录。 */
    WIN,

    /** 已完成比对且未中奖。 */
    NO_WIN,

    /** 证据不足或尚未形成结论。 */
    PENDING,
}

/**
 * 结果页顶部的结论主卡：中奖时使用品牌渐变和大号奖金，未中奖为白色卡片，未形成结论为提醒色。
 *
 * @param tone 结论语气。
 * @param headline 主结论。
 * @param amountFen 已确认的税前奖金；没有时展示 [detail]。
 * @param detail 结论说明。
 * @param caption 彩种、期号等上下文。
 */
@Composable
private fun ResultHero(
    tone: ResultTone,
    headline: String,
    amountFen: Long?,
    detail: String,
    caption: String,
) {
    val extended = LotteryThemeTokens.colors
    val scheme = MaterialTheme.colorScheme
    val isWin = tone == ResultTone.WIN
    val titleColor = if (isWin) Color.White else scheme.onSurface
    val bodyColor = if (isWin) Color.White.copy(alpha = 0.86f) else scheme.onSurfaceVariant
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.extraLarge)
                .then(
                    when (tone) {
                        ResultTone.WIN -> {
                            Modifier
                                .background(Brush.linearGradient(listOf(extended.heroStart, extended.heroEnd)))
                                .drawBehind {
                                    val unit = size.minDimension
                                    drawCircle(
                                        Color.White.copy(alpha = 0.08f),
                                        radius = unit * 0.55f,
                                        center = Offset(size.width, 0f),
                                    )
                                    drawCircle(
                                        Color.White.copy(alpha = 0.10f),
                                        radius = unit * 0.14f,
                                        center = Offset(size.width * 0.78f, unit * 0.42f),
                                    )
                                }
                        }

                        ResultTone.NO_WIN -> {
                            Modifier
                                .background(scheme.surface)
                                .border(1.dp, extended.hairline, MaterialTheme.shapes.extraLarge)
                        }

                        ResultTone.PENDING -> {
                            Modifier.background(extended.warningContainer)
                        }
                    },
                ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (tone) {
                ResultTone.WIN -> {
                    IconTile(
                        icon = LotteryIcons.Trophy,
                        tint = Color.White,
                        container = Color.White.copy(alpha = 0.18f),
                    )
                }

                ResultTone.NO_WIN -> {
                    IconTile(
                        icon = LotteryIcons.Success,
                        tint = extended.success,
                        container = extended.successContainer,
                    )
                }

                ResultTone.PENDING -> {
                    IconTile(
                        icon = LotteryIcons.Warning,
                        tint = extended.warning,
                        container = scheme.surface.copy(alpha = 0.7f),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                caption,
                style = MaterialTheme.typography.labelMedium,
                color = if (tone == ResultTone.PENDING) extended.onWarningContainer.copy(alpha = 0.8f) else bodyColor,
            )
            Text(
                headline,
                style = MaterialTheme.typography.headlineMedium,
                color = if (tone == ResultTone.PENDING) extended.onWarningContainer else titleColor,
            )
            if (amountFen != null) {
                Spacer(Modifier.height(6.dp))
                Text("税前奖金合计", style = MaterialTheme.typography.labelMedium, color = bodyColor)
                Text(
                    amountFen.toPrizeYuanText(),
                    style = MaterialTheme.typography.displaySmall,
                    color = titleColor,
                )
            } else {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (tone == ResultTone.PENDING) extended.onWarningContainer else bodyColor,
                )
            }
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
            result.verifiedPeriodCount == result.periodResults.size -> "部分期次无法完成比对"
            else -> "已完成 ${result.verifiedPeriodCount}/${result.periodResults.size} 期核对"
        }
    val detail =
        when {
            !result.isConclusive -> "整票结论尚未形成，请继续核对未完成期次"
            estimatedPrizeFen != null -> "整票税前奖金合计 ${estimatedPrizeFen.toPrizeYuanText()}"
            result.hasWinningPeriod -> "已确认命中奖级，部分奖金仍待官方数据完整"
            else -> "所有期次均已使用最终开奖号码完成逐注核对"
        }
    ResultHero(
        tone =
            when {
                result.isConclusive && result.hasWinningPeriod -> ResultTone.WIN
                result.isConclusive -> ResultTone.NO_WIN
                else -> ResultTone.PENDING
            },
        headline = headline,
        amountFen = estimatedPrizeFen.takeIf { result.isConclusive && result.hasWinningPeriod },
        detail = detail,
        caption =
            "${result.ticket.lotteryType.value.displayName()} · 起始第 ${result.ticket.issue.value.value} 期 · " +
                "共 ${result.ticket.periodCount.value} 期",
    )
}

/** 展示一个期次的号码、测算摘要或不可用原因；已核对期次可展开查看每注命中号码。 */
@Composable
private fun PeriodVerificationItem(
    ticket: ConfirmedTicket,
    result: PeriodVerification,
) {
    LotteryCard(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "第 ${result.issue.value} 期",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            when (result) {
                is PeriodVerification.Verified -> {
                    StatusTag(
                        result.prizeCheckResult.headline(),
                        tone =
                            when (result.prizeCheckResult.status) {
                                PrizeCheckStatus.WIN -> TagTone.BRAND
                                PrizeCheckStatus.NO_WIN -> TagTone.NEUTRAL
                                else -> TagTone.WARNING
                            },
                    )
                }

                is PeriodVerification.Unavailable -> {
                    StatusTag(result.status.displayName(), tone = TagTone.WARNING)
                }
            }
        }
        when (result) {
            is PeriodVerification.Verified -> {
                NumberBallLine(
                    primaryNumbers = result.drawResult.primaryNumbers,
                    secondaryNumbers = result.drawResult.secondaryNumbers,
                    maxBallSize = 32.dp,
                    minBallSize = 24.dp,
                )
                Text(
                    result.prizeCheckResult.detail(),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val lineResults = result.prizeCheckResult.lineResults
                val ticketHasCompound = lineResults.any { it.isCompound }
                val winningLines = lineResults.filter { it.prizeTierCode != null }
                if (winningLines.isNotEmpty()) {
                    Text(
                        winningLines.joinToString(separator = "；") { line ->
                            val label = betLineLabel(line.lineIndex, ticketHasCompound)
                            if (line.isCompound) {
                                "$label ${line.tierWins.tierWinsText { result.drawResult.prizeTierName(it) }}"
                            } else {
                                "$label ${result.drawResult.prizeTierName(checkNotNull(line.prizeTierCode))}"
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    "${result.drawResult.drawDate} · ${result.drawResult.status.displayName()} · " +
                        "${result.drawResult.supportingEvidence.size + 1} 份官方证据",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (result.isFromLocalStore) {
                    Text(
                        result.drawResult.localSourceText(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                if (lineResults.isNotEmpty()) {
                    HairlineDivider()
                    ExpandableSection(
                        collapsedLabel = "查看每注命中号码",
                        expandedLabel = "收起每注命中号码",
                        stateKey = result.issue.value,
                    ) {
                        PeriodHitDetails(ticket, result.drawResult, lineResults)
                    }
                }
            }

            is PeriodVerification.Unavailable -> {
                Text(result.message, style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (result.wasQueried) "本期已发起官网查询" else "本期尚未发起官网查询",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 展示当期主号码和次号码；快乐8 的 20 个开奖号码按宽度换行。 */
@Composable
private fun DrawNumbers(drawResult: DrawResult) {
    if (drawResult.lotteryType == LotteryType.HAPPY_8) {
        NumberBallLine(
            primaryNumbers = drawResult.primaryNumbers,
            secondaryNumbers = emptyList(),
            maxBallSize = 36.dp,
            minBallSize = 34.dp,
        )
        return
    }
    val primaryName = if (drawResult.lotteryType == LotteryType.SUPER_LOTTO) "前区" else "红球"
    val secondaryName = if (drawResult.lotteryType == LotteryType.SUPER_LOTTO) "后区" else "蓝球"
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NumberBallLine(
            primaryNumbers = drawResult.primaryNumbers,
            secondaryNumbers = drawResult.secondaryNumbers,
            maxBallSize = 42.dp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendDot(MaterialTheme.colorScheme.primary, primaryName)
            LegendDot(MaterialTheme.colorScheme.secondary, secondaryName)
        }
    }
}

/** 号码区颜色图例。 */
@Composable
private fun LegendDot(
    color: Color,
    label: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 展示一行票面投注的命中数、奖级和金额；复式行列出展开后各奖级的中奖注数。 */
@Composable
private fun BetLineResult(
    ticket: ConfirmedTicket,
    drawResult: DrawResult,
    result: BetLinePrizeResult,
) {
    val line = ticket.betLines.getOrNull(result.lineIndex)
    val lotteryType = ticket.lotteryType.value
    val ticketHasCompound = ticket.betLines.any { it.isCompound(lotteryType) }
    val isWin = result.prizeTierCode != null
    LotteryCard(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                betLineTitle(result.lineIndex, result.betCount, result.isCompound, ticketHasCompound),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            StatusTag(result.outcomeText(drawResult), tone = if (isWin) TagTone.BRAND else TagTone.NEUTRAL)
        }
        line?.let {
            Text(
                if (drawResult.lotteryType == LotteryType.HAPPY_8) {
                    AnnotatedString(it.primaryNumbers.value.toNumberText())
                } else {
                    numbersText(
                        it.primaryNumbers.value,
                        it.secondaryNumbers.value,
                        MaterialTheme.colorScheme.onSurface,
                        MaterialTheme.colorScheme.secondary,
                        MaterialTheme.colorScheme.outline,
                    )
                },
                style = MaterialTheme.typography.titleSmall.copy(letterSpacing = 0.6.sp),
            )
        }
        Text(
            result.hitSummary(drawResult.lotteryType),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (result.isCompound && result.tierWins.isNotEmpty()) {
            Text(
                "其中 ${result.tierWins.tierWinsText { drawResult.prizeTierName(it) }}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        line?.let {
            val betType = if (it.isAdditional.value) "追加投注" else "基本投注"
            Text(
                "$betType · ${ticket.multiplier.value} 倍",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isWin) {
            val scope = if (result.isCompound) "本组" else "本注"
            Text(
                result.estimatedPrizeFen?.let { "${scope}税前奖金 ${it.toPrizeYuanText()}" } ?: "${scope}奖金待官方确认",
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        line?.let {
            HairlineDivider(Modifier.padding(top = 4.dp))
            ExpandableSection(
                collapsedLabel = "查看命中号码",
                expandedLabel = "收起命中号码",
                stateKey = result.lineIndex,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BetHitNumbers(lotteryType, it.primaryNumbers.value, it.secondaryNumbers.value, drawResult)
                    HitNumbersLegend()
                }
            }
        }
    }
}

/** 把一注号码拼成主号码常规色、加号淡色、次号码蓝色的文本。 */
private fun numbersText(
    primary: List<Int>,
    secondary: List<Int>,
    primaryColor: Color,
    secondaryColor: Color,
    separatorColor: Color,
): AnnotatedString =
    buildAnnotatedString {
        withStyle(SpanStyle(color = primaryColor)) { append(primary.toNumberText()) }
        withStyle(SpanStyle(color = separatorColor)) { append(" + ") }
        withStyle(SpanStyle(color = secondaryColor)) { append(secondary.toNumberText()) }
    }

/** 逐注列出一个期次中每注号码与开奖号码的命中情况和本注结果。 */
@Composable
private fun PeriodHitDetails(
    ticket: ConfirmedTicket,
    drawResult: DrawResult,
    lineResults: List<BetLinePrizeResult>,
) {
    val lotteryType = ticket.lotteryType.value
    val ticketHasCompound = ticket.betLines.any { it.isCompound(lotteryType) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        lineResults.forEach { lineResult ->
            val line = ticket.betLines.getOrNull(lineResult.lineIndex) ?: return@forEach
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        betLineTitle(
                            lineResult.lineIndex,
                            lineResult.betCount,
                            lineResult.isCompound,
                            ticketHasCompound,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        lineResult.outcomeText(drawResult),
                        style = MaterialTheme.typography.labelLarge,
                        color =
                            if (lineResult.prizeTierCode != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
                BetHitNumbers(lotteryType, line.primaryNumbers.value, line.secondaryNumbers.value, drawResult)
            }
        }
        HitNumbersLegend()
    }
}

/** 卡片内的紧凑键值行，不再包裹外层卡片。 */
@Composable
private fun CompactInfoRows(rows: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { (label, value) ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(
                    label,
                    modifier = Modifier.weight(0.4f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    value,
                    modifier = Modifier.weight(0.6f),
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

/** 展示一份官方数据源及其规范化证据摘要。 */
@Composable
private fun EvidenceItem(evidence: SourceEvidence) {
    LotteryCard {
        Row(verticalAlignment = Alignment.Top) {
            IconTile(
                icon = LotteryIcons.Evidence,
                tint = MaterialTheme.colorScheme.secondary,
                container = MaterialTheme.colorScheme.secondaryContainer,
                size = 40.dp,
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(evidence.sourceName, style = MaterialTheme.typography.titleSmall)
                Text(
                    evidence.sourceUrl.compactUrl(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    "获取时间：${evidence.fetchedAtEpochMillis.toEvidenceTime()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "证据校验：${evidence.contentSha256.take(EVIDENCE_HASH_PREVIEW_LENGTH)}...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 返回测算状态对应的主结论。 */
private fun PrizeCheckResult.headline(): String =
    when (status) {
        PrizeCheckStatus.WIN -> "有中奖记录"
        PrizeCheckStatus.NO_WIN -> "本期未中奖"
        PrizeCheckStatus.RULE_UNSUPPORTED -> "当前规则暂不支持"
        PrizeCheckStatus.NOT_CALCULATED -> "无法完成比对"
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
        PrizeCheckStatus.NOT_CALCULATED,
        -> {
            message ?: "当前无法用官方开奖号码完成比对"
        }
    }

/** 返回一行的中奖结论：单式为奖级名称，复式为中奖注数，未中奖时为“未中奖”。 */
private fun BetLinePrizeResult.outcomeText(drawResult: DrawResult): String {
    val tierCode = prizeTierCode ?: return "未中奖"
    return if (isCompound) "中奖 ${tierWins.sumOf { it.winningBetCount }} 注" else drawResult.prizeTierName(tierCode)
}

/** 本行是否为复式；复式至少展开为两注，单式恰好一注。 */
private val BetLinePrizeResult.isCompound: Boolean
    get() = betCount > 1L

/** 返回奖级名称，优先使用官方数据中的名称。 */
private fun DrawResult.prizeTierName(code: String): String =
    prizeTiers.firstOrNull { it.code == code }?.displayName ?: code.displayPrizeTier()

/** 返回逐注命中数的玩法化文案。 */
private fun BetLinePrizeResult.hitSummary(lotteryType: LotteryType): String =
    when (lotteryType) {
        LotteryType.SUPER_LOTTO -> "前区命中 $primaryHitCount 个，后区命中 $secondaryHitCount 个"
        LotteryType.DOUBLE_COLOR_BALL -> "红球命中 $primaryHitCount 个，蓝球命中 $secondaryHitCount 个"
        LotteryType.HAPPY_8 -> "命中 $primaryHitCount 个"
    }

/** 返回彩票玩法中文名称。 */
private fun LotteryType.displayName(): String =
    when (this) {
        LotteryType.SUPER_LOTTO -> "超级大乐透"
        LotteryType.DOUBLE_COLOR_BALL -> "双色球"
        LotteryType.HAPPY_8 -> "快乐8"
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
        DrawStatus.CONFLICT -> "官方数据不一致"
    }

/** 返回不可用状态页的标题。 */
private fun DrawStatus.unavailableTitle(): String =
    when (this) {
        DrawStatus.NOT_PUBLISHED -> "该期开奖结果尚未发布"

        DrawStatus.PUBLISHING -> "开奖结果正在发布核对"

        DrawStatus.NETWORK_UNAVAILABLE -> "当前无法连接网络"

        DrawStatus.SOURCE_UNAVAILABLE -> "官方数据暂时不可用"

        DrawStatus.CONFLICT -> "官方开奖号码不一致"

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

        DrawStatus.CONFLICT -> "两个官方数据面给出的开奖号码不同，请稍后重新查询；若持续出现，请以发行机构公告为准。"

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
        else -> PrizeTierCodes.happy8DisplayName(this) ?: this
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

/** 说明开奖结果取自本机保存，并给出最近一次联网核对的北京时间。 */
private fun DrawResult.localSourceText(): String {
    val time =
        Instant
            .fromEpochMilliseconds(
                evidence.fetchedAtEpochMillis,
            ).toLocalDateTime(TimeZone.of("Asia/Shanghai"))
    val text =
        "${(time.month.ordinal + 1).toString().padStart(2, '0')}-${time.day.toString().padStart(2, '0')} " +
            "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
    return "使用本机保存的开奖 · 核对于 $text"
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

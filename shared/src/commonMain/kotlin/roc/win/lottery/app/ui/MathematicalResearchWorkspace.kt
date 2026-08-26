package roc.win.lottery.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import roc.win.lottery.app.TrendChartContent
import roc.win.lottery.app.TrendChartState
import roc.win.lottery.app.TrendResearchContent
import roc.win.lottery.domain.GeneratedNumberLine
import roc.win.lottery.domain.LotteryPredictionAnalysis
import roc.win.lottery.domain.LotteryPredictionAreaBacktest
import roc.win.lottery.domain.LotteryTrendArea
import roc.win.lottery.domain.LotteryType
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 展示固定策略候选与严格时间前推回测。
 *
 * @param chart 当前走势会话配置与数学研究状态。
 * @param isLandscape 当前是否为横屏视口。
 * @param onLotteryTypeSelected 切换研究彩种。
 * @param onRetry 用户明确重新加载当前彩种历史开奖。
 */
@Composable
internal fun MathematicalResearchWorkspace(
    chart: TrendChartState,
    isLandscape: Boolean,
    onLotteryTypeSelected: (LotteryType) -> Unit,
    onRetry: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ResearchLotteryControl(
            lotteryType = chart.lotteryType,
            onLotteryTypeSelected = onLotteryTypeSelected,
        )
        when (val content = chart.researchContent) {
            TrendResearchContent.Loading -> {
                ResearchLoadStatus(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    isLandscape = isLandscape,
                )
            }

            is TrendResearchContent.Failed -> {
                ResearchFailureStatus(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    message = content.message,
                    onRetry = onRetry,
                )
            }

            is TrendResearchContent.Ready -> {
                val sourceName =
                    (chart.content as? TrendChartContent.Ready)?.sourceName ?: "官方历史开奖"
                ResearchReadyContent(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    analysis = content.analysis,
                    sourceName = sourceName,
                    isLandscape = isLandscape,
                )
            }
        }
    }
}

/** 使用与基本走势相同的分段控件选择研究彩种。 */
@Composable
private fun ResearchLotteryControl(
    lotteryType: LotteryType,
    onLotteryTypeSelected: (LotteryType) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "彩种",
            modifier = Modifier.width(44.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LotteryTypeTrendSelector(
            modifier = Modifier.weight(1f),
            selected = lotteryType,
            onSelected = onLotteryTypeSelected,
        )
    }
}

/** 展示正在读取真实历史开奖并执行共享领域计算的状态。 */
@Composable
private fun ResearchLoadStatus(
    modifier: Modifier,
    isLandscape: Boolean,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(if (isLandscape) 28.dp else 40.dp))
            Spacer(Modifier.height(12.dp))
            Text("正在计算数学研究", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "读取当前会话 500 期并执行时间前推回测",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 展示数学研究失败关闭状态与显式重试入口。 */
@Composable
private fun ResearchFailureStatus(
    modifier: Modifier,
    message: String,
    onRetry: () -> Unit,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "数学研究暂时不可用",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            Button(onClick = onRetry, shape = MaterialTheme.shapes.small) { Text("重试") }
        }
    }
}

/** 展示策略元数据、候选号码、前推指标和固定责任说明。 */
@Composable
private fun ResearchReadyContent(
    modifier: Modifier,
    analysis: LotteryPredictionAnalysis,
    sourceName: String,
    isLandscape: Boolean,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(key = "research-status") {
            ResearchProtocolStatus(analysis = analysis, sourceName = sourceName)
            Spacer(Modifier.height(if (isLandscape) 10.dp else 16.dp))
        }
        item(key = "research-results") {
            if (isLandscape) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    ResearchCandidateSection(
                        modifier = Modifier.weight(0.9f),
                        analysis = analysis,
                        isLandscape = true,
                    )
                    ResearchBacktestSection(
                        modifier = Modifier.weight(1.1f),
                        analysis = analysis,
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ResearchCandidateSection(
                        modifier = Modifier.fillMaxWidth(),
                        analysis = analysis,
                        isLandscape = false,
                    )
                    Spacer(Modifier.height(18.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(18.dp))
                    ResearchBacktestSection(
                        modifier = Modifier.fillMaxWidth(),
                        analysis = analysis,
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            ResearchResponsibilityNotice()
        }
    }
}

/** 展示策略名称、版本、固定参数和当前会话官方来源。 */
@Composable
private fun ResearchProtocolStatus(
    analysis: LotteryPredictionAnalysis,
    sourceName: String,
) {
    val candidate = analysis.candidate
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                "${candidate.strategyName} · v${candidate.strategyVersion}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "固定窗口 ${candidate.trainingWindowSize} 期 · " +
                    "${candidate.trainingFirstIssue.value} 至 ${candidate.trainingLastIssue.value}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "策略标识：${candidate.strategyId}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "官方历史开奖 · $sourceName · 仅当前会话内计算",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 展示由最新 50 期生成的一注下一期候选。 */
@Composable
private fun ResearchCandidateSection(
    modifier: Modifier,
    analysis: LotteryPredictionAnalysis,
    isLandscape: Boolean,
) {
    val candidate = analysis.candidate
    val line = candidate.line
    Column(
        modifier =
            modifier.semantics {
                contentDescription = candidateDescription(candidate.lotteryType, line)
            },
    ) {
        Text("下一期候选", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "按出现次数少、当前遗漏大、号码小的固定顺序选取",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        ResearchNumberArea(
            label = LotteryTrendArea.PRIMARY.displayName(candidate.lotteryType),
            numbers = line.primaryNumbers,
            lotteryType = candidate.lotteryType,
            area = LotteryTrendArea.PRIMARY,
            ballSize = if (isLandscape) 38.dp else 44.dp,
        )
        Spacer(Modifier.height(12.dp))
        ResearchNumberArea(
            label = LotteryTrendArea.SECONDARY.displayName(candidate.lotteryType),
            numbers = line.secondaryNumbers,
            lotteryType = candidate.lotteryType,
            area = LotteryTrendArea.SECONDARY,
            ballSize = if (isLandscape) 38.dp else 44.dp,
        )
    }
}

/** 展示候选号码的一个规则区域。 */
@Composable
private fun ResearchNumberArea(
    label: String,
    numbers: List<Int>,
    lotteryType: LotteryType,
    area: LotteryTrendArea,
    ballSize: Dp,
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            numbers.forEach { number ->
                ResearchNumberBall(
                    number = number,
                    size = ballSize,
                    background = trendHitColor(lotteryType, area),
                    foreground = trendHitContentColor(lotteryType, area),
                )
            }
        }
    }
}

/** 绘制固定尺寸且不受辅助字号挤压的两位候选号码球。 */
@Composable
private fun ResearchNumberBall(
    number: Int,
    size: Dp,
    background: Color,
    foreground: Color,
) {
    Surface(
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = background,
        contentColor = foreground,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                number.toString().padStart(2, '0'),
                style = researchBallTextStyle(),
                maxLines = 1,
            )
        }
    }
}

/** 返回候选号码球使用的稳定物理字号。 */
@Composable
private fun researchBallTextStyle(): TextStyle {
    val fontScale = LocalDensity.current.fontScale
    return MaterialTheme.typography.labelLarge.copy(
        fontSize = 13.sp / fontScale,
        lineHeight = 15.sp / fontScale,
        letterSpacing = 0.sp,
        fontWeight = FontWeight.Bold,
    )
}

/** 展示回测范围、无泄漏口径和两个号码区域的基线对比。 */
@Composable
private fun ResearchBacktestSection(
    modifier: Modifier,
    analysis: LotteryPredictionAnalysis,
) {
    val backtest = analysis.backtest
    Column(modifier = modifier) {
        Text("时间前推回测", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "${backtest.targetCount} 个样本外目标期 · " +
                "${backtest.firstTargetIssue.value} 至 ${backtest.lastTargetIssue.value}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "每个目标期只使用此前 ${backtest.trainingWindowSize} 期",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "均匀随机理论基线",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        ResearchBacktestAreaRow(
            lotteryType = analysis.candidate.lotteryType,
            result = backtest.primary,
        )
        Spacer(Modifier.height(8.dp))
        ResearchBacktestAreaRow(
            lotteryType = analysis.candidate.lotteryType,
            result = backtest.secondary,
        )
    }
}

/** 展示一个号码区域的策略均值、随机理论值和客观差值。 */
@Composable
private fun ResearchBacktestAreaRow(
    lotteryType: LotteryType,
    result: LotteryPredictionAreaBacktest,
) {
    val areaName = result.area.displayName(lotteryType)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f))
                .padding(horizontal = 10.dp, vertical = 9.dp)
                .semantics {
                    contentDescription =
                        "$areaName，策略平均命中 ${result.averageMatches.toResearchDecimal()}，" +
                        "随机理论 ${result.uniformRandomExpectedAverageMatches.toResearchDecimal()}，" +
                        "差值 ${result.differenceFromUniformRandom.toSignedResearchDecimal()}"
                },
    ) {
        Text(areaName, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ResearchMetric(
                modifier = Modifier.weight(1f),
                label = "策略均值",
                value = result.averageMatches.toResearchDecimal(),
            )
            ResearchMetric(
                modifier = Modifier.weight(1f),
                label = "随机理论",
                value = result.uniformRandomExpectedAverageMatches.toResearchDecimal(),
            )
            ResearchMetric(
                modifier = Modifier.weight(1f),
                label = "差值",
                value = result.differenceFromUniformRandom.toSignedResearchDecimal(),
            )
        }
    }
}

/** 展示一项紧凑回测指标。 */
@Composable
private fun ResearchMetric(
    modifier: Modifier,
    label: String,
    value: String,
) {
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

/** 持续展示样本结论和不可用于购彩决策的责任边界。 */
@Composable
private fun ResearchResponsibilityNotice() {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription =
                        "当前前推样本尚未证明稳定优于均匀随机。" +
                        "历史排序不会改变下一期各号码概率，仅供娱乐和研究，不构成购彩建议。"
                },
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                "当前前推样本尚未证明稳定优于均匀随机",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "历史排序不会改变下一期各号码概率，仅供娱乐和研究，不构成购彩建议。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 返回候选号码的完整无障碍摘要。 */
private fun candidateDescription(
    lotteryType: LotteryType,
    line: GeneratedNumberLine,
): String =
    "${lotteryType.researchDisplayName()}下一期候选，" +
        "${LotteryTrendArea.PRIMARY.displayName(lotteryType)}" +
        line.primaryNumbers.joinToString("，") { number -> number.toString().padStart(2, '0') } +
        "，${LotteryTrendArea.SECONDARY.displayName(lotteryType)}" +
        line.secondaryNumbers.joinToString("，") { number -> number.toString().padStart(2, '0') }

/** 返回数学研究页使用的彩种中文名称。 */
private fun LotteryType.researchDisplayName(): String =
    when (this) {
        LotteryType.SUPER_LOTTO -> "大乐透"
        LotteryType.DOUBLE_COLOR_BALL -> "双色球"
    }

/** 将回测指标格式化为三位小数，避免依赖平台专属格式化器。 */
private fun Double.toResearchDecimal(): String {
    val scaled = (this * RESEARCH_DECIMAL_SCALE).roundToInt()
    return scaled.toResearchDecimalText(includePositiveSign = false)
}

/** 将回测差值格式化为带正负号的三位小数。 */
private fun Double.toSignedResearchDecimal(): String {
    val scaled = (this * RESEARCH_DECIMAL_SCALE).roundToInt()
    return scaled.toResearchDecimalText(includePositiveSign = true)
}

/** 把已经缩放的整数转成跨平台稳定小数文本。 */
private fun Int.toResearchDecimalText(includePositiveSign: Boolean): String {
    val sign =
        when {
            this < 0 -> "-"
            includePositiveSign -> "+"
            else -> ""
        }
    val absolute = abs(this)
    val whole = absolute / RESEARCH_DECIMAL_SCALE
    val fraction = (absolute % RESEARCH_DECIMAL_SCALE).toString().padStart(3, '0')
    return "$sign$whole.$fraction"
}

/** 三位小数使用的整数缩放倍数。 */
private const val RESEARCH_DECIMAL_SCALE = 1_000

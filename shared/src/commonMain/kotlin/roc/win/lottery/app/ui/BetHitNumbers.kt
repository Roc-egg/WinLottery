package roc.win.lottery.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import roc.win.lottery.domain.DrawResult
import roc.win.lottery.domain.LotteryType

/**
 * 返回一注号码与开奖号码逐个比对的说明，供读屏使用。
 *
 * 例如“前区 03 09 14 18 22，命中 09 14；后区 02 06，未命中”。
 */
internal fun betHitDescription(
    lotteryType: LotteryType,
    primaryNumbers: List<Int>,
    secondaryNumbers: List<Int>,
    drawPrimaryNumbers: List<Int>,
    drawSecondaryNumbers: List<Int>,
): String {
    fun area(
        name: String,
        numbers: List<Int>,
        drawNumbers: List<Int>,
    ): String {
        val sorted = numbers.sorted()
        val hits = sorted.filter { it in drawNumbers }
        val hitText = if (hits.isEmpty()) "未命中" else "命中 ${hits.toBallText()}"
        return "$name ${sorted.toBallText()}，$hitText"
    }
    val (primaryName, secondaryName) =
        when (lotteryType) {
            LotteryType.SUPER_LOTTO -> "前区" to "后区"
            LotteryType.DOUBLE_COLOR_BALL -> "红球" to "蓝球"
        }
    return area(primaryName, primaryNumbers, drawPrimaryNumbers) + "；" +
        area(secondaryName, secondaryNumbers, drawSecondaryNumbers)
}

/**
 * 用号码球展示一注号码：与开奖号码相同的号码实心高亮，其余空心。
 *
 * 单式按可用宽度缩小球径排在一行；复式号码较多、最小球径也排不下一行时，前区或红球自动换行，
 * 后区或蓝球以加号开头另起一行，两区分界保持清楚。
 *
 * @param lotteryType 彩票玩法。
 * @param primaryNumbers 本注前区或红球号码。
 * @param secondaryNumbers 本注后区或蓝球号码。
 * @param drawResult 用于比对的开奖结果。
 */
@Composable
internal fun BetHitNumbers(
    lotteryType: LotteryType,
    primaryNumbers: List<Int>,
    secondaryNumbers: List<Int>,
    drawResult: DrawResult,
) {
    val description =
        betHitDescription(
            lotteryType,
            primaryNumbers,
            secondaryNumbers,
            drawResult.primaryNumbers,
            drawResult.secondaryNumbers,
        )
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description }) {
        // 加号占一个号码位。
        val slotCount = primaryNumbers.size + secondaryNumbers.size + 1
        val fittedSize = (maxWidth - HIT_BALL_SPACING * (slotCount - 1)) / slotCount
        val primaryBalls: @Composable (Dp) -> Unit = { size ->
            primaryNumbers.sorted().forEach { number ->
                HitBall(
                    number = number,
                    isHit = number in drawResult.primaryNumbers,
                    size = size,
                    hitColor = MaterialTheme.colorScheme.primary,
                    onHitColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        val secondaryBalls: @Composable (Dp) -> Unit = { size ->
            Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
                Text(
                    "+",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            secondaryNumbers.sorted().forEach { number ->
                HitBall(
                    number = number,
                    isHit = number in drawResult.secondaryNumbers,
                    size = size,
                    hitColor = MaterialTheme.colorScheme.secondary,
                    onHitColor = MaterialTheme.colorScheme.onSecondary,
                )
            }
        }
        if (fittedSize >= HIT_BALL_MIN_SIZE) {
            val size = fittedSize.coerceAtMost(HIT_BALL_MAX_SIZE)
            Row(horizontalArrangement = Arrangement.spacedBy(HIT_BALL_SPACING)) {
                primaryBalls(size)
                secondaryBalls(size)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(HIT_BALL_SPACING)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(HIT_BALL_SPACING),
                    verticalArrangement = Arrangement.spacedBy(HIT_BALL_SPACING),
                ) {
                    primaryBalls(HIT_BALL_MAX_SIZE)
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(HIT_BALL_SPACING),
                    verticalArrangement = Arrangement.spacedBy(HIT_BALL_SPACING),
                ) {
                    secondaryBalls(HIT_BALL_MAX_SIZE)
                }
            }
        }
    }
}

/** 说明号码球实心、空心含义的一行图例。 */
@Composable
internal fun HitNumbersLegend() {
    Text(
        "实心为命中开奖号码，空心为未命中",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 默认收起的可展开区块：整行开关可点，箭头随展开旋转，读屏报告展开状态。
 *
 * @param collapsedLabel 收起时开关上的文字。
 * @param expandedLabel 展开时开关上的文字。
 * @param stateKey 区块所属条目的稳定标识；条目变化时重新回到收起状态。
 * @param content 展开后显示的内容。
 */
@Composable
internal fun ExpandableSection(
    collapsedLabel: String,
    expandedLabel: String,
    stateKey: Any,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(stateKey) { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(if (expanded) EXPANDED_ARROW_ROTATION else 0f)
    Column {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = EXPAND_TOGGLE_MIN_HEIGHT)
                    .clip(MaterialTheme.shapes.small)
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .semantics { stateDescription = if (expanded) "已展开" else "已收起" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (expanded) expandedLabel else collapsedLabel,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                LotteryIcons.Expand,
                contentDescription = null,
                modifier = Modifier.size(EXPAND_ARROW_SIZE).rotate(arrowRotation),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(modifier = Modifier.padding(top = 4.dp)) { content() }
        }
    }
}

/** 一个命中或未命中的只读号码球。 */
@Composable
private fun HitBall(
    number: Int,
    isHit: Boolean,
    size: Dp,
    hitColor: Color,
    onHitColor: Color,
) {
    Surface(
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = if (isHit) hitColor else MaterialTheme.colorScheme.surface,
        contentColor = if (isHit) onHitColor else MaterialTheme.colorScheme.onSurfaceVariant,
        border = if (isHit) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                number.toString().padStart(2, '0'),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isHit) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

/** 把号码列表转换为空格分隔的两位文本。 */
private fun List<Int>.toBallText(): String = joinToString(separator = " ") { it.toString().padStart(2, '0') }

/** 命中号码球最大直径。 */
private val HIT_BALL_MAX_SIZE = 32.dp

/** 单行排列时号码球的最小直径，再小就改为换行排列。 */
private val HIT_BALL_MIN_SIZE = 26.dp

/** 命中号码球间距。 */
private val HIT_BALL_SPACING = 6.dp

/** 展开开关的最小触控高度。 */
private val EXPAND_TOGGLE_MIN_HEIGHT = 44.dp

/** 展开箭头图标尺寸。 */
private val EXPAND_ARROW_SIZE = 20.dp

/** 展开后箭头旋转角度。 */
private const val EXPANDED_ARROW_ROTATION = 180f

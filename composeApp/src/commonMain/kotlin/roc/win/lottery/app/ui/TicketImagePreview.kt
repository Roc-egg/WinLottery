package roc.win.lottery.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import roc.win.lottery.recognition.ImageRef
import roc.win.lottery.recognition.NormalizedBounds
import roc.win.lottery.recognition.TicketFieldReference
import roc.win.lottery.recognition.TicketFieldRegion
import kotlin.math.abs
import kotlin.math.roundToInt

/** 票图预览的异步加载状态。 */
internal sealed interface TicketPreviewState {
    /** 正在读取当前流程的私有临时图片。 */
    data object Loading : TicketPreviewState

    /**
     * 已取得可显示的预览位图。
     *
     * @property bitmap 受最长边限制的内存位图。
     */
    data class Ready(
        val bitmap: ImageBitmap,
    ) : TicketPreviewState

    /** 临时图片已失效或平台解码失败。 */
    data object Unavailable : TicketPreviewState
}

/**
 * 记住当前图片的异步预览状态。
 *
 * @param imageRef 当前流程图片引用。
 */
@Composable
internal fun rememberTicketPreviewState(imageRef: ImageRef): TicketPreviewState =
    produceState<TicketPreviewState>(TicketPreviewState.Loading, imageRef.id) {
        value =
            decodeTicketPreview(imageRef, MAX_PREVIEW_EDGE_PIXELS)
                ?.let(TicketPreviewState::Ready)
                ?: TicketPreviewState.Unavailable
    }.value

/**
 * 显示整张票图，并允许在已有 OCR 字段区域之间切换。
 *
 * @param state 当前预览加载状态。
 * @param fieldRegions 可供定位的字段区域。
 */
@Composable
internal fun TicketImagePreview(
    state: TicketPreviewState,
    fieldRegions: List<TicketFieldRegion>,
) {
    val regions = previewableTicketRegions(fieldRegions)
    var selectedField by remember(regions) { mutableStateOf<TicketFieldReference?>(null) }
    val selectedRegion = regions.firstOrNull { it.field == selectedField }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("票面原图", style = MaterialTheme.typography.titleLarge)
        Surface(
            modifier = Modifier.fillMaxWidth().height(PREVIEW_HEIGHT),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            when (state) {
                TicketPreviewState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                is TicketPreviewState.Ready -> {
                    TicketPreviewCanvas(
                        bitmap = state.bitmap,
                        selectedRegion = selectedRegion,
                        highlightColor = MaterialTheme.colorScheme.primary,
                    )
                }

                TicketPreviewState.Unavailable -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(LotteryIcons.Image, contentDescription = null)
                        Spacer(Modifier.height(10.dp))
                        Text("原图预览不可用，请返回后重新导入")
                    }
                }
            }
        }
        if (state is TicketPreviewState.Ready && regions.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = selectedField == null,
                    onClick = { selectedField = null },
                    label = { Text("整票") },
                )
                regions.forEach { region ->
                    FilterChip(
                        selected = selectedField == region.field,
                        onClick = { selectedField = region.field },
                        label = { Text(region.field.displayName()) },
                    )
                }
            }
        }
    }
}

/**
 * 返回适合用户定位的去重字段区域。
 *
 * 部分 OCR 引擎会给标题返回覆盖大半票面的行框；彩种原始置信度仍保留在流程状态中，
 * 但这种框不能作为定位入口展示，以免让用户误以为整张票都是标题区域。
 */
internal fun previewableTicketRegions(fieldRegions: List<TicketFieldRegion>): List<TicketFieldRegion> =
    fieldRegions
        .filter { region ->
            region.field != TicketFieldReference.LotteryType ||
                abs(region.bounds.bottom - region.bounds.top) <= MAX_LOTTERY_TYPE_REGION_HEIGHT
        }.distinctBy { it.field }

/** 绘制整票或选中字段的带上下文裁切区域。 */
@Composable
private fun TicketPreviewCanvas(
    bitmap: ImageBitmap,
    selectedRegion: TicketFieldRegion?,
    highlightColor: Color,
) {
    val description = selectedRegion?.field?.displayName()?.let { "票面原图，当前定位：$it" } ?: "票面原图"
    Canvas(
        modifier =
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = description },
    ) {
        val viewport =
            calculateTicketPreviewViewport(
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
                selectedBounds = selectedRegion?.bounds,
            )
        drawTicketBitmap(
            bitmap = bitmap,
            viewport = viewport,
            selectedBounds = selectedRegion?.bounds,
            highlightColor = highlightColor,
        )
    }
}

/**
 * 预览使用的源图片像素区域。
 *
 * @property left 左侧像素坐标。
 * @property top 顶部像素坐标。
 * @property right 右侧像素坐标，不包含该像素。
 * @property bottom 底部像素坐标，不包含该像素。
 */
internal data class TicketPreviewViewport(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    /** 源区域宽度。 */
    val width: Int
        get() = right - left

    /** 源区域高度。 */
    val height: Int
        get() = bottom - top
}

/**
 * 把归一化 OCR 区域扩展为包含上下文、且不会越出图片的像素视口。
 *
 * @param imageWidth 图片像素宽度。
 * @param imageHeight 图片像素高度。
 * @param selectedBounds 当前字段边界；为空时返回整图。
 */
internal fun calculateTicketPreviewViewport(
    imageWidth: Int,
    imageHeight: Int,
    selectedBounds: NormalizedBounds?,
): TicketPreviewViewport {
    require(imageWidth > 0 && imageHeight > 0) { "预览图片尺寸必须为正数" }
    if (selectedBounds == null) return TicketPreviewViewport(0, 0, imageWidth, imageHeight)

    val normalizedLeft = minOf(selectedBounds.left, selectedBounds.right).coerceIn(0f, 1f)
    val normalizedRight = maxOf(selectedBounds.left, selectedBounds.right).coerceIn(0f, 1f)
    val normalizedTop = minOf(selectedBounds.top, selectedBounds.bottom).coerceIn(0f, 1f)
    val normalizedBottom = maxOf(selectedBounds.top, selectedBounds.bottom).coerceIn(0f, 1f)
    val horizontalPadding = maxOf((normalizedRight - normalizedLeft) * 0.2f, MIN_HORIZONTAL_PADDING)
    val verticalPadding = maxOf((normalizedBottom - normalizedTop) * 1.5f, MIN_VERTICAL_PADDING)
    val left = ((normalizedLeft - horizontalPadding).coerceAtLeast(0f) * imageWidth).toInt()
    val top = ((normalizedTop - verticalPadding).coerceAtLeast(0f) * imageHeight).toInt()
    val right =
        ((normalizedRight + horizontalPadding).coerceAtMost(1f) * imageWidth)
            .roundToInt()
            .coerceAtLeast(left + 1)
            .coerceAtMost(imageWidth)
    val bottom =
        ((normalizedBottom + verticalPadding).coerceAtMost(1f) * imageHeight)
            .roundToInt()
            .coerceAtLeast(top + 1)
            .coerceAtMost(imageHeight)
    return TicketPreviewViewport(left, top, right, bottom)
}

/** 把源像素区域按比例绘制到画布中央，并标出选中字段。 */
private fun DrawScope.drawTicketBitmap(
    bitmap: ImageBitmap,
    viewport: TicketPreviewViewport,
    selectedBounds: NormalizedBounds?,
    highlightColor: Color,
) {
    val scale = minOf(size.width / viewport.width, size.height / viewport.height)
    val destinationWidth = (viewport.width * scale).roundToInt().coerceAtLeast(1)
    val destinationHeight = (viewport.height * scale).roundToInt().coerceAtLeast(1)
    val destinationLeft = ((size.width - destinationWidth) / 2f).roundToInt()
    val destinationTop = ((size.height - destinationHeight) / 2f).roundToInt()
    drawImage(
        image = bitmap,
        srcOffset = IntOffset(viewport.left, viewport.top),
        srcSize = IntSize(viewport.width, viewport.height),
        dstOffset = IntOffset(destinationLeft, destinationTop),
        dstSize = IntSize(destinationWidth, destinationHeight),
        filterQuality = FilterQuality.High,
    )
    selectedBounds?.let { bounds ->
        val sourceRect = bounds.toPixelRect(bitmap.width, bitmap.height)
        val highlightLeft = destinationLeft + (sourceRect.left - viewport.left) * scale
        val highlightTop = destinationTop + (sourceRect.top - viewport.top) * scale
        val highlightWidth = sourceRect.width * scale
        val highlightHeight = sourceRect.height * scale
        drawRect(
            color = highlightColor.copy(alpha = HIGHLIGHT_FILL_ALPHA),
            topLeft = Offset(highlightLeft, highlightTop),
            size = Size(highlightWidth, highlightHeight),
        )
        drawRect(
            color = highlightColor,
            topLeft = Offset(highlightLeft, highlightTop),
            size = Size(highlightWidth, highlightHeight),
            style = Stroke(width = HIGHLIGHT_STROKE_WIDTH.toPx()),
        )
    }
}

/** 把归一化坐标转换为图片像素矩形。 */
private fun NormalizedBounds.toPixelRect(
    imageWidth: Int,
    imageHeight: Int,
): Rect =
    Rect(
        left = minOf(left, right).coerceIn(0f, 1f) * imageWidth,
        top = minOf(top, bottom).coerceIn(0f, 1f) * imageHeight,
        right = maxOf(left, right).coerceIn(0f, 1f) * imageWidth,
        bottom = maxOf(top, bottom).coerceIn(0f, 1f) * imageHeight,
    )

/** 返回字段定位控件使用的简短名称。 */
private fun TicketFieldReference.displayName(): String =
    when (this) {
        TicketFieldReference.LotteryType -> "彩种"
        TicketFieldReference.Issue -> "期号"
        is TicketFieldReference.BetLine -> "第 ${index + 1} 注"
        TicketFieldReference.Multiplier -> "倍数"
        TicketFieldReference.Additional -> "追加"
        TicketFieldReference.PeriodCount -> "期数"
        TicketFieldReference.PaidAmount -> "金额"
    }

/** 票图预览的稳定尺寸和裁切参数。 */
private val PREVIEW_HEIGHT = 300.dp

/** 预览解码后的最长边，避免校正页持有完整 OCR 尺寸位图。 */
private const val MAX_PREVIEW_EDGE_PIXELS = 1600

/** 字段裁切至少保留的横向上下文。 */
private const val MIN_HORIZONTAL_PADDING = 0.04f

/** 字段裁切至少保留的纵向上下文。 */
private const val MIN_VERTICAL_PADDING = 0.025f

/** 彩种标题框允许展示为单行定位入口的最大归一化高度。 */
private const val MAX_LOTTERY_TYPE_REGION_HEIGHT = 0.25f

/** 字段高亮填充透明度。 */
private const val HIGHLIGHT_FILL_ALPHA = 0.16f

/** 字段高亮描边宽度。 */
private val HIGHLIGHT_STROKE_WIDTH = 2.dp

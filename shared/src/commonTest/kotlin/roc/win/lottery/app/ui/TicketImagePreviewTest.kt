package roc.win.lottery.app.ui

import roc.win.lottery.recognition.NormalizedBounds
import roc.win.lottery.recognition.TicketFieldReference
import roc.win.lottery.recognition.TicketFieldRegion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 票图字段裁切视口测试。 */
class TicketImagePreviewTest {
    /** 未选择字段时应使用整张预览图。 */
    @Test
    fun fullImageIsUsedWithoutSelectedBounds() {
        val viewport = calculateTicketPreviewViewport(1000, 2000, null)

        assertEquals(TicketPreviewViewport(0, 0, 1000, 2000), viewport)
    }

    /** 字段区域应带上下文扩展后转换为稳定像素视口。 */
    @Test
    fun selectedBoundsAreExpandedWithContext() {
        val viewport =
            calculateTicketPreviewViewport(
                imageWidth = 100,
                imageHeight = 100,
                selectedBounds = NormalizedBounds(0.25f, 0.25f, 0.50f, 0.3125f),
            )

        assertEquals(TicketPreviewViewport(20, 15, 55, 41), viewport)
    }

    /** 靠近图片边缘的字段扩展后不得越出有效像素范围。 */
    @Test
    fun expandedBoundsAreClampedToImage() {
        val viewport =
            calculateTicketPreviewViewport(
                imageWidth = 1000,
                imageHeight = 1000,
                selectedBounds = NormalizedBounds(0.01f, 0.01f, 0.20f, 0.04f),
            )

        assertEquals(0, viewport.left)
        assertEquals(0, viewport.top)
        assertEquals(240, viewport.right)
        assertEquals(85, viewport.bottom)
    }

    /** 非法图片尺寸必须立即拒绝，不能产生零尺寸绘制区域。 */
    @Test
    fun nonPositiveImageSizeIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            calculateTicketPreviewViewport(0, 100, null)
        }
    }

    /** 覆盖大半票面的彩种框应隐藏，但紧凑标题框和其他字段仍可定位。 */
    @Test
    fun oversizedLotteryTypeRegionIsNotOfferedForPreview() {
        val oversizedLotteryType =
            TicketFieldRegion(
                field = TicketFieldReference.LotteryType,
                bounds = NormalizedBounds(0.10f, 0.05f, 0.90f, 0.80f),
                rawConfidence = 0.8f,
            )
        val compactLotteryType =
            TicketFieldRegion(
                field = TicketFieldReference.LotteryType,
                bounds = NormalizedBounds(0.20f, 0.08f, 0.70f, 0.18f),
                rawConfidence = 0.7f,
            )
        val issue =
            TicketFieldRegion(
                field = TicketFieldReference.Issue,
                bounds = NormalizedBounds(0.20f, 0.20f, 0.60f, 0.26f),
                rawConfidence = 0.9f,
            )

        assertEquals(
            listOf(compactLotteryType, issue),
            previewableTicketRegions(listOf(oversizedLotteryType, compactLotteryType, issue)),
        )
    }
}

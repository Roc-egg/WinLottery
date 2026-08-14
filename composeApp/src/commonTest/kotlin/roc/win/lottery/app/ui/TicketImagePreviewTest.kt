package roc.win.lottery.app.ui

import roc.win.lottery.recognition.NormalizedBounds
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
}

package roc.win.lottery.data

import roc.win.lottery.domain.DrawStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 开奖查询封闭状态测试。 */
class DrawQueryResultTest {
    /** 未开奖状态应被保留为不可用，不能等价于未中奖。 */
    @Test
    fun notPublishedIsUnavailable() {
        val result = DrawQueryResult.Unavailable(DrawStatus.NOT_PUBLISHED, "暂未发布")

        assertEquals(DrawStatus.NOT_PUBLISHED, result.status)
    }

    /** 已确认号码必须通过成功分支携带统一领域模型。 */
    @Test
    fun finalStatusCannotUseUnavailableBranch() {
        assertFailsWith<IllegalArgumentException> {
            DrawQueryResult.Unavailable(DrawStatus.FINAL_NUMBERS, "错误分支")
        }
    }
}

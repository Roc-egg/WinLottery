package roc.win.lottery.app.ui

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

/** 关于页 V1 支持范围文案契约测试。 */
class AboutScreenScopeTextTest {
    /** 受控多期已进入 V1，页面不得继续把全部多期描述为不支持。 */
    @Test
    fun controlledMultiPeriodScopeMatchesCurrentV1Policy() {
        assertContains(V1_SUPPORTED_TICKET_SCOPE_TEXT, "1 至 20 期受控连续投注")
        assertContains(V1_SUPPORTED_TICKET_SCOPE_TEXT, "补打票")
        assertContains(V1_UNSUPPORTED_TICKET_SCOPE_TEXT, "超过 20 期")
        assertContains(V1_UNSUPPORTED_TICKET_SCOPE_TEXT, "跨年度未知期次")
        assertFalse(V1_UNSUPPORTED_TICKET_SCOPE_TEXT.contains("补打票"))
        assertFalse(V1_UNSUPPORTED_TICKET_SCOPE_TEXT.contains("不支持复式、胆拖、多期"))
    }
}

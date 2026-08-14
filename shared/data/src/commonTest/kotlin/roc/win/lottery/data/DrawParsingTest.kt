package roc.win.lottery.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 金额、日期、号码和规范化摘要的纯函数测试。 */
class DrawParsingTest {
    /** 金额必须精确按分换算，并支持逗号与两位以内小数。 */
    @Test
    fun moneyIsParsedExactlyInFen() {
        assertEquals(1_000_000_000L, parseYuanToFen("10,000,000"))
        assertEquals(12_345L, parseYuanToFen("123.45"))
        assertEquals(1_230L, parseYuanToFen("12.3"))
        assertEquals(0L, parseYuanToFen("0"))
    }

    /** 占位符、中文说明、负数和过高精度不能被静默转换成零。 */
    @Test
    fun unknownMoneyRemainsNull() {
        assertNull(parseYuanToFen("---"))
        assertNull(parseYuanToFen("-1"))
        assertNull(parseYuanToFen("奖金待定"))
        assertNull(parseYuanToFen("1.234"))
        assertNull(parseYuanToFen("-5"))
    }

    /** 金额乘以一百会溢出时必须保留为未知。 */
    @Test
    fun overflowingMoneyRemainsNull() {
        assertNull(parseYuanToFen(Long.MAX_VALUE.toString()))
    }

    /** 日期时间只取合法 ISO 日期前缀。 */
    @Test
    fun drawDateIsNormalizedStrictly() {
        assertEquals("2026-08-12", normalizeDrawDate("2026-08-12 21:18:19"))
        assertEquals("2026-08-12", normalizeDrawDate("2026-08-12(日)"))
        assertNull(normalizeDrawDate("2026-02-30"))
        assertNull(normalizeDrawDate("08/12/2026"))
    }

    /** 号码区域必须同时满足数量、范围和区域内唯一性。 */
    @Test
    fun numberAreaIsValidatedStrictly() {
        assertEquals(listOf(1, 2, 3), parseNumberArea("01, 02 03", 3, 1..5))
        assertNull(parseNumberArea("01 02", 3, 1..5))
        assertNull(parseNumberArea("01 01 03", 3, 1..5))
        assertNull(parseNumberArea("01 02 06", 3, 1..5))
    }

    /** SHA-256 必须稳定为 64 位小写十六进制，并随规范化内容变化。 */
    @Test
    fun sha256IsStableAndSensitiveToContent() {
        val first = sha256Hex("规范化内容")
        val repeated = sha256Hex("规范化内容")
        val changed = sha256Hex("规范化内容2")

        assertEquals(first, repeated)
        assertNotEquals(first, changed)
        assertEquals(64, first.length)
        assertTrue(first.all { it.isDigit() || it in 'a'..'f' })
    }
}

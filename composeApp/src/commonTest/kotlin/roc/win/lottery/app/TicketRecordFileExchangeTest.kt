package roc.win.lottery.app

import okio.Buffer
import roc.win.lottery.persistence.MAX_TICKET_RECORD_IMPORT_BYTES
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 逻辑票据包平台文件边界测试。 */
class TicketRecordFileExchangeTest {
    /** 文件名必须包含非空基础名称并严格使用固定扩展名。 */
    @Test
    fun packageFileNameRequiresExactExtensionAndSafeBaseName() {
        assertTrue(isTicketRecordPackageFileName("backup.wltickets.json"))
        assertFalse(isTicketRecordPackageFileName(".wltickets.json"))
        assertFalse(isTicketRecordPackageFileName("backup.WLTICKETS.JSON"))
        assertFalse(isTicketRecordPackageFileName("folder/backup.wltickets.json"))
        assertFalse(isTicketRecordPackageFileName("folder\\backup.wltickets.json"))
    }

    /** 不超过 5 MiB 的文件必须完整读取，不得改变任何字节。 */
    @Test
    fun importReaderAcceptsExactSizeLimit() {
        val content = ByteArray(MAX_TICKET_RECORD_IMPORT_BYTES) { index -> (index % 251).toByte() }

        val actual = Buffer().write(content).readTicketRecordImportContent()

        assertContentEquals(content, actual)
    }

    /** 超过上限时只多读一个探测字节，并立即拒绝文件。 */
    @Test
    fun importReaderRejectsOversizedContentAfterOneProbeByte() {
        val source = Buffer().write(ByteArray(MAX_TICKET_RECORD_IMPORT_BYTES + 128))

        val actual = source.readTicketRecordImportContent()

        assertNull(actual)
        assertEquals(127L, source.size)
    }
}

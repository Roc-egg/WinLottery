package roc.win.lottery.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.IOException

/** 使用 Windows 或 macOS 系统文件选择器即时导入或导出逻辑票据包。 */
class DesktopTicketRecordFileExchange(
    /** 返回系统文件选择器使用的当前桌面窗口。 */
    private val ownerProvider: () -> Frame?,
) : TicketRecordFileExchange {
    /** 防止导入和导出系统对话框重叠。 */
    private val operationMutex = Mutex()

    /** 选择固定扩展名文件，并在后台线程按共享上限即时读取。 */
    override suspend fun readImportPackage(): TicketRecordFileReadResult {
        if (!operationMutex.tryLock()) {
            return TicketRecordFileReadResult.Failure("已有票据文件操作正在进行")
        }
        return try {
            when (val selection = selectImportFile()) {
                DesktopFileSelectionResult.Cancelled -> TicketRecordFileReadResult.Cancelled
                is DesktopFileSelectionResult.Failure -> TicketRecordFileReadResult.Failure(selection.message)
                is DesktopFileSelectionResult.Selected -> readSelectedImport(selection.file)
            }
        } finally {
            operationMutex.unlock()
        }
    }

    /** 验证建议文件名，选择保存位置并在后台线程完整覆盖写入。 */
    override suspend fun writeExportPackage(
        suggestedFileName: String,
        content: ByteArray,
    ): TicketRecordFileWriteResult {
        if (!isTicketRecordPackageFileName(suggestedFileName)) {
            return TicketRecordFileWriteResult.Failure("导出文件名不符合逻辑票据包格式")
        }
        if (!operationMutex.tryLock()) {
            return TicketRecordFileWriteResult.Failure("已有票据文件操作正在进行")
        }
        return try {
            when (val selection = selectExportLocation(suggestedFileName)) {
                DesktopFileSelectionResult.Cancelled -> TicketRecordFileWriteResult.Cancelled
                is DesktopFileSelectionResult.Failure -> TicketRecordFileWriteResult.Failure(selection.message)
                is DesktopFileSelectionResult.Selected -> writeSelectedExport(selection.file, content)
            }
        } finally {
            operationMutex.unlock()
        }
    }

    /** 在 Swing 主线程展示只允许单选逻辑票据包的系统打开对话框。 */
    private suspend fun selectImportFile(): DesktopFileSelectionResult =
        withContext(Dispatchers.Main) {
            showFileDialog(
                title = IMPORT_DIALOG_TITLE,
                mode = FileDialog.LOAD,
                suggestedFileName = null,
                requirePackageExtension = true,
            )
        }

    /** 在 Swing 主线程展示带建议文件名的系统保存对话框。 */
    private suspend fun selectExportLocation(suggestedFileName: String): DesktopFileSelectionResult =
        withContext(Dispatchers.Main) {
            showFileDialog(
                title = EXPORT_DIALOG_TITLE,
                mode = FileDialog.SAVE,
                suggestedFileName = suggestedFileName,
                requirePackageExtension = false,
            )
        }

    /** 创建模态系统文件对话框，并把用户选择归一为本地文件。 */
    private fun showFileDialog(
        title: String,
        mode: Int,
        suggestedFileName: String?,
        requirePackageExtension: Boolean,
    ): DesktopFileSelectionResult {
        val dialog = FileDialog(ownerProvider(), title, mode)
        return try {
            dialog.isMultipleMode = false
            if (requirePackageExtension) {
                dialog.filenameFilter = java.io.FilenameFilter { _, name -> isTicketRecordPackageFileName(name) }
            }
            suggestedFileName?.let { dialog.file = it }
            dialog.isVisible = true
            val directory = dialog.directory ?: return DesktopFileSelectionResult.Cancelled
            val fileName = dialog.file ?: return DesktopFileSelectionResult.Cancelled
            DesktopFileSelectionResult.Selected(File(directory, fileName))
        } catch (_: RuntimeException) {
            DesktopFileSelectionResult.Failure("无法打开系统文件选择器")
        } finally {
            dialog.dispose()
        }
    }

    /** 校验文件名并最多读取共享上限加一个探测字节。 */
    private suspend fun readSelectedImport(file: File): TicketRecordFileReadResult =
        withContext(Dispatchers.IO) {
            if (!file.isFile || !isTicketRecordPackageFileName(file.name)) {
                return@withContext TicketRecordFileReadResult.Failure("请选择 .wltickets.json 逻辑票据包")
            }
            try {
                val content = file.source().buffer().use { source -> source.readTicketRecordImportContent() }
                if (content == null) {
                    TicketRecordFileReadResult.Failure("逻辑票据包超过 5 MiB 上限")
                } else {
                    TicketRecordFileReadResult.Success(content)
                }
            } catch (_: SecurityException) {
                TicketRecordFileReadResult.Failure("没有读取所选逻辑票据包的权限")
            } catch (_: IOException) {
                TicketRecordFileReadResult.Failure("读取逻辑票据包时发生错误")
            } catch (_: RuntimeException) {
                TicketRecordFileReadResult.Failure("所选逻辑票据包无法处理")
            }
        }

    /** 校验目标文件名并完整覆盖写入用户选择的位置。 */
    private suspend fun writeSelectedExport(
        file: File,
        content: ByteArray,
    ): TicketRecordFileWriteResult =
        withContext(Dispatchers.IO) {
            if (!isTicketRecordPackageFileName(file.name)) {
                return@withContext TicketRecordFileWriteResult.Failure("导出文件必须保留 .wltickets.json 扩展名")
            }
            try {
                file.outputStream().buffered().use { output ->
                    output.write(content)
                    output.flush()
                }
                TicketRecordFileWriteResult.Success
            } catch (_: SecurityException) {
                TicketRecordFileWriteResult.Failure("没有写入所选位置的权限")
            } catch (_: IOException) {
                TicketRecordFileWriteResult.Failure("写入逻辑票据包时发生错误")
            } catch (_: RuntimeException) {
                TicketRecordFileWriteResult.Failure("所选位置无法写入逻辑票据包")
            }
        }

    /** 桌面系统文件选择器常量。 */
    private companion object {
        /** 导入逻辑票据包时显示的标题。 */
        const val IMPORT_DIALOG_TITLE = "导入彩票记录"

        /** 导出逻辑票据包时显示的标题。 */
        const val EXPORT_DIALOG_TITLE = "导出彩票记录"
    }
}

/** 桌面系统文件选择器的内部结果。 */
private sealed interface DesktopFileSelectionResult {
    /** 用户主动取消系统选择器。 */
    data object Cancelled : DesktopFileSelectionResult

    /** 系统选择器返回了一个即时可访问的本地文件。 */
    data class Selected(
        /** 本次操作使用的短期本地文件引用。 */
        val file: File,
    ) : DesktopFileSelectionResult

    /** 系统选择器无法启动。 */
    data class Failure(
        /** 不包含文件路径或票面内容的安全说明。 */
        val message: String,
    ) : DesktopFileSelectionResult
}

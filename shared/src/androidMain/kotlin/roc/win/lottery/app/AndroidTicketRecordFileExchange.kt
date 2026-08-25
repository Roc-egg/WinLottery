package roc.win.lottery.app

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source
import java.io.IOException
import kotlin.coroutines.resume

/** 使用 Android 系统文件选择器即时导入或导出逻辑票据包。 */
class AndroidTicketRecordFileExchange(
    /** 注册 Activity Result 并访问所选内容的宿主 Activity。 */
    private val activity: ComponentActivity,
) : TicketRecordFileExchange {
    /** 等待系统导入文件选择结果的单个请求。 */
    private var pendingImportSelection: CancellableContinuation<AndroidFileSelectionResult>? = null

    /** 等待系统导出位置选择结果的单个请求。 */
    private var pendingExportSelection: CancellableContinuation<AndroidFileSelectionResult>? = null

    /** 必须在 Activity 进入启动状态前完成注册的系统导入文件选择器。 */
    private val importLauncher =
        activity.registerForActivityResult(OpenDocument()) { uri ->
            resumeImportSelection(
                uri?.let(AndroidFileSelectionResult::Selected) ?: AndroidFileSelectionResult.Cancelled,
            )
        }

    /** 必须在 Activity 进入启动状态前完成注册的系统导出位置选择器。 */
    private val exportLauncher =
        activity.registerForActivityResult(CreateDocument(JSON_MIME_TYPE)) { uri ->
            resumeExportSelection(
                uri?.let(AndroidFileSelectionResult::Selected) ?: AndroidFileSelectionResult.Cancelled,
            )
        }

    /** 选择一个固定扩展名文件，并在后台线程按共享上限即时读取。 */
    override suspend fun readImportPackage(): TicketRecordFileReadResult =
        when (val selection = selectImportFile()) {
            AndroidFileSelectionResult.Cancelled -> {
                TicketRecordFileReadResult.Cancelled
            }

            is AndroidFileSelectionResult.Failure -> {
                TicketRecordFileReadResult.Failure(selection.message)
            }

            is AndroidFileSelectionResult.Selected -> {
                readSelectedImport(selection.uri)
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
        return when (val selection = selectExportLocation(suggestedFileName)) {
            AndroidFileSelectionResult.Cancelled -> {
                TicketRecordFileWriteResult.Cancelled
            }

            is AndroidFileSelectionResult.Failure -> {
                TicketRecordFileWriteResult.Failure(selection.message)
            }

            is AndroidFileSelectionResult.Selected -> {
                writeSelectedExport(selection.uri, content)
            }
        }
    }

    /** 在主线程启动系统导入选择器，并把回调转换为可取消挂起调用。 */
    private suspend fun selectImportFile(): AndroidFileSelectionResult =
        withContext(Dispatchers.Main.immediate) {
            if (pendingImportSelection != null || pendingExportSelection != null) {
                return@withContext AndroidFileSelectionResult.Failure("已有票据文件操作正在进行")
            }
            suspendCancellableCoroutine<AndroidFileSelectionResult> { continuation ->
                pendingImportSelection = continuation
                continuation.invokeOnCancellation {
                    if (pendingImportSelection === continuation) pendingImportSelection = null
                }
                runCatching {
                    importLauncher.launch(IMPORT_MIME_TYPES)
                }.onFailure {
                    pendingImportSelection = null
                    if (continuation.isActive) {
                        continuation.resume(AndroidFileSelectionResult.Failure("无法打开系统文件选择器"))
                    }
                }
            }
        }

    /** 在主线程启动系统保存选择器，并把回调转换为可取消挂起调用。 */
    private suspend fun selectExportLocation(suggestedFileName: String): AndroidFileSelectionResult =
        withContext(Dispatchers.Main.immediate) {
            if (pendingImportSelection != null || pendingExportSelection != null) {
                return@withContext AndroidFileSelectionResult.Failure("已有票据文件操作正在进行")
            }
            suspendCancellableCoroutine<AndroidFileSelectionResult> { continuation ->
                pendingExportSelection = continuation
                continuation.invokeOnCancellation {
                    if (pendingExportSelection === continuation) pendingExportSelection = null
                }
                runCatching {
                    exportLauncher.launch(suggestedFileName)
                }.onFailure {
                    pendingExportSelection = null
                    if (continuation.isActive) {
                        continuation.resume(AndroidFileSelectionResult.Failure("无法打开系统保存选择器"))
                    }
                }
            }
        }

    /** 校验系统显示名称并最多读取共享上限加一个探测字节。 */
    private suspend fun readSelectedImport(uri: Uri): TicketRecordFileReadResult =
        withContext(Dispatchers.IO) {
            try {
                val fileName = queryDisplayName(uri)
                if (fileName == null || !isTicketRecordPackageFileName(fileName)) {
                    return@withContext TicketRecordFileReadResult.Failure("请选择 .wltickets.json 逻辑票据包")
                }
                val input =
                    activity.contentResolver.openInputStream(uri)
                        ?: return@withContext TicketRecordFileReadResult.Failure("无法读取所选逻辑票据包")
                val content = input.source().buffer().use { source -> source.readTicketRecordImportContent() }
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

    /** 在用户选择的位置完整覆盖写入逻辑票据包。 */
    private suspend fun writeSelectedExport(
        uri: Uri,
        content: ByteArray,
    ): TicketRecordFileWriteResult =
        withContext(Dispatchers.IO) {
            try {
                val output =
                    activity.contentResolver.openOutputStream(uri, WRITE_MODE)
                        ?: return@withContext TicketRecordFileWriteResult.Failure("无法写入所选位置")
                output.use { stream ->
                    stream.write(content)
                    stream.flush()
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

    /** 查询文档提供方声明的显示文件名。 */
    private fun queryDisplayName(uri: Uri): String? =
        activity.contentResolver
            .query(uri, DISPLAY_NAME_PROJECTION, null, null, null)
            ?.use { cursor ->
                val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (columnIndex >= 0 && cursor.moveToFirst()) cursor.getString(columnIndex) else null
            }

    /** 只恢复仍有效的导入选择请求。 */
    private fun resumeImportSelection(result: AndroidFileSelectionResult) {
        val continuation = pendingImportSelection
        pendingImportSelection = null
        if (continuation?.isActive == true) continuation.resume(result)
    }

    /** 只恢复仍有效的导出选择请求。 */
    private fun resumeExportSelection(result: AndroidFileSelectionResult) {
        val continuation = pendingExportSelection
        pendingExportSelection = null
        if (continuation?.isActive == true) continuation.resume(result)
    }

    /** Android 系统文件交互常量。 */
    private companion object {
        /** JSON 文档的标准 MIME 类型。 */
        const val JSON_MIME_TYPE = "application/json"

        /** 导入时兼容文档提供方常见的 JSON 和通用二进制声明。 */
        val IMPORT_MIME_TYPES = arrayOf(JSON_MIME_TYPE, "text/json", "application/octet-stream")

        /** 只查询系统文档提供方的显示名称。 */
        val DISPLAY_NAME_PROJECTION = arrayOf(OpenableColumns.DISPLAY_NAME)

        /** 覆盖并截断用户选择目标的输出流模式。 */
        const val WRITE_MODE = "wt"
    }
}

/** Android 系统文件选择器的内部结果。 */
private sealed interface AndroidFileSelectionResult {
    /** 用户主动取消系统选择器。 */
    data object Cancelled : AndroidFileSelectionResult

    /** 系统选择器返回了一个即时可访问的内容 URI。 */
    data class Selected(
        /** 本次操作使用的短期内容 URI。 */
        val uri: Uri,
    ) : AndroidFileSelectionResult

    /** 系统选择器无法启动或已有互斥操作。 */
    data class Failure(
        /** 不包含文件路径或票面内容的安全说明。 */
        val message: String,
    ) : AndroidFileSelectionResult
}

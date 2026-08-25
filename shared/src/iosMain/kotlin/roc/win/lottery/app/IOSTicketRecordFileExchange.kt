package roc.win.lottery.app

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeJSON
import platform.darwin.NSObject
import kotlin.coroutines.resume

/** 使用 iOS 系统文档选择器即时导入或导出逻辑票据包。 */
@OptIn(ExperimentalForeignApi::class)
class IOSTicketRecordFileExchange(
    /** 返回当前可展示系统文档选择器的宿主控制器。 */
    private val presenterProvider: () -> UIViewController?,
) : TicketRecordFileExchange {
    /** 等待系统文档选择结果的单个请求。 */
    private var pendingSelection: CancellableContinuation<IOSDocumentSelectionResult>? = null

    /** 由文件接口强引用的系统文档选择器代理。 */
    private val pickerDelegate = IOSDocumentPickerDelegate(::handlePickedDocuments, ::handlePickerCancellation)

    /** 选择固定扩展名文件，并按共享上限即时读取本地副本。 */
    override suspend fun readImportPackage(): TicketRecordFileReadResult =
        when (val selection = selectImportDocument()) {
            IOSDocumentSelectionResult.Cancelled -> {
                TicketRecordFileReadResult.Cancelled
            }

            is IOSDocumentSelectionResult.Failure -> {
                TicketRecordFileReadResult.Failure(selection.message)
            }

            is IOSDocumentSelectionResult.Selected -> {
                readSelectedImport(selection.url)
            }
        }

    /** 创建短期导出源文件，交给系统选择保存位置，并无条件清理源文件。 */
    override suspend fun writeExportPackage(
        suggestedFileName: String,
        content: ByteArray,
    ): TicketRecordFileWriteResult {
        if (!isTicketRecordPackageFileName(suggestedFileName)) {
            return TicketRecordFileWriteResult.Failure("导出文件名不符合逻辑票据包格式")
        }
        val temporaryExport =
            createTemporaryExport(suggestedFileName, content)
                ?: return TicketRecordFileWriteResult.Failure("无法创建导出临时文件")
        return try {
            when (val selection = selectExportLocation(temporaryExport.fileUrl)) {
                IOSDocumentSelectionResult.Cancelled -> {
                    TicketRecordFileWriteResult.Cancelled
                }

                is IOSDocumentSelectionResult.Failure -> {
                    TicketRecordFileWriteResult.Failure(selection.message)
                }

                is IOSDocumentSelectionResult.Selected -> {
                    TicketRecordFileWriteResult.Success
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Default) {
                runCatching {
                    FileSystem.SYSTEM.deleteRecursively(temporaryExport.directoryPath, mustExist = false)
                }
            }
        }
    }

    /** 展示只允许打开 JSON 文档本地副本的系统选择器。 */
    private suspend fun selectImportDocument(): IOSDocumentSelectionResult =
        withContext(Dispatchers.Main) {
            val picker =
                UIDocumentPickerViewController(
                    forOpeningContentTypes = listOf(UTTypeJSON),
                    asCopy = true,
                )
            presentPicker(picker)
        }

    /** 展示把私有临时文件复制到用户指定位置的系统选择器。 */
    private suspend fun selectExportLocation(fileUrl: NSURL): IOSDocumentSelectionResult =
        withContext(Dispatchers.Main) {
            val picker =
                UIDocumentPickerViewController(
                    forExportingURLs = listOf(fileUrl),
                    asCopy = true,
                )
            presentPicker(picker)
        }

    /** 配置代理、展示选择器并把 Objective-C 回调转换为可取消挂起调用。 */
    private suspend fun presentPicker(picker: UIDocumentPickerViewController): IOSDocumentSelectionResult {
        if (pendingSelection != null) return IOSDocumentSelectionResult.Failure("已有票据文件操作正在进行")
        val presenter = presenterProvider() ?: return IOSDocumentSelectionResult.Failure("无法展示系统文件选择器")
        return suspendCancellableCoroutine { continuation ->
            pendingSelection = continuation
            continuation.invokeOnCancellation {
                if (pendingSelection === continuation) pendingSelection = null
            }
            picker.delegate = pickerDelegate
            presenter.presentViewController(picker, animated = true, completion = null)
        }
    }

    /** 验证文件名并在短期安全访问范围内读取所选本地副本。 */
    private suspend fun readSelectedImport(url: NSURL): TicketRecordFileReadResult {
        val fileName = url.lastPathComponent
        if (fileName == null || !isTicketRecordPackageFileName(fileName)) {
            return TicketRecordFileReadResult.Failure("请选择 .wltickets.json 逻辑票据包")
        }
        val path = url.path?.toPath() ?: return TicketRecordFileReadResult.Failure("无法读取所选逻辑票据包")
        val hasSecurityAccess = url.startAccessingSecurityScopedResource()
        return try {
            withContext(Dispatchers.Default) {
                try {
                    val content =
                        FileSystem.SYSTEM.read(path) {
                            readTicketRecordImportContent()
                        }
                    if (content == null) {
                        TicketRecordFileReadResult.Failure("逻辑票据包超过 5 MiB 上限")
                    } else {
                        TicketRecordFileReadResult.Success(content)
                    }
                } catch (_: Exception) {
                    TicketRecordFileReadResult.Failure("读取逻辑票据包时发生错误")
                }
            }
        } finally {
            if (hasSecurityAccess) url.stopAccessingSecurityScopedResource()
        }
    }

    /** 在私有临时子目录中写入保持建议名称的导出源文件。 */
    private suspend fun createTemporaryExport(
        suggestedFileName: String,
        content: ByteArray,
    ): TemporaryIOSExport? =
        withContext(Dispatchers.Default) {
            val temporaryRoot = NSTemporaryDirectory().trimEnd(PATH_SEPARATOR)
            val directoryPath =
                "$temporaryRoot/$EXPORT_DIRECTORY_NAME/${NSUUID().UUIDString}".toPath()
            val filePath = directoryPath.resolve(suggestedFileName)
            try {
                FileSystem.SYSTEM.createDirectories(directoryPath)
                FileSystem.SYSTEM.write(filePath) { write(content) }
                TemporaryIOSExport(
                    directoryPath = directoryPath,
                    fileUrl = NSURL.fileURLWithPath(filePath.toString()),
                )
            } catch (_: Exception) {
                runCatching { FileSystem.SYSTEM.deleteRecursively(directoryPath, mustExist = false) }
                null
            }
        }

    /** 接收用户选中的首个文档 URL。 */
    private fun handlePickedDocuments(
        controller: UIDocumentPickerViewController,
        urls: List<*>,
    ) {
        val selectedUrl = urls.firstOrNull() as? NSURL
        resumeSelection(
            selectedUrl?.let(IOSDocumentSelectionResult::Selected)
                ?: IOSDocumentSelectionResult.Failure("系统文件选择器没有返回可用文档"),
        )
    }

    /** 接收用户主动取消系统文档选择的回调。 */
    private fun handlePickerCancellation(controller: UIDocumentPickerViewController) {
        resumeSelection(IOSDocumentSelectionResult.Cancelled)
    }

    /** 只恢复仍有效的当前文档选择请求。 */
    private fun resumeSelection(result: IOSDocumentSelectionResult) {
        val continuation = pendingSelection
        pendingSelection = null
        if (continuation?.isActive == true) continuation.resume(result)
    }

    /** iOS 临时导出常量。 */
    private companion object {
        /** 临时目录内专用于系统导出的父目录名称。 */
        const val EXPORT_DIRECTORY_NAME = "WinLotteryTicketExports"

        /** Unix 路径分隔符。 */
        const val PATH_SEPARATOR = '/'
    }
}

/** iOS 系统文档选择器的内部结果。 */
private sealed interface IOSDocumentSelectionResult {
    /** 用户主动取消系统选择器。 */
    data object Cancelled : IOSDocumentSelectionResult

    /** 系统选择器返回了一个即时可访问的文件 URL。 */
    data class Selected(
        /** 本次操作使用的短期文件 URL。 */
        val url: NSURL,
    ) : IOSDocumentSelectionResult

    /** 系统选择器无法展示或没有返回有效文档。 */
    data class Failure(
        /** 不包含文件路径或票面内容的安全说明。 */
        val message: String,
    ) : IOSDocumentSelectionResult
}

/** 保留建议文件名的 iOS 临时导出源。 */
private data class TemporaryIOSExport(
    /** 完成或取消系统导出后需要递归删除的私有目录。 */
    val directoryPath: Path,
    /** 交给系统文档选择器复制的私有源文件 URL。 */
    val fileUrl: NSURL,
)

/** 只负责把 iOS 文档选择器代理回调转交给文件接口。 */
private class IOSDocumentPickerDelegate(
    /** 用户选择文档后的回调。 */
    private val onPicked: (UIDocumentPickerViewController, List<*>) -> Unit,
    /** 用户取消选择后的回调。 */
    private val onCancelled: (UIDocumentPickerViewController) -> Unit,
) : NSObject(),
    UIDocumentPickerDelegateProtocol {
    /** 转交导入文件或导出位置选择结果。 */
    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        onPicked(controller, didPickDocumentsAtURLs)
    }

    /** 转交用户主动取消结果。 */
    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onCancelled(controller)
    }
}

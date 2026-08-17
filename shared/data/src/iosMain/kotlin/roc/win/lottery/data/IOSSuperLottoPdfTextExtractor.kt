@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package roc.win.lottery.data

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.dataWithBytes
import platform.PDFKit.PDFAnnotation
import platform.PDFKit.PDFAnnotationSubtypeWidget
import platform.PDFKit.PDFDocument
import platform.PDFKit.PDFDocumentProducerAttribute

/** 使用 Apple PDFKit 读取固定官方大乐透公告的文本层。 */
class IOSSuperLottoPdfTextExtractor : SuperLottoPdfTextExtractor {
    /** 在后台线程提取单页结构和系统文本层，不解释彩票字段。 */
    override suspend fun extract(pdfBytes: ByteArray): SuperLottoPdfTextExtractionResult =
        withContext(Dispatchers.Default) {
            try {
                val data = pdfBytes.toNSData()
                val document = PDFDocument(data = data)
                val firstPage = document.pageAtIndex(0u) ?: return@withContext failure()
                val producer = document.documentAttributes?.get(PDFDocumentProducerAttribute) as? String
                val hasInteractiveForm =
                    firstPage.annotations.any { annotation ->
                        (annotation as? PDFAnnotation)?.type == PDFAnnotationSubtypeWidget
                    }
                SuperLottoPdfTextExtractionResult.Success(
                    SuperLottoPdfDocument(
                        text = document.string.orEmpty(),
                        pageCount = document.pageCount.toInt(),
                        producer = producer,
                        pageRotationDegrees = firstPage.rotation.toInt(),
                        isEncrypted = document.isEncrypted || document.isLocked,
                        hasInteractiveForm = hasInteractiveForm,
                    ),
                )
            } catch (_: Exception) {
                failure()
            }
        }

    /** 将非空 Kotlin 字节数组复制为只在当前解析调用中使用的 NSData。 */
    private fun ByteArray.toNSData(): NSData =
        usePinned { pinned ->
            NSData.dataWithBytes(pinned.addressOf(0), size.toULong())
        }

    /** 返回不包含 PDF 原文或平台异常细节的失败结果。 */
    private fun failure(): SuperLottoPdfTextExtractionResult.Failure =
        SuperLottoPdfTextExtractionResult.Failure("iOS 无法安全解析大乐透 PDF 公告")
}

package roc.win.lottery.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper

/** 使用 JVM PDFBox 读取固定官方大乐透公告的文本层。 */
class JvmSuperLottoPdfTextExtractor : SuperLottoPdfTextExtractor {
    /** 在后台线程提取单页结构和按位置排序的文本，不解释彩票字段。 */
    override suspend fun extract(pdfBytes: ByteArray): SuperLottoPdfTextExtractionResult =
        withContext(Dispatchers.Default) {
            try {
                Loader.loadPDF(pdfBytes).use { document ->
                    val firstPage = document.getPage(0)
                    SuperLottoPdfTextExtractionResult.Success(
                        SuperLottoPdfDocument(
                            text = PDFTextStripper().apply { sortByPosition = true }.getText(document),
                            pageCount = document.numberOfPages,
                            producer = document.documentInformation.producer,
                            pageRotationDegrees = firstPage.rotation,
                            isEncrypted = document.isEncrypted,
                            hasInteractiveForm = document.documentCatalog.acroForm != null,
                        ),
                    )
                }
            } catch (_: Exception) {
                SuperLottoPdfTextExtractionResult.Failure("JVM 无法安全解析大乐透 PDF 公告")
            }
        }
}

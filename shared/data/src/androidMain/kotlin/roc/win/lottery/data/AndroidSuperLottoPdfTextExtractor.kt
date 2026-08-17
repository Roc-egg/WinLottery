package roc.win.lottery.data

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 使用 Android PDFBox 读取固定官方大乐透公告的文本层。
 *
 * @param context 应用上下文来源，仅用于初始化 PDFBox 内置资源。
 */
class AndroidSuperLottoPdfTextExtractor(
    context: Context,
) : SuperLottoPdfTextExtractor {
    /** 只保留进程级应用上下文，供 PDFBox 加载内置资源。 */
    private val applicationContext = context.applicationContext

    init {
        PDFBoxResourceLoader.init(applicationContext)
    }

    /** 在后台线程提取单页结构和按位置排序的文本，不解释彩票字段。 */
    override suspend fun extract(pdfBytes: ByteArray): SuperLottoPdfTextExtractionResult =
        withContext(Dispatchers.Default) {
            try {
                PDDocument.load(pdfBytes).use { document ->
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
                SuperLottoPdfTextExtractionResult.Failure("Android 无法安全解析大乐透 PDF 公告")
            }
        }
}

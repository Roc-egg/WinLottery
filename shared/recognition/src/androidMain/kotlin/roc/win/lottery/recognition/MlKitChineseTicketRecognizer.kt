package roc.win.lottery.recognition

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

/** 使用设备内置模型执行 ML Kit 中文文字识别，不上传票图或文字。 */
class MlKitChineseTicketRecognizer(
    context: Context,
) : TicketRecognizer {
    /** 只用于读取应用私有临时文件的应用上下文。 */
    private val applicationContext = context.applicationContext

    /** 进程内复用的中文文字识别器。 */
    private val recognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    /** 对私有 JPEG 执行 OCR，并转换为共享坐标模型。 */
    override suspend fun recognize(imageRef: ImageRef): RecognitionResult =
        withContext(Dispatchers.Default) {
            val file = File(imageRef.localPath)
            if (!file.isFile) return@withContext RecognitionResult.Failure("临时图片不存在，请重新导入")
            try {
                val input = InputImage.fromFilePath(applicationContext, Uri.fromFile(file))
                val result = recognizer.process(input).await()
                val lines = result.toOcrLines(input.width, input.height)
                if (lines.isEmpty()) {
                    RecognitionResult.PoorImage(listOf("未识别到清晰文字，请检查裁切、对焦和光线"))
                } else {
                    RecognitionResult.Success(
                        OcrDocument(
                            imageId = imageRef.id,
                            lines = lines,
                            engineName = ENGINE_NAME,
                        ),
                    )
                }
            } catch (_: Exception) {
                RecognitionResult.Failure("本地文字识别未能完成，请重新导入图片")
            }
        }

    /** 保留 ML Kit 行级位置与置信度，并按阅读顺序排序。 */
    private fun Text.toOcrLines(
        imageWidth: Int,
        imageHeight: Int,
    ): List<OcrTextLine> =
        textBlocks
            .flatMap { block -> block.lines }
            .mapNotNull { line -> line.toOcrLineOrNull(imageWidth, imageHeight) }
            .sortedWith(compareBy<OcrTextLine> { it.bounds.top }.thenBy { it.bounds.left })

    /** 把单行像素坐标归一化到共享模型，缺少位置或空文本时丢弃。 */
    private fun Text.Line.toOcrLineOrNull(
        imageWidth: Int,
        imageHeight: Int,
    ): OcrTextLine? {
        val box = boundingBox ?: return null
        val content = text.trim()
        if (content.isEmpty() || imageWidth <= 0 || imageHeight <= 0) return null
        return OcrTextLine(
            text = content,
            bounds =
                NormalizedBounds(
                    left = normalizeCoordinate(box.left, imageWidth),
                    top = normalizeCoordinate(box.top, imageHeight),
                    right = normalizeCoordinate(box.right, imageWidth),
                    bottom = normalizeCoordinate(box.bottom, imageHeight),
                ),
            confidence = confidence.takeIf { it in 0f..1f },
        )
    }

    /** 把单个像素坐标限制并归一化为 `0.0..1.0`。 */
    private fun normalizeCoordinate(
        coordinate: Int,
        dimension: Int,
    ): Float = (coordinate.toFloat() / dimension.toFloat()).coerceIn(0f, 1f)

    /** ML Kit OCR 实现信息。 */
    private companion object {
        /** 用于 PoC 结果追踪的引擎与依赖版本摘要。 */
        const val ENGINE_NAME = "ML Kit 中文文字识别 16.0.1"
    }
}

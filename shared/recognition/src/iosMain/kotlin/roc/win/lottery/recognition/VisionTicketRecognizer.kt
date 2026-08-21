package roc.win.lottery.recognition

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectGetMaxX
import platform.CoreGraphics.CGRectGetMaxY
import platform.CoreGraphics.CGRectGetMinX
import platform.CoreGraphics.CGRectGetMinY
import platform.UIKit.UIImage
import platform.Vision.VNImageRequestHandler
import platform.Vision.VNRecognizeTextRequest
import platform.Vision.VNRecognizedText
import platform.Vision.VNRecognizedTextObservation
import platform.Vision.VNRequestTextRecognitionLevelAccurate

/** 使用 Apple Vision 在本机执行中文文字识别。 */
@OptIn(ExperimentalForeignApi::class)
class VisionTicketRecognizer : TicketRecognizer {
    /** 对私有 JPEG 执行 Vision OCR，并转换为共享归一化坐标模型。 */
    override suspend fun recognize(imageRef: ImageRef): RecognitionResult =
        withContext(Dispatchers.Default) {
            val image =
                UIImage.imageWithContentsOfFile(imageRef.localPath)?.CGImage
                    ?: return@withContext RecognitionResult.Failure("无法读取本地图片，请重新导入")
            val request =
                VNRecognizeTextRequest(completionHandler = null).apply {
                    recognitionLevel = VNRequestTextRecognitionLevelAccurate
                    recognitionLanguages = listOf("zh-Hans", "en-US")
                    usesLanguageCorrection = false
                }
            val handler = VNImageRequestHandler(cGImage = image, options = emptyMap<Any?, Any>())
            if (!handler.performRequests(listOf(request), error = null)) {
                return@withContext RecognitionResult.Failure("本地文字识别未能完成，请重新导入图片")
            }
            val lines =
                request.results
                    .orEmpty()
                    .mapNotNull { result ->
                        (result as? VNRecognizedTextObservation)?.toOcrLineOrNull()
                    }.sortedWith(compareBy<OcrTextLine> { it.bounds.top }.thenBy { it.bounds.left })
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
        }

    /** 把 Vision 左下原点坐标转换为共享模型的左上原点坐标。 */
    private fun VNRecognizedTextObservation.toOcrLineOrNull(): OcrTextLine? {
        val candidate = topCandidates(1u).firstOrNull() as? VNRecognizedText ?: return null
        val content = candidate.string.trim()
        if (content.isEmpty()) return null
        return OcrTextLine(
            text = content,
            bounds =
                NormalizedBounds(
                    left = CGRectGetMinX(boundingBox).toFloat().coerceIn(0f, 1f),
                    top = (1.0 - CGRectGetMaxY(boundingBox)).toFloat().coerceIn(0f, 1f),
                    right = CGRectGetMaxX(boundingBox).toFloat().coerceIn(0f, 1f),
                    bottom = (1.0 - CGRectGetMinY(boundingBox)).toFloat().coerceIn(0f, 1f),
                ),
            confidence = candidate.confidence.takeIf { it in 0f..1f },
        )
    }

    /** Vision OCR 实现信息。 */
    private companion object {
        /** 用于 PoC 结果追踪的引擎名称。 */
        const val ENGINE_NAME = "Apple Vision 中文文字识别"
    }
}

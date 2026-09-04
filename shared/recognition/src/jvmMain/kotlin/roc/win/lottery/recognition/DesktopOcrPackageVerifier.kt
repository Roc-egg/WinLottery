package roc.win.lottery.recognition

import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.UUID
import javax.imageio.ImageIO

/** 使用不含真实票面数据的合成图片验证最终桌面分发包 OCR 链路。 */
object DesktopOcrPackageVerifier {
    /** 启动真实隔离子进程，并验证共享 PP-OCRv5 能返回标准文档和单调进度。 */
    fun verify(applicationCommand: List<String>): Boolean =
        try {
            runBlocking {
                val imageRef = DesktopOcrSyntheticVerificationImage.create()
                try {
                    val progress = mutableListOf<RecognitionProgress>()
                    val result =
                        DesktopPpOcrTicketRecognizer(applicationCommand).recognize(
                            imageRef = imageRef,
                            onProgress = progress::add,
                        )
                    val document = (result as? RecognitionResult.Success)?.document
                    document != null &&
                        document.imageId == imageRef.id &&
                        document.engineName == EXPECTED_ENGINE_NAME &&
                        document.lines.isNotEmpty() &&
                        progress.firstOrNull()?.fraction == 0f &&
                        progress.lastOrNull()?.fraction == 1f &&
                        progress.zipWithNext().all { (previous, next) ->
                            next.fraction >= previous.fraction
                        }
                } finally {
                    File(imageRef.localPath).delete()
                }
            }
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        }

    /** 共享 OCR 实现应返回的稳定引擎摘要。 */
    private const val EXPECTED_ENGINE_NAME = "PP-OCRv5 mobile + ONNX Runtime"
}

/** 为单元测试和分发包验收创建同一张无真实数据合成票面。 */
internal object DesktopOcrSyntheticVerificationImage {
    /** 在应用受控临时目录创建 JPEG，并返回可交给真实工作进程的轻量引用。 */
    fun create(): ImageRef {
        val temporaryRoot =
            System
                .getProperty(DesktopAppPaths.JAVA_TEMPORARY_DIRECTORY_PROPERTY)
                ?.takeIf(String::isNotBlank)
                ?.let(::File)
                ?: error("无法定位桌面临时目录")
        val imageDirectory = File(temporaryRoot, DesktopAppPaths.TEMPORARY_DIRECTORY_NAME)
        check(imageDirectory.isDirectory || imageDirectory.mkdirs()) { "无法创建桌面 OCR 临时目录" }
        val imageId = UUID.randomUUID().toString()
        val output = File(imageDirectory, "$imageId.jpg")
        val image = BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.color = Color.BLACK
            graphics.font = Font(Font.MONOSPACED, Font.BOLD, FONT_SIZE)
            graphics.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
            )
            TICKET_LINES.forEachIndexed { index, line ->
                graphics.drawString(line, TEXT_LEFT, TEXT_TOP + index * LINE_HEIGHT)
            }
        } finally {
            graphics.dispose()
        }
        try {
            check(ImageIO.write(image, JPEG_FORMAT_NAME, output)) { "无法创建合成 OCR 图片" }
        } finally {
            image.flush()
        }
        return ImageRef(
            id = imageId,
            localPath = output.absolutePath,
            mimeType = JPEG_MIME_TYPE,
            widthPixels = IMAGE_WIDTH,
            heightPixels = IMAGE_HEIGHT,
        )
    }

    /** 合成票面固定参数。 */
    private const val IMAGE_WIDTH = 1_200

    /** 合成票面固定高度。 */
    private const val IMAGE_HEIGHT = 760

    /** 合成票面字体大小。 */
    private const val FONT_SIZE = 42

    /** 合成文字左边距。 */
    private const val TEXT_LEFT = 70

    /** 合成首行基线。 */
    private const val TEXT_TOP = 100

    /** 合成文字行距。 */
    private const val LINE_HEIGHT = 88

    /** 私有副本 MIME 类型。 */
    private const val JPEG_MIME_TYPE = "image/jpeg"

    /** ImageIO 使用的 JPEG 格式名。 */
    private const val JPEG_FORMAT_NAME = "jpeg"

    /** 不含真实用户数据、用于触发检测和识别模型的合成文字。 */
    private val TICKET_LINES =
        listOf(
            "SUPER LOTTO LOCAL OCR TEST",
            "ISSUE 00000",
            "A 01 02 03 04 05 + 06 07 x1",
            "B 08 09 10 11 12 + 01 02 x1",
            "C 13 14 15 16 17 + 03 04 x1",
            "TOTAL 6.00",
        )
}

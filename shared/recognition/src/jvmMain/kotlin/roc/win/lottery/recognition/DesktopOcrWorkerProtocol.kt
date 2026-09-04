package roc.win.lottery.recognition

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * 桌面 OCR 工作进程请求。
 *
 * @property imageId 私有临时 JPEG 的 UUID 文件名主体。
 */
internal data class DesktopOcrWorkerRequest(
    val imageId: String,
)

/** 通过匿名管道传输桌面 OCR 请求、进度和最终结果的二进制协议。 */
internal object DesktopOcrWorkerProtocol {
    /** 子进程无法形成可信结果时返回给界面的固定说明。 */
    const val GENERIC_FAILURE_MESSAGE = "PP-OCRv5 本地识别进程未能完成，请重新导入图片"

    /** 写入单个只包含随机图片标识的工作请求。 */
    fun writeRequest(
        output: DataOutputStream,
        request: DesktopOcrWorkerRequest,
    ) {
        require(isValidImageId(request.imageId)) { "桌面 OCR 图片标识无效" }
        output.writeInt(REQUEST_MAGIC)
        output.writeInt(PROTOCOL_VERSION)
        output.writeUTF(request.imageId)
        output.flush()
    }

    /** 读取并严格校验唯一工作请求，拒绝额外输入。 */
    fun readRequest(input: DataInputStream): DesktopOcrWorkerRequest {
        require(input.readInt() == REQUEST_MAGIC) { "桌面 OCR 请求协议标识无效" }
        require(input.readInt() == PROTOCOL_VERSION) { "桌面 OCR 请求协议版本无效" }
        val imageId = input.readUTF()
        require(isValidImageId(imageId)) { "桌面 OCR 图片标识无效" }
        require(input.read() == END_OF_STREAM) { "桌面 OCR 请求包含额外数据" }
        return DesktopOcrWorkerRequest(imageId)
    }

    /** 写入响应流头，后续只允许追加进度和一个最终结果。 */
    fun writeResponseHeader(output: DataOutputStream) {
        output.writeInt(RESPONSE_MAGIC)
        output.writeInt(PROTOCOL_VERSION)
        output.flush()
    }

    /** 写入不包含票面内容的阶段进度并立即刷新。 */
    fun writeProgress(
        output: DataOutputStream,
        progress: RecognitionProgress,
    ) {
        validateSafeText(progress.message, MAXIMUM_PROGRESS_MESSAGE_LENGTH, "桌面 OCR 进度说明")
        output.writeByte(EVENT_PROGRESS)
        output.writeFloat(progress.fraction)
        output.writeUTF(progress.message)
        output.flush()
    }

    /** 写入唯一最终结果；失败状态不跨进程传输异常或路径。 */
    fun writeResult(
        output: DataOutputStream,
        result: RecognitionResult,
    ) {
        when (result) {
            is RecognitionResult.Success -> writeSuccess(output, result.document)

            is RecognitionResult.PoorImage -> writePoorImage(output, result.issues)

            is RecognitionResult.ManualEntryRequired,
            is RecognitionResult.Failure,
            -> output.writeByte(EVENT_FAILURE)
        }
        output.flush()
    }

    /** 读取完整响应流，实时转发进度并返回唯一最终结果。 */
    fun readResponse(
        input: DataInputStream,
        expectedImageId: String,
        onProgress: (RecognitionProgress) -> Unit,
    ): RecognitionResult {
        require(input.readInt() == RESPONSE_MAGIC) { "桌面 OCR 响应协议标识无效" }
        require(input.readInt() == PROTOCOL_VERSION) { "桌面 OCR 响应协议版本无效" }
        var progressEventCount = 0
        var lastProgress = 0f
        while (true) {
            when (input.readUnsignedByte()) {
                EVENT_PROGRESS -> {
                    progressEventCount += 1
                    require(progressEventCount <= MAXIMUM_PROGRESS_EVENT_COUNT) {
                        "桌面 OCR 进度事件过多"
                    }
                    val fraction = input.readFloat()
                    val message = input.readUTF()
                    require(fraction.isFinite() && fraction in lastProgress..1f) {
                        "桌面 OCR 进度非单调"
                    }
                    validateSafeText(message, MAXIMUM_PROGRESS_MESSAGE_LENGTH, "桌面 OCR 进度说明")
                    lastProgress = fraction
                    onProgress(RecognitionProgress(fraction, message))
                }

                EVENT_SUCCESS -> {
                    return readSuccess(input, expectedImageId).also { requireEndOfStream(input) }
                }

                EVENT_POOR_IMAGE -> {
                    return readPoorImage(input).also { requireEndOfStream(input) }
                }

                EVENT_FAILURE -> {
                    return RecognitionResult.Failure(GENERIC_FAILURE_MESSAGE).also {
                        requireEndOfStream(input)
                    }
                }

                else -> {
                    error("桌面 OCR 响应事件无效")
                }
            }
        }
    }

    /** 写入不含业务解析的标准 OCR 文档。 */
    private fun writeSuccess(
        output: DataOutputStream,
        document: OcrDocument,
    ) {
        require(isValidImageId(document.imageId)) { "桌面 OCR 结果图片标识无效" }
        validateSafeText(document.engineName, MAXIMUM_ENGINE_NAME_LENGTH, "桌面 OCR 引擎名称")
        require(document.lines.size in 1..MAXIMUM_TEXT_LINE_COUNT) { "桌面 OCR 文字行数量无效" }
        output.writeByte(EVENT_SUCCESS)
        output.writeUTF(document.imageId)
        output.writeUTF(document.engineName)
        output.writeInt(document.lines.size)
        document.lines.forEach { line ->
            validateTextLine(line)
            output.writeUTF(line.text)
            output.writeFloat(line.bounds.left)
            output.writeFloat(line.bounds.top)
            output.writeFloat(line.bounds.right)
            output.writeFloat(line.bounds.bottom)
            output.writeBoolean(line.confidence != null)
            line.confidence?.let(output::writeFloat)
        }
    }

    /** 读取并校验标准 OCR 文档。 */
    private fun readSuccess(
        input: DataInputStream,
        expectedImageId: String,
    ): RecognitionResult.Success {
        val imageId = input.readUTF()
        require(imageId == expectedImageId && isValidImageId(imageId)) { "桌面 OCR 结果图片标识不匹配" }
        val engineName = input.readUTF()
        validateSafeText(engineName, MAXIMUM_ENGINE_NAME_LENGTH, "桌面 OCR 引擎名称")
        val lineCount = input.readInt()
        require(lineCount in 1..MAXIMUM_TEXT_LINE_COUNT) { "桌面 OCR 文字行数量无效" }
        val lines =
            List(lineCount) {
                val text = input.readUTF()
                val bounds =
                    NormalizedBounds(
                        left = input.readFloat(),
                        top = input.readFloat(),
                        right = input.readFloat(),
                        bottom = input.readFloat(),
                    )
                val confidence = if (input.readBoolean()) input.readFloat() else null
                OcrTextLine(text, bounds, confidence).also(::validateTextLine)
            }
        return RecognitionResult.Success(OcrDocument(imageId, lines, engineName))
    }

    /** 写入可展示但不包含票面文字的图片质量问题。 */
    private fun writePoorImage(
        output: DataOutputStream,
        issues: List<String>,
    ) {
        require(issues.size in 1..MAXIMUM_ISSUE_COUNT) { "桌面 OCR 图片问题数量无效" }
        output.writeByte(EVENT_POOR_IMAGE)
        output.writeInt(issues.size)
        issues.forEach { issue ->
            validateSafeText(issue, MAXIMUM_ISSUE_LENGTH, "桌面 OCR 图片问题")
            output.writeUTF(issue)
        }
    }

    /** 读取可展示但不包含票面文字的图片质量问题。 */
    private fun readPoorImage(input: DataInputStream): RecognitionResult.PoorImage {
        val issueCount = input.readInt()
        require(issueCount in 1..MAXIMUM_ISSUE_COUNT) { "桌面 OCR 图片问题数量无效" }
        val issues =
            List(issueCount) {
                input.readUTF().also { issue ->
                    validateSafeText(issue, MAXIMUM_ISSUE_LENGTH, "桌面 OCR 图片问题")
                }
            }
        return RecognitionResult.PoorImage(issues)
    }

    /** 校验单行 OCR 文本、归一化边界和置信度。 */
    private fun validateTextLine(line: OcrTextLine) {
        validateSafeText(line.text, MAXIMUM_TEXT_LINE_LENGTH, "桌面 OCR 文字行")
        val bounds = line.bounds
        require(
            bounds.left.isFinite() &&
                bounds.top.isFinite() &&
                bounds.right.isFinite() &&
                bounds.bottom.isFinite() &&
                bounds.left in 0f..1f &&
                bounds.top in 0f..1f &&
                bounds.right in bounds.left..1f &&
                bounds.bottom in bounds.top..1f,
        ) {
            "桌面 OCR 文字边界无效"
        }
        require(line.confidence == null || (line.confidence.isFinite() && line.confidence in 0f..1f)) {
            "桌面 OCR 文字置信度无效"
        }
    }

    /** 拒绝空白、过长或含控制字符的协议文本。 */
    private fun validateSafeText(
        value: String,
        maximumLength: Int,
        label: String,
    ) {
        require(value.isNotBlank() && value.length <= maximumLength && value.none(Char::isISOControl)) {
            "$label 无效"
        }
    }

    /** 最终结果后必须立即结束响应流。 */
    private fun requireEndOfStream(input: DataInputStream) {
        require(input.read() == END_OF_STREAM) { "桌面 OCR 最终结果后包含额外数据" }
    }

    /** 只接受 Desktop 图片导入器生成的小写 UUID。 */
    fun isValidImageId(imageId: String): Boolean = IMAGE_ID_PATTERN.matches(imageId)

    /** 桌面 OCR 匿名管道协议常量。 */
    private const val PROTOCOL_VERSION = 1

    /** 请求流固定标识 `WLOQ`。 */
    private const val REQUEST_MAGIC = 0x574C4F51

    /** 响应流固定标识 `WLOR`。 */
    private const val RESPONSE_MAGIC = 0x574C4F52

    /** 进度事件类型。 */
    private const val EVENT_PROGRESS = 1

    /** 成功结果事件类型。 */
    private const val EVENT_SUCCESS = 2

    /** 图片质量不足事件类型。 */
    private const val EVENT_POOR_IMAGE = 3

    /** 通用失败事件类型。 */
    private const val EVENT_FAILURE = 4

    /** 输入流结束标识。 */
    private const val END_OF_STREAM = -1

    /** 单次识别允许的最大进度事件数量。 */
    private const val MAXIMUM_PROGRESS_EVENT_COUNT = 4_096

    /** 进度说明最大字符数。 */
    private const val MAXIMUM_PROGRESS_MESSAGE_LENGTH = 256

    /** OCR 引擎名称最大字符数。 */
    private const val MAXIMUM_ENGINE_NAME_LENGTH = 128

    /** 单次 OCR 最大文字行数量。 */
    private const val MAXIMUM_TEXT_LINE_COUNT = 2_048

    /** 单行 OCR 文本最大字符数。 */
    private const val MAXIMUM_TEXT_LINE_LENGTH = 2_048

    /** 图片问题最大数量。 */
    private const val MAXIMUM_ISSUE_COUNT = 16

    /** 单条图片问题最大字符数。 */
    private const val MAXIMUM_ISSUE_LENGTH = 256

    /** Desktop 图片导入器生成的标准小写 UUID。 */
    private val IMAGE_ID_PATTERN =
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
}

package roc.win.lottery.recognition

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** Desktop OCR 匿名管道协议测试。 */
class DesktopOcrWorkerProtocolTest {
    /** 请求应只往返标准 UUID，并拒绝路径、额外字节和非标准标识。 */
    @Test
    fun requestRoundTripRejectsUntrustedInput() {
        val request = DesktopOcrWorkerRequest(IMAGE_ID)
        assertEquals(request, readRequest(writeRequest(request)))

        assertFailsWith<IllegalArgumentException> {
            writeRequest(DesktopOcrWorkerRequest("../../private-ticket.jpg"))
        }
        assertFailsWith<IllegalArgumentException> {
            readRequest(writeRequest(request) + EXTRA_BYTE)
        }
    }

    /** 成功响应应实时转发单调进度，并完整保留标准 OCR 文档。 */
    @Test
    fun successResponseRoundTripsProgressAndDocument() {
        val progress = mutableListOf<RecognitionProgress>()
        val document =
            OcrDocument(
                imageId = IMAGE_ID,
                lines =
                    listOf(
                        OcrTextLine(
                            text = "合成测试行",
                            bounds = NormalizedBounds(0.1f, 0.2f, 0.8f, 0.3f),
                            confidence = 0.92f,
                        ),
                    ),
                engineName = "测试引擎",
            )
        val bytes =
            writeResponse {
                DesktopOcrWorkerProtocol.writeProgress(this, RecognitionProgress(0.2f, "正在检测"))
                DesktopOcrWorkerProtocol.writeProgress(this, RecognitionProgress(1f, "识别完成"))
                DesktopOcrWorkerProtocol.writeResult(this, RecognitionResult.Success(document))
            }

        val result = readResponse(bytes, progress::add)

        assertEquals(document, assertIs<RecognitionResult.Success>(result).document)
        assertEquals(listOf(0.2f, 1f), progress.map(RecognitionProgress::fraction))
    }

    /** 图片质量和通用失败响应不得跨进程携带票面文本或异常细节。 */
    @Test
    fun nonSuccessResponsesUseBoundedPublicPayloads() {
        val poorImage =
            readResponse(
                writeResponse {
                    DesktopOcrWorkerProtocol.writeResult(
                        this,
                        RecognitionResult.PoorImage(listOf("未检测到清晰票面文字")),
                    )
                },
            )
        val failure =
            readResponse(
                writeResponse {
                    DesktopOcrWorkerProtocol.writeResult(
                        this,
                        RecognitionResult.Failure("不应跨进程返回的内部错误"),
                    )
                },
            )

        assertEquals(
            listOf("未检测到清晰票面文字"),
            assertIs<RecognitionResult.PoorImage>(poorImage).issues,
        )
        assertEquals(
            DesktopOcrWorkerProtocol.GENERIC_FAILURE_MESSAGE,
            assertIs<RecognitionResult.Failure>(failure).message,
        )
    }

    /** 响应读取器应拒绝非单调进度、无效坐标和最终结果后的额外数据。 */
    @Test
    fun responseRejectsProtocolBoundaryViolations() {
        val nonMonotonic =
            writeResponse {
                DesktopOcrWorkerProtocol.writeProgress(this, RecognitionProgress(0.8f, "后阶段"))
                DesktopOcrWorkerProtocol.writeProgress(this, RecognitionProgress(0.4f, "前阶段"))
                DesktopOcrWorkerProtocol.writeResult(this, RecognitionResult.PoorImage(listOf("测试问题")))
            }
        assertFailsWith<IllegalArgumentException> { readResponse(nonMonotonic) }

        assertFailsWith<IllegalArgumentException> {
            writeResponse {
                DesktopOcrWorkerProtocol.writeResult(
                    this,
                    RecognitionResult.Success(
                        OcrDocument(
                            imageId = IMAGE_ID,
                            lines =
                                listOf(
                                    OcrTextLine(
                                        text = "越界",
                                        bounds = NormalizedBounds(-0.1f, 0f, 1f, 1f),
                                        confidence = 1f,
                                    ),
                                ),
                            engineName = "测试引擎",
                        ),
                    ),
                )
            }
        }

        val valid =
            writeResponse {
                DesktopOcrWorkerProtocol.writeResult(this, RecognitionResult.PoorImage(listOf("测试问题")))
            }
        assertFailsWith<IllegalArgumentException> { readResponse(valid + EXTRA_BYTE) }
    }

    /** 编码并返回唯一 OCR 请求。 */
    private fun writeRequest(request: DesktopOcrWorkerRequest): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                DesktopOcrWorkerProtocol.writeRequest(output, request)
            }
            bytes.toByteArray()
        }

    /** 从完整字节流读取唯一 OCR 请求。 */
    private fun readRequest(bytes: ByteArray): DesktopOcrWorkerRequest =
        DataInputStream(ByteArrayInputStream(bytes)).use(DesktopOcrWorkerProtocol::readRequest)

    /** 编码响应流头和调用方提供的响应事件。 */
    private fun writeResponse(writeEvents: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                DesktopOcrWorkerProtocol.writeResponseHeader(output)
                output.writeEvents()
            }
            bytes.toByteArray()
        }

    /** 读取完整响应，并允许调用方收集进度。 */
    private fun readResponse(
        bytes: ByteArray,
        onProgress: (RecognitionProgress) -> Unit = {},
    ): RecognitionResult =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            DesktopOcrWorkerProtocol.readResponse(input, IMAGE_ID, onProgress)
        }

    /** 协议测试使用的标准小写 UUID。 */
    private companion object {
        /** 唯一图片标识。 */
        const val IMAGE_ID = "01234567-89ab-cdef-0123-456789abcdef"

        /** 用于构造流尾越界输入的单字节。 */
        val EXTRA_BYTE = byteArrayOf(1)
    }
}

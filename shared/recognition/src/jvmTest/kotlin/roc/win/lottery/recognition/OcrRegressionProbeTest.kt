package roc.win.lottery.recognition

import ai.onnxruntime.OrtSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.time.TimeSource

/**
 * 真实票样 OCR 回归与测速探针。
 *
 * 只在设置 `WINLOTTERY_OCR_PROBE_DIR` 和 `WINLOTTERY_OCR_PROBE_OUTPUT` 时运行，CI 中自动跳过。对目录内全部票图
 * 在当前进程执行桌面识别和保守解析，逐张写出一行 JSON，供 `tools/ocr-regression/compare.py` 对比新旧版本。
 *
 * 可选环境变量：
 * - `WINLOTTERY_OCR_PROBE_MODELS`：替换模型的目录，按 [PpOcrModel.fileName] 和字典文件名覆盖，缺少的沿用打包版本。
 * - `WINLOTTERY_OCR_PROBE_DETECTION`：`像素阈值,框阈值,扩张比例,最长边[,up]`，覆盖检测参数；`up` 表示小图放大到最长边。
 * - `WINLOTTERY_OCR_PROBE_SESSION`：额外会话配置，目前支持 `coreml`。
 */
class OcrRegressionProbeTest {
    /** 逐张识别并写出结果。 */
    @Test
    fun probe() =
        runBlocking {
            val directory = System.getenv("WINLOTTERY_OCR_PROBE_DIR")?.let(::File) ?: return@runBlocking
            val output = System.getenv("WINLOTTERY_OCR_PROBE_OUTPUT")?.let(::File) ?: return@runBlocking
            val recognizer = createRecognizer()
            val parser = ConservativeTicketParser()
            val images =
                directory
                    .walkTopDown()
                    .filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
                    .sortedBy { it.relativeTo(directory).path }
                    .toList()
            check(images.isNotEmpty()) { "样本目录中没有票图" }
            // 首张图先完整跑一次，排除模型加载和 JIT 预热对耗时的影响。
            recognizer.recognize(images.first().toImageRef(directory)) {}
            val lines =
                images.map { file ->
                    val start = TimeSource.Monotonic.markNow()
                    val (result, stats) =
                        PpOcrInferenceDiagnostics.collect {
                            recognizer.recognize(file.toImageRef(directory)) {}
                        }
                    val ocrMicroseconds = start.elapsedNow().inWholeMicroseconds
                    reportLine(file.relativeTo(directory).path, result, parser, ocrMicroseconds, stats)
                }
            output.parentFile?.mkdirs()
            output.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
        }

    /** 按环境变量组装与生产相同的共享识别流程。 */
    private fun createRecognizer(): PpOcrTicketRecognizer {
        val bundled = DesktopPpOcrResources()
        val overrides = System.getenv("WINLOTTERY_OCR_PROBE_MODELS")?.takeIf(String::isNotBlank)?.let(::File)

        fun override(fileName: String): File? = overrides?.resolve(fileName)?.takeIf(File::isFile)

        val characters =
            override(PP_OCR_DICTIONARY_FILE_NAME)?.readText()?.let { parsePpOcrCharacters(it, it.lineCount()) }
                ?: parsePpOcrCharacters(bundled.readText(PP_OCR_DICTIONARY_FILE_NAME))
        val detection =
            System.getenv("WINLOTTERY_OCR_PROBE_DETECTION")?.takeIf(String::isNotBlank)?.split(',')?.let { values ->
                PpOcrDetectionConfig(
                    pixelThreshold = values[0].toFloat(),
                    boxThreshold = values[1].toFloat(),
                    unclipRatio = values[2].toFloat(),
                    longEdge = values[3].toInt(),
                    upscaleSmallImages = values.getOrNull(4) == "up",
                )
            } ?: PpOcrPipelineConfig().detection
        val session = System.getenv("WINLOTTERY_OCR_PROBE_SESSION").orEmpty()
        return PpOcrTicketRecognizer(
            imageDecoder = DesktopPpOcrRgbImageDecoder(),
            runtime =
                DesktopPpOcrOnnxRuntime(
                    modelBytes = { model -> override(model.fileName)?.readBytes() ?: bundled.readModel(model) },
                    configureSession = { options -> options.applyProbeSession(session) },
                ),
            characters = characters,
            config = PpOcrPipelineConfig(characterCount = characters.size, detection = detection),
        )
    }

    /** 生成一张票图的结果行。 */
    private fun reportLine(
        name: String,
        result: RecognitionResult,
        parser: ConservativeTicketParser,
        ocrMicroseconds: Long,
        stats: PpOcrInferenceStats,
    ): String {
        val parsed = (result as? RecognitionResult.Success)?.let { parser.parse(it.document) }
        val draft =
            when (parsed) {
                is TicketParseResult.ReadyForReview -> parsed.draft
                is TicketParseResult.NeedsCorrection -> parsed.draft
                else -> null
            }
        val fields =
            when (parsed) {
                is TicketParseResult.ReadyForReview -> parsed.fieldRegions
                is TicketParseResult.NeedsCorrection -> parsed.fieldRegions
                else -> emptyList()
            }.map(TicketFieldRegion::field).toSet()
        return buildJsonObject {
            put("image", name)
            put("outcome", parsed?.let { it::class.simpleName } ?: result::class.simpleName)
            put("lottery", draft?.lotteryType?.name)
            put("issue", draft?.issue)
            put("periods", draft?.periodCount)
            put("periodsRecognized", TicketFieldReference.PeriodCount in fields)
            put("multiplier", draft?.multiplier)
            put("amountFen", draft?.paidAmountFen)
            put(
                "bets",
                JsonArray(
                    draft?.betLines.orEmpty().map { line ->
                        val additional =
                            when (line.isAdditional) {
                                true -> "追加"
                                false -> ""
                                null -> "?"
                            }
                        JsonPrimitive(
                            "${line.primaryNumbers.joinToString(" ")}+${line.secondaryNumbers.joinToString(" ")}" +
                                additional,
                        )
                    },
                ),
            )
            put(
                "message",
                when (parsed) {
                    is TicketParseResult.NeedsCorrection -> parsed.message
                    is TicketParseResult.Unsupported -> parsed.message
                    else -> null
                },
            )
            put("ocrMs", ocrMicroseconds / MICROSECONDS_PER_MILLISECOND)
            PpOcrModel.entries.forEach { model ->
                put("${model.name.lowercase()}Runs", stats.count(model))
                put("${model.name.lowercase()}Ms", stats.microseconds(model) / MICROSECONDS_PER_MILLISECOND)
            }
        }.toString()
    }

    /** 包装为样本目录内的本地图片引用。 */
    private fun File.toImageRef(directory: File): ImageRef =
        ImageRef(relativeTo(directory).path, absolutePath, "image/jpeg", null, null)

    /** 字典文件的有效行数。 */
    private fun String.lineCount(): Int = replace("\r\n", "\n").trimEnd('\n').split('\n').size

    /** 按名称追加评估用会话选项。 */
    private fun OrtSession.SessionOptions.applyProbeSession(name: String) {
        when (name) {
            "" -> Unit
            "coreml" -> addCoreML()
            else -> error("未知会话配置：$name")
        }
    }

    /** 探针固定参数。 */
    private companion object {
        /** 参与回归的图片扩展名。 */
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png")

        /** 1 毫秒包含的微秒数。 */
        const val MICROSECONDS_PER_MILLISECOND = 1_000L
    }
}

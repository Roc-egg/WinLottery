package roc.win.lottery.recognition

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * 一份需要在加载前校验的桌面 OCR 资源文件。
 *
 * @property resourceFile 模型资源根目录下的单层文件名。
 * @property byteCount 锁定文件字节数。
 * @property sha256 锁定文件 SHA-256 小写十六进制值。
 */
internal data class DesktopOcrLockedFile(
    val resourceFile: String,
    val byteCount: Long,
    val sha256: String,
)

/**
 * ONNX 单输入或单输出张量接口。
 *
 * @property name 锁定张量名称。
 * @property shape 锁定张量形状，动态维度统一使用 -1。
 */
internal data class DesktopOcrTensorContract(
    val name: String,
    val shape: List<Int>,
)

/**
 * 一段 PP-OCRv5 ONNX 模型的运行时锁。
 *
 * @property id 固定模型职责标识。
 * @property file ONNX 资源文件锁。
 * @property input 唯一输入张量接口。
 * @property output 唯一输出张量接口。
 */
internal data class DesktopOcrLockedModel(
    val id: String,
    val file: DesktopOcrLockedFile,
    val input: DesktopOcrTensorContract,
    val output: DesktopOcrTensorContract,
)

/**
 * PP-OCRv5 Desktop 模型包的运行时锁定清单。
 *
 * @property schemaVersion 清单结构版本。
 * @property bundleId 模型包唯一版本标识。
 * @property resourceRoot 类路径中的模型资源根目录。
 * @property opsetVersion 三段 ONNX 统一 opset 版本。
 * @property dictionary 字符字典资源文件锁。
 * @property dictionaryLineCount 字符字典固定行数。
 * @property models 按检测、方向、识别顺序排列的三段模型锁。
 */
internal data class DesktopOcrModelManifest(
    val schemaVersion: Int,
    val bundleId: String,
    val resourceRoot: String,
    val opsetVersion: Int,
    val dictionary: DesktopOcrLockedFile,
    val dictionaryLineCount: Int,
    val models: List<DesktopOcrLockedModel>,
)

/** 从包内 JSON 读取并严格校验桌面 OCR 模型锁。 */
internal object DesktopOcrModelManifestLoader {
    /** 包内模型锁的绝对类路径。 */
    private const val MODEL_LOCK_RESOURCE =
        "/roc/win/lottery/recognition/models/ppocrv5/model-lock.json"

    /** 当前运行时代码支持的模型锁结构版本。 */
    private const val SUPPORTED_SCHEMA_VERSION = 1

    /** 当前运行时代码固定使用的 Paddle2ONNX opset。 */
    private const val SUPPORTED_OPSET_VERSION = 17

    /** PP-OCRv5 三段模型必须保持的稳定职责顺序。 */
    private val expectedModelIds = listOf("detection", "orientation", "recognition")

    /** 单层资源文件名允许使用的字符范围。 */
    private val safeFileNamePattern = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")

    /** SHA-256 小写十六进制文本格式。 */
    private val sha256Pattern = Regex("^[0-9a-f]{64}$")

    /** 从当前 JAR 资源读取并解析模型锁。 */
    fun load(): DesktopOcrModelManifest {
        val content =
            checkNotNull(DesktopOcrModelManifestLoader::class.java.getResource(MODEL_LOCK_RESOURCE)) {
                "桌面 OCR 模型锁资源缺失"
            }.readText(Charsets.UTF_8)
        return parse(content)
    }

    /** 解析 JSON 文本，供运行时和不可信边界测试共用。 */
    internal fun parse(content: String): DesktopOcrModelManifest {
        val root = Json.parseToJsonElement(content).jsonObject
        val conversion = root.requiredObject("conversion", "modelLock")
        val dictionaryObject = root.requiredObject("dictionary", "modelLock")
        val manifest =
            DesktopOcrModelManifest(
                schemaVersion = root.requiredInt("schemaVersion", "modelLock"),
                bundleId = root.requiredString("bundleId", "modelLock"),
                resourceRoot = root.requiredString("resourceRoot", "modelLock"),
                opsetVersion = conversion.requiredInt("opsetVersion", "conversion"),
                dictionary = dictionaryObject.toLockedFile("dictionary"),
                dictionaryLineCount = dictionaryObject.requiredInt("lineCount", "dictionary"),
                models =
                    root.requiredArray("models", "modelLock").mapIndexed { index, element ->
                        element.requiredObject("models[$index]").toLockedModel("models[$index]")
                    },
            )
        validate(manifest)
        return manifest
    }

    /** 校验运行时真正依赖的模型顺序、路径、哈希和张量约束。 */
    private fun validate(manifest: DesktopOcrModelManifest) {
        require(manifest.schemaVersion == SUPPORTED_SCHEMA_VERSION) {
            "桌面 OCR 模型锁结构版本不受支持"
        }
        require(manifest.bundleId.isNotBlank()) { "桌面 OCR 模型包标识不能为空" }
        require(isSafeResourceRoot(manifest.resourceRoot)) { "桌面 OCR 模型资源根目录不安全" }
        require(manifest.opsetVersion == SUPPORTED_OPSET_VERSION) {
            "桌面 OCR 模型 opset 不受支持"
        }
        require(manifest.dictionaryLineCount > 0) { "桌面 OCR 字典行数必须大于零" }
        validateLockedFile(manifest.dictionary, "字典")

        require(manifest.models.map(DesktopOcrLockedModel::id) == expectedModelIds) {
            "桌面 OCR 必须按检测、方向、识别顺序锁定三段模型"
        }
        val resourceFiles =
            manifest.models.map { model -> model.file.resourceFile } +
                manifest.dictionary.resourceFile
        require(resourceFiles.distinct().size == resourceFiles.size) {
            "桌面 OCR 模型和字典资源文件名不得重复"
        }
        manifest.models.forEach { model ->
            validateLockedFile(model.file, "模型 ${model.id}")
            validateTensorContract(model.input, "模型 ${model.id} 输入")
            validateTensorContract(model.output, "模型 ${model.id} 输出")
            require(model.input.name == "x") { "模型 ${model.id} 输入名称不受支持" }
            require(model.output.name == "fetch_name_0") { "模型 ${model.id} 输出名称不受支持" }
        }

        val recognitionClasses =
            manifest.models
                .last()
                .output.shape
                .last()
        require(recognitionClasses == manifest.dictionaryLineCount + 2) {
            "识别输出类别数必须等于字典行数加两个 CTC 特殊类别"
        }
    }

    /** 校验资源根目录只包含安全的正向相对路径段。 */
    private fun isSafeResourceRoot(resourceRoot: String): Boolean {
        if (resourceRoot.startsWith('/') || '\\' in resourceRoot) {
            return false
        }
        val segments = resourceRoot.split('/')
        return segments.isNotEmpty() &&
            segments.all { segment ->
                segment.isNotBlank() && segment != "." && segment != ".."
            }
    }

    /** 校验资源文件的单层名称、长度和 SHA-256。 */
    private fun validateLockedFile(
        file: DesktopOcrLockedFile,
        label: String,
    ) {
        require(safeFileNamePattern.matches(file.resourceFile)) { "$label 文件名不安全" }
        require(file.byteCount > 0) { "$label 字节数必须大于零" }
        require(sha256Pattern.matches(file.sha256)) { "$label SHA-256 格式无效" }
    }

    /** 校验张量名称和只使用 -1 表示动态维度的非空形状。 */
    private fun validateTensorContract(
        contract: DesktopOcrTensorContract,
        label: String,
    ) {
        require(contract.name.isNotBlank()) { "${label}名称不能为空" }
        require(contract.shape.isNotEmpty()) { "${label}形状不能为空" }
        require(contract.shape.all { dimension -> dimension == -1 || dimension > 0 }) {
            "${label}形状只允许正整数或 -1"
        }
    }

    /** 把模型 JSON 对象转换为运行时锁。 */
    private fun JsonObject.toLockedModel(label: String): DesktopOcrLockedModel =
        DesktopOcrLockedModel(
            id = requiredString("id", label),
            file = toLockedFile(label),
            input = requiredObject("input", label).toTensorContract("$label.input"),
            output = requiredObject("output", label).toTensorContract("$label.output"),
        )

    /** 把字典或模型 JSON 对象转换为资源文件锁。 */
    private fun JsonObject.toLockedFile(label: String): DesktopOcrLockedFile =
        DesktopOcrLockedFile(
            resourceFile = requiredString("resourceFile", label),
            byteCount = requiredLong("byteCount", label),
            sha256 = requiredString("sha256", label),
        )

    /** 把张量 JSON 对象转换为接口约束。 */
    private fun JsonObject.toTensorContract(label: String): DesktopOcrTensorContract =
        DesktopOcrTensorContract(
            name = requiredString("name", label),
            shape =
                requiredArray("shape", label).mapIndexed { index, element ->
                    element.requiredInt("$label.shape[$index]")
                },
        )

    /** 读取必需的 JSON 对象字段。 */
    private fun JsonObject.requiredObject(
        name: String,
        label: String,
    ): JsonObject = requiredElement(name, label).requiredObject("$label.$name")

    /** 要求当前 JSON 元素是对象。 */
    private fun JsonElement.requiredObject(label: String): JsonObject =
        this as? JsonObject ?: throw IllegalArgumentException("$label 必须是对象")

    /** 读取必需的 JSON 数组字段。 */
    private fun JsonObject.requiredArray(
        name: String,
        label: String,
    ): JsonArray =
        requiredElement(name, label) as? JsonArray
            ?: throw IllegalArgumentException("$label.$name 必须是数组")

    /** 读取必需的非空 JSON 字符串字段。 */
    private fun JsonObject.requiredString(
        name: String,
        label: String,
    ): String {
        val primitive = requiredElement(name, label) as? JsonPrimitive
        require(primitive != null && primitive.isString && primitive.content.isNotBlank()) {
            "$label.$name 必须是非空字符串"
        }
        return primitive.content
    }

    /** 读取必需且范围有效的 JSON Int 字段。 */
    private fun JsonObject.requiredInt(
        name: String,
        label: String,
    ): Int = requiredElement(name, label).requiredInt("$label.$name")

    /** 要求当前 JSON 元素是 Int。 */
    private fun JsonElement.requiredInt(label: String): Int {
        val primitive = this as? JsonPrimitive
        return primitive?.takeUnless(JsonPrimitive::isString)?.intOrNull
            ?: throw IllegalArgumentException("$label 必须是整数")
    }

    /** 读取必需且范围有效的 JSON Long 字段。 */
    private fun JsonObject.requiredLong(
        name: String,
        label: String,
    ): Long {
        val primitive = requiredElement(name, label) as? JsonPrimitive
        return primitive?.takeUnless(JsonPrimitive::isString)?.longOrNull
            ?: throw IllegalArgumentException("$label.$name 必须是整数")
    }

    /** 读取必需的 JSON 字段。 */
    private fun JsonObject.requiredElement(
        name: String,
        label: String,
    ): JsonElement = this[name] ?: throw IllegalArgumentException("$label.$name 缺失")
}

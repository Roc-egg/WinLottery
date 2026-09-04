package roc.win.lottery.recognition

/**
 * 一份随应用分发的 PP-OCRv5 资源锁。
 *
 * @property byteCount 资源的固定字节数。
 * @property sha256 资源的小写十六进制 SHA-256。
 */
internal data class PpOcrResourceLock(
    val byteCount: Long,
    val sha256: String,
)

/** Android、iOS 和 Desktop 共用的四段 ONNX 模型资源锁。 */
internal val PP_OCR_MODEL_RESOURCE_LOCKS: Map<PpOcrModel, PpOcrResourceLock> =
    mapOf(
        PpOcrModel.DETECTION to
            PpOcrResourceLock(
                byteCount = 4_766_440,
                sha256 = "c8d9b07063420ce5365c74e42532de48238feeeedcdb7a330b195708bc38a93f",
            ),
        PpOcrModel.ORIENTATION to
            PpOcrResourceLock(
                byteCount = 1_016_850,
                sha256 = "4d6027cad43b04171d6eafa20e7342fea7a657724e96d642564392b6df1e9768",
            ),
        PpOcrModel.RECOGNITION to
            PpOcrResourceLock(
                byteCount = 16_529_870,
                sha256 = "bcb195e3463eb9e46ef419b8a01ea4729577de5fd63c64f0a762e43bd64256e7",
            ),
        PpOcrModel.LATIN_RECOGNITION to
            PpOcrResourceLock(
                byteCount = 7_843_511,
                sha256 = "70b2450eed39599af6b996c27a2f1a0ef30eeb49f9f66dd3e74f28f652befc89",
            ),
    )

/** Android、iOS 和 Desktop 共用的两份字符字典资源锁。 */
internal val PP_OCR_DICTIONARY_RESOURCE_LOCKS: Map<String, PpOcrResourceLock> =
    mapOf(
        PP_OCR_DICTIONARY_FILE_NAME to
            PpOcrResourceLock(
                byteCount = 74_012,
                sha256 = "d1979e9f794c464c0d2e0b70a7fe14dd978e9dc644c0e71f14158cdf8342af1b",
            ),
        PP_OCR_LATIN_DICTIONARY_FILE_NAME to
            PpOcrResourceLock(
                byteCount = 1_416,
                sha256 = "e025a66d31f327ba0c232e03f407ae8d105e1e709e7ccb3f408aa778c24e70d6",
            ),
    )

/** 与中文、英文和数字识别模型配套的字符字典文件名。 */
internal const val PP_OCR_DICTIONARY_FILE_NAME = "characters.txt"

/** 与英文数字兜底模型配套的字符字典文件名。 */
internal const val PP_OCR_LATIN_DICTIONARY_FILE_NAME = "characters_latin.txt"

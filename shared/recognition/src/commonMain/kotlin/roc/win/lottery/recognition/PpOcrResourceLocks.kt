package roc.win.lottery.recognition

/**
 * 一份随应用分发的 OCR 模型或字典资源锁。
 *
 * @property byteCount 资源的固定字节数。
 * @property sha256 资源的小写十六进制 SHA-256。
 */
internal data class PpOcrResourceLock(
    val byteCount: Long,
    val sha256: String,
)

/**
 * Android、iOS 和 Desktop 共用的 ONNX 模型资源锁。
 *
 * 三个模型都是 PaddlePaddle 官方 Hugging Face 仓库固定修订下的 `inference.onnx`，按原样分发；
 * 来源地址与修订由 `tools/ocr-models/fetch_models.py` 记录，并可重新下载校验。
 */
internal val PP_OCR_MODEL_RESOURCE_LOCKS: Map<PpOcrModel, PpOcrResourceLock> =
    mapOf(
        PpOcrModel.DETECTION to
            PpOcrResourceLock(
                byteCount = 9_880_512,
                sha256 = "d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e",
            ),
        PpOcrModel.ORIENTATION to
            PpOcrResourceLock(
                byteCount = 1_019_454,
                sha256 = "94a6a0a0425f2b5f08b5df72086f2d72abe40f1d22f6d12d2cd83674f11f2ff3",
            ),
        PpOcrModel.RECOGNITION to
            PpOcrResourceLock(
                byteCount = 21_159_378,
                sha256 = "5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634",
            ),
    )

/** Android、iOS 和 Desktop 共用的字符字典资源锁；内容与识别模型内嵌字符表逐项一致。 */
internal val PP_OCR_DICTIONARY_RESOURCE_LOCKS: Map<String, PpOcrResourceLock> =
    mapOf(
        PP_OCR_DICTIONARY_FILE_NAME to
            PpOcrResourceLock(
                byteCount = 74_947,
                sha256 = "b5f2bfe2bdd9448429e3e82b51c789775d9b42f2403d082b00662eb77e401c5d",
            ),
    )

/** 与文字识别模型配套的字符字典文件名。 */
internal const val PP_OCR_DICTIONARY_FILE_NAME = "characters.txt"

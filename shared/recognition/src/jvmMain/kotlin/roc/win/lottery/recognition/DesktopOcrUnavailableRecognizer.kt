package roc.win.lottery.recognition

/** 桌面 OCR 模型接入前的明确阻断实现，禁止真实图片落入 Fake OCR。 */
class DesktopOcrUnavailableRecognizer : TicketRecognizer {
    /** 返回可操作的能力边界说明，不读取或记录票面内容。 */
    override suspend fun recognize(imageRef: ImageRef): RecognitionResult =
        RecognitionResult.Failure("Windows/macOS 本地 OCR 尚未接入，本次图片不会用于票面识别或开奖查询")
}

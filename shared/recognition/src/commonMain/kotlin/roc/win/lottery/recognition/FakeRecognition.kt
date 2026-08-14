package roc.win.lottery.recognition

import kotlinx.coroutines.delay

/** B1 无相机环境使用的图片采集 Fake。 */
class FakeImageAcquirer(
    /** 当前演示平台是否显示拍照能力。 */
    override val supportsCamera: Boolean,
) : ImageAcquirer {
    /** 返回不包含真实票据内容的内存演示引用。 */
    override suspend fun acquire(source: ImageAcquisitionSource): ImageAcquisitionResult {
        if (source == ImageAcquisitionSource.CAMERA && !supportsCamera) {
            return ImageAcquisitionResult.Unavailable("桌面端 V1 仅支持导入图片")
        }
        delay(ACQUIRE_DELAY_MILLIS)
        return ImageAcquisitionResult.Success(
            ImageRef(
                id = "b1-demo-ticket",
                localPath = "memory://b1-demo-ticket.jpg",
                mimeType = "image/jpeg",
                widthPixels = 1600,
                heightPixels = 2400,
            ),
        )
    }

    /** Fake 操作延迟。 */
    private companion object {
        /** 演示采集延迟毫秒数。 */
        const val ACQUIRE_DELAY_MILLIS = 250L
    }
}

/** B1 无 OCR 环境使用的本地识别 Fake。 */
class FakeTicketRecognizer : TicketRecognizer {
    /** 返回固定且不含真实票据内容的 OCR 文档。 */
    override suspend fun recognize(imageRef: ImageRef): RecognitionResult {
        delay(RECOGNITION_DELAY_MILLIS)
        return RecognitionResult.Success(
            OcrDocument(
                imageId = imageRef.id,
                engineName = "B1 Fake OCR",
                lines =
                    listOf(
                        OcrTextLine("超级大乐透", NormalizedBounds(0.20f, 0.08f, 0.80f, 0.14f), 1.0f),
                        OcrTextLine("第26091期", NormalizedBounds(0.28f, 0.18f, 0.72f, 0.23f), 1.0f),
                        OcrTextLine("单式票 追加投注 1期1倍 合计3元", NormalizedBounds(0.10f, 0.28f, 0.90f, 0.34f), 1.0f),
                        OcrTextLine("02 07 14 21 33 + 04 09", NormalizedBounds(0.12f, 0.40f, 0.88f, 0.47f), 1.0f),
                    ),
            ),
        )
    }

    /** Fake OCR 延迟。 */
    private companion object {
        /** 演示识别延迟毫秒数。 */
        const val RECOGNITION_DELAY_MILLIS = 650L
    }
}

/** B1 Fake 使用的内存临时路径实现。 */
class FakeAppPaths : AppPaths {
    /** Fake 不会创建真实目录。 */
    override val temporaryImageDirectory: String = "memory://temporary-images"

    /** Fake 没有落盘文件，因此始终视为已经清理。 */
    override suspend fun deleteTemporaryImage(imageRef: ImageRef): Boolean = imageRef.id.isNotBlank()
}

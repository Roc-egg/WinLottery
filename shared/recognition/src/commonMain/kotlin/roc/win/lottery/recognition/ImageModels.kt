package roc.win.lottery.recognition

/** 图片进入应用的方式。 */
enum class ImageAcquisitionSource {
    /** 移动端应用内拍照。 */
    CAMERA,

    /** 系统图片选择器或桌面文件选择器。 */
    SYSTEM_PICKER,
}

/**
 * 指向应用私有临时图片的轻量引用。
 *
 * @property id 当前流程内使用的稳定标识。
 * @property localPath 平台私有临时文件路径，不得写入日志或长期持久化。
 * @property mimeType 归一化后的图片 MIME 类型。
 * @property widthPixels 图片宽度，尚未解码时为 `null`。
 * @property heightPixels 图片高度，尚未解码时为 `null`。
 */
data class ImageRef(
    val id: String,
    val localPath: String,
    val mimeType: String,
    val widthPixels: Int?,
    val heightPixels: Int?,
)

/** 图片采集结果。 */
sealed interface ImageAcquisitionResult {
    /**
     * 图片已复制到应用私有临时目录。
     *
     * @property imageRef 不携带图片字节的轻量引用。
     */
    data class Success(
        val imageRef: ImageRef,
    ) : ImageAcquisitionResult

    /** 用户主动取消了系统操作。 */
    data object Cancelled : ImageAcquisitionResult

    /**
     * 当前平台不具备请求的采集能力。
     *
     * @property message 面向用户的简体中文说明。
     */
    data class Unavailable(
        val message: String,
    ) : ImageAcquisitionResult

    /**
     * 采集失败且用户可以重试。
     *
     * @property message 不包含原始路径或票面内容的错误说明。
     */
    data class Failure(
        val message: String,
    ) : ImageAcquisitionResult
}

/** 隔离移动相机和各平台系统图片选择器。 */
interface ImageAcquirer {
    /** 当前平台是否支持应用内拍照。 */
    val supportsCamera: Boolean

    /**
     * 启动平台采集流程并把图片复制到私有临时目录。
     *
     * @param source 用户选择的采集方式。
     * @return 成功、取消、能力不可用或失败状态。
     */
    suspend fun acquire(source: ImageAcquisitionSource): ImageAcquisitionResult
}

/** 管理当前流程使用的应用私有临时文件。 */
interface AppPaths {
    /** 不被系统相册和云备份扫描的临时图片目录。 */
    val temporaryImageDirectory: String

    /** 清理当前平台私有目录中的全部临时票图。 */
    fun clearTemporaryImages()

    /**
     * 删除分析流程已经不再需要的临时图片。
     *
     * @param imageRef 要清理的图片引用。
     * @return 文件已删除或原本不存在时为 `true`。
     */
    suspend fun deleteTemporaryImage(imageRef: ImageRef): Boolean
}

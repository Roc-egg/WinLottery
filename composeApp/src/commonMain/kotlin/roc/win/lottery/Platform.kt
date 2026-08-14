package roc.win.lottery

/** 当前运行平台向共享 UI 暴露的最小能力。 */
interface Platform {
    /** 面向用户的平台名称。 */
    val name: String

    /** V1 是否支持应用内拍照。 */
    val supportsCamera: Boolean
}

/** 返回当前平台能力。 */
expect fun getPlatform(): Platform

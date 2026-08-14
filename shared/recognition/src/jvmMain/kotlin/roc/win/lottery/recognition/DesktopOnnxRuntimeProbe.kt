package roc.win.lottery.recognition

import ai.onnxruntime.OrtEnvironment

/**
 * 桌面 ONNX Runtime 本地加载结果。
 *
 * @property version 实际加载的 ONNX Runtime 版本。
 * @property providerNames 当前系统可用的执行提供器名称。
 */
internal data class DesktopOnnxRuntimeInfo(
    val version: String,
    val providerNames: Set<String>,
)

/** 只验证桌面 ONNX Runtime 原生库和执行提供器，不加载 OCR 模型。 */
internal object DesktopOnnxRuntimeProbe {
    /** ONNX Runtime 在初始化前读取的遥测禁用环境变量。 */
    private const val TELEMETRY_DISABLED_VARIABLE = "ORT_DISABLE_TELEMETRY"

    /** 官方约定的遥测完全禁用值。 */
    private const val TELEMETRY_DISABLED_VALUE = "1"

    /**
     * 触发 ONNX Runtime 原生库加载并返回脱敏运行时信息。
     *
     * @return 版本和可用执行提供器，不包含设备、路径或票面信息。
     */
    fun inspect(): DesktopOnnxRuntimeInfo {
        check(System.getenv(TELEMETRY_DISABLED_VARIABLE) == TELEMETRY_DISABLED_VALUE) {
            "ONNX Runtime 必须在进程启动前禁用遥测"
        }

        val environment = OrtEnvironment.getEnvironment()
        environment.setTelemetry(false)
        return DesktopOnnxRuntimeInfo(
            version = environment.version,
            providerNames = OrtEnvironment.getAvailableProviders().mapTo(linkedSetOf()) { it.name },
        )
    }
}

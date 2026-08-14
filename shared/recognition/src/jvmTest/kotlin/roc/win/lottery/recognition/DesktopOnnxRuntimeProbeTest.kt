package roc.win.lottery.recognition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 桌面 ONNX Runtime 原生库加载测试。 */
class DesktopOnnxRuntimeProbeTest {
    /** 本机构建必须加载冻结版本并提供 CPU 执行能力。 */
    @Test
    fun bundledRuntimeLoadsCpuProvider() {
        val runtime = DesktopOnnxRuntimeProbe.inspect()

        assertEquals("1.29.0", runtime.version)
        assertTrue("CPU" in runtime.providerNames)
    }
}

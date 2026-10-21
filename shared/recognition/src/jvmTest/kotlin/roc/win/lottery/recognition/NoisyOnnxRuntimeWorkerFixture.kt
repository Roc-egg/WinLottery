package roc.win.lottery.recognition

import kotlin.system.exitProcess

/**
 * 模拟 ONNX Runtime 在标准错误上输出警告的工作进程入口。
 *
 * Linux 云主机上，运行时初始化设备发现时会向标准错误写入警告；客户端必须只按标准输出解析协议。
 */
fun main(arguments: Array<String>) {
    if (!DesktopOnnxRuntimeWorker.isRequested(arguments)) {
        exitProcess(3)
    }
    System.err.println("[W:onnxruntime:ort-java, device_discovery.cc:146 GetPciBusId] Skipping pci_bus_id")
    exitProcess(DesktopOnnxRuntimeWorker.run())
}

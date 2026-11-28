# ONNX Runtime iOS 本地包

iOS 应用通过这个本地 Swift Package 使用 ONNX Runtime 1.24.2 执行本地 OCR 模型。

- `objectivec/` 和 `LICENSE` 原样取自微软 `onnxruntime-swift-package-manager` 的 `1.24.2` 标签（提交 `b7fb7f7dea8a2469e6335d95a61b8f36d0dc83b2`，MIT 许可），只删去了测试、文档和训练接口文件。上游仓库已于 2026-11 从 GitHub 移除，原远程包无法再解析。
- `Package.swift` 改编自上游同版本，只保留应用用到的 `onnxruntime` 产品，去掉扩展库和测试目标。
- `Frameworks/onnxruntime.xcframework` 不提交 Git，由 `python3 -I tools/ios/fetch_onnxruntime.py` 从 nuget.org 上微软官方签名的 `Microsoft.ML.OnnxRuntime` 1.24.2 包中取出，下载后按固定 SHA-256 校验。

首次用 Xcode 打开 `iosApp` 前先在仓库根目录运行一次上面的脚本，否则 Xcode 会提示找不到 `onnxruntime` 二进制目标。

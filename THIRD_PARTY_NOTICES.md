# 第三方组件声明

## PaddleOCR PP-OCRv5 模型与字典

- 组件：`PP-OCRv5_mobile_det`、`PP-LCNet_x0_25_textline_ori`、`PP-OCRv5_mobile_rec`、`ppocrv5_dict.txt`
- 上游版本：PaddleOCR `v3.7.0`
- 许可证：Apache License 2.0
- 上游地址：https://github.com/PaddlePaddle/PaddleOCR/tree/v3.7.0
- 许可证正文：[licenses/PaddleOCR-Apache-2.0.txt](licenses/PaddleOCR-Apache-2.0.txt)

仓库使用 Paddle2ONNX `v2.1.0` 将官方 Paddle 推理模型转换为 opset 17 ONNX。转换脚本、依赖版本、源文件哈希和输出哈希由 `shared/recognition/src/jvmMain/resources/roc/win/lottery/recognition/models/ppocrv5/model-lock.json` 锁定。模型未经过训练或字符表裁剪，仅转换了存储格式。

## Microsoft ONNX Runtime

- Android：`com.microsoft.onnxruntime:onnxruntime-android:1.24.2`
- iOS：`onnxruntime-swift-package-manager:1.24.2`
- Desktop 工程底座：`com.microsoft.onnxruntime:onnxruntime:1.29.0`
- 许可证：MIT
- 上游地址：https://github.com/microsoft/onnxruntime
- 许可证正文：[licenses/ONNX-Runtime-MIT.txt](licenses/ONNX-Runtime-MIT.txt)

正式分发前必须把适用许可证正文和 ONNX Runtime 官方 `ThirdPartyNotices.txt` 纳入发布材料，并按目标平台核对最终二进制中的第三方组件。本文件不是完整的全项目依赖清单。

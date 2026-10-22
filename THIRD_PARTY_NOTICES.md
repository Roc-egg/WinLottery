# 第三方组件声明

## PaddleOCR 模型与字典

| 应用内文件 | 上游组件 | 官方来源（固定修订） |
|---|---|---|
| `detection.onnx` | `PP-OCRv6_small_det` | Hugging Face `PaddlePaddle/PP-OCRv6_small_det_onnx` @ `28fe5895c24fd108c19eb3e8479f4ab385fbfc62` |
| `orientation.onnx` | `PP-LCNet_x0_25_textline_ori` | Hugging Face `PaddlePaddle/PP-LCNet_x0_25_textline_ori_onnx` @ `aea1f18d97338aaf84b463f90192f2820524a00a` |
| `recognition.onnx` | `PP-OCRv6_small_rec` | Hugging Face `PaddlePaddle/PP-OCRv6_small_rec_onnx` @ `b8f84f0b80c529de40b4fbb3544b84fa7233a513` |
| `characters.txt` | `ppocr/utils/dict/ppocrv6_dict.txt` | GitHub `PaddlePaddle/PaddleOCR` @ `b03f46425e8ff4442b268ce449e3eef758146cd4` |

- 许可证：Apache License 2.0
- 上游地址：https://github.com/PaddlePaddle/PaddleOCR 、https://huggingface.co/PaddlePaddle
- 许可证正文：[licenses/PaddleOCR-Apache-2.0.txt](licenses/PaddleOCR-Apache-2.0.txt)

三个模型均为官方发布的 ONNX，字典与识别模型内嵌字符表逐项一致；仓库按原样分发，未经过训练、转换、量化或字符表裁剪。下载地址、字节数和 SHA-256 由 `tools/ocr-models/fetch_models.py` 与 `PpOcrResourceLocks.kt` 共同锁定，CI 校验已提交文件。

## Microsoft ONNX Runtime

- Android：`com.microsoft.onnxruntime:onnxruntime-android:1.29.0`
- iOS：`onnxruntime-swift-package-manager:1.24.2`
- Desktop：`com.microsoft.onnxruntime:onnxruntime:1.29.0`
- 许可证：MIT
- 上游地址：https://github.com/microsoft/onnxruntime
- 许可证正文：[licenses/ONNX-Runtime-MIT.txt](licenses/ONNX-Runtime-MIT.txt)

正式分发前必须把适用许可证正文和 ONNX Runtime 官方 `ThirdPartyNotices.txt` 纳入发布材料，并按目标平台核对最终二进制中的第三方组件。本文件不是完整的全项目依赖清单。

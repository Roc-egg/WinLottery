# Desktop PP-OCRv5 模型工具

该目录固定 Windows/macOS Desktop OCR PoC 的官方模型来源、转换环境、转换参数和输出哈希。普通 Gradle 构建不会下载模型，也不会隐式执行 Python；模型仍未接入图片推理工作进程，因此当前桌面真实 OCR 继续保持阻断。

## 锁定范围

- PaddleOCR `v3.7.0`：`PP-OCRv5_mobile_det`、`PP-LCNet_x0_25_textline_ori`、`PP-OCRv5_mobile_rec` 和 `ppocrv5_dict.txt`。
- PaddlePaddle `3.0.0`、Paddle2ONNX `2.1.0`、ONNX `1.17.0`、PyYAML `6.0.3` 和 Paddle2ONNX 实际需要但未声明的 `packaging 24.2`。
- macOS arm64、Python `3.9.6`、opset `17`、关闭自动 opset 升级、开启 ONNX checker、明确使用 `--optimize_tool None`。
- 官方源归档、字典、ONNX 输出、许可证正文均锁定字节数或 SHA-256；识别模型内嵌字符表必须与字典逐项一致。

未启用 `onnxoptimizer`。Paddle2ONNX `2.1.0` 在当前模型上会请求本机 `onnxoptimizer` 不支持的 `eliminate_nop_cast` pass，工具可能跳过优化但仍返回成功，无法作为可复现产线的一部分。

## 使用方式

仅校验仓库内 JSON 锁定清单，不安装依赖、不访问网络：

```bash
python3 tools/desktop-ocr/convert_ppocrv5.py --verify-lock-only
```

创建仓库内隔离虚拟环境、按 wheel 哈希安装依赖、下载官方源并完成三份模型转换：

```bash
tools/desktop-ocr/convert_ppocrv5.sh
```

如果官方源文件已位于默认缓存，可强制断网语义运行：

```bash
tools/desktop-ocr/convert_ppocrv5.sh --offline
```

默认源缓存位于 `.gradle/desktop-ocr/ppocrv5/source`，生成资源位于 `shared/recognition/build/generated/desktopOcrResources/roc/win/lottery/recognition/models/ppocrv5`。两者都属于可再生本地文件，不提交仓库；执行 `clean` 后需要重新生成资源，源缓存和隔离虚拟环境会保留。

## 当前验证证据

三份锁定 ONNX 已通过 ONNX checker、严格形状推导和 ONNX Runtime Java `1.29.0` CPU Session 加载。使用不含彩票信息的确定性合成张量逐元素比较 Paddle 与 ONNX 输出，阈值为 `rtol=1e-4`、`atol=1e-5`：

| 模型 | 输出元素 | 最大绝对误差 | 结果 |
| --- | ---: | ---: | --- |
| detection | 4,096 | `1.09456266e-7` | 通过 |
| orientation | 2 | `7.30156898e-7` | 通过 |
| recognition | 367,700 | `7.42673874e-5` | 通过 |

该证据只证明模型转换数值一致，不代表真实票面 OCR 准确率、速度、内存或发布包验收通过。真实票样不得复制进模型缓存、转换目录或自动化测试产物。

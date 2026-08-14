# B3 图片采集与 OCR PoC 进展记录

记录日期：2026-08-14

## 状态结论

B3 已打通 Android 和 iOS 的“系统选图 → 私有去元数据副本 → 本地 OCR → 共享保守解析 → PoC 核对页”纵向链路，并完成两端应用内相机实现；Android CameraX 和 iOS AVFoundation 的核心拍照流程均已通过真机闭环。iOS 横竖屏、闪光灯模式和拒绝权限分支仍待补测。Windows/macOS 已接入共用的“系统文件选择 → 私有去元数据副本 → 明确 OCR 阻断”链路，并加入 ONNX Runtime 依赖、加载探针和独立本地工作进程；macOS 最终分发启动器已验证由父进程在运行时初始化前禁用遥测并加载 CPU Provider。PP-OCRv5 官方来源锁定、可复现转换和数值一致性验证已完成，但转换模型尚未进入安装包或图片推理协议，Windows 分发实机验收也未完成。B3 尚未通过验收，桌面真实 OCR、图片质量与几何校正、真值标注、准确率统计和干净系统连续识别仍未完成。

当前支持白名单保持为空。本进展不能解释为支持任何省份、销售终端、版式或真实中奖判断。

## 本次已实现

### Android

- 使用 CameraX 提供全屏后置相机取景、闪光灯模式、拍照、真实成片预览、重拍和确认；相机权限按需请求。
- 拍照原始 JPEG 只暂存在应用私有 `camera-captures` 目录，重拍、取消和失败时立即删除；确认后仍需经过统一方向归一、缩放和去元数据重编码，原始暂存文件随后删除。
- 使用系统 Photo Picker 单选 JPG、JPEG 或 PNG，不请求整个媒体库权限。
- 解码后应用 EXIF 方向，PoC 最长边暂限 4096 像素，并重新编码为应用缓存内 JPEG；该参数仍需结合低内存和准确率测试冻结。
- 重新编码后的副本不继承原图 GPS 等 EXIF；缓存目录包含 `.nomedia`，应用启动、流程退出、失败或重新开始时清理临时票图。
- 使用随应用打包的 ML Kit 中文文字识别 `16.0.1`，离线输出行文本、归一化坐标和引擎原始置信度。

### iOS

- 使用 AVFoundation 提供全屏后置相机取景、闪光灯模式、拍照、真实成片预览、重拍和确认；用户确认前只在内存中持有原始成片数据。
- 使用旋转协调器分别同步实时取景和静态成片方向；确认后复用统一的 UIKit 重绘、缩放和去元数据编码流程。
- 使用 PHPicker 单选图片，不请求整个照片库权限。
- 通过 UIKit 重新渲染并编码最长边不超过 4096 像素的私有 JPEG，从而归一方向并去除原始图片元数据；该参数仍需结合性能测试冻结。
- 使用 Apple Vision 精确中文文字识别；关闭语言纠错，避免自然语言模型静默改写数字。
- 把 Vision 左下原点坐标转换为共享左上原点归一化坐标，并保留第一候选置信度。

### Windows/macOS

- 使用桌面系统文件选择器单选 JPG、JPEG 或 PNG，不复制原图路径或内容到共享 UI 状态。
- 只读取 JPEG `APP1` 或 PNG `eXIf` 中 TIFF IFD0 的方向字段，不解析 GPS 等其他元数据目录；完整支持 EXIF 方向 `1..8`。
- 使用 ImageIO 限制最长边不超过 4096 像素，并重新编码为应用临时目录内、不携带原始元数据的 JPEG。
- 应用启动时清理异常退出残留，识别失败、流程退出或重新开始时立即删除当前临时票图；路径边界拒绝删除受控目录外文件。
- 接入 `com.microsoft.onnxruntime:onnxruntime:1.29.0` CPU 运行时，并增加只返回版本和执行提供器的 `DesktopOnnxRuntimeProbe`；不加载图片、模型或票面文本。
- 普通桌面 UI 主进程不加载 ONNX Runtime。`DesktopOnnxRuntimeWorkerClient` 使用当前应用启动器创建独立本地子进程，并在进程启动前注入 `ORT_DISABLE_TELEMETRY=1`；子进程探针再次调用关闭遥测 API。
- 工作进程健康检查协议只返回版本和执行提供器，不接收图片路径、OCR 文本或彩票字段；客户端拒绝额外标准输出或错误输出，并在超时后终止子进程，避免静默接受遥测警告或未知状态。
- 桌面入口在创建 UI 前识别内部工作模式；新增 `verifyPackagedOnnxRuntime` Gradle 任务，对 Compose 最终分发启动器、精简 JDK 21 runtime 和分发 JAR 执行父子进程验收。
- macOS arm64 的最终 `.app` 已在父进程明确未禁用遥测时通过验收，确认子进程覆盖为禁用状态后可加载 1.29.0 和 CPU Provider；任务支持 Gradle configuration cache，最终 `.app` 通过 `codesign --verify --deep --strict`。
- 分发 JAR 已核实包含 macOS arm64 动态库、Windows x64 DLL、`Privacy.md` 和 `ThirdPartyNotices.txt`。官方 Maven JAR 仍包含其他平台原生库和 macOS dSYM，当前没有按目标平台裁剪。
- 固定 PaddleOCR `v3.7.0` 的检测、文本行方向和识别三份官方 Paddle 推理模型及 18,383 行字典；源归档和字典均记录 URL、字节数与 SHA-256。
- 固定 macOS arm64、Python `3.9.6`、PaddlePaddle `3.0.0`、Paddle2ONNX `2.1.0`、ONNX `1.17.0`、PyYAML `6.0.3` 和 `packaging 24.2`，所有 wheel 使用哈希锁；转换固定 opset 17、关闭自动升级、开启 checker 并明确使用 `--optimize_tool None`。
- 三份 ONNX 均通过 checker、严格形状推导和 ONNX Runtime Java `1.29.0` CPU Session 加载；使用非彩票确定性合成张量逐元素比较 Paddle 与 ONNX 输出，检测、方向、识别的最大绝对误差分别为 `1.09456266e-7`、`7.30156898e-7`、`7.42673874e-5`，均通过 `rtol=1e-4`、`atol=1e-5`。
- 增加包内 `model-lock.json`、Kotlin 严格解析和路径穿越拒绝测试，以及完全离线、可重复执行的转换工具；普通 Gradle 构建不会联网下载模型。
- 转换后的 ONNX 二进制尚未纳入仓库或分发包，真实图片导入后仍返回明确能力错误，不会调用 Fake OCR 或进入 Fake 开奖流程。

### 共享流程

- OCR 前增加共享图片质量闸门。当前只检查归一化图片尺寸：PoC 暂定最短边不少于 720 像素、最长边不少于 1280 像素；尺寸缺失会明确失败，分辨率不足会提示靠近彩票重拍。
- 质量闸门拒绝图片时不会调用 OCR，并在错误页出现前清理当前临时图片。阈值尚未经过不同版式和拍摄条件校准，不能视为完整图片质量检测。
- 两个平台的 OCR 文档统一交给 `ConservativeTicketParser`，不在平台层猜测或修正号码。
- 精确彩种标题仍是首选证据；标题 OCR 缺失时，只允许“唯一发行机构锚点 + 唯一合法单式号码结构”联合判定。仅号码结构、机构冲突、结构冲突或结构残缺均要求人工修正。
- 真实识别结果必须停留在 PoC 核对页。当前没有人工编辑器，也不会调用 Fake 开奖仓库；控制器和界面均有阻断。
- OCR 全文、真实号码、期号、路径和 EXIF 不进入日志、测试夹具或文档。

## 本机验证

- Android 9 真机已使用一张单期双色球探索样本完成“系统选图 → 私有 JPEG → 离线 ML Kit OCR → 保守解析 → 核对页 → 结束流程”冒烟测试。
- 真机 OCR 未稳定保留精确彩种标题，但发行机构与严格 `6+1` 单式结构形成一致证据后可进入核对页；该结论只验证当前样本的流程可行性，不构成版式支持承诺。
- 核对页只允许结束本次真实识别，不提供开奖查询入口；结束后返回首页，应用私有票图目录除 `.nomedia` 外为空，系统日志未发现应用崩溃。
- Android 9 真机已完成 CameraX 的“拍照 → 重拍 → 再拍 → 使用照片 → OCR 质量阻断”闭环。重拍后 `camera-captures` 立即为空，确认后的原始拍照缓存也被清理；无文字照片返回“图片质量不足”，没有进入 Fake 开奖流程，未发现应用级崩溃或 ANR。
- Android 使用随 APK 打包的中文识别模型，在断网设备上完成本次识别。
- iOS `iosApp` Scheme 已通过 Simulator 构建；使用个人开发者团队生成的 iPhone 真机包已完成签名、安装、证书信任和启动。应用启动后 `tmp/WinLotteryTicketImages` 为 0 个文件。
- iPhone 15 Pro 真机已完成“拍照 → 重拍 → 再拍 → 使用照片”核心交互，两次成片均可正常拍摄。流程结束后 WinLottery 进程仍在，`tmp/WinLotteryTicketImages` 和应用 `tmp` 下的 JPEG 均为 0 个文件，设备系统崩溃日志中没有 WinLottery 记录。
- 本轮未单独覆盖横竖屏切换、闪光灯三种模式、拒绝相机权限和真实票 OCR 结果，不能据此把这些分支记为通过。
- 当前 13 张探索图片的归一化尺寸均高于分辨率闸门，但样本没有低分辨率、模糊、过曝等成组真值，只能证明现有样本不会被尺寸规则误拒绝。
- macOS arm64 已使用打包后的 `.app` 和系统文件选择器完成真实探索样本导入冒烟；导入后明确展示桌面 OCR 未接入，临时票图在错误页出现前已清理，返回首页正常。
- macOS arm64 已额外执行 `verifyPackagedOnnxRuntime`：父启动器创建自身子进程并注入遥测禁用变量，子进程从最终分发目录加载 ONNX Runtime 1.29.0；全程无遥测警告或会话文件，CPU Provider 验证以状态 0 退出。该验证不包含 OCR 模型和图片推理。
- Windows 共用导入实现已通过 JVM 合成图片测试和 CI 构建配置覆盖，但尚未在 Windows 10/11 x64 干净系统完成系统选择器、安装包和连续导入验证。

## 未完成范围

- iOS AVFoundation 应用内相机的横竖屏、闪光灯模式和拒绝权限分支真机验收。
- 图片模糊、过曝、裁切、透视、阴影和反光质量检测。
- 彩票边缘检测、旋转、透视校正、增强候选图和号码区域二次识别。
- PP-OCRv5 三段 ONNX 的资源分发、加载前哈希校验、图片预处理、检测后处理、方向校正、CTC 解码、ZXing，以及 Windows 干净系统文件导入验证。
- Windows x64 最终分发启动器尚未执行同一父子进程验收；自构建 `--no_telemetry` 制品仍可作为后续纵深防护评估项。
- 当前工作进程只支持运行时健康检查，尚未定义模型 Session 生命周期、图片推理请求、受控临时路径校验、OCR 结果 IPC 和进程异常恢复。
- ONNX Runtime 目标平台裁剪、正式许可证归档、公证和卸载验证。
- 原图与低置信度字段对照、号码编辑器和人工确认，属于 B4。
- OCR 置信度平台校准、准确率统计、四端差异评估和连续识别性能测试。

## 票样与验收差距

- 当前 13 张探索图中，12 张大乐透均为 V1 不支持的多期或补打票；只有 1 张双色球单期票。
- 每种彩票未达到 30 张不同物理票、每张 4 种拍摄条件和至少 3 个计划省份的 B3 门槛。
- 未建立逐 token 双人真值，因此不报告彩种、期号或号码准确率。
- 当前白名单为空，不能据此承诺任何自动识别范围。

## 本轮构建基线

- 完整命令 `spotlessCheck jvmTest testAndroidHostTest iosSimulatorArm64Test :androidApp:assembleDebug :desktopApp:packageDmg :desktopApp:verifyPackagedOnnxRuntime :composeApp:linkDebugFrameworkIosSimulatorArm64 --rerun-tasks --console=plain` 执行成功，共 193 个 Gradle 任务。
- 自动化测试按当前 Gradle 模块统计共 356 项：JVM 124 项、Android Host 120 项、iOS Simulator 112 项；失败、错误和跳过均为 0。模型锁新增 2 项 JVM 测试。
- Xcode `iosApp` Scheme 的 iOS Simulator Debug 构建成功；iPhone Debug 包完成自动签名、安装和启动。
- Android Debug APK：61,998,602 字节，SHA-256 `2bdcf8526425a460d1ad7d1dab2846f9940cf8452767548aa4d6dfc32df17efa`。
- macOS DMG：138,120,605 字节，SHA-256 `3cbc5009471b887a90ac7ffb12990e4fb1cad8c96be8d6aa5b8f7287fdae4edf`。
- iOS Simulator Debug Framework 主二进制：283,351,360 字节，SHA-256 `c8e8c97e468569572b5bae8518cf8b9432bc9e6348d09e57a3ba99bab6ad7084`。
- ONNX Runtime Maven 原始 JAR：54,400,660 字节，SHA-256 `5933bbc0c6c4d89afc04bf7c11011de23a947509c1352e5bf5936e62a84e4d10`；Compose 分发目录中的重打包 JAR 为 55,528,543 字节，本轮 SHA-256 `1036faa5e3d5fd37dafc68cc0a7bfefbdbbebafb03c1d6afc86fd8f45ef2f389`。
- 分发 `recognition` JAR 已包含 4,744 字节的 `model-lock.json`，但不包含 `.onnx` 或字符字典；这与当前“锁定转换已完成、模型分发未启用”的能力边界一致。
- 相比接入前 82,687,210 字节的 DMG，本轮增加 55,433,395 字节，约 55.4 MB；主要风险是官方全平台 JAR 尚未裁剪，不能作为最终发布包体积。
- 以上均为 2026-08-14 开发构建基线，包含调试符号或运行时，不能作为发布包体积承诺。

## 下一步

1. 补测 iOS 真机相机的横竖屏、闪光灯三种模式和拒绝权限分支，并用非票面图片确认 OCR 质量阻断。
2. 补齐大乐透单期基本票、单期追加票及当前双色球版式样本，并建立脱敏真值台账。
3. 在现有分辨率闸门上建立可量化的模糊、曝光、阴影、反光、透视和裁切检测，并完成预处理候选对比。
4. 将锁定模型接入分发资源，加载前校验字节数和 SHA-256；在独立工作进程中实现受控图片请求、预处理、三段推理、后处理和 OCR 结果 IPC。
5. 对 ONNX Runtime、模型和字典做目标平台裁剪与许可证归档，在 Windows 10/11 x64 执行同一无遥测分发验收，并在两端干净系统验证打包及连续识别。

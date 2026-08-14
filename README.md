# 彩票中奖测算工具

基于 Kotlin Multiplatform 和 Compose Multiplatform 的跨平台项目。V1 优先交付 Android 和 iOS，Windows、macOS 保留已完成的工程与 OCR 底座，延期到 V1.1 或后续版本。

## 开发推进进度

更新日期：2026-08-14。当前主开发阶段为 **移动端 B4 票面解析与人工校正**。

| 批次 | 状态 | 已完成 | 未完成或验收差距 |
|---|---|---|---|
| B0 需求与票样基线 | 进行中 | 产品边界、技术方案、支持门槛和隐私规则已固化；已完成 13 张真实图片的本地探索 | 样本数量、省份和拍摄条件不足；未建立双人真值，支持白名单保持为空 |
| B1 跨平台工程骨架 | 核心完成 | 建立 Android、iOS、Windows/macOS 宿主、共享模块、Compose 页面壳、CI 和开发构建链路 | 桌面发布验证延期，不阻塞移动 V1 |
| B2 开奖查询与规则引擎 | 核心完成，待验收 | 已实现体彩/福彩适配器、双源证据状态、两种彩票现行规则、倍数和大乐透追加计算 | 每种彩票 20 期正式对账、连续 6 个开奖窗口采样和数据授权仍未完成 |
| B3 移动图片采集与 OCR PoC | 进行中 | Android/iOS 拍照、系统选图、本地 OCR、分辨率闸门和保守解析已贯通并完成核心真机冒烟 | 图片几何与质量检测、OCR 真值、准确率统计和 iOS 相机分支补测未完成 |
| B4 移动票面解析与人工校正 | 进行中 | 已实现彩种/期号校正、逐注号码球、倍数步进、追加开关、金额推导、实时领域错误及 `ConfirmedTicket` 来源追踪；真实 OCR 主动阻断已移除 | 原图区域对照、低置信字段定位、解析候选值和足量真实票样验收未完成 |
| B5 移动核心端到端闭环 | 未开始 | 开奖仓库、规则引擎、移动采集 OCR 和真实人工确认已分别具备 | 尚未接入真实开奖仓库、中奖测算结果页及完整错误恢复 |
| B6 Android/iOS V1 发布 | 未开始 | 已建立移动端发布指标并具备 Android/iOS 签名和真机开发链路 | 盲测、性能、AAB/IPA、隐私材料、商店合规和数据授权未完成 |
| V1.1+ Windows/macOS | 延期 | 已完成桌面宿主、导图、无遥测 ONNX 工作进程、PP-OCRv5 来源锁和可复现转换 | 真实桌面 OCR、ZXing、Windows 实机、目标平台裁剪和正式分发均不属于移动 V1 闸门 |

当前自动化基线共 383 项：JVM 133 项、Android Host 129 项、iOS Simulator 121 项，失败、错误和跳过均为 0。Android 和 iOS 核心相机流程已通过真机冒烟；真实图片现在可以进入人工校正并生成通过领域校验的 `ConfirmedTicket`。开奖查询仍使用明确标注的固定演示数据，尚不能用于真实中奖判断；确认或退出后会清理应用私有临时图片。

> 当前版本不能用于真实中奖判断，也不能解释为支持任何省份、销售终端或票面版式。B0、B2、移动 B3 均未通过发布验收闸门。

桌面端已完成 ONNX Runtime 底座、独立本地工作进程，以及 PP-OCRv5 三段官方 Paddle 模型的来源锁定、opset 17 转换和 Paddle/ONNX 合成张量逐元素对比。转换后的约 22.3 MB 模型二进制尚未纳入仓库或安装包，图片预处理、后处理和工作进程推理协议也未实现；这些成果作为 V1.1 或后续版本基础保留，不再阻塞 Android/iOS V1。

开发基线文档：

- [产品与技术方案](docs/01-product-and-architecture.md)
- [开奖数据与中奖测算规则](docs/02-draw-data-and-rules.md)
- [分批开发与验收计划](docs/03-delivery-plan.md)
- [架构决策记录](docs/04-decisions.md)
- [B1 四端工程骨架交付记录](docs/delivery/B1-delivery.md)
- [B2 开奖查询与规则引擎交付记录](docs/delivery/B2-delivery.md)
- [B3 图片采集与 OCR PoC 进展记录](docs/delivery/B3-progress.md)
- [B4 移动端人工校正进展记录](docs/delivery/B4-progress.md)
- [首批真实票样探索记录](docs/research/real-ticket-sample-exploration-2026-08-13.md)

项目首版定位是“纸质彩票中奖测算”，不提供彩票真伪、撤单状态或是否已兑奖的官方核验。

## 模块

```text
:androidApp / :desktopApp / :iosApp
                 ↓
             :composeApp
              ↙       ↘
  :shared:recognition  :shared:data
              ↘       ↙
           :shared:domain
```

## 本地运行

- Android 调试包：`./gradlew :androidApp:assembleDebug`
- macOS 桌面应用：`./gradlew :desktopApp:run`
- 桌面分发 ONNX 验收：`./gradlew :desktopApp:verifyPackagedOnnxRuntime`
- Desktop OCR 模型锁校验：`python3 tools/desktop-ocr/convert_ppocrv5.py --verify-lock-only`
- Desktop OCR 模型转换：`tools/desktop-ocr/convert_ppocrv5.sh`
- JVM 共享测试：`./gradlew jvmTest`
- 完整共享测试矩阵：`./gradlew jvmTest testAndroidHostTest iosSimulatorArm64Test`
- 格式检查：`./gradlew spotlessCheck`
- iOS Simulator：用 Xcode 打开 `iosApp/iosApp.xcodeproj`，构建 `iosApp` Scheme
- iOS 真机：参考 `iosApp/Configuration/Local.xcconfig.example` 创建不提交 Git 的 `Local.xcconfig`，填写自己的 `DEVELOPMENT_TEAM_ID` 后构建 `iosApp` Scheme

B0 票样白名单尚未完成。当前已收到 13 张探索图片，并据此实现了共享保守票面解析器、Android/iOS 本地 OCR PoC 及 Windows/macOS 图片导入 PoC。样本数量、彩种分布、票型和省份覆盖均未达到白名单门槛，因此不承诺自动票面解析范围。

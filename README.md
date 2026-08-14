# 彩票中奖测算工具

基于 Kotlin Multiplatform 和 Compose Multiplatform 的 Android、iOS、Windows、macOS 跨平台项目。

## 开发推进进度

更新日期：2026-08-14。当前开发阶段为 **B3 图片采集与 OCR PoC**。

| 批次 | 状态 | 已完成 | 未完成或验收差距 |
|---|---|---|---|
| B0 需求与票样基线 | 进行中 | 产品边界、技术方案、支持门槛和隐私规则已固化；已完成 13 张真实图片的本地探索 | 样本数量、省份和拍摄条件不足；未建立双人真值，支持白名单保持为空 |
| B1 四端工程骨架 | 核心完成 | 建立 Android、iOS、Windows/macOS 宿主、共享模块、Compose 页面壳、CI 和开发构建链路 | Windows MSI 仍待远端 runner 实际构建和干净系统验证 |
| B2 开奖查询与规则引擎 | 核心完成，待验收 | 已实现体彩/福彩适配器、双源证据状态、两种彩票现行规则、倍数和大乐透追加计算 | 每种彩票 20 期正式对账、连续 6 个开奖窗口采样和数据授权仍未完成 |
| B3 图片采集与 OCR PoC | 进行中 | Android/iOS 拍照、系统选图、本地 OCR 和保守解析已贯通；桌面导图、ONNX Runtime 1.29.0 底座及 macOS 无遥测工作进程分发验收已完成 | 桌面 PP-OCR/ZXing、Windows 工作进程实机验收、完整图片质量与几何校正、准确率统计和干净系统连续识别未完成 |
| B4 票面解析与人工校正 | 未开始 | 已有保守解析器和真实识别阻断闸门作为前置验证 | 原图字段对照、号码编辑器、倍数/追加校正和人工确认未实现 |
| B5 核心端到端闭环 | 未开始 | 开奖仓库、规则引擎与采集 PoC 已分别具备 | 尚未串联真实确认、开奖查询、中奖测算和结果页 |
| B6 稳定性与 V1 发布 | 未开始 | 已建立发布指标和分批验收标准 | 盲测、性能、四端安装包、签名公证、隐私材料和数据授权未完成 |

当前自动化基线按现有 Gradle 模块统计为 354 项：JVM 122 项、Android Host 120 项、iOS Simulator 112 项，失败、错误和跳过均为 0。Android 和 iOS 核心相机流程已通过真机冒烟；真实图片不会进入 Fake 开奖流程，分析结束后会清理应用私有临时副本。

> 当前版本不能用于真实中奖判断，也不能解释为支持任何省份、销售终端或票面版式。B2、B3 均未通过验收闸门。

桌面端当前只完成 ONNX Runtime 依赖、CPU Provider 探针和独立本地工作进程，尚未包含 PP-OCR 模型或执行真实 OCR。普通 UI 主进程不加载 ONNX；macOS 分发启动器已验证由父进程注入 `ORT_DISABLE_TELEMETRY=1` 后再启动工作进程，Windows 仍待同等实机验收，因此真实桌面 OCR 继续保持明确阻断。

开发基线文档：

- [产品与技术方案](docs/01-product-and-architecture.md)
- [开奖数据与中奖测算规则](docs/02-draw-data-and-rules.md)
- [分批开发与验收计划](docs/03-delivery-plan.md)
- [架构决策记录](docs/04-decisions.md)
- [B1 四端工程骨架交付记录](docs/delivery/B1-delivery.md)
- [B2 开奖查询与规则引擎交付记录](docs/delivery/B2-delivery.md)
- [B3 图片采集与 OCR PoC 进展记录](docs/delivery/B3-progress.md)
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
- JVM 共享测试：`./gradlew jvmTest`
- 完整共享测试矩阵：`./gradlew jvmTest testAndroidHostTest iosSimulatorArm64Test`
- 格式检查：`./gradlew spotlessCheck`
- iOS Simulator：用 Xcode 打开 `iosApp/iosApp.xcodeproj`，构建 `iosApp` Scheme
- iOS 真机：参考 `iosApp/Configuration/Local.xcconfig.example` 创建不提交 Git 的 `Local.xcconfig`，填写自己的 `DEVELOPMENT_TEAM_ID` 后构建 `iosApp` Scheme

B0 票样白名单尚未完成。当前已收到 13 张探索图片，并据此实现了共享保守票面解析器、Android/iOS 本地 OCR PoC 及 Windows/macOS 图片导入 PoC。样本数量、彩种分布、票型和省份覆盖均未达到白名单门槛，因此不承诺自动票面解析范围。

# B1 四端工程骨架交付记录

记录日期：2026-08-13

## 已实现范围

- 建立 `:composeApp`、`:shared:domain`、`:shared:data`、`:shared:recognition` 的物理模块和单向依赖。
- 保留 Android、iOS、Desktop 四端宿主入口；Desktop 同一 JVM 目标用于 Windows 和 macOS。
- 配置 Ktor Core、Android/Desktop OkHttp、iOS Darwin、kotlinx.serialization 和 Coroutines。
- 定义不可变票据、统一开奖模型、测算结果、领域校验状态和金额分单位约束。
- 定义 `ImageAcquirer`、`TicketRecognizer`、`TicketParser`、`DrawRepository`、`PrizeCalculator` 和 `AppPaths` 小接口。
- 建立仅用于开发的 Fake 能力，演示采集、本地分析、人工确认、单期查询和错误恢复状态流。
- 建立共享主题、响应式页面壳、首页、分析页、确认页壳、查询页、演示完成页、错误页和关于隐私页。
- Android 关闭自动备份；临时图片在确认、取消或重新开始时清理；任何页面都不把未知状态解释为未中奖。
- 建立 Spotless 格式检查、领域/契约/状态流测试，以及 macOS、Windows CI 构建矩阵。

## 未实现范围

- B0 真实票样台账、支持白名单和双人复核真值尚无输入。
- 尚未接入真实相机、系统图片选择器、图片质量检测或任何平台 OCR。
- 尚未实现体彩、福彩适配器和官网数据状态交叉验证。
- 尚未实现 `DLT_2026_01`、`SSQ_2026_01` 规则计算和确定金额展示。
- 确认页当前只验证闸门和领域对象生成，号码编辑器与原图定位属于 B4。
- 未完成签名、公证、真机矩阵、干净系统或安装包体积验证。

## 自动化测试

- 领域测试覆盖期号格式、号码数量/范围/唯一性、倍数、多期、双色球追加和票面金额严格一致。
- 数据契约测试保证 `FINAL_NUMBERS` / `FINAL_PAYOUT` 不会误入不可用分支。
- 平台 Fake 测试覆盖桌面相机阻断、轻量图片引用和 Fake OCR 明示。
- 状态流测试保证分析完成后停在人工确认页，确认前不开启开奖查询，退出后清除流程状态。
- Desktop JVM、Android Host 和 iOS Simulator arm64 测试任务均已在本机执行通过。

## 真机与桌面验证矩阵

| 平台 | 本批验证 | 状态 |
|---|---|---|
| Android | 本机 Gradle 编译与 APK 构建 | 通过，已生成 Debug APK |
| iOS Simulator arm64 | Kotlin Framework 与 Xcode Scheme 构建 | 通过，最低版本暂定 iOS 18.5 |
| macOS arm64 | JVM 测试、应用编译、界面流程实测与 DMG | 通过 |
| Windows x64 | GitHub Actions MSI 构建 | CI 配置完成，待远端执行 |

## 票样与盲测

- 当前票样数量：0。
- 当前白名单：空。
- B1 不依赖真实票样；B4 自动票面解析仍被 B0 闸门阻断。

## 已知风险

- 官网内部 JSON 接口尚无书面授权或 SLA，B2 只能低频、用户触发地验证。
- 目标省份和真实票样未确定，不能承诺任何省份或终端版式。
- Desktop PP-OCR 模型转换、包体和干净系统加载尚需 B3 PoC。
- Windows 构建只能在 Windows runner 完成，本机 macOS 无法直接验证 MSI。

## 下一批输入条件

- B2 确定性开奖查询与规则引擎可以开始，不依赖 B0 票样。
- B3 通用 OCR PoC 可以准备模型和平台采集，但准确率验收依赖票样。
- B4 不可开始发布实现；需先指定至少 3 个计划覆盖省份，并为每种彩票取得至少 20 张不同物理票。

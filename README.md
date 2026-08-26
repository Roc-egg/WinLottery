# 给我中

<div align="center">
  <p><strong>基于 Kotlin Multiplatform 的纸质彩票识别与中奖测算工具</strong></p>
  <p>Android 与 iOS 共享业务规则、数据访问、结构化存储和 Compose Multiplatform 界面。</p>
  <p>
    <img alt="当前版本" src="https://img.shields.io/badge/version-1.2.0-2f7d32">
    <img alt="项目状态" src="https://img.shields.io/badge/status-V1.3%20closing-d97706">
    <img alt="技术架构" src="https://img.shields.io/badge/Kotlin-Multiplatform-7f52ff">
    <img alt="目标平台" src="https://img.shields.io/badge/platform-Android%20%7C%20iOS-1565c0">
    <a href="LICENSE"><img alt="许可协议" src="https://img.shields.io/badge/license-Apache%202.0-1565c0"></a>
  </p>
</div>

> [!WARNING]
> **免责声明与开奖数据合规提示**
>
> - **结果与责任**：本项目用于学习、研究和辅助核对，不构成购彩、投资或收益建议。识别、随机选号和中奖测算结果仅供参考，最终结果以彩票发行机构的官方信息及实体彩票兑奖结果为准。本项目不核验彩票真伪、撤单或兑奖状态，也不提供代购、自动购彩或收益承诺。软件按“原样”提供；在适用法律允许的最大范围内，作者不对因使用或无法使用本项目而产生的损失承担责任。请理性参与彩票活动。
> - **当前数据源**：大乐透使用中国体育彩票官网前端内部接口 `webapi.sporttery.cn/gateway/lottery/getHistoryPageListV1.qry` 和 `getDigitalDrawInfoV1.qry`，历史期辅助核对还会读取 `pdf.sporttery.cn` 的官方开奖公告；双色球使用中国福利彩票官网前端内部接口 `www.cwl.gov.cn/cwl_admin/front/cwlkj/search/kjxx/findDrawNotice` 和 `findKjxx/forIssue`。这些接口不是面向第三方承诺服务的开放 API，完整参数与校验方式见[开奖数据与规则](docs/02-draw-data-and-rules.md)。
> - **数据使用边界**：期号、开奖号码、开奖日期、奖池和奖级金额是官方向社会公布的开奖事实。当前接口可以公开访问，但不是承诺向第三方长期提供的开放 API，也没有公开的 API Key、SLA 或字段兼容保证。当前开源项目只在个人设备上由用户操作触发精确期号查询，或读取最新一期向前最多 500 期的会话数据；不运行集中式采集服务、不后台轮询、不内置或分发历史开奖数据库，官网原始响应不落盘。
> - **发布与处置要求**：开源代码和个人自用不等于对商店上架、集中式数据服务或商业运营作出合规承诺。未来若转向高频批量采集、历史数据镜像、付费数据服务或应用市场公开运营，应重新核对官网条款、访问限制和目标地区要求，必要时联系中国体育彩票 `95086`、中国福利彩票 `954168` 或更换有明确服务协议的数据源。若上游明确禁止自动访问或返回访问限制，不得绕过控制，应停止对应联网能力并引导用户前往官网核验。

## 项目总进度

更新日期：2026-08-26。当前稳定版本为 `1.2.0 (4)`，V1、V1.1 与 V1.2 已完成关闭；V1.3 的开源个人自用范围已冻结，正在完成版本升级与最终覆盖验收。

| 状态 | 版本数量 | 当前范围 |
|---|---:|---|
| **已完成** | **3** | V1、V1.1、V1.2 |
| **进行中** | **1** | V1.3 |
| **未完成** | **2** | V1.4、V1.5 |

版本数量只表示路线节点，不代表各版本工作量相同，也不作为完成度百分比。

## 版本路线

| 版本 | 状态 | 目标 | 当前结论 |
|---|---|---|---|
| V1 | **已完成** | Android/iOS 纸质彩票识别与中奖测算闭环 | 已按个人自用内部基线关闭 |
| V1.1 | **已完成** | Room 3 本地记录、二次查询、导入导出与跨端迁移 | 已按固定双虚拟机基线关闭 |
| V1.2 | **已完成** | 大乐透、双色球合法随机号码，支持期数和每期注数 | 响应式 UI、双端 Release 与覆盖升级验收均已关闭 |
| V1.3 | **收口中** | 跨平台真实走势图和可回测数学研究 | 功能与专项已完成；个人自用边界已冻结，等待版本升级验收 |
| V1.4 | **未完成** | 基于 V1.3 历史数据的大模型分析 | 依赖 V1.3，待完成 AI 供应商与合规设计 |
| V1.5 | **未完成** | 传统文化娱乐推演 | 概念规划，待隐私与合规专项评审 |

### 当前状态

V1.3 的功能范围已经完成。Android/iOS 共用的基本走势以官网最新已发布期为锚点提供 `50 / 80 / 120 / 300 / 500` 期真实样本；数学研究使用固定策略、50 期训练窗口和 450 期时间前推回测，并如实对比精确均匀随机基线。ADR-024 已明确：公开开奖事实的用户触发、低频、当前会话使用不阻止开源个人自用版本关闭；官网接口没有开放 API 与 SLA 只作为稳定性和未来运营边界。长期历史数据库不属于当前需求，后续确有需要时再以独立实体和迁移方案立项。

## 已完成能力

| 能力域 | 已完成内容 |
|---|---|
| 票据输入 | Android/iOS 拍照、系统选图、无图手动录入、原图对照和人工校正 |
| 本地识别 | PP-OCRv5 三段 ONNX 流水线、图片质量检查、保守票面解析和失败关闭 |
| 中奖测算 | 大乐透、双色球；单期与受控连续多期；多注单式、倍数和大乐透追加 |
| 开奖证据 | 按精确期号查询、双官方数据面核对、历史官方 PDF 证据和内容冲突阻断 |
| 记录管理 | Room 3 跨平台结构化存储、增删清空、二次查询和数据库显式迁移 |
| 数据迁移 | Android/iOS 系统文件选择器、规范化 JSON 导入导出、SHA-256 校验和冲突处理 |
| 随机选号 | 大乐透、双色球合法随机号码；`1..20` 期、每期 `1..10` 注、最多 `200` 注及受控跨期模式 |
| 历史走势 | 大乐透前后区、双色球红蓝球；官方最新期向前 `50 / 80 / 120 / 300 / 500` 期及遗漏统计 |
| 数学研究 | 固定频次遗漏策略、下一期候选、450 期样本外前推回测、精确随机基线与责任说明 |
| 工程交付 | KMP 独立宿主与 `:shared` 架构、CI、R8 Release、签名 APK/AAB 和双虚拟机回归 |

## 支持范围

| 当前支持 | 当前不支持或未承诺 |
|---|---|
| 大乐透 `26014` 起、双色球 `2026014` 起 | 更早历史规则 |
| 1 至 20 期连续票 | 超过 20 期或跨年度未知期号序列 |
| 单式、多注单式、倍数、大乐透追加 | 复式、胆拖及未知票型 |
| Android 与 iOS 个人自用 | 应用市场公开分发、完整真机矩阵和商用数据授权 |
| Windows/macOS 工程与 OCR 底座 | 桌面真实 OCR、目标平台裁剪、签名和正式分发 |

自动识别结果始终需要用户确认。任何期号、号码、金额、开奖证据或规则状态不完整时，应用不会输出确定的“未中奖”结论。

## 技术架构

项目采用 JetBrains 当前共享 UI 项目结构。Android、Desktop 与 iOS 使用独立宿主，共享 UI 和应用装配位于 `:shared`；业务模块保持单向依赖，领域层不依赖 Compose、网络、数据库或 OCR。

```text
:androidApp     :desktopApp      iosApp（Xcode）
      └──────────────┼──────────────┘
                     ↓
                  :shared
        ┌────────────┼────────────┐
        ↓            ↓            ↓
:shared:data  :shared:persistence  :shared:recognition
        └────────────┼────────────┘
                     ↓
              :shared:domain
```

`iosApp` 通过静态 `Shared.framework` 接入共享代码，不属于 Gradle 项目。`:desktopApp` 为独立 ONNX Runtime 工作进程额外直接依赖 `:shared:recognition`。

### 技术栈

| 层级 | 主要技术 |
|---|---|
| 跨平台 | Kotlin Multiplatform、Compose Multiplatform、Coroutines、kotlinx.serialization |
| Android | Android Application、CameraX、ONNX Runtime、R8 |
| iOS | SwiftUI 宿主、Kotlin/Native、AVFoundation、ONNX Runtime Swift |
| 数据访问 | Ktor、OkHttp、Darwin、官方 JSON/PDF 数据适配 |
| 本地存储 | Room 3、SQLite Bundled、规范化 JSON 逻辑包 |
| 质量保障 | Kotlin Test、Android Instrumentation、iOS Simulator、Spotless、GitHub Actions |

## 验证状态

当前最新验收基线已经完成：

- V1.3 的 `spotlessCheck`、共享领域/JVM 测试、Android 与 iOS Simulator ARM64 编译通过；生产候选和 450 期前推回测复用同一策略实现。
- Android R8 Release APK/AAB、lint、签名和压缩结构通过，173 个任务全量执行成功；iOS Simulator Release 为 `BUILD SUCCEEDED`，两端均以非 Debug 进程启动。
- 固定 Android Emulator 与 iOS Simulator 的标准字号专项通过，覆盖双彩种五档真实走势、横屏 `01..35` 完整矩阵、数学候选、精确随机基线、责任说明和一级页面往返状态保留。
- 验收时官网最新期为大乐透 `26096`、双色球 `2026098`；500 期大乐透样本为 `23049..26096`，没有使用静态演示期号。
- V1 至 V1.2 的最大字号、`200` 注边界、双色球逐期独立、V1.1 记录往返、横竖屏与固定虚拟机回归结论继续保留。
- `1.1.0 (3)` 至 `1.2.0 (4)` 的双端 Release 覆盖升级验证通过，真实 Room 记录与匿名数据完整保留。

上述结果不代表生产 IPA、应用商店上架、所有真机型号、运营商网络或公开分发合规已经完成。

## 本地构建

```bash
# Android 调试包
./gradlew :androidApp:assembleDebug

# macOS/Windows 桌面开发入口
./gradlew :desktopApp:run

# 共享测试矩阵
./gradlew spotlessCheck jvmTest testAndroidHostTest iosSimulatorArm64Test

# 仓库锁定的 Android Emulator 与 iOS Simulator 回归
./tools/mobile/run-current-vm-tests.sh
```

iOS 使用 Xcode 打开 `iosApp/iosApp.xcodeproj`，构建 `iosApp` Scheme。固定虚拟机脚本会校验精确设备身份，目标不匹配时直接停止，不会回退到其他 ADB 或 iOS 设备。

## 项目文档

| 文档 | 内容 |
|---|---|
| [产品与技术方案](docs/01-product-and-architecture.md) | 产品边界、用户流程、KMP 架构和安全策略 |
| [开奖数据与规则](docs/02-draw-data-and-rules.md) | 官方数据证据、规则版本和失败关闭条件 |
| [交付与验收计划](docs/03-delivery-plan.md) | 批次范围、测试分层和验收标准 |
| [架构决策记录](docs/04-decisions.md) | 已冻结的产品与技术决策 |
| [V1 至 V1.5 路线图](docs/05-version-roadmap.md) | 各版本目标、前置条件和完成状态 |
| [V1.1 交付记录](docs/delivery/V1.1-progress.md) | Room 3、记录管理、跨端迁移和关闭证据 |
| [V1.2 交付记录](docs/delivery/V1.2-progress.md) | 随机选号、响应式 UI、覆盖升级和关闭证据 |
| [V1.3 交付记录](docs/delivery/V1.3-progress.md) | 真实走势图、数学研究、双虚拟机证据和个人自用收口 |

详细修复过程、逐次验收记录和研究材料保留在 `docs/`，不再堆叠到项目主页。

## 开源许可

本项目采用 [Apache License 2.0](LICENSE) 开源。该协议允许个人及商业使用、修改和分发，也提供明确的专利授权；使用者需要遵守协议中的版权、许可声明和变更说明要求。

- **二次开发与分发**：请保留完整的 `LICENSE`、版权声明及 [NOTICE](NOTICE) 中的归属信息，并按协议要求说明所做修改。
- **许可边界**：Apache License 2.0 仅覆盖本仓库有权许可的源代码，不授予体彩、福彩接口及开奖数据、第三方依赖、模型、名称或商标的使用权；这些内容继续适用各自的授权和规则。

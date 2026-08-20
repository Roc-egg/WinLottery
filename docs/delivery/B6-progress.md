# B6 Android/iOS V1 发布进展记录

记录日期：2026-08-20

## 状态结论

B6 已开始，但远未通过 V1 发布验收。当前增量只固化 Android/iOS Release 配置在指定双虚拟机上的可重复构建、非 Debug 属性、覆盖安装和启动检查，不能替代物理真机矩阵、正式生产签名、商店上传、真实票样盲测、性能、隐私材料或数据授权验收。

当前执行目标严格锁定为 Android `Medium_Phone_API_36.1`（`emulator-5554`）和 iPhone 17 Pro / iOS 26.5 Simulator（`E6D04C31-9EB8-4696-990F-DA54D0112E13`）。共享守卫会核对 Android 模拟器标志与 AVD 名称，以及 iOS Simulator 的 UDID、名称、启动状态和运行时；任一目标不匹配时立即失败，不回退到其他 ADB 或 Apple 设备。

## 已完成范围

- 新增 `tools/mobile/current-vm-guard.sh`，由普通回归和 Release 专项共同复用精确设备身份校验。
- `tools/mobile/run-current-vm-tests.sh` 只向 `emulator-5554` 导出 `ANDROID_SERIAL`，并只向锁定的 iOS Simulator 运行测试。
- 新增 `tools/mobile/run-current-vm-release-checks.sh`，执行格式检查、Android Release APK/AAB 构建、iOS `Release-iphonesimulator` 构建、产物检查、覆盖安装和进程存活检查。
- Android 未签名 Release APK 仅在临时目录中使用本机调试证书签名，以便安装到指定模拟器；该临时签名 APK 在脚本退出时删除，不是发布产物。
- Android APK 的清单和模拟器运行时包标记均不含 `DEBUGGABLE`。脚本同时传入与 B5 反向验收相同的 Debug 专项参数，但本专项只检查包属性、启动和进程存活；装饰器功能禁用结论仍以 B5 的完整交互记录为准。
- Android AAB 完成非空和 ZIP 完整性检查，并自动识别 JAR 签名记录；当前工程没有配置生产签名，生成的 AAB 未签名。
- iOS Simulator Release 可执行文件为 arm64，应用签名完整，未发现 `get-task-allow=true`；产物覆盖安装并成功启动。
- iOS 结果只代表 Simulator 本地签名 `.app`，不是归档导出的 IPA，也不是 App Store 发布签名。
- 临时验收 APK 和 Xcode DerivedData 均由退出钩子清理，不写入仓库。
- Android recognition 新增设备测试源集，在真实应用缓存中验证路径管理器重新初始化时清除临时票图和 CameraX 原始成片；iOS Simulator 同步在真实临时目录验证上次流程遗留票图清扫。
- 两端删除接口均验证只接受临时票图目录直属文件，目录外受控文件会被拒绝且保持存在；测试内容不包含真实彩票或个人信息。

## 本轮验证记录

- 当前双虚拟机安全回归通过：iOS Simulator 测试和 Android 数据层设备测试均成功，Android 为 66/66；101 个 Gradle 任务实际执行并全部成功。
- Android Release 构建执行 145 个 Gradle 任务并全部成功。
- 临时验收签名 Android Release APK 为 59,317,806 字节，SHA-256 为 `2f1c074dcae92410cf5c1ae00606fb4123bd9809866321749f9326bc55b5e28e`；覆盖安装、冷启动和进程存活检查通过。
- Android Release AAB 为 34,272,214 字节，SHA-256 为 `ae57d147e9e5123c02070d54cca01d2b2afcde072b649d7d4a61418da6ce9391`；ZIP 结构完整，但没有签名，不能作为可上架 AAB。
- iOS `Release-iphonesimulator` 应用为 40,668 KiB；arm64、签名完整性、非调试附加、覆盖安装和启动检查通过。
- 三个 Shell 脚本均通过 Zsh 语法检查，本增量通过 `git diff --check`。
- 临时图片清扫增量复跑后，iOS Simulator 为 230/230；Android 数据层为 66/66、识别层为 59/59，合计 125 项 Android 设备测试；失败、错误和跳过均为 0，140 个 Gradle 任务全部实际执行。
- 上述识别层 59 项包含 2 项新增 Android 平台文件测试；iOS 的 230 项包含 2 项新增平台文件测试。用例验证的是应用初始化时实际调用的文件清扫机制，不等同于完整的进程强杀、版本升级或卸载验收。

## 未完成范围

- Android 生产签名、密钥托管、签名轮换方案、Play App Signing 和 Play Console 上传验证未完成。
- iOS Archive、分发证书、Provisioning Profile、正式 IPA 导出、安装和 App Store Connect 上传验证未完成。
- Android/iPhone 物理真机矩阵未执行；Simulator 结果不能代替相机、内存、系统权限、安装升级和真实性能验收。
- 12MP 固定输入、重复采样入口及可信 P95 统计尚未建立；当前没有用单次模拟器耗时冒充产品性能指标。
- 每种彩票 150 至 200 张不同物理票、每张至少 3 种拍摄条件的盲测规模未达到，逐 token 双人真值和平台分别校准也未完成。
- 旋转、低内存、权限拒绝、网络抖动、完整进程强杀恢复、升级卸载及敏感临时图片残留仍未形成完整发布矩阵；当前只完成初始化清扫和目录边界的平台文件回归。
- B2 第二人独立真值复核、连续 6 个开奖窗口采样和官方数据商用授权未完成。
- 隐私政策、免责声明、应用商店隐私清单、截图文案、年龄与彩票相关政策核对尚未完成。

## 下一步

1. 在当前双虚拟机补齐不依赖实体硬件的权限拒绝、进程恢复、安装升级与卸载残留专项，并继续由精确设备守卫阻止误用其他设备。
2. 建立固定 12MP 输入和重复采样工具，先形成模拟器开发基线；最终 P95 仍必须在目标物理真机矩阵重新验收。
3. 准备 Android 生产签名和 iOS 分发配置，但不得把本机调试证书或 Simulator 签名记为发布签名。
4. 与 B0/B2/B3 并行补齐真实票样盲测、双人真值、开奖窗口、数据授权和隐私合规材料后，再执行完整发布闸门。

# B6 Android/iOS V1 发布进展记录

记录日期：2026-08-20

## 状态结论

B6 已开始，但远未通过 V1 发布验收。当前已固化 Android/iOS Release 配置在指定双虚拟机上的可重复构建、非 Debug 属性、覆盖安装、启动、初始化清扫、Android 相机首次拒权、受控进程终止、`SIGKILL` 异常终止恢复和卸载清除检查，不能替代物理真机矩阵、正式生产签名、跨版本升级、真实崩溃与低内存恢复、商店上传、真实票样盲测、性能、隐私材料或数据授权验收。

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
- Android 当前模拟器已使用非 Debug Release 包完成相机首次拒权：系统权限框选择“不允许”后返回应用内失败页，明确说明未获得相机权限，应用进程保持存活并可返回首页。
- iOS Simulator 不提供 AVFoundation 相机设备，Release 首页按能力隐藏拍照入口，只保留系统选图和手动录入；这只验证无相机能力降级，不能替代 iPhone 真机拒权。
- 新增显式的 `tools/mobile/run-current-vm-lifecycle-checks.sh`。默认 Release 检查不卸载应用；生命周期入口会明确清空两端当前测试应用数据，验证卸载清除匿名标记、全新安装不恢复旧标记，并在结束或失败清理时尽力恢复 Release 应用。
- Android 生命周期专项只用同 applicationId、同一本机测试证书的 Debug 包取得 `run-as` 测试权限并放置空标记，标记在全新安装且应用尚未启动时已经不存在；最终覆盖回非 Debug Release。iOS 则验证卸载后旧数据容器路径整体消失，新容器在应用启动前不含标记。
- 新增 `tools/mobile/run-current-vm-process-recovery-checks.sh`。Android 在已启动的 Debug 测试包私有缓存放置票图和 CameraX 成片空标记，`force-stop` 后确认进程消失，重启时验证两类标记均被初始化清扫，再恢复非 Debug Release；iOS Release 使用 `simctl terminate` 和重新启动验证同类清扫。
- 新增 `tools/mobile/run-current-vm-abrupt-termination-checks.sh`。Android 只在 Debug 测试包内用 `run-as` 放置空标记，并在 PID 为单个纯数字、进程名和 UID 均属于目标应用后，以应用自身 UID 发送 `SIGKILL`；iOS 只在 PID 为纯数字、命令路径属于指定 Simulator 的 Release 应用容器后发送 `SIGKILL`。两端重启后验证标记清扫，Android 最终恢复非 Debug Release，失败退出钩子也会尽力恢复 Release。

## 本轮验证记录

- 当前双虚拟机安全回归通过：iOS Simulator 测试和 Android 数据层设备测试均成功，Android 为 66/66；101 个 Gradle 任务实际执行并全部成功。
- Android Release 构建执行 145 个 Gradle 任务并全部成功。
- 临时验收签名 Android Release APK 为 59,317,806 字节，SHA-256 为 `2f1c074dcae92410cf5c1ae00606fb4123bd9809866321749f9326bc55b5e28e`；覆盖安装、冷启动和进程存活检查通过。
- Android Release AAB 为 34,272,214 字节，SHA-256 为 `ae57d147e9e5123c02070d54cca01d2b2afcde072b649d7d4a61418da6ce9391`；ZIP 结构完整，但没有签名，不能作为可上架 AAB。
- iOS `Release-iphonesimulator` 应用为 40,668 KiB；arm64、签名完整性、非调试附加、覆盖安装和启动检查通过。
- 六个移动 Shell 脚本均通过 Zsh 语法检查，本增量通过 `git diff --check`。
- 临时图片清扫增量复跑后，iOS Simulator 为 230/230；Android 数据层为 66/66、识别层为 59/59，合计 125 项 Android 设备测试；失败、错误和跳过均为 0，140 个 Gradle 任务全部实际执行。
- 上述识别层 59 项包含 2 项新增 Android 平台文件测试；iOS 的 230 项包含 2 项新增平台文件测试。用例验证的是应用初始化时实际调用的文件清扫机制，不等同于完整的进程强杀、版本升级或卸载验收。
- Android 权限专项先清空当前模拟器应用数据，从冷启动首页进入拍照并在系统原生权限框选择“不允许”；随后页面显示“无法读取图片”和“未获得相机权限，无法拍摄彩票”，主进程保持存活，返回首页入口可用。1080×2400 页面没有裁切或重叠，拒权发生在创建成片前。
- iOS Release 首页在 402×874 点的当前 Simulator 实图中没有拍照入口，导图与手动录入入口完整可见且无重叠。本轮临时截图和 Android 页面树均只保存在临时目录并已删除，Android 测试应用数据已再次清空后冷启动到首页。
- 安装生命周期专项在 Android 构建 Debug、Release APK 与 Release AAB，180 个 Gradle 任务全部实际执行；iOS `Release-iphonesimulator` 完整构建成功。两端卸载前匿名标记均确认存在，卸载后 Android 包消失、iOS 旧数据容器消失，全新安装后均在应用启动前确认旧标记不存在。
- 生命周期专项结束后 Android 恢复为非 Debug Release 并保持进程存活，iOS 恢复为 Release Simulator 应用并成功启动。随后单独复跑默认 Release 入口，145 个 Gradle 任务全部实际执行，脚本没有进入卸载分支。
- 当前源码重新构建的临时验收签名 Android Release APK 为 59,317,806 字节，SHA-256 为 `398be95acc618f29123508a310e90e2a6829d1f8fef77872a2784e0a47c5b636`；未签名 AAB 为 34,272,214 字节，SHA-256 为 `dea633b84e6d806c7b9f0c9c09d605070a4247a9f80724d029707412551a3316`。iOS Simulator `.app` 仍为 40,668 KiB。
- 受控进程恢复专项重新执行 Android Debug、Release APK 与 AAB 构建，180 个 Gradle 任务全部实际执行；iOS `Release-iphonesimulator` 完整构建成功。Android `force-stop` 后 PID 为空，重启后两类标记均不存在；iOS `terminate` 后重启，票图标记不存在。
- 进程恢复专项结束后 Android 恢复为非 Debug Release 且进程存活，iOS Release Simulator 应用成功启动；临时 DerivedData 已删除。该次 APK SHA-256 为 `86607aea794a7937544f52dada113719b849f70e7e6ba7efc550d4e95c044edb`，AAB SHA-256 为 `ff46ac595fc425edf907fe2f60f78a46d3ce865f86f3b1e37259cd02e72502c3`，大小保持不变。
- `SIGKILL` 异常终止专项重新执行 180 个 Android Gradle 任务并全部成功，iOS `Release-iphonesimulator` 完整构建成功。Android 主进程名和 UID、iOS Simulator 应用容器和进程命令路径均通过精确归属校验后才发送信号；两端原 PID 消失，重启后匿名标记均不存在。
- 异常终止专项结束后 Android 恢复为非 Debug Release 且进程存活，iOS Release Simulator 应用成功启动；临时 DerivedData 已删除。临时验收签名 APK 为 59,317,806 字节，SHA-256 为 `7d8214dbb6ab92e1ec69bacd84a6489d0120e40aaf0d566e57959aa4168ebeb1`；未签名 AAB 为 34,272,213 字节，SHA-256 为 `77e99eb84cd747f7c56d0fa3fac738a50b1c635704750121d82ab7231db299b3`；iOS Simulator `.app` 为 40,668 KiB。

## 未完成范围

- Android 生产签名、密钥托管、签名轮换方案、Play App Signing 和 Play Console 上传验证未完成。
- iOS Archive、分发证书、Provisioning Profile、正式 IPA 导出、安装和 App Store Connect 上传验证未完成。
- Android/iPhone 物理真机矩阵未执行；Simulator 结果不能代替相机、内存、系统权限、安装升级和真实性能验收。
- 12MP 固定输入、重复采样入口及可信 P95 统计尚未建立；当前没有用单次模拟器耗时冒充产品性能指标。
- 每种彩票 150 至 200 张不同物理票、每张至少 3 种拍摄条件的盲测规模未达到，逐 token 双人真值和平台分别校准也未完成。
- 旋转、低内存和网络抖动仍未形成完整发布矩阵；进程专项分别覆盖受控 `force-stop` / `terminate` 和已核验 PID 的 `SIGKILL` 后临时文件清扫，不覆盖未捕获异常、OOM、低内存系统回收或业务状态持久化。当前只完成同版本测试产物的覆盖安装及双虚拟机卸载清除，跨版本升级迁移、物理真机卸载和系统备份恢复边界未完成。权限专项只完成 Android 当前模拟器首次相机拒权，iPhone 真机拒权和系统设置恢复仍未完成。
- B2 第二人独立真值复核、连续 6 个开奖窗口采样和官方数据商用授权未完成。
- 隐私政策、免责声明、应用商店隐私清单、截图文案、年龄与彩票相关政策核对尚未完成。

## 下一步

1. 在当前双虚拟机继续补齐未捕获异常、低内存开发基线和跨版本升级专项，并继续由精确设备守卫阻止误用其他设备；iOS 相机拒权和物理真机卸载保留到真机矩阵。
2. 建立固定 12MP 输入和重复采样工具，先形成模拟器开发基线；最终 P95 仍必须在目标物理真机矩阵重新验收。
3. 准备 Android 生产签名和 iOS 分发配置，但不得把本机调试证书或 Simulator 签名记为发布签名。
4. 与 B0/B2/B3 并行补齐真实票样盲测、双人真值、开奖窗口、数据授权和隐私合规材料后，再执行完整发布闸门。

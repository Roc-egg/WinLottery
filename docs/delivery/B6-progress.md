# B6 Android/iOS V1 虚拟机验收进展记录

记录日期：2026-08-21

## 状态结论

根据 ADR-017，V1 公开发布整体延期，B6 当前双虚拟机开发验收范围已经完成。现已固化 Android/iOS Release 配置在指定双虚拟机上的可重复构建、非 Debug 属性、覆盖安装、启动、初始化清扫、Android 相机首次拒权、受控进程终止、`SIGKILL`、Debug 受控崩溃恢复、低内存开发回调边界、卸载清除、构建号 1 → 2 开发签名升级检查、固定 12MP 重复采样开发基线、横竖屏开发检查、受控网络抖动开发检查，以及最大辅助字号下的 V1 支持范围、手动录入可达性与退出状态清空检查。

当前结论只代表固定双虚拟机中的 V1 内部版本，不是公开发布验收。真机矩阵、正式生产签名、商店上传、真实相机硬件、运营商网络与 Release 物理设备性能不再是当前项目要求，也不得由虚拟机结果冒充完成；未来恢复公开发布时再单独重启对应计划。

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
- 新增 `tools/mobile/run-current-vm-crash-recovery-checks.sh`。Android 主宿主在临时路径初始化前调用按构建变体装配的验收函数：Debug 只在显式布尔 extra 下抛出固定匿名 `IllegalStateException`，Release 实现始终无操作且不包含触发常量；iOS 同步用 `#if DEBUG` 隔离显式环境变量触发的 `fatalError`。两端正常启动均不受影响。
- 崩溃专项会先用 Debug 包在真实私有临时目录放置空标记，再核对 Android crash buffer 的目标包名与固定异常标记、iOS `simctl --console` 的固定 fatal error 标记以及进程退出；无参数重启后验证标记清扫。Android Release 全部 DEX 与 iOS Release 可执行文件还必须不含崩溃参数和固定标记，最终安装 Release、传入相同参数并验证进程继续存活；失败退出钩子会尽力恢复 Release。
- 新增 `tools/mobile/run-current-vm-memory-pressure-checks.sh`。Android 主宿主将 `onTrimMemory` 转发给按构建变体装配的验收函数，Debug 只记录匿名级别，Release 实现始终无操作且 DEX 不得包含固定标记；专项使用系统 `am send-trim-memory` 发送 `RUNNING_CRITICAL` 并核对精确级别 15、原 PID 存活和 Release 反向隔离。
- iOS Debug 只在 `#if DEBUG` 内注册 `UIApplication.didReceiveMemoryWarningNotification` 观察者，收到回调后在应用数据容器 `tmp` 写空标记。专项用 LLDB 附加指定 Simulator 的已核验应用 PID 并调用 UIKit `_performMemoryWarning`，随后核对标记、原进程命令路径和 PID；Release 可执行文件必须不含标记并恢复运行。该调试语义不制造真实系统压力，不覆盖 OOM、Jetsam、物理真机或 Release 低内存恢复。
- Android `versionCode` 与 iOS `CURRENT_PROJECT_VERSION` 已从 1 提升到 2，两端展示版本同步从 `1.0` 提升到 `1.0.1`。
- 新增 `tools/mobile/run-current-vm-upgrade-checks.sh`，从提交 `7df5e68` 的干净归档重新构建双端构建号 1 Release 基线，再完整构建当前构建号 2，并只在锁定双虚拟机执行 Release 1 → Release 2 覆盖升级。
- Android 先用同 applicationId、同一本机测试证书的基线 Debug 包在私有目录放置匿名持久标记、临时票图和 CameraX 成片标记，再切换到非 Debug Release 1 并实际升级到 Release 2；当前 Debug 包只用于升级后读取私有标记。iOS 在基线 Release 数据容器放置匿名标记后直接覆盖安装当前 Release；容器 UUID 允许由 Simulator 更新，但升级后容器必须仍属于指定 Simulator，且数据必须真实保留。两端最终删除验收标记并恢复构建号 2 Release。
- 新增 `tools/mobile/run-current-vm-performance-checks.sh`，只在锁定双虚拟机生成相同的匿名 `3000×4000` JPEG，并对“已归一化私有 JPEG → 图片质量检查 → 平台本地 OCR → 共享保守解析”执行首次冷样本、2 次热身和 20 次统计采样；图片生成、系统选图与导入归一化不计时。
- Android 采样运行在设备 instrumentation 测试进程；iOS 因 Kotlin/Native 独立测试可执行进程在当前 Simulator 中无法取得 Vision 文字结果，改在 Debug 应用进程内通过显式环境变量启动，并复用应用实际装配的质量检查、Vision 和解析器。相同参数不能启用 iOS Release，脚本退出时会删除测试 JPEG、测试包和 DerivedData，并恢复 Release 应用。
- 匿名报告只包含平台、输入尺寸、次数、各阶段耗时和解析分类，不输出图片标识、路径、OCR 文本、号码或金额。当前不设置 3 秒自动门槛，因为 Debug/测试进程和虚拟机不能代表 Release 物理真机 P95。
- 新增 `tools/mobile/run-current-vm-rotation-checks.sh`，先复用完整 Release 构建、产物、安装和启动闸门，再通过本机 Appium 服务按 `emulator-5554` 与固定 iOS UDID 分别创建会话；即使 ADB 同时存在其他设备，也不会省略 UDID 或回退选择。
- 旋转专项依次切换横屏和竖屏，核对驱动系统方向、应用截图宽高、首页导图与手动录入入口、Android 包进程和 iOS Simulator 应用进程。横屏首屏高度不足时会执行一次真实触摸滚动再复核入口；成功、失败和退出路径都会关闭会话、恢复竖屏并删除临时截图与 Appium 日志。
- 新增 `tools/mobile/run-current-vm-network-jitter-checks.sh` 和只监听 `127.0.0.1` 的受控 Node 服务。服务按匿名会话分别制造首次 503、首次连接中断和固定 800 ms 延迟；Android 只通过 `adb -s emulator-5554` 安装和执行独立设备测试 APK，iOS 只通过固定 UDID 的 `simctl spawn` 启动测试二进制，不枚举或操作其他设备。
- 两端测试均使用正式 `createPlatformHttpClient()` 配置和实际平台引擎，断言 503 与连接中断各恰好发出 2 次请求、延迟场景恰好发出 1 次请求且可观察延迟不低于 700 ms。Android 明文 HTTP 放行只存在于设备测试清单，iOS 同时要求既有验收代理为空；正式应用网络配置、官网 URL、TLS 和重试上限均未修改。
- 专项成功、失败和退出路径都会删除 Android 测试包、临时请求计数、服务日志和动态端口目录。受控响应不包含彩票、票图、OCR 文本、号码、金额或其他用户数据。
- 关于页 V1 支持范围已与现有受控多期能力对齐：明确支持 1 至 20 期受控连续投注，并把超过 20 期、跨年度未知期次与补打票列入未支持范围；共享契约测试防止页面重新退回“全部多期不支持”的旧描述。
- 新增 `tools/mobile/run-current-vm-v1-scope-checks.sh` 和对应 Appium 检查。专项先复用完整 Release 构建、产物、安装和启动闸门，再把锁定 Android 模拟器临时切换到 `2.0` 字号、锁定 iOS Simulator 临时切换到 `accessibility-extra-extra-extra-large`。脚本从首页进入关于页并按需真实滚动，核对支持、限制和验真边界文案；随后进入空白手动录入，真实选择彩种和基本投注、把期数从 1 增加到 2、触达底部确认入口，再返回首页重新进入并核对旧状态已经清空。所有目标控件都必须完整位于窗口内，Android 与 iOS 分别按 `checked` 和 `selected` 确认选择态；iOS 滚动后会等待元素矩形稳定，期数点按只允许在状态尚未生效时有限补偿一次。成功、失败和退出路径都会关闭会话、恢复原字号与应用前台状态，并删除 Appium 日志。

## 本轮验证记录

- 当前双虚拟机安全回归通过：iOS Simulator 测试和 Android 数据层设备测试均成功，Android 为 66/66；101 个 Gradle 任务实际执行并全部成功。
- Android Release 构建执行 145 个 Gradle 任务并全部成功。
- 临时验收签名 Android Release APK 为 59,317,806 字节，SHA-256 为 `2f1c074dcae92410cf5c1ae00606fb4123bd9809866321749f9326bc55b5e28e`；覆盖安装、冷启动和进程存活检查通过。
- Android Release AAB 为 34,272,214 字节，SHA-256 为 `ae57d147e9e5123c02070d54cca01d2b2afcde072b649d7d4a61418da6ce9391`；ZIP 结构完整，但没有签名，不能作为可上架 AAB。
- iOS `Release-iphonesimulator` 应用为 40,668 KiB；arm64、签名完整性、非调试附加、覆盖安装和启动检查通过。
- 十二个移动 Shell 脚本均通过 Zsh 语法检查，本增量通过 `git diff --check`；受控 Node 服务同时通过语法检查。
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
- Debug 受控崩溃专项重新执行 180 个 Android Gradle 任务并全部成功；iOS Debug 和 `Release-iphonesimulator` 两套应用均完整构建成功。Android crash buffer 明确记录目标包的主线程 `IllegalStateException` 和固定匿名标记，iOS 控制台明确记录相同固定标记的 `fatalError`；两端崩溃进程退出，无参数重启后匿名标记均不存在。
- 崩溃专项结束时，Android 非 Debug Release 和 iOS Release 均在收到相同崩溃参数后保持进程存活，证明 Debug 验收入口不能由 Release 外部参数启用。临时验收签名 APK 为 59,317,806 字节，SHA-256 为 `4ea83c8e00a9acbde1a468e2220dc6e82df531f4762e68fa167c006260733a0f`；未签名 AAB 为 34,272,612 字节，SHA-256 为 `07a0c5b868d65388ed5c404f530359310d92e4638d58719a32f0109896f29170`；iOS Release Simulator `.app` 为 40,668 KiB，临时 DerivedData 已删除。
- 崩溃增量完成后复跑固定双虚拟机回归：iOS Simulator 230/230，Android 数据层 66/66、识别层 59/59，失败、错误和跳过均为 0；140 个 Gradle 任务全部实际执行成功。
- 低内存开发专项重新执行 Android Debug、Release APK 与 AAB 构建，180 个 Gradle 任务全部实际执行成功；iOS Debug 和 `Release-iphonesimulator` 两套应用均完整构建成功。Android Debug 收到精确 `RUNNING_CRITICAL=15` 且原 PID 保持，Release 收到相同系统命令后也保持运行且没有输出 Debug 标记。
- iOS LLDB 成功附加指定 Debug Simulator 进程并调用 `_performMemoryWarning`，应用真实通知回调在其数据容器 `tmp` 创建固定空标记，原 PID 与应用容器命令路径保持；标记随后删除，Release 二进制不含该标记且最终进程存活。临时验收签名 APK 为 59,317,806 字节，SHA-256 为 `af660ef9e93d72071ac97458d9bc95bd52dc5bcdb64f79078bcfbb503e601717`；未签名 AAB 为 34,273,214 字节，SHA-256 为 `fd778279b3d7e77045853eccd8362f72671c7f9ca74abe7d272396ef80591649`；iOS Release Simulator `.app` 为 40,668 KiB，临时 DerivedData 已删除。
- 低内存开发增量完成后复跑固定双虚拟机回归：iOS Simulator 230/230，Android 数据层 66/66、识别层 59/59，失败、错误和跳过均为 0；140 个 Gradle 任务全部实际执行成功。
- 构建号升级专项从固定基线完整执行 Android Debug/Release 的 171 个 Gradle 任务并全部成功，当前源码的 Debug、Release APK 与 Release AAB 重新执行 180 个任务并全部成功；基线和当前 iOS `Release-iphonesimulator` 也均完整构建成功。
- Android 实际执行构建号 1 Release → 构建号 2 Release 覆盖升级，升级后持久标记保留，启动时票图与 CameraX 临时标记均被清扫；iOS 实际执行相同方向的 Release 覆盖升级，升级后持久标记保留且临时票图被清扫。两端最终版本均为 `1.0.1 (2)`、非 Debug Release 且进程存活。
- 本次临时验收签名 APK 为 59,317,806 字节，SHA-256 为 `2f8deefb6d26c76ceb995307f6799207208257fc0e66ad91d50bef2801a13696`；未签名 AAB 为 34,273,216 字节，SHA-256 为 `1a4d42e36db4c7a6355d1f0c1950f0d9c739ea1a5ac2979ed3833376b2cb2c36`；iOS Release Simulator `.app` 为 40,668 KiB，临时验收签名产物和 DerivedData 已删除。
- 构建号升级增量完成后复跑固定双虚拟机回归：iOS Simulator 230/230，Android 数据层 66/66、识别层 59/59，失败、错误和跳过均为 0；140 个 Gradle 任务全部实际执行成功。最终 Android 构建号 2 包不含 `DEBUGGABLE` 且进程存活；iOS 为 `1.0.1 (2)`，进程命令路径属于指定 Simulator，匿名升级标记已删除。
- 固定 12MP 专项实际完成 Android instrumentation 和 iOS Debug 应用进程各 20 次统计采样。Android 冷样本 856.8 ms、P50 152.6 ms、P95 354.8 ms、最大 355.6 ms，阶段 P95 为质量检查 189.9 ms、OCR 166.5 ms、解析 0.7 ms；iOS 冷样本 1436.3 ms、P50 1019.2 ms、P95 1036.8 ms、最大 1040.2 ms，阶段 P95 为质量检查 21.1 ms、OCR 1016.5 ms、解析 0.1 ms。两端解析分类均为 `correction`。
- 性能专项完整执行 53 个 Android Gradle 任务并全部成功，Android 单项 instrumentation 为 `OK (1 test)`；iOS Debug 和 Release Simulator 应用均完整构建成功。Release 传入相同参数后未进入采样入口，最终 Release 应用成功启动；Android 测试包、iOS 合成 JPEG、控制台日志和 DerivedData 均已删除。上述耗时只是当前虚拟机开发基线，不是产品性能承诺，也不能作为 Release 物理真机 P95。
- 固定 12MP 增量完成后复跑固定双虚拟机回归：iOS Simulator 230/230，Android 数据层 66/66、识别层 60/60，失败、错误和跳过均为 0；140 个 Gradle 任务全部实际执行成功。识别层新增的 1 项是 Android 固定 12MP 真实链路冒烟，普通回归只执行 1 次，不替代 20 次专项。
- 旋转专项最终整链重新执行 145 个 Android Gradle 任务并全部成功，iOS `Release-iphonesimulator` 完整构建成功；两端安装包均保持非 Debug，专项没有操作同时连接的 Android 实体设备。
- Android Release 横屏截图为 `2400×1080`、竖屏截图为 `1080×2400`，主进程 PID `5338` 在两次切换中保持；iOS Release 横屏截图为 `2622×1206`、竖屏截图为 `1206×2622`，Simulator 应用 PID `81710` 保持。两端导图和手动录入入口在横竖屏均可由辅助功能访问，退出后已恢复竖屏，临时证据已删除。PID 只用于证明本次切换没有重启进程，不作为跨次验收常量。
- 网络抖动专项最终执行 Android 51 个 Gradle 构建与格式任务、iOS 15 个 Kotlin/Native 构建任务并全部成功。Android OkHttp 单项为 `OK (1 test)`、耗时 2.422 秒；iOS Darwin 单项通过、耗时 855 ms。两端服务计数均为 503 场景 2 次、连接中断场景 2 次、延迟场景 1 次，证明正式策略对 GET 瞬时错误只进行一次有限重试；上述耗时包含受控延迟和引擎重试调度，不是网络或产品性能基线。
- 网络抖动增量完成后复跑固定双虚拟机回归：iOS Simulator 230/230，Android 数据层 67/67、识别层 60/60，失败、错误和跳过均为 0；140 个 Gradle 任务全部实际执行成功。输出只包含锁定 Android AVD，专项 Android 测试包和本地服务临时目录均已删除。
- V1 范围最大字号专项重新执行 Android Release 的 145 个 Gradle 任务并全部成功，iOS `Release-iphonesimulator` 完整构建、覆盖安装和非 Debug 启动检查通过。Android 在 `2.0` 字号下进入关于页并真实滚动 1 次，iOS 在最大辅助字号下首屏可访问性树已包含全部目标文案；两端都核对到 1 至 20 期支持、超过 20 期限制、跨年度未知期次限制和验真边界，且没有旧的“全部多期不支持”描述。专项结束后 Android 字号恢复为 `1.0`，iOS 恢复为 `large`。
- 范围文案增量完成后复跑固定双虚拟机回归：iOS Simulator 231/231，Android 数据层 67/67、识别层 60/60，失败、错误和跳过均为 0；140 个 Gradle 任务全部实际执行成功。iOS 新增的 1 项是共享关于页范围文案契约测试。
- 最大字号手动录入增量再次执行 Android Release 的 145 个 Gradle 任务并全部成功，iOS `Release-iphonesimulator` 完整构建、覆盖安装和非 Debug 启动检查通过。Android 在 `2.0` 字号下关于页滚动 1 次、手动录入累计滚动 3 次；iOS 在最大辅助字号下关于页无需滚动、手动录入累计滚动 3 次。两端均确认基本投注真实选中、期数显示由 `1 期` 更新为 `2 期`、底部“已核对，继续”完整可达，返回首页再次进入后不再包含彩种投注区或 `2 期` 旧状态。正式专项未触发 iOS 补偿点按；退出后 Android 字号恢复为 `1.0`，iOS 恢复为 `large`，临时日志与 Release 验收目录均已清理。
- 手动录入增量完成后复跑固定双虚拟机回归：iOS Simulator 231/231，Android 数据层 67/67、识别层 60/60，失败、错误和跳过均为 0；140 个 Gradle 任务全部实际执行成功。专项脚本同时通过 Node 与 Zsh 语法检查，完整差异通过 `git diff --check`。

## 延期发布与能力边界

- Android 生产签名、密钥托管、签名轮换方案、Play App Signing 和 Play Console 上传验证未完成。
- iOS Archive、分发证书、Provisioning Profile、正式 IPA 导出、安装和 App Store Connect 上传验证未完成。
- Android/iPhone 物理真机矩阵已从当前项目要求中移除；Simulator 不能证明相机硬件、物理设备内存、系统权限差异和真实性能，但这些差距不再阻塞当前 V1。
- 当前 12MP 重复采样只作为固定双虚拟机 Debug/测试进程开发基线，不设置 Release 物理设备 P95，也不作为产品性能承诺。
- 足量实体票、多拍摄条件、逐 token 双人真值和平台分别校准仍决定自动识别支持白名单；当前白名单为空时，产品继续通过人工确认和安全失败关闭工作，不把探索图识别结果宣传为版式准确率。
- 固定双虚拟机不覆盖真实低内存和公网弱网；网络抖动专项只覆盖本机受控 HTTP 端点、当前 Android OkHttp 与 iOS Darwin 引擎的一次有限重试和短延迟响应，不覆盖公网可达性、系统 TLS、DNS、CDN、运营商切网、长时丢包、后台切换或物理设备。旋转专项只覆盖当前双虚拟机 Release 首页的横竖屏尺寸、关键入口和进程保持，不代表其他页面、分屏、折叠屏、iPad、多窗口或物理设备。进程专项覆盖受控 `force-stop` / `terminate`、已核验 PID 的 `SIGKILL`、Android Debug 主线程异常和 iOS Debug `fatalError` 后的临时文件清扫，低内存开发专项只覆盖 Android 系统内存收紧回调与 iOS Debug 模拟 UIKit 内存警告。当前不覆盖 native crash、ANR/watchdog、OOM、Jetsam、真实低内存系统回收、崩溃上报隐私或业务状态持久化。同版本覆盖安装、双虚拟机卸载清除及构建号 1 → 2 开发签名升级已完成；生产签名升级、物理真机卸载和系统备份随公开发布延期。权限专项完成 Android 当前模拟器首次相机拒权，iOS Simulator 保持无相机能力降级，不再追加 iPhone 真机拒权待办。
- B2 第二人独立真值复核、连续 6 个开奖窗口采样和官方数据商用授权未完成。
- 隐私政策、免责声明、应用商店隐私清单、截图文案、年龄与彩票相关政策核对尚未完成。

## 下一步

1. 继续在当前双虚拟机收束 V1 页面可访问性、状态恢复、图片导入、人工校正、单期与多期测算等内部质量，不启动 V1.1。
2. 生产签名、真机矩阵、真实弱网、商店上传与发布合规材料全部保持延期，不在当前 V1 迭代中执行。
3. B0/B2/B3 的票样真值、开奖窗口和数据授权继续作为能力声明边界；缺少这些材料时保持白名单为空和失败关闭，但不阻止双虚拟机内的 V1 功能完善。

#!/bin/zsh

set -euo pipefail

readonly SCRIPT_DIRECTORY="${0:A:h}"
readonly APPIUM_CHECK_SCRIPT="$SCRIPT_DIRECTORY/v1-1-records-appium-check.mjs"
readonly ROUNDTRIP_VERIFY_SCRIPT="$SCRIPT_DIRECTORY/verify-v1-1-record-roundtrip.mjs"
readonly APPIUM_PORT=4726
readonly APPIUM_BASE_URL="http://127.0.0.1:$APPIUM_PORT"
readonly ANDROID_APPLICATION_ID="roc.win.lottery"
readonly ANDROID_ACTIVITY="roc.win.lottery.MainActivity"
readonly IOS_APPLICATION_ID="roc.win.lottery.WinLottery"
readonly IOS_PLATFORM_VERSION="26.5"
readonly ANDROID_STANDARD_FONT_SCALE="1.0"
readonly ANDROID_MAXIMUM_FONT_SCALE="2.0"
readonly IOS_STANDARD_CONTENT_SIZE="large"
readonly IOS_MAXIMUM_CONTENT_SIZE="accessibility-extra-extra-extra-large"
readonly ANDROID_TO_IOS_FILE_NAME="android-to-ios.wltickets.json"
readonly IOS_TO_ANDROID_FILE_NAME="ios-to-android.wltickets.json"
readonly ANDROID_RECORD_NAME="V1.1 Android 自动验收"
readonly IOS_RECORD_NAME="v11 ios test"
readonly ANDROID_RECORD_ISSUE="26997"
readonly IOS_RECORD_ISSUE="26998"

source "$SCRIPT_DIRECTORY/current-vm-guard.sh"
verify_current_mobile_vms

for required_command in appium comm cp curl find node plutil sips sort tail; do
  command -v "$required_command" >/dev/null || current_vm_fail "缺少命令 $required_command"
done
[[ -f "$APPIUM_CHECK_SCRIPT" ]] || current_vm_fail "缺少 V1.1 记录 Appium 检查脚本"
[[ -f "$ROUNDTRIP_VERIFY_SCRIPT" ]] || current_vm_fail "缺少 V1.1 跨端字段核对脚本"

readonly ANDROID_FONT_SCALE_BEFORE="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell settings get system font_scale | tr -d '\r')"
readonly IOS_CONTENT_SIZE_BEFORE="$(xcrun simctl ui "$CURRENT_IOS_SIMULATOR_UDID" content_size | tr -d '\r')"
[[ "$ANDROID_FONT_SCALE_BEFORE" == <->.<-> ]] || current_vm_fail "Android 原始字号比例格式异常"
[[ -n "$IOS_CONTENT_SIZE_BEFORE" ]] || current_vm_fail "无法读取 iOS 原始辅助字号"

readonly WORK_DIRECTORY="$(mktemp -d "${TMPDIR:-/tmp}/winlottery-v1-1-records.XXXXXX")"
readonly SCREENSHOT_DIRECTORY="$WORK_DIRECTORY/screenshots"
readonly APPIUM_LOG="$WORK_DIRECTORY/appium.log"
mkdir -p "$SCREENSHOT_DIRECTORY"
APPIUM_PROCESS_ID=""
typeset -a android_acceptance_files=()
typeset -a ios_acceptance_files=()

# 从 Simulator 容器元数据解析“我的 iPhone”本地文件提供方目录。
resolve_ios_local_storage_root() {
  local app_group_root="$HOME/Library/Developer/CoreSimulator/Devices/$CURRENT_IOS_SIMULATOR_UDID/data/Containers/Shared/AppGroup"
  local metadata identifier
  for metadata in "$app_group_root"/*/.com.apple.mobile_container_manager.metadata.plist(N); do
    identifier="$(plutil -extract MCMMetadataIdentifier raw "$metadata" 2>/dev/null || true)"
    if [[ "$identifier" == "group.com.apple.FileProvider.LocalStorage" ]]; then
      local storage_root="${metadata:h}/File Provider Storage"
      [[ -d "$storage_root" ]] || current_vm_fail "iOS 本地文件提供方目录不存在"
      print -r -- "$storage_root"
      return
    fi
  done
  current_vm_fail "无法定位 iOS 本地文件提供方目录"
}

readonly IOS_LOCAL_STORAGE_ROOT="$(resolve_ios_local_storage_root)"

# 列出 Android 下载目录内由应用实际导出的逻辑包。
list_android_exports() {
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell find /sdcard/Download \
    -maxdepth 1 \
    -type f \
    -name 'win-lottery-tickets-*.wltickets.json' \
    -print 2>/dev/null |
    tr -d '\r' |
    sort
}

# 列出 iOS“我的 iPhone”内由应用实际导出的逻辑包。
list_ios_exports() {
  find "$IOS_LOCAL_STORAGE_ROOT" \
    -type f \
    -name 'win-lottery-tickets-*.wltickets.json' \
    -print 2>/dev/null |
    sort
}

# 从导出前后快照中读取本轮唯一新增文件。
read_single_new_export() {
  local before_snapshot="$1"
  local after_snapshot="$2"
  local platform_name="$3"
  local difference_file="$WORK_DIRECTORY/${platform_name}-new-export.txt"
  comm -13 "$before_snapshot" "$after_snapshot" >"$difference_file"
  local difference_count
  difference_count="$(awk 'NF { count += 1 } END { print count + 0 }' "$difference_file")"
  [[ "$difference_count" == "1" ]] ||
    current_vm_fail "$platform_name 本轮新增导出文件数量不是 1：$difference_count"
  sed -n '1p' "$difference_file"
}

# 退出时恢复双虚拟机原始字号、应用前台状态，清理明确的验收文件并停止本地服务。
cleanup() {
  if [[ -n "$APPIUM_PROCESS_ID" ]] && kill -0 "$APPIUM_PROCESS_ID" 2>/dev/null; then
    kill "$APPIUM_PROCESS_ID" 2>/dev/null || true
    wait "$APPIUM_PROCESS_ID" 2>/dev/null || true
  fi
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell settings put system font_scale "$ANDROID_FONT_SCALE_BEFORE" >/dev/null 2>&1 || true
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am force-stop "$ANDROID_APPLICATION_ID" >/dev/null 2>&1 || true
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am start -n "$ANDROID_APPLICATION_ID/$ANDROID_ACTIVITY" >/dev/null 2>&1 || true
  xcrun simctl ui "$CURRENT_IOS_SIMULATOR_UDID" content_size "$IOS_CONTENT_SIZE_BEFORE" >/dev/null 2>&1 || true
  xcrun simctl terminate "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_APPLICATION_ID" >/dev/null 2>&1 || true
  xcrun simctl launch "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_APPLICATION_ID" >/dev/null 2>&1 || true
  local acceptance_file
  for acceptance_file in "${android_acceptance_files[@]}"; do
    if [[ "$acceptance_file" == /sdcard/Download/* ]]; then
      adb -s "$CURRENT_ANDROID_SERIAL_ID" shell rm -f "$acceptance_file" >/dev/null 2>&1 || true
    fi
  done
  for acceptance_file in "${ios_acceptance_files[@]}"; do
    if [[ "$acceptance_file" == "$IOS_LOCAL_STORAGE_ROOT/"* ]]; then
      rm -f -- "$acceptance_file" >/dev/null 2>&1 || true
    fi
  done
}
trap cleanup EXIT

# 先复用非 Debug 构建、安装和启动闸门，再执行记录专项操作。
"$SCRIPT_DIRECTORY/run-current-vm-release-checks.sh"

appium \
  --address 127.0.0.1 \
  --port "$APPIUM_PORT" \
  --log-level warn \
  --log-no-colors \
  >"$APPIUM_LOG" 2>&1 &
APPIUM_PROCESS_ID=$!

# 等待 Appium 就绪，提前退出时保留明确诊断。
for _ in {1..100}; do
  if curl --silent --show-error --fail "$APPIUM_BASE_URL/status" >/dev/null 2>&1; then
    break
  fi
  if ! kill -0 "$APPIUM_PROCESS_ID" 2>/dev/null; then
    print -u2 "Appium 提前退出"
    tail -n 120 "$APPIUM_LOG" >&2 || true
    exit 1
  fi
  sleep 0.1
done
curl --silent --show-error --fail "$APPIUM_BASE_URL/status" >/dev/null || current_vm_fail "等待 Appium 就绪超时"

# 标准字号阶段创建并标记合成记录，同时验证基础管理界面。
adb -s "$CURRENT_ANDROID_SERIAL_ID" shell settings put system font_scale "$ANDROID_STANDARD_FONT_SCALE" >/dev/null
adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am force-stop "$ANDROID_APPLICATION_ID"
adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am start -n "$ANDROID_APPLICATION_ID/$ANDROID_ACTIVITY" >/dev/null
xcrun simctl ui "$CURRENT_IOS_SIMULATOR_UDID" content_size "$IOS_STANDARD_CONTENT_SIZE"
xcrun simctl terminate "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_APPLICATION_ID" >/dev/null 2>&1 || true
xcrun simctl launch "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_APPLICATION_ID" >/dev/null

if ! node \
  "$APPIUM_CHECK_SCRIPT" \
  "$APPIUM_BASE_URL" \
  prepare \
  "$SCREENSHOT_DIRECTORY" \
  "$CURRENT_ANDROID_SERIAL_ID" \
  "$CURRENT_ANDROID_AVD_NAME" \
  "$ANDROID_APPLICATION_ID" \
  "$ANDROID_ACTIVITY" \
  "$CURRENT_IOS_SIMULATOR_UDID" \
  "$CURRENT_IOS_SIMULATOR_NAME" \
  "$IOS_PLATFORM_VERSION" \
  "$IOS_APPLICATION_ID"; then
  print -u2 "V1.1 标准字号记录准备失败，Appium 日志末尾如下："
  tail -n 120 "$APPIUM_LOG" >&2 || true
  print -u2 "验收证据保留于：$WORK_DIRECTORY"
  exit 1
fi

# 最大字号阶段复用已保存记录，验证布局与系统文件选择器。
adb -s "$CURRENT_ANDROID_SERIAL_ID" shell settings put system font_scale "$ANDROID_MAXIMUM_FONT_SCALE" >/dev/null
adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am force-stop "$ANDROID_APPLICATION_ID"
adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am start -n "$ANDROID_APPLICATION_ID/$ANDROID_ACTIVITY" >/dev/null
xcrun simctl ui "$CURRENT_IOS_SIMULATOR_UDID" content_size "$IOS_MAXIMUM_CONTENT_SIZE"
xcrun simctl terminate "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_APPLICATION_ID" >/dev/null 2>&1 || true
xcrun simctl launch "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_APPLICATION_ID" >/dev/null

if ! node \
  "$APPIUM_CHECK_SCRIPT" \
  "$APPIUM_BASE_URL" \
  accept \
  "$SCREENSHOT_DIRECTORY" \
  "$CURRENT_ANDROID_SERIAL_ID" \
  "$CURRENT_ANDROID_AVD_NAME" \
  "$ANDROID_APPLICATION_ID" \
  "$ANDROID_ACTIVITY" \
  "$CURRENT_IOS_SIMULATOR_UDID" \
  "$CURRENT_IOS_SIMULATOR_NAME" \
  "$IOS_PLATFORM_VERSION" \
  "$IOS_APPLICATION_ID"; then
  print -u2 "V1.1 最大字号记录验收失败，Appium 日志末尾如下："
  tail -n 120 "$APPIUM_LOG" >&2 || true
  print -u2 "验收证据保留于：$WORK_DIRECTORY"
  exit 1
fi

# 重启指定平台应用，让后续阶段从默认筛选和空搜索状态开始。
restart_acceptance_apps() {
  local target_platform="$1"
  if [[ "$target_platform" == "both" || "$target_platform" == "android" ]]; then
    adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am force-stop "$ANDROID_APPLICATION_ID"
    adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am start \
      -n "$ANDROID_APPLICATION_ID/$ANDROID_ACTIVITY" >/dev/null
  fi
  if [[ "$target_platform" == "both" || "$target_platform" == "ios" ]]; then
    xcrun simctl terminate "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_APPLICATION_ID" >/dev/null 2>&1 || true
    xcrun simctl launch "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_APPLICATION_ID" >/dev/null
  fi
}

# 执行真实文件交互阶段，失败时统一保留 Appium 诊断。
run_transfer_phase() {
  local phase="$1"
  local target_platform="$2"
  local transfer_file_name="${3:-}"
  if ! node \
    "$APPIUM_CHECK_SCRIPT" \
    "$APPIUM_BASE_URL" \
    "$phase" \
    "$SCREENSHOT_DIRECTORY" \
    "$CURRENT_ANDROID_SERIAL_ID" \
    "$CURRENT_ANDROID_AVD_NAME" \
    "$ANDROID_APPLICATION_ID" \
    "$ANDROID_ACTIVITY" \
    "$CURRENT_IOS_SIMULATOR_UDID" \
    "$CURRENT_IOS_SIMULATOR_NAME" \
    "$IOS_PLATFORM_VERSION" \
    "$IOS_APPLICATION_ID" \
    "$target_platform" \
    "$transfer_file_name"; then
    print -u2 "V1.1 $phase/$target_platform 真实文件交互失败，Appium 日志末尾如下："
    tail -n 120 "$APPIUM_LOG" >&2 || true
    print -u2 "验收证据保留于：$WORK_DIRECTORY"
    exit 1
  fi
}

# 标准字号下分别真实导出，避免辅助字号影响系统文件列表的可操作密度。
adb -s "$CURRENT_ANDROID_SERIAL_ID" shell settings put system font_scale "$ANDROID_STANDARD_FONT_SCALE" >/dev/null
xcrun simctl ui "$CURRENT_IOS_SIMULATOR_UDID" content_size "$IOS_STANDARD_CONTENT_SIZE"
restart_acceptance_apps both

android_source_before="$WORK_DIRECTORY/android-source-before.txt"
android_source_after="$WORK_DIRECTORY/android-source-after.txt"
list_android_exports >"$android_source_before"
run_transfer_phase export android
list_android_exports >"$android_source_after"
android_source_export="$(read_single_new_export "$android_source_before" "$android_source_after" android-source)"
android_acceptance_files+=("$android_source_export")
android_source_package="$WORK_DIRECTORY/android-source.wltickets.json"
adb -s "$CURRENT_ANDROID_SERIAL_ID" pull "$android_source_export" "$android_source_package" >/dev/null

ios_source_before="$WORK_DIRECTORY/ios-source-before.txt"
ios_source_after="$WORK_DIRECTORY/ios-source-after.txt"
list_ios_exports >"$ios_source_before"
run_transfer_phase export ios
list_ios_exports >"$ios_source_after"
ios_source_export="$(read_single_new_export "$ios_source_before" "$ios_source_after" ios-source)"
ios_acceptance_files+=("$ios_source_export")
ios_source_package="$WORK_DIRECTORY/ios-source.wltickets.json"
cp "$ios_source_export" "$ios_source_package"

# 只向两个虚拟机放入明确命名的对端包，再通过系统选择器完成真实导入。
ios_import_file="$IOS_LOCAL_STORAGE_ROOT/$ANDROID_TO_IOS_FILE_NAME"
cp "$android_source_package" "$ios_import_file"
ios_acceptance_files+=("$ios_import_file")
android_import_file="/sdcard/Download/$IOS_TO_ANDROID_FILE_NAME"
adb -s "$CURRENT_ANDROID_SERIAL_ID" push "$ios_source_package" "$android_import_file" >/dev/null
android_acceptance_files+=("$android_import_file")

restart_acceptance_apps ios
run_transfer_phase import ios "$ANDROID_TO_IOS_FILE_NAME"
restart_acceptance_apps android
run_transfer_phase import android "$IOS_TO_ANDROID_FILE_NAME"

# 双端导入后再次真实导出，并按稳定 UUID 对全部规范化字段做深比较。
ios_target_before="$WORK_DIRECTORY/ios-target-before.txt"
ios_target_after="$WORK_DIRECTORY/ios-target-after.txt"
list_ios_exports >"$ios_target_before"
run_transfer_phase export ios
list_ios_exports >"$ios_target_after"
ios_target_export="$(read_single_new_export "$ios_target_before" "$ios_target_after" ios-target)"
ios_acceptance_files+=("$ios_target_export")
ios_target_package="$WORK_DIRECTORY/ios-after-android-import.wltickets.json"
cp "$ios_target_export" "$ios_target_package"

android_target_before="$WORK_DIRECTORY/android-target-before.txt"
android_target_after="$WORK_DIRECTORY/android-target-after.txt"
list_android_exports >"$android_target_before"
run_transfer_phase export android
list_android_exports >"$android_target_after"
android_target_export="$(read_single_new_export "$android_target_before" "$android_target_after" android-target)"
android_acceptance_files+=("$android_target_export")
android_target_package="$WORK_DIRECTORY/android-after-ios-import.wltickets.json"
adb -s "$CURRENT_ANDROID_SERIAL_ID" pull "$android_target_export" "$android_target_package" >/dev/null

node \
  "$ROUNDTRIP_VERIFY_SCRIPT" \
  "$android_source_package" \
  "$ios_target_package" \
  "$ANDROID_RECORD_NAME" \
  "$ANDROID_RECORD_ISSUE"
node \
  "$ROUNDTRIP_VERIFY_SCRIPT" \
  "$ios_source_package" \
  "$android_target_package" \
  "$IOS_RECORD_NAME" \
  "$IOS_RECORD_ISSUE"

restart_acceptance_apps both
run_transfer_phase cleanup both

# iOS Appium 截图按逻辑视口统一为 402×874，Android 保持虚拟机原生 1080×2400。
for ios_screenshot in "$SCREENSHOT_DIRECTORY"/ios-*.png; do
  [[ -f "$ios_screenshot" ]] || continue
  sips --resampleHeightWidth 874 402 "$ios_screenshot" --out "$ios_screenshot" >/dev/null
done

print "双虚拟机 V1.1 本机记录、最大字号、系统文件选择器与跨端文件往返检查通过"
print "验收截图与日志：$WORK_DIRECTORY"

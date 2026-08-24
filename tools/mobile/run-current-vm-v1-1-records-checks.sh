#!/bin/zsh

set -euo pipefail

readonly SCRIPT_DIRECTORY="${0:A:h}"
readonly APPIUM_CHECK_SCRIPT="$SCRIPT_DIRECTORY/v1-1-records-appium-check.mjs"
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

source "$SCRIPT_DIRECTORY/current-vm-guard.sh"
verify_current_mobile_vms

for required_command in appium curl node sips tail; do
  command -v "$required_command" >/dev/null || current_vm_fail "缺少命令 $required_command"
done
[[ -f "$APPIUM_CHECK_SCRIPT" ]] || current_vm_fail "缺少 V1.1 记录 Appium 检查脚本"

readonly ANDROID_FONT_SCALE_BEFORE="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell settings get system font_scale | tr -d '\r')"
readonly IOS_CONTENT_SIZE_BEFORE="$(xcrun simctl ui "$CURRENT_IOS_SIMULATOR_UDID" content_size | tr -d '\r')"
[[ "$ANDROID_FONT_SCALE_BEFORE" == <->.<-> ]] || current_vm_fail "Android 原始字号比例格式异常"
[[ -n "$IOS_CONTENT_SIZE_BEFORE" ]] || current_vm_fail "无法读取 iOS 原始辅助字号"

readonly WORK_DIRECTORY="$(mktemp -d "${TMPDIR:-/tmp}/winlottery-v1-1-records.XXXXXX")"
readonly SCREENSHOT_DIRECTORY="$WORK_DIRECTORY/screenshots"
readonly APPIUM_LOG="$WORK_DIRECTORY/appium.log"
mkdir -p "$SCREENSHOT_DIRECTORY"
APPIUM_PROCESS_ID=""

# 退出时恢复双虚拟机原始字号、应用前台状态并停止本地服务；截图目录保留供人工复核。
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

# 最大字号阶段复用已保存记录，验证布局、系统文件选择器并只清理合成记录。
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

# iOS Appium 截图按逻辑视口统一为 402×874，Android 保持虚拟机原生 1080×2400。
for ios_screenshot in "$SCREENSHOT_DIRECTORY"/ios-*.png; do
  [[ -f "$ios_screenshot" ]] || continue
  sips --resampleHeightWidth 874 402 "$ios_screenshot" --out "$ios_screenshot" >/dev/null
done

print "双虚拟机 V1.1 本机记录、最大字号与系统文件选择器检查通过"
print "验收截图与日志：$WORK_DIRECTORY"

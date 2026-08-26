#!/bin/zsh

set -euo pipefail

readonly SCRIPT_DIRECTORY="${0:A:h}"
readonly REPOSITORY_ROOT="${SCRIPT_DIRECTORY:h:h}"
readonly APPIUM_CHECK_SCRIPT="$SCRIPT_DIRECTORY/v1-3-trend-appium-check.mjs"
readonly APPIUM_PORT=4729
readonly APPIUM_BASE_URL="http://127.0.0.1:$APPIUM_PORT"
readonly ANDROID_APPLICATION_ID="roc.win.lottery"
readonly ANDROID_ACTIVITY="roc.win.lottery.MainActivity"
readonly IOS_APPLICATION_ID="roc.win.lottery.WinLottery"
readonly IOS_PLATFORM_VERSION="26.5"
readonly ANDROID_STANDARD_FONT_SCALE="1.0"
readonly ANDROID_MAXIMUM_FONT_SCALE="2.0"
readonly IOS_STANDARD_CONTENT_SIZE="large"
readonly IOS_MAXIMUM_CONTENT_SIZE="accessibility-extra-extra-extra-large"
readonly SCREENSHOT_DIRECTORY="$REPOSITORY_ROOT/build/reports/v1-3-trends"

source "$SCRIPT_DIRECTORY/current-vm-guard.sh"
verify_current_mobile_vms

for required_command in appium curl node tail; do
  command -v "$required_command" >/dev/null || current_vm_fail "缺少命令 $required_command"
done
[[ -f "$APPIUM_CHECK_SCRIPT" ]] || current_vm_fail "缺少 V1.3 Appium 检查脚本"

readonly ANDROID_FONT_SCALE_BEFORE="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell settings get system font_scale | tr -d '\r')"
readonly IOS_CONTENT_SIZE_BEFORE="$(xcrun simctl ui "$CURRENT_IOS_SIMULATOR_UDID" content_size | tr -d '\r')"
[[ "$ANDROID_FONT_SCALE_BEFORE" == <->.<-> ]] || current_vm_fail "Android 原始字号比例格式异常"
[[ -n "$IOS_CONTENT_SIZE_BEFORE" ]] || current_vm_fail "无法读取 iOS 原始辅助字号"

readonly WORK_DIRECTORY="$(mktemp -d "${TMPDIR:-/tmp}/winlottery-v1-3.XXXXXX")"
readonly APPIUM_LOG="$WORK_DIRECTORY/appium.log"
APPIUM_PROCESS_ID=""

# 退出时恢复双虚拟机原始字号、应用前台状态并清理本机 Appium。
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

# 使用指定字号重新启动双端 Release 应用，确保控制器会话从可重复状态开始。
restart_apps_with_font_size() {
  local android_font_scale="$1"
  local ios_content_size="$2"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell settings put system font_scale "$android_font_scale" >/dev/null
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am force-stop "$ANDROID_APPLICATION_ID"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am start -n "$ANDROID_APPLICATION_ID/$ANDROID_ACTIVITY" >/dev/null
  xcrun simctl ui "$CURRENT_IOS_SIMULATOR_UDID" content_size "$ios_content_size"
  xcrun simctl terminate "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_APPLICATION_ID" >/dev/null 2>&1 || true
  xcrun simctl launch "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_APPLICATION_ID" >/dev/null
}

# 调用双端 Appium 专项，并在失败时输出服务端诊断和证据目录。
run_appium_phase() {
  local phase="$1"
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
    "$IOS_APPLICATION_ID"; then
    print -u2 "V1.3 $phase 双虚拟机验收失败，Appium 日志末尾如下："
    tail -n 160 "$APPIUM_LOG" >&2 || true
    print -u2 "截图证据保留于：$SCREENSHOT_DIRECTORY"
    exit 1
  fi
}

# 先复用非 Debug 构建、签名边界、安装和启动闸门，再执行两档字号交互。
"$SCRIPT_DIRECTORY/run-current-vm-release-checks.sh"

restart_apps_with_font_size "$ANDROID_STANDARD_FONT_SCALE" "$IOS_STANDARD_CONTENT_SIZE"

appium \
  --address 127.0.0.1 \
  --port "$APPIUM_PORT" \
  --log-level warn \
  --log-no-colors \
  >"$APPIUM_LOG" 2>&1 &
APPIUM_PROCESS_ID=$!

# 等待 Appium 就绪，提前退出时给出明确诊断。
for _ in {1..100}; do
  if curl --silent --show-error --fail "$APPIUM_BASE_URL/status" >/dev/null 2>&1; then
    break
  fi
  if ! kill -0 "$APPIUM_PROCESS_ID" 2>/dev/null; then
    print -u2 "V1.3 验收 Appium 提前退出"
    tail -n 160 "$APPIUM_LOG" >&2 || true
    exit 1
  fi
  sleep 0.1
done
curl --silent --show-error --fail "$APPIUM_BASE_URL/status" >/dev/null || current_vm_fail "等待 V1.3 Appium 就绪超时"

run_appium_phase standard
restart_apps_with_font_size "$ANDROID_MAXIMUM_FONT_SCALE" "$IOS_MAXIMUM_CONTENT_SIZE"
run_appium_phase maximum

print "双虚拟机 V1.3 标准与最大字号、四级导航、50 期边界、横向矩阵和固定期号列检查通过"
print "截图证据：$SCREENSHOT_DIRECTORY"

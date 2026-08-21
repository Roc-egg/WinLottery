#!/bin/zsh

set -euo pipefail

readonly SCRIPT_DIRECTORY="${0:A:h}"
readonly APPIUM_CHECK_SCRIPT="$SCRIPT_DIRECTORY/v1-scope-appium-check.mjs"
readonly APPIUM_PORT=4725
readonly APPIUM_BASE_URL="http://127.0.0.1:$APPIUM_PORT"
readonly ANDROID_APPLICATION_ID="roc.win.lottery"
readonly ANDROID_ACTIVITY="roc.win.lottery.MainActivity"
readonly IOS_APPLICATION_ID="roc.win.lottery.WinLottery"
readonly IOS_PLATFORM_VERSION="26.5"
readonly ANDROID_ACCEPTANCE_FONT_SCALE="2.0"
readonly IOS_ACCEPTANCE_CONTENT_SIZE="accessibility-extra-extra-extra-large"

source "$SCRIPT_DIRECTORY/current-vm-guard.sh"
verify_current_mobile_vms

for required_command in appium curl node tail; do
  command -v "$required_command" >/dev/null || current_vm_fail "缺少命令 $required_command"
done
[[ -f "$APPIUM_CHECK_SCRIPT" ]] || current_vm_fail "缺少 V1 范围 Appium 检查脚本"

readonly ANDROID_FONT_SCALE_BEFORE="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell settings get system font_scale | tr -d '\r')"
readonly IOS_CONTENT_SIZE_BEFORE="$(xcrun simctl ui "$CURRENT_IOS_SIMULATOR_UDID" content_size | tr -d '\r')"
[[ "$ANDROID_FONT_SCALE_BEFORE" == <->.<-> ]] || current_vm_fail "Android 原始字号比例格式异常"
[[ -n "$IOS_CONTENT_SIZE_BEFORE" ]] || current_vm_fail "无法读取 iOS 原始辅助字号"

readonly WORK_DIRECTORY="$(mktemp -d "${TMPDIR:-/tmp}/winlottery-v1-scope.XXXXXX")"
readonly APPIUM_LOG="$WORK_DIRECTORY/appium.log"
APPIUM_PROCESS_ID=""

# 退出时恢复双虚拟机原始字号、应用前台状态并清理本地服务。
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
  rm -rf -- "$WORK_DIRECTORY"
}
trap cleanup EXIT

# 先复用非 Debug 构建、安装和启动闸门，再切换系统字号。
"$SCRIPT_DIRECTORY/run-current-vm-release-checks.sh"

adb -s "$CURRENT_ANDROID_SERIAL_ID" shell settings put system font_scale "$ANDROID_ACCEPTANCE_FONT_SCALE" >/dev/null
adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am force-stop "$ANDROID_APPLICATION_ID"
adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am start -n "$ANDROID_APPLICATION_ID/$ANDROID_ACTIVITY" >/dev/null
xcrun simctl ui "$CURRENT_IOS_SIMULATOR_UDID" content_size "$IOS_ACCEPTANCE_CONTENT_SIZE"
xcrun simctl terminate "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_APPLICATION_ID" >/dev/null 2>&1 || true
xcrun simctl launch "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_APPLICATION_ID" >/dev/null

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

if ! node \
  "$APPIUM_CHECK_SCRIPT" \
  "$APPIUM_BASE_URL" \
  "$CURRENT_ANDROID_SERIAL_ID" \
  "$CURRENT_ANDROID_AVD_NAME" \
  "$ANDROID_APPLICATION_ID" \
  "$ANDROID_ACTIVITY" \
  "$CURRENT_IOS_SIMULATOR_UDID" \
  "$CURRENT_IOS_SIMULATOR_NAME" \
  "$IOS_PLATFORM_VERSION" \
  "$IOS_APPLICATION_ID"; then
  print -u2 "V1 最大字号页面验收失败，Appium 日志末尾如下："
  tail -n 120 "$APPIUM_LOG" >&2 || true
  exit 1
fi

print "双虚拟机最大字号 V1 范围与手动录入检查通过，退出时恢复原字号"

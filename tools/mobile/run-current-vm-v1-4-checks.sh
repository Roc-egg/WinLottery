#!/bin/zsh

set -euo pipefail

readonly SCRIPT_DIRECTORY="${0:A:h}"
readonly REPOSITORY_ROOT="${SCRIPT_DIRECTORY:h:h}"
readonly APPIUM_CHECK_SCRIPT="$SCRIPT_DIRECTORY/v1-4-ai-appium-check.mjs"
readonly STALL_SERVER_SCRIPT="$SCRIPT_DIRECTORY/v1-4-ai-stall-server.mjs"
readonly APPIUM_PORT=4730
readonly APPIUM_BASE_URL="http://127.0.0.1:$APPIUM_PORT"
readonly STALL_SERVER_PORT=4740
readonly ANDROID_APPLICATION_ID="roc.win.lottery"
readonly ANDROID_ACTIVITY="roc.win.lottery.MainActivity"
readonly IOS_APPLICATION_ID="roc.win.lottery.WinLottery"
readonly IOS_PLATFORM_VERSION="26.5"
readonly ANDROID_STANDARD_FONT_SCALE="1.0"
readonly IOS_STANDARD_CONTENT_SIZE="large"
readonly SCREENSHOT_DIRECTORY="$REPOSITORY_ROOT/build/reports/v1-4-ai"

source "$SCRIPT_DIRECTORY/current-vm-guard.sh"

# 锁屏会使 iOS Simulator 文本框无法获得键盘焦点，必须在耗时构建前阻断。
verify_macos_unlocked() {
  local root_registry
  root_registry="$(ioreg -n Root -d1)"
  if print -r -- "$root_registry" | rg --fixed-strings '"CGSSessionScreenIsLocked"=Yes' >/dev/null; then
    current_vm_fail "Mac 当前处于锁屏状态，无法执行 iOS 键盘交互验收"
  fi
}

for required_command in appium curl ioreg nc node rg tail; do
  command -v "$required_command" >/dev/null || current_vm_fail "缺少命令 $required_command"
done
verify_macos_unlocked
verify_current_mobile_vms

[[ -f "$APPIUM_CHECK_SCRIPT" ]] || current_vm_fail "缺少 V1.4 Appium 检查脚本"
[[ -f "$STALL_SERVER_SCRIPT" ]] || current_vm_fail "缺少 V1.4 本机停滞服务"

readonly ANDROID_FONT_SCALE_BEFORE="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell settings get system font_scale | tr -d '\r')"
readonly IOS_CONTENT_SIZE_BEFORE="$(xcrun simctl ui "$CURRENT_IOS_SIMULATOR_UDID" content_size | tr -d '\r')"
[[ "$ANDROID_FONT_SCALE_BEFORE" == <->.<-> ]] || current_vm_fail "Android 原始字号比例格式异常"
[[ -n "$IOS_CONTENT_SIZE_BEFORE" ]] || current_vm_fail "无法读取 iOS 原始辅助字号"

readonly WORK_DIRECTORY="$(mktemp -d "${TMPDIR:-/tmp}/winlottery-v1-4.XXXXXX")"
readonly APPIUM_LOG="$WORK_DIRECTORY/appium.log"
readonly STALL_SERVER_LOG="$WORK_DIRECTORY/stall-server.log"
APPIUM_PROCESS_ID=""
STALL_SERVER_PROCESS_ID=""

# 退出时恢复双虚拟机原始字号、竖屏和 Release 应用前台状态。
cleanup() {
  if [[ -n "$APPIUM_PROCESS_ID" ]] && kill -0 "$APPIUM_PROCESS_ID" 2>/dev/null; then
    kill "$APPIUM_PROCESS_ID" 2>/dev/null || true
    wait "$APPIUM_PROCESS_ID" 2>/dev/null || true
  fi
  if [[ -n "$STALL_SERVER_PROCESS_ID" ]] && kill -0 "$STALL_SERVER_PROCESS_ID" 2>/dev/null; then
    kill "$STALL_SERVER_PROCESS_ID" 2>/dev/null || true
    wait "$STALL_SERVER_PROCESS_ID" 2>/dev/null || true
  fi
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell settings put system font_scale "$ANDROID_FONT_SCALE_BEFORE" >/dev/null 2>&1 || true
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell settings put system accelerometer_rotation 1 >/dev/null 2>&1 || true
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am force-stop "$ANDROID_APPLICATION_ID" >/dev/null 2>&1 || true
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am start -n "$ANDROID_APPLICATION_ID/$ANDROID_ACTIVITY" >/dev/null 2>&1 || true
  xcrun simctl ui "$CURRENT_IOS_SIMULATOR_UDID" content_size "$IOS_CONTENT_SIZE_BEFORE" >/dev/null 2>&1 || true
  xcrun simctl io "$CURRENT_IOS_SIMULATOR_UDID" orientation portrait >/dev/null 2>&1 || true
  xcrun simctl terminate "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_APPLICATION_ID" >/dev/null 2>&1 || true
  xcrun simctl launch "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_APPLICATION_ID" >/dev/null 2>&1 || true
  rm -rf -- "$WORK_DIRECTORY"
}
trap cleanup EXIT

# 使用标准字号重新启动双端 Release 应用，确保会话从相同状态开始。
restart_apps_with_standard_font() {
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell settings put system font_scale "$ANDROID_STANDARD_FONT_SCALE" >/dev/null
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am force-stop "$ANDROID_APPLICATION_ID"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am start -n "$ANDROID_APPLICATION_ID/$ANDROID_ACTIVITY" >/dev/null
  xcrun simctl ui "$CURRENT_IOS_SIMULATOR_UDID" content_size "$IOS_STANDARD_CONTENT_SIZE"
  xcrun simctl terminate "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_APPLICATION_ID" >/dev/null 2>&1 || true
  xcrun simctl launch "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_APPLICATION_ID" >/dev/null
}

# 等待其他测试释放 Android UiAutomation，避免连接到正在执行的验收会话。
wait_for_android_instrumentation_slot() {
  local attempt
  local activity_snapshot
  for attempt in {1..180}; do
    activity_snapshot="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell dumpsys activity)"
    if ! print -r -- "$activity_snapshot" | rg --count-matches --ignore-case 'Active instrumentation|ActiveInstrumentation' >/dev/null; then
      return
    fi
    if (( attempt == 1 )); then
      print "检测到其他 Android instrumentation，等待其释放 UiAutomation"
    fi
    sleep 1
  done
  current_vm_fail "等待其他 Android instrumentation 释放 UiAutomation 超时"
}

# 先复用非 Debug 构建、签名边界、双端安装和启动闸门。
"$SCRIPT_DIRECTORY/run-current-vm-release-checks.sh"
restart_apps_with_standard_font

node "$STALL_SERVER_SCRIPT" "$STALL_SERVER_PORT" >"$STALL_SERVER_LOG" 2>&1 &
STALL_SERVER_PROCESS_ID=$!
for _ in {1..100}; do
  if nc -z 127.0.0.1 "$STALL_SERVER_PORT" >/dev/null 2>&1; then
    break
  fi
  if ! kill -0 "$STALL_SERVER_PROCESS_ID" 2>/dev/null; then
    print -u2 "V1.4 本机停滞服务提前退出"
    tail -n 80 "$STALL_SERVER_LOG" >&2 || true
    exit 1
  fi
  sleep 0.1
done
nc -z 127.0.0.1 "$STALL_SERVER_PORT" >/dev/null 2>&1 || current_vm_fail "等待 V1.4 本机停滞服务就绪超时"

wait_for_android_instrumentation_slot
appium \
  --address 127.0.0.1 \
  --port "$APPIUM_PORT" \
  --log-level warn \
  --log-no-colors \
  >"$APPIUM_LOG" 2>&1 &
APPIUM_PROCESS_ID=$!

for _ in {1..100}; do
  if curl --silent --show-error --fail "$APPIUM_BASE_URL/status" >/dev/null 2>&1; then
    break
  fi
  if ! kill -0 "$APPIUM_PROCESS_ID" 2>/dev/null; then
    print -u2 "V1.4 验收 Appium 提前退出"
    tail -n 160 "$APPIUM_LOG" >&2 || true
    exit 1
  fi
  sleep 0.1
done
curl --silent --show-error --fail "$APPIUM_BASE_URL/status" >/dev/null || current_vm_fail "等待 V1.4 Appium 就绪超时"

if ! node \
  "$APPIUM_CHECK_SCRIPT" \
  "$APPIUM_BASE_URL" \
  "$SCREENSHOT_DIRECTORY" \
  "$CURRENT_ANDROID_SERIAL_ID" \
  "$CURRENT_ANDROID_AVD_NAME" \
  "$ANDROID_APPLICATION_ID" \
  "$ANDROID_ACTIVITY" \
  "$CURRENT_IOS_SIMULATOR_UDID" \
  "$CURRENT_IOS_SIMULATOR_NAME" \
  "$IOS_PLATFORM_VERSION" \
  "$IOS_APPLICATION_ID" \
  "$STALL_SERVER_PORT"; then
  print -u2 "V1.4 双虚拟机 AI 专项失败，Appium 日志末尾如下："
  tail -n 160 "$APPIUM_LOG" >&2 || true
  print -u2 "截图证据保留于：$SCREENSHOT_DIRECTORY"
  exit 1
fi

print "双虚拟机 V1.4 五项导航、双彩种、五档样本、会话密钥、精确确认、竖横屏和主动取消检查通过"
print "截图证据：$SCREENSHOT_DIRECTORY"

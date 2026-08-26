#!/bin/zsh

set -euo pipefail

readonly SCRIPT_DIRECTORY="${0:A:h}"
readonly REPOSITORY_ROOT="${SCRIPT_DIRECTORY:h:h}"
readonly ANDROID_APPLICATION_ID="roc.win.lottery"
readonly ANDROID_ACTIVITY="roc.win.lottery.MainActivity"
readonly ANDROID_DEBUG_CRASH_EXTRA="roc.win.lottery.debug.CRASH_ON_CREATE"
readonly ANDROID_MEMORY_PRESSURE_LOG_TAG="WinLotteryMemoryPressure"
readonly ANDROID_RUNNING_CRITICAL_LEVEL="15"
readonly IOS_EXPECTED_BUNDLE_ID="roc.win.lottery.WinLottery"
readonly IOS_DEBUG_CRASH_ENVIRONMENT="WINLOTTERY_DEBUG_CRASH_ON_LAUNCH"
readonly CONTROLLED_CRASH_MARKER="WinLotteryControlledCrashAcceptance"
readonly MEMORY_PRESSURE_MARKER="WinLotteryMemoryPressureAcceptance"
readonly UPGRADE_BASE_REVISION="724adec"
readonly UPGRADE_EXPECTED_BASE_BUILD_NUMBER="4"
readonly UPGRADE_EXPECTED_BASE_VERSION_NAME="1.2.0"
readonly UPGRADE_EXPECTED_CURRENT_BUILD_NUMBER="5"
readonly UPGRADE_EXPECTED_CURRENT_VERSION_NAME="1.3.0"
readonly UPGRADE_RECORDS_CHECK_SCRIPT="$SCRIPT_DIRECTORY/v1-1-records-appium-check.mjs"
readonly UPGRADE_APPIUM_PORT=4727
readonly UPGRADE_APPIUM_BASE_URL="http://127.0.0.1:$UPGRADE_APPIUM_PORT"
readonly UPGRADE_IOS_PLATFORM_VERSION="26.5"
readonly VERIFY_INSTALL_LIFECYCLE="${WINLOTTERY_VERIFY_INSTALL_LIFECYCLE:-0}"
readonly VERIFY_PROCESS_RECOVERY="${WINLOTTERY_VERIFY_PROCESS_RECOVERY:-0}"
readonly VERIFY_ABRUPT_TERMINATION="${WINLOTTERY_VERIFY_ABRUPT_TERMINATION:-0}"
readonly VERIFY_CRASH_RECOVERY="${WINLOTTERY_VERIFY_CRASH_RECOVERY:-0}"
readonly VERIFY_MEMORY_PRESSURE="${WINLOTTERY_VERIFY_MEMORY_PRESSURE:-0}"
readonly VERIFY_VERSION_UPGRADE="${WINLOTTERY_VERIFY_VERSION_UPGRADE:-0}"
ios_upgrade_persistent_directories=()
upgrade_appium_process_id=""
upgrade_evidence_directory=""
upgrade_appium_log=""
upgrade_screenshot_directory=""

source "$SCRIPT_DIRECTORY/current-vm-guard.sh"

# 失败时尽力恢复 Release 应用，并清理本轮签名 APK 与 Xcode DerivedData。
cleanup_release_check() {
  if [[ -n "$upgrade_appium_process_id" ]] && kill -0 "$upgrade_appium_process_id" 2>/dev/null; then
    kill "$upgrade_appium_process_id" 2>/dev/null || true
    wait "$upgrade_appium_process_id" 2>/dev/null || true
  fi
  if [[ "${android_restore_required:-0}" == "1" && -s "${signed_android_apk:-}" ]]; then
    adb -s "$CURRENT_ANDROID_SERIAL_ID" install -r -d "$signed_android_apk" >/dev/null 2>&1 || true
    adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am start \
      -n "$ANDROID_APPLICATION_ID/$ANDROID_ACTIVITY" >/dev/null 2>&1 || true
  fi
  if [[ "${ios_restore_required:-0}" == "1" && -d "${ios_release_app:-}" ]]; then
    xcrun simctl install "$CURRENT_IOS_SIMULATOR_UDID" "$ios_release_app" >/dev/null 2>&1 || true
    xcrun simctl launch --terminate-running-process \
      "$CURRENT_IOS_SIMULATOR_UDID" \
      "$IOS_EXPECTED_BUNDLE_ID" >/dev/null 2>&1 || true
  fi
  local persistent_directory
  for persistent_directory in "${ios_upgrade_persistent_directories[@]}"; do
    rm -f -- "$persistent_directory/persistent-marker"
    rmdir "$persistent_directory" 2>/dev/null || true
  done
  if [[ -n "${release_check_directory:-}" && -d "$release_check_directory" ]]; then
    rm -rf -- "$release_check_directory"
  fi
}

# 启动只服务于真实 Room 覆盖升级阶段的本机 Appium，并保留截图与日志供复核。
start_upgrade_appium() {
  upgrade_evidence_directory="$(mktemp -d "${TMPDIR:-/tmp}/winlottery-version-upgrade.XXXXXX")"
  upgrade_screenshot_directory="$upgrade_evidence_directory/screenshots"
  upgrade_appium_log="$upgrade_evidence_directory/appium.log"
  mkdir -p "$upgrade_screenshot_directory"

  appium \
    --address 127.0.0.1 \
    --port "$UPGRADE_APPIUM_PORT" \
    --log-level warn \
    --log-no-colors \
    >"$upgrade_appium_log" 2>&1 &
  upgrade_appium_process_id=$!

  for _ in {1..100}; do
    if curl --silent --show-error --fail "$UPGRADE_APPIUM_BASE_URL/status" >/dev/null 2>&1; then
      return
    fi
    if ! kill -0 "$upgrade_appium_process_id" 2>/dev/null; then
      print -u2 "升级验收 Appium 提前退出"
      tail -n 120 "$upgrade_appium_log" >&2 || true
      current_vm_fail "无法启动升级验收 Appium"
    fi
    sleep 0.1
  done
  current_vm_fail "等待升级验收 Appium 就绪超时"
}

# 对指定平台执行一次旧版建档、新版读回或合成记录清理，并统一保留失败诊断。
run_upgrade_record_phase() {
  local phase="$1"
  local target_platform="$2"
  if ! node \
    "$UPGRADE_RECORDS_CHECK_SCRIPT" \
    "$UPGRADE_APPIUM_BASE_URL" \
    "$phase" \
    "$upgrade_screenshot_directory" \
    "$CURRENT_ANDROID_SERIAL_ID" \
    "$CURRENT_ANDROID_AVD_NAME" \
    "$ANDROID_APPLICATION_ID" \
    "$ANDROID_ACTIVITY" \
    "$CURRENT_IOS_SIMULATOR_UDID" \
    "$CURRENT_IOS_SIMULATOR_NAME" \
    "$UPGRADE_IOS_PLATFORM_VERSION" \
    "$IOS_EXPECTED_BUNDLE_ID" \
    "$target_platform"; then
    print -u2 "版本 $phase/$target_platform 真实 Room 升级验收失败"
    tail -n 120 "$upgrade_appium_log" >&2 || true
    print -u2 "升级验收证据保留于：$upgrade_evidence_directory"
    exit 1
  fi
}

# 要求文件存在且非空，避免后续命令误用不存在的构建产物。
require_release_file() {
  [[ -s "$1" ]] || current_vm_fail "缺少构建产物 $1"
}

# 使用同一本机验收证书重新签名 APK，只供锁定 Android 虚拟机覆盖安装。
sign_android_acceptance_apk() {
  local source_apk="$1"
  local aligned_apk="$2"
  local signed_apk="$3"

  "$zipalign_command" -f 4 "$source_apk" "$aligned_apk"
  "$apksigner_command" sign \
    --ks "$android_test_keystore" \
    --ks-key-alias androiddebugkey \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out "$signed_apk" \
    "$aligned_apk"
  "$apksigner_command" verify --verbose "$signed_apk"
}

# 从 APK 清单读取纯数字构建号，供升级方向检查使用。
read_android_version_code() {
  local apk="$1"
  "$aapt_command" dump badging "$apk" |
    sed -n "s/^package: .*versionCode='\([^']*\)'.*/\1/p" |
    head -n 1
}

# 从 APK 清单读取展示版本，供升级方向检查使用。
read_android_version_name() {
  local apk="$1"
  "$aapt_command" dump badging "$apk" |
    sed -n "s/^package: .*versionName='\([^']*\)'.*/\1/p" |
    head -n 1
}

# 强制停止 Android 应用后重新启动，验证两类遗留临时图片都会在初始化时清扫。
verify_android_process_recovery() {
  local debug_apk="$1"
  local release_apk="$2"
  local ticket_marker="cache/ticket-images/process-marker.jpg"
  local camera_marker="cache/camera-captures/process-marker.jpg"

  android_restore_required=1
  adb -s "$CURRENT_ANDROID_SERIAL_ID" install -r -d "$debug_apk"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am start -W -n "$ANDROID_APPLICATION_ID/$ANDROID_ACTIVITY"
  local debug_package_flags
  debug_package_flags="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell dumpsys package "$ANDROID_APPLICATION_ID" | rg 'pkgFlags=' | head -n 1)"
  [[ "$debug_package_flags" == *DEBUGGABLE* ]] || current_vm_fail "Android 进程恢复测试包不可调试"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell run-as "$ANDROID_APPLICATION_ID" \
    mkdir -p cache/ticket-images cache/camera-captures
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell run-as "$ANDROID_APPLICATION_ID" touch "$ticket_marker" "$camera_marker"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell run-as "$ANDROID_APPLICATION_ID" test -f "$ticket_marker" ||
    current_vm_fail "Android 强制停止前票图标记不存在"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell run-as "$ANDROID_APPLICATION_ID" test -f "$camera_marker" ||
    current_vm_fail "Android 强制停止前成片标记不存在"

  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am force-stop "$ANDROID_APPLICATION_ID"
  [[ -z "$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell pidof "$ANDROID_APPLICATION_ID" | tr -d '\r')" ]] ||
    current_vm_fail "Android force-stop 后进程仍存活"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am start -W -n "$ANDROID_APPLICATION_ID/$ANDROID_ACTIVITY"
  if adb -s "$CURRENT_ANDROID_SERIAL_ID" shell run-as "$ANDROID_APPLICATION_ID" test -e "$ticket_marker" ||
    adb -s "$CURRENT_ANDROID_SERIAL_ID" shell run-as "$ANDROID_APPLICATION_ID" test -e "$camera_marker"; then
    current_vm_fail "Android 重新启动后仍存在进程终止前匿名标记"
  fi

  adb -s "$CURRENT_ANDROID_SERIAL_ID" install -r -d "$release_apk"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am force-stop "$ANDROID_APPLICATION_ID"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am start -W -n "$ANDROID_APPLICATION_ID/$ANDROID_ACTIVITY"
  local release_package_flags
  release_package_flags="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell dumpsys package "$ANDROID_APPLICATION_ID" | rg 'pkgFlags=' | head -n 1)"
  [[ "$release_package_flags" != *DEBUGGABLE* ]] || current_vm_fail "Android 进程恢复专项结束后没有恢复 Release 包"
  android_process_id="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell pidof "$ANDROID_APPLICATION_ID" | tr -d '\r')"
  [[ -n "$android_process_id" ]] || current_vm_fail "Android 进程恢复专项结束后 Release 进程未存活"
  android_restore_required=0
}

# 终止 iOS Simulator 应用后重新启动，验证数据容器内遗留票图会在初始化时清扫。
verify_ios_process_recovery() {
  local expected_data_prefix="$HOME/Library/Developer/CoreSimulator/Devices/$CURRENT_IOS_SIMULATOR_UDID/data/Containers/Data/Application/"
  local data_container
  data_container="$(xcrun simctl get_app_container "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_EXPECTED_BUNDLE_ID" data)"
  [[ "$data_container" == "$expected_data_prefix"* ]] || current_vm_fail "iOS 数据容器不属于指定 Simulator"
  local marker_directory="$data_container/tmp/WinLotteryTicketImages"
  local marker_path="$marker_directory/process-marker.jpg"
  sleep 1
  mkdir -p "$marker_directory"
  touch "$marker_path"
  [[ -f "$marker_path" ]] || current_vm_fail "iOS 进程终止前匿名标记不存在"

  ios_restore_required=1
  xcrun simctl terminate "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_EXPECTED_BUNDLE_ID"
  ios_launch_result="$({
    xcrun simctl launch "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_EXPECTED_BUNDLE_ID"
  })"
  [[ "$ios_launch_result" == *:* ]] || current_vm_fail "iOS 进程恢复专项重新启动结果异常"
  sleep 1
  [[ ! -e "$marker_path" ]] || current_vm_fail "iOS 重新启动后仍存在进程终止前匿名标记"
  ios_restore_required=0
}

# 使用应用自身 UID 强杀 Android 主进程，验证异常终止后的初始化清扫和 Release 恢复。
verify_android_abrupt_termination() {
  local debug_apk="$1"
  local release_apk="$2"
  local ticket_marker="cache/ticket-images/abrupt-marker.jpg"
  local camera_marker="cache/camera-captures/abrupt-marker.jpg"

  android_restore_required=1
  adb -s "$CURRENT_ANDROID_SERIAL_ID" install -r -d "$debug_apk"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am start -W -n "$ANDROID_APPLICATION_ID/$ANDROID_ACTIVITY"
  local debug_package_flags
  debug_package_flags="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell dumpsys package "$ANDROID_APPLICATION_ID" | rg 'pkgFlags=' | head -n 1)"
  [[ "$debug_package_flags" == *DEBUGGABLE* ]] || current_vm_fail "Android 异常终止测试包不可调试"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell run-as "$ANDROID_APPLICATION_ID" \
    mkdir -p cache/ticket-images cache/camera-captures
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell run-as "$ANDROID_APPLICATION_ID" touch "$ticket_marker" "$camera_marker"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell run-as "$ANDROID_APPLICATION_ID" test -f "$ticket_marker" ||
    current_vm_fail "Android 异常终止前票图标记不存在"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell run-as "$ANDROID_APPLICATION_ID" test -f "$camera_marker" ||
    current_vm_fail "Android 异常终止前成片标记不存在"

  local killed_process_id
  killed_process_id="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell pidof "$ANDROID_APPLICATION_ID" | tr -d '\r')"
  print -r -- "$killed_process_id" | rg -q '^[0-9]+$' ||
    current_vm_fail "Android 目标应用 PID 不是单个纯数字"
  local process_name
  process_name="$({
    adb -s "$CURRENT_ANDROID_SERIAL_ID" shell run-as "$ANDROID_APPLICATION_ID" \
      cat "/proc/$killed_process_id/cmdline" | tr '\000' '\n' | head -n 1 | tr -d '\r'
  })"
  [[ "$process_name" == "$ANDROID_APPLICATION_ID" ]] ||
    current_vm_fail "Android PID 不属于目标应用主进程"
  local app_user_id process_user_id
  app_user_id="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell run-as "$ANDROID_APPLICATION_ID" id -u | tr -d '\r')"
  process_user_id="$({
    adb -s "$CURRENT_ANDROID_SERIAL_ID" shell run-as "$ANDROID_APPLICATION_ID" \
      cat "/proc/$killed_process_id/status" | awk '$1 == "Uid:" { print $2; exit }'
  })"
  print -r -- "$app_user_id" | rg -q '^[0-9]+$' || current_vm_fail "Android 应用 UID 不是纯数字"
  [[ "$process_user_id" == "$app_user_id" ]] || current_vm_fail "Android PID 与目标应用 UID 不一致"

  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell run-as "$ANDROID_APPLICATION_ID" kill -9 "$killed_process_id"
  sleep 1
  [[ -z "$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell pidof "$ANDROID_APPLICATION_ID" | tr -d '\r')" ]] ||
    current_vm_fail "Android SIGKILL 后目标应用进程仍存活"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am start -W -n "$ANDROID_APPLICATION_ID/$ANDROID_ACTIVITY"
  if adb -s "$CURRENT_ANDROID_SERIAL_ID" shell run-as "$ANDROID_APPLICATION_ID" test -e "$ticket_marker" ||
    adb -s "$CURRENT_ANDROID_SERIAL_ID" shell run-as "$ANDROID_APPLICATION_ID" test -e "$camera_marker"; then
    current_vm_fail "Android 异常终止后重新启动仍存在匿名标记"
  fi

  adb -s "$CURRENT_ANDROID_SERIAL_ID" install -r -d "$release_apk"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am force-stop "$ANDROID_APPLICATION_ID"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am start -W -n "$ANDROID_APPLICATION_ID/$ANDROID_ACTIVITY"
  local release_package_flags
  release_package_flags="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell dumpsys package "$ANDROID_APPLICATION_ID" | rg 'pkgFlags=' | head -n 1)"
  [[ "$release_package_flags" != *DEBUGGABLE* ]] || current_vm_fail "Android 异常终止专项结束后没有恢复 Release 包"
  android_process_id="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell pidof "$ANDROID_APPLICATION_ID" | tr -d '\r')"
  print -r -- "$android_process_id" | rg -q '^[0-9]+$' ||
    current_vm_fail "Android 异常终止专项结束后 Release 进程未存活"
  android_restore_required=0
}

# 强杀指定 Simulator 中的 iOS Release 进程，验证异常终止后的初始化清扫。
verify_ios_abrupt_termination() {
  local initial_launch_result="$1"
  local expected_data_prefix="$HOME/Library/Developer/CoreSimulator/Devices/$CURRENT_IOS_SIMULATOR_UDID/data/Containers/Data/Application/"
  local expected_app_prefix="$HOME/Library/Developer/CoreSimulator/Devices/$CURRENT_IOS_SIMULATOR_UDID/data/Containers/Bundle/Application/"
  local data_container app_container
  data_container="$(xcrun simctl get_app_container "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_EXPECTED_BUNDLE_ID" data)"
  app_container="$(xcrun simctl get_app_container "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_EXPECTED_BUNDLE_ID" app)"
  [[ "$data_container" == "$expected_data_prefix"* ]] || current_vm_fail "iOS 数据容器不属于指定 Simulator"
  [[ "$app_container" == "$expected_app_prefix"* ]] || current_vm_fail "iOS 应用容器不属于指定 Simulator"
  local marker_directory="$data_container/tmp/WinLotteryTicketImages"
  local marker_path="$marker_directory/abrupt-marker.jpg"
  mkdir -p "$marker_directory"
  touch "$marker_path"
  [[ -f "$marker_path" ]] || current_vm_fail "iOS 异常终止前匿名标记不存在"

  [[ "$initial_launch_result" == "$IOS_EXPECTED_BUNDLE_ID: "* ]] ||
    current_vm_fail "iOS 启动结果不属于目标应用"
  local killed_process_id="${initial_launch_result##*: }"
  print -r -- "$killed_process_id" | rg -q '^[0-9]+$' || current_vm_fail "iOS 目标应用 PID 不是纯数字"
  local expected_executable="$app_container/WinLottery"
  local process_command
  process_command="$(ps -p "$killed_process_id" -o command= | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
  [[ "$process_command" == "$expected_executable" || "$process_command" == "$expected_executable "* ]] ||
    current_vm_fail "iOS PID 不属于指定 Simulator 的目标应用"

  ios_restore_required=1
  kill -9 "$killed_process_id"
  local wait_attempt
  for wait_attempt in {1..50}; do
    ps -p "$killed_process_id" >/dev/null 2>&1 || break
    sleep 0.1
  done
  ps -p "$killed_process_id" >/dev/null 2>&1 && current_vm_fail "iOS SIGKILL 后目标应用进程仍存活"
  ios_launch_result="$({
    xcrun simctl launch "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_EXPECTED_BUNDLE_ID"
  })"
  [[ "$ios_launch_result" == "$IOS_EXPECTED_BUNDLE_ID: "* ]] ||
    current_vm_fail "iOS 异常终止后重新启动结果异常"
  sleep 1
  [[ ! -e "$marker_path" ]] || current_vm_fail "iOS 异常终止后重新启动仍存在匿名标记"
  ios_restore_required=0
}

# 制造 Android Debug 主线程未捕获异常，验证崩溃证据、启动清扫和 Release 反向边界。
verify_android_crash_recovery() {
  local debug_apk="$1"
  local release_apk="$2"
  local ticket_marker="cache/ticket-images/crash-marker.jpg"
  local camera_marker="cache/camera-captures/crash-marker.jpg"

  android_restore_required=1
  adb -s "$CURRENT_ANDROID_SERIAL_ID" install -r -d "$debug_apk"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am force-stop "$ANDROID_APPLICATION_ID"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am start -W -n "$ANDROID_APPLICATION_ID/$ANDROID_ACTIVITY"
  local debug_package_flags
  debug_package_flags="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell dumpsys package "$ANDROID_APPLICATION_ID" | rg 'pkgFlags=' | head -n 1)"
  [[ "$debug_package_flags" == *DEBUGGABLE* ]] || current_vm_fail "Android 崩溃恢复测试包不可调试"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell run-as "$ANDROID_APPLICATION_ID" \
    mkdir -p cache/ticket-images cache/camera-captures
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell run-as "$ANDROID_APPLICATION_ID" touch "$ticket_marker" "$camera_marker"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell run-as "$ANDROID_APPLICATION_ID" test -f "$ticket_marker" ||
    current_vm_fail "Android 崩溃前票图标记不存在"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell run-as "$ANDROID_APPLICATION_ID" test -f "$camera_marker" ||
    current_vm_fail "Android 崩溃前成片标记不存在"

  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am force-stop "$ANDROID_APPLICATION_ID"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" logcat -b crash -c
  {
    adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am start -W \
      -n "$ANDROID_APPLICATION_ID/$ANDROID_ACTIVITY" \
      --ez "$ANDROID_DEBUG_CRASH_EXTRA" true >/dev/null 2>&1
  } || true
  sleep 2
  [[ -z "$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell pidof "$ANDROID_APPLICATION_ID" | tr -d '\r')" ]] ||
    current_vm_fail "Android 未捕获异常后目标应用进程仍存活"
  local crash_log
  crash_log="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" logcat -b crash -d -t 200)"
  print -r -- "$crash_log" | rg -Fq "Process: $ANDROID_APPLICATION_ID" ||
    current_vm_fail "Android crash 日志不属于目标应用"
  print -r -- "$crash_log" | rg -Fq "$CONTROLLED_CRASH_MARKER" ||
    current_vm_fail "Android crash 日志缺少受控异常标记"

  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am start -W -n "$ANDROID_APPLICATION_ID/$ANDROID_ACTIVITY"
  if adb -s "$CURRENT_ANDROID_SERIAL_ID" shell run-as "$ANDROID_APPLICATION_ID" test -e "$ticket_marker" ||
    adb -s "$CURRENT_ANDROID_SERIAL_ID" shell run-as "$ANDROID_APPLICATION_ID" test -e "$camera_marker"; then
    current_vm_fail "Android 崩溃后重新启动仍存在匿名标记"
  fi

  adb -s "$CURRENT_ANDROID_SERIAL_ID" install -r -d "$release_apk"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am force-stop "$ANDROID_APPLICATION_ID"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am start -W \
    -n "$ANDROID_APPLICATION_ID/$ANDROID_ACTIVITY" \
    --ez "$ANDROID_DEBUG_CRASH_EXTRA" true
  sleep 1
  local release_package_flags
  release_package_flags="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell dumpsys package "$ANDROID_APPLICATION_ID" | rg 'pkgFlags=' | head -n 1)"
  [[ "$release_package_flags" != *DEBUGGABLE* ]] || current_vm_fail "Android 崩溃专项结束后没有恢复 Release 包"
  android_process_id="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell pidof "$ANDROID_APPLICATION_ID" | tr -d '\r')"
  print -r -- "$android_process_id" | rg -q '^[0-9]+$' ||
    current_vm_fail "Android Release 包没有忽略崩溃验收参数"
  android_restore_required=0
}

# 制造 iOS Debug fatalError，验证崩溃证据、启动清扫和 Release 反向边界。
verify_ios_crash_recovery() {
  local debug_app="$1"
  local release_app="$2"
  local expected_data_prefix="$HOME/Library/Developer/CoreSimulator/Devices/$CURRENT_IOS_SIMULATOR_UDID/data/Containers/Data/Application/"
  local expected_app_prefix="$HOME/Library/Developer/CoreSimulator/Devices/$CURRENT_IOS_SIMULATOR_UDID/data/Containers/Bundle/Application/"

  ios_restore_required=1
  xcrun simctl install "$CURRENT_IOS_SIMULATOR_UDID" "$debug_app"
  xcrun simctl launch --terminate-running-process "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_EXPECTED_BUNDLE_ID"
  local data_container debug_app_container
  data_container="$(xcrun simctl get_app_container "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_EXPECTED_BUNDLE_ID" data)"
  debug_app_container="$(xcrun simctl get_app_container "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_EXPECTED_BUNDLE_ID" app)"
  [[ "$data_container" == "$expected_data_prefix"* ]] || current_vm_fail "iOS Debug 数据容器不属于指定 Simulator"
  [[ "$debug_app_container" == "$expected_app_prefix"* ]] || current_vm_fail "iOS Debug 应用容器不属于指定 Simulator"
  local marker_directory="$data_container/tmp/WinLotteryTicketImages"
  local marker_path="$marker_directory/crash-marker.jpg"
  mkdir -p "$marker_directory"
  touch "$marker_path"
  [[ -f "$marker_path" ]] || current_vm_fail "iOS 崩溃前匿名标记不存在"

  xcrun simctl terminate "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_EXPECTED_BUNDLE_ID"
  local crash_output
  crash_output="$({
    env "SIMCTL_CHILD_${IOS_DEBUG_CRASH_ENVIRONMENT}=1" \
      xcrun simctl launch --console "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_EXPECTED_BUNDLE_ID" 2>&1
  } || true)"
  print -r -- "$crash_output" | rg -Fq "$CONTROLLED_CRASH_MARKER" ||
    current_vm_fail "iOS Debug 崩溃输出缺少受控标记"
  local debug_executable="$debug_app_container/WinLottery"
  if ps -axo command= | rg -Fx "$debug_executable" >/dev/null; then
    current_vm_fail "iOS fatalError 后目标应用进程仍存活"
  fi

  ios_launch_result="$({
    xcrun simctl launch "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_EXPECTED_BUNDLE_ID"
  })"
  [[ "$ios_launch_result" == "$IOS_EXPECTED_BUNDLE_ID: "* ]] ||
    current_vm_fail "iOS 崩溃后重新启动结果异常"
  sleep 1
  [[ ! -e "$marker_path" ]] || current_vm_fail "iOS 崩溃后重新启动仍存在匿名标记"

  xcrun simctl install "$CURRENT_IOS_SIMULATOR_UDID" "$release_app"
  ios_launch_result="$({
    env "SIMCTL_CHILD_${IOS_DEBUG_CRASH_ENVIRONMENT}=1" \
      xcrun simctl launch --terminate-running-process \
        "$CURRENT_IOS_SIMULATOR_UDID" \
        "$IOS_EXPECTED_BUNDLE_ID"
  })"
  [[ "$ios_launch_result" == "$IOS_EXPECTED_BUNDLE_ID: "* ]] ||
    current_vm_fail "iOS Release 包崩溃参数反向检查启动结果异常"
  local release_process_id="${ios_launch_result##*: }"
  print -r -- "$release_process_id" | rg -q '^[0-9]+$' || current_vm_fail "iOS Release 进程 PID 不是纯数字"
  local release_app_container
  release_app_container="$(xcrun simctl get_app_container "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_EXPECTED_BUNDLE_ID" app)"
  [[ "$release_app_container" == "$expected_app_prefix"* ]] || current_vm_fail "iOS Release 应用容器不属于指定 Simulator"
  local release_process_command
  release_process_command="$(ps -p "$release_process_id" -o command= | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
  [[ "$release_process_command" == "$release_app_container/WinLottery" ]] ||
    current_vm_fail "iOS Release 包没有忽略崩溃验收参数"
  ios_restore_required=0
}

# 向 Android Debug 进程发送临界内存收紧通知，验证回调送达、存活和 Release 反向边界。
verify_android_memory_pressure() {
  local debug_apk="$1"
  local release_apk="$2"

  android_restore_required=1
  adb -s "$CURRENT_ANDROID_SERIAL_ID" install -r -d "$debug_apk"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am force-stop "$ANDROID_APPLICATION_ID"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am start -W -n "$ANDROID_APPLICATION_ID/$ANDROID_ACTIVITY"
  local debug_package_flags
  debug_package_flags="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell dumpsys package "$ANDROID_APPLICATION_ID" | rg 'pkgFlags=' | head -n 1)"
  [[ "$debug_package_flags" == *DEBUGGABLE* ]] || current_vm_fail "Android 内存压力测试包不可调试"
  local debug_process_id
  debug_process_id="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell pidof "$ANDROID_APPLICATION_ID" | tr -d '\r')"
  print -r -- "$debug_process_id" | rg -q '^[0-9]+$' || current_vm_fail "Android Debug 进程 PID 不是纯数字"

  adb -s "$CURRENT_ANDROID_SERIAL_ID" logcat -b main -c
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am send-trim-memory \
    "$ANDROID_APPLICATION_ID" \
    RUNNING_CRITICAL
  sleep 1
  local debug_process_after
  debug_process_after="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell pidof "$ANDROID_APPLICATION_ID" | tr -d '\r')"
  [[ "$debug_process_after" == "$debug_process_id" ]] || current_vm_fail "Android 内存收紧通知后 Debug 进程没有保持存活"
  local memory_log
  memory_log="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" logcat -b main -d -s "$ANDROID_MEMORY_PRESSURE_LOG_TAG:I" '*:S')"
  print -r -- "$memory_log" | rg -Fq "$MEMORY_PRESSURE_MARKER:$ANDROID_RUNNING_CRITICAL_LEVEL" ||
    current_vm_fail "Android Debug 没有收到 RUNNING_CRITICAL 回调"

  adb -s "$CURRENT_ANDROID_SERIAL_ID" install -r -d "$release_apk"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am force-stop "$ANDROID_APPLICATION_ID"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am start -W -n "$ANDROID_APPLICATION_ID/$ANDROID_ACTIVITY"
  local release_package_flags
  release_package_flags="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell dumpsys package "$ANDROID_APPLICATION_ID" | rg 'pkgFlags=' | head -n 1)"
  [[ "$release_package_flags" != *DEBUGGABLE* ]] || current_vm_fail "Android 内存压力专项结束后没有恢复 Release 包"
  android_process_id="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell pidof "$ANDROID_APPLICATION_ID" | tr -d '\r')"
  print -r -- "$android_process_id" | rg -q '^[0-9]+$' || current_vm_fail "Android Release 进程 PID 不是纯数字"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" logcat -b main -c
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am send-trim-memory \
    "$ANDROID_APPLICATION_ID" \
    RUNNING_CRITICAL
  sleep 1
  local release_process_after
  release_process_after="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell pidof "$ANDROID_APPLICATION_ID" | tr -d '\r')"
  [[ "$release_process_after" == "$android_process_id" ]] || current_vm_fail "Android 内存收紧通知后 Release 进程没有保持存活"
  if adb -s "$CURRENT_ANDROID_SERIAL_ID" logcat -b main -d | rg -F "$MEMORY_PRESSURE_MARKER" >/dev/null; then
    current_vm_fail "Android Release 仍输出 Debug 内存压力标记"
  fi
  android_restore_required=0
}

# 向 iOS Debug 应用模拟 UIKit 内存警告，验证回调送达、进程存活和 Release 隔离。
verify_ios_memory_pressure() {
  local debug_app="$1"
  local release_app="$2"
  local expected_data_prefix="$HOME/Library/Developer/CoreSimulator/Devices/$CURRENT_IOS_SIMULATOR_UDID/data/Containers/Data/Application/"
  local expected_app_prefix="$HOME/Library/Developer/CoreSimulator/Devices/$CURRENT_IOS_SIMULATOR_UDID/data/Containers/Bundle/Application/"

  ios_restore_required=1
  xcrun simctl install "$CURRENT_IOS_SIMULATOR_UDID" "$debug_app"
  local debug_launch_result
  debug_launch_result="$({
    xcrun simctl launch --terminate-running-process \
      "$CURRENT_IOS_SIMULATOR_UDID" \
      "$IOS_EXPECTED_BUNDLE_ID"
  })"
  [[ "$debug_launch_result" == "$IOS_EXPECTED_BUNDLE_ID: "* ]] || current_vm_fail "iOS Debug 内存压力启动结果异常"
  local debug_process_id="${debug_launch_result##*: }"
  print -r -- "$debug_process_id" | rg -q '^[0-9]+$' || current_vm_fail "iOS Debug 进程 PID 不是纯数字"
  local debug_data_container debug_app_container
  debug_data_container="$(xcrun simctl get_app_container "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_EXPECTED_BUNDLE_ID" data)"
  debug_app_container="$(xcrun simctl get_app_container "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_EXPECTED_BUNDLE_ID" app)"
  [[ "$debug_data_container" == "$expected_data_prefix"* ]] || current_vm_fail "iOS Debug 数据容器不属于指定 Simulator"
  [[ "$debug_app_container" == "$expected_app_prefix"* ]] || current_vm_fail "iOS Debug 应用容器不属于指定 Simulator"
  local memory_marker="$debug_data_container/tmp/$MEMORY_PRESSURE_MARKER"
  [[ ! -e "$memory_marker" ]] || current_vm_fail "iOS 模拟内存警告前已存在旧标记"
  local debug_process_command
  debug_process_command="$(ps -p "$debug_process_id" -o command= | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
  [[ "$debug_process_command" == "$debug_app_container/WinLottery" ]] ||
    current_vm_fail "iOS Debug 进程不属于指定 Simulator 应用容器"

  xcrun lldb --batch \
    -p "$debug_process_id" \
    -o 'expr -l objc++ -O -- [[UIApplication sharedApplication] _performMemoryWarning]' \
    -o detach
  local wait_attempt
  for wait_attempt in {1..20}; do
    [[ -e "$memory_marker" ]] && break
    sleep 0.1
  done
  [[ -f "$memory_marker" ]] || current_vm_fail "iOS Debug 没有收到模拟 UIKit 内存警告"
  debug_process_command="$(ps -p "$debug_process_id" -o command= | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
  [[ "$debug_process_command" == "$debug_app_container/WinLottery" ]] ||
    current_vm_fail "iOS 模拟内存警告后 Debug 进程没有保持存活"
  rm -f -- "$memory_marker"

  xcrun simctl install "$CURRENT_IOS_SIMULATOR_UDID" "$release_app"
  ios_launch_result="$({
    xcrun simctl launch --terminate-running-process \
      "$CURRENT_IOS_SIMULATOR_UDID" \
      "$IOS_EXPECTED_BUNDLE_ID"
  })"
  [[ "$ios_launch_result" == "$IOS_EXPECTED_BUNDLE_ID: "* ]] || current_vm_fail "iOS Release 内存压力启动结果异常"
  local release_process_id="${ios_launch_result##*: }"
  print -r -- "$release_process_id" | rg -q '^[0-9]+$' || current_vm_fail "iOS Release 进程 PID 不是纯数字"
  local release_app_container
  release_app_container="$(xcrun simctl get_app_container "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_EXPECTED_BUNDLE_ID" app)"
  [[ "$release_app_container" == "$expected_app_prefix"* ]] || current_vm_fail "iOS Release 应用容器不属于指定 Simulator"
  local release_process_command
  release_process_command="$(ps -p "$release_process_id" -o command= | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
  [[ "$release_process_command" == "$release_app_container/WinLottery" ]] ||
    current_vm_fail "iOS 内存压力专项结束后 Release 进程没有保持存活"
  ios_restore_required=0
}

# 执行 Android Release 覆盖升级，验证真实 Room 记录与持久标记保留、临时图片清扫和 Release 恢复。
verify_android_version_upgrade() {
  local base_debug_apk="$1"
  local base_release_apk="$2"
  local current_debug_apk="$3"
  local current_release_apk="$4"
  local persistent_marker="files/upgrade-acceptance/persistent-marker"
  local ticket_marker="cache/ticket-images/upgrade-marker.jpg"
  local camera_marker="cache/camera-captures/upgrade-marker.jpg"
  local base_version_code current_version_code base_version_name current_version_name
  base_version_code="$(read_android_version_code "$base_release_apk")"
  current_version_code="$(read_android_version_code "$current_release_apk")"
  base_version_name="$(read_android_version_name "$base_release_apk")"
  current_version_name="$(read_android_version_name "$current_release_apk")"
  print -r -- "$base_version_code" | rg -q '^[0-9]+$' || current_vm_fail "Android 基线构建号不是纯数字"
  print -r -- "$current_version_code" | rg -q '^[0-9]+$' || current_vm_fail "Android 当前构建号不是纯数字"
  [[ "$base_version_code" == "$UPGRADE_EXPECTED_BASE_BUILD_NUMBER" ]] ||
    current_vm_fail "Android 基线构建号不是 $UPGRADE_EXPECTED_BASE_BUILD_NUMBER"
  [[ "$base_version_name" == "$UPGRADE_EXPECTED_BASE_VERSION_NAME" ]] ||
    current_vm_fail "Android 基线展示版本不是 $UPGRADE_EXPECTED_BASE_VERSION_NAME"
  [[ "$current_version_code" == "$UPGRADE_EXPECTED_CURRENT_BUILD_NUMBER" ]] ||
    current_vm_fail "Android 当前构建号不是 $UPGRADE_EXPECTED_CURRENT_BUILD_NUMBER"
  [[ "$current_version_name" == "$UPGRADE_EXPECTED_CURRENT_VERSION_NAME" ]] ||
    current_vm_fail "Android 当前展示版本不是 $UPGRADE_EXPECTED_CURRENT_VERSION_NAME"
  (( current_version_code > base_version_code )) || current_vm_fail "Android 当前构建号没有高于升级基线"

  android_restore_required=1
  # Android 用户版系统只允许已安装的可调试包降级，先用同版本同证书 Debug 包无损切换安装状态。
  adb -s "$CURRENT_ANDROID_SERIAL_ID" install --no-incremental -r -d "$current_debug_apk"
  local current_debug_package_flags
  current_debug_package_flags="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell dumpsys package "$ANDROID_APPLICATION_ID" | rg 'pkgFlags=' | head -n 1)"
  [[ "$current_debug_package_flags" == *DEBUGGABLE* ]] || current_vm_fail "Android 当前过渡测试包不可调试"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" install --no-incremental -r -d "$base_debug_apk"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am start -W -n "$ANDROID_APPLICATION_ID/$ANDROID_ACTIVITY"
  local debug_package_flags
  debug_package_flags="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell dumpsys package "$ANDROID_APPLICATION_ID" | rg 'pkgFlags=' | head -n 1)"
  [[ "$debug_package_flags" == *DEBUGGABLE* ]] || current_vm_fail "Android 升级基线测试包不可调试"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell run-as "$ANDROID_APPLICATION_ID" \
    mkdir -p files/upgrade-acceptance cache/ticket-images cache/camera-captures
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell run-as "$ANDROID_APPLICATION_ID" \
    touch "$persistent_marker" "$ticket_marker" "$camera_marker"

  adb -s "$CURRENT_ANDROID_SERIAL_ID" install -r "$base_release_apk"
  local base_package_flags
  base_package_flags="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell dumpsys package "$ANDROID_APPLICATION_ID" | rg 'pkgFlags=' | head -n 1)"
  [[ "$base_package_flags" != *DEBUGGABLE* ]] || current_vm_fail "Android 升级前没有切换到 Release 基线包"
  local installed_base_version
  installed_base_version="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell dumpsys package "$ANDROID_APPLICATION_ID" | sed -n 's/^[[:space:]]*versionCode=\([0-9]*\).*/\1/p' | head -n 1)"
  [[ "$installed_base_version" == "$base_version_code" ]] || current_vm_fail "Android 安装的基线构建号不匹配"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am force-stop "$ANDROID_APPLICATION_ID"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am start -W -n "$ANDROID_APPLICATION_ID/$ANDROID_ACTIVITY"
  run_upgrade_record_phase upgrade-prepare android

  adb -s "$CURRENT_ANDROID_SERIAL_ID" install -r "$current_release_apk"
  local current_package_flags
  current_package_flags="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell dumpsys package "$ANDROID_APPLICATION_ID" | rg 'pkgFlags=' | head -n 1)"
  [[ "$current_package_flags" != *DEBUGGABLE* ]] || current_vm_fail "Android 升级后没有保持 Release 包"
  local installed_current_version
  installed_current_version="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell dumpsys package "$ANDROID_APPLICATION_ID" | sed -n 's/^[[:space:]]*versionCode=\([0-9]*\).*/\1/p' | head -n 1)"
  [[ "$installed_current_version" == "$current_version_code" ]] || current_vm_fail "Android 升级后的构建号不匹配"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am start -W -n "$ANDROID_APPLICATION_ID/$ANDROID_ACTIVITY"
  sleep 1
  run_upgrade_record_phase upgrade-verify android
  run_upgrade_record_phase cleanup android

  adb -s "$CURRENT_ANDROID_SERIAL_ID" install -r -d "$current_debug_apk"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell run-as "$ANDROID_APPLICATION_ID" test -f "$persistent_marker" ||
    current_vm_fail "Android 构建号升级后匿名持久标记丢失"
  if adb -s "$CURRENT_ANDROID_SERIAL_ID" shell run-as "$ANDROID_APPLICATION_ID" test -e "$ticket_marker" ||
    adb -s "$CURRENT_ANDROID_SERIAL_ID" shell run-as "$ANDROID_APPLICATION_ID" test -e "$camera_marker"; then
    current_vm_fail "Android 构建号升级并启动后仍存在临时图片标记"
  fi
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell run-as "$ANDROID_APPLICATION_ID" rm -f "$persistent_marker"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" install -r -d "$current_release_apk"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am force-stop "$ANDROID_APPLICATION_ID"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am start -W -n "$ANDROID_APPLICATION_ID/$ANDROID_ACTIVITY"
  current_package_flags="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell dumpsys package "$ANDROID_APPLICATION_ID" | rg 'pkgFlags=' | head -n 1)"
  [[ "$current_package_flags" != *DEBUGGABLE* ]] || current_vm_fail "Android 升级专项结束后没有恢复 Release 包"
  android_process_id="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell pidof "$ANDROID_APPLICATION_ID" | tr -d '\r')"
  print -r -- "$android_process_id" | rg -q '^[0-9]+$' || current_vm_fail "Android 升级专项结束后 Release 进程未存活"
  android_restore_required=0
}

# 执行 iOS Release 覆盖升级，验证真实 Room 记录与匿名数据保留、临时票图清扫和 Release 恢复。
verify_ios_version_upgrade() {
  local base_release_app="$1"
  local current_release_app="$2"
  local base_info_plist="$base_release_app/Info.plist"
  local current_info_plist="$current_release_app/Info.plist"
  local base_bundle_version current_bundle_version base_marketing_version current_marketing_version
  base_bundle_version="$(plutil -extract CFBundleVersion raw "$base_info_plist")"
  current_bundle_version="$(plutil -extract CFBundleVersion raw "$current_info_plist")"
  base_marketing_version="$(plutil -extract CFBundleShortVersionString raw "$base_info_plist")"
  current_marketing_version="$(plutil -extract CFBundleShortVersionString raw "$current_info_plist")"
  print -r -- "$base_bundle_version" | rg -q '^[0-9]+$' || current_vm_fail "iOS 基线构建号不是纯数字"
  print -r -- "$current_bundle_version" | rg -q '^[0-9]+$' || current_vm_fail "iOS 当前构建号不是纯数字"
  [[ "$base_bundle_version" == "$UPGRADE_EXPECTED_BASE_BUILD_NUMBER" ]] ||
    current_vm_fail "iOS 基线构建号不是 $UPGRADE_EXPECTED_BASE_BUILD_NUMBER"
  [[ "$base_marketing_version" == "$UPGRADE_EXPECTED_BASE_VERSION_NAME" ]] ||
    current_vm_fail "iOS 基线展示版本不是 $UPGRADE_EXPECTED_BASE_VERSION_NAME"
  [[ "$current_bundle_version" == "$UPGRADE_EXPECTED_CURRENT_BUILD_NUMBER" ]] ||
    current_vm_fail "iOS 当前构建号不是 $UPGRADE_EXPECTED_CURRENT_BUILD_NUMBER"
  [[ "$current_marketing_version" == "$UPGRADE_EXPECTED_CURRENT_VERSION_NAME" ]] ||
    current_vm_fail "iOS 当前展示版本不是 $UPGRADE_EXPECTED_CURRENT_VERSION_NAME"
  (( current_bundle_version > base_bundle_version )) || current_vm_fail "iOS 当前构建号没有高于升级基线"
  local expected_data_prefix="$HOME/Library/Developer/CoreSimulator/Devices/$CURRENT_IOS_SIMULATOR_UDID/data/Containers/Data/Application/"
  local expected_app_prefix="$HOME/Library/Developer/CoreSimulator/Devices/$CURRENT_IOS_SIMULATOR_UDID/data/Containers/Bundle/Application/"

  ios_restore_required=1
  xcrun simctl terminate "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_EXPECTED_BUNDLE_ID" >/dev/null 2>&1 || true
  xcrun simctl install "$CURRENT_IOS_SIMULATOR_UDID" "$base_release_app"
  local data_container_before app_container_before
  data_container_before="$(xcrun simctl get_app_container "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_EXPECTED_BUNDLE_ID" data)"
  app_container_before="$(xcrun simctl get_app_container "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_EXPECTED_BUNDLE_ID" app)"
  [[ "$data_container_before" == "$expected_data_prefix"* ]] || current_vm_fail "iOS 升级前数据容器不属于指定 Simulator"
  [[ "$app_container_before" == "$expected_app_prefix"* ]] || current_vm_fail "iOS 升级前应用容器不属于指定 Simulator"
  local installed_base_version
  installed_base_version="$(plutil -extract CFBundleVersion raw "$app_container_before/Info.plist")"
  [[ "$installed_base_version" == "$base_bundle_version" ]] || current_vm_fail "iOS 安装的基线构建号不匹配"
  local persistent_directory="$data_container_before/Library/Application Support/WinLotteryUpgradeAcceptance"
  local persistent_marker="$persistent_directory/persistent-marker"
  local ticket_directory="$data_container_before/tmp/WinLotteryTicketImages"
  local ticket_marker="$ticket_directory/upgrade-marker.jpg"
  ios_upgrade_persistent_directories+=("$persistent_directory")
  mkdir -p "$persistent_directory" "$ticket_directory"
  touch "$persistent_marker" "$ticket_marker"
  xcrun simctl launch --terminate-running-process \
    "$CURRENT_IOS_SIMULATOR_UDID" \
    "$IOS_EXPECTED_BUNDLE_ID" >/dev/null
  run_upgrade_record_phase upgrade-prepare ios

  xcrun simctl install "$CURRENT_IOS_SIMULATOR_UDID" "$current_release_app"
  local data_container_after app_container_after
  data_container_after="$(xcrun simctl get_app_container "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_EXPECTED_BUNDLE_ID" data)"
  app_container_after="$(xcrun simctl get_app_container "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_EXPECTED_BUNDLE_ID" app)"
  [[ "$data_container_after" == "$expected_data_prefix"* ]] || current_vm_fail "iOS 升级后数据容器不属于指定 Simulator"
  [[ "$app_container_after" == "$expected_app_prefix"* ]] || current_vm_fail "iOS 升级后应用容器不属于指定 Simulator"
  persistent_directory="$data_container_after/Library/Application Support/WinLotteryUpgradeAcceptance"
  persistent_marker="$persistent_directory/persistent-marker"
  ticket_directory="$data_container_after/tmp/WinLotteryTicketImages"
  ticket_marker="$ticket_directory/upgrade-marker.jpg"
  if [[ "$data_container_after" != "$data_container_before" ]]; then
    ios_upgrade_persistent_directories+=("$persistent_directory")
  fi
  local installed_current_version
  installed_current_version="$(plutil -extract CFBundleVersion raw "$app_container_after/Info.plist")"
  [[ "$installed_current_version" == "$current_bundle_version" ]] || current_vm_fail "iOS 升级后的构建号不匹配"
  ios_launch_result="$({
    xcrun simctl launch --terminate-running-process \
      "$CURRENT_IOS_SIMULATOR_UDID" \
      "$IOS_EXPECTED_BUNDLE_ID"
  })"
  [[ "$ios_launch_result" == "$IOS_EXPECTED_BUNDLE_ID: "* ]] || current_vm_fail "iOS 升级后 Release 启动结果异常"
  sleep 1
  run_upgrade_record_phase upgrade-verify ios
  run_upgrade_record_phase cleanup ios
  [[ -f "$persistent_marker" ]] || current_vm_fail "iOS 构建号升级后匿名持久标记丢失"
  [[ ! -e "$ticket_marker" ]] || current_vm_fail "iOS 构建号升级并启动后仍存在临时票图标记"
  rm -f -- "$persistent_marker"
  rmdir "$persistent_directory" 2>/dev/null || true
  local release_process_id="${ios_launch_result##*: }"
  print -r -- "$release_process_id" | rg -q '^[0-9]+$' || current_vm_fail "iOS 升级后 Release 进程 PID 不是纯数字"
  local release_process_command
  release_process_command="$(ps -p "$release_process_id" -o command= | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
  [[ "$release_process_command" == "$app_container_after/WinLottery" ]] ||
    current_vm_fail "iOS 升级专项结束后 Release 进程没有保持存活"
  ios_restore_required=0
}

# 使用可调试测试包放置匿名标记，验证 Android 卸载清除数据并恢复 Release 包。
verify_android_install_lifecycle() {
  local debug_apk="$1"
  local release_apk="$2"
  local marker_path="cache/ticket-images/uninstall-marker.jpg"

  android_restore_required=1
  adb -s "$CURRENT_ANDROID_SERIAL_ID" install -r -d "$debug_apk"
  local debug_package_flags
  debug_package_flags="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell dumpsys package "$ANDROID_APPLICATION_ID" | rg 'pkgFlags=' | head -n 1)"
  [[ "$debug_package_flags" == *DEBUGGABLE* ]] || current_vm_fail "Android 生命周期测试包不可调试"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell run-as "$ANDROID_APPLICATION_ID" mkdir -p cache/ticket-images
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell run-as "$ANDROID_APPLICATION_ID" touch "$marker_path"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell run-as "$ANDROID_APPLICATION_ID" test -f "$marker_path" ||
    current_vm_fail "Android 卸载前匿名标记不存在"

  adb -s "$CURRENT_ANDROID_SERIAL_ID" uninstall "$ANDROID_APPLICATION_ID"
  if adb -s "$CURRENT_ANDROID_SERIAL_ID" shell pm path "$ANDROID_APPLICATION_ID" | rg -q '^package:'; then
    current_vm_fail "Android 卸载后应用包仍存在"
  fi
  adb -s "$CURRENT_ANDROID_SERIAL_ID" install "$debug_apk"
  if adb -s "$CURRENT_ANDROID_SERIAL_ID" shell run-as "$ANDROID_APPLICATION_ID" test -e "$marker_path"; then
    current_vm_fail "Android 全新安装后仍存在卸载前匿名标记"
  fi

  adb -s "$CURRENT_ANDROID_SERIAL_ID" install -r -d "$release_apk"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am force-stop "$ANDROID_APPLICATION_ID"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am start -W -n "$ANDROID_APPLICATION_ID/$ANDROID_ACTIVITY"
  local release_package_flags
  release_package_flags="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell dumpsys package "$ANDROID_APPLICATION_ID" | rg 'pkgFlags=' | head -n 1)"
  [[ "$release_package_flags" != *DEBUGGABLE* ]] || current_vm_fail "Android 生命周期专项结束后没有恢复 Release 包"
  android_process_id="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell pidof "$ANDROID_APPLICATION_ID" | tr -d '\r')"
  [[ -n "$android_process_id" ]] || current_vm_fail "Android 生命周期专项结束后 Release 进程未存活"
  android_restore_required=0
}

# 在 Simulator 数据容器放置匿名标记，验证 iOS 卸载删除容器并恢复 Release 应用。
verify_ios_install_lifecycle() {
  local release_app="$1"
  local expected_data_prefix="$HOME/Library/Developer/CoreSimulator/Devices/$CURRENT_IOS_SIMULATOR_UDID/data/Containers/Data/Application/"
  local data_container_before
  data_container_before="$(xcrun simctl get_app_container "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_EXPECTED_BUNDLE_ID" data)"
  [[ "$data_container_before" == "$expected_data_prefix"* ]] || current_vm_fail "iOS 数据容器不属于指定 Simulator"
  local marker_directory="$data_container_before/tmp/WinLotteryTicketImages"
  local marker_path="$marker_directory/uninstall-marker.jpg"
  mkdir -p "$marker_directory"
  touch "$marker_path"
  [[ -f "$marker_path" ]] || current_vm_fail "iOS 卸载前匿名标记不存在"

  ios_restore_required=1
  xcrun simctl uninstall "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_EXPECTED_BUNDLE_ID"
  if xcrun simctl get_app_container \
    "$CURRENT_IOS_SIMULATOR_UDID" \
    "$IOS_EXPECTED_BUNDLE_ID" \
    app >/dev/null 2>&1; then
    current_vm_fail "iOS 卸载后应用容器仍存在"
  fi
  [[ ! -e "$data_container_before" ]] || current_vm_fail "iOS 卸载后旧数据容器仍存在"

  xcrun simctl install "$CURRENT_IOS_SIMULATOR_UDID" "$release_app"
  local data_container_after
  data_container_after="$(xcrun simctl get_app_container "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_EXPECTED_BUNDLE_ID" data)"
  [[ "$data_container_after" == "$expected_data_prefix"* ]] || current_vm_fail "iOS 新数据容器不属于指定 Simulator"
  [[ ! -e "$data_container_after/tmp/WinLotteryTicketImages/uninstall-marker.jpg" ]] ||
    current_vm_fail "iOS 全新安装后仍存在卸载前匿名标记"
  ios_launch_result="$({
    xcrun simctl launch --terminate-running-process "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_EXPECTED_BUNDLE_ID"
  })"
  [[ "$ios_launch_result" == *:* ]] || current_vm_fail "iOS 生命周期专项结束后 Release 启动结果异常"
  ios_restore_required=0
}

verify_current_mobile_vms
[[ "$VERIFY_INSTALL_LIFECYCLE" == "0" || "$VERIFY_INSTALL_LIFECYCLE" == "1" ]] ||
  current_vm_fail "WINLOTTERY_VERIFY_INSTALL_LIFECYCLE 只允许 0 或 1"
[[ "$VERIFY_PROCESS_RECOVERY" == "0" || "$VERIFY_PROCESS_RECOVERY" == "1" ]] ||
  current_vm_fail "WINLOTTERY_VERIFY_PROCESS_RECOVERY 只允许 0 或 1"
[[ "$VERIFY_ABRUPT_TERMINATION" == "0" || "$VERIFY_ABRUPT_TERMINATION" == "1" ]] ||
  current_vm_fail "WINLOTTERY_VERIFY_ABRUPT_TERMINATION 只允许 0 或 1"
[[ "$VERIFY_CRASH_RECOVERY" == "0" || "$VERIFY_CRASH_RECOVERY" == "1" ]] ||
  current_vm_fail "WINLOTTERY_VERIFY_CRASH_RECOVERY 只允许 0 或 1"
[[ "$VERIFY_MEMORY_PRESSURE" == "0" || "$VERIFY_MEMORY_PRESSURE" == "1" ]] ||
  current_vm_fail "WINLOTTERY_VERIFY_MEMORY_PRESSURE 只允许 0 或 1"
[[ "$VERIFY_VERSION_UPGRADE" == "0" || "$VERIFY_VERSION_UPGRADE" == "1" ]] ||
  current_vm_fail "WINLOTTERY_VERIFY_VERSION_UPGRADE 只允许 0 或 1"
if [[ "$VERIFY_MEMORY_PRESSURE" == "1" ]]; then
  xcrun --find lldb >/dev/null || current_vm_fail "缺少 iOS Simulator 调试器"
fi

for required_command in codesign file jarsigner plutil rg shasum strings unzip xcodebuild; do
  command -v "$required_command" >/dev/null || current_vm_fail "缺少命令 $required_command"
done
if [[ "$VERIFY_VERSION_UPGRADE" == "1" ]]; then
  for required_command in appium curl git node tail tar; do
    command -v "$required_command" >/dev/null || current_vm_fail "缺少命令 $required_command"
  done
  [[ -f "$UPGRADE_RECORDS_CHECK_SCRIPT" ]] || current_vm_fail "缺少 V1.1 记录 Appium 检查脚本"
  git -C "$REPOSITORY_ROOT" cat-file -e "${UPGRADE_BASE_REVISION}^{commit}" 2>/dev/null ||
    current_vm_fail "找不到升级基线提交 $UPGRADE_BASE_REVISION"
  git -C "$REPOSITORY_ROOT" merge-base --is-ancestor "$UPGRADE_BASE_REVISION" HEAD ||
    current_vm_fail "升级基线不是当前提交的祖先"
fi

readonly android_sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -n "$android_sdk_root" ]] || current_vm_fail "未配置 Android SDK 路径"

build_tool_directories=("$android_sdk_root"/build-tools/*(/Nn))
(( ${#build_tool_directories} > 0 )) || current_vm_fail "找不到 Android build-tools"
readonly latest_build_tools="${build_tool_directories[-1]}"
readonly aapt_command="$latest_build_tools/aapt"
readonly apksigner_command="$latest_build_tools/apksigner"
readonly zipalign_command="$latest_build_tools/zipalign"
for required_file in "$aapt_command" "$apksigner_command" "$zipalign_command"; do
  [[ -x "$required_file" ]] || current_vm_fail "缺少 Android 构建工具 $required_file"
done

readonly android_user_directory="${ANDROID_USER_HOME:-${HOME}/.android}"
readonly android_test_keystore="$android_user_directory/debug.keystore"
[[ -f "$android_test_keystore" ]] || current_vm_fail "缺少本机 Android 调试证书"

release_check_directory="$(mktemp -d)"
readonly release_check_directory
trap cleanup_release_check EXIT

cd "$REPOSITORY_ROOT"
export ANDROID_SERIAL="$CURRENT_ANDROID_SERIAL_ID"

if [[ "$VERIFY_VERSION_UPGRADE" == "1" ]]; then
  upgrade_base_source="$release_check_directory/upgrade-base-source"
  mkdir -p "$upgrade_base_source"
  git archive "$UPGRADE_BASE_REVISION" | tar -x -C "$upgrade_base_source"
  (
    cd "$upgrade_base_source"
    ANDROID_HOME="$android_sdk_root" ./gradlew \
      spotlessCheck \
      :androidApp:assembleDebug \
      :androidApp:assembleRelease \
      --rerun-tasks \
      --console=plain
  )
  upgrade_base_debug_apk="$upgrade_base_source/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
  upgrade_base_unsigned_apk="$upgrade_base_source/androidApp/build/outputs/apk/release/androidApp-release-unsigned.apk"
  upgrade_base_configured_signed_apk="$upgrade_base_source/androidApp/build/outputs/apk/release/androidApp-release.apk"
  upgrade_base_aligned_apk="$release_check_directory/upgrade-base-release-aligned.apk"
  upgrade_base_signed_apk="$release_check_directory/upgrade-base-release-test-signed.apk"
  require_release_file "$upgrade_base_debug_apk"
  if [[ -s "$upgrade_base_configured_signed_apk" ]]; then
    "$apksigner_command" verify --verbose "$upgrade_base_configured_signed_apk"
    sign_android_acceptance_apk \
      "$upgrade_base_configured_signed_apk" \
      "$upgrade_base_aligned_apk" \
      "$upgrade_base_signed_apk"
  else
    require_release_file "$upgrade_base_unsigned_apk"
    sign_android_acceptance_apk \
      "$upgrade_base_unsigned_apk" \
      "$upgrade_base_aligned_apk" \
      "$upgrade_base_signed_apk"
  fi
  upgrade_base_aligned_debug_apk="$release_check_directory/upgrade-base-debug-aligned.apk"
  upgrade_base_signed_debug_apk="$release_check_directory/upgrade-base-debug-test-signed.apk"
  sign_android_acceptance_apk \
    "$upgrade_base_debug_apk" \
    "$upgrade_base_aligned_debug_apk" \
    "$upgrade_base_signed_debug_apk"

  upgrade_base_ios_derived_data="$release_check_directory/upgrade-base-ios-derived-data"
  xcodebuild \
    -project "$upgrade_base_source/iosApp/iosApp.xcodeproj" \
    -scheme iosApp \
    -configuration Release \
    -sdk iphonesimulator \
    -destination "platform=iOS Simulator,id=$CURRENT_IOS_SIMULATOR_UDID" \
    -derivedDataPath "$upgrade_base_ios_derived_data" \
    build
  upgrade_base_ios_release_app="$upgrade_base_ios_derived_data/Build/Products/Release-iphonesimulator/WinLottery.app"
  require_release_file "$upgrade_base_ios_release_app/Info.plist"
  require_release_file "$upgrade_base_ios_release_app/WinLottery"
fi

release_gradle_tasks=(spotlessCheck :androidApp:assembleRelease :androidApp:bundleRelease)
if [[ "$VERIFY_INSTALL_LIFECYCLE" == "1" || "$VERIFY_PROCESS_RECOVERY" == "1" || "$VERIFY_ABRUPT_TERMINATION" == "1" || "$VERIFY_CRASH_RECOVERY" == "1" || "$VERIFY_MEMORY_PRESSURE" == "1" || "$VERIFY_VERSION_UPGRADE" == "1" ]]; then
  release_gradle_tasks+=(:androidApp:assembleDebug)
fi
if [[ "$VERIFY_VERSION_UPGRADE" == "1" ]]; then
  ./gradlew :androidApp:clean --console=plain
fi
./gradlew "${release_gradle_tasks[@]}" --rerun-tasks --console=plain

readonly unsigned_android_apk="$REPOSITORY_ROOT/androidApp/build/outputs/apk/release/androidApp-release-unsigned.apk"
readonly configured_signed_android_apk="$REPOSITORY_ROOT/androidApp/build/outputs/apk/release/androidApp-release.apk"
readonly android_aab="$REPOSITORY_ROOT/androidApp/build/outputs/bundle/release/androidApp-release.aab"
readonly android_debug_apk="$REPOSITORY_ROOT/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
readonly aligned_android_debug_apk="$release_check_directory/androidApp-debug-aligned.apk"
readonly signed_android_debug_apk="$release_check_directory/androidApp-debug-test-signed.apk"
readonly aligned_android_apk="$release_check_directory/androidApp-release-aligned.apk"
readonly signed_android_apk="$release_check_directory/androidApp-release-test-signed.apk"
require_release_file "$android_aab"
if [[ "$VERIFY_INSTALL_LIFECYCLE" == "1" || "$VERIFY_PROCESS_RECOVERY" == "1" || "$VERIFY_ABRUPT_TERMINATION" == "1" || "$VERIFY_CRASH_RECOVERY" == "1" || "$VERIFY_MEMORY_PRESSURE" == "1" || "$VERIFY_VERSION_UPGRADE" == "1" ]]; then
  require_release_file "$android_debug_apk"
  sign_android_acceptance_apk \
    "$android_debug_apk" \
    "$aligned_android_debug_apk" \
    "$signed_android_debug_apk"
fi

if [[ -s "$configured_signed_android_apk" ]]; then
  "$apksigner_command" verify --verbose "$configured_signed_android_apk"
  cp "$configured_signed_android_apk" "$signed_android_apk"
  "$apksigner_command" verify --verbose "$signed_android_apk"
else
  require_release_file "$unsigned_android_apk"
  sign_android_acceptance_apk "$unsigned_android_apk" "$aligned_android_apk" "$signed_android_apk"
fi

if "$aapt_command" dump badging "$signed_android_apk" | rg -q '^application-debuggable'; then
  current_vm_fail "Android Release APK 清单仍允许调试"
fi
if [[ "$VERIFY_CRASH_RECOVERY" == "1" || "$VERIFY_MEMORY_PRESSURE" == "1" ]]; then
  readonly android_dex_directory="$release_check_directory/android-release-dex"
  mkdir -p "$android_dex_directory"
  unzip -q "$signed_android_apk" 'classes*.dex' -d "$android_dex_directory"
  if rg -a -F "$CONTROLLED_CRASH_MARKER" "$android_dex_directory" >/dev/null; then
    current_vm_fail "Android Release DEX 仍包含受控崩溃标记"
  fi
  if rg -a -F "$ANDROID_DEBUG_CRASH_EXTRA" "$android_dex_directory" >/dev/null; then
    current_vm_fail "Android Release DEX 仍包含崩溃验收参数"
  fi
  if rg -a -F "$MEMORY_PRESSURE_MARKER" "$android_dex_directory" >/dev/null; then
    current_vm_fail "Android Release DEX 仍包含内存压力验收标记"
  fi
fi
unzip -tq "$android_aab"
if unzip -Z1 "$android_aab" | rg -qi '^META-INF/[^/]+\.(RSA|DSA|EC)$'; then
  jarsigner -verify "$android_aab" >/dev/null 2>&1 || current_vm_fail "Android Release AAB 签名校验失败"
  android_aab_signature_state="存在有效签名记录，但仍需核对生产证书与 Play Console"
else
  android_aab_signature_state="未配置生产签名，仅完成 ZIP 结构校验"
fi

adb -s "$CURRENT_ANDROID_SERIAL_ID" install -r -d "$signed_android_apk"
adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am force-stop "$ANDROID_APPLICATION_ID"
adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am start -W \
  -n "$ANDROID_APPLICATION_ID/$ANDROID_ACTIVITY" \
  --es roc.win.lottery.debug.CONFLICT_ISSUE 2026094

android_package_flags="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell dumpsys package "$ANDROID_APPLICATION_ID" | rg 'pkgFlags=' | head -n 1)"
[[ "$android_package_flags" != *DEBUGGABLE* ]] || current_vm_fail "Android 运行时包仍带 DEBUGGABLE 标记"
android_process_id="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell pidof "$ANDROID_APPLICATION_ID" | tr -d '\r')"
[[ -n "$android_process_id" ]] || current_vm_fail "Android Release 应用启动后没有存活进程"

readonly ios_derived_data="$release_check_directory/ios-derived-data"
if [[ "$VERIFY_CRASH_RECOVERY" == "1" || "$VERIFY_MEMORY_PRESSURE" == "1" ]]; then
  xcodebuild \
    -project "$REPOSITORY_ROOT/iosApp/iosApp.xcodeproj" \
    -scheme iosApp \
    -configuration Debug \
    -sdk iphonesimulator \
    -destination "platform=iOS Simulator,id=$CURRENT_IOS_SIMULATOR_UDID" \
    -derivedDataPath "$ios_derived_data" \
    build
  ios_debug_app="$ios_derived_data/Build/Products/Debug-iphonesimulator/WinLottery.app"
  require_release_file "$ios_debug_app/Info.plist"
  require_release_file "$ios_debug_app/WinLottery"
fi
xcodebuild \
  -project "$REPOSITORY_ROOT/iosApp/iosApp.xcodeproj" \
  -scheme iosApp \
  -configuration Release \
  -sdk iphonesimulator \
  -destination "platform=iOS Simulator,id=$CURRENT_IOS_SIMULATOR_UDID" \
  -derivedDataPath "$ios_derived_data" \
  build

readonly ios_release_app="$ios_derived_data/Build/Products/Release-iphonesimulator/WinLottery.app"
readonly ios_info_plist="$ios_release_app/Info.plist"
readonly ios_executable="$ios_release_app/WinLottery"
require_release_file "$ios_info_plist"
require_release_file "$ios_executable"

ios_bundle_id="$(plutil -extract CFBundleIdentifier raw "$ios_info_plist")"
[[ "$ios_bundle_id" == "$IOS_EXPECTED_BUNDLE_ID" ]] || current_vm_fail "iOS Bundle ID 不匹配"
file "$ios_executable" | rg -q 'arm64' || current_vm_fail "iOS Release 二进制不是 arm64 Simulator 架构"
codesign --verify --deep --strict "$ios_release_app"
if [[ "$VERIFY_CRASH_RECOVERY" == "1" || "$VERIFY_MEMORY_PRESSURE" == "1" ]]; then
  if strings "$ios_executable" | rg -F "$CONTROLLED_CRASH_MARKER" >/dev/null; then
    current_vm_fail "iOS Release 可执行文件仍包含受控崩溃标记"
  fi
  if strings "$ios_executable" | rg -F "$IOS_DEBUG_CRASH_ENVIRONMENT" >/dev/null; then
    current_vm_fail "iOS Release 可执行文件仍包含崩溃验收参数"
  fi
  if strings "$ios_executable" | rg -F "$MEMORY_PRESSURE_MARKER" >/dev/null; then
    current_vm_fail "iOS Release 可执行文件仍包含内存压力验收标记"
  fi
fi

readonly ios_entitlements="$release_check_directory/ios-entitlements.plist"
codesign -d --entitlements :- "$ios_release_app" >"$ios_entitlements" 2>/dev/null || true
if [[ -s "$ios_entitlements" ]] &&
  [[ "$(plutil -extract get-task-allow raw "$ios_entitlements" 2>/dev/null || true)" == "true" ]]; then
  current_vm_fail "iOS Release 应用仍允许调试附加"
fi

xcrun simctl install "$CURRENT_IOS_SIMULATOR_UDID" "$ios_release_app"
ios_launch_result="$({
  SIMCTL_CHILD_WINLOTTERY_DEBUG_CONFLICT_ISSUE=2026094 \
    xcrun simctl launch --terminate-running-process \
      "$CURRENT_IOS_SIMULATOR_UDID" \
      "$ios_bundle_id"
})"
[[ "$ios_launch_result" == *:* ]] || current_vm_fail "iOS Release 应用启动结果异常"

if [[ "$VERIFY_PROCESS_RECOVERY" == "1" ]]; then
  verify_android_process_recovery "$signed_android_debug_apk" "$signed_android_apk"
  verify_ios_process_recovery
fi
if [[ "$VERIFY_ABRUPT_TERMINATION" == "1" ]]; then
  verify_android_abrupt_termination "$signed_android_debug_apk" "$signed_android_apk"
  verify_ios_abrupt_termination "$ios_launch_result"
fi
if [[ "$VERIFY_CRASH_RECOVERY" == "1" ]]; then
  verify_android_crash_recovery "$signed_android_debug_apk" "$signed_android_apk"
  verify_ios_crash_recovery "$ios_debug_app" "$ios_release_app"
fi
if [[ "$VERIFY_MEMORY_PRESSURE" == "1" ]]; then
  verify_android_memory_pressure "$signed_android_debug_apk" "$signed_android_apk"
  verify_ios_memory_pressure "$ios_debug_app" "$ios_release_app"
fi
if [[ "$VERIFY_VERSION_UPGRADE" == "1" ]]; then
  start_upgrade_appium
  verify_android_version_upgrade \
    "$upgrade_base_signed_debug_apk" \
    "$upgrade_base_signed_apk" \
    "$signed_android_debug_apk" \
    "$signed_android_apk"
  verify_ios_version_upgrade "$upgrade_base_ios_release_app" "$ios_release_app"
fi
if [[ "$VERIFY_INSTALL_LIFECYCLE" == "1" ]]; then
  verify_android_install_lifecycle "$signed_android_debug_apk" "$signed_android_apk"
  verify_ios_install_lifecycle "$ios_release_app"
fi

android_apk_size="$(stat -f '%z' "$signed_android_apk")"
android_aab_size="$(stat -f '%z' "$android_aab")"
ios_app_size_kib="$(du -sk "$ios_release_app" | awk '{print $1}')"

print "Android Release APK：${android_apk_size} 字节，进程 $android_process_id，非 Debug 校验通过"
print "Android Release AAB：${android_aab_size} 字节，${android_aab_signature_state}"
print "iOS Release Simulator App：${ios_app_size_kib} KiB，${ios_launch_result}，非 Debug 校验通过"
print "iOS 产物仅为 Simulator 本地签名 .app，不是 IPA 或发布签名"
if [[ "$VERIFY_INSTALL_LIFECYCLE" == "1" ]]; then
  print "双虚拟机卸载清除匿名标记、全新安装和 Release 恢复检查通过"
fi
if [[ "$VERIFY_PROCESS_RECOVERY" == "1" ]]; then
  print "双虚拟机受控进程终止、遗留标记清扫和 Release 恢复检查通过"
fi
if [[ "$VERIFY_ABRUPT_TERMINATION" == "1" ]]; then
  print "双虚拟机 SIGKILL 异常终止、遗留标记清扫和 Release 恢复检查通过"
fi
if [[ "$VERIFY_CRASH_RECOVERY" == "1" ]]; then
  print "双虚拟机 Debug 受控崩溃、遗留标记清扫和 Release 反向检查通过"
fi
if [[ "$VERIFY_MEMORY_PRESSURE" == "1" ]]; then
  print "Android 内存收紧回调、iOS Debug 模拟内存警告和 Release 隔离检查通过"
fi
if [[ "$VERIFY_VERSION_UPGRADE" == "1" ]]; then
  print "双虚拟机 $UPGRADE_EXPECTED_BASE_VERSION_NAME ($UPGRADE_EXPECTED_BASE_BUILD_NUMBER) 到 $UPGRADE_EXPECTED_CURRENT_VERSION_NAME ($UPGRADE_EXPECTED_CURRENT_BUILD_NUMBER) 的 Release 覆盖升级、真实 Room 记录与匿名数据保留、临时票图清扫检查通过"
  print "升级验收截图与日志：$upgrade_evidence_directory"
fi
print "Android APK SHA-256：$(shasum -a 256 "$signed_android_apk" | awk '{print $1}')"
print "Android AAB SHA-256：$(shasum -a 256 "$android_aab" | awk '{print $1}')"
print "临时验收签名 APK 与 Xcode DerivedData 将在脚本退出时清理"

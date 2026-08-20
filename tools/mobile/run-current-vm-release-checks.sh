#!/bin/zsh

set -euo pipefail

readonly REPOSITORY_ROOT="${0:A:h:h:h}"
readonly ANDROID_APPLICATION_ID="roc.win.lottery"
readonly ANDROID_ACTIVITY="roc.win.lottery.MainActivity"
readonly ANDROID_DEBUG_CRASH_EXTRA="roc.win.lottery.debug.CRASH_ON_CREATE"
readonly IOS_EXPECTED_BUNDLE_ID="roc.win.lottery.WinLottery"
readonly IOS_DEBUG_CRASH_ENVIRONMENT="WINLOTTERY_DEBUG_CRASH_ON_LAUNCH"
readonly CONTROLLED_CRASH_MARKER="WinLotteryControlledCrashAcceptance"
readonly VERIFY_INSTALL_LIFECYCLE="${WINLOTTERY_VERIFY_INSTALL_LIFECYCLE:-0}"
readonly VERIFY_PROCESS_RECOVERY="${WINLOTTERY_VERIFY_PROCESS_RECOVERY:-0}"
readonly VERIFY_ABRUPT_TERMINATION="${WINLOTTERY_VERIFY_ABRUPT_TERMINATION:-0}"
readonly VERIFY_CRASH_RECOVERY="${WINLOTTERY_VERIFY_CRASH_RECOVERY:-0}"

source "${0:A:h}/current-vm-guard.sh"

# 失败时尽力恢复 Release 应用，并清理本轮签名 APK 与 Xcode DerivedData。
cleanup_release_check() {
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
  if [[ -n "${release_check_directory:-}" && -d "$release_check_directory" ]]; then
    rm -rf -- "$release_check_directory"
  fi
}

# 要求文件存在且非空，避免后续命令误用不存在的构建产物。
require_release_file() {
  [[ -s "$1" ]] || current_vm_fail "缺少构建产物 $1"
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

for required_command in codesign file jarsigner plutil rg shasum strings unzip xcodebuild; do
  command -v "$required_command" >/dev/null || current_vm_fail "缺少命令 $required_command"
done

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

release_gradle_tasks=(spotlessCheck :androidApp:assembleRelease :androidApp:bundleRelease)
if [[ "$VERIFY_INSTALL_LIFECYCLE" == "1" || "$VERIFY_PROCESS_RECOVERY" == "1" || "$VERIFY_ABRUPT_TERMINATION" == "1" || "$VERIFY_CRASH_RECOVERY" == "1" ]]; then
  release_gradle_tasks+=(:androidApp:assembleDebug)
fi
./gradlew "${release_gradle_tasks[@]}" --rerun-tasks --console=plain

readonly unsigned_android_apk="$REPOSITORY_ROOT/androidApp/build/outputs/apk/release/androidApp-release-unsigned.apk"
readonly android_aab="$REPOSITORY_ROOT/androidApp/build/outputs/bundle/release/androidApp-release.aab"
readonly android_debug_apk="$REPOSITORY_ROOT/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
readonly aligned_android_apk="$release_check_directory/androidApp-release-aligned.apk"
readonly signed_android_apk="$release_check_directory/androidApp-release-test-signed.apk"
require_release_file "$unsigned_android_apk"
require_release_file "$android_aab"
if [[ "$VERIFY_INSTALL_LIFECYCLE" == "1" || "$VERIFY_PROCESS_RECOVERY" == "1" || "$VERIFY_ABRUPT_TERMINATION" == "1" || "$VERIFY_CRASH_RECOVERY" == "1" ]]; then
  require_release_file "$android_debug_apk"
fi

"$zipalign_command" -f 4 "$unsigned_android_apk" "$aligned_android_apk"
"$apksigner_command" sign \
  --ks "$android_test_keystore" \
  --ks-key-alias androiddebugkey \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out "$signed_android_apk" \
  "$aligned_android_apk"
"$apksigner_command" verify --verbose "$signed_android_apk"

if "$aapt_command" dump badging "$signed_android_apk" | rg -q '^application-debuggable'; then
  current_vm_fail "Android Release APK 清单仍允许调试"
fi
if [[ "$VERIFY_CRASH_RECOVERY" == "1" ]]; then
  readonly android_dex_directory="$release_check_directory/android-release-dex"
  mkdir -p "$android_dex_directory"
  unzip -q "$signed_android_apk" 'classes*.dex' -d "$android_dex_directory"
  if rg -a -F "$CONTROLLED_CRASH_MARKER" "$android_dex_directory" >/dev/null; then
    current_vm_fail "Android Release DEX 仍包含受控崩溃标记"
  fi
  if rg -a -F "$ANDROID_DEBUG_CRASH_EXTRA" "$android_dex_directory" >/dev/null; then
    current_vm_fail "Android Release DEX 仍包含崩溃验收参数"
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
if [[ "$VERIFY_CRASH_RECOVERY" == "1" ]]; then
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
if [[ "$VERIFY_CRASH_RECOVERY" == "1" ]]; then
  if strings "$ios_executable" | rg -F "$CONTROLLED_CRASH_MARKER" >/dev/null; then
    current_vm_fail "iOS Release 可执行文件仍包含受控崩溃标记"
  fi
  if strings "$ios_executable" | rg -F "$IOS_DEBUG_CRASH_ENVIRONMENT" >/dev/null; then
    current_vm_fail "iOS Release 可执行文件仍包含崩溃验收参数"
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
  verify_android_process_recovery "$android_debug_apk" "$signed_android_apk"
  verify_ios_process_recovery
fi
if [[ "$VERIFY_ABRUPT_TERMINATION" == "1" ]]; then
  verify_android_abrupt_termination "$android_debug_apk" "$signed_android_apk"
  verify_ios_abrupt_termination "$ios_launch_result"
fi
if [[ "$VERIFY_CRASH_RECOVERY" == "1" ]]; then
  verify_android_crash_recovery "$android_debug_apk" "$signed_android_apk"
  verify_ios_crash_recovery "$ios_debug_app" "$ios_release_app"
fi
if [[ "$VERIFY_INSTALL_LIFECYCLE" == "1" ]]; then
  verify_android_install_lifecycle "$android_debug_apk" "$signed_android_apk"
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
print "Android APK SHA-256：$(shasum -a 256 "$signed_android_apk" | awk '{print $1}')"
print "Android AAB SHA-256：$(shasum -a 256 "$android_aab" | awk '{print $1}')"
print "临时验收签名 APK 与 Xcode DerivedData 将在脚本退出时清理"

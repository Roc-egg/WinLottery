#!/bin/zsh

readonly CURRENT_ANDROID_SERIAL_ID="emulator-5554"
readonly CURRENT_ANDROID_AVD_NAME="Medium_Phone_API_36.1"
readonly CURRENT_IOS_SIMULATOR_UDID="E6D04C31-9EB8-4696-990F-DA54D0112E13"
readonly CURRENT_IOS_SIMULATOR_NAME="iPhone 17 Pro"
readonly CURRENT_IOS_RUNTIME_SUFFIX="iOS-26-5"

# 输出明确原因并阻止命令回退到其他已连接设备。
current_vm_fail() {
  print -u2 "虚拟机验收已阻止：$1"
  exit 1
}

# 校验当前 Android 与 iOS 目标均为文档锁定且已启动的虚拟机。
verify_current_mobile_vms() {
  local required_command
  for required_command in adb jq xcrun; do
    command -v "$required_command" >/dev/null || current_vm_fail "缺少命令 $required_command"
  done

  local android_state
  if ! android_state="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" get-state 2>/dev/null)"; then
    current_vm_fail "Android 目标 $CURRENT_ANDROID_SERIAL_ID 未连接"
  fi
  [[ "$android_state" == "device" ]] || current_vm_fail "Android 目标状态不是 device"

  local android_is_emulator
  android_is_emulator="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell getprop ro.kernel.qemu | tr -d '\r')"
  [[ "$android_is_emulator" == "1" ]] || current_vm_fail "Android 目标不是虚拟机"

  local android_avd_name
  android_avd_name="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell getprop ro.boot.qemu.avd_name | tr -d '\r')"
  [[ "$android_avd_name" == "$CURRENT_ANDROID_AVD_NAME" ]] ||
    current_vm_fail "Android AVD 不是 $CURRENT_ANDROID_AVD_NAME"

  local ios_target
  if ! ios_target="$({
    xcrun simctl list devices --json |
      jq -r --arg udid "$CURRENT_IOS_SIMULATOR_UDID" '
        [
          .devices | to_entries[] as $runtime |
          $runtime.value[] |
          select(.udid == $udid) |
          [.state, .name, $runtime.key] | @tsv
        ][0] // empty
      '
  })"; then
    current_vm_fail "无法读取 iOS Simulator 状态"
  fi
  [[ -n "$ios_target" ]] || current_vm_fail "找不到指定 iOS Simulator"

  local ios_state ios_name ios_runtime
  IFS=$'\t' read -r ios_state ios_name ios_runtime <<<"$ios_target"
  [[ "$ios_state" == "Booted" ]] || current_vm_fail "iOS Simulator 尚未启动"
  [[ "$ios_name" == "$CURRENT_IOS_SIMULATOR_NAME" ]] || current_vm_fail "iOS Simulator 名称不匹配"
  [[ "$ios_runtime" == *"$CURRENT_IOS_RUNTIME_SUFFIX" ]] || current_vm_fail "iOS Simulator 运行时不匹配"

  print "已锁定 Android：$CURRENT_ANDROID_AVD_NAME ($CURRENT_ANDROID_SERIAL_ID)"
  print "已锁定 iOS：$CURRENT_IOS_SIMULATOR_NAME ($CURRENT_IOS_SIMULATOR_UDID)"
}

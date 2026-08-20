#!/bin/zsh

set -euo pipefail

readonly ANDROID_SERIAL_ID="emulator-5554"
readonly ANDROID_AVD_NAME="Medium_Phone_API_36.1"
readonly IOS_SIMULATOR_UDID="E6D04C31-9EB8-4696-990F-DA54D0112E13"
readonly IOS_SIMULATOR_NAME="iPhone 17 Pro"
readonly IOS_RUNTIME_SUFFIX="iOS-26-5"
readonly REPOSITORY_ROOT="${0:A:h:h:h}"

# 输出明确原因并阻止测试回退到其他已连接设备。
fail() {
  print -u2 "虚拟机验收已阻止：$1"
  exit 1
}

for required_command in adb jq xcrun; do
  command -v "$required_command" >/dev/null || fail "缺少命令 $required_command"
done

if ! android_state="$(adb -s "$ANDROID_SERIAL_ID" get-state 2>/dev/null)"; then
  fail "Android 目标 $ANDROID_SERIAL_ID 未连接"
fi
[[ "$android_state" == "device" ]] || fail "Android 目标状态不是 device"

android_is_emulator="$(adb -s "$ANDROID_SERIAL_ID" shell getprop ro.kernel.qemu | tr -d '\r')"
[[ "$android_is_emulator" == "1" ]] || fail "Android 目标不是虚拟机"

android_avd_name="$(adb -s "$ANDROID_SERIAL_ID" shell getprop ro.boot.qemu.avd_name | tr -d '\r')"
[[ "$android_avd_name" == "$ANDROID_AVD_NAME" ]] ||
  fail "Android AVD 不是 $ANDROID_AVD_NAME"

ios_target="$({
  xcrun simctl list devices --json |
    jq -r --arg udid "$IOS_SIMULATOR_UDID" '
      [
        .devices | to_entries[] as $runtime |
        $runtime.value[] |
        select(.udid == $udid) |
        [.state, .name, $runtime.key] | @tsv
      ][0] // empty
    '
})"
[[ -n "$ios_target" ]] || fail "找不到指定 iOS Simulator"

IFS=$'\t' read -r ios_state ios_name ios_runtime <<<"$ios_target"
[[ "$ios_state" == "Booted" ]] || fail "iOS Simulator 尚未启动"
[[ "$ios_name" == "$IOS_SIMULATOR_NAME" ]] || fail "iOS Simulator 名称不匹配"
[[ "$ios_runtime" == *"$IOS_RUNTIME_SUFFIX" ]] || fail "iOS Simulator 运行时不匹配"

cd "$REPOSITORY_ROOT"
export ANDROID_SERIAL="$ANDROID_SERIAL_ID"

print "已锁定 Android：$ANDROID_AVD_NAME ($ANDROID_SERIAL_ID)"
print "已锁定 iOS：$IOS_SIMULATOR_NAME ($IOS_SIMULATOR_UDID)"

./gradlew \
  spotlessCheck \
  iosSimulatorArm64Test \
  :shared:data:connectedAndroidDeviceTest \
  --rerun-tasks \
  --console=plain

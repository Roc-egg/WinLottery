#!/bin/zsh

set -euo pipefail

readonly REPOSITORY_ROOT="${0:A:h:h:h}"
readonly NETWORK_JITTER_SERVER="$REPOSITORY_ROOT/tools/mobile/network-jitter-server.mjs"
readonly ANDROID_TEST_CLASS="roc.win.lottery.data.AndroidNetworkJitterTest"
readonly ANDROID_TEST_APK="$REPOSITORY_ROOT/shared/data/build/outputs/apk/androidTest/data-androidTest.apk"
readonly ANDROID_TEST_PACKAGE="roc.win.lottery.data.test"
readonly ANDROID_TEST_RUNNER="$ANDROID_TEST_PACKAGE/androidx.test.runner.AndroidJUnitRunner"
readonly IOS_TEST_FILTER="roc.win.lottery.data.IOSNetworkJitterTest.*"
readonly IOS_TEST_BINARY="$REPOSITORY_ROOT/shared/data/build/bin/iosSimulatorArm64/debugTest/test.kexe"

source "${0:A:h}/current-vm-guard.sh"
verify_current_mobile_vms

# 检查专项依赖，避免在缺少本地服务或构建工具时执行部分验收。
for required_command in curl node; do
  command -v "$required_command" >/dev/null || current_vm_fail "缺少命令 $required_command"
done
[[ -f "$NETWORK_JITTER_SERVER" ]] || current_vm_fail "缺少受控网络抖动服务"

readonly WORK_DIRECTORY="$(mktemp -d "${TMPDIR:-/tmp}/winlottery-network-jitter.XXXXXX")"
readonly PORT_FILE="$WORK_DIRECTORY/port"
readonly SERVER_LOG="$WORK_DIRECTORY/server.log"
readonly ANDROID_TOKEN="android-$(date +%s)-$$"
readonly IOS_TOKEN="ios-$(date +%s)-$$"
NETWORK_JITTER_SERVER_PID=""
ANDROID_TEST_INSTALLED=0

# 退出时只清理本专项创建的服务进程和临时目录。
cleanup() {
  if (( ANDROID_TEST_INSTALLED == 1 )); then
    adb -s "$CURRENT_ANDROID_SERIAL_ID" uninstall "$ANDROID_TEST_PACKAGE" >/dev/null 2>&1 || true
  fi
  if [[ -n "$NETWORK_JITTER_SERVER_PID" ]] && kill -0 "$NETWORK_JITTER_SERVER_PID" 2>/dev/null; then
    kill "$NETWORK_JITTER_SERVER_PID" 2>/dev/null || true
    wait "$NETWORK_JITTER_SERVER_PID" 2>/dev/null || true
  fi
  rm -rf -- "$WORK_DIRECTORY"
}
trap cleanup EXIT

node "$NETWORK_JITTER_SERVER" "$PORT_FILE" >"$SERVER_LOG" 2>&1 &
NETWORK_JITTER_SERVER_PID=$!

# 等待服务写出动态端口，超时或提前退出时输出明确错误。
for _ in {1..100}; do
  [[ -s "$PORT_FILE" ]] && break
  if ! kill -0 "$NETWORK_JITTER_SERVER_PID" 2>/dev/null; then
    current_vm_fail "受控网络抖动服务提前退出：$(<"$SERVER_LOG")"
  fi
  sleep 0.05
done
[[ -s "$PORT_FILE" ]] || current_vm_fail "等待受控网络抖动服务超时"

readonly SERVER_PORT="$(tr -d '\r\n' <"$PORT_FILE")"
[[ "$SERVER_PORT" == <-> ]] || current_vm_fail "本地服务端口不是纯数字"
(( SERVER_PORT >= 1 && SERVER_PORT <= 65535 )) || current_vm_fail "本地服务端口超出范围"
curl --fail --silent --show-error "http://127.0.0.1:$SERVER_PORT/health" >/dev/null

cd "$REPOSITORY_ROOT"
export ANDROID_SERIAL="$CURRENT_ANDROID_SERIAL_ID"

print "开始 Android OkHttp 网络抖动专项"
./gradlew \
  spotlessCheck \
  :shared:data:assembleAndroidDeviceTest \
  --rerun-tasks \
  --console=plain
[[ -f "$ANDROID_TEST_APK" ]] || current_vm_fail "找不到 Android 设备测试 APK"

readonly EXISTING_ANDROID_TEST_PATH="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell pm path "$ANDROID_TEST_PACKAGE" 2>/dev/null | tr -d '\r')"
[[ -z "$EXISTING_ANDROID_TEST_PATH" ]] || current_vm_fail "Android 虚拟机已有同名测试包，拒绝覆盖"
adb -s "$CURRENT_ANDROID_SERIAL_ID" install -r -t "$ANDROID_TEST_APK" >/dev/null
ANDROID_TEST_INSTALLED=1

readonly ANDROID_TEST_OUTPUT="$WORK_DIRECTORY/android-test-output.txt"
adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am instrument -w -r \
  -e class "$ANDROID_TEST_CLASS" \
  -e winlotteryNetworkJitter 1 \
  -e winlotteryNetworkJitterBaseUrl "http://10.0.2.2:$SERVER_PORT" \
  -e winlotteryNetworkJitterToken "$ANDROID_TOKEN" \
  "$ANDROID_TEST_RUNNER" >"$ANDROID_TEST_OUTPUT"
print -r -- "$(<"$ANDROID_TEST_OUTPUT")"
[[ "$(<"$ANDROID_TEST_OUTPUT")" == *"OK (1 test)"* ]] || current_vm_fail "Android 网络抖动单项测试未通过"
adb -s "$CURRENT_ANDROID_SERIAL_ID" uninstall "$ANDROID_TEST_PACKAGE" >/dev/null
ANDROID_TEST_INSTALLED=0

print "开始 iOS Darwin 网络抖动专项"
./gradlew \
  :shared:data:linkDebugTestIosSimulatorArm64 \
  --rerun-tasks \
  --console=plain
[[ -x "$IOS_TEST_BINARY" ]] || current_vm_fail "找不到 iOS Simulator 测试可执行文件"

SIMCTL_CHILD_WINLOTTERY_IOS_NETWORK_JITTER=1 \
  SIMCTL_CHILD_WINLOTTERY_IOS_NETWORK_JITTER_BASE_URL="http://127.0.0.1:$SERVER_PORT" \
  SIMCTL_CHILD_WINLOTTERY_IOS_NETWORK_JITTER_TOKEN="$IOS_TOKEN" \
  SIMCTL_CHILD_WINLOTTERY_IOS_ACCEPTANCE_PROXY_PORT= \
  xcrun simctl spawn "$CURRENT_IOS_SIMULATOR_UDID" \
  "$IOS_TEST_BINARY" \
  --ktest_filter="$IOS_TEST_FILTER"

print "当前双虚拟机网络抖动专项通过"

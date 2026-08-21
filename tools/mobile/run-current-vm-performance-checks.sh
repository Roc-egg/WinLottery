#!/bin/zsh

set -euo pipefail

readonly REPOSITORY_ROOT="${0:A:h:h:h}"
readonly ANDROID_TEST_PACKAGE="roc.win.lottery.recognition.test"
readonly ANDROID_TEST_RUNNER="androidx.test.runner.AndroidJUnitRunner"
readonly ANDROID_TEST_CLASS="roc.win.lottery.recognition.MobileAnalysisPerformanceDeviceTest"
readonly ANDROID_LOG_TAG="WinLotteryPerformance"
readonly IOS_BUNDLE_ID="roc.win.lottery.WinLottery"
readonly SAMPLE_COUNT="20"
readonly BASELINE_MARKER="WINLOTTERY_MOBILE_ANALYSIS_BASELINE"
readonly IOS_START_MARKER="WINLOTTERY_MOBILE_ANALYSIS_STARTED"
readonly IOS_FAILURE_MARKER="WINLOTTERY_MOBILE_ANALYSIS_FAILURE"
readonly IOS_SAMPLE_COUNT_ENVIRONMENT="WINLOTTERY_MOBILE_ANALYSIS_SAMPLE_COUNT"
readonly IOS_SAMPLE_TIMEOUT_SECONDS="600"

source "${0:A:h}/current-vm-guard.sh"

# 失败或成功退出时恢复 iOS Release 应用，并删除临时测试包和构建目录。
cleanup_performance_check() {
  if [[ -n "${ios_console_process_id:-}" ]]; then
    kill "$ios_console_process_id" >/dev/null 2>&1 || true
    wait "$ios_console_process_id" >/dev/null 2>&1 || true
  fi
  adb -s "$CURRENT_ANDROID_SERIAL_ID" uninstall "$ANDROID_TEST_PACKAGE" >/dev/null 2>&1 || true
  if [[ "${ios_restore_required:-0}" == "1" && -d "${ios_release_app:-}" ]]; then
    xcrun simctl terminate "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_BUNDLE_ID" >/dev/null 2>&1 || true
    xcrun simctl install "$CURRENT_IOS_SIMULATOR_UDID" "$ios_release_app" >/dev/null 2>&1 || true
    xcrun simctl launch "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_BUNDLE_ID" >/dev/null 2>&1 || true
  fi
  if [[ -n "${performance_check_directory:-}" && -d "$performance_check_directory" ]]; then
    rm -rf -- "$performance_check_directory"
  fi
}

# 要求文件存在且非空，避免安装不完整的 Xcode 构建产物。
require_performance_file() {
  [[ -s "$1" ]] || current_vm_fail "缺少构建产物 $1"
}

verify_current_mobile_vms
for required_command in adb rg xcodebuild; do
  command -v "$required_command" >/dev/null || current_vm_fail "缺少命令 $required_command"
done

performance_check_directory="$(mktemp -d)"
readonly performance_check_directory
trap cleanup_performance_check EXIT
cd "$REPOSITORY_ROOT"
export ANDROID_SERIAL="$CURRENT_ANDROID_SERIAL_ID"

./gradlew \
  spotlessCheck \
  :shared:recognition:packageAndroidDeviceTest \
  --rerun-tasks \
  --console=plain

readonly android_test_apk="$REPOSITORY_ROOT/shared/recognition/build/outputs/apk/androidTest/recognition-androidTest.apk"
[[ -s "$android_test_apk" ]] || current_vm_fail "缺少 Android 性能测试 APK"
adb -s "$CURRENT_ANDROID_SERIAL_ID" install -r "$android_test_apk"
adb -s "$CURRENT_ANDROID_SERIAL_ID" logcat -c
android_instrumentation_output="$({
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am instrument -w -r \
    -e class "$ANDROID_TEST_CLASS" \
    -e winlottery.performance.samples "$SAMPLE_COUNT" \
    "$ANDROID_TEST_PACKAGE/$ANDROID_TEST_RUNNER"
})"
print -r -- "$android_instrumentation_output"
print -r -- "$android_instrumentation_output" | rg -Fq "OK (1 test)" ||
  current_vm_fail "Android 12MP 性能专项没有成功完成单项测试"
android_report="$({
  adb -s "$CURRENT_ANDROID_SERIAL_ID" logcat -d -s "$ANDROID_LOG_TAG:I" '*:S' |
    rg -o "$BASELINE_MARKER.*" |
    tail -n 1
})"
[[ "$android_report" == *"samples=$SAMPLE_COUNT"* ]] || current_vm_fail "Android 性能报告样本数不匹配"
adb -s "$CURRENT_ANDROID_SERIAL_ID" uninstall "$ANDROID_TEST_PACKAGE" >/dev/null

readonly ios_derived_data="$performance_check_directory/ios-derived-data"
xcodebuild \
  -project "$REPOSITORY_ROOT/iosApp/iosApp.xcodeproj" \
  -scheme iosApp \
  -configuration Debug \
  -sdk iphonesimulator \
  -destination "platform=iOS Simulator,id=$CURRENT_IOS_SIMULATOR_UDID" \
  -derivedDataPath "$ios_derived_data" \
  build
xcodebuild \
  -project "$REPOSITORY_ROOT/iosApp/iosApp.xcodeproj" \
  -scheme iosApp \
  -configuration Release \
  -sdk iphonesimulator \
  -destination "platform=iOS Simulator,id=$CURRENT_IOS_SIMULATOR_UDID" \
  -derivedDataPath "$ios_derived_data" \
  build

readonly ios_debug_app="$ios_derived_data/Build/Products/Debug-iphonesimulator/WinLottery.app"
readonly ios_release_app="$ios_derived_data/Build/Products/Release-iphonesimulator/WinLottery.app"
require_performance_file "$ios_debug_app/WinLottery"
require_performance_file "$ios_release_app/WinLottery"

ios_restore_required=1
xcrun simctl terminate "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_BUNDLE_ID" >/dev/null 2>&1 || true
xcrun simctl install "$CURRENT_IOS_SIMULATOR_UDID" "$ios_debug_app"
readonly ios_console_output="$performance_check_directory/ios-performance-console.log"
touch "$ios_console_output"
env "SIMCTL_CHILD_${IOS_SAMPLE_COUNT_ENVIRONMENT}=$SAMPLE_COUNT" \
  xcrun simctl launch --terminate-running-process --console \
    "$CURRENT_IOS_SIMULATOR_UDID" \
    "$IOS_BUNDLE_ID" >"$ios_console_output" 2>&1 &
ios_console_process_id=$!

ios_report=""
for ((attempt = 1; attempt <= IOS_SAMPLE_TIMEOUT_SECONDS; attempt++)); do
  if rg -Fq "$IOS_FAILURE_MARKER" "$ios_console_output"; then
    print -r -- "$(<"$ios_console_output")"
    current_vm_fail "iOS Debug 应用内 12MP 性能采样失败"
  fi
  if rg -Fq "$BASELINE_MARKER" "$ios_console_output"; then
    ios_report="$(rg -o "$BASELINE_MARKER.*" "$ios_console_output" | tail -n 1)"
    break
  fi
  kill -0 "$ios_console_process_id" >/dev/null 2>&1 || {
    wait "$ios_console_process_id" >/dev/null 2>&1 || true
    ios_console_process_id=""
    print -r -- "$(<"$ios_console_output")"
    current_vm_fail "iOS Debug 应用在性能采样完成前退出"
  }
  sleep 1
done
[[ -n "$ios_report" ]] || current_vm_fail "iOS Debug 应用内 12MP 性能采样超时"
rg -Fq "$IOS_START_MARKER" "$ios_console_output" || current_vm_fail "iOS Debug 性能入口未启动"
[[ "$ios_report" == *"samples=$SAMPLE_COUNT"* ]] || current_vm_fail "iOS 性能报告样本数不匹配"
kill "$ios_console_process_id" >/dev/null 2>&1 || true
wait "$ios_console_process_id" >/dev/null 2>&1 || true
ios_console_process_id=""

xcrun simctl terminate "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_BUNDLE_ID" >/dev/null 2>&1 || true
xcrun simctl install "$CURRENT_IOS_SIMULATOR_UDID" "$ios_release_app"
readonly ios_release_console_output="$performance_check_directory/ios-release-console.log"
touch "$ios_release_console_output"
env "SIMCTL_CHILD_${IOS_SAMPLE_COUNT_ENVIRONMENT}=1" \
  xcrun simctl launch --terminate-running-process --console \
    "$CURRENT_IOS_SIMULATOR_UDID" \
    "$IOS_BUNDLE_ID" >"$ios_release_console_output" 2>&1 &
ios_console_process_id=$!
sleep 3
rg -Fq "$IOS_START_MARKER" "$ios_release_console_output" &&
  current_vm_fail "iOS Release 应用错误启用了性能入口"
kill "$ios_console_process_id" >/dev/null 2>&1 || true
wait "$ios_console_process_id" >/dev/null 2>&1 || true
ios_console_process_id=""
xcrun simctl launch --terminate-running-process "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_BUNDLE_ID"
ios_restore_required=0

print "Android 12MP 测试进程开发基线：$android_report"
print "iOS 12MP Simulator Debug 应用进程开发基线：$ios_report"
print "该结果不含图片或 OCR 文本，不设置发布阈值，也不代表 Release 物理真机 P95"

exit=0

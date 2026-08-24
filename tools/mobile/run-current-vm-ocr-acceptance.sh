#!/bin/zsh

set -euo pipefail

readonly REPOSITORY_ROOT="${0:A:h:h:h}"
readonly SAMPLE_DIRECTORY="$REPOSITORY_ROOT/image/test"
readonly ANDROID_TEST_PACKAGE="roc.win.lottery.recognition.test"
readonly ANDROID_TEST_RUNNER="androidx.test.runner.AndroidJUnitRunner"
readonly ANDROID_TEST_CLASS="roc.win.lottery.recognition.OcrAcceptanceDeviceTest"
readonly ANDROID_LOG_TAG="WinLotteryOcrAcceptance"
readonly SAMPLE_MARKER="WINLOTTERY_OCR_ACCEPTANCE_SAMPLE"
readonly IOS_BUNDLE_ID="roc.win.lottery.WinLottery"
readonly IOS_SAMPLE_DIRECTORY_NAME="WinLotteryOcrAcceptance"
readonly IOS_SAMPLE_COUNT_ENVIRONMENT="WINLOTTERY_OCR_ACCEPTANCE_SAMPLE_COUNT"
readonly START_MARKER="WINLOTTERY_OCR_ACCEPTANCE_STARTED"
readonly FAILURE_MARKER="WINLOTTERY_OCR_ACCEPTANCE_FAILURE"
readonly SUMMARY_MARKER="WINLOTTERY_OCR_ACCEPTANCE_SUMMARY"
readonly IOS_TIMEOUT_SECONDS="600"

source "${0:A:h}/current-vm-guard.sh"

# 失败或成功退出时清理匿名样本、测试包和构建目录，并恢复 iOS Release 应用。
cleanup_ocr_acceptance() {
  if [[ -n "${ios_console_process_id:-}" ]]; then
    kill "$ios_console_process_id" >/dev/null 2>&1 || true
    wait "$ios_console_process_id" >/dev/null 2>&1 || true
  fi
  if [[ -n "${android_device_stage:-}" ]]; then
    adb -s "$CURRENT_ANDROID_SERIAL_ID" shell rm -rf -- "$android_device_stage" >/dev/null 2>&1 || true
  fi
  adb -s "$CURRENT_ANDROID_SERIAL_ID" uninstall "$ANDROID_TEST_PACKAGE" >/dev/null 2>&1 || true
  if [[ "${ios_restore_required:-0}" == "1" && -d "${ios_release_app:-}" ]]; then
    xcrun simctl terminate "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_BUNDLE_ID" >/dev/null 2>&1 || true
    xcrun simctl install "$CURRENT_IOS_SIMULATOR_UDID" "$ios_release_app" >/dev/null 2>&1 || true
    xcrun simctl launch "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_BUNDLE_ID" >/dev/null 2>&1 || true
  fi
  if [[ -n "${acceptance_directory:-}" && -d "$acceptance_directory" ]]; then
    rm -rf -- "$acceptance_directory"
  fi
}

# 要求 Xcode 构建产物存在且非空。
require_acceptance_file() {
  [[ -s "$1" ]] || current_vm_fail "缺少构建产物 $1"
}

# 从 Android 单样本报告中统计指定结果分类。
count_android_outcome() {
  awk -v expected="outcome=$1" '
    {
      for (field = 1; field <= NF; field++) {
        if ($field == expected) count++
      }
    }
    END { print count + 0 }
  ' "$android_report_file"
}

# 汇总 Android 单样本报告中的数值字段。
sum_android_metric() {
  awk -v expected="$1" '
    {
      for (field = 1; field <= NF; field++) {
        split($field, pair, "=")
        if (pair[1] == expected) sum += pair[2]
      }
    }
    END { printf "%.0f", sum }
  ' "$android_report_file"
}

# 使用最近秩定义计算 Android 单样本报告的百分位。
percentile_android_metric() {
  awk -v expected="$1" -v percentile="$2" '
    {
      for (field = 1; field <= NF; field++) {
        split($field, pair, "=")
        if (pair[1] == expected) values[++count] = pair[2] + 0
      }
    }
    END {
      for (left = 1; left <= count; left++) {
        for (right = left + 1; right <= count; right++) {
          if (values[right] < values[left]) {
            temporary = values[left]
            values[left] = values[right]
            values[right] = temporary
          }
        }
      }
      rank = int(count * percentile)
      if (rank < count * percentile) rank++
      if (rank < 1) rank = 1
      printf "%.1f", values[rank]
    }
  ' "$android_report_file"
}

verify_current_mobile_vms
for required_command in adb rg xcodebuild; do
  command -v "$required_command" >/dev/null || current_vm_fail "缺少命令 $required_command"
done
[[ -d "$SAMPLE_DIRECTORY" ]] || current_vm_fail "缺少本地 OCR 验收图片目录"
sample_files=("$SAMPLE_DIRECTORY"/*.(jpg|jpeg|png)(N))
readonly sample_count=${#sample_files[@]}
(( sample_count > 0 )) || current_vm_fail "本地 OCR 验收图片目录中没有受支持图片"
(( sample_count <= 100 )) || current_vm_fail "本地 OCR 验收图片数量超过安全上限"

acceptance_directory="$(mktemp -d)"
readonly acceptance_directory
android_device_stage="/data/local/tmp/winlottery-ocr-acceptance"
trap cleanup_ocr_acceptance EXIT
cd "$REPOSITORY_ROOT"
export ANDROID_SERIAL="$CURRENT_ANDROID_SERIAL_ID"

./gradlew \
  spotlessCheck \
  :shared:recognition:packageAndroidDeviceTest \
  --rerun-tasks \
  --console=plain

readonly android_test_apk="$REPOSITORY_ROOT/shared/recognition/build/outputs/apk/androidTest/recognition-androidTest.apk"
[[ -s "$android_test_apk" ]] || current_vm_fail "缺少 Android OCR 验收测试 APK"
adb -s "$CURRENT_ANDROID_SERIAL_ID" uninstall "$ANDROID_TEST_PACKAGE" >/dev/null 2>&1 || true
adb -s "$CURRENT_ANDROID_SERIAL_ID" install "$android_test_apk" >/dev/null
adb -s "$CURRENT_ANDROID_SERIAL_ID" shell rm -rf -- "$android_device_stage" >/dev/null 2>&1 || true
adb -s "$CURRENT_ANDROID_SERIAL_ID" shell mkdir -p "$android_device_stage"
android_report_file="$acceptance_directory/android-ocr-acceptance.log"
touch "$android_report_file"
for ((index = 1; index <= sample_count; index++)); do
  anonymous_name="sample-001.jpg"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell run-as "$ANDROID_TEST_PACKAGE" mkdir -p cache/ocr-acceptance
  adb -s "$CURRENT_ANDROID_SERIAL_ID" push "${sample_files[$index]}" "$android_device_stage/$anonymous_name" \
    >/dev/null 2>&1
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell run-as "$ANDROID_TEST_PACKAGE" \
    cp "$android_device_stage/$anonymous_name" "cache/ocr-acceptance/$anonymous_name"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" logcat -c
  android_instrumentation_output="$({
    adb -s "$CURRENT_ANDROID_SERIAL_ID" shell am instrument -w -r \
      -e class "$ANDROID_TEST_CLASS" \
      -e winlottery.ocr.acceptance.samples 1 \
      -e winlottery.ocr.acceptance.sampleOffset "$((index - 1))" \
      "$ANDROID_TEST_PACKAGE/$ANDROID_TEST_RUNNER"
  })"
  print -r -- "$android_instrumentation_output" | rg -Fq "OK (1 test)" || {
    print -r -- "$android_instrumentation_output"
    current_vm_fail "Android 第 $index 张真实票图 OCR 验收未通过"
  }
  android_sample_report="$({
    adb -s "$CURRENT_ANDROID_SERIAL_ID" logcat -d -s "$ANDROID_LOG_TAG:I" '*:S' |
      rg -o "$SAMPLE_MARKER.*" |
      tail -n 1
  })"
  [[ "$android_sample_report" == *"sample=$index "* ]] ||
    current_vm_fail "Android 第 $index 张 OCR 匿名报告序号不匹配"
  print -r -- "$android_sample_report" >>"$android_report_file"
  adb -s "$CURRENT_ANDROID_SERIAL_ID" shell rm -f -- "$android_device_stage/$anonymous_name"
done
android_report="$SUMMARY_MARKER platform=android-emulator samples=$sample_count"
android_report+=" ready=$(count_android_outcome ready)"
android_report+=" correction_with_draft=$(count_android_outcome correction_with_draft)"
android_report+=" correction_empty=$(count_android_outcome correction_empty)"
android_report+=" unsupported=$(count_android_outcome unsupported)"
android_report+=" quality_rejected=$(count_android_outcome quality_rejected)"
android_report+=" recognition_poor=$(count_android_outcome recognition_poor_image)"
android_report+=" recognition_failure=$(count_android_outcome recognition_failure)"
for metric in ocr_lines safe_fields safe_bet_lines visual_rows numeric_candidates digit_characters; do
  android_report+=" $metric=$(sum_android_metric "$metric")"
done
android_report+=" p50_total_ms=$(percentile_android_metric total_ms 0.50)"
android_report+=" p95_total_ms=$(percentile_android_metric total_ms 0.95)"
android_report+=" p50_ocr_ms=$(percentile_android_metric ocr_ms 0.50)"
android_report+=" p95_ocr_ms=$(percentile_android_metric ocr_ms 0.95)"
print "Android 真实票图逐样本匿名结果："
print -r -- "$(<"$android_report_file")"
adb -s "$CURRENT_ANDROID_SERIAL_ID" shell rm -rf -- "$android_device_stage"
android_device_stage=""
adb -s "$CURRENT_ANDROID_SERIAL_ID" uninstall "$ANDROID_TEST_PACKAGE" >/dev/null

readonly ios_derived_data="$acceptance_directory/ios-derived-data"
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
require_acceptance_file "$ios_debug_app/WinLottery"
require_acceptance_file "$ios_release_app/WinLottery"

ios_restore_required=1
xcrun simctl terminate "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_BUNDLE_ID" >/dev/null 2>&1 || true
xcrun simctl install "$CURRENT_IOS_SIMULATOR_UDID" "$ios_debug_app"
readonly ios_data_container="$({
  xcrun simctl get_app_container "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_BUNDLE_ID" data
})"
readonly ios_sample_directory="$ios_data_container/tmp/$IOS_SAMPLE_DIRECTORY_NAME"
mkdir -p "$ios_sample_directory"
for ((index = 1; index <= sample_count; index++)); do
  anonymous_name="sample-$(printf '%03d' "$index").jpg"
  cp "${sample_files[$index]}" "$ios_sample_directory/$anonymous_name"
done

readonly ios_console_output="$acceptance_directory/ios-ocr-acceptance-console.log"
touch "$ios_console_output"
env "SIMCTL_CHILD_${IOS_SAMPLE_COUNT_ENVIRONMENT}=$sample_count" \
  xcrun simctl launch --terminate-running-process --console \
    "$CURRENT_IOS_SIMULATOR_UDID" \
    "$IOS_BUNDLE_ID" >"$ios_console_output" 2>&1 &
ios_console_process_id=$!

ios_report=""
for ((attempt = 1; attempt <= IOS_TIMEOUT_SECONDS; attempt++)); do
  if rg -Fq "$FAILURE_MARKER" "$ios_console_output"; then
    print -r -- "$(<"$ios_console_output")"
    current_vm_fail "iOS Debug 应用内真实票图 OCR 验收失败"
  fi
  if rg -Fq "$SUMMARY_MARKER" "$ios_console_output"; then
    ios_report="$(rg -o "$SUMMARY_MARKER.*" "$ios_console_output" | tail -n 1)"
    break
  fi
  kill -0 "$ios_console_process_id" >/dev/null 2>&1 || {
    wait "$ios_console_process_id" >/dev/null 2>&1 || true
    ios_console_process_id=""
    print -r -- "$(<"$ios_console_output")"
    current_vm_fail "iOS Debug 应用在 OCR 验收完成前退出"
  }
  sleep 1
done
[[ -n "$ios_report" ]] || current_vm_fail "iOS Debug 应用内真实票图 OCR 验收超时"
rg -Fq "$START_MARKER" "$ios_console_output" || current_vm_fail "iOS Debug OCR 验收入口未启动"
[[ "$ios_report" == *"samples=$sample_count"* ]] || current_vm_fail "iOS OCR 验收报告样本数不匹配"
ios_sample_reports="$({
  rg -o "$SAMPLE_MARKER.*" "$ios_console_output"
})"
print "iOS 真实票图逐样本匿名结果："
print -r -- "$ios_sample_reports"
kill "$ios_console_process_id" >/dev/null 2>&1 || true
wait "$ios_console_process_id" >/dev/null 2>&1 || true
ios_console_process_id=""

xcrun simctl terminate "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_BUNDLE_ID" >/dev/null 2>&1 || true
xcrun simctl install "$CURRENT_IOS_SIMULATOR_UDID" "$ios_release_app"
readonly ios_release_console_output="$acceptance_directory/ios-release-console.log"
touch "$ios_release_console_output"
env "SIMCTL_CHILD_${IOS_SAMPLE_COUNT_ENVIRONMENT}=$sample_count" \
  xcrun simctl launch --terminate-running-process --console \
    "$CURRENT_IOS_SIMULATOR_UDID" \
    "$IOS_BUNDLE_ID" >"$ios_release_console_output" 2>&1 &
ios_console_process_id=$!
sleep 3
rg -Fq "$START_MARKER" "$ios_release_console_output" &&
  current_vm_fail "iOS Release 应用错误启用了真实票图 OCR 验收入口"
kill "$ios_console_process_id" >/dev/null 2>&1 || true
wait "$ios_console_process_id" >/dev/null 2>&1 || true
ios_console_process_id=""
xcrun simctl launch --terminate-running-process "$CURRENT_IOS_SIMULATOR_UDID" "$IOS_BUNDLE_ID"
ios_restore_required=0

print "Android 真实票图匿名验收：$android_report"
print "iOS 真实票图匿名验收：$ios_report"
print "该结果只衡量 OCR 文档形成、解析完整度和耗时；没有逐字段真值时不能解释为号码准确率"

#!/bin/zsh

set -euo pipefail

readonly REPOSITORY_ROOT="${0:A:h:h:h}"
readonly ANDROID_APPLICATION_ID="roc.win.lottery"
readonly ANDROID_ACTIVITY="roc.win.lottery.MainActivity"
readonly IOS_EXPECTED_BUNDLE_ID="roc.win.lottery.WinLottery"
readonly VERIFY_INSTALL_LIFECYCLE="${WINLOTTERY_VERIFY_INSTALL_LIFECYCLE:-0}"

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

for required_command in codesign file jarsigner plutil rg shasum unzip xcodebuild; do
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
if [[ "$VERIFY_INSTALL_LIFECYCLE" == "1" ]]; then
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
if [[ "$VERIFY_INSTALL_LIFECYCLE" == "1" ]]; then
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
print "Android APK SHA-256：$(shasum -a 256 "$signed_android_apk" | awk '{print $1}')"
print "Android AAB SHA-256：$(shasum -a 256 "$android_aab" | awk '{print $1}')"
print "临时验收签名 APK 与 Xcode DerivedData 将在脚本退出时清理"

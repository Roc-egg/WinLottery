#!/bin/zsh

set -euo pipefail

readonly REPOSITORY_ROOT="${0:A:h:h:h}"
readonly ANDROID_APPLICATION_ID="roc.win.lottery"
readonly ANDROID_ACTIVITY="roc.win.lottery.MainActivity"
readonly IOS_EXPECTED_BUNDLE_ID="roc.win.lottery.WinLottery"

source "${0:A:h}/current-vm-guard.sh"

# 清理只为本轮验收创建的签名 APK 与 Xcode DerivedData。
cleanup_release_check() {
  if [[ -n "${release_check_directory:-}" && -d "$release_check_directory" ]]; then
    rm -rf -- "$release_check_directory"
  fi
}

# 要求文件存在且非空，避免后续命令误用不存在的构建产物。
require_release_file() {
  [[ -s "$1" ]] || current_vm_fail "缺少构建产物 $1"
}

verify_current_mobile_vms

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

./gradlew \
  spotlessCheck \
  :androidApp:assembleRelease \
  :androidApp:bundleRelease \
  --rerun-tasks \
  --console=plain

readonly unsigned_android_apk="$REPOSITORY_ROOT/androidApp/build/outputs/apk/release/androidApp-release-unsigned.apk"
readonly android_aab="$REPOSITORY_ROOT/androidApp/build/outputs/bundle/release/androidApp-release.aab"
readonly aligned_android_apk="$release_check_directory/androidApp-release-aligned.apk"
readonly signed_android_apk="$release_check_directory/androidApp-release-test-signed.apk"
require_release_file "$unsigned_android_apk"
require_release_file "$android_aab"

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

android_apk_size="$(stat -f '%z' "$signed_android_apk")"
android_aab_size="$(stat -f '%z' "$android_aab")"
ios_app_size_kib="$(du -sk "$ios_release_app" | awk '{print $1}')"

print "Android Release APK：${android_apk_size} 字节，进程 $android_process_id，非 Debug 校验通过"
print "Android Release AAB：${android_aab_size} 字节，${android_aab_signature_state}"
print "iOS Release Simulator App：${ios_app_size_kib} KiB，${ios_launch_result}，非 Debug 校验通过"
print "iOS 产物仅为 Simulator 本地签名 .app，不是 IPA 或发布签名"
print "Android APK SHA-256：$(shasum -a 256 "$signed_android_apk" | awk '{print $1}')"
print "Android AAB SHA-256：$(shasum -a 256 "$android_aab" | awk '{print $1}')"
print "临时验收签名 APK 与 Xcode DerivedData 将在脚本退出时清理"

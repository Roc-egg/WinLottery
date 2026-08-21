#!/bin/zsh

set -euo pipefail

readonly SCRIPT_DIRECTORY="${0:A:h}"
readonly ANDROID_APPLICATION_ID="roc.win.lottery"
readonly ANDROID_ACTIVITY="roc.win.lottery.MainActivity"
readonly IOS_APPLICATION_ID="roc.win.lottery.WinLottery"
readonly IOS_PLATFORM_VERSION="26.5"
readonly APPIUM_PORT="4725"
readonly APPIUM_BASE_URL="http://127.0.0.1:$APPIUM_PORT"

source "$SCRIPT_DIRECTORY/current-vm-guard.sh"

rotation_check_directory=""
appium_process_id=""
android_session_id=""
ios_session_id=""

# 删除临时证据、关闭 Appium 会话，并尽力把两个虚拟机恢复为竖屏。
cleanup_rotation_check() {
  if [[ -n "$android_session_id" ]]; then
    curl --silent --show-error \
      -H 'Content-Type: application/json' \
      --data-binary '{"orientation":"PORTRAIT"}' \
      "$APPIUM_BASE_URL/session/$android_session_id/orientation" >/dev/null 2>&1 || true
    curl --silent --show-error \
      -X DELETE \
      "$APPIUM_BASE_URL/session/$android_session_id" >/dev/null 2>&1 || true
  fi
  if [[ -n "$ios_session_id" ]]; then
    curl --silent --show-error \
      -H 'Content-Type: application/json' \
      --data-binary '{"orientation":"PORTRAIT"}' \
      "$APPIUM_BASE_URL/session/$ios_session_id/orientation" >/dev/null 2>&1 || true
    curl --silent --show-error \
      -X DELETE \
      "$APPIUM_BASE_URL/session/$ios_session_id" >/dev/null 2>&1 || true
  fi
  if [[ -n "$appium_process_id" ]]; then
    kill "$appium_process_id" >/dev/null 2>&1 || true
    wait "$appium_process_id" >/dev/null 2>&1 || true
  fi
  if [[ -n "$rotation_check_directory" && -d "$rotation_check_directory" ]]; then
    rm -rf -- "$rotation_check_directory"
  fi
}

# 输出 Appium 尾部日志后终止专项，便于定位设备会话失败原因。
fail_with_appium_log() {
  local message="$1"
  if [[ -n "$rotation_check_directory" && -f "$rotation_check_directory/appium.log" ]]; then
    tail -n 80 "$rotation_check_directory/appium.log" >&2
  fi
  current_vm_fail "$message"
}

# 等待只监听本机回环地址的 Appium 服务就绪。
wait_for_appium() {
  local attempt
  for attempt in {1..60}; do
    if curl --silent --show-error --fail "$APPIUM_BASE_URL/status" >/dev/null 2>&1; then
      return
    fi
    sleep 1
  done
  fail_with_appium_log "Appium 本机服务未在 60 秒内就绪"
}

# 创建精确绑定设备 UDID 的 Appium 会话并返回会话标识。
create_appium_session() {
  local capabilities="$1"
  local response
  response="$(
    curl --silent --show-error --fail-with-body \
      -H 'Content-Type: application/json' \
      --data-binary "$capabilities" \
      "$APPIUM_BASE_URL/session"
  )" || fail_with_appium_log "无法创建 Appium 设备会话"

  local session_id
  session_id="$(print -r -- "$response" | jq -r '.sessionId // .value.sessionId // empty')"
  [[ -n "$session_id" ]] || fail_with_appium_log "Appium 响应缺少会话标识"
  print -r -- "$session_id"
}

# 设置会话方向并核对驱动返回的系统方向。
set_and_verify_orientation() {
  local session_id="$1"
  local expected_orientation="$2"
  curl --silent --show-error --fail-with-body \
    -H 'Content-Type: application/json' \
    --data-binary "{\"orientation\":\"$expected_orientation\"}" \
    "$APPIUM_BASE_URL/session/$session_id/orientation" >/dev/null ||
    fail_with_appium_log "无法切换到 $expected_orientation"

  local actual_orientation
  actual_orientation="$(
    curl --silent --show-error --fail-with-body \
      "$APPIUM_BASE_URL/session/$session_id/orientation" |
      jq -r '.value // empty'
  )" || fail_with_appium_log "无法读取旋转后的系统方向"
  [[ "$actual_orientation" == "$expected_orientation" ]] ||
    fail_with_appium_log "系统方向不是 $expected_orientation"
}

# 保存当前应用截图，并核对像素方向与文件非空。
verify_screenshot_orientation() {
  local session_id="$1"
  local expected_orientation="$2"
  local screenshot_path="$3"
  local screenshot_value
  screenshot_value="$(
    curl --silent --show-error --fail-with-body \
      "$APPIUM_BASE_URL/session/$session_id/screenshot" |
      jq -r '.value // empty'
  )" || fail_with_appium_log "无法取得 $expected_orientation 截图"
  [[ -n "$screenshot_value" ]] || fail_with_appium_log "$expected_orientation 截图为空"
  print -rn -- "$screenshot_value" | base64 -D >"$screenshot_path"

  local screenshot_size screenshot_width screenshot_height
  screenshot_size="$(stat -f '%z' "$screenshot_path")"
  (( screenshot_size > 10000 )) || fail_with_appium_log "$expected_orientation 截图文件异常"
  screenshot_width="$(sips -g pixelWidth "$screenshot_path" | awk '/pixelWidth:/ { print $2 }')"
  screenshot_height="$(sips -g pixelHeight "$screenshot_path" | awk '/pixelHeight:/ { print $2 }')"
  print -r -- "$screenshot_width" | rg -q '^[0-9]+$' || fail_with_appium_log "截图宽度不是纯数字"
  print -r -- "$screenshot_height" | rg -q '^[0-9]+$' || fail_with_appium_log "截图高度不是纯数字"

  if [[ "$expected_orientation" == "LANDSCAPE" ]]; then
    (( screenshot_width > screenshot_height )) || fail_with_appium_log "横屏截图宽高关系异常"
  else
    (( screenshot_height > screenshot_width )) || fail_with_appium_log "竖屏截图宽高关系异常"
  fi
  print -r -- "${screenshot_width}x${screenshot_height}"
}

# 读取当前应用窗口的辅助功能树。
read_page_source() {
  local session_id="$1"
  local page_source
  page_source="$(
    curl --silent --show-error --fail-with-body \
      "$APPIUM_BASE_URL/session/$session_id/source" |
      jq -r '.value // empty'
  )" || fail_with_appium_log "无法读取首页辅助功能树"
  print -r -- "$page_source"
}

# 在当前应用窗口执行一次向上滑动，以访问横屏首屏之外的首页入口。
scroll_home_actions_into_view() {
  local session_id="$1"
  local window_rect
  window_rect="$(
    curl --silent --show-error --fail-with-body \
      "$APPIUM_BASE_URL/session/$session_id/window/rect" |
      jq -c '.value // empty'
  )" || fail_with_appium_log "无法读取旋转后的窗口尺寸"

  local window_width window_height start_x start_y end_y
  window_width="$(print -r -- "$window_rect" | jq -r '.width // empty')"
  window_height="$(print -r -- "$window_rect" | jq -r '.height // empty')"
  print -r -- "$window_width" | rg -q '^[0-9]+$' || fail_with_appium_log "窗口宽度不是纯数字"
  print -r -- "$window_height" | rg -q '^[0-9]+$' || fail_with_appium_log "窗口高度不是纯数字"
  start_x="$(( window_width / 2 ))"
  start_y="$(( window_height * 3 / 4 ))"
  end_y="$(( window_height / 4 ))"

  local actions
  actions="$(
    jq -cn \
      --argjson x "$start_x" \
      --argjson start_y "$start_y" \
      --argjson end_y "$end_y" \
      '{
        actions: [
          {
            type: "pointer",
            id: "finger",
            parameters: { pointerType: "touch" },
            actions: [
              { type: "pointerMove", duration: 0, x: $x, y: $start_y, origin: "viewport" },
              { type: "pointerDown", button: 0 },
              { type: "pause", duration: 200 },
              { type: "pointerMove", duration: 600, x: $x, y: $end_y, origin: "viewport" },
              { type: "pointerUp", button: 0 }
            ]
          }
        ]
      }'
  )"
  curl --silent --show-error --fail-with-body \
    -H 'Content-Type: application/json' \
    --data-binary "$actions" \
    "$APPIUM_BASE_URL/session/$session_id/actions" >/dev/null ||
    fail_with_appium_log "无法滚动旋转后的首页"
}

# 核对首页关键入口在首屏或一次滚动后仍可由辅助功能访问。
verify_home_accessibility() {
  local session_id="$1"
  local platform_name="$2"
  local page_source
  page_source="$(read_page_source "$session_id")"
  if [[ "$page_source" != *"导入彩票图片"* || "$page_source" != *"手动录入彩票"* ]]; then
    scroll_home_actions_into_view "$session_id"
    page_source="$(read_page_source "$session_id")"
  fi
  [[ "$page_source" == *"导入彩票图片"* ]] || fail_with_appium_log "$platform_name 首页缺少导图入口"
  [[ "$page_source" == *"手动录入彩票"* ]] || fail_with_appium_log "$platform_name 首页缺少手动录入入口"
}

# 返回指定 Simulator 内目标 iOS 应用的唯一进程标识。
ios_application_process_id() {
  local process_id
  process_id="$(
    xcrun simctl spawn "$CURRENT_IOS_SIMULATOR_UDID" launchctl list |
      rg -F "UIKitApplication:${IOS_APPLICATION_ID}[" |
      cut -f 1
  )"
  print -r -- "$process_id" | rg -q '^[0-9]+$' || fail_with_appium_log "iOS 目标应用 PID 不是单个纯数字"
  print -r -- "$process_id"
}

verify_current_mobile_vms
for required_command in appium base64 curl cut jq sips stat; do
  command -v "$required_command" >/dev/null || current_vm_fail "缺少命令 $required_command"
done

# 先复用非 Debug 构建、签名边界、安装和启动闸门，再做旋转交互。
"$SCRIPT_DIRECTORY/run-current-vm-release-checks.sh"

rotation_check_directory="$(mktemp -d)"
readonly rotation_check_directory
trap cleanup_rotation_check EXIT

appium \
  --address 127.0.0.1 \
  --port "$APPIUM_PORT" \
  --log-level warn \
  --log-no-colors \
  >"$rotation_check_directory/appium.log" 2>&1 &
appium_process_id="$!"
wait_for_appium

readonly android_capabilities="$(
  jq -cn \
    --arg udid "$CURRENT_ANDROID_SERIAL_ID" \
    --arg device_name "$CURRENT_ANDROID_AVD_NAME" \
    --arg package_name "$ANDROID_APPLICATION_ID" \
    --arg activity_name "$ANDROID_ACTIVITY" \
    '{
      capabilities: {
        alwaysMatch: {
          platformName: "Android",
          "appium:automationName": "UiAutomator2",
          "appium:udid": $udid,
          "appium:deviceName": $device_name,
          "appium:appPackage": $package_name,
          "appium:appActivity": $activity_name,
          "appium:noReset": true,
          "appium:newCommandTimeout": 120
        }
      }
    }'
)"
android_session_id="$(create_appium_session "$android_capabilities")"
android_process_id_before="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell pidof "$ANDROID_APPLICATION_ID" | tr -d '\r')"
print -r -- "$android_process_id_before" | rg -q '^[0-9]+$' || fail_with_appium_log "Android 目标应用 PID 不是单个纯数字"

set_and_verify_orientation "$android_session_id" LANDSCAPE
verify_home_accessibility "$android_session_id" Android
android_landscape_size="$(
  verify_screenshot_orientation \
    "$android_session_id" \
    LANDSCAPE \
    "$rotation_check_directory/android-landscape.png"
)"
android_process_id_landscape="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell pidof "$ANDROID_APPLICATION_ID" | tr -d '\r')"
[[ "$android_process_id_landscape" == "$android_process_id_before" ]] ||
  fail_with_appium_log "Android 横屏后应用进程未保持"

set_and_verify_orientation "$android_session_id" PORTRAIT
verify_home_accessibility "$android_session_id" Android
android_portrait_size="$(
  verify_screenshot_orientation \
    "$android_session_id" \
    PORTRAIT \
    "$rotation_check_directory/android-portrait.png"
)"
android_process_id_portrait="$(adb -s "$CURRENT_ANDROID_SERIAL_ID" shell pidof "$ANDROID_APPLICATION_ID" | tr -d '\r')"
[[ "$android_process_id_portrait" == "$android_process_id_before" ]] ||
  fail_with_appium_log "Android 恢复竖屏后应用进程未保持"
curl --silent --show-error --fail-with-body \
  -X DELETE \
  "$APPIUM_BASE_URL/session/$android_session_id" >/dev/null
android_session_id=""

readonly ios_capabilities="$(
  jq -cn \
    --arg udid "$CURRENT_IOS_SIMULATOR_UDID" \
    --arg device_name "$CURRENT_IOS_SIMULATOR_NAME" \
    --arg bundle_id "$IOS_APPLICATION_ID" \
    --arg platform_version "$IOS_PLATFORM_VERSION" \
    '{
      capabilities: {
        alwaysMatch: {
          platformName: "iOS",
          "appium:automationName": "XCUITest",
          "appium:udid": $udid,
          "appium:deviceName": $device_name,
          "appium:platformVersion": $platform_version,
          "appium:bundleId": $bundle_id,
          "appium:noReset": true,
          "appium:shouldTerminateApp": false,
          "appium:useNewWDA": false,
          "appium:wdaLocalPort": 8101,
          "appium:newCommandTimeout": 120
        }
      }
    }'
)"
ios_session_id="$(create_appium_session "$ios_capabilities")"
ios_process_id_before="$(ios_application_process_id)"

set_and_verify_orientation "$ios_session_id" LANDSCAPE
verify_home_accessibility "$ios_session_id" iOS
ios_landscape_size="$(
  verify_screenshot_orientation \
    "$ios_session_id" \
    LANDSCAPE \
    "$rotation_check_directory/ios-landscape.png"
)"
ios_process_id_landscape="$(ios_application_process_id)"
[[ "$ios_process_id_landscape" == "$ios_process_id_before" ]] ||
  fail_with_appium_log "iOS 横屏后应用进程未保持"

set_and_verify_orientation "$ios_session_id" PORTRAIT
verify_home_accessibility "$ios_session_id" iOS
ios_portrait_size="$(
  verify_screenshot_orientation \
    "$ios_session_id" \
    PORTRAIT \
    "$rotation_check_directory/ios-portrait.png"
)"
ios_process_id_portrait="$(ios_application_process_id)"
[[ "$ios_process_id_portrait" == "$ios_process_id_before" ]] ||
  fail_with_appium_log "iOS 恢复竖屏后应用进程未保持"

print "Android Release 旋转通过：横屏 $android_landscape_size，竖屏 $android_portrait_size，进程 $android_process_id_before 保持"
print "iOS Release 旋转通过：横屏 $ios_landscape_size，竖屏 $ios_portrait_size，进程 $ios_process_id_before 保持"
print "双虚拟机首页关键入口在横竖屏均可访问，临时截图和 Appium 日志将在退出时清理"

#!/bin/zsh

set -euo pipefail

readonly REPOSITORY_ROOT="${0:A:h:h:h}"

source "${0:A:h}/current-vm-guard.sh"
verify_current_mobile_vms

cd "$REPOSITORY_ROOT"
export ANDROID_SERIAL="$CURRENT_ANDROID_SERIAL_ID"

./gradlew \
  spotlessCheck \
  iosSimulatorArm64Test \
  :shared:data:connectedAndroidDeviceTest \
  --rerun-tasks \
  --console=plain

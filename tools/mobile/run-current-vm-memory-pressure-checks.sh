#!/bin/zsh

set -euo pipefail

# 启用 Android 内存收紧与 iOS Debug 模拟内存警告开发专项。
export WINLOTTERY_VERIFY_MEMORY_PRESSURE=1
exec "${0:A:h}/run-current-vm-release-checks.sh"

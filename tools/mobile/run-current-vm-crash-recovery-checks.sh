#!/bin/zsh

set -euo pipefail

# 启用 Debug 受控崩溃、启动清扫和 Release 反向检查专项。
export WINLOTTERY_VERIFY_CRASH_RECOVERY=1
exec "${0:A:h}/run-current-vm-release-checks.sh"

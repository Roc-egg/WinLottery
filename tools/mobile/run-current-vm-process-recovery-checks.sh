#!/bin/zsh

set -euo pipefail

# 启用只终止应用进程、不卸载应用的双虚拟机恢复专项。
export WINLOTTERY_VERIFY_PROCESS_RECOVERY=1
exec "${0:A:h}/run-current-vm-release-checks.sh"

#!/bin/zsh

set -euo pipefail

# 启用只向已核验目标应用 PID 发送 SIGKILL 的双虚拟机异常终止专项。
export WINLOTTERY_VERIFY_ABRUPT_TERMINATION=1
exec "${0:A:h}/run-current-vm-release-checks.sh"

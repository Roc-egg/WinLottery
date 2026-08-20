#!/bin/zsh

set -euo pipefail

# 启用会清空当前双虚拟机应用数据的构建号 1 到构建号 2 升级开发专项。
export WINLOTTERY_VERIFY_VERSION_UPGRADE=1
exec "${0:A:h}/run-current-vm-release-checks.sh"

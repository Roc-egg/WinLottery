#!/bin/zsh

set -euo pipefail

# 启用会清空当前双虚拟机应用数据的安装与卸载生命周期专项。
export WINLOTTERY_VERIFY_INSTALL_LIFECYCLE=1
exec "${0:A:h}/run-current-vm-release-checks.sh"

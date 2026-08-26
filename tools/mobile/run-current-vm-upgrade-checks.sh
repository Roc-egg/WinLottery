#!/bin/zsh

set -euo pipefail

# 启用保留现有应用数据、只清理明确验收记录的 1.2.0 (4) 到 1.3.0 (5) 真实 Room 升级专项。
export WINLOTTERY_VERIFY_VERSION_UPGRADE=1
exec "${0:A:h}/run-current-vm-release-checks.sh"

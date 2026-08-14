#!/usr/bin/env bash
set -euo pipefail

# 当前转换工具目录。
SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# WinLottery 仓库根目录。
PROJECT_ROOT="$(cd "$SCRIPT_DIRECTORY/../.." && pwd)"

# 允许开发者显式覆盖的 Python 3.9.6 路径。
CONVERTER_PYTHON="${WINLOTTERY_CONVERTER_PYTHON:-/Applications/Xcode.app/Contents/Developer/usr/bin/python3}"

# 仓库内隔离的转换虚拟环境，不写入全局 Python。
VIRTUAL_ENVIRONMENT_DIRECTORY="$PROJECT_ROOT/.gradle/desktop-ocr/converter-venv"

# 仓库内隔离的 pip 下载缓存。
PIP_CACHE_DIRECTORY="$PROJECT_ROOT/.gradle/desktop-ocr/pip-cache"

if [[ "$(uname -s)" != "Darwin" || "$(uname -m)" != "arm64" ]]; then
    echo "PP-OCRv5 锁定转换只支持 macOS arm64" >&2
    exit 2
fi

if [[ ! -x "$CONVERTER_PYTHON" ]]; then
    echo "未找到 Python 3.9.6：$CONVERTER_PYTHON" >&2
    exit 2
fi

if [[ "$("$CONVERTER_PYTHON" -c 'import platform; print(platform.python_version())')" != "3.9.6" ]]; then
    echo "PP-OCRv5 锁定转换必须使用 Python 3.9.6" >&2
    exit 2
fi

if [[ ! -x "$VIRTUAL_ENVIRONMENT_DIRECTORY/bin/python" ]]; then
    "$CONVERTER_PYTHON" -m venv "$VIRTUAL_ENVIRONMENT_DIRECTORY"
fi

mkdir -p "$PIP_CACHE_DIRECTORY"
PIP_CACHE_DIR="$PIP_CACHE_DIRECTORY" \
    "$VIRTUAL_ENVIRONMENT_DIRECTORY/bin/python" -m pip install \
    --disable-pip-version-check \
    --only-binary=:all: \
    --require-hashes \
    --requirement "$SCRIPT_DIRECTORY/requirements-macos-arm64.lock"

exec "$VIRTUAL_ENVIRONMENT_DIRECTORY/bin/python" \
    "$SCRIPT_DIRECTORY/convert_ppocrv5.py" \
    "$@"

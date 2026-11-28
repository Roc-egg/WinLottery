#!/usr/bin/env python3
"""下载或校验 iOS 本地包使用的 ONNX Runtime xcframework。

微软的 onnxruntime-swift-package-manager 仓库和 download.onnxruntime.ai 上的 pod 压缩包已于 2026-11 失效，
iOS 改用 nuget.org 上微软官方签名的 Microsoft.ML.OnnxRuntime 1.24.2 包：取出其中的
`runtimes/ios/native/onnxruntime.xcframework.zip`，按固定字节数和 SHA-256 校验后原样解压到
`iosApp/Vendor/onnxruntime-swift-package-manager/Frameworks/`，不做任何修改。只使用 Python 标准库和 macOS 自带的
ditto，可在 CI 中直接运行。

用法：
  python3 -I tools/ios/fetch_onnxruntime.py                       # 缺失或版本不符时下载并解压
  python3 -I tools/ios/fetch_onnxruntime.py --verify              # 只检查已解压的版本，不联网
  python3 -I tools/ios/fetch_onnxruntime.py --nupkg 本地.nupkg     # 使用已下载的 NuGet 包，不联网
"""

import argparse
import hashlib
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

PROJECT_ROOT = pathlib.Path(__file__).resolve().parents[2]

# 本地 Swift Package 中二进制目标所在目录，与 Package.swift 的 binaryTarget 路径一致。
FRAMEWORKS_DIRECTORY = PROJECT_ROOT / "iosApp/Vendor/onnxruntime-swift-package-manager/Frameworks"

# 解压后的 xcframework 名称。
XCFRAMEWORK_NAME = "onnxruntime.xcframework"

# 记录已解压内容对应的压缩包 SHA-256，用于跳过重复下载。
STAMP_NAME = ".onnxruntime-xcframework.sha256"

# 微软官方 NuGet 包；包内作者签名为 Microsoft Corporation，仓库副署为 NuGet.org Repository by Microsoft。
NUPKG = {
    "component": "Microsoft.ML.OnnxRuntime 1.24.2",
    "url": "https://api.nuget.org/v3-flatcontainer/microsoft.ml.onnxruntime/1.24.2/"
    "microsoft.ml.onnxruntime.1.24.2.nupkg",
    "byteCount": 124_756_536,
    "sha256": "782564d3d68e87269ea1812cc94710ad06fe014faf093f01a6f89d0bd58719d3",
}

# NuGet 包内的 iOS xcframework 压缩包。
XCFRAMEWORK_ARCHIVE = {
    "member": "runtimes/ios/native/onnxruntime.xcframework.zip",
    "byteCount": 50_051_623,
    "sha256": "a161fe8628ed3bf8d97915bd34063fe115f58a1b9b0f4f0baec78c62487ac3ee",
}

# 构建 iOS 真机和模拟器必须存在的切片。
REQUIRED_SLICES = ["ios-arm64", "ios-arm64_x86_64-simulator"]

# 下载中断时的最大尝试次数，后续尝试从已下载的位置续传。
MAXIMUM_DOWNLOAD_ATTEMPTS = 4


def sha256_file(path):
    """流式计算文件 SHA-256。"""
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_file(path, expected, label):
    """核对字节数和 SHA-256，不一致时抛出异常。"""
    size = path.stat().st_size
    if size != expected["byteCount"]:
        raise ValueError(f"{label} 字节数不一致: expected={expected['byteCount']}, actual={size}")
    actual = sha256_file(path)
    if actual != expected["sha256"]:
        raise ValueError(f"{label} SHA-256 不一致: expected={expected['sha256']}, actual={actual}")


def download(url, destination):
    """下载到指定文件，连接中断时按已下载字节续传。"""
    for attempt in range(1, MAXIMUM_DOWNLOAD_ATTEMPTS + 1):
        offset = destination.stat().st_size if destination.exists() else 0
        request = urllib.request.Request(url, headers={"Range": f"bytes={offset}-"} if offset else {})
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                # 服务器不支持续传时会返回完整内容，需要从头写入。
                mode = "ab" if offset and response.status == 206 else "wb"
                with destination.open(mode) as output:
                    for chunk in iter(lambda: response.read(1024 * 1024), b""):
                        output.write(chunk)
            return
        except OSError as error:
            if attempt == MAXIMUM_DOWNLOAD_ATTEMPTS:
                raise
            print(f"下载中断，第 {attempt} 次重试: {error}", file=sys.stderr)


def extract_archive(archive, destination):
    """用保留符号链接的系统工具解压 xcframework 压缩包。"""
    if shutil.which("ditto"):
        command = ["ditto", "-x", "-k", str(archive), str(destination)]
    else:
        command = ["unzip", "-q", str(archive), "-d", str(destination)]
    subprocess.run(command, check=True)


def verify_installed(frameworks_directory):
    """检查已解压的 xcframework 来自锁定的压缩包且包含所需切片。"""
    stamp = frameworks_directory / STAMP_NAME
    xcframework = frameworks_directory / XCFRAMEWORK_NAME
    if not stamp.is_file() or stamp.read_text(encoding="utf-8").strip() != XCFRAMEWORK_ARCHIVE["sha256"]:
        raise ValueError(f"{xcframework} 缺失或不是锁定版本，请先运行 python3 -I tools/ios/fetch_onnxruntime.py")
    verify_slices(xcframework)


def verify_slices(xcframework):
    """确认 xcframework 含 Info.plist 和真机、模拟器切片中的静态库。"""
    if not (xcframework / "Info.plist").is_file():
        raise ValueError(f"{xcframework} 缺少 Info.plist")
    for slice_name in REQUIRED_SLICES:
        library = xcframework / slice_name / "onnxruntime.framework" / "onnxruntime"
        if not library.is_file():
            raise ValueError(f"{xcframework} 缺少 {slice_name} 切片")


def install(nupkg_path, frameworks_directory):
    """从 NuGet 包取出 xcframework 压缩包，校验后原子替换旧版本。"""
    verify_file(nupkg_path, NUPKG, NUPKG["component"])
    frameworks_directory.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".onnxruntime-", dir=frameworks_directory) as temporary_name:
        temporary = pathlib.Path(temporary_name)
        archive = temporary / "onnxruntime.xcframework.zip"
        with zipfile.ZipFile(nupkg_path) as package, package.open(XCFRAMEWORK_ARCHIVE["member"]) as source:
            with archive.open("wb") as output:
                shutil.copyfileobj(source, output, 1024 * 1024)
        verify_file(archive, XCFRAMEWORK_ARCHIVE, XCFRAMEWORK_ARCHIVE["member"])
        extracted = temporary / "extracted"
        extract_archive(archive, extracted)
        verify_slices(extracted / XCFRAMEWORK_NAME)
        target = frameworks_directory / XCFRAMEWORK_NAME
        stamp = frameworks_directory / STAMP_NAME
        stamp.unlink(missing_ok=True)
        if target.exists():
            shutil.rmtree(target)
        os.replace(extracted / XCFRAMEWORK_NAME, target)
        stamp.write_text(XCFRAMEWORK_ARCHIVE["sha256"] + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--verify", action="store_true", help="只检查已解压的版本，不联网")
    parser.add_argument("--force", action="store_true", help="即使已是锁定版本也重新下载解压")
    parser.add_argument("--nupkg", type=pathlib.Path, help="使用已下载的 NuGet 包，不联网")
    parser.add_argument("--frameworks-dir", type=pathlib.Path, default=FRAMEWORKS_DIRECTORY, help="解压目录")
    arguments = parser.parse_args()
    frameworks_directory = arguments.frameworks_dir
    if arguments.verify:
        verify_installed(frameworks_directory)
    else:
        try:
            if arguments.force:
                raise ValueError("要求重新下载")
            verify_installed(frameworks_directory)
            print(f"{XCFRAMEWORK_NAME} 已是锁定版本，跳过下载")
            return 0
        except ValueError:
            pass
        if arguments.nupkg:
            install(arguments.nupkg, frameworks_directory)
        else:
            with tempfile.TemporaryDirectory(prefix="winlottery-onnxruntime-") as download_directory:
                nupkg_path = pathlib.Path(download_directory) / "onnxruntime.nupkg"
                download(NUPKG["url"], nupkg_path)
                install(nupkg_path, frameworks_directory)
    print(f"{XCFRAMEWORK_NAME} 校验通过: {NUPKG['component']}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except ValueError as failure:
        print(f"错误: {failure}", file=sys.stderr)
        sys.exit(1)

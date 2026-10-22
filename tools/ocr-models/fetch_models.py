#!/usr/bin/env python3
"""下载或校验随应用分发的 OCR 模型与字典。

全部文件取自 PaddlePaddle 官方发布的固定修订，按原样分发，不做转换、量化或裁剪：
三个模型来自官方 Hugging Face 仓库的 `inference.onnx`，字典来自 PaddleOCR 仓库，
并与识别模型 `inference.yml` 内嵌的字符表逐项一致。只使用 Python 标准库，可在 CI 中直接运行。

用法：
  python3 -I tools/ocr-models/fetch_models.py           # 下载到资源目录并校验
  python3 -I tools/ocr-models/fetch_models.py --verify  # 只校验仓库中已提交的文件，不联网
"""

import argparse
import hashlib
import os
import pathlib
import sys
import tempfile
import urllib.request

PROJECT_ROOT = pathlib.Path(__file__).resolve().parents[2]

# 与 PpOcrResourceLocks.kt 中的资源锁保持一致。
RESOURCE_DIRECTORY = (
    PROJECT_ROOT / "shared/recognition/src/commonMain/resources/roc/win/lottery/recognition/models/ppocr"
)

# 资源文件名、官方来源、字节数与 SHA-256；许可证均为 Apache-2.0。
RESOURCES = [
    {
        "file": "detection.onnx",
        "component": "PP-OCRv6_small_det",
        "url": "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx/resolve/"
        "28fe5895c24fd108c19eb3e8479f4ab385fbfc62/inference.onnx",
        "byteCount": 9_880_512,
        "sha256": "d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e",
    },
    {
        "file": "orientation.onnx",
        "component": "PP-LCNet_x0_25_textline_ori",
        "url": "https://huggingface.co/PaddlePaddle/PP-LCNet_x0_25_textline_ori_onnx/resolve/"
        "aea1f18d97338aaf84b463f90192f2820524a00a/inference.onnx",
        "byteCount": 1_019_454,
        "sha256": "94a6a0a0425f2b5f08b5df72086f2d72abe40f1d22f6d12d2cd83674f11f2ff3",
    },
    {
        "file": "recognition.onnx",
        "component": "PP-OCRv6_small_rec",
        "url": "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/"
        "b8f84f0b80c529de40b4fbb3544b84fa7233a513/inference.onnx",
        "byteCount": 21_159_378,
        "sha256": "5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634",
    },
    {
        "file": "characters.txt",
        "component": "ppocrv6_dict.txt",
        "url": "https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/"
        "b03f46425e8ff4442b268ce449e3eef758146cd4/ppocr/utils/dict/ppocrv6_dict.txt",
        "byteCount": 74_947,
        "sha256": "b5f2bfe2bdd9448429e3e82b51c789775d9b42f2403d082b00662eb77e401c5d",
    },
]


def sha256_file(path):
    """流式计算文件 SHA-256。"""
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify(path, resource):
    """核对字节数和 SHA-256，不一致时抛出异常。"""
    size = path.stat().st_size
    if size != resource["byteCount"]:
        raise ValueError(f"{resource['file']} 字节数不一致: expected={resource['byteCount']}, actual={size}")
    actual = sha256_file(path)
    if actual != resource["sha256"]:
        raise ValueError(f"{resource['file']} SHA-256 不一致: expected={resource['sha256']}, actual={actual}")


def download(resource, destination):
    """下载到同目录临时文件，校验通过后原子替换。"""
    destination.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{resource['file']}.", dir=destination.parent)
    temporary = pathlib.Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as output, urllib.request.urlopen(resource["url"], timeout=120) as response:
            for chunk in iter(lambda: response.read(1024 * 1024), b""):
                output.write(chunk)
        verify(temporary, resource)
        os.replace(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--verify", action="store_true", help="只校验仓库中已提交的文件，不联网")
    parser.add_argument("--output-dir", type=pathlib.Path, default=RESOURCE_DIRECTORY, help="资源目录")
    arguments = parser.parse_args()
    for resource in RESOURCES:
        destination = arguments.output_dir / resource["file"]
        if not arguments.verify:
            download(resource, destination)
        verify(destination, resource)
        print(f"{resource['file']} 校验通过: {resource['component']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

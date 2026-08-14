#!/usr/bin/env python3
"""按仓库锁定清单下载、转换并验证 PP-OCRv5 Desktop 模型。"""

import argparse
import hashlib
import importlib.metadata
import json
import os
import pathlib
import platform
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
from typing import Any, List, Mapping, Optional, Sequence


# 当前工具所在目录。
SCRIPT_DIRECTORY = pathlib.Path(__file__).resolve().parent

# WinLottery 仓库根目录。
PROJECT_ROOT = SCRIPT_DIRECTORY.parents[1]

# 同时供转换工具和 JVM 运行时使用的模型锁定清单。
MODEL_LOCK_PATH = (
    PROJECT_ROOT
    / "shared/recognition/src/jvmMain/resources"
    / "roc/win/lottery/recognition/models/ppocrv5/model-lock.json"
)

# 下载后的官方源文件缓存目录。
DEFAULT_CACHE_DIRECTORY = PROJECT_ROOT / ".gradle/desktop-ocr/ppocrv5/source"

# 转换过程中使用的临时工作目录。
DEFAULT_WORK_DIRECTORY = PROJECT_ROOT / ".gradle/desktop-ocr/ppocrv5/work"

# 最终生成的 JVM 资源根目录。
DEFAULT_RESOURCE_DIRECTORY = (
    PROJECT_ROOT
    / "shared/recognition/build/generated/desktopOcrResources"
    / "roc/win/lottery/recognition/models/ppocrv5"
)

# 锁定清单允许的 SHA-256 文本格式。
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")

# 当前模型包必须且只允许包含的三段模型标识。
EXPECTED_MODEL_IDS = ["detection", "orientation", "recognition"]


def require_mapping(value: Any, label: str) -> Mapping[str, Any]:
    """要求给定值是 JSON 对象并返回该对象。"""
    if not isinstance(value, dict):
        raise ValueError(f"{label} 必须是对象")
    return value


def require_list(value: Any, label: str) -> List[Any]:
    """要求给定值是 JSON 数组并返回该数组。"""
    if not isinstance(value, list):
        raise ValueError(f"{label} 必须是数组")
    return value


def require_string(record: Mapping[str, Any], key: str, label: str) -> str:
    """读取对象中的非空字符串字段。"""
    value = record.get(key)
    if not isinstance(value, str) or not value:
        raise ValueError(f"{label}.{key} 必须是非空字符串")
    return value


def require_integer(record: Mapping[str, Any], key: str, label: str) -> int:
    """读取对象中的整数字段，拒绝布尔值伪装成整数。"""
    value = record.get(key)
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValueError(f"{label}.{key} 必须是整数")
    return value


def require_boolean(record: Mapping[str, Any], key: str, label: str) -> bool:
    """读取对象中的布尔字段。"""
    value = record.get(key)
    if not isinstance(value, bool):
        raise ValueError(f"{label}.{key} 必须是布尔值")
    return value


def validate_https_url(value: str, label: str) -> None:
    """要求外部来源使用 HTTPS，避免转换工具降级到明文下载。"""
    if not value.startswith("https://"):
        raise ValueError(f"{label} 必须使用 HTTPS")


def validate_file_name(value: str, label: str) -> None:
    """要求清单文件名不包含目录或路径穿越片段。"""
    if pathlib.PurePath(value).name != value or value in {".", ".."}:
        raise ValueError(f"{label} 必须是安全的单层文件名")


def validate_sha256(value: str, label: str) -> None:
    """要求哈希是 64 位小写十六进制 SHA-256。"""
    if SHA256_PATTERN.fullmatch(value) is None:
        raise ValueError(f"{label} 必须是 64 位小写 SHA-256")


def validate_file_record(record: Mapping[str, Any], label: str) -> None:
    """校验带文件名、字节数和哈希的通用锁定记录。"""
    file_name = require_string(record, "fileName", label)
    validate_file_name(file_name, f"{label}.fileName")
    if require_integer(record, "byteCount", label) <= 0:
        raise ValueError(f"{label}.byteCount 必须大于零")
    validate_sha256(require_string(record, "sha256", label), f"{label}.sha256")


def validate_source_record(record: Mapping[str, Any], label: str) -> None:
    """校验可下载官方源文件的 URL 和文件锁。"""
    validate_file_record(record, label)
    validate_https_url(require_string(record, "url", label), f"{label}.url")


def validate_shape(value: Any, label: str) -> None:
    """校验只允许用 -1 表示动态维度的张量形状。"""
    dimensions = require_list(value, label)
    if not dimensions:
        raise ValueError(f"{label} 不能为空")
    for dimension in dimensions:
        if isinstance(dimension, bool) or not isinstance(dimension, int):
            raise ValueError(f"{label} 只能包含整数")
        if dimension == 0 or dimension < -1:
            raise ValueError(f"{label} 只允许正整数或 -1")


def validate_tensor_contract(value: Any, label: str) -> None:
    """校验单输入或单输出张量的名称和形状。"""
    contract = require_mapping(value, label)
    require_string(contract, "name", label)
    validate_shape(contract.get("shape"), f"{label}.shape")


def validate_model_record(value: Any, index: int) -> Mapping[str, Any]:
    """校验一份 Paddle 源模型、ONNX 输出和接口记录。"""
    label = f"models[{index}]"
    model = require_mapping(value, label)
    require_string(model, "id", label)
    require_string(model, "upstreamName", label)

    source = require_mapping(model.get("source"), f"{label}.source")
    validate_source_record(source, f"{label}.source")
    validate_file_name(
        require_string(source, "modelDirectory", f"{label}.source"),
        f"{label}.source.modelDirectory",
    )

    resource_file = require_string(model, "resourceFile", label)
    validate_file_name(resource_file, f"{label}.resourceFile")
    if not resource_file.endswith(".onnx"):
        raise ValueError(f"{label}.resourceFile 必须是 ONNX 文件")
    if require_integer(model, "byteCount", label) <= 0:
        raise ValueError(f"{label}.byteCount 必须大于零")
    validate_sha256(require_string(model, "sha256", label), f"{label}.sha256")
    if require_integer(model, "irVersion", label) <= 0:
        raise ValueError(f"{label}.irVersion 必须大于零")
    if require_integer(model, "nodeCount", label) <= 0:
        raise ValueError(f"{label}.nodeCount 必须大于零")
    validate_tensor_contract(model.get("input"), f"{label}.input")
    validate_tensor_contract(model.get("output"), f"{label}.output")
    validate_shape(
        require_mapping(model.get("syntheticParity"), f"{label}.syntheticParity").get(
            "inputShape"
        ),
        f"{label}.syntheticParity.inputShape",
    )
    return model


def validate_model_lock(value: Any) -> Mapping[str, Any]:
    """严格校验模型锁的版本、来源、转换参数和三段模型边界。"""
    model_lock = require_mapping(value, "modelLock")
    if require_integer(model_lock, "schemaVersion", "modelLock") != 1:
        raise ValueError("只支持 schemaVersion=1 的模型锁")
    require_string(model_lock, "bundleId", "modelLock")
    resource_root = require_string(model_lock, "resourceRoot", "modelLock")
    if resource_root.startswith("/") or ".." in pathlib.PurePosixPath(resource_root).parts:
        raise ValueError("modelLock.resourceRoot 必须是安全的相对资源路径")

    conversion = require_mapping(model_lock.get("conversion"), "conversion")
    if require_integer(conversion, "opsetVersion", "conversion") != 17:
        raise ValueError("当前模型锁只允许 opsetVersion=17")
    if require_boolean(conversion, "enableAutoUpdateOpset", "conversion"):
        raise ValueError("转换时禁止自动升级 opset")
    if not require_boolean(conversion, "enableOnnxChecker", "conversion"):
        raise ValueError("转换时必须开启 ONNX checker")
    if require_string(conversion, "optimizeTool", "conversion") != "None":
        raise ValueError("当前锁定输出必须明确关闭外部 ONNX 优化器")
    for field in (
        "platform",
        "pythonVersion",
        "paddlePaddleVersion",
        "paddle2OnnxVersion",
        "onnxVersion",
        "packagingVersion",
        "pyYamlVersion",
    ):
        require_string(conversion, field, "conversion")

    licenses = require_list(model_lock.get("licenses"), "licenses")
    if not licenses:
        raise ValueError("licenses 不能为空")
    for index, value in enumerate(licenses):
        license_record = require_mapping(value, f"licenses[{index}]")
        require_string(license_record, "component", f"licenses[{index}]")
        require_string(license_record, "spdx", f"licenses[{index}]")
        validate_https_url(
            require_string(license_record, "url", f"licenses[{index}]"),
            f"licenses[{index}].url",
        )
        validate_sha256(
            require_string(license_record, "sha256", f"licenses[{index}]"),
            f"licenses[{index}].sha256",
        )

    dictionary = require_mapping(model_lock.get("dictionary"), "dictionary")
    dictionary_source = require_mapping(dictionary.get("source"), "dictionary.source")
    validate_source_record(dictionary_source, "dictionary.source")
    dictionary_resource = require_string(dictionary, "resourceFile", "dictionary")
    validate_file_name(dictionary_resource, "dictionary.resourceFile")
    if require_integer(dictionary, "byteCount", "dictionary") <= 0:
        raise ValueError("dictionary.byteCount 必须大于零")
    validate_sha256(
        require_string(dictionary, "sha256", "dictionary"),
        "dictionary.sha256",
    )
    line_count = require_integer(dictionary, "lineCount", "dictionary")
    if line_count <= 0:
        raise ValueError("dictionary.lineCount 必须大于零")
    if dictionary["byteCount"] != dictionary_source["byteCount"]:
        raise ValueError("字典源文件和资源文件字节数必须一致")
    if dictionary["sha256"] != dictionary_source["sha256"]:
        raise ValueError("字典源文件和资源文件哈希必须一致")

    models = require_list(model_lock.get("models"), "models")
    validated_models = [
        validate_model_record(model, index) for index, model in enumerate(models)
    ]
    model_ids = [str(model["id"]) for model in validated_models]
    if model_ids != EXPECTED_MODEL_IDS:
        raise ValueError(f"models 必须按固定顺序包含 {EXPECTED_MODEL_IDS}")
    resource_files = [str(model["resourceFile"]) for model in validated_models]
    resource_files.append(dictionary_resource)
    if len(resource_files) != len(set(resource_files)):
        raise ValueError("模型和字典资源文件名不得重复")

    recognition_output = require_mapping(validated_models[2]["output"], "recognition.output")
    recognition_shape = require_list(recognition_output["shape"], "recognition.output.shape")
    if recognition_shape[-1] != line_count + 2:
        raise ValueError("识别输出类别数必须等于字典行数加两个 CTC 特殊类别")
    return model_lock


def load_model_lock(path: pathlib.Path) -> Mapping[str, Any]:
    """从 UTF-8 JSON 文件读取并校验模型锁。"""
    with path.open("r", encoding="utf-8") as input_file:
        return validate_model_lock(json.load(input_file))


def sha256_file(path: pathlib.Path) -> str:
    """流式计算文件 SHA-256，避免一次性读取大模型。"""
    digest = hashlib.sha256()
    with path.open("rb") as input_file:
        while True:
            block = input_file.read(1024 * 1024)
            if not block:
                break
            digest.update(block)
    return digest.hexdigest()


def verify_locked_file(path: pathlib.Path, record: Mapping[str, Any], label: str) -> None:
    """严格核对文件字节数和 SHA-256。"""
    expected_size = int(record["byteCount"])
    actual_size = path.stat().st_size
    if actual_size != expected_size:
        raise ValueError(f"{label} 字节数不一致: expected={expected_size}, actual={actual_size}")
    expected_hash = str(record["sha256"])
    actual_hash = sha256_file(path)
    if actual_hash != expected_hash:
        raise ValueError(f"{label} SHA-256 不一致: expected={expected_hash}, actual={actual_hash}")


def download_locked_file(
    record: Mapping[str, Any],
    cache_directory: pathlib.Path,
    offline: bool,
    label: str,
) -> pathlib.Path:
    """复用已验证缓存，缺失时从官方 HTTPS 地址下载后原子落盘。"""
    cache_directory.mkdir(parents=True, exist_ok=True)
    destination = cache_directory / str(record["fileName"])
    if destination.is_file():
        verify_locked_file(destination, record, label)
        return destination
    if offline:
        raise FileNotFoundError(f"离线模式缺少锁定源文件: {destination}")

    request = urllib.request.Request(
        str(record["url"]),
        headers={"User-Agent": "WinLottery-Desktop-OCR-Converter/1"},
    )
    temporary_path: Optional[pathlib.Path] = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="wb",
            prefix=f".{destination.name}.",
            dir=cache_directory,
            delete=False,
        ) as temporary_file:
            temporary_path = pathlib.Path(temporary_file.name)
            with urllib.request.urlopen(request, timeout=60) as response:
                shutil.copyfileobj(response, temporary_file, length=1024 * 1024)
        verify_locked_file(temporary_path, record, label)
        os.replace(temporary_path, destination)
        temporary_path = None
        return destination
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)


def require_conversion_environment(conversion: Mapping[str, Any]) -> pathlib.Path:
    """确认平台、Python 和关键转换依赖与锁定环境完全一致。"""
    current_platform = f"{platform.system().lower()}-{platform.machine().lower()}"
    if current_platform != conversion["platform"]:
        raise RuntimeError(
            f"转换平台不匹配: expected={conversion['platform']}, actual={current_platform}"
        )
    current_python = platform.python_version()
    if current_python != conversion["pythonVersion"]:
        raise RuntimeError(
            f"Python 版本不匹配: expected={conversion['pythonVersion']}, actual={current_python}"
        )

    expected_packages = {
        "paddlepaddle": conversion["paddlePaddleVersion"],
        "paddle2onnx": conversion["paddle2OnnxVersion"],
        "onnx": conversion["onnxVersion"],
        "packaging": conversion["packagingVersion"],
        "PyYAML": conversion["pyYamlVersion"],
    }
    for package_name, expected_version in expected_packages.items():
        actual_version = importlib.metadata.version(package_name)
        if actual_version != expected_version:
            raise RuntimeError(
                f"{package_name} 版本不匹配: expected={expected_version}, actual={actual_version}"
            )

    converter = pathlib.Path(sys.executable).with_name("paddle2onnx")
    if not converter.is_file() or not os.access(converter, os.X_OK):
        raise RuntimeError(f"当前虚拟环境缺少 paddle2onnx 入口: {converter}")
    return converter


def safe_extract_model(
    archive_path: pathlib.Path,
    expected_directory: str,
    destination: pathlib.Path,
) -> pathlib.Path:
    """拒绝链接和路径穿越后解压单一官方模型目录。"""
    with tarfile.open(archive_path, mode="r:*") as archive:
        members = archive.getmembers()
        if not members:
            raise ValueError(f"模型归档为空: {archive_path.name}")
        for member in members:
            member_path = pathlib.PurePosixPath(member.name)
            if member_path.is_absolute() or ".." in member_path.parts:
                raise ValueError(f"模型归档包含不安全路径: {member.name}")
            if not member_path.parts or member_path.parts[0] != expected_directory:
                raise ValueError(f"模型归档包含未知顶层目录: {member.name}")
            if member.issym() or member.islnk() or member.isdev():
                raise ValueError(f"模型归档包含不允许的链接或设备文件: {member.name}")
        archive.extractall(destination, members=members)

    model_directory = destination / expected_directory
    required_files = ["inference.json", "inference.pdiparams", "inference.yml"]
    for file_name in required_files:
        if not (model_directory / file_name).is_file():
            raise ValueError(f"模型归档缺少 {expected_directory}/{file_name}")
    return model_directory


def verify_character_dictionary(
    model_directory: pathlib.Path,
    dictionary_path: pathlib.Path,
    expected_line_count: int,
) -> None:
    """使用 YAML 解析器逐项核对识别模型内嵌字符表与锁定字典。"""
    import yaml

    with (model_directory / "inference.yml").open("r", encoding="utf-8") as input_file:
        model_config = yaml.safe_load(input_file)
    embedded_characters = model_config["PostProcess"]["character_dict"]
    dictionary_characters = dictionary_path.read_text(encoding="utf-8").splitlines()
    if len(dictionary_characters) != expected_line_count:
        raise ValueError(
            "字典行数不一致: "
            f"expected={expected_line_count}, actual={len(dictionary_characters)}"
        )
    if embedded_characters != dictionary_characters:
        raise ValueError("识别模型内嵌字符表与锁定字典不一致")


def onnx_shape(value_info: Any) -> List[int]:
    """把 ONNX 静态维度转换为整数，动态维度统一转换为 -1。"""
    dimensions: List[int] = []
    for dimension in value_info.type.tensor_type.shape.dim:
        if dimension.HasField("dim_value"):
            dimensions.append(int(dimension.dim_value))
        else:
            dimensions.append(-1)
    return dimensions


def verify_onnx_contract(
    path: pathlib.Path,
    model_record: Mapping[str, Any],
    opset_version: int,
) -> None:
    """执行 ONNX 完整检查、严格形状推导并核对锁定接口。"""
    import onnx

    model = onnx.load(path)
    onnx.checker.check_model(model, full_check=True)
    inferred = onnx.shape_inference.infer_shapes(model, strict_mode=True)
    if model.ir_version != model_record["irVersion"]:
        raise ValueError(f"{model_record['id']} 的 ONNX IR 版本不一致")
    if len(model.graph.node) != model_record["nodeCount"]:
        raise ValueError(f"{model_record['id']} 的 ONNX 节点数不一致")
    imported_opsets = {item.domain: item.version for item in model.opset_import}
    if imported_opsets != {"": opset_version}:
        raise ValueError(
            f"{model_record['id']} 的 opset 不一致: {imported_opsets}"
        )
    if len(inferred.graph.input) != 1 or len(inferred.graph.output) != 1:
        raise ValueError(f"{model_record['id']} 必须只有一个输入和一个输出")

    actual_input = inferred.graph.input[0]
    actual_output = inferred.graph.output[0]
    expected_input = require_mapping(model_record["input"], "model.input")
    expected_output = require_mapping(model_record["output"], "model.output")
    if actual_input.name != expected_input["name"]:
        raise ValueError(f"{model_record['id']} 输入名称不一致")
    if actual_output.name != expected_output["name"]:
        raise ValueError(f"{model_record['id']} 输出名称不一致")
    if onnx_shape(actual_input) != expected_input["shape"]:
        raise ValueError(f"{model_record['id']} 输入形状不一致")
    if onnx_shape(actual_output) != expected_output["shape"]:
        raise ValueError(f"{model_record['id']} 输出形状不一致")
    if actual_input.type.tensor_type.elem_type != onnx.TensorProto.FLOAT:
        raise ValueError(f"{model_record['id']} 输入必须是 FLOAT")
    if actual_output.type.tensor_type.elem_type != onnx.TensorProto.FLOAT:
        raise ValueError(f"{model_record['id']} 输出必须是 FLOAT")


def atomic_copy(source: pathlib.Path, destination: pathlib.Path) -> None:
    """通过同目录临时文件原子替换生成资源。"""
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary_path: Optional[pathlib.Path] = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="wb",
            prefix=f".{destination.name}.",
            dir=destination.parent,
            delete=False,
        ) as temporary_file:
            temporary_path = pathlib.Path(temporary_file.name)
            with source.open("rb") as input_file:
                shutil.copyfileobj(input_file, temporary_file, length=1024 * 1024)
        os.replace(temporary_path, destination)
        temporary_path = None
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)


def prepare_dictionary(
    dictionary: Mapping[str, Any],
    source_path: pathlib.Path,
    resource_directory: pathlib.Path,
) -> pathlib.Path:
    """校验字典行数并把原始 UTF-8 字节写入生成资源目录。"""
    characters = source_path.read_text(encoding="utf-8").splitlines()
    if len(characters) != dictionary["lineCount"]:
        raise ValueError(
            "字典行数不一致: "
            f"expected={dictionary['lineCount']}, actual={len(characters)}"
        )
    destination = resource_directory / str(dictionary["resourceFile"])
    if not destination.is_file() or sha256_file(destination) != dictionary["sha256"]:
        atomic_copy(source_path, destination)
    verify_locked_file(destination, dictionary, "生成字典")
    return destination


def run_conversion(
    converter: pathlib.Path,
    conversion: Mapping[str, Any],
    model_directory: pathlib.Path,
    output_path: pathlib.Path,
) -> None:
    """用全部显式参数调用 Paddle2ONNX，禁止隐式优化和 opset 升级。"""
    command = [
        str(converter),
        "--model_dir",
        str(model_directory),
        "--model_filename",
        "inference.json",
        "--params_filename",
        "inference.pdiparams",
        "--save_file",
        str(output_path),
        "--opset_version",
        str(conversion["opsetVersion"]),
        "--enable_auto_update_opset",
        "False",
        "--enable_onnx_checker",
        "True",
        "--optimize_tool",
        "None",
    ]
    subprocess.run(command, check=True)


def prepare_model(
    model_record: Mapping[str, Any],
    archive_path: pathlib.Path,
    dictionary_path: pathlib.Path,
    dictionary_line_count: int,
    converter: pathlib.Path,
    conversion: Mapping[str, Any],
    work_directory: pathlib.Path,
    resource_directory: pathlib.Path,
) -> pathlib.Path:
    """安全解压、转换、校验并原子写入一份 ONNX 模型。"""
    work_directory.mkdir(parents=True, exist_ok=True)
    source = require_mapping(model_record["source"], "model.source")
    destination = resource_directory / str(model_record["resourceFile"])
    with tempfile.TemporaryDirectory(
        prefix=f"{model_record['id']}-",
        dir=work_directory,
    ) as temporary_directory_text:
        temporary_directory = pathlib.Path(temporary_directory_text)
        model_directory = safe_extract_model(
            archive_path,
            str(source["modelDirectory"]),
            temporary_directory,
        )
        if model_record["id"] == "recognition":
            verify_character_dictionary(
                model_directory,
                dictionary_path,
                dictionary_line_count,
            )

        if destination.is_file():
            verify_locked_file(destination, model_record, f"生成模型 {model_record['id']}")
            verify_onnx_contract(
                destination,
                model_record,
                int(conversion["opsetVersion"]),
            )
            print(f"{model_record['id']}: 已存在并通过锁定校验")
            return destination

        temporary_output = temporary_directory / str(model_record["resourceFile"])
        run_conversion(converter, conversion, model_directory, temporary_output)
        verify_locked_file(temporary_output, model_record, f"转换模型 {model_record['id']}")
        verify_onnx_contract(
            temporary_output,
            model_record,
            int(conversion["opsetVersion"]),
        )
        atomic_copy(temporary_output, destination)
        verify_locked_file(destination, model_record, f"生成模型 {model_record['id']}")
        print(f"{model_record['id']}: 转换完成并通过锁定校验")
        return destination


def parse_arguments(arguments: Optional[Sequence[str]] = None) -> argparse.Namespace:
    """解析锁校验、离线缓存和输出目录参数。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--verify-lock-only",
        action="store_true",
        help="只使用标准库校验模型锁，不安装或加载转换依赖",
    )
    parser.add_argument(
        "--offline",
        action="store_true",
        help="禁止网络下载，要求缓存目录已包含全部官方源文件",
    )
    parser.add_argument(
        "--cache-dir",
        type=pathlib.Path,
        default=DEFAULT_CACHE_DIRECTORY,
        help="官方源归档和字典的缓存目录",
    )
    parser.add_argument(
        "--work-dir",
        type=pathlib.Path,
        default=DEFAULT_WORK_DIRECTORY,
        help="安全解压和转换使用的临时目录",
    )
    parser.add_argument(
        "--output-dir",
        type=pathlib.Path,
        default=DEFAULT_RESOURCE_DIRECTORY,
        help="生成 ONNX 和字典资源的最终目录",
    )
    return parser.parse_args(arguments)


def main(arguments: Optional[Sequence[str]] = None) -> int:
    """执行模型锁校验，或完成全部模型的来源验证和转换。"""
    parsed_arguments = parse_arguments(arguments)
    model_lock = load_model_lock(MODEL_LOCK_PATH)
    if parsed_arguments.verify_lock_only:
        print(f"PP-OCRv5 模型锁校验通过: {model_lock['bundleId']}")
        return 0

    conversion = require_mapping(model_lock["conversion"], "conversion")
    converter = require_conversion_environment(conversion)
    dictionary = require_mapping(model_lock["dictionary"], "dictionary")
    dictionary_source = require_mapping(dictionary["source"], "dictionary.source")
    dictionary_source_path = download_locked_file(
        dictionary_source,
        parsed_arguments.cache_dir,
        parsed_arguments.offline,
        "官方字符字典",
    )
    prepare_dictionary(
        dictionary,
        dictionary_source_path,
        parsed_arguments.output_dir,
    )

    models = require_list(model_lock["models"], "models")
    for model_value in models:
        model_record = require_mapping(model_value, "model")
        source = require_mapping(model_record["source"], "model.source")
        archive_path = download_locked_file(
            source,
            parsed_arguments.cache_dir,
            parsed_arguments.offline,
            f"官方模型 {model_record['id']}",
        )
        prepare_model(
            model_record,
            archive_path,
            dictionary_source_path,
            int(dictionary["lineCount"]),
            converter,
            conversion,
            parsed_arguments.work_dir,
            parsed_arguments.output_dir,
        )

    print(f"PP-OCRv5 资源生成完成: {parsed_arguments.output_dir}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, ValueError, subprocess.CalledProcessError) as error:
        print(f"PP-OCRv5 转换失败: {error}", file=sys.stderr)
        raise SystemExit(2) from error

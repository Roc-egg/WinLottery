#!/usr/bin/env python3
"""对比两份 OCR 回归探针报告，列出逐张差异并汇总分类、金额自洽与耗时。

用法：python3 -I tools/ocr-regression/compare.py 基线.jsonl 候选.jsonl
报告由 OcrRegressionProbeTest 生成，每行一张票图。
"""

import json
import math
import sys

# 参与逐张对比的解析字段。
FIELDS = ["outcome", "lottery", "issue", "periods", "periodsRecognized", "multiplier", "amountFen", "bets", "message"]


def load(path):
    """读取报告，返回按图片名索引的记录。"""
    with open(path, encoding="utf-8") as handle:
        return {record["image"]: record for record in map(json.loads, filter(str.strip, handle))}


# 各彩种单式投注的主区、次区号码数量。
SINGLE_BET_COUNTS = {"SUPER_LOTTO": (5, 2), "DOUBLE_COLOR_BALL": (6, 1)}


def bet_count(lottery, bet):
    """返回一行投注展开后的单式注数，复式按两区组合数相乘。"""
    primary, secondary = bet.removesuffix("追加").split("+")
    primary_count, secondary_count = SINGLE_BET_COUNTS[lottery]
    return math.comb(len(primary.split()), primary_count) * math.comb(len(secondary.split()), secondary_count)


def is_consistent(record):
    """金额等于展开注数、期数、倍数与单注价格之积时视为自洽；字段不全时返回 False。"""
    bets = record.get("bets") or []
    periods, multiplier, amount = record.get("periods"), record.get("multiplier"), record.get("amountFen")
    lottery = record.get("lottery")
    if not bets or None in (periods, multiplier, amount) or lottery not in SINGLE_BET_COUNTS:
        return False
    if any(bet.endswith("?") for bet in bets):
        return False
    per_period = sum((300 if bet.endswith("追加") else 200) * bet_count(lottery, bet) for bet in bets)
    return per_period * periods * multiplier == amount


def percentile(values, ratio):
    """最近秩百分位。"""
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * ratio) - 1)]


def model_names(*reports):
    """从报告字段中收集模型名，兼容不同版本报告包含的模型不同。"""
    names = []
    for records in reports:
        for record in records.values():
            for key in record:
                if key.endswith("Runs") and key[: -len("Runs")] not in names:
                    names.append(key[: -len("Runs")])
    return names


def summarize(records, models):
    """汇总一份报告。"""
    values = list(records.values())
    outcomes = {}
    for record in values:
        outcomes[record["outcome"]] = outcomes.get(record["outcome"], 0) + 1
    times = [record["ocrMs"] for record in values]
    summary = {
        "样本数": len(values),
        "分类": outcomes,
        "金额自洽": sum(map(is_consistent, values)),
        "P50 毫秒": percentile(times, 0.5),
        "P95 毫秒": percentile(times, 0.95),
        "总毫秒": sum(times),
    }
    for model in models:
        runs = sum(record.get(f"{model}Runs", 0) for record in values)
        millis = sum(record.get(f"{model}Ms", 0) for record in values)
        summary[f"{model} 次数/毫秒"] = f"{runs}/{millis}"
    return summary


def main():
    baseline, candidate = load(sys.argv[1]), load(sys.argv[2])
    if baseline.keys() != candidate.keys():
        print("两份报告的图片集合不同：", sorted(baseline.keys() ^ candidate.keys()))
    models = model_names(baseline, candidate)
    left, right = summarize(baseline, models), summarize(candidate, models)
    print(f"{'指标':<28}{'基线':<36}候选")
    for key in left:
        print(f"{key:<28}{str(left[key]):<36}{right[key]}")
    print()
    changed = 0
    for image in sorted(baseline.keys() & candidate.keys()):
        old, new = baseline[image], candidate[image]
        differences = [field for field in FIELDS if old.get(field) != new.get(field)]
        if not differences:
            continue
        changed += 1
        print(f"== {image}（自洽：{is_consistent(old)} → {is_consistent(new)}）")
        for field in differences:
            print(f"   {field}: {old.get(field)}\n   {' ' * len(field)}→ {new.get(field)}")
    print(f"\n有差异的票：{changed} 张")


if __name__ == "__main__":
    main()

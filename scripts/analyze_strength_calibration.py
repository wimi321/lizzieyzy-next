#!/usr/bin/env python3
"""Analyze player-strength calibration rows with repeatable statistics.

This script intentionally keeps the raw SGFs and evaluated JSONL files under
target/. It writes aggregate CSV/Markdown reports that can be regenerated after
larger KataGo runs.
"""

from __future__ import annotations

import argparse
import csv
import glob
import json
import math
import statistics
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Iterable

import evaluate_strength_samples as evaluator
import run_strength_calibration as calibration

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")


RANK_GROUP_ORDER = [
    "low_kyu",
    "mid_kyu",
    "high_kyu",
    "low_dan",
    "mid_dan",
    "high_dan",
]

METRICS = [
    ("first_choice_rate", "一选率", 1.0),
    ("good_move_rate", "好手率", 1.0),
    ("mistake_rate", "失误率", -1.0),
    ("weighted_point_loss", "加权目损", -1.0),
    ("average_score_loss", "平均目损", -1.0),
    ("median_score_loss", "中位目损", -1.0),
    ("average_difficulty", "难度", 1.0),
    ("quality_score", "当前评分", 1.0),
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "inputs",
        nargs="+",
        help="evaluation.jsonl/evaluation_summary_rows.csv paths or recursive glob patterns.",
    )
    parser.add_argument(
        "--out",
        default="target/strength-calibration-analysis",
        help="Output directory for aggregate reports.",
    )
    parser.add_argument("--min-samples", type=int, default=40, help="Skip sides with fewer samples.")
    parser.add_argument(
        "--outlier-z",
        type=float,
        default=3.5,
        help="Robust within-group metric distance used to flag statistical outliers.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    rows = load_rows(args.inputs)
    prepared = prepare_rows(rows, args.min_samples)
    if not prepared:
        print("[error] no usable rows")
        return 1

    mark_outliers(prepared, args.outlier_z)
    write_row_csv(prepared, out_dir / "calibration_rows.csv")

    correlation_rows = metric_correlations(prepared)
    write_csv(
        out_dir / "metric_correlations.csv",
        correlation_rows,
        ["metric", "pearson_with_rank", "spearman_with_rank", "suggested_weight_share"],
    )

    distribution_rows = metric_distributions(prepared)
    write_csv(
        out_dir / "metric_distribution_by_actual_group.csv",
        distribution_rows,
        ["actual_group", "metric", "n", "q10", "q25", "median", "q75", "q90", "mean"],
    )

    exact_rank_rows = metric_distributions_by_exact_rank(prepared)
    write_csv(
        out_dir / "metric_distribution_by_exact_rank.csv",
        exact_rank_rows,
        ["actual_rank", "metric", "n", "q10", "q25", "median", "q75", "q90", "mean"],
    )

    formula_rows = formula_evaluation(prepared)
    write_csv(
        out_dir / "formula_evaluation.csv",
        formula_rows,
        [
            "slice",
            "rows",
            "exact_group",
            "within_one_group",
            "kyu_as_dan",
            "severe_over",
            "severe_under",
            "median_error",
            "mean_absolute_error",
        ],
    )

    write_markdown(
        out_dir / "analysis.md",
        prepared,
        correlation_rows,
        distribution_rows,
        formula_rows,
        args,
    )
    print(f"[analysis] wrote {out_dir / 'analysis.md'}")
    return 0


def load_rows(patterns: Iterable[str]) -> list[dict[str, Any]]:
    paths: list[Path] = []
    for pattern in patterns:
        matches = glob.glob(pattern, recursive=True)
        if matches:
            paths.extend(Path(match) for match in matches)
        else:
            paths.append(Path(pattern))

    rows: list[dict[str, Any]] = []
    seen: set[tuple[str, str]] = set()
    for path in paths:
        if not path.exists() or path.is_dir():
            continue
        loaded = load_csv_rows(path) if path.suffix.lower() == ".csv" else load_jsonl_rows(path)
        for row in loaded:
            key = (str(row.get("path") or row.get("sgf") or ""), str(row.get("side") or ""))
            if key in seen:
                continue
            seen.add(key)
            rows.append(row)
    return rows


def load_jsonl_rows(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open(encoding="utf-8", errors="replace") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                continue
            if isinstance(row, dict):
                rows.append(row)
    return rows


def load_csv_rows(path: Path) -> list[dict[str, Any]]:
    with path.open(encoding="utf-8", errors="replace", newline="") as handle:
        return list(csv.DictReader(handle))


def prepare_rows(rows: list[dict[str, Any]], min_samples: int) -> list[dict[str, Any]]:
    prepared: list[dict[str, Any]] = []
    for row in rows:
        samples = int_number(row.get("samples"))
        if samples < min_samples:
            continue
        rank = calibration.normalize_rank(row.get("fox_rank"))
        if rank is None:
            continue
        try:
            current_score = evaluator.quality_score(
                number(row["weighted_point_loss"]),
                number(row.get("average_score_loss") or row.get("average_point_loss")),
                number(row.get("median_score_loss") or row.get("average_score_loss")),
                number(row["first_choice_rate"]),
                number(row["good_move_rate"]),
                number(row["mistake_rate"]),
                number(row["average_difficulty"]),
            )
            strength = evaluator.strength_band(
                current_score,
                number(row["weighted_point_loss"]),
                number(row.get("median_score_loss") or row.get("average_score_loss")),
                number(row["first_choice_rate"]),
                number(row["good_move_rate"]),
                number(row["mistake_rate"]),
                number(row["average_difficulty"]),
            )
        except (KeyError, TypeError, ValueError):
            continue
        estimated = calibration.estimate_value({"strength": strength})
        if estimated is None:
            continue
        actual_group = rank.group
        estimated_group = calibration.estimate_group(estimated)
        prepared_row = dict(row)
        prepared_row.update(
            {
                "sgf": Path(str(row.get("path") or row.get("sgf") or "")).name,
                "side": row.get("side") or row.get("color") or "",
                "samples": samples,
                "actual_rank_value": rank.value,
                "actual_group": actual_group,
                "quality_score": round(current_score, 3),
                "strength": strength,
                "estimated_rank_value": estimated,
                "estimated_group": estimated_group,
                "numeric_error": estimated - rank.value,
                "group_distance": calibration.group_distance(actual_group, estimated_group),
            }
        )
        for key, _, _ in METRICS:
            if key in prepared_row:
                prepared_row[key] = number(prepared_row[key])
        prepared.append(prepared_row)
    return prepared


def mark_outliers(rows: list[dict[str, Any]], outlier_z: float) -> None:
    by_group: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        by_group[str(row["actual_group"])].append(row)

    for group_rows in by_group.values():
        if len(group_rows) < 20:
            for row in group_rows:
                row["robust_metric_distance"] = 0.0
                row["statistical_outlier"] = False
            continue
        centers: dict[str, float] = {}
        scales: dict[str, float] = {}
        for key, _, direction in METRICS:
            values = [direction * number(row[key]) for row in group_rows if key in row]
            centers[key] = statistics.median(values)
            deviations = [abs(value - centers[key]) for value in values]
            mad = statistics.median(deviations) if deviations else 0.0
            scales[key] = max(1.4826 * mad, 1e-6)
        for row in group_rows:
            z_values = []
            for key, _, direction in METRICS:
                if key not in row:
                    continue
                value = direction * number(row[key])
                z_values.append((value - centers[key]) / scales[key])
            distance = math.sqrt(statistics.fmean(z * z for z in z_values)) if z_values else 0.0
            row["robust_metric_distance"] = round(distance, 3)
            row["statistical_outlier"] = distance >= outlier_z


def metric_correlations(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    actual = [float(row["actual_rank_value"]) for row in rows]
    correlations: list[dict[str, Any]] = []
    strength_sum = 0.0
    raw_rows: list[tuple[str, str, float, float]] = []
    for key, label, direction in METRICS:
        values = [direction * number(row[key]) for row in rows if key in row]
        if len(values) != len(actual):
            continue
        pearson = pearson_correlation(values, actual)
        spearman = spearman_correlation(values, actual)
        if key != "quality_score":
            strength_sum += max(spearman, 0.0)
        raw_rows.append((key, label, pearson, spearman))

    for key, label, pearson, spearman in raw_rows:
        share = 0.0
        if key != "quality_score" and strength_sum > 0.0:
            share = max(spearman, 0.0) / strength_sum
        correlations.append(
            {
                "metric": label,
                "pearson_with_rank": round(pearson, 4),
                "spearman_with_rank": round(spearman, 4),
                "suggested_weight_share": round(share, 4),
            }
        )
    return correlations


def metric_distributions(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for group in RANK_GROUP_ORDER:
        group_rows = [row for row in rows if row["actual_group"] == group]
        result.extend(distribution_rows(group, group_rows, "actual_group"))
    return result


def metric_distributions_by_exact_rank(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for rank in sorted({int(row["actual_rank_value"]) for row in rows}):
        rank_rows = [row for row in rows if int(row["actual_rank_value"]) == rank]
        result.extend(distribution_rows(rank, rank_rows, "actual_rank"))
    return result


def distribution_rows(label: Any, rows: list[dict[str, Any]], label_key: str) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for key, metric_label, _ in METRICS:
        values = sorted(number(row[key]) for row in rows if key in row)
        if not values:
            continue
        result.append(
            {
                label_key: label,
                "metric": metric_label,
                "n": len(values),
                "q10": round(percentile(values, 0.10), 4),
                "q25": round(percentile(values, 0.25), 4),
                "median": round(percentile(values, 0.50), 4),
                "q75": round(percentile(values, 0.75), 4),
                "q90": round(percentile(values, 0.90), 4),
                "mean": round(statistics.fmean(values), 4),
            }
        )
    return result


def formula_evaluation(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    all_rows = evaluate_slice("全部样本", rows)
    regular_rows = [row for row in rows if not row["statistical_outlier"]]
    outlier_rows = [row for row in rows if row["statistical_outlier"]]
    result = [all_rows, evaluate_slice("剔除统计异常", regular_rows)]
    if outlier_rows:
        result.append(evaluate_slice("统计异常候选", outlier_rows))
    return result


def evaluate_slice(name: str, rows: list[dict[str, Any]]) -> dict[str, Any]:
    errors = [number(row["numeric_error"]) for row in rows]
    return {
        "slice": name,
        "rows": len(rows),
        "exact_group": count_pct(sum(1 for row in rows if int(row["group_distance"]) == 0), rows),
        "within_one_group": count_pct(sum(1 for row in rows if int(row["group_distance"]) <= 1), rows),
        "kyu_as_dan": count_pct(
            sum(
                1
                for row in rows
                if number(row["actual_rank_value"]) <= 0
                and number(row["estimated_rank_value"]) >= 1
            ),
            rows,
        ),
        "severe_over": count_pct(sum(1 for error in errors if error >= 4.0), rows),
        "severe_under": count_pct(sum(1 for error in errors if error <= -4.0), rows),
        "median_error": round(statistics.median(errors), 3) if errors else 0.0,
        "mean_absolute_error": round(statistics.fmean(abs(error) for error in errors), 3)
        if errors
        else 0.0,
    }


def write_row_csv(rows: list[dict[str, Any]], path: Path) -> None:
    fieldnames = [
        "sgf",
        "side",
        "player",
        "fox_rank",
        "actual_rank_value",
        "actual_group",
        "strength",
        "estimated_rank_value",
        "estimated_group",
        "numeric_error",
        "samples",
        "max_visits",
        "quality_score",
        "first_choice_rate",
        "good_move_rate",
        "mistake_rate",
        "average_difficulty",
        "weighted_point_loss",
        "average_score_loss",
        "median_score_loss",
        "average_winrate_loss",
        "robust_metric_distance",
        "statistical_outlier",
    ]
    write_csv(path, rows, fieldnames)


def write_csv(path: Path, rows: list[dict[str, Any]], fieldnames: list[str]) -> None:
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def write_markdown(
    path: Path,
    rows: list[dict[str, Any]],
    correlations: list[dict[str, Any]],
    distributions: list[dict[str, Any]],
    formula_rows: list[dict[str, Any]],
    args: argparse.Namespace,
) -> None:
    visits = Counter(str(row.get("max_visits") or "") for row in rows)
    group_counts = Counter(str(row["actual_group"]) for row in rows)
    outliers = sum(1 for row in rows if row["statistical_outlier"])
    lines = [
        "# 棋力评测统计校准报告",
        "",
        "## 数据范围",
        "",
        f"- 有效评测方数：{len(rows)}",
        f"- 最少样本手数：{args.min_samples}",
        f"- 统计异常候选：{outliers}/{len(rows)} ({pct(outliers, len(rows))})",
        f"- robust z 距离阈值：{args.outlier_z}",
        "- 只分析已经由采集/评测脚本筛选出的 19 路、非让子、足够手数样本；原始 SGF 和 JSONL 保留在 `target/`。",
        "",
        "## visits 分布",
        "",
    ]
    for visit, count in sorted(visits.items(), key=lambda item: int(item[0] or 0)):
        lines.append(f"- {visit or '未知'} visits：{count} 方")
    lines.extend(["", "## 实际大段位组分布", ""])
    for group in RANK_GROUP_ORDER:
        lines.append(f"- {group}: {group_counts[group]}")

    lines.extend(["", "## 指标与显示段位相关性", ""])
    lines.append("| 指标 | Pearson | Spearman | 归一化建议权重 |")
    lines.append("| --- | ---: | ---: | ---: |")
    for row in correlations:
        lines.append(
            "| "
            + " | ".join(
                [
                    str(row["metric"]),
                    f"{number(row['pearson_with_rank']):.3f}",
                    f"{number(row['spearman_with_rank']):.3f}",
                    f"{number(row['suggested_weight_share']):.3f}",
                ]
            )
            + " |"
        )

    lines.extend(["", "## 当前公式误差", ""])
    lines.append("| 样本切片 | 方数 | 大组命中 | 大组误差<=1 | 级位评成段位 | 严重高估 | 严重低估 | 中位误差 | MAE |")
    lines.append("| --- | ---: | --- | --- | --- | --- | --- | ---: | ---: |")
    for row in formula_rows:
        lines.append(
            "| "
            + " | ".join(
                [
                    str(row["slice"]),
                    str(row["rows"]),
                    str(row["exact_group"]),
                    str(row["within_one_group"]),
                    str(row["kyu_as_dan"]),
                    str(row["severe_over"]),
                    str(row["severe_under"]),
                    f"{number(row['median_error']):.2f}",
                    f"{number(row['mean_absolute_error']):.2f}",
                ]
            )
            + " |"
        )

    lines.extend(["", "## 各大段位组中位数", ""])
    lines.append("| 组 | n | 一选率 | 好手率 | 失误率 | 加权目损 | 平均目损 | 中位目损 | 当前评分 |")
    lines.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
    by_group_metric = {
        (row["actual_group"], row["metric"]): row for row in distributions if "actual_group" in row
    }
    for group in RANK_GROUP_ORDER:
        first = by_group_metric.get((group, "一选率"))
        if not first:
            continue
        values = [
            group,
            str(first["n"]),
            metric_median(by_group_metric, group, "一选率", percent=True),
            metric_median(by_group_metric, group, "好手率", percent=True),
            metric_median(by_group_metric, group, "失误率", percent=True),
            metric_median(by_group_metric, group, "加权目损"),
            metric_median(by_group_metric, group, "平均目损"),
            metric_median(by_group_metric, group, "中位目损"),
            metric_median(by_group_metric, group, "当前评分"),
        ]
        lines.append("| " + " | ".join(values) + " |")

    lines.extend(
        [
            "",
            "## 结论",
            "",
            "- 本报告只给出统计证据，不针对某个用户、某盘棋或某个段位单独调参。",
            "- 若继续扩大样本，应优先比较 Spearman 相关性、各组分位数间隔、剔除统计异常后的误差表，而不是看单盘成败。",
            "- 当前实现把目损从硬封顶降为综合评分信号；一选率、好手率、失误率和目损同时参与判断，避免某个单项指标支配最终段位。",
        ]
    )
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def metric_median(
    rows: dict[tuple[Any, str], dict[str, Any]], group: str, metric: str, percent: bool = False
) -> str:
    row = rows.get((group, metric))
    if not row:
        return "-"
    value = number(row["median"])
    if percent:
        return f"{value:.0%}"
    return f"{value:.2f}"


def count_pct(count: int, rows: list[dict[str, Any]]) -> str:
    return f"{count}/{len(rows)} ({pct(count, len(rows))})"


def pct(count: int, total: int) -> str:
    if total <= 0:
        return "0.0%"
    return f"{count * 100.0 / total:.1f}%"


def percentile(sorted_values: list[float], fraction: float) -> float:
    if not sorted_values:
        return 0.0
    if len(sorted_values) == 1:
        return sorted_values[0]
    position = fraction * (len(sorted_values) - 1)
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return sorted_values[lower]
    weight = position - lower
    return sorted_values[lower] * (1.0 - weight) + sorted_values[upper] * weight


def pearson_correlation(left: list[float], right: list[float]) -> float:
    if len(left) < 2 or len(left) != len(right):
        return 0.0
    left_mean = statistics.fmean(left)
    right_mean = statistics.fmean(right)
    numerator = sum((x - left_mean) * (y - right_mean) for x, y in zip(left, right))
    left_denominator = math.sqrt(sum((x - left_mean) ** 2 for x in left))
    right_denominator = math.sqrt(sum((y - right_mean) ** 2 for y in right))
    if left_denominator == 0.0 or right_denominator == 0.0:
        return 0.0
    return numerator / (left_denominator * right_denominator)


def ranks(values: list[float]) -> list[float]:
    indexed = sorted(enumerate(values), key=lambda item: item[1])
    result = [0.0] * len(values)
    index = 0
    while index < len(indexed):
        end = index + 1
        while end < len(indexed) and indexed[end][1] == indexed[index][1]:
            end += 1
        average_rank = (index + end + 1) / 2.0
        for inner in range(index, end):
            result[indexed[inner][0]] = average_rank
        index = end
    return result


def spearman_correlation(left: list[float], right: list[float]) -> float:
    if len(left) < 2 or len(left) != len(right):
        return 0.0
    return pearson_correlation(ranks(left), ranks(right))


def number(value: Any) -> float:
    if value is None or value == "":
        return 0.0
    return float(value)


def int_number(value: Any) -> int:
    try:
        return int(float(value))
    except (TypeError, ValueError):
        return 0


if __name__ == "__main__":
    raise SystemExit(main())

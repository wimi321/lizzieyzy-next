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
    ("match_rate", "吻合度", 1.0),
    ("mistake_rate", "失误率", -1.0),
    ("blunder_rate", "大失误率", -1.0),
    ("weighted_point_loss", "加权目损", -1.0),
    ("average_score_loss", "平均目损", -1.0),
    ("average_score_equivalent_loss", "折算平均目损", -1.0),
    ("median_score_loss", "中位目损", -1.0),
    ("p75_score_equivalent_loss", "P75目损", -1.0),
    ("p90_score_equivalent_loss", "P90目损", -1.0),
    ("average_difficulty", "难度", 1.0),
    ("quality_score", "当前评分", 1.0),
]

MISMATCH_METRICS = [
    item
    for item in METRICS
    if item[0]
    not in {
        "quality_score",
        "average_difficulty",
    }
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
    mark_rank_mismatches(prepared)
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

    regression_summary_rows, regression_coefficient_rows = clean_set_regression(prepared)
    write_csv(
        out_dir / "clean_set_regression_summary.csv",
        regression_summary_rows,
        [
            "status",
            "method",
            "rows",
            "games",
            "cv_mean_absolute_error",
            "cv_exact_group",
            "cv_within_one_group",
            "in_sample_mean_absolute_error",
            "in_sample_exact_group",
            "in_sample_within_one_group",
            "note",
        ],
    )
    write_csv(
        out_dir / "clean_set_regression_coefficients.csv",
        regression_coefficient_rows,
        ["feature", "coefficient"],
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
        if calibration.row_has_excluded_player(row):
            continue
        rank = calibration.normalize_rank(row.get("fox_rank"), row.get("player"))
        if rank is None:
            continue
        try:
            current_score = evaluator.quality_score(
                number(row["weighted_point_loss"]),
                number(row.get("average_score_equivalent_loss") or row.get("average_score_loss") or row.get("average_point_loss")),
                number(row.get("median_score_loss") or row.get("average_score_loss")),
                number(row.get("p75_score_equivalent_loss") or row.get("median_score_loss") or row.get("average_score_loss")),
                number(row.get("p90_score_equivalent_loss") or row.get("median_score_loss") or row.get("average_score_loss")),
                number(row["first_choice_rate"]),
                number(row["good_move_rate"]),
                number(row["mistake_rate"]),
                number(row.get("blunder_rate")),
                match_rate(row),
                number(row["average_difficulty"]),
            )
            strength = evaluator.strength_band(
                current_score,
                number(row["weighted_point_loss"]),
                number(row.get("average_score_equivalent_loss") or row.get("average_score_loss") or row.get("average_point_loss")),
                number(row.get("median_score_loss") or row.get("average_score_loss")),
                number(row.get("p90_score_equivalent_loss") or row.get("median_score_loss") or row.get("average_score_loss")),
                number(row["first_choice_rate"]),
                number(row["good_move_rate"]),
                number(row["mistake_rate"]),
                match_rate(row),
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
                "actual_rank_text": rank.text,
                "actual_group": actual_group,
                "quality_score": round(current_score, 3),
                "match_rate": round(match_rate(row), 4),
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


def mark_rank_mismatches(rows: list[dict[str, Any]]) -> None:
    centers: dict[str, dict[str, float]] = {}
    scales: dict[str, float] = {}
    for key, _, direction in MISMATCH_METRICS:
        values = [direction * number(row[key]) for row in rows if key in row]
        if not values:
            continue
        center = statistics.median(values)
        deviations = [abs(value - center) for value in values]
        scales[key] = max(1.4826 * statistics.median(deviations), 1e-6)

    for group in RANK_GROUP_ORDER:
        group_rows = [row for row in rows if row["actual_group"] == group]
        if len(group_rows) < 20:
            continue
        centers[group] = {}
        for key, _, direction in MISMATCH_METRICS:
            values = [direction * number(row[key]) for row in group_rows if key in row]
            if values:
                centers[group][key] = statistics.median(values)

    for row in rows:
        actual_group = str(row["actual_group"])
        distances: dict[str, float] = {}
        for group, group_centers in centers.items():
            z_values = []
            for key, _, direction in MISMATCH_METRICS:
                if key not in row or key not in group_centers or key not in scales:
                    continue
                value = direction * number(row[key])
                z_values.append((value - group_centers[key]) / scales[key])
            if z_values:
                distances[group] = math.sqrt(statistics.fmean(z * z for z in z_values))

        actual_distance = distances.get(actual_group, 0.0)
        nearest_group = actual_group
        nearest_distance = actual_distance
        if distances:
            nearest_group, nearest_distance = min(distances.items(), key=lambda item: item[1])

        group_gap = (
            calibration.group_distance(actual_group, nearest_group)
            if actual_group in RANK_GROUP_ORDER and nearest_group in RANK_GROUP_ORDER
            else 0
        )
        mismatch = (
            nearest_group != actual_group
            and group_gap >= 2
            and actual_distance >= 2.0
            and nearest_distance + 0.75 < actual_distance
        )
        row["nearest_metric_group"] = nearest_group
        row["actual_metric_distance"] = round(actual_distance, 3)
        row["nearest_metric_distance"] = round(nearest_distance, 3)
        row["rank_mismatch_candidate"] = mismatch


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
    clean_rows = [
        row
        for row in rows
        if not row["statistical_outlier"] and not row["rank_mismatch_candidate"]
    ]
    mismatch_rows = [row for row in rows if row["rank_mismatch_candidate"]]
    result = [
        all_rows,
        evaluate_slice("剔除统计异常", regular_rows),
        evaluate_slice("剔除统计异常和段位不匹配", clean_rows),
    ]
    if outlier_rows:
        result.append(evaluate_slice("统计异常候选", outlier_rows))
    if mismatch_rows:
        result.append(evaluate_slice("段位不匹配候选", mismatch_rows))
    return result


def clean_set_regression(rows: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    clean_rows = [
        row
        for row in rows
        if not row["statistical_outlier"] and not row["rank_mismatch_candidate"]
    ]
    if len(clean_rows) < 40:
        return (
            [
                {
                    "status": "skipped",
                    "method": "HuberRegressor",
                    "rows": len(clean_rows),
                    "games": len({row["sgf"] for row in clean_rows}),
                    "note": "清洗集样本不足，跳过回归。",
                }
            ],
            [],
        )
    try:
        import numpy as np
        from sklearn.linear_model import HuberRegressor
        from sklearn.model_selection import GroupKFold
    except ImportError as exc:
        return (
            [
                {
                    "status": "skipped",
                    "method": "HuberRegressor",
                    "rows": len(clean_rows),
                    "games": len({row["sgf"] for row in clean_rows}),
                    "note": f"缺少可选依赖，未运行回归：{exc}",
                }
            ],
            [],
        )

    feature_names = regression_feature_names()
    x = np.array([regression_features(row) for row in clean_rows], dtype=float)
    y = np.array([float(row["actual_rank_value"]) for row in clean_rows], dtype=float)
    groups = np.array([str(row["sgf"]) for row in clean_rows])
    weights = regression_balanced_weights(clean_rows)
    model = HuberRegressor(alpha=0.001, epsilon=1.8, max_iter=2000)
    model.fit(x, y, sample_weight=weights)
    in_sample_predictions = model.predict(x)
    cv_predictions = grouped_regression_predictions(model, x, y, groups, weights)

    summary = {
        "status": "ok",
        "method": "HuberRegressor(alpha=0.001, epsilon=1.8), 只使用清洗集并按大段位均衡加权",
        "rows": len(clean_rows),
        "games": len({row["sgf"] for row in clean_rows}),
        "cv_mean_absolute_error": round(mean_absolute_rank_error(clean_rows, cv_predictions), 3),
        "cv_exact_group": ratio_text(exact_group_count(clean_rows, cv_predictions), clean_rows),
        "cv_within_one_group": ratio_text(within_one_group_count(clean_rows, cv_predictions), clean_rows),
        "in_sample_mean_absolute_error": round(
            mean_absolute_rank_error(clean_rows, in_sample_predictions), 3
        ),
        "in_sample_exact_group": ratio_text(
            exact_group_count(clean_rows, in_sample_predictions), clean_rows
        ),
        "in_sample_within_one_group": ratio_text(
            within_one_group_count(clean_rows, in_sample_predictions), clean_rows
        ),
        "note": "回归只用于寻找指标规律并固化可解释公式；异常候选和段位不匹配候选不参与拟合。",
    }
    coefficients = [{"feature": "intercept", "coefficient": round(float(model.intercept_), 10)}]
    coefficients.extend(
        {"feature": name, "coefficient": round(float(coef), 10)}
        for name, coef in zip(feature_names, model.coef_)
    )
    return [summary], coefficients


def regression_feature_names() -> list[str]:
    return [
        "first_choice_rate",
        "good_move_rate",
        "match_rate",
        "non_mistake_rate",
        "non_blunder_rate",
        "weighted_loss_fit",
        "average_loss_fit",
        "median_loss_fit",
        "p75_loss_fit",
        "p90_loss_fit",
        "difficulty_fit",
        "first_choice_x_difficulty",
        "good_move_x_difficulty",
        "match_x_difficulty",
    ]


def regression_features(row: dict[str, Any]) -> list[float]:
    first_choice = clamp(number(row["first_choice_rate"]), 0.0, 1.0)
    good_move = clamp(number(row["good_move_rate"]), 0.0, 1.0)
    match = clamp(number(row["match_rate"]), 0.0, 1.0)
    non_mistake = 1.0 - clamp(number(row["mistake_rate"]), 0.0, 1.0)
    non_blunder = 1.0 - clamp(number(row.get("blunder_rate")), 0.0, 1.0)
    weighted_loss_fit = 1.0 / (1.0 + clamp(number(row["weighted_point_loss"]), 0.0, 50.0))
    average_loss_fit = 1.0 / (
        1.0 + clamp(number(row.get("average_score_equivalent_loss")), 0.0, 50.0)
    )
    median_loss_fit = 1.0 / (1.0 + clamp(number(row["median_score_loss"]), 0.0, 50.0))
    p75_loss_fit = 1.0 / (
        1.0 + clamp(number(row.get("p75_score_equivalent_loss")), 0.0, 50.0)
    )
    p90_loss_fit = 1.0 / (
        1.0 + clamp(number(row.get("p90_score_equivalent_loss")), 0.0, 80.0)
    )
    difficulty = clamp((number(row["average_difficulty"]) - 25.0) / 35.0, 0.0, 1.0)
    return [
        first_choice,
        good_move,
        match,
        non_mistake,
        non_blunder,
        weighted_loss_fit,
        average_loss_fit,
        median_loss_fit,
        p75_loss_fit,
        p90_loss_fit,
        difficulty,
        first_choice * difficulty,
        good_move * difficulty,
        match * difficulty,
    ]


def regression_balanced_weights(rows: list[dict[str, Any]]) -> list[float]:
    counts = Counter(str(row["actual_group"]) for row in rows)
    group_count = max(1, len(counts))
    return [len(rows) / (group_count * counts[str(row["actual_group"])]) for row in rows]


def grouped_regression_predictions(model: Any, x: Any, y: Any, groups: Any, weights: list[float]) -> list[float]:
    import numpy as np
    from sklearn.base import clone
    from sklearn.model_selection import GroupKFold

    unique_groups = sorted(set(groups))
    if len(unique_groups) < 5:
        fitted = clone(model)
        fitted.fit(x, y, sample_weight=weights)
        return list(map(float, fitted.predict(x)))
    predictions = np.zeros(len(y), dtype=float)
    splitter = GroupKFold(n_splits=5)
    weights_array = np.array(weights, dtype=float)
    for train_index, test_index in splitter.split(x, y, groups):
        fitted = clone(model)
        fitted.fit(x[train_index], y[train_index], sample_weight=weights_array[train_index])
        predictions[test_index] = fitted.predict(x[test_index])
    return list(map(float, predictions))


def mean_absolute_rank_error(rows: list[dict[str, Any]], predictions: Iterable[float]) -> float:
    errors = [
        abs(estimate_value_from_level(level_from_rank_value(prediction)) - number(row["actual_rank_value"]))
        for row, prediction in zip(rows, predictions)
    ]
    return statistics.fmean(errors) if errors else 0.0


def exact_group_count(rows: list[dict[str, Any]], predictions: Iterable[float]) -> int:
    return sum(group_distance_for_prediction(row, prediction) == 0 for row, prediction in zip(rows, predictions))


def within_one_group_count(rows: list[dict[str, Any]], predictions: Iterable[float]) -> int:
    return sum(group_distance_for_prediction(row, prediction) <= 1 for row, prediction in zip(rows, predictions))


def group_distance_for_prediction(row: dict[str, Any], prediction: float) -> int:
    estimated = estimate_value_from_level(level_from_rank_value(prediction))
    return calibration.group_distance(str(row["actual_group"]), calibration.estimate_group(estimated))


def level_from_rank_value(value: float) -> int:
    thresholds = [
        (11.5, 13),
        (10.5, 12),
        (9.5, 11),
        (8.5, 10),
        (7.5, 9),
        (6.5, 8),
        (4.5, 7),
        (2.5, 6),
        (0.0, 5),
        (-2.75, 4),
        (-6.0, 3),
        (-10.5, 2),
        (-15.5, 1),
    ]
    for threshold, level in thresholds:
        if value >= threshold:
            return level
    return 0


def estimate_value_from_level(level: int) -> float:
    values = [-18.0, -13.0, -8.0, -4.0, -1.5, 1.5, 3.5, 5.5, 7.0, 8.0, 9.0, 10.0, 11.0, 12.0]
    return values[max(0, min(len(values) - 1, level))]


def ratio_text(count: int, rows: list[dict[str, Any]]) -> str:
    return count_pct(count, rows)


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
        "actual_rank_text",
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
        "match_rate",
        "mistake_rate",
        "average_difficulty",
        "weighted_point_loss",
        "average_score_loss",
        "average_score_equivalent_loss",
        "median_score_loss",
        "p75_score_equivalent_loss",
        "p90_score_equivalent_loss",
        "average_winrate_loss",
        "bad_move_rate",
        "blunder_rate",
        "robust_metric_distance",
        "statistical_outlier",
        "nearest_metric_group",
        "actual_metric_distance",
        "nearest_metric_distance",
        "rank_mismatch_candidate",
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
    mismatches = sum(1 for row in rows if row["rank_mismatch_candidate"])
    lines = [
        "# 棋力评测统计校准报告",
        "",
        "## 数据范围",
        "",
        f"- 有效评测方数：{len(rows)}",
        f"- 最少样本手数：{args.min_samples}",
        f"- 统计异常候选：{outliers}/{len(rows)} ({pct(outliers, len(rows))})",
        f"- 段位不匹配候选：{mismatches}/{len(rows)} ({pct(mismatches, len(rows))})",
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
    lines.append("| 组 | n | 一选率 | 好手率 | 失误率 | 大失误率 | 加权目损 | 平均目损 | 中位目损 | P90目损 | 当前评分 |")
    lines.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
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
            metric_median(by_group_metric, group, "大失误率", percent=True),
            metric_median(by_group_metric, group, "加权目损"),
            metric_median(by_group_metric, group, "平均目损"),
            metric_median(by_group_metric, group, "中位目损"),
            metric_median(by_group_metric, group, "P90目损"),
            metric_median(by_group_metric, group, "当前评分"),
        ]
        lines.append("| " + " | ".join(values) + " |")

    lines.extend(
        [
            "",
            "## 结论",
            "",
            "- 本报告只给出统计证据，不针对某个用户、某盘棋或某个段位单独调参。",
            "- `段位不匹配候选` 表示该样本的整体指标距离其它大段位组明显更近；它用于后续剔除或降权，不直接改写原始段位。",
            "- 若继续扩大样本，应优先比较 Spearman 相关性、各组分位数间隔、剔除统计异常后的误差表，而不是看单盘成败。",
            "- 当前实现把目损从硬封顶降为综合评分信号；后续应重点比较平均目损、中位目损、P90目损和大失误率，避免含义不清的加权目损单独支配最终段位。",
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


def clamp(value: float, minimum: float, maximum: float) -> float:
    return max(minimum, min(maximum, value))


def match_rate(row: dict[str, Any]) -> float:
    if row.get("match_rate") not in (None, ""):
        return number(row.get("match_rate"))
    return evaluator.match_rate(
        number(row.get("first_choice_rate")),
        number(row.get("good_move_rate")),
        number(row.get("mistake_rate")),
    )


def int_number(value: Any) -> int:
    try:
        return int(float(value))
    except (TypeError, ValueError):
        return 0


if __name__ == "__main__":
    raise SystemExit(main())

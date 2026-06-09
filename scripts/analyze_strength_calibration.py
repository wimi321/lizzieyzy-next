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
    "kyu_level",
    "low_dan",
    "mid_dan",
    "high_dan",
    "professional",
    "top_professional",
    "ai",
]

METRICS = [
    ("first_choice_rate", "一选率", 1.0),
    ("top3_rate", "Top3率", 1.0),
    ("top5_rate", "Top5率", 1.0),
    ("outside_top5_rate", "Top5外率", -1.0),
    ("average_ai_rank", "平均AI排名", -1.0),
    ("excellent_rate", "极佳手率", 1.0),
    ("great_rate", "佳手率", 1.0),
    ("good_move_rate", "好手率", 1.0),
    ("inaccuracy_rate", "缓手率", -1.0),
    ("match_rate", "吻合度", 1.0),
    ("mistake_rate", "失误率", -1.0),
    ("blunder_rate", "大失误率", -1.0),
    ("weighted_point_loss", "加权目损", -1.0),
    ("average_score_loss", "平均目损", -1.0),
    ("average_score_equivalent_loss", "折算平均目损", -1.0),
    ("median_score_loss", "中位目损", -1.0),
    ("p75_score_equivalent_loss", "P75目损", -1.0),
    ("p90_score_equivalent_loss", "P90目损", -1.0),
    ("p95_score_equivalent_loss", "P95目损", -1.0),
    ("max_score_equivalent_loss", "最大目损", -1.0),
    ("loss_stddev", "目损波动", -1.0),
    ("average_difficulty", "难度", 1.0),
    ("opening_weighted_loss", "布局加权目损", -1.0),
    ("middlegame_weighted_loss", "中盘加权目损", -1.0),
    ("endgame_weighted_loss", "官子加权目损", -1.0),
    ("opening_good_move_rate", "布局好手率", 1.0),
    ("middlegame_good_move_rate", "中盘好手率", 1.0),
    ("endgame_good_move_rate", "官子好手率", 1.0),
    ("quality_score", "当前评分", 1.0),
]

CORE_FOUR_METRICS = [
    ("match_rate", "吻合度"),
    ("first_choice_rate", "一选率"),
    ("average_score_equivalent_loss", "折算平均目损"),
    ("average_difficulty", "当前难度"),
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
    parser.add_argument(
        "--metric-reassignment-min-samples",
        type=int,
        default=20,
        help="Minimum rows required to build an exact-rank metric profile.",
    )
    parser.add_argument(
        "--metric-reassignment-min-distance",
        type=float,
        default=2.0,
        help="Minimum distance from the labeled rank profile before metric reassignment is considered.",
    )
    parser.add_argument(
        "--metric-reassignment-margin",
        type=float,
        default=0.75,
        help="Nearest rank must beat the labeled rank by at least this robust distance.",
    )
    parser.add_argument(
        "--metric-reassignment-max-nearest-distance",
        type=float,
        default=3.0,
        help="Maximum robust distance to the nearest rank profile for reassignment.",
    )
    parser.add_argument(
        "--metric-cluster-mode",
        choices=["rank_class", "collection", "auto"],
        default="rank_class",
        help="Default uses the 12 collected rank classes: FOX 1d-9d, pro=10d, top pro=11d, KataGo=12d.",
    )
    parser.add_argument(
        "--metric-cluster-min-k",
        type=int,
        default=4,
        help="Minimum number of unsupervised metric clusters to try.",
    )
    parser.add_argument(
        "--metric-cluster-max-k",
        type=int,
        default=12,
        help="Maximum number of unsupervised metric clusters to try.",
    )
    parser.add_argument(
        "--metric-cluster-min-size",
        type=int,
        default=60,
        help="Minimum anchor rows required for a stable metric cluster.",
    )
    parser.add_argument(
        "--metric-rank-class-min-size",
        type=int,
        default=20,
        help="Minimum anchor rows required for each fixed rank class cluster.",
    )
    parser.add_argument(
        "--metric-cluster-reassignment-min-rank-gap",
        type=float,
        default=1.5,
        help="Minimum gap between original rank and cluster median rank before cluster reassignment.",
    )
    parser.add_argument(
        "--metric-cluster-reassignment-min-original-distance",
        type=float,
        default=2.0,
        help="Minimum distance from the original exact-rank profile before cluster reassignment.",
    )
    parser.add_argument(
        "--metric-cluster-reassignment-margin",
        type=float,
        default=0.10,
        help="Nearest metric cluster centroid must beat the second nearest centroid by this distance.",
    )
    parser.add_argument(
        "--metric-cluster-reassignment-max-radius-ratio",
        type=float,
        default=1.20,
        help="Outlier-to-centroid distance must be no more than this multiple of the cluster p90 radius.",
    )
    parser.add_argument(
        "--enable-twelve-d-style-rule",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Promote rows meeting the Shin-Jinseo 40%% 12d-style line to 12d.",
    )
    parser.add_argument("--twelve-d-style-match", type=float, default=0.7887)
    parser.add_argument("--twelve-d-style-top5", type=float, default=0.9115)
    parser.add_argument("--twelve-d-style-average-ai-rank", type=float, default=2.1842)
    parser.add_argument("--twelve-d-style-average-loss", type=float, default=0.4154)
    parser.add_argument("--twelve-d-style-p90-loss", type=float, default=1.1192)
    parser.add_argument("--twelve-d-style-mistake-rate", type=float, default=0.0190)
    parser.add_argument(
        "--residual-core-four-outlier-z",
        type=float,
        default=3.5,
        help="After metric reassignment, exclude remaining within-class outliers on the 4 core metrics.",
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
    mark_metric_rank_assignments(
        prepared,
        min_samples=args.metric_reassignment_min_samples,
        min_distance=args.metric_reassignment_min_distance,
        margin=args.metric_reassignment_margin,
        max_nearest_distance=args.metric_reassignment_max_nearest_distance,
    )
    cluster_k_rows, cluster_summary_rows = mark_metric_clusters(
        prepared,
        mode=args.metric_cluster_mode,
        min_k=args.metric_cluster_min_k,
        max_k=args.metric_cluster_max_k,
        min_size=args.metric_cluster_min_size,
        rank_class_min_size=args.metric_rank_class_min_size,
        min_rank_gap=args.metric_cluster_reassignment_min_rank_gap,
        min_original_distance=args.metric_cluster_reassignment_min_original_distance,
        reassignment_margin=args.metric_cluster_reassignment_margin,
        max_radius_ratio=args.metric_cluster_reassignment_max_radius_ratio,
    )
    if args.enable_twelve_d_style_rule:
        mark_twelve_d_style_rule(
            prepared,
            match_threshold=args.twelve_d_style_match,
            top5_threshold=args.twelve_d_style_top5,
            average_ai_rank_threshold=args.twelve_d_style_average_ai_rank,
            average_loss_threshold=args.twelve_d_style_average_loss,
            p90_loss_threshold=args.twelve_d_style_p90_loss,
            mistake_rate_threshold=args.twelve_d_style_mistake_rate,
        )
    residual_core_four_summary_rows = mark_residual_core_four_outliers(
        prepared,
        outlier_z=args.residual_core_four_outlier_z,
    )
    write_row_csv(prepared, out_dir / "calibration_rows.csv")

    metric_reassignment_rows = metric_rank_reassignment_rows(prepared)
    twelve_d_style_rows = twelve_d_style_rule_rows(prepared)
    residual_core_four_rows = residual_core_four_outlier_rows(prepared)
    write_csv(
        out_dir / "metric_rank_reassignments.csv",
        metric_reassignment_rows,
        [
            "sgf",
            "sample_source",
            "side",
            "player",
            "fox_rank",
            "actual_rank_text",
            "actual_rank_value",
            "actual_group",
            "metric_reassignment_method",
            "metric_adjusted_rank_value",
            "metric_adjusted_group",
            "metric_cluster_id",
            "metric_cluster_collection_class",
            "metric_cluster_rank_median",
            "metric_cluster_group",
            "metric_cluster_distance",
            "metric_cluster_radius_p90",
            "metric_cluster_distance_ratio",
            "metric_original_rank_distance",
            "metric_nearest_rank_distance",
            "metric_reassignment_margin",
            "metric_reassignment_reason",
            "samples",
            "match_rate",
            "top5_rate",
            "average_ai_rank",
            "average_score_equivalent_loss",
            "p90_score_equivalent_loss",
            "mistake_rate",
        ],
    )
    write_csv(
        out_dir / "twelve_d_style_rule_hits.csv",
        twelve_d_style_rows,
        [
            "sgf",
            "sample_source",
            "side",
            "player",
            "fox_rank",
            "actual_rank_text",
            "actual_rank_value",
            "actual_group",
            "model_rank_value",
            "model_group",
            "twelve_d_style_rule_reassigned",
            "metric_reassignment_method",
            "metric_cluster_collection_class",
            "match_rate",
            "top5_rate",
            "average_ai_rank",
            "average_score_equivalent_loss",
            "p90_score_equivalent_loss",
            "mistake_rate",
            "samples",
            "metric_reassignment_reason",
        ],
    )
    write_csv(
        out_dir / "twelve_d_style_rule_summary.csv",
        twelve_d_style_rule_summary(prepared),
        [
            "actual_rank_value",
            "actual_group",
            "rows",
            "games",
            "reassigned_rows",
            "sources",
            "median_match_rate",
            "median_top5_rate",
            "median_average_ai_rank",
            "median_average_score_equivalent_loss",
            "median_p90_score_equivalent_loss",
            "median_mistake_rate",
        ],
    )
    write_csv(
        out_dir / "metric_reassignment_summary.csv",
        metric_reassignment_summary(prepared),
        [
            "from_rank_value",
            "from_group",
            "to_rank_value",
            "to_group",
            "method",
            "rows",
            "games",
            "sources",
            "median_original_distance",
            "median_nearest_distance",
            "median_margin",
        ],
    )
    write_csv(
        out_dir / "metric_cluster_k_selection.csv",
        cluster_k_rows,
        [
            "k",
            "selected",
            "method",
            "classes",
            "anchor_rows",
            "inertia",
            "centroid_silhouette",
            "davies_bouldin",
            "min_cluster_rows",
            "max_cluster_rows",
            "stable",
        ],
    )
    write_csv(
        out_dir / "metric_cluster_summary.csv",
        cluster_summary_rows,
        [
            "cluster_id",
            "collection_class",
            "rows",
            "anchor_rows",
            "outlier_rows",
            "cluster_rank_median",
            "cluster_rank_q25",
            "cluster_rank_q75",
            "cluster_group",
            "dominant_rank_value",
            "dominant_rank_share",
            "dominant_group",
            "dominant_group_share",
            "sources",
            "radius_median",
            "radius_p90",
            "median_match_rate",
            "median_top5_rate",
            "median_average_ai_rank",
            "median_average_score_equivalent_loss",
            "median_mistake_rate",
        ],
    )
    write_csv(
        out_dir / "residual_core_four_outliers.csv",
        residual_core_four_rows,
        [
            "sgf",
            "sample_source",
            "side",
            "player",
            "fox_rank",
            "actual_rank_text",
            "actual_rank_value",
            "actual_group",
            "model_rank_value",
            "model_group",
            "metric",
            "direction",
            "robust_z",
            "abs_robust_z",
            "value",
            "class_median",
            "samples",
            "match_rate",
            "first_choice_rate",
            "average_score_equivalent_loss",
            "average_difficulty",
            "metric_reassignment_method",
            "metric_reassignment_reason",
        ],
    )
    write_csv(
        out_dir / "residual_core_four_outlier_summary.csv",
        residual_core_four_summary_rows,
        [
            "model_rank_value",
            "rank_class",
            "clean_training_candidate_sides",
            "residual_outlier_sides",
            "residual_outlier_pct",
            "match_rate_outliers",
            "first_choice_rate_outliers",
            "average_loss_outliers",
            "difficulty_outliers",
            "stronger_than_class",
            "weaker_than_class",
            "difficulty_high",
            "difficulty_low",
        ],
    )

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

    source_rows = source_evaluation(prepared)
    write_csv(
        out_dir / "source_evaluation.csv",
        source_rows,
        [
            "slice",
            "rows",
            "games",
            "actual_groups",
            "median_actual_rank",
            "median_quality_score",
            "median_first_choice_rate",
            "median_top5_rate",
            "median_average_ai_rank",
            "median_average_score_loss",
            "median_p90_score_loss",
            "current_exact_group",
            "current_within_one_group",
            "current_mean_absolute_error",
        ],
    )

    (
        regression_summary_rows,
        regression_coefficient_rows,
        regression_prediction_rows,
        regression_slice_rows,
    ) = clean_set_regression(prepared)
    write_csv(
        out_dir / "clean_set_regression_summary.csv",
        regression_summary_rows,
        [
            "status",
            "method",
            "rows",
            "games",
            "metric_reassigned_rows",
            "excluded_statistical_outliers",
            "excluded_rank_mismatches",
            "excluded_residual_core_four_outliers",
            "game_cv_groups",
            "cv_mean_absolute_error",
            "cv_exact_group",
            "cv_within_one_group",
            "player_cv_groups",
            "player_cv_mean_absolute_error",
            "player_cv_exact_group",
            "player_cv_within_one_group",
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
    write_csv(
        out_dir / "clean_set_regression_predictions.csv",
        regression_prediction_rows,
        [
            "sgf",
            "sample_source",
            "side",
            "player",
            "fox_rank",
            "actual_rank_text",
            "actual_rank_value",
            "actual_group",
            "model_rank_value",
            "model_group",
            "metric_rank_reassigned",
            "metric_adjusted_rank_value",
            "metric_adjusted_group",
            "cv_predicted_rank_value",
            "cv_predicted_rank_1dp",
            "cv_predicted_group",
            "cv_numeric_error",
            "cv_abs_error",
            "cv_group_distance",
            "player_cv_predicted_rank_value",
            "player_cv_predicted_rank_1dp",
            "player_cv_predicted_group",
            "player_cv_numeric_error",
            "player_cv_abs_error",
            "player_cv_group_distance",
            "samples",
            "first_choice_rate",
            "top5_rate",
            "average_ai_rank",
            "median_score_loss",
            "p90_score_equivalent_loss",
            "statistical_outlier",
            "rank_mismatch_candidate",
            "residual_core_four_outlier",
        ],
    )
    write_csv(
        out_dir / "clean_set_regression_by_slice.csv",
        regression_slice_rows,
        [
            "validation",
            "slice",
            "rows",
            "mean_absolute_error",
            "median_absolute_error",
            "exact_group",
            "within_one_group",
            "p80_abs_error",
            "p90_abs_error",
        ],
    )
    core_four_metric_rows = core_four_metric_summary(prepared)
    write_csv(
        out_dir / "core_four_metric_summary.csv",
        core_four_metric_rows,
        [
            "label_type",
            "rank_value",
            "rank_class",
            "group",
            "sides",
            "games",
            "mean_match_rate",
            "mean_first_choice_rate",
            "mean_average_score_equivalent_loss",
            "mean_average_difficulty",
            "median_match_rate",
            "median_first_choice_rate",
            "median_average_score_equivalent_loss",
            "median_average_difficulty",
        ],
    )
    core_four_summary_rows, core_four_coefficient_rows = core_four_regression(prepared)
    write_csv(
        out_dir / "core_four_regression_summary.csv",
        core_four_summary_rows,
        [
            "status",
            "method",
            "features",
            "rows",
            "games",
            "metric_reassigned_rows",
            "excluded_residual_core_four_outliers",
            "game_cv_groups",
            "cv_mean_absolute_error",
            "cv_exact_group",
            "cv_within_one_group",
            "player_cv_groups",
            "player_cv_mean_absolute_error",
            "player_cv_exact_group",
            "player_cv_within_one_group",
            "in_sample_mean_absolute_error",
            "in_sample_exact_group",
            "in_sample_within_one_group",
            "note",
        ],
    )
    write_csv(
        out_dir / "core_four_regression_coefficients.csv",
        core_four_coefficient_rows,
        ["feature", "coefficient"],
    )
    display_policy_rows = strength_display_policy(regression_slice_rows)
    write_csv(
        out_dir / "strength_display_policy.csv",
        display_policy_rows,
        [
            "validation",
            "slice",
            "rows",
            "reliability",
            "display_interval_80",
            "display_interval_90",
            "mean_absolute_error",
            "p80_abs_error",
            "p90_abs_error",
            "note",
        ],
    )
    model_config = strength_model_config(
        regression_summary_rows,
        regression_coefficient_rows,
        regression_slice_rows,
        display_policy_rows,
    )
    write_json(out_dir / "strength_model_calibration.json", model_config)

    write_markdown(
        out_dir / "analysis.md",
        prepared,
        correlation_rows,
        distribution_rows,
        formula_rows,
        source_rows,
        regression_summary_rows,
        regression_slice_rows,
        display_policy_rows,
        metric_reassignment_rows,
        cluster_k_rows,
        cluster_summary_rows,
        core_four_metric_rows,
        core_four_summary_rows,
        core_four_coefficient_rows,
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
            if not values:
                continue
            centers[key] = statistics.median(values)
            deviations = [abs(value - centers[key]) for value in values]
            mad = statistics.median(deviations) if deviations else 0.0
            scales[key] = max(1.4826 * mad, 1e-6)
        for row in group_rows:
            z_values = []
            for key, _, direction in METRICS:
                if key not in row or key not in centers or key not in scales:
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


def mark_metric_rank_assignments(
    rows: list[dict[str, Any]],
    *,
    min_samples: int,
    min_distance: float,
    margin: float,
    max_nearest_distance: float,
) -> None:
    centers: dict[int, dict[str, float]] = {}
    scales = metric_scales(rows, MISMATCH_METRICS)
    rows_by_rank: dict[int, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        rows_by_rank[labeled_rank_value(row)].append(row)

    for rank, rank_rows in sorted(rows_by_rank.items()):
        if len(rank_rows) < min_samples:
            continue
        centers[rank] = {}
        for key, _, direction in MISMATCH_METRICS:
            values = [direction * number(row[key]) for row in rank_rows if key in row]
            if values:
                centers[rank][key] = statistics.median(values)

    for row in rows:
        actual_rank = labeled_rank_value(row)
        actual_group = str(row["actual_group"])
        distances = metric_rank_distances(row, centers, scales)
        actual_distance = distances.get(actual_rank, 0.0)
        nearest_rank = actual_rank
        nearest_distance = actual_distance
        if distances:
            nearest_rank, nearest_distance = min(distances.items(), key=lambda item: item[1])
        distance_margin = actual_distance - nearest_distance
        adjusted_rank = actual_rank
        reassigned = (
            nearest_rank != actual_rank
            and actual_distance >= min_distance
            and distance_margin >= margin
            and nearest_distance <= max_nearest_distance
        )
        if reassigned:
            adjusted_rank = nearest_rank
        adjusted_group = rank_group_from_value(adjusted_rank)
        nearest_group = rank_group_from_value(nearest_rank)
        row["metric_nearest_rank_value"] = nearest_rank
        row["metric_nearest_group"] = nearest_group
        row["metric_original_rank_distance"] = round(actual_distance, 3)
        row["metric_nearest_rank_distance"] = round(nearest_distance, 3)
        row["metric_reassignment_margin"] = round(distance_margin, 3)
        row["metric_rank_reassigned"] = reassigned
        row["metric_adjusted_rank_value"] = adjusted_rank
        row["metric_adjusted_group"] = adjusted_group
        row["model_rank_value"] = adjusted_rank
        row["model_group"] = adjusted_group
        row["metric_reassignment_method"] = "exact_rank_profile" if reassigned else ""
        row["metric_reassignment_reason"] = (
            f"指标画像离原标签 {actual_rank}d/{actual_group} 距离 {actual_distance:.2f}，"
            f"更接近 {nearest_rank}d/{nearest_group} 距离 {nearest_distance:.2f}"
        ) if reassigned else ""


def metric_scales(
    rows: list[dict[str, Any]], metrics: list[tuple[str, str, float]]
) -> dict[str, float]:
    scales: dict[str, float] = {}
    for key, _, direction in metrics:
        values = [direction * number(row[key]) for row in rows if key in row]
        if not values:
            continue
        center = statistics.median(values)
        deviations = [abs(value - center) for value in values]
        scales[key] = max(1.4826 * statistics.median(deviations), 1e-6)
    return scales


def metric_rank_distances(
    row: dict[str, Any],
    centers: dict[int, dict[str, float]],
    scales: dict[str, float],
) -> dict[int, float]:
    distances: dict[int, float] = {}
    for rank, rank_centers in centers.items():
        z_values = []
        for key, _, direction in MISMATCH_METRICS:
            if key not in row or key not in rank_centers or key not in scales:
                continue
            value = direction * number(row[key])
            z_values.append((value - rank_centers[key]) / scales[key])
        if z_values:
            distances[rank] = math.sqrt(statistics.fmean(z * z for z in z_values))
    return distances


def mark_metric_clusters(
    rows: list[dict[str, Any]],
    *,
    mode: str,
    min_k: int,
    max_k: int,
    min_size: int,
    rank_class_min_size: int,
    min_rank_gap: float,
    min_original_distance: float,
    reassignment_margin: float,
    max_radius_ratio: float,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    for row in rows:
        row["metric_cluster_id"] = ""
        row["metric_cluster_k"] = ""
        row["metric_cluster_collection_class"] = ""
        row["metric_cluster_rank_median"] = ""
        row["metric_cluster_group"] = ""
        row["metric_cluster_distance"] = ""
        row["metric_cluster_second_distance"] = ""
        row["metric_cluster_margin"] = ""
        row["metric_cluster_radius_p90"] = ""
        row["metric_cluster_distance_ratio"] = ""
        row["metric_cluster_reassigned"] = False

    try:
        import numpy as np
    except ImportError:
        return [], []

    anchor_rows = [
        row
        for row in rows
        if not bool(row["statistical_outlier"]) and not bool(row["rank_mismatch_candidate"])
    ]
    if len(anchor_rows) < max(min_size * 2, min_k):
        return [], []

    centers_by_metric, scales = metric_centers_and_scales(anchor_rows, MISMATCH_METRICS)
    all_x = metric_feature_array(rows, centers_by_metric, scales, np)
    anchor_x = metric_feature_array(anchor_rows, centers_by_metric, scales, np)
    if anchor_x.size == 0:
        return [], []
    if mode == "rank_class":
        return mark_rank_class_metric_clusters(
            rows,
            anchor_rows,
            all_x,
            anchor_x,
            np,
            min_size=rank_class_min_size,
            min_rank_gap=min_rank_gap,
            min_original_distance=min_original_distance,
            reassignment_margin=reassignment_margin,
            max_radius_ratio=max_radius_ratio,
        )
    if mode == "collection":
        return mark_collection_metric_clusters(
            rows,
            anchor_rows,
            all_x,
            anchor_x,
            np,
            min_size=min_size,
            min_rank_gap=min_rank_gap,
            min_original_distance=min_original_distance,
            reassignment_margin=reassignment_margin,
            max_radius_ratio=max_radius_ratio,
        )

    min_k = max(2, min_k)
    max_k = max(min_k, max_k)
    max_k = min(max_k, max(min_k, len(anchor_rows) // max(1, min_size // 2)))

    k_rows: list[dict[str, Any]] = []
    fitted: dict[int, tuple[Any, Any, float]] = {}
    for k in range(min_k, max_k + 1):
        centers, labels, inertia = deterministic_kmeans(anchor_x, k, np)
        sizes = [int(np.sum(labels == cluster_id)) for cluster_id in range(k)]
        silhouette = centroid_silhouette(anchor_x, centers, labels, np)
        db_index = davies_bouldin_index(anchor_x, centers, labels, np)
        stable = min(sizes) >= min_size
        k_rows.append(
            {
                "k": k,
                "selected": False,
                "method": "unsupervised_kmeans",
                "classes": "",
                "anchor_rows": len(anchor_rows),
                "inertia": round(float(inertia), 6),
                "centroid_silhouette": round(float(silhouette), 6),
                "davies_bouldin": round(float(db_index), 6),
                "min_cluster_rows": min(sizes) if sizes else 0,
                "max_cluster_rows": max(sizes) if sizes else 0,
                "stable": stable,
            }
        )
        fitted[k] = (centers, labels, inertia)

    stable_rows = [row for row in k_rows if row["stable"]]
    candidate_rows = stable_rows or k_rows
    selected_row = max(
        candidate_rows,
        key=lambda row: (
            number(row["centroid_silhouette"]),
            -number(row["davies_bouldin"]),
            -abs(int_number(row["k"]) - 7),
        ),
    )
    selected_k = int_number(selected_row["k"])
    for row in k_rows:
        row["selected"] = int_number(row["k"]) == selected_k

    centers, anchor_labels, _ = fitted[selected_k]
    all_labels, all_distances, all_second_distances = assign_to_centroids(all_x, centers, np)
    anchor_labels, anchor_distances, _ = assign_to_centroids(anchor_x, centers, np)
    anchor_labels_for_stats = list(map(int, anchor_labels))
    stats_by_cluster = metric_cluster_stats(
        rows,
        anchor_rows,
        list(map(int, all_labels)),
        anchor_labels_for_stats,
        list(map(float, all_distances)),
        list(map(float, anchor_distances)),
        selected_k,
    )

    for row, label, distance, second_distance in zip(
        rows,
        map(int, all_labels),
        map(float, all_distances),
        map(float, all_second_distances),
    ):
        stats = stats_by_cluster[label]
        radius_p90 = max(number(stats["radius_p90"]), 1e-6)
        distance_ratio = distance / radius_p90
        cluster_margin = second_distance - distance
        cluster_rank = number(stats["cluster_rank_median"])
        cluster_group = str(stats["cluster_group"])
        row["metric_cluster_id"] = label
        row["metric_cluster_k"] = selected_k
        row["metric_cluster_collection_class"] = ""
        row["metric_cluster_rank_median"] = cluster_rank
        row["metric_cluster_group"] = cluster_group
        row["metric_cluster_distance"] = round(distance, 3)
        row["metric_cluster_second_distance"] = round(second_distance, 3)
        row["metric_cluster_margin"] = round(cluster_margin, 3)
        row["metric_cluster_radius_p90"] = round(radius_p90, 3)
        row["metric_cluster_distance_ratio"] = round(distance_ratio, 3)

        candidate = bool(row["statistical_outlier"]) or bool(row["rank_mismatch_candidate"])
        already_reassigned = bool(row.get("metric_rank_reassigned"))
        rank_gap = abs(cluster_rank - number(row["actual_rank_value"]))
        cluster_reassigned = (
            candidate
            and not already_reassigned
            and int_number(stats["anchor_rows"]) >= min_size
            and rank_gap >= min_rank_gap
            and number(row.get("metric_original_rank_distance")) >= min_original_distance
            and cluster_margin >= reassignment_margin
            and distance_ratio <= max_radius_ratio
        )
        row["metric_cluster_reassigned"] = cluster_reassigned
        if cluster_reassigned:
            row["metric_rank_reassigned"] = True
            row["metric_adjusted_rank_value"] = cluster_rank
            row["metric_adjusted_group"] = cluster_group
            row["model_rank_value"] = cluster_rank
            row["model_group"] = cluster_group
            row["metric_reassignment_method"] = "metric_cluster"
            row["metric_reassignment_reason"] = (
                f"无监督指标聚类 k={selected_k}，样本落入 cluster {label}；"
                f"该簇锚点中位段位 {cluster_rank:.1f}/{cluster_group}，"
                f"到簇中心距离 {distance:.2f}，p90 半径 {radius_p90:.2f}"
            )

    return k_rows, list(stats_by_cluster.values())


def mark_rank_class_metric_clusters(
    rows: list[dict[str, Any]],
    anchor_rows: list[dict[str, Any]],
    all_x: Any,
    anchor_x: Any,
    np: Any,
    *,
    min_size: int,
    min_rank_gap: float,
    min_original_distance: float,
    reassignment_margin: float,
    max_radius_ratio: float,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    rank_counts = Counter(labeled_rank_value(row) for row in anchor_rows)
    ranks = [rank for rank in range(1, 13) if rank_counts[rank] >= min_size]
    if len(ranks) < 2:
        return [], []

    anchor_indexes = [
        index
        for index, row in enumerate(anchor_rows)
        if labeled_rank_value(row) in ranks
    ]
    cluster_anchor_rows = [anchor_rows[index] for index in anchor_indexes]
    cluster_anchor_x = anchor_x[anchor_indexes]
    rank_index = {rank: index for index, rank in enumerate(ranks)}
    centers = []
    for rank in ranks:
        indexes = [
            index
            for index, row in enumerate(cluster_anchor_rows)
            if labeled_rank_value(row) == rank
        ]
        centers.append(np.median(cluster_anchor_x[indexes], axis=0))
    centers = np.asarray(centers, dtype=float)
    anchor_labels = np.asarray(
        [rank_index[labeled_rank_value(row)] for row in cluster_anchor_rows],
        dtype=int,
    )
    all_labels, all_distances, all_second_distances = assign_to_centroids(all_x, centers, np)
    anchor_own_distances = np.sqrt(np.sum((cluster_anchor_x - centers[anchor_labels]) ** 2, axis=1))
    nearest_anchor_distances = np.min(squared_distances_to_centers(cluster_anchor_x, centers, np), axis=1)
    sizes = [int(np.sum(anchor_labels == cluster_id)) for cluster_id in range(len(ranks))]
    class_names = [rank_class_name(rank) for rank in ranks]
    stats_by_cluster = metric_cluster_stats(
        rows,
        cluster_anchor_rows,
        list(map(int, all_labels)),
        list(map(int, anchor_labels)),
        list(map(float, all_distances)),
        list(map(float, anchor_own_distances)),
        len(ranks),
        collection_classes=class_names,
    )
    k_rows = [
        {
            "k": len(ranks),
            "selected": True,
            "method": "rank_class_centroid",
            "classes": ";".join(class_names),
            "anchor_rows": len(cluster_anchor_rows),
            "inertia": round(float(np.mean(nearest_anchor_distances)), 6),
            "centroid_silhouette": round(float(centroid_silhouette(anchor_x, centers, anchor_labels, np)), 6),
            "davies_bouldin": round(float(davies_bouldin_index(anchor_x, centers, anchor_labels, np)), 6),
            "min_cluster_rows": min(sizes) if sizes else 0,
            "max_cluster_rows": max(sizes) if sizes else 0,
            "stable": min(sizes) >= min_size if sizes else False,
        }
    ]

    for row, label, distance, second_distance in zip(
        rows,
        map(int, all_labels),
        map(float, all_distances),
        map(float, all_second_distances),
    ):
        target_rank = float(ranks[label])
        target_class = rank_class_name(int(target_rank))
        cluster_group = rank_group_from_value(target_rank)
        stats = stats_by_cluster[label]
        radius_p90 = max(number(stats["radius_p90"]), 1e-6)
        distance_ratio = distance / radius_p90
        cluster_margin = second_distance - distance
        row["metric_cluster_id"] = label
        row["metric_cluster_k"] = len(ranks)
        row["metric_cluster_collection_class"] = target_class
        row["metric_cluster_rank_median"] = stats["cluster_rank_median"]
        row["metric_cluster_group"] = str(stats["cluster_group"])
        row["metric_cluster_distance"] = round(distance, 3)
        row["metric_cluster_second_distance"] = round(second_distance, 3)
        row["metric_cluster_margin"] = round(cluster_margin, 3)
        row["metric_cluster_radius_p90"] = round(radius_p90, 3)
        row["metric_cluster_distance_ratio"] = round(distance_ratio, 3)

        candidate = bool(row["statistical_outlier"]) or bool(row["rank_mismatch_candidate"])
        already_reassigned = bool(row.get("metric_rank_reassigned"))
        rank_gap = abs(target_rank - number(row["actual_rank_value"]))
        cluster_reassigned = (
            candidate
            and not already_reassigned
            and int(target_rank) != labeled_rank_value(row)
            and int_number(stats["anchor_rows"]) >= min_size
            and rank_gap >= min_rank_gap
            and number(row.get("metric_original_rank_distance")) >= min_original_distance
            and cluster_margin >= reassignment_margin
            and distance_ratio <= max_radius_ratio
        )
        row["metric_cluster_reassigned"] = cluster_reassigned
        if cluster_reassigned:
            row["metric_rank_reassigned"] = True
            row["metric_adjusted_rank_value"] = target_rank
            row["metric_adjusted_group"] = cluster_group
            row["model_rank_value"] = target_rank
            row["model_group"] = cluster_group
            row["metric_reassignment_method"] = "rank_class_cluster"
            row["metric_reassignment_reason"] = (
                f"按固定12段位类聚类，样本更接近 {target_class} 类；"
                f"到类中心距离 {distance:.2f}，p90 半径 {radius_p90:.2f}"
            )
    return k_rows, list(stats_by_cluster.values())


def rank_class_name(rank: int) -> str:
    if rank == 10:
        return "10d-pro"
    if rank == 11:
        return "11d-top-pro"
    if rank == 12:
        return "12d-katago-rating"
    return f"{rank}d-fox"


def mark_collection_metric_clusters(
    rows: list[dict[str, Any]],
    anchor_rows: list[dict[str, Any]],
    all_x: Any,
    anchor_x: Any,
    np: Any,
    *,
    min_size: int,
    min_rank_gap: float,
    min_original_distance: float,
    reassignment_margin: float,
    max_radius_ratio: float,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    class_counts = Counter(collection_class(row) for row in anchor_rows)
    classes = sorted(class_name for class_name, count in class_counts.items() if count >= min_size)
    if len(classes) < 2:
        return [], []

    class_index = {class_name: index for index, class_name in enumerate(classes)}
    centers = []
    for class_name in classes:
        indexes = [
            index
            for index, row in enumerate(anchor_rows)
            if collection_class(row) == class_name
        ]
        centers.append(np.median(anchor_x[indexes], axis=0))
    centers = np.asarray(centers, dtype=float)
    anchor_labels = np.asarray([class_index[collection_class(row)] for row in anchor_rows], dtype=int)
    all_labels, all_distances, all_second_distances = assign_to_centroids(all_x, centers, np)
    anchor_own_distances = np.sqrt(
        np.sum((anchor_x - centers[anchor_labels]) ** 2, axis=1)
    )
    nearest_anchor_distances = np.min(squared_distances_to_centers(anchor_x, centers, np), axis=1)
    sizes = [int(np.sum(anchor_labels == cluster_id)) for cluster_id in range(len(classes))]
    stats_by_cluster = metric_cluster_stats(
        rows,
        anchor_rows,
        list(map(int, all_labels)),
        list(map(int, anchor_labels)),
        list(map(float, all_distances)),
        list(map(float, anchor_own_distances)),
        len(classes),
        collection_classes=classes,
    )
    rank_centers = exact_rank_centers(anchor_rows, min_samples=20)
    rank_scales = metric_scales(anchor_rows, MISMATCH_METRICS)
    rank_values_by_class = collection_rank_values(anchor_rows)
    k_rows = [
        {
            "k": len(classes),
            "selected": True,
            "method": "collection_class_centroid",
            "classes": ";".join(classes),
            "anchor_rows": len(anchor_rows),
            "inertia": round(float(np.mean(nearest_anchor_distances)), 6),
            "centroid_silhouette": round(float(centroid_silhouette(anchor_x, centers, anchor_labels, np)), 6),
            "davies_bouldin": round(float(davies_bouldin_index(anchor_x, centers, anchor_labels, np)), 6),
            "min_cluster_rows": min(sizes) if sizes else 0,
            "max_cluster_rows": max(sizes) if sizes else 0,
            "stable": min(sizes) >= min_size if sizes else False,
        }
    ]

    for row, label, distance, second_distance in zip(
        rows,
        map(int, all_labels),
        map(float, all_distances),
        map(float, all_second_distances),
    ):
        target_class = classes[label]
        stats = stats_by_cluster[label]
        radius_p90 = max(number(stats["radius_p90"]), 1e-6)
        distance_ratio = distance / radius_p90
        cluster_margin = second_distance - distance
        cluster_rank = nearest_rank_in_collection_class(
            row,
            target_class,
            rank_values_by_class,
            rank_centers,
            rank_scales,
        )
        cluster_group = rank_group_from_value(cluster_rank)
        row["metric_cluster_id"] = label
        row["metric_cluster_k"] = len(classes)
        row["metric_cluster_collection_class"] = target_class
        row["metric_cluster_rank_median"] = stats["cluster_rank_median"]
        row["metric_cluster_group"] = str(stats["cluster_group"])
        row["metric_cluster_distance"] = round(distance, 3)
        row["metric_cluster_second_distance"] = round(second_distance, 3)
        row["metric_cluster_margin"] = round(cluster_margin, 3)
        row["metric_cluster_radius_p90"] = round(radius_p90, 3)
        row["metric_cluster_distance_ratio"] = round(distance_ratio, 3)

        candidate = bool(row["statistical_outlier"]) or bool(row["rank_mismatch_candidate"])
        already_reassigned = bool(row.get("metric_rank_reassigned"))
        rank_gap = abs(cluster_rank - number(row["actual_rank_value"]))
        cluster_reassigned = (
            candidate
            and not already_reassigned
            and target_class != collection_class(row)
            and int_number(stats["anchor_rows"]) >= min_size
            and rank_gap >= min_rank_gap
            and number(row.get("metric_original_rank_distance")) >= min_original_distance
            and cluster_margin >= reassignment_margin
            and distance_ratio <= max_radius_ratio
        )
        row["metric_cluster_reassigned"] = cluster_reassigned
        if cluster_reassigned:
            row["metric_rank_reassigned"] = True
            row["metric_adjusted_rank_value"] = cluster_rank
            row["metric_adjusted_group"] = cluster_group
            row["model_rank_value"] = cluster_rank
            row["model_group"] = cluster_group
            row["metric_reassignment_method"] = "collection_class_cluster"
            row["metric_reassignment_reason"] = (
                f"按采集类别聚类 k={len(classes)}，样本更接近 {target_class} 类；"
                f"类内最近段位画像 {cluster_rank:g}d/{cluster_group}，"
                f"到类中心距离 {distance:.2f}，p90 半径 {radius_p90:.2f}"
            )
    return k_rows, list(stats_by_cluster.values())


def collection_class(row: dict[str, Any]) -> str:
    return str(row.get("sample_source") or "unknown")


def collection_rank_values(rows: list[dict[str, Any]]) -> dict[str, list[float]]:
    result: dict[str, list[float]] = defaultdict(list)
    for row in rows:
        result[collection_class(row)].append(number(row["actual_rank_value"]))
    return {class_name: sorted(values) for class_name, values in result.items()}


def nearest_rank_in_collection_class(
    row: dict[str, Any],
    target_class: str,
    rank_values_by_class: dict[str, list[float]],
    rank_centers: dict[int, dict[str, float]],
    rank_scales: dict[str, float],
) -> float:
    ranks = sorted({int(round(value)) for value in rank_values_by_class.get(target_class, [])})
    distances = metric_rank_distances(row, rank_centers, rank_scales)
    available = [rank for rank in ranks if rank in distances]
    if available:
        return float(min(available, key=lambda rank: distances[rank]))
    values = rank_values_by_class.get(target_class, [])
    return round_to_half(percentile(values, 0.50)) if values else number(row["actual_rank_value"])


def exact_rank_centers(
    rows: list[dict[str, Any]], *, min_samples: int
) -> dict[int, dict[str, float]]:
    centers: dict[int, dict[str, float]] = {}
    rows_by_rank: dict[int, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        rows_by_rank[labeled_rank_value(row)].append(row)
    for rank, rank_rows in rows_by_rank.items():
        if len(rank_rows) < min_samples:
            continue
        centers[rank] = {}
        for key, _, direction in MISMATCH_METRICS:
            values = [direction * number(row[key]) for row in rank_rows if key in row]
            if values:
                centers[rank][key] = statistics.median(values)
    return centers


def metric_centers_and_scales(
    rows: list[dict[str, Any]],
    metrics: list[tuple[str, str, float]],
) -> tuple[dict[str, float], dict[str, float]]:
    centers: dict[str, float] = {}
    scales: dict[str, float] = {}
    for key, _, direction in metrics:
        values = [direction * number(row[key]) for row in rows if key in row]
        if not values:
            continue
        center = statistics.median(values)
        deviations = [abs(value - center) for value in values]
        centers[key] = center
        scales[key] = max(1.4826 * statistics.median(deviations), 1e-6)
    return centers, scales


def metric_feature_array(
    rows: list[dict[str, Any]],
    centers: dict[str, float],
    scales: dict[str, float],
    np: Any,
) -> Any:
    features: list[list[float]] = []
    for row in rows:
        values = []
        for key, _, direction in MISMATCH_METRICS:
            if key not in centers or key not in scales:
                continue
            value = direction * number(row.get(key))
            values.append(clamp((value - centers[key]) / scales[key], -6.0, 6.0))
        features.append(values)
    return np.asarray(features, dtype=float)


def deterministic_kmeans(x: Any, k: int, np: Any, max_iter: int = 80) -> tuple[Any, Any, float]:
    centers = deterministic_kmeans_initial_centers(x, k, np)
    labels = np.full(x.shape[0], -1, dtype=int)
    for _ in range(max_iter):
        distances = squared_distances_to_centers(x, centers, np)
        next_labels = np.argmin(distances, axis=1)
        next_centers = centers.copy()
        for cluster_id in range(k):
            mask = next_labels == cluster_id
            if np.any(mask):
                next_centers[cluster_id] = np.mean(x[mask], axis=0)
            else:
                farthest = int(np.argmax(np.min(distances, axis=1)))
                next_centers[cluster_id] = x[farthest]
                next_labels[farthest] = cluster_id
        if np.array_equal(labels, next_labels):
            centers = next_centers
            labels = next_labels
            break
        centers = next_centers
        labels = next_labels
    distances = squared_distances_to_centers(x, centers, np)
    nearest = np.min(distances, axis=1)
    return centers, labels, float(np.mean(nearest))


def deterministic_kmeans_initial_centers(x: Any, k: int, np: Any) -> Any:
    median = np.median(x, axis=0)
    first = int(np.argmin(np.sum((x - median) ** 2, axis=1)))
    centers = [x[first]]
    nearest = np.sum((x - centers[0]) ** 2, axis=1)
    for _ in range(1, k):
        next_index = int(np.argmax(nearest))
        centers.append(x[next_index])
        distance = np.sum((x - x[next_index]) ** 2, axis=1)
        nearest = np.minimum(nearest, distance)
    return np.asarray(centers, dtype=float)


def squared_distances_to_centers(x: Any, centers: Any, np: Any) -> Any:
    return np.sum((x[:, None, :] - centers[None, :, :]) ** 2, axis=2)


def assign_to_centroids(x: Any, centers: Any, np: Any) -> tuple[Any, Any, Any]:
    distances = np.sqrt(squared_distances_to_centers(x, centers, np))
    labels = np.argmin(distances, axis=1)
    sorted_distances = np.sort(distances, axis=1)
    nearest = sorted_distances[:, 0]
    second = sorted_distances[:, 1] if centers.shape[0] > 1 else sorted_distances[:, 0]
    return labels, nearest, second


def centroid_silhouette(x: Any, centers: Any, labels: Any, np: Any) -> float:
    distances = np.sqrt(squared_distances_to_centers(x, centers, np))
    own = distances[np.arange(x.shape[0]), labels]
    masked = distances.copy()
    masked[np.arange(x.shape[0]), labels] = np.inf
    other = np.min(masked, axis=1)
    denominator = np.maximum(np.maximum(own, other), 1e-9)
    return float(np.mean((other - own) / denominator))


def davies_bouldin_index(x: Any, centers: Any, labels: Any, np: Any) -> float:
    k = centers.shape[0]
    scatter = np.zeros(k, dtype=float)
    for cluster_id in range(k):
        mask = labels == cluster_id
        if np.any(mask):
            scatter[cluster_id] = float(
                np.mean(np.sqrt(np.sum((x[mask] - centers[cluster_id]) ** 2, axis=1)))
            )
    center_distances = np.sqrt(squared_distances_to_centers(centers, centers, np))
    center_distances[center_distances == 0.0] = np.inf
    ratios = (scatter[:, None] + scatter[None, :]) / center_distances
    ratios[np.eye(k, dtype=bool)] = 0.0
    return float(np.mean(np.max(ratios, axis=1)))


def metric_cluster_stats(
    all_rows: list[dict[str, Any]],
    anchor_rows: list[dict[str, Any]],
    all_labels: list[int],
    anchor_labels: list[int],
    all_distances: list[float],
    anchor_distances: list[float],
    k: int,
    collection_classes: list[str] | None = None,
) -> dict[int, dict[str, Any]]:
    stats: dict[int, dict[str, Any]] = {}
    for cluster_id in range(k):
        cluster_rows = [row for row, label in zip(all_rows, all_labels) if label == cluster_id]
        cluster_anchor_rows = [
            row for row, label in zip(anchor_rows, anchor_labels) if label == cluster_id
        ]
        cluster_anchor_distances = [
            distance for distance, label in zip(anchor_distances, anchor_labels) if label == cluster_id
        ]
        rank_values = sorted(number(row["actual_rank_value"]) for row in cluster_anchor_rows)
        rank_median = round_to_half(percentile(rank_values, 0.50)) if rank_values else 0.0
        group_counts = Counter(str(row["actual_group"]) for row in cluster_anchor_rows)
        rank_counts = Counter(labeled_rank_value(row) for row in cluster_anchor_rows)
        source_counts = Counter(str(row.get("sample_source") or "unknown") for row in cluster_rows)
        dominant_group, dominant_group_count = most_common_or_blank(group_counts)
        dominant_rank, dominant_rank_count = most_common_or_blank(rank_counts)
        stats[cluster_id] = {
            "cluster_id": cluster_id,
            "collection_class": collection_classes[cluster_id] if collection_classes else "",
            "rows": len(cluster_rows),
            "anchor_rows": len(cluster_anchor_rows),
            "outlier_rows": sum(
                1
                for row in cluster_rows
                if bool(row["statistical_outlier"]) or bool(row["rank_mismatch_candidate"])
            ),
            "cluster_rank_median": rank_median,
            "cluster_rank_q25": round(percentile(rank_values, 0.25), 3) if rank_values else 0.0,
            "cluster_rank_q75": round(percentile(rank_values, 0.75), 3) if rank_values else 0.0,
            "cluster_group": rank_group_from_value(rank_median),
            "dominant_rank_value": dominant_rank,
            "dominant_rank_share": round(dominant_rank_count / len(cluster_anchor_rows), 4)
            if cluster_anchor_rows
            else 0.0,
            "dominant_group": dominant_group,
            "dominant_group_share": round(dominant_group_count / len(cluster_anchor_rows), 4)
            if cluster_anchor_rows
            else 0.0,
            "sources": ";".join(
                f"{source}:{source_counts[source]}" for source in sorted(source_counts)
            ),
            "radius_median": round(statistics.median(cluster_anchor_distances), 3)
            if cluster_anchor_distances
            else 0.0,
            "radius_p90": round(percentile(sorted(cluster_anchor_distances), 0.90), 3)
            if cluster_anchor_distances
            else 0.0,
            "median_match_rate": median_number(cluster_rows, "match_rate"),
            "median_top5_rate": median_number(cluster_rows, "top5_rate"),
            "median_average_ai_rank": median_number(cluster_rows, "average_ai_rank"),
            "median_average_score_equivalent_loss": median_number_or(
                cluster_rows,
                ["average_score_equivalent_loss", "average_score_loss", "average_point_loss"],
            ),
            "median_mistake_rate": median_number(cluster_rows, "mistake_rate"),
        }
    return stats


def most_common_or_blank(counter: Counter[Any]) -> tuple[Any, int]:
    if not counter:
        return "", 0
    return counter.most_common(1)[0]


def round_to_half(value: float) -> float:
    return round(value * 2.0) / 2.0


def mark_twelve_d_style_rule(
    rows: list[dict[str, Any]],
    *,
    match_threshold: float,
    top5_threshold: float,
    average_ai_rank_threshold: float,
    average_loss_threshold: float,
    p90_loss_threshold: float,
    mistake_rate_threshold: float,
) -> None:
    required_keys = [
        "match_rate",
        "top5_rate",
        "average_ai_rank",
        "average_score_equivalent_loss",
        "p90_score_equivalent_loss",
        "mistake_rate",
    ]
    for row in rows:
        has_required_metrics = all(row.get(key) not in (None, "") for key in required_keys)
        hit = has_required_metrics and (
            number(row.get("match_rate")) >= match_threshold
            and number(row.get("top5_rate")) >= top5_threshold
            and number(row.get("average_ai_rank")) <= average_ai_rank_threshold
            and number(row.get("average_score_equivalent_loss")) <= average_loss_threshold
            and number(row.get("p90_score_equivalent_loss")) <= p90_loss_threshold
            and number(row.get("mistake_rate")) <= mistake_rate_threshold
        )
        row["twelve_d_style_rule_hit"] = hit
        row["twelve_d_style_rule_reassigned"] = False
        if not hit or number(row["actual_rank_value"]) >= 12.0:
            continue
        row["twelve_d_style_rule_reassigned"] = True
        row["metric_rank_reassigned"] = True
        row["metric_adjusted_rank_value"] = 12.0
        row["metric_adjusted_group"] = "ai"
        row["model_rank_value"] = 12.0
        row["model_group"] = "ai"
        row["metric_reassignment_method"] = "twelve_d_style_rule"
        row["metric_reassignment_reason"] = (
            "满足申真谞40% 12d表现线："
            f"吻合度>={match_threshold:.4f}、Top5>={top5_threshold:.4f}、"
            f"平均AI排名<={average_ai_rank_threshold:.4f}、"
            f"折算平均目损<={average_loss_threshold:.4f}、"
            f"P90目损<={p90_loss_threshold:.4f}、失误率<={mistake_rate_threshold:.4f}"
        )


def twelve_d_style_rule_rows(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [row for row in rows if bool(row.get("twelve_d_style_rule_hit"))]


def mark_residual_core_four_outliers(
    rows: list[dict[str, Any]],
    *,
    outlier_z: float,
    min_class_size: int = 20,
) -> list[dict[str, Any]]:
    for row in rows:
        row["residual_core_four_outlier"] = False
        row["residual_core_four_metric"] = ""
        row["residual_core_four_direction"] = ""
        row["residual_core_four_robust_z"] = ""
        row["residual_core_four_abs_robust_z"] = ""
        row["residual_core_four_value"] = ""
        row["residual_core_four_class_median"] = ""

    candidates = [row for row in rows if is_pre_residual_clean_row(row)]
    grouped: dict[int, list[dict[str, Any]]] = defaultdict(list)
    for row in candidates:
        grouped[int(round(model_rank_value(row)))].append(row)

    summary_rows: list[dict[str, Any]] = []
    for rank in range(1, 13):
        rank_rows = grouped.get(rank, [])
        metric_counts = {
            "match_rate": 0,
            "first_choice_rate": 0,
            "average_score_equivalent_loss": 0,
            "average_difficulty": 0,
        }
        direction_counts = {
            "stronger_than_class": 0,
            "weaker_than_class": 0,
            "difficulty_high": 0,
            "difficulty_low": 0,
        }
        if len(rank_rows) < min_class_size:
            summary_rows.append(
                residual_core_four_summary_row(
                    rank,
                    len(rank_rows),
                    0,
                    metric_counts,
                    direction_counts,
                )
            )
            continue

        stats: dict[str, tuple[float, float]] = {}
        for key, _ in CORE_FOUR_METRICS:
            values = [number(row[key]) for row in rank_rows if key in row]
            if not values:
                continue
            median = statistics.median(values)
            deviations = [abs(value - median) for value in values]
            scale = max(1.4826 * statistics.median(deviations), 1e-6)
            stats[key] = (median, scale)

        outlier_count = 0
        for row in rank_rows:
            z_values: list[tuple[float, float, str, str, float, float]] = []
            for key, label in CORE_FOUR_METRICS:
                if key not in row or key not in stats:
                    continue
                median, scale = stats[key]
                value = number(row[key])
                z = (value - median) / scale
                z_values.append((abs(z), z, key, label, value, median))
            if not z_values:
                continue
            abs_z, z, key, label, value, median = max(z_values, key=lambda item: item[0])
            if abs_z < outlier_z:
                continue
            direction = residual_core_four_direction(key, z)
            row["residual_core_four_outlier"] = True
            row["residual_core_four_metric"] = label
            row["residual_core_four_direction"] = direction
            row["residual_core_four_robust_z"] = round(z, 3)
            row["residual_core_four_abs_robust_z"] = round(abs_z, 3)
            row["residual_core_four_value"] = round(value, 4)
            row["residual_core_four_class_median"] = round(median, 4)
            metric_counts[key] += 1
            direction_counts[direction] += 1
            outlier_count += 1

        summary_rows.append(
            residual_core_four_summary_row(
                rank,
                len(rank_rows),
                outlier_count,
                metric_counts,
                direction_counts,
            )
        )
    return summary_rows


def is_pre_residual_clean_row(row: dict[str, Any]) -> bool:
    if bool(row.get("metric_rank_reassigned")):
        return True
    return not bool(row["statistical_outlier"]) and not bool(row["rank_mismatch_candidate"])


def residual_core_four_direction(key: str, z: float) -> str:
    if key in {"match_rate", "first_choice_rate"}:
        return "stronger_than_class" if z > 0 else "weaker_than_class"
    if key == "average_score_equivalent_loss":
        return "weaker_than_class" if z > 0 else "stronger_than_class"
    return "difficulty_high" if z > 0 else "difficulty_low"


def residual_core_four_summary_row(
    rank: int,
    candidate_count: int,
    outlier_count: int,
    metric_counts: dict[str, int],
    direction_counts: dict[str, int],
) -> dict[str, Any]:
    return {
        "model_rank_value": rank,
        "rank_class": rank_class_name(rank),
        "clean_training_candidate_sides": candidate_count,
        "residual_outlier_sides": outlier_count,
        "residual_outlier_pct": round(100.0 * outlier_count / candidate_count, 2)
        if candidate_count
        else 0.0,
        "match_rate_outliers": metric_counts.get("match_rate", 0),
        "first_choice_rate_outliers": metric_counts.get("first_choice_rate", 0),
        "average_loss_outliers": metric_counts.get("average_score_equivalent_loss", 0),
        "difficulty_outliers": metric_counts.get("average_difficulty", 0),
        "stronger_than_class": direction_counts.get("stronger_than_class", 0),
        "weaker_than_class": direction_counts.get("weaker_than_class", 0),
        "difficulty_high": direction_counts.get("difficulty_high", 0),
        "difficulty_low": direction_counts.get("difficulty_low", 0),
    }


def residual_core_four_outlier_rows(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for row in rows:
        if not bool(row.get("residual_core_four_outlier")):
            continue
        result.append(
            {
                "sgf": row.get("sgf", ""),
                "sample_source": row.get("sample_source", ""),
                "side": row.get("side", ""),
                "player": row.get("player", ""),
                "fox_rank": row.get("fox_rank", ""),
                "actual_rank_text": row.get("actual_rank_text", ""),
                "actual_rank_value": row.get("actual_rank_value", ""),
                "actual_group": row.get("actual_group", ""),
                "model_rank_value": model_rank_value(row),
                "model_group": model_group(row),
                "metric": row.get("residual_core_four_metric", ""),
                "direction": row.get("residual_core_four_direction", ""),
                "robust_z": row.get("residual_core_four_robust_z", ""),
                "abs_robust_z": row.get("residual_core_four_abs_robust_z", ""),
                "value": row.get("residual_core_four_value", ""),
                "class_median": row.get("residual_core_four_class_median", ""),
                "samples": row.get("samples", ""),
                "match_rate": row.get("match_rate", ""),
                "first_choice_rate": row.get("first_choice_rate", ""),
                "average_score_equivalent_loss": row.get(
                    "average_score_equivalent_loss",
                    "",
                ),
                "average_difficulty": row.get("average_difficulty", ""),
                "metric_reassignment_method": row.get("metric_reassignment_method", ""),
                "metric_reassignment_reason": row.get("metric_reassignment_reason", ""),
            }
        )
    return sorted(
        result,
        key=lambda row: (
            int(round(number(row["model_rank_value"]))),
            -number(row["abs_robust_z"]),
        ),
    )


def twelve_d_style_rule_summary(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    grouped: dict[int, list[dict[str, Any]]] = defaultdict(list)
    for row in twelve_d_style_rule_rows(rows):
        grouped[labeled_rank_value(row)].append(row)

    result: list[dict[str, Any]] = []
    for rank, rank_rows in sorted(grouped.items()):
        sources = Counter(str(row.get("sample_source") or "unknown") for row in rank_rows)
        result.append(
            {
                "actual_rank_value": rank,
                "actual_group": rank_group_from_value(rank),
                "rows": len(rank_rows),
                "games": len({row["sgf"] for row in rank_rows}),
                "reassigned_rows": sum(
                    1 for row in rank_rows if bool(row.get("twelve_d_style_rule_reassigned"))
                ),
                "sources": ";".join(f"{source}:{sources[source]}" for source in sorted(sources)),
                "median_match_rate": median_number(rank_rows, "match_rate"),
                "median_top5_rate": median_number(rank_rows, "top5_rate"),
                "median_average_ai_rank": median_number(rank_rows, "average_ai_rank"),
                "median_average_score_equivalent_loss": median_number(
                    rank_rows,
                    "average_score_equivalent_loss",
                ),
                "median_p90_score_equivalent_loss": median_number(
                    rank_rows,
                    "p90_score_equivalent_loss",
                ),
                "median_mistake_rate": median_number(rank_rows, "mistake_rate"),
            }
        )
    return result


def metric_rank_reassignment_rows(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [row for row in rows if bool(row.get("metric_rank_reassigned"))]


def metric_reassignment_summary(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    reassigned = metric_rank_reassignment_rows(rows)
    grouped: dict[tuple[int, str, float, str, str], list[dict[str, Any]]] = defaultdict(list)
    for row in reassigned:
        key = (
            labeled_rank_value(row),
            str(row["actual_group"]),
            number(row.get("metric_adjusted_rank_value")),
            str(row.get("metric_adjusted_group") or ""),
            str(row.get("metric_reassignment_method") or ""),
        )
        grouped[key].append(row)

    result: list[dict[str, Any]] = []
    for (from_rank, from_group, to_rank, to_group, method), group_rows in sorted(grouped.items()):
        sources = Counter(str(row.get("sample_source") or "unknown") for row in group_rows)
        result.append(
            {
                "from_rank_value": from_rank,
                "from_group": from_group,
                "to_rank_value": to_rank,
                "to_group": to_group,
                "method": method,
                "rows": len(group_rows),
                "games": len({row["sgf"] for row in group_rows}),
                "sources": ";".join(f"{source}:{sources[source]}" for source in sorted(sources)),
                "median_original_distance": median_number(group_rows, "metric_original_rank_distance"),
                "median_nearest_distance": median_number(group_rows, "metric_nearest_rank_distance"),
                "median_margin": median_number(group_rows, "metric_reassignment_margin"),
            }
        )
    return result


def labeled_rank_value(row: dict[str, Any]) -> int:
    return int(round(number(row["actual_rank_value"])))


def model_rank_value(row: dict[str, Any]) -> float:
    return number(row.get("model_rank_value", row["actual_rank_value"]))


def model_group(row: dict[str, Any]) -> str:
    return str(row.get("model_group") or row.get("actual_group") or "")


def rank_group_from_value(value: float) -> str:
    return calibration.estimate_group(float(value))


def is_model_clean_row(row: dict[str, Any]) -> bool:
    if bool(row.get("residual_core_four_outlier")):
        return False
    if bool(row.get("metric_rank_reassigned")):
        return True
    return not bool(row["statistical_outlier"]) and not bool(row["rank_mismatch_candidate"])


def metric_correlations(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    actual = [model_rank_value(row) for row in rows]
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
    clean_rows = [row for row in rows if is_model_clean_row(row)]
    reassigned_rows = metric_rank_reassignment_rows(rows)
    mismatch_rows = [row for row in rows if row["rank_mismatch_candidate"]]
    result = [
        all_rows,
        evaluate_slice("剔除统计异常", regular_rows),
        evaluate_slice("指标重归属后清洗集", clean_rows),
    ]
    if reassigned_rows:
        result.append(evaluate_slice("指标重归属样本", reassigned_rows))
    if outlier_rows:
        result.append(evaluate_slice("统计异常候选", outlier_rows))
    if mismatch_rows:
        result.append(evaluate_slice("段位不匹配候选", mismatch_rows))
    return result


def source_evaluation(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    result.append(source_slice_row("all", rows))
    for source in sorted({str(row.get("sample_source") or "unknown") for row in rows}):
        source_rows = [row for row in rows if str(row.get("sample_source") or "unknown") == source]
        result.append(source_slice_row(f"source:{source}", source_rows))
    for group in RANK_GROUP_ORDER:
        group_rows = [row for row in rows if str(row["actual_group"]) == group]
        if group_rows:
            result.append(source_slice_row(f"group:{group}", group_rows))
    for source in sorted({str(row.get("sample_source") or "unknown") for row in rows}):
        for group in RANK_GROUP_ORDER:
            slice_rows = [
                row
                for row in rows
                if str(row.get("sample_source") or "unknown") == source
                and str(row["actual_group"]) == group
            ]
            if slice_rows:
                result.append(source_slice_row(f"source:{source}/group:{group}", slice_rows))
    return result


def source_slice_row(name: str, rows: list[dict[str, Any]]) -> dict[str, Any]:
    groups = Counter(str(row["actual_group"]) for row in rows)
    formula = evaluate_slice(name, rows)
    return {
        "slice": name,
        "rows": len(rows),
        "games": len({row["sgf"] for row in rows}),
        "actual_groups": ";".join(f"{group}:{groups[group]}" for group in RANK_GROUP_ORDER if groups[group]),
        "median_actual_rank": median_number(rows, "actual_rank_value"),
        "median_quality_score": median_number(rows, "quality_score"),
        "median_first_choice_rate": median_number(rows, "first_choice_rate"),
        "median_top5_rate": median_number(rows, "top5_rate"),
        "median_average_ai_rank": median_number(rows, "average_ai_rank"),
        "median_average_score_loss": median_number_or(
            rows,
            ["average_score_equivalent_loss", "average_score_loss", "average_point_loss"],
        ),
        "median_p90_score_loss": median_number_or(
            rows,
            ["p90_score_equivalent_loss", "median_score_loss", "average_score_loss"],
        ),
        "current_exact_group": formula["exact_group"],
        "current_within_one_group": formula["within_one_group"],
        "current_mean_absolute_error": formula["mean_absolute_error"],
    }


def median_number(rows: list[dict[str, Any]], key: str) -> float:
    values = sorted(number(row[key]) for row in rows if key in row)
    return round(statistics.median(values), 4) if values else 0.0


def median_number_or(rows: list[dict[str, Any]], keys: list[str]) -> float:
    values: list[float] = []
    for row in rows:
        for key in keys:
            value = row.get(key)
            if value not in (None, ""):
                values.append(number(value))
                break
    return round(statistics.median(sorted(values)), 4) if values else 0.0


def clean_set_regression(
    rows: list[dict[str, Any]]
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
    clean_rows = [row for row in rows if is_model_clean_row(row)]
    reassigned_rows = metric_rank_reassignment_rows(rows)
    excluded_statistical_outliers = [
        row
        for row in rows
        if row["statistical_outlier"] and not bool(row.get("metric_rank_reassigned"))
    ]
    excluded_rank_mismatches = [
        row
        for row in rows
        if row["rank_mismatch_candidate"] and not bool(row.get("metric_rank_reassigned"))
    ]
    excluded_residual_core_four_outliers = [
        row for row in rows if bool(row.get("residual_core_four_outlier"))
    ]
    if len(clean_rows) < 40:
        return (
            [
                {
                    "status": "skipped",
                    "method": "HuberRegressor",
                    "rows": len(clean_rows),
                    "games": len({row["sgf"] for row in clean_rows}),
                    "metric_reassigned_rows": len(reassigned_rows),
                    "excluded_statistical_outliers": len(excluded_statistical_outliers),
                    "excluded_rank_mismatches": len(excluded_rank_mismatches),
                    "excluded_residual_core_four_outliers": len(
                        excluded_residual_core_four_outliers
                    ),
                    "note": "清洗集样本不足，跳过回归。",
                }
            ],
            [],
            [],
            [],
        )
    try:
        import numpy as np
    except ImportError as exc:
        return (
            [
                {
                    "status": "skipped",
                    "method": "robust linear regression",
                    "rows": len(clean_rows),
                    "games": len({row["sgf"] for row in clean_rows}),
                    "metric_reassigned_rows": len(reassigned_rows),
                    "excluded_statistical_outliers": len(excluded_statistical_outliers),
                    "excluded_rank_mismatches": len(excluded_rank_mismatches),
                    "excluded_residual_core_four_outliers": len(
                        excluded_residual_core_four_outliers
                    ),
                    "note": f"缺少可选依赖，未运行回归：{exc}",
                }
            ],
            [],
            [],
            [],
        )

    feature_names = regression_feature_names()
    x = np.array([regression_features(row) for row in clean_rows], dtype=float)
    y = np.array([model_rank_value(row) for row in clean_rows], dtype=float)
    game_groups = np.array([str(row["sgf"]) for row in clean_rows])
    player_groups = np.array([player_group_key(row) for row in clean_rows])
    weights = regression_balanced_weights(clean_rows)
    model, method_name = regression_model()
    model.fit(x, y, sample_weight=weights)
    in_sample_predictions = model.predict(x)
    game_cv_predictions = grouped_regression_predictions(model, x, y, game_groups, weights)
    player_cv_predictions = grouped_regression_predictions(model, x, y, player_groups, weights)

    summary = {
        "status": "ok",
        "method": f"{method_name}, 只使用清洗集并按大段位均衡加权",
        "rows": len(clean_rows),
        "games": len({row["sgf"] for row in clean_rows}),
        "metric_reassigned_rows": len(reassigned_rows),
        "excluded_statistical_outliers": len(excluded_statistical_outliers),
        "excluded_rank_mismatches": len(excluded_rank_mismatches),
        "excluded_residual_core_four_outliers": len(excluded_residual_core_four_outliers),
        "game_cv_groups": len(set(game_groups)),
        "cv_mean_absolute_error": round(mean_absolute_rank_error(clean_rows, game_cv_predictions), 3),
        "cv_exact_group": ratio_text(exact_group_count(clean_rows, game_cv_predictions), clean_rows),
        "cv_within_one_group": ratio_text(within_one_group_count(clean_rows, game_cv_predictions), clean_rows),
        "player_cv_groups": len(set(player_groups)),
        "player_cv_mean_absolute_error": round(mean_absolute_rank_error(clean_rows, player_cv_predictions), 3),
        "player_cv_exact_group": ratio_text(
            exact_group_count(clean_rows, player_cv_predictions), clean_rows
        ),
        "player_cv_within_one_group": ratio_text(
            within_one_group_count(clean_rows, player_cv_predictions), clean_rows
        ),
        "in_sample_mean_absolute_error": round(
            mean_absolute_rank_error(clean_rows, in_sample_predictions), 3
        ),
        "in_sample_exact_group": ratio_text(
            exact_group_count(clean_rows, in_sample_predictions), clean_rows
        ),
        "in_sample_within_one_group": ratio_text(
            within_one_group_count(clean_rows, in_sample_predictions), clean_rows
        ),
        "note": "先按精确段位指标画像、固定12段位类中心和12d强指标规则重归属明显错位样本；再剔除最终类别内4指标残留离群样本；仍不稳定的统计异常/段位不匹配样本不参与拟合。棋局CV防止同一盘黑白互泄；棋手CV防止同一棋手多盘泄漏，最终方法验收优先看棋手CV。",
    }
    coefficients = [{"feature": "intercept", "coefficient": round(float(model.intercept_), 10)}]
    coefficients.extend(
        {"feature": name, "coefficient": round(float(coef), 10)}
        for name, coef in zip(feature_names, model.coef_)
    )
    prediction_rows = regression_prediction_rows(clean_rows, game_cv_predictions, player_cv_predictions)
    slice_rows = regression_slice_evaluation(clean_rows, game_cv_predictions, "game_cv")
    slice_rows.extend(regression_slice_evaluation(clean_rows, player_cv_predictions, "player_cv"))
    return [summary], coefficients, prediction_rows, slice_rows


def core_four_metric_summary(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for label_type in ["actual_label", "clean_label", "clean_training_label"]:
        for rank in range(1, 13):
            if label_type == "actual_label":
                rank_rows = [row for row in rows if labeled_rank_value(row) == rank]
            elif label_type == "clean_training_label":
                rank_rows = [
                    row
                    for row in rows
                    if is_model_clean_row(row) and int(round(model_rank_value(row))) == rank
                ]
            else:
                rank_rows = [row for row in rows if int(round(model_rank_value(row))) == rank]
            if not rank_rows:
                continue
            result.append(core_four_metric_summary_row(label_type, rank, rank_rows))
    return result


def core_four_metric_summary_row(
    label_type: str, rank: int, rows: list[dict[str, Any]]
) -> dict[str, Any]:
    row: dict[str, Any] = {
        "label_type": label_type,
        "rank_value": rank,
        "rank_class": rank_class_name(rank),
        "group": rank_group_from_value(rank),
        "sides": len(rows),
        "games": len({item["sgf"] for item in rows}),
    }
    for key, _ in CORE_FOUR_METRICS:
        values = sorted(number(item[key]) for item in rows if item.get(key) not in (None, ""))
        row[f"mean_{key}"] = round(statistics.fmean(values), 4) if values else 0.0
        row[f"median_{key}"] = round(statistics.median(values), 4) if values else 0.0
    return row


def core_four_regression(
    rows: list[dict[str, Any]]
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    clean_rows = [row for row in rows if is_model_clean_row(row)]
    reassigned_rows = metric_rank_reassignment_rows(rows)
    residual_outliers = [row for row in rows if bool(row.get("residual_core_four_outlier"))]
    feature_labels = "吻合度,一选率,折算平均目损,当前难度"
    if len(clean_rows) < 40:
        return (
            [
                {
                    "status": "skipped",
                    "method": "core-four robust regression",
                    "features": feature_labels,
                    "rows": len(clean_rows),
                    "games": len({row["sgf"] for row in clean_rows}),
                    "metric_reassigned_rows": len(reassigned_rows),
                    "excluded_residual_core_four_outliers": len(residual_outliers),
                    "note": "清洗集样本不足，跳过 4 指标回归。",
                }
            ],
            [],
        )
    try:
        import numpy as np
    except ImportError as exc:
        return (
            [
                {
                    "status": "skipped",
                    "method": "core-four robust regression",
                    "features": feature_labels,
                    "rows": len(clean_rows),
                    "games": len({row["sgf"] for row in clean_rows}),
                    "metric_reassigned_rows": len(reassigned_rows),
                    "excluded_residual_core_four_outliers": len(residual_outliers),
                    "note": f"缺少可选依赖，未运行 4 指标回归：{exc}",
                }
            ],
            [],
        )

    feature_names = core_four_regression_feature_names()
    x = np.array([core_four_regression_features(row) for row in clean_rows], dtype=float)
    y = np.array([model_rank_value(row) for row in clean_rows], dtype=float)
    game_groups = np.array([str(row["sgf"]) for row in clean_rows])
    player_groups = np.array([player_group_key(row) for row in clean_rows])
    weights = regression_balanced_weights(clean_rows)
    model, method_name = regression_model()
    model.fit(x, y, sample_weight=weights)
    in_sample_predictions = model.predict(x)
    game_cv_predictions = grouped_regression_predictions(model, x, y, game_groups, weights)
    player_cv_predictions = grouped_regression_predictions(model, x, y, player_groups, weights)
    summary = {
        "status": "ok",
        "method": f"{method_name}, 4指标清洗集回归",
        "features": feature_labels,
        "rows": len(clean_rows),
        "games": len({row["sgf"] for row in clean_rows}),
        "metric_reassigned_rows": len(reassigned_rows),
        "excluded_residual_core_four_outliers": len(residual_outliers),
        "game_cv_groups": len(set(game_groups)),
        "cv_mean_absolute_error": round(mean_absolute_rank_error(clean_rows, game_cv_predictions), 3),
        "cv_exact_group": ratio_text(exact_group_count(clean_rows, game_cv_predictions), clean_rows),
        "cv_within_one_group": ratio_text(within_one_group_count(clean_rows, game_cv_predictions), clean_rows),
        "player_cv_groups": len(set(player_groups)),
        "player_cv_mean_absolute_error": round(mean_absolute_rank_error(clean_rows, player_cv_predictions), 3),
        "player_cv_exact_group": ratio_text(
            exact_group_count(clean_rows, player_cv_predictions), clean_rows
        ),
        "player_cv_within_one_group": ratio_text(
            within_one_group_count(clean_rows, player_cv_predictions), clean_rows
        ),
        "in_sample_mean_absolute_error": round(
            mean_absolute_rank_error(clean_rows, in_sample_predictions), 3
        ),
        "in_sample_exact_group": ratio_text(
            exact_group_count(clean_rows, in_sample_predictions), clean_rows
        ),
        "in_sample_within_one_group": ratio_text(
            within_one_group_count(clean_rows, in_sample_predictions), clean_rows
        ),
        "note": "表格报告原始4指标；回归中折算平均目损使用 loss_fit=1/(1+loss)，当前难度使用评估脚本自带的 average_difficulty 复杂度归一化。",
    }
    coefficients = [{"feature": "intercept", "coefficient": round(float(model.intercept_), 10)}]
    coefficients.extend(
        {"feature": name, "coefficient": round(float(coef), 10)}
        for name, coef in zip(feature_names, model.coef_)
    )
    return [summary], coefficients


def core_four_regression_feature_names() -> list[str]:
    return ["match_rate", "first_choice_rate", "average_loss_fit", "current_difficulty_fit"]


def core_four_regression_features(row: dict[str, Any]) -> list[float]:
    average_loss = number_or(
        row,
        "average_score_equivalent_loss",
        number_or(row, "average_score_loss", number(row.get("average_point_loss"))),
    )
    return [
        clamp(number(row["match_rate"]), 0.0, 1.0),
        clamp(number(row["first_choice_rate"]), 0.0, 1.0),
        loss_fit(average_loss, 50.0),
        core_four_difficulty_fit(row),
    ]


def core_four_difficulty_fit(row: dict[str, Any]) -> float:
    return clamp((number(row["average_difficulty"]) - 25.0) / 35.0, 0.0, 1.0)


def regression_prediction_rows(
    rows: list[dict[str, Any]],
    game_predictions: Iterable[float],
    player_predictions: Iterable[float],
) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for row, game_prediction, player_prediction in zip(rows, game_predictions, player_predictions):
        game_rank = float(game_prediction)
        game_group = calibration.estimate_group(game_rank)
        target_rank = model_rank_value(row)
        target_group = model_group(row)
        game_error = game_rank - target_rank
        player_rank = float(player_prediction)
        player_group = calibration.estimate_group(player_rank)
        player_error = player_rank - target_rank
        result.append(
            {
                "sgf": row["sgf"],
                "sample_source": row.get("sample_source", ""),
                "side": row.get("side", ""),
                "player": row.get("player", ""),
                "fox_rank": row.get("fox_rank", ""),
                "actual_rank_text": row["actual_rank_text"],
                "actual_rank_value": row["actual_rank_value"],
                "actual_group": row["actual_group"],
                "model_rank_value": round(target_rank, 3),
                "model_group": target_group,
                "metric_rank_reassigned": row.get("metric_rank_reassigned", ""),
                "metric_adjusted_rank_value": row.get("metric_adjusted_rank_value", ""),
                "metric_adjusted_group": row.get("metric_adjusted_group", ""),
                "cv_predicted_rank_value": round(game_rank, 3),
                "cv_predicted_rank_1dp": round(game_rank, 1),
                "cv_predicted_group": game_group,
                "cv_numeric_error": round(game_error, 3),
                "cv_abs_error": round(abs(game_error), 3),
                "cv_group_distance": calibration.group_distance(target_group, game_group),
                "player_cv_predicted_rank_value": round(player_rank, 3),
                "player_cv_predicted_rank_1dp": round(player_rank, 1),
                "player_cv_predicted_group": player_group,
                "player_cv_numeric_error": round(player_error, 3),
                "player_cv_abs_error": round(abs(player_error), 3),
                "player_cv_group_distance": calibration.group_distance(target_group, player_group),
                "samples": row.get("samples", ""),
                "first_choice_rate": row.get("first_choice_rate", ""),
                "top5_rate": row.get("top5_rate", ""),
                "average_ai_rank": row.get("average_ai_rank", ""),
                "median_score_loss": row.get("median_score_loss", ""),
                "p90_score_equivalent_loss": row.get("p90_score_equivalent_loss", ""),
                "statistical_outlier": row.get("statistical_outlier", ""),
                "rank_mismatch_candidate": row.get("rank_mismatch_candidate", ""),
            }
        )
    return result


def regression_slice_evaluation(
    rows: list[dict[str, Any]], predictions: Iterable[float], validation: str
) -> list[dict[str, Any]]:
    pairs = list(zip(rows, predictions))
    result = [regression_slice_row(validation, "all-clean", pairs)]
    for source in sorted({str(row.get("sample_source") or "unknown") for row in rows}):
        result.append(
            regression_slice_row(
                validation,
                f"source:{source}",
                [
                    (row, prediction)
                    for row, prediction in pairs
                    if str(row.get("sample_source") or "unknown") == source
                ],
            )
        )
    for group in RANK_GROUP_ORDER:
        group_pairs = [(row, prediction) for row, prediction in pairs if model_group(row) == group]
        if group_pairs:
            result.append(regression_slice_row(validation, f"group:{group}", group_pairs))
    return result


def regression_slice_row(
    validation: str, name: str, pairs: list[tuple[dict[str, Any], float]]
) -> dict[str, Any]:
    errors = [
        float(prediction) - model_rank_value(row)
        for row, prediction in pairs
    ]
    abs_errors = sorted(abs(error) for error in errors)
    rows = [row for row, _ in pairs]
    predictions = [prediction for _, prediction in pairs]
    return {
        "validation": validation,
        "slice": name,
        "rows": len(pairs),
        "mean_absolute_error": round(statistics.fmean(abs_errors), 3) if abs_errors else 0.0,
        "median_absolute_error": round(statistics.median(abs_errors), 3) if abs_errors else 0.0,
        "exact_group": ratio_text(exact_group_count(rows, predictions), rows),
        "within_one_group": ratio_text(within_one_group_count(rows, predictions), rows),
        "p80_abs_error": round(percentile(abs_errors, 0.80), 3) if abs_errors else 0.0,
        "p90_abs_error": round(percentile(abs_errors, 0.90), 3) if abs_errors else 0.0,
    }


def strength_display_policy(slice_rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    player_rows = [row for row in slice_rows if row.get("validation") == "player_cv"]
    if not player_rows:
        player_rows = slice_rows
    result: list[dict[str, Any]] = []
    for row in player_rows:
        rows = int_number(row.get("rows"))
        if rows <= 0:
            continue
        p80 = number(row.get("p80_abs_error"))
        p90 = number(row.get("p90_abs_error"))
        mae = number(row.get("mean_absolute_error"))
        reliability = reliability_label(rows, p80, p90)
        result.append(
            {
                "validation": row.get("validation", ""),
                "slice": row.get("slice", ""),
                "rows": rows,
                "reliability": reliability,
                "display_interval_80": f"+/-{max(0.5, p80):.1f}",
                "display_interval_90": f"+/-{max(0.8, p90):.1f}",
                "mean_absolute_error": round(mae, 3),
                "p80_abs_error": round(p80, 3),
                "p90_abs_error": round(p90, 3),
                "note": display_policy_note(str(row.get("slice", "")), reliability),
            }
        )
    return sorted(result, key=lambda row: (policy_sort_key(str(row["slice"])), -int(row["rows"])))


def reliability_label(rows: int, p80: float, p90: float) -> str:
    if rows >= 200 and p80 <= 1.8 and p90 <= 3.0:
        return "high"
    if rows >= 80 and p80 <= 2.5 and p90 <= 4.0:
        return "medium"
    return "low"


def display_policy_note(slice_name: str, reliability: str) -> str:
    if slice_name == "all-clean":
        scope = "默认全局显示区间"
    elif slice_name.startswith("source:"):
        scope = "来源分层显示区间"
    elif slice_name.startswith("group:"):
        scope = "段位组分层显示区间"
    else:
        scope = "分层显示区间"
    if reliability == "high":
        return f"{scope}；样本量和误差分位数足够，可作为默认置信区间。"
    if reliability == "medium":
        return f"{scope}；可用于提示不确定性，但不宜单独作为阈值。"
    return f"{scope}；样本或误差不稳定，仅作诊断，不用于硬阈值。"


def policy_sort_key(slice_name: str) -> tuple[int, str]:
    if slice_name == "all-clean":
        return (0, slice_name)
    if slice_name.startswith("source:"):
        return (1, slice_name)
    if slice_name.startswith("group:"):
        return (2, slice_name)
    return (3, slice_name)


def strength_model_config(
    summary_rows: list[dict[str, Any]],
    coefficient_rows: list[dict[str, Any]],
    slice_rows: list[dict[str, Any]],
    display_policy_rows: list[dict[str, Any]],
) -> dict[str, Any]:
    coefficients = {
        str(row["feature"]): number(row.get("coefficient"))
        for row in coefficient_rows
        if row.get("feature") and row.get("feature") != "intercept"
    }
    intercept = next(
        (
            number(row.get("coefficient"))
            for row in coefficient_rows
            if row.get("feature") == "intercept"
        ),
        0.0,
    )
    return {
        "schema_version": 1,
        "model_name": "lizzie_strength_rank_value",
        "target": "continuous_rank_value",
        "training_target": "metric-adjusted continuous_rank_value from exact-rank profiles, fixed 12-rank-class metric centers, and Shin-Jinseo 40% 12d-style rule",
        "prediction_formula": "rank_value = intercept + sum(coefficients[feature] * feature_value)",
        "feature_order": regression_feature_names(),
        "intercept": intercept,
        "coefficients": coefficients,
        "training_summary": summary_rows[0] if summary_rows else {},
        "display_policy": display_policy_rows,
        "validation_slices": slice_rows,
        "rank_axis": {
            "kyu_level": "negative values; kept only when kyu samples are intentionally included",
            "low_dan": "1d-3d",
            "mid_dan": "4d-6d",
            "high_dan": "7d-9d",
            "professional": "10d-professional",
            "top_professional": "11d-top-professional",
            "ai": "12d-AI",
        },
        "recommended_usage": [
            "Keep original rank labels for audit, but fit the model against metric-adjusted model_rank_value.",
            "Use fixed 12-rank-class metric centers as secondary evidence; do not override labels unless the row is also far from its original exact-rank profile.",
            "Use the 12d-style rule as a high-confidence promotion rule; require the full metric bundle instead of a single average-loss threshold.",
            "Use the continuous rank_value as the primary internal score.",
            "Display one decimal place for rank_value.",
            "Use player_cv display intervals for uncertainty; fall back to all-clean when a source/group slice is unavailable.",
            "Keep professional, top_professional, and ai labels separate unless their full-batch validation slices are stable.",
        ],
    }


def regression_feature_names() -> list[str]:
    return [
        "first_choice_rate",
        "top3_rate",
        "top5_rate",
        "average_ai_rank_fit",
        "excellent_rate",
        "good_move_rate",
        "non_inaccuracy_rate",
        "match_rate",
        "non_mistake_rate",
        "non_blunder_rate",
        "weighted_loss_fit",
        "average_loss_fit",
        "median_loss_fit",
        "p75_loss_fit",
        "p90_loss_fit",
        "p95_loss_fit",
        "max_loss_fit",
        "loss_stability_fit",
        "difficulty_fit",
        "opening_loss_fit",
        "middlegame_loss_fit",
        "endgame_loss_fit",
        "opening_good_move_rate",
        "middlegame_good_move_rate",
        "endgame_good_move_rate",
        "first_choice_x_difficulty",
        "good_move_x_difficulty",
        "match_x_difficulty",
        "top5_x_difficulty",
    ]


def regression_features(row: dict[str, Any]) -> list[float]:
    first_choice = clamp(number(row["first_choice_rate"]), 0.0, 1.0)
    good_move = clamp(number(row["good_move_rate"]), 0.0, 1.0)
    match = clamp(number(row["match_rate"]), 0.0, 1.0)
    top3 = clamp(number_or(row, "top3_rate", first_choice), 0.0, 1.0)
    top5 = clamp(number_or(row, "top5_rate", good_move), 0.0, 1.0)
    average_ai_rank_fallback = max(1.0, 1.0 + 2.0 * (1.0 - top3) + 4.0 * (1.0 - top5))
    average_ai_rank_fit = 1.0 / (
        1.0 + clamp(number_or(row, "average_ai_rank", average_ai_rank_fallback), 0.0, 10.0)
    )
    excellent = clamp(number_or(row, "excellent_rate", first_choice), 0.0, 1.0)
    non_inaccuracy = 1.0 - clamp(number_or(row, "inaccuracy_rate", number(row.get("bad_move_rate"))), 0.0, 1.0)
    non_mistake = 1.0 - clamp(number(row["mistake_rate"]), 0.0, 1.0)
    non_blunder = 1.0 - clamp(number(row.get("blunder_rate")), 0.0, 1.0)
    average_loss_value = number_or(
        row,
        "average_score_equivalent_loss",
        number_or(row, "average_score_loss", number(row.get("average_point_loss"))),
    )
    median_loss_value = number_or(row, "median_score_loss", average_loss_value)
    p75_loss_value = number_or(row, "p75_score_equivalent_loss", median_loss_value)
    p90_loss_value = number_or(row, "p90_score_equivalent_loss", p75_loss_value)
    weighted_loss_value = number_or(row, "weighted_point_loss", average_loss_value)
    weighted_loss_fit = loss_fit(weighted_loss_value, 50.0)
    average_loss_fit = loss_fit(average_loss_value, 50.0)
    median_loss_fit = loss_fit(median_loss_value, 50.0)
    p75_loss_fit = loss_fit(p75_loss_value, 50.0)
    p90_loss_fit = loss_fit(p90_loss_value, 80.0)
    p95_loss_fit = loss_fit(number_or(row, "p95_score_equivalent_loss", p90_loss_value), 100.0)
    max_loss_fit = loss_fit(number_or(row, "max_score_equivalent_loss", p90_loss_value), 120.0)
    loss_stddev_fallback = max(
        0.0,
        p90_loss_value - median_loss_value,
    )
    loss_stability_fit = loss_fit(number_or(row, "loss_stddev", loss_stddev_fallback), 50.0)
    difficulty = clamp((number(row["average_difficulty"]) - 25.0) / 35.0, 0.0, 1.0)
    opening_loss_fit = loss_fit(number_or(row, "opening_weighted_loss", weighted_loss_value), 50.0)
    middlegame_loss_fit = loss_fit(number_or(row, "middlegame_weighted_loss", weighted_loss_value), 50.0)
    endgame_loss_fit = loss_fit(number_or(row, "endgame_weighted_loss", weighted_loss_value), 50.0)
    opening_good = clamp(number_or(row, "opening_good_move_rate", good_move), 0.0, 1.0)
    middlegame_good = clamp(number_or(row, "middlegame_good_move_rate", good_move), 0.0, 1.0)
    endgame_good = clamp(number_or(row, "endgame_good_move_rate", good_move), 0.0, 1.0)
    return [
        first_choice,
        top3,
        top5,
        average_ai_rank_fit,
        excellent,
        good_move,
        non_inaccuracy,
        match,
        non_mistake,
        non_blunder,
        weighted_loss_fit,
        average_loss_fit,
        median_loss_fit,
        p75_loss_fit,
        p90_loss_fit,
        p95_loss_fit,
        max_loss_fit,
        loss_stability_fit,
        difficulty,
        opening_loss_fit,
        middlegame_loss_fit,
        endgame_loss_fit,
        opening_good,
        middlegame_good,
        endgame_good,
        first_choice * difficulty,
        good_move * difficulty,
        match * difficulty,
        top5 * difficulty,
    ]


def loss_fit(value: Any, limit: float) -> float:
    return 1.0 / (1.0 + clamp(number(value), 0.0, limit))


def number_or(row: dict[str, Any], key: str, fallback: float) -> float:
    value = row.get(key)
    if value in (None, ""):
        return fallback
    return number(value)


def regression_balanced_weights(rows: list[dict[str, Any]]) -> list[float]:
    counts = Counter(model_group(row) for row in rows)
    group_count = max(1, len(counts))
    return [len(rows) / (group_count * counts[model_group(row)]) for row in rows]


def player_group_key(row: dict[str, Any]) -> str:
    player = str(row.get("player") or "").strip()
    source = str(row.get("sample_source") or "unknown").strip()
    if player:
        return f"{source}:{player}"
    return f"anonymous:{row.get('sgf', '')}:{row.get('side', '')}"


def regression_model() -> tuple[Any, str]:
    try:
        from sklearn.linear_model import HuberRegressor

        return (
            HuberRegressor(alpha=0.001, epsilon=1.8, max_iter=2000),
            "HuberRegressor(alpha=0.001, epsilon=1.8)",
        )
    except ImportError:
        return (
            NumpyHuberRidgeRegressor(alpha=0.001, delta=1.8, max_iter=40),
            "NumpyHuberRidgeRegressor(alpha=0.001, delta=1.8)",
        )


class NumpyHuberRidgeRegressor:
    def __init__(self, alpha: float = 0.001, delta: float = 1.8, max_iter: int = 40) -> None:
        self.alpha = alpha
        self.delta = delta
        self.max_iter = max_iter
        self.intercept_ = 0.0
        self.coef_: list[float] = []

    def clone(self) -> "NumpyHuberRidgeRegressor":
        return NumpyHuberRidgeRegressor(self.alpha, self.delta, self.max_iter)

    def fit(self, x: Any, y: Any, sample_weight: Iterable[float] | None = None) -> "NumpyHuberRidgeRegressor":
        import numpy as np

        x_array = np.asarray(x, dtype=float)
        y_array = np.asarray(y, dtype=float)
        weights = np.ones(len(y_array), dtype=float)
        if sample_weight is not None:
            weights = np.asarray(list(sample_weight), dtype=float)
        design = np.column_stack([np.ones(len(y_array)), x_array])
        beta = np.zeros(design.shape[1], dtype=float)
        robust = np.ones(len(y_array), dtype=float)
        penalty = np.eye(design.shape[1], dtype=float) * self.alpha
        penalty[0, 0] = 0.0
        for _ in range(self.max_iter):
            effective = np.maximum(weights * robust, 1e-9)
            weighted_design = design * np.sqrt(effective)[:, None]
            weighted_y = y_array * np.sqrt(effective)
            try:
                next_beta = np.linalg.solve(
                    weighted_design.T @ weighted_design + penalty,
                    weighted_design.T @ weighted_y,
                )
            except np.linalg.LinAlgError:
                next_beta = np.linalg.lstsq(weighted_design, weighted_y, rcond=None)[0]
            residual = y_array - design @ next_beta
            robust = np.where(
                np.abs(residual) <= self.delta,
                1.0,
                self.delta / np.maximum(np.abs(residual), 1e-9),
            )
            if np.linalg.norm(next_beta - beta) < 1e-6:
                beta = next_beta
                break
            beta = next_beta
        self.intercept_ = float(beta[0])
        self.coef_ = [float(value) for value in beta[1:]]
        return self

    def predict(self, x: Any) -> Any:
        import numpy as np

        return np.asarray(x, dtype=float) @ np.asarray(self.coef_, dtype=float) + self.intercept_


def grouped_regression_predictions(model: Any, x: Any, y: Any, groups: Any, weights: list[float]) -> list[float]:
    import numpy as np

    unique_groups = sorted(set(groups))
    if len(unique_groups) < 2:
        fitted = clone_regression_model(model)
        fitted.fit(x, y, sample_weight=weights)
        return list(map(float, fitted.predict(x)))
    predictions = np.zeros(len(y), dtype=float)
    weights_array = np.array(weights, dtype=float)
    for test_index in group_kfold_indices(groups, n_splits=min(5, len(unique_groups))):
        test_set = set(test_index)
        train_index = np.array([idx for idx in range(len(y)) if idx not in test_set], dtype=int)
        test_index = np.array(test_index, dtype=int)
        fitted = clone_regression_model(model)
        fitted.fit(x[train_index], y[train_index], sample_weight=weights_array[train_index])
        predictions[test_index] = fitted.predict(x[test_index])
    return list(map(float, predictions))


def clone_regression_model(model: Any) -> Any:
    if hasattr(model, "clone"):
        return model.clone()
    try:
        from sklearn.base import clone

        return clone(model)
    except ImportError:
        raise RuntimeError(f"cannot clone regression model of type {type(model).__name__}")


def group_kfold_indices(groups: Iterable[Any], n_splits: int) -> list[list[int]]:
    grouped: dict[Any, list[int]] = defaultdict(list)
    for index, group in enumerate(groups):
        grouped[group].append(index)
    fold_indices: list[list[int]] = [[] for _ in range(n_splits)]
    group_order = sorted(grouped, key=lambda group: (-len(grouped[group]), str(group)))
    for group in group_order:
        target = min(range(n_splits), key=lambda idx: len(fold_indices[idx]))
        fold_indices[target].extend(grouped[group])
    return [sorted(indices) for indices in fold_indices if indices]


def mean_absolute_rank_error(rows: list[dict[str, Any]], predictions: Iterable[float]) -> float:
    errors = [abs(float(prediction) - model_rank_value(row)) for row, prediction in zip(rows, predictions)]
    return statistics.fmean(errors) if errors else 0.0


def exact_group_count(rows: list[dict[str, Any]], predictions: Iterable[float]) -> int:
    return sum(group_distance_for_prediction(row, prediction) == 0 for row, prediction in zip(rows, predictions))


def within_one_group_count(rows: list[dict[str, Any]], predictions: Iterable[float]) -> int:
    return sum(group_distance_for_prediction(row, prediction) <= 1 for row, prediction in zip(rows, predictions))


def group_distance_for_prediction(row: dict[str, Any], prediction: float) -> int:
    return calibration.group_distance(model_group(row), calibration.estimate_group(float(prediction)))


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
    errors = [number(row["estimated_rank_value"]) - model_rank_value(row) for row in rows]
    return {
        "slice": name,
        "rows": len(rows),
        "exact_group": count_pct(
            sum(
                1
                for row in rows
                if calibration.group_distance(model_group(row), str(row["estimated_group"])) == 0
            ),
            rows,
        ),
        "within_one_group": count_pct(
            sum(
                1
                for row in rows
                if calibration.group_distance(model_group(row), str(row["estimated_group"])) <= 1
            ),
            rows,
        ),
        "kyu_as_dan": count_pct(
            sum(
                1
                for row in rows
                if model_rank_value(row) <= 0
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
        "sample_source",
        "side",
        "player",
        "fox_rank",
        "actual_rank_text",
        "actual_rank_value",
        "actual_group",
        "model_rank_value",
        "model_group",
        "metric_rank_reassigned",
        "metric_reassignment_method",
        "metric_adjusted_rank_value",
        "metric_adjusted_group",
        "metric_nearest_rank_value",
        "metric_nearest_group",
        "metric_cluster_id",
        "metric_cluster_k",
        "metric_cluster_collection_class",
        "metric_cluster_rank_median",
        "metric_cluster_group",
        "metric_cluster_distance",
        "metric_cluster_second_distance",
        "metric_cluster_margin",
        "metric_cluster_radius_p90",
        "metric_cluster_distance_ratio",
        "metric_cluster_reassigned",
        "twelve_d_style_rule_hit",
        "twelve_d_style_rule_reassigned",
        "metric_original_rank_distance",
        "metric_nearest_rank_distance",
        "metric_reassignment_margin",
        "metric_reassignment_reason",
        "strength",
        "estimated_rank_value",
        "estimated_group",
        "numeric_error",
        "samples",
        "max_visits",
        "quality_score",
        "first_choice_rate",
        "top3_rate",
        "top5_rate",
        "outside_top5_rate",
        "average_ai_rank",
        "excellent_rate",
        "great_rate",
        "good_move_rate",
        "inaccuracy_rate",
        "match_rate",
        "mistake_rate",
        "average_difficulty",
        "weighted_point_loss",
        "average_score_loss",
        "average_score_equivalent_loss",
        "median_score_loss",
        "p75_score_equivalent_loss",
        "p90_score_equivalent_loss",
        "p95_score_equivalent_loss",
        "max_score_equivalent_loss",
        "loss_stddev",
        "average_winrate_loss",
        "bad_move_rate",
        "blunder_rate",
        "opening_samples",
        "middlegame_samples",
        "endgame_samples",
        "opening_weighted_loss",
        "middlegame_weighted_loss",
        "endgame_weighted_loss",
        "opening_good_move_rate",
        "middlegame_good_move_rate",
        "endgame_good_move_rate",
        "robust_metric_distance",
        "statistical_outlier",
        "nearest_metric_group",
        "actual_metric_distance",
        "nearest_metric_distance",
        "rank_mismatch_candidate",
        "residual_core_four_outlier",
        "residual_core_four_metric",
        "residual_core_four_direction",
        "residual_core_four_robust_z",
        "residual_core_four_abs_robust_z",
        "residual_core_four_value",
        "residual_core_four_class_median",
    ]
    write_csv(path, rows, fieldnames)


def write_csv(path: Path, rows: list[dict[str, Any]], fieldnames: list[str]) -> None:
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_markdown(
    path: Path,
    rows: list[dict[str, Any]],
    correlations: list[dict[str, Any]],
    distributions: list[dict[str, Any]],
    formula_rows: list[dict[str, Any]],
    source_rows: list[dict[str, Any]],
    regression_summary_rows: list[dict[str, Any]],
    regression_slice_rows: list[dict[str, Any]],
    display_policy_rows: list[dict[str, Any]],
    metric_reassignment_rows: list[dict[str, Any]],
    cluster_k_rows: list[dict[str, Any]],
    cluster_summary_rows: list[dict[str, Any]],
    core_four_metric_rows: list[dict[str, Any]],
    core_four_summary_rows: list[dict[str, Any]],
    core_four_coefficient_rows: list[dict[str, Any]],
    args: argparse.Namespace,
) -> None:
    visits = Counter(str(row.get("max_visits") or "") for row in rows)
    group_counts = Counter(str(row["actual_group"]) for row in rows)
    model_group_counts = Counter(model_group(row) for row in rows)
    outliers = sum(1 for row in rows if row["statistical_outlier"])
    mismatches = sum(1 for row in rows if row["rank_mismatch_candidate"])
    reassignments = len(metric_reassignment_rows)
    cluster_reassignments = sum(1 for row in rows if row.get("metric_cluster_reassigned"))
    twelve_d_hits = twelve_d_style_rule_rows(rows)
    twelve_d_reassignments = sum(
        1 for row in rows if row.get("twelve_d_style_rule_reassigned")
    )
    selected_cluster_row = next((row for row in cluster_k_rows if row.get("selected")), None)
    if selected_cluster_row and selected_cluster_row.get("method") == "rank_class_centroid":
        cluster_mode_text = (
            f"按固定12段位类聚类，k={selected_cluster_row['k']}，"
            f"类别={selected_cluster_row.get('classes', '')}"
        )
    elif selected_cluster_row and selected_cluster_row.get("method") == "collection_class_centroid":
        cluster_mode_text = (
            f"按采集数据类别固定聚类，k={selected_cluster_row['k']}，"
            f"类别={selected_cluster_row.get('classes', '')}"
        )
    elif selected_cluster_row:
        cluster_mode_text = (
            f"自动指标聚类范围 k={args.metric_cluster_min_k}..{args.metric_cluster_max_k}，"
            f"选中 k={selected_cluster_row['k']}"
        )
    else:
        cluster_mode_text = "未生成稳定聚类"
    lines = [
        "# 棋力评测统计校准报告",
        "",
        "## 数据范围",
        "",
        f"- 有效评测方数：{len(rows)}",
        f"- 最少样本手数：{args.min_samples}",
        f"- 统计异常候选：{outliers}/{len(rows)} ({pct(outliers, len(rows))})",
        f"- 段位不匹配候选：{mismatches}/{len(rows)} ({pct(mismatches, len(rows))})",
        f"- 指标重归属样本：{reassignments}/{len(rows)} ({pct(reassignments, len(rows))})",
        f"- 其中聚类重归属样本：{cluster_reassignments}/{len(rows)} ({pct(cluster_reassignments, len(rows))})",
        f"- 12d强指标规则命中：{len(twelve_d_hits)}/{len(rows)} ({pct(len(twelve_d_hits), len(rows))})",
        f"- 其中12d强指标规则重归属：{twelve_d_reassignments}/{len(rows)} ({pct(twelve_d_reassignments, len(rows))})",
        f"- robust z 距离阈值：{args.outlier_z}",
        f"- 精确段位画像重归属阈值：原段位距离>={args.metric_reassignment_min_distance}、优势>={args.metric_reassignment_margin}、最近段位距离<={args.metric_reassignment_max_nearest_distance}",
        f"- 指标聚类模式：{cluster_mode_text}",
        f"- 聚类重归属闸门：原段位画像距离>={args.metric_cluster_reassignment_min_original_distance}、段位差>={args.metric_cluster_reassignment_min_rank_gap}、类中心优势>={args.metric_cluster_reassignment_margin}、半径比<={args.metric_cluster_reassignment_max_radius_ratio}",
        f"- 12d强指标阈值：吻合度>={args.twelve_d_style_match}、Top5>={args.twelve_d_style_top5}、平均AI排名<={args.twelve_d_style_average_ai_rank}、折算平均目损<={args.twelve_d_style_average_loss}、P90目损<={args.twelve_d_style_p90_loss}、失误率<={args.twelve_d_style_mistake_rate}",
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
    lines.extend(["", "## 清洗后目标大段位组分布", ""])
    for group in RANK_GROUP_ORDER:
        lines.append(f"- {group}: {model_group_counts[group]}")

    lines.extend(["", "## 指标聚类选类", ""])
    if cluster_k_rows:
        lines.append("| k | 选中 | 方法 | 类别 | 锚点方数 | inertia | 中心 silhouette | Davies-Bouldin | 最小簇 | 最大簇 | 稳定 |")
        lines.append("| ---: | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |")
        for row in cluster_k_rows:
            lines.append(
                "| "
                + " | ".join(
                    [
                        str(row["k"]),
                        "yes" if row.get("selected") else "",
                        str(row.get("method", "")),
                        str(row.get("classes", "")),
                        str(row["anchor_rows"]),
                        f"{number(row['inertia']):.3f}",
                        f"{number(row['centroid_silhouette']):.3f}",
                        f"{number(row['davies_bouldin']):.3f}",
                        str(row["min_cluster_rows"]),
                        str(row["max_cluster_rows"]),
                        "yes" if row.get("stable") else "",
                    ]
                )
                + " |"
            )
    else:
        lines.append("_未生成聚类，通常是锚点样本不足或缺少 NumPy。_")

    lines.extend(["", "## 指标聚类摘要", ""])
    if cluster_summary_rows:
        lines.append("| 簇 | 采集类 | 方数 | 锚点 | 离群 | 中位段位 | Q25 | Q75 | 归属组 | 主组占比 | 吻合度 | Top5 | 平均AI排名 | 折算平均目损 | 失误率 |")
        lines.append("| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |")
        for row in cluster_summary_rows:
            lines.append(
                "| "
                + " | ".join(
                    [
                        str(row["cluster_id"]),
                        str(row.get("collection_class", "")),
                        str(row["rows"]),
                        str(row["anchor_rows"]),
                        str(row["outlier_rows"]),
                        str(row["cluster_rank_median"]),
                        str(row["cluster_rank_q25"]),
                        str(row["cluster_rank_q75"]),
                        str(row["cluster_group"]),
                        f"{number(row['dominant_group_share']):.2f}",
                        f"{number(row['median_match_rate']):.3f}",
                        f"{number(row['median_top5_rate']):.3f}",
                        f"{number(row['median_average_ai_rank']):.2f}",
                        f"{number(row['median_average_score_equivalent_loss']):.2f}",
                        f"{number(row['median_mistake_rate']):.3f}",
                    ]
                )
                + " |"
            )
    else:
        lines.append("_未生成聚类摘要。_")

    lines.extend(["", "## 指标归属清洗", ""])
    if metric_reassignment_rows:
        summary_rows = metric_reassignment_summary(rows)
        lines.append(
            "明显脱离原段位指标画像、且稳定接近另一个精确段位画像或固定12段位类中心的样本，会保留原始标签并把 `model_rank_value` 改为指标归属段位；满足12d强指标规则的非12d样本直接提升为12d训练标签；未能稳定归属的统计异常/段位不匹配样本仍不参与拟合。"
        )
        lines.append("")
        lines.append("| 原段位 | 原组 | 调整段位 | 调整组 | 方法 | 方数 | 棋局 | 来源 | 原距离中位 | 新距离中位 | 优势中位 |")
        lines.append("| ---: | --- | ---: | --- | --- | ---: | ---: | --- | ---: | ---: | ---: |")
        for row in summary_rows:
            lines.append(
                "| "
                + " | ".join(
                    [
                        str(row["from_rank_value"]),
                        str(row["from_group"]),
                        str(row["to_rank_value"]),
                        str(row["to_group"]),
                        str(row["method"]),
                        str(row["rows"]),
                        str(row["games"]),
                        str(row["sources"]),
                        f"{number(row['median_original_distance']):.2f}",
                        f"{number(row['median_nearest_distance']):.2f}",
                        f"{number(row['median_margin']):.2f}",
                    ]
                )
                + " |"
            )
    else:
        lines.append("_未发现满足阈值的指标重归属样本。_")

    lines.extend(["", "## 12d强指标规则", ""])
    if twelve_d_hits:
        lines.append(
            "满足申真谞 40% 12d 表现线的样本视为 12d 风格：只有同时满足吻合度、Top5、平均AI排名、折算目损、P90目损和失误率六个阈值才命中；原始段位低于 12d 的命中样本会把 `model_rank_value` 提升为 12.0。"
        )
        lines.append("")
        lines.append("| 原段位 | 原组 | 命中方数 | 棋局 | 重归属方数 | 来源 | 吻合度 | Top5 | 平均AI排名 | 平均目损 | P90目损 | 失误率 |")
        lines.append("| ---: | --- | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |")
        for row in twelve_d_style_rule_summary(rows):
            lines.append(
                "| "
                + " | ".join(
                    [
                        str(row["actual_rank_value"]),
                        str(row["actual_group"]),
                        str(row["rows"]),
                        str(row["games"]),
                        str(row["reassigned_rows"]),
                        str(row["sources"]),
                        f"{number(row['median_match_rate']):.3f}",
                        f"{number(row['median_top5_rate']):.3f}",
                        f"{number(row['median_average_ai_rank']):.2f}",
                        f"{number(row['median_average_score_equivalent_loss']):.3f}",
                        f"{number(row['median_p90_score_equivalent_loss']):.3f}",
                        f"{number(row['median_mistake_rate']):.3f}",
                    ]
                )
                + " |"
            )
    else:
        lines.append("_未发现满足 12d 强指标规则的样本。_")

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

    lines.extend(["", "## 来源与分组误差", ""])
    lines.append("| 切片 | 方数 | 棋局 | 实际组 | 当前公式组命中 | 当前公式组误差<=1 | 当前公式MAE |")
    lines.append("| --- | ---: | ---: | --- | --- | --- | ---: |")
    for row in source_rows[:32]:
        lines.append(
            "| "
            + " | ".join(
                [
                    str(row["slice"]),
                    str(row["rows"]),
                    str(row["games"]),
                    str(row["actual_groups"]),
                    str(row["current_exact_group"]),
                    str(row["current_within_one_group"]),
                    f"{number(row['current_mean_absolute_error']):.2f}",
                ]
            )
            + " |"
        )
    if len(source_rows) > 32:
        lines.append(f"| ... | 另有 {len(source_rows) - 32} 行，见 `source_evaluation.csv` |  |  |  |  |  |")

    lines.extend(["", "## 清洗集回归交叉验证", ""])
    if regression_summary_rows:
        lines.append("| 状态 | 方法 | 方数 | 棋局 | 棋局CV组数 | 棋局CV MAE | 棋局CV组命中 | 棋手CV组数 | 棋手CV MAE | 棋手CV组命中 | 样本内MAE |")
        lines.append("| --- | --- | ---: | ---: | ---: | ---: | --- | ---: | ---: | --- | ---: |")
        for row in regression_summary_rows:
            lines.append(
                "| "
                + " | ".join(
                    [
                        str(row.get("status", "")),
                        str(row.get("method", "")),
                        str(row.get("rows", "")),
                        str(row.get("games", "")),
                        str(row.get("game_cv_groups", "")),
                        f"{number(row.get('cv_mean_absolute_error')):.2f}",
                        str(row.get("cv_exact_group", "")),
                        str(row.get("player_cv_groups", "")),
                        f"{number(row.get('player_cv_mean_absolute_error')):.2f}",
                        str(row.get("player_cv_exact_group", "")),
                        f"{number(row.get('in_sample_mean_absolute_error')):.2f}",
                    ]
                )
                + " |"
            )
    if regression_slice_rows:
        lines.extend(["", "### 回归分层误差", ""])
        lines.append("| 验证 | 切片 | 方数 | MAE | 中位AE | 组命中 | 组误差<=1 | P80 AE | P90 AE |")
        lines.append("| --- | --- | ---: | ---: | ---: | --- | --- | ---: | ---: |")
        for row in regression_slice_rows:
            lines.append(
                "| "
                + " | ".join(
                    [
                        str(row.get("validation", "")),
                        str(row["slice"]),
                        str(row["rows"]),
                        f"{number(row['mean_absolute_error']):.2f}",
                        f"{number(row['median_absolute_error']):.2f}",
                        str(row["exact_group"]),
                        str(row["within_one_group"]),
                        f"{number(row['p80_abs_error']):.2f}",
                        f"{number(row['p90_abs_error']):.2f}",
                    ]
                )
                + " |"
            )

    if core_four_metric_rows:
        lines.extend(["", "## 4指标核心表", ""])
        lines.append(
            "这张表只报告吻合度、一选率、折算平均目损、当前难度四项；当前难度使用评估脚本自带的 average_difficulty 复杂度。`actual_label` 保留采集标签，`clean_label` 使用全有效样本的清洗后标签，`clean_training_label` 只统计实际进入回归训练的清洗集。"
        )
        lines.append("")
        lines.append("| 标签口径 | 段位 | 类别 | 方数 | 棋局 | 吻合度均值 | 一选率均值 | 折算平均目损均值 | 当前难度均值 | 吻合度中位 | 一选率中位 | 折算平均目损中位 | 当前难度中位 |")
        lines.append("| --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
        for row in core_four_metric_rows:
            lines.append(
                "| "
                + " | ".join(
                    [
                        str(row["label_type"]),
                        str(row["rank_value"]),
                        str(row["rank_class"]),
                        str(row["sides"]),
                        str(row["games"]),
                        f"{number(row['mean_match_rate']):.3f}",
                        f"{number(row['mean_first_choice_rate']):.3f}",
                        f"{number(row['mean_average_score_equivalent_loss']):.3f}",
                        f"{number(row['mean_average_difficulty']):.1f}",
                        f"{number(row['median_match_rate']):.3f}",
                        f"{number(row['median_first_choice_rate']):.3f}",
                        f"{number(row['median_average_score_equivalent_loss']):.3f}",
                        f"{number(row['median_average_difficulty']):.1f}",
                    ]
                )
                + " |"
            )

    if core_four_summary_rows:
        lines.extend(["", "## 4指标核心回归", ""])
        lines.append("| 状态 | 特征 | 方数 | 棋局 | 棋手CV组数 | 棋手CV MAE | 棋手CV组命中 | 棋手CV组误差<=1 | 棋局CV MAE | 样本内MAE |")
        lines.append("| --- | --- | ---: | ---: | ---: | ---: | --- | --- | ---: | ---: |")
        for row in core_four_summary_rows:
            lines.append(
                "| "
                + " | ".join(
                    [
                        str(row.get("status", "")),
                        str(row.get("features", "")),
                        str(row.get("rows", "")),
                        str(row.get("games", "")),
                        str(row.get("player_cv_groups", "")),
                        f"{number(row.get('player_cv_mean_absolute_error')):.2f}",
                        str(row.get("player_cv_exact_group", "")),
                        str(row.get("player_cv_within_one_group", "")),
                        f"{number(row.get('cv_mean_absolute_error')):.2f}",
                        f"{number(row.get('in_sample_mean_absolute_error')):.2f}",
                    ]
                )
                + " |"
            )
        if core_four_coefficient_rows:
            lines.extend(["", "### 4指标回归系数", ""])
            lines.append("| 特征 | 系数 |")
            lines.append("| --- | ---: |")
            for row in core_four_coefficient_rows:
                lines.append(
                    f"| {row.get('feature', '')} | {number(row.get('coefficient')):.6f} |"
                )

    if display_policy_rows:
        lines.extend(["", "## 显示区间建议", ""])
        lines.append("| 验证 | 切片 | 方数 | 可靠性 | 80%显示区间 | 90%显示区间 | MAE | P80 AE | P90 AE |")
        lines.append("| --- | --- | ---: | --- | ---: | ---: | ---: | ---: | ---: |")
        for row in display_policy_rows[:32]:
            lines.append(
                "| "
                + " | ".join(
                    [
                        str(row.get("validation", "")),
                        str(row.get("slice", "")),
                        str(row.get("rows", "")),
                        str(row.get("reliability", "")),
                        str(row.get("display_interval_80", "")),
                        str(row.get("display_interval_90", "")),
                        f"{number(row.get('mean_absolute_error')):.2f}",
                        f"{number(row.get('p80_abs_error')):.2f}",
                        f"{number(row.get('p90_abs_error')):.2f}",
                    ]
                )
                + " |"
            )
        if len(display_policy_rows) > 32:
            lines.append(f"| ... | 另有 {len(display_policy_rows) - 32} 行，见 `strength_display_policy.csv` |  |  |  |  |  |  |  |")

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
            "- `指标重归属样本` 表示该样本的整体指标稳定接近另一个精确段位画像；原始标签保留，拟合目标使用 `model_rank_value`。",
            "- `段位不匹配候选` 表示该样本的整体指标距离其它大段位组明显更近；如果不能稳定重归属到精确段位画像，则不参与公式拟合。",
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

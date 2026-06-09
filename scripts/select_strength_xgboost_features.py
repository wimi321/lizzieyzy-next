#!/usr/bin/env python3
"""Run XGBoost feature selection for strength calibration."""

from __future__ import annotations

import argparse
import csv
import json
import math
import sys
from pathlib import Path
from typing import Any

import numpy as np

import analyze_strength_calibration as calibration


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")


Z80 = 1.2815515655446004
Z90 = 1.6448536269514722


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("analysis_dir", help="Analysis directory containing calibration_rows.csv.")
    parser.add_argument("--seed", type=int, default=20260609)
    parser.add_argument("--output-prefix", default="xgboost_feature_selection")
    parser.add_argument("--selected-prefix", default="xgboost_selected_strength")
    parser.add_argument("--rounds", type=int, default=520)
    parser.add_argument("--depth", type=int, default=4)
    parser.add_argument("--eta", type=float, default=0.03)
    parser.add_argument("--search-rounds", type=int, default=220)
    parser.add_argument("--search-depth", type=int, default=3)
    parser.add_argument("--max-greedy-features", type=int, default=12)
    parser.add_argument("--selection-tolerance", type=float, default=0.015)
    parser.add_argument("--subsample", type=float, default=0.85)
    parser.add_argument("--colsample", type=float, default=0.9)
    parser.add_argument("--min-child-weight", type=float, default=3.0)
    parser.add_argument("--reg-lambda", type=float, default=3.0)
    parser.add_argument("--reg-alpha", type=float, default=0.0)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    analysis_dir = Path(args.analysis_dir)
    rows = [row for row in read_csv(analysis_dir / "calibration_rows.csv") if is_clean_csv_row(row)]
    if len(rows) < 100:
        print("[error] not enough clean rows", file=sys.stderr)
        return 1

    feature_names = calibration.regression_feature_names()
    x = np.array([calibration.regression_features(row) for row in rows], dtype=float)
    y = np.array([calibration.model_rank_value(row) for row in rows], dtype=float)
    ranks = np.array([int(round(calibration.model_rank_value(row))) for row in rows], dtype=int)
    groups = np.array([calibration.player_group_key(row) for row in rows])
    folds = calibration.group_kfold_indices(groups, n_splits=min(5, len(set(groups))))
    importance_order = feature_order_from_importance(
        analysis_dir / "xgboost_strength_feature_importance.csv",
        feature_names,
    )

    print(f"[feature-select] rows={len(rows)} features={len(feature_names)} folds={len(folds)}")
    single_rows = single_feature_results(x, y, ranks, folds, feature_names, args)
    write_csv(analysis_dir / f"{args.output_prefix}_single_feature.csv", single_rows)
    print(f"[feature-select] single-feature best={single_rows[0]['feature']} mae={single_rows[0]['player_cv_mae']}")

    cumulative_rows = cumulative_importance_results(
        x,
        y,
        ranks,
        folds,
        feature_names,
        importance_order,
        args,
    )
    write_csv(analysis_dir / f"{args.output_prefix}_cumulative_importance.csv", cumulative_rows)
    best_cumulative = min(cumulative_rows, key=lambda row: numeric(row["player_cv_mae"]))
    print(
        "[feature-select] cumulative best="
        f"k={best_cumulative['feature_count']} mae={best_cumulative['player_cv_mae']}"
    )

    greedy_search_rows, greedy_order = greedy_forward_search(
        x,
        y,
        ranks,
        folds,
        feature_names,
        args,
    )
    write_csv(analysis_dir / f"{args.output_prefix}_greedy_search.csv", greedy_search_rows)
    greedy_final_rows = evaluate_prefixes(
        x,
        y,
        ranks,
        folds,
        feature_names,
        greedy_order,
        args,
    )
    write_csv(analysis_dir / f"{args.output_prefix}_greedy_final.csv", greedy_final_rows)
    best_greedy = min(greedy_final_rows, key=lambda row: numeric(row["player_cv_mae"]))
    print(
        "[feature-select] greedy best="
        f"k={best_greedy['feature_count']} mae={best_greedy['player_cv_mae']}"
    )

    selected_source, selected_row = choose_selected_subset(
        cumulative_rows,
        greedy_final_rows,
        tolerance=float(args.selection_tolerance),
    )
    selected_features = str(selected_row["features"]).split(";")
    selected_indices = [feature_names.index(name) for name in selected_features]
    selected_metrics, fitted_prediction, cv_prediction, model = evaluate_subset(
        x,
        y,
        ranks,
        folds,
        selected_indices,
        feature_names,
        args,
        rounds=int(args.rounds),
        depth=int(args.depth),
        return_predictions=True,
        fit_full=True,
    )
    selected_payload = selected_model_payload(
        args,
        selected_source,
        selected_features,
        selected_metrics,
    )
    prediction_rows = prediction_rows_from_arrays(
        rows,
        y,
        fitted_prediction,
        cv_prediction,
        np.full(len(y), selected_metrics["global_prediction_sigma"], dtype=float),
    )
    write_csv(analysis_dir / f"{args.selected_prefix}_predictions.csv", prediction_rows)
    if model is not None:
        model.save_model(str(analysis_dir / f"{args.selected_prefix}_booster.json"))
    write_csv(
        analysis_dir / f"{args.selected_prefix}_feature_importance.csv",
        xgboost_importance_rows(model, x[:, selected_indices], selected_features) if model is not None else [],
    )
    (analysis_dir / f"{args.selected_prefix}_model.json").write_text(
        json.dumps(selected_payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    (analysis_dir / f"{args.output_prefix}_report.md").write_text(
        feature_selection_report(
            single_rows,
            cumulative_rows,
            greedy_search_rows,
            greedy_final_rows,
            selected_source,
            selected_metrics,
            selected_features,
        ),
        encoding="utf-8",
    )
    print(
        "[feature-select] selected="
        f"{selected_source} k={len(selected_features)} mae={selected_metrics['player_cv_mae']}"
    )
    return 0


def single_feature_results(
    x: np.ndarray,
    y: np.ndarray,
    ranks: np.ndarray,
    folds: list[list[int]],
    feature_names: list[str],
    args: argparse.Namespace,
) -> list[dict[str, Any]]:
    rows = []
    for index, feature in enumerate(feature_names):
        metrics, _, _, _ = evaluate_subset(
            x,
            y,
            ranks,
            folds,
            [index],
            feature_names,
            args,
            rounds=int(args.rounds),
            depth=int(args.depth),
        )
        rows.append({"feature": feature, **metrics})
    rows.sort(key=lambda row: numeric(row["player_cv_mae"]))
    for rank, row in enumerate(rows, start=1):
        row["rank"] = rank
    return rows


def cumulative_importance_results(
    x: np.ndarray,
    y: np.ndarray,
    ranks: np.ndarray,
    folds: list[list[int]],
    feature_names: list[str],
    feature_order: list[str],
    args: argparse.Namespace,
) -> list[dict[str, Any]]:
    rows = []
    selected: list[int] = []
    for added in feature_order:
        selected.append(feature_names.index(added))
        metrics, _, _, _ = evaluate_subset(
            x,
            y,
            ranks,
            folds,
            selected,
            feature_names,
            args,
            rounds=int(args.rounds),
            depth=int(args.depth),
        )
        rows.append(
            {
                "feature_count": len(selected),
                "added_feature": added,
                "features": ";".join(feature_names[index] for index in selected),
                **metrics,
            }
        )
    return rows


def greedy_forward_search(
    x: np.ndarray,
    y: np.ndarray,
    ranks: np.ndarray,
    folds: list[list[int]],
    feature_names: list[str],
    args: argparse.Namespace,
) -> tuple[list[dict[str, Any]], list[str]]:
    selected: list[int] = []
    remaining = list(range(len(feature_names)))
    rows = []
    order: list[str] = []
    for step in range(1, min(int(args.max_greedy_features), len(feature_names)) + 1):
        candidates = []
        for candidate in remaining:
            trial = selected + [candidate]
            metrics, _, _, _ = evaluate_subset(
                x,
                y,
                ranks,
                folds,
                trial,
                feature_names,
                args,
                rounds=int(args.search_rounds),
                depth=int(args.search_depth),
            )
            candidates.append((numeric(metrics["player_cv_mae"]), candidate, metrics))
        candidates.sort(key=lambda item: item[0])
        _, added, metrics = candidates[0]
        selected.append(added)
        remaining.remove(added)
        order.append(feature_names[added])
        rows.append(
            {
                "step": step,
                "added_feature": feature_names[added],
                "candidate_count": len(candidates),
                "features": ";".join(feature_names[index] for index in selected),
                **metrics,
            }
        )
        print(
            "[feature-select] greedy "
            f"step={step} added={feature_names[added]} search_mae={metrics['player_cv_mae']}"
        )
    return rows, order


def evaluate_prefixes(
    x: np.ndarray,
    y: np.ndarray,
    ranks: np.ndarray,
    folds: list[list[int]],
    feature_names: list[str],
    feature_order: list[str],
    args: argparse.Namespace,
) -> list[dict[str, Any]]:
    rows = []
    selected: list[int] = []
    for added in feature_order:
        selected.append(feature_names.index(added))
        metrics, _, _, _ = evaluate_subset(
            x,
            y,
            ranks,
            folds,
            selected,
            feature_names,
            args,
            rounds=int(args.rounds),
            depth=int(args.depth),
        )
        rows.append(
            {
                "feature_count": len(selected),
                "added_feature": added,
                "features": ";".join(feature_names[index] for index in selected),
                **metrics,
            }
        )
    return rows


def evaluate_subset(
    x: np.ndarray,
    y: np.ndarray,
    ranks: np.ndarray,
    folds: list[list[int]],
    feature_indices: list[int],
    all_feature_names: list[str],
    args: argparse.Namespace,
    *,
    rounds: int,
    depth: int,
    return_predictions: bool = False,
    fit_full: bool = False,
) -> tuple[dict[str, Any], np.ndarray, np.ndarray, Any]:
    subset_names = [all_feature_names[index] for index in feature_indices]
    subset_x = x[:, feature_indices]
    cv_prediction = grouped_xgboost_predictions(
        subset_x,
        y,
        ranks,
        folds,
        subset_names,
        args,
        rounds=rounds,
        depth=depth,
    )
    raw_error = cv_prediction - y
    bias_correction = float(np.median(raw_error))
    cv_prediction = cv_prediction - bias_correction
    cv_error = cv_prediction - y
    metrics = metric_row(cv_error)
    metrics["median_cv_bias_correction"] = round(bias_correction, 3)
    metrics["feature_count"] = len(feature_indices)
    metrics["features"] = ";".join(subset_names)
    model = None
    fitted_prediction = np.zeros(len(y), dtype=float)
    if fit_full or return_predictions:
        model = train_xgboost(
            subset_x,
            y,
            ranks,
            subset_names,
            args,
            rounds=rounds,
            depth=depth,
            seed_offset=777,
        )
        fitted_prediction = predict_xgboost(model, subset_x, subset_names) - bias_correction
    return metrics, fitted_prediction, cv_prediction, model


def grouped_xgboost_predictions(
    x: np.ndarray,
    y: np.ndarray,
    ranks: np.ndarray,
    folds: list[list[int]],
    feature_names: list[str],
    args: argparse.Namespace,
    *,
    rounds: int,
    depth: int,
) -> np.ndarray:
    predictions = np.zeros(len(y), dtype=float)
    for fold_id, test_index in enumerate(folds):
        test_index_array = np.array(test_index, dtype=int)
        train_mask = np.ones(len(y), dtype=bool)
        train_mask[test_index_array] = False
        train_index = np.where(train_mask)[0]
        model = train_xgboost(
            x[train_index],
            y[train_index],
            ranks[train_index],
            feature_names,
            args,
            rounds=rounds,
            depth=depth,
            seed_offset=fold_id * 1009,
        )
        predictions[test_index_array] = predict_xgboost(model, x[test_index_array], feature_names)
    return predictions


def train_xgboost(
    x: np.ndarray,
    y: np.ndarray,
    ranks: np.ndarray,
    feature_names: list[str],
    args: argparse.Namespace,
    *,
    rounds: int,
    depth: int,
    seed_offset: int,
) -> Any:
    import xgboost as xgb

    dtrain = xgb.DMatrix(x, label=y, weight=np.ones(len(y), dtype=float), feature_names=feature_names)
    params = {
        "objective": "reg:absoluteerror",
        "eval_metric": "mae",
        "max_depth": int(depth),
        "eta": float(args.eta),
        "subsample": float(args.subsample),
        "colsample_bytree": float(args.colsample),
        "min_child_weight": float(args.min_child_weight),
        "lambda": float(args.reg_lambda),
        "alpha": float(args.reg_alpha),
        "tree_method": "hist",
        "seed": int(args.seed) + seed_offset,
        "nthread": 4,
    }
    return xgb.train(params, dtrain, num_boost_round=int(rounds), verbose_eval=False)


def predict_xgboost(model: Any, x: np.ndarray, feature_names: list[str]) -> np.ndarray:
    import xgboost as xgb

    return np.asarray(model.predict(xgb.DMatrix(x, feature_names=feature_names)), dtype=float)


def xgboost_importance_rows(model: Any, x: np.ndarray, feature_names: list[str]) -> list[dict[str, Any]]:
    if model is None:
        return []
    import xgboost as xgb

    gain = model.get_score(importance_type="gain")
    cover = model.get_score(importance_type="cover")
    contrib = model.predict(xgb.DMatrix(x, feature_names=feature_names), pred_contribs=True)
    mean_abs_contrib = np.mean(np.abs(contrib[:, :-1]), axis=0)
    rows = []
    for index, name in enumerate(feature_names):
        rows.append(
            {
                "feature": name,
                "gain": round(float(gain.get(name, 0.0)), 8),
                "cover": round(float(cover.get(name, 0.0)), 8),
                "mean_abs_contribution": round(float(mean_abs_contrib[index]), 8),
            }
        )
    return sorted(rows, key=lambda row: row["mean_abs_contribution"], reverse=True)


def metric_row(cv_error: np.ndarray) -> dict[str, Any]:
    abs_error = np.abs(cv_error)
    sigma = max(float(np.quantile(abs_error, 0.90) / Z90), 1e-9)
    return {
        "player_cv_mae": round(float(np.mean(abs_error)), 3),
        "player_cv_rmse": round(float(math.sqrt(np.mean(cv_error * cv_error))), 3),
        "player_cv_abs_error_p50": round(float(np.quantile(abs_error, 0.50)), 3),
        "player_cv_abs_error_p80": round(float(np.quantile(abs_error, 0.80)), 3),
        "player_cv_abs_error_p90": round(float(np.quantile(abs_error, 0.90)), 3),
        "within_0_5": round(float(np.mean(abs_error <= 0.5)), 4),
        "within_1_0": round(float(np.mean(abs_error <= 1.0)), 4),
        "within_1_5": round(float(np.mean(abs_error <= 1.5)), 4),
        "within_2_0": round(float(np.mean(abs_error <= 2.0)), 4),
        "global_prediction_sigma": round(sigma, 3),
        "interval80_coverage": round(float(np.mean(abs_error <= Z80 * sigma)), 4),
        "interval90_coverage": round(float(np.mean(abs_error <= Z90 * sigma)), 4),
    }


def feature_order_from_importance(path: Path, feature_names: list[str]) -> list[str]:
    if path.exists():
        rows = read_csv(path)
        ordered = [
            row["feature"]
            for row in sorted(
                rows,
                key=lambda item: numeric(item.get("mean_abs_contribution")),
                reverse=True,
            )
            if row.get("feature") in feature_names
        ]
        missing = [name for name in feature_names if name not in set(ordered)]
        return ordered + missing
    return feature_names[:]


def choose_selected_subset(
    cumulative_rows: list[dict[str, Any]],
    greedy_rows: list[dict[str, Any]],
    *,
    tolerance: float,
) -> tuple[str, dict[str, Any]]:
    candidates = [("importance_cumulative", row) for row in cumulative_rows]
    candidates.extend(("greedy_forward", row) for row in greedy_rows)
    best_mae = min(numeric(row["player_cv_mae"]) for _, row in candidates)
    acceptable = [
        (source, row)
        for source, row in candidates
        if numeric(row["player_cv_mae"]) <= best_mae + tolerance
    ]
    acceptable.sort(key=lambda item: (int_number(item[1]["feature_count"]), numeric(item[1]["player_cv_mae"])))
    return acceptable[0]


def selected_model_payload(
    args: argparse.Namespace,
    selected_source: str,
    selected_features: list[str],
    summary: dict[str, Any],
) -> dict[str, Any]:
    return {
        "schema_version": 1,
        "model_name": args.selected_prefix,
        "target": "continuous_rank_value",
        "prediction": {
            "feature_order": selected_features,
            "model_file": f"{args.selected_prefix}_booster.json",
            "median_cv_bias_correction": summary["median_cv_bias_correction"],
            "global_prediction_sigma": summary["global_prediction_sigma"],
            "variance_method": "global conformal sigma calibrated from player-CV residuals",
        },
        "hyperparameters": {
            "source": selected_source,
            "objective": "reg:absoluteerror",
            "rounds": args.rounds,
            "depth": args.depth,
            "eta": args.eta,
            "subsample": args.subsample,
            "colsample": args.colsample,
            "min_child_weight": args.min_child_weight,
            "reg_lambda": args.reg_lambda,
            "reg_alpha": args.reg_alpha,
        },
        "summary": summary,
    }


def prediction_rows_from_arrays(
    rows: list[dict[str, Any]],
    target: np.ndarray,
    fitted_prediction: np.ndarray,
    cv_prediction: np.ndarray,
    sigma: np.ndarray,
) -> list[dict[str, Any]]:
    result = []
    for index, row in enumerate(rows):
        point = float(fitted_prediction[index])
        std = float(sigma[index])
        cv_error = float(cv_prediction[index] - target[index])
        result.append(
            {
                "sgf": row.get("sgf", ""),
                "sample_source": row.get("sample_source", ""),
                "side": row.get("side", ""),
                "player": row.get("player", ""),
                "fox_rank": row.get("fox_rank", ""),
                "target_rank_value": round(float(target[index]), 3),
                "predicted_rank_value": round(point, 3),
                "predicted_rank_1dp": round(point, 1),
                "predicted_label": rank_value_label(point),
                "prediction_variance": round(std * std, 4),
                "prediction_sigma": round(std, 4),
                "prediction_interval_80": interval_text(point, std, Z80),
                "prediction_interval_90": interval_text(point, std, Z90),
                "player_cv_predicted_rank_value": round(float(cv_prediction[index]), 3),
                "player_cv_error": round(cv_error, 3),
                "player_cv_abs_error": round(abs(cv_error), 3),
                "model_group": calibration.model_group(row),
            }
        )
    return result


def feature_selection_report(
    single_rows: list[dict[str, Any]],
    cumulative_rows: list[dict[str, Any]],
    greedy_search_rows: list[dict[str, Any]],
    greedy_final_rows: list[dict[str, Any]],
    selected_source: str,
    selected_metrics: dict[str, Any],
    selected_features: list[str],
) -> str:
    lines = [
        "# XGBoost Feature Selection",
        "",
        "## Selected Subset",
        "",
        f"- source: {selected_source}",
        f"- feature_count: {len(selected_features)}",
        f"- player_cv_mae: {selected_metrics['player_cv_mae']}",
        f"- player_cv_rmse: {selected_metrics['player_cv_rmse']}",
        f"- p80_abs_error: {selected_metrics['player_cv_abs_error_p80']}",
        f"- p90_abs_error: {selected_metrics['player_cv_abs_error_p90']}",
        f"- features: {';'.join(selected_features)}",
        "",
        "## Top Single Features",
        "",
    ]
    lines.extend(markdown_table(single_rows[:12], ["rank", "feature", "player_cv_mae", "player_cv_rmse", "player_cv_abs_error_p80", "player_cv_abs_error_p90"]))
    lines.extend(["", "## Cumulative Importance Order", ""])
    lines.extend(markdown_table(cumulative_rows, ["feature_count", "added_feature", "player_cv_mae", "player_cv_rmse", "player_cv_abs_error_p80", "player_cv_abs_error_p90"]))
    lines.extend(["", "## Greedy Forward Search", ""])
    lines.extend(markdown_table(greedy_search_rows, ["step", "added_feature", "player_cv_mae", "player_cv_rmse", "player_cv_abs_error_p80", "player_cv_abs_error_p90"]))
    lines.extend(["", "## Greedy Prefix Final Evaluation", ""])
    lines.extend(markdown_table(greedy_final_rows, ["feature_count", "added_feature", "player_cv_mae", "player_cv_rmse", "player_cv_abs_error_p80", "player_cv_abs_error_p90"]))
    return "\n".join(lines) + "\n"


def markdown_table(rows: list[dict[str, Any]], fields: list[str]) -> list[str]:
    if not rows:
        return ["_无数据。_"]
    result = ["| " + " | ".join(fields) + " |", "| " + " | ".join("---" for _ in fields) + " |"]
    for row in rows:
        result.append("| " + " | ".join(clean_cell(row.get(field, "")) for field in fields) + " |")
    return result


def is_clean_csv_row(row: dict[str, Any]) -> bool:
    if truthy(row.get("residual_core_four_outlier")):
        return False
    if truthy(row.get("metric_rank_reassigned")):
        return True
    return not truthy(row.get("statistical_outlier")) and not truthy(row.get("rank_mismatch_candidate"))


def truthy(value: Any) -> bool:
    return str(value).strip().lower() in {"1", "true", "yes", "y"}


def numeric(value: Any) -> float:
    text = str(value if value is not None else "").strip()
    try:
        return float(text)
    except ValueError:
        return 0.0


def int_number(value: Any) -> int:
    try:
        return int(float(str(value if value is not None else "0")))
    except ValueError:
        return 0


def clean_cell(value: Any) -> str:
    return str(value if value is not None else "").replace("|", "/").replace("\n", " ")


def rank_value_label(value: float) -> str:
    value = max(1.0, min(12.0, float(value)))
    if value < 10.0:
        return f"{value:.1f}d"
    if value < 11.0:
        return f"{value:.1f}d-pro"
    if value < 12.0:
        return f"{value:.1f}d-top-pro"
    return "12.0d-AI"


def interval_text(center: float, sigma: float, z_value: float) -> str:
    low = max(1.0, min(12.0, center - z_value * sigma))
    high = max(1.0, min(12.0, center + z_value * sigma))
    return f"{low:.1f}-{high:.1f}"


def read_csv(path: Path) -> list[dict[str, Any]]:
    with path.open(encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle))


def write_csv(path: Path, rows: list[dict[str, Any]]) -> None:
    if not rows:
        path.write_text("", encoding="utf-8")
        return
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


if __name__ == "__main__":
    raise SystemExit(main())

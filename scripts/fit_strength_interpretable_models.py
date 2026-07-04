#!/usr/bin/env python3
"""Fit interpretable strength models: XGBoost and fuzzy rank centroids."""

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
    parser.add_argument("--model", choices=["xgboost", "fuzzy"], required=True)
    parser.add_argument("--output-prefix", required=True)
    parser.add_argument("--seed", type=int, default=20260609)
    parser.add_argument(
        "--features",
        choices=["full", "core4"],
        default="full",
        help="Feature set used by the model.",
    )
    parser.add_argument(
        "--sample-weight",
        choices=["none", "rank", "group"],
        default="none",
        help="Optional training weights for XGBoost.",
    )
    parser.add_argument("--xgb-objective", default="reg:absoluteerror")
    parser.add_argument("--xgb-rounds", type=int, default=220)
    parser.add_argument("--xgb-depth", type=int, default=2)
    parser.add_argument("--xgb-eta", type=float, default=0.04)
    parser.add_argument("--xgb-subsample", type=float, default=0.85)
    parser.add_argument("--xgb-colsample", type=float, default=0.9)
    parser.add_argument("--xgb-min-child-weight", type=float, default=3.0)
    parser.add_argument("--xgb-reg-lambda", type=float, default=3.0)
    parser.add_argument("--xgb-reg-alpha", type=float, default=0.0)
    parser.add_argument("--fuzzy-bandwidth", type=float, default=2.0)
    parser.add_argument(
        "--fuzzy-prior",
        choices=["uniform", "empirical", "sqrt"],
        default="uniform",
        help="Class prior used in fuzzy membership logits.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    analysis_dir = Path(args.analysis_dir)
    rows = [row for row in read_csv(analysis_dir / "calibration_rows.csv") if is_clean_csv_row(row)]
    if len(rows) < 100:
        print("[error] not enough clean rows", file=sys.stderr)
        return 1

    feature_names = feature_name_list(args.features)
    x = np.array([feature_values(row, args.features) for row in rows], dtype=float)
    y = np.array([calibration.model_rank_value(row) for row in rows], dtype=float)
    ranks = np.array([int(round(calibration.model_rank_value(row))) for row in rows], dtype=int)
    groups = np.array([calibration.player_group_key(row) for row in rows])

    if args.model == "xgboost":
        fitted, fitted_prediction, importance_rows = fit_xgboost_and_predict(
            x,
            y,
            ranks,
            feature_names,
            args,
        )
        cv_prediction = grouped_xgboost_predictions(x, y, ranks, groups, feature_names, args)
        model_payload = xgboost_payload(fitted, feature_names, args)
        extra_files = {
            f"{args.output_prefix}_feature_importance.csv": importance_rows,
        }
        if fitted is not None:
            booster_path = analysis_dir / f"{args.output_prefix}_booster.json"
            fitted.save_model(str(booster_path))
    else:
        fitted = fit_fuzzy_model(x, y, ranks, feature_names, args)
        fitted_prediction, _ = fitted.predict(x)
        cv_prediction = grouped_fuzzy_predictions(x, y, ranks, groups, feature_names, args)
        model_payload = fuzzy_payload(fitted, feature_names, args)
        extra_files = {
            f"{args.output_prefix}_rank_profiles.csv": fuzzy_profile_rows(fitted, feature_names),
        }

    cv_error = cv_prediction - y
    bias_correction = float(np.median(cv_error))
    cv_prediction = cv_prediction - bias_correction
    fitted_prediction = fitted_prediction - bias_correction
    cv_error = cv_prediction - y
    sigma = calibrated_global_sigma(np.abs(cv_error))
    summary = summary_row(
        model_name=args.output_prefix,
        args=args,
        rows=len(rows),
        groups=len(set(groups)),
        cv_error=cv_error,
        sigma=sigma,
        bias_correction=bias_correction,
    )
    prediction_rows = prediction_rows_from_arrays(rows, y, fitted_prediction, cv_prediction, cv_error, sigma)
    model_payload["summary"] = summary
    model_payload["prediction"]["median_cv_bias_correction"] = round(bias_correction, 10)
    model_payload["prediction"]["global_prediction_sigma"] = round(sigma, 10)

    write_csv(analysis_dir / f"{args.output_prefix}_predictions.csv", prediction_rows)
    for filename, csv_rows in extra_files.items():
        write_csv(analysis_dir / filename, csv_rows)
    (analysis_dir / f"{args.output_prefix}_model.json").write_text(
        json.dumps(model_payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    (analysis_dir / f"{args.output_prefix}_model_report.md").write_text(
        model_report(summary),
        encoding="utf-8",
    )
    print(
        f"[{args.model}] rows={summary['rows']} player_cv_mae={summary['player_cv_mae']} "
        f"rmse={summary['player_cv_rmse']}"
    )
    print(f"[{args.model}] wrote {analysis_dir / f'{args.output_prefix}_model.json'}")
    return 0


def fit_xgboost_and_predict(
    x: np.ndarray,
    y: np.ndarray,
    ranks: np.ndarray,
    feature_names: list[str],
    args: argparse.Namespace,
) -> tuple[Any, np.ndarray, list[dict[str, Any]]]:
    model = train_xgboost(x, y, ranks, feature_names, args, seed_offset=777)
    prediction = predict_xgboost(model, x, feature_names)
    return model, prediction, xgboost_importance_rows(model, x, feature_names)


def grouped_xgboost_predictions(
    x: np.ndarray,
    y: np.ndarray,
    ranks: np.ndarray,
    groups: np.ndarray,
    feature_names: list[str],
    args: argparse.Namespace,
) -> np.ndarray:
    predictions = np.zeros(len(y), dtype=float)
    folds = calibration.group_kfold_indices(groups, n_splits=min(5, len(set(groups))))
    for fold_id, test_index in enumerate(folds):
        test_index = np.array(test_index, dtype=int)
        train_mask = np.ones(len(y), dtype=bool)
        train_mask[test_index] = False
        train_index = np.where(train_mask)[0]
        model = train_xgboost(
            x[train_index],
            y[train_index],
            ranks[train_index],
            feature_names,
            args,
            seed_offset=fold_id * 1009,
        )
        predictions[test_index] = predict_xgboost(model, x[test_index], feature_names)
    return predictions


def train_xgboost(
    x: np.ndarray,
    y: np.ndarray,
    ranks: np.ndarray,
    feature_names: list[str],
    args: argparse.Namespace,
    *,
    seed_offset: int,
) -> Any:
    import xgboost as xgb

    weights = sample_weights(ranks, args.sample_weight)
    dtrain = xgb.DMatrix(x, label=y, weight=weights, feature_names=feature_names)
    params = {
        "objective": args.xgb_objective,
        "eval_metric": "mae",
        "max_depth": int(args.xgb_depth),
        "eta": float(args.xgb_eta),
        "subsample": float(args.xgb_subsample),
        "colsample_bytree": float(args.xgb_colsample),
        "min_child_weight": float(args.xgb_min_child_weight),
        "lambda": float(args.xgb_reg_lambda),
        "alpha": float(args.xgb_reg_alpha),
        "tree_method": "hist",
        "seed": int(args.seed) + seed_offset,
        "nthread": 4,
    }
    return xgb.train(params, dtrain, num_boost_round=int(args.xgb_rounds), verbose_eval=False)


def predict_xgboost(model: Any, x: np.ndarray, feature_names: list[str]) -> np.ndarray:
    import xgboost as xgb

    return np.asarray(model.predict(xgb.DMatrix(x, feature_names=feature_names)), dtype=float)


def xgboost_importance_rows(model: Any, x: np.ndarray, feature_names: list[str]) -> list[dict[str, Any]]:
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


class FuzzyCentroidModel:
    def __init__(
        self,
        *,
        feature_names: list[str],
        centers: np.ndarray,
        scales: np.ndarray,
        priors: np.ndarray,
        noise_variance: float,
        args: argparse.Namespace,
    ) -> None:
        self.feature_names = feature_names
        self.centers = centers
        self.scales = scales
        self.priors = priors
        self.noise_variance = float(noise_variance)
        self.args = args

    def predict(self, x: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        scaled = (x[:, None, :] - self.centers[None, :, :]) / self.scales[None, None, :]
        distances = np.sum(scaled * scaled, axis=2)
        logits = -0.5 * distances / max(float(self.args.fuzzy_bandwidth) ** 2, 1e-9)
        logits += np.log(np.maximum(self.priors, 1e-12))[None, :]
        logits -= np.max(logits, axis=1)[:, None]
        membership = np.exp(logits)
        membership /= np.maximum(np.sum(membership, axis=1)[:, None], 1e-12)
        rank_values = np.arange(1, 13, dtype=float)
        mean = membership @ rank_values
        variance = np.sum(membership * ((rank_values[None, :] - mean[:, None]) ** 2), axis=1)
        return mean, np.maximum(variance + self.noise_variance, 1e-9)


def fit_fuzzy_model(
    x: np.ndarray,
    y: np.ndarray,
    ranks: np.ndarray,
    feature_names: list[str],
    args: argparse.Namespace,
) -> FuzzyCentroidModel:
    global_center = np.mean(x, axis=0)
    centers = []
    counts = []
    for rank in range(1, 13):
        rank_x = x[ranks == rank]
        if len(rank_x) == 0:
            centers.append(global_center)
            counts.append(0)
        else:
            centers.append(np.mean(rank_x, axis=0))
            counts.append(len(rank_x))
    centers_array = np.asarray(centers, dtype=float)
    scales = np.maximum(np.std(x, axis=0), 1e-6)
    priors = fuzzy_priors(np.asarray(counts, dtype=float), args.fuzzy_prior)
    provisional = FuzzyCentroidModel(
        feature_names=feature_names,
        centers=centers_array,
        scales=scales,
        priors=priors,
        noise_variance=0.0,
        args=args,
    )
    prediction, _ = provisional.predict(x)
    noise_variance = float(np.mean((prediction - y) * (prediction - y)))
    return FuzzyCentroidModel(
        feature_names=feature_names,
        centers=centers_array,
        scales=scales,
        priors=priors,
        noise_variance=noise_variance,
        args=args,
    )


def grouped_fuzzy_predictions(
    x: np.ndarray,
    y: np.ndarray,
    ranks: np.ndarray,
    groups: np.ndarray,
    feature_names: list[str],
    args: argparse.Namespace,
) -> np.ndarray:
    predictions = np.zeros(len(y), dtype=float)
    folds = calibration.group_kfold_indices(groups, n_splits=min(5, len(set(groups))))
    for test_index in folds:
        test_index = np.array(test_index, dtype=int)
        train_mask = np.ones(len(y), dtype=bool)
        train_mask[test_index] = False
        train_index = np.where(train_mask)[0]
        model = fit_fuzzy_model(x[train_index], y[train_index], ranks[train_index], feature_names, args)
        fold_prediction, _ = model.predict(x[test_index])
        predictions[test_index] = fold_prediction
    return predictions


def fuzzy_priors(counts: np.ndarray, mode: str) -> np.ndarray:
    if mode == "uniform":
        return np.ones(12, dtype=float) / 12.0
    values = np.maximum(counts, 1.0)
    if mode == "sqrt":
        values = np.sqrt(values)
    return values / np.sum(values)


def fuzzy_profile_rows(model: FuzzyCentroidModel, feature_names: list[str]) -> list[dict[str, Any]]:
    rows = []
    for rank_index in range(12):
        row: dict[str, Any] = {
            "rank_value": rank_index + 1,
            "prior": round(float(model.priors[rank_index]), 8),
        }
        for feature_index, name in enumerate(feature_names):
            row[f"center_{name}"] = round(float(model.centers[rank_index, feature_index]), 8)
        rows.append(row)
    return rows


def sample_weights(ranks: np.ndarray, mode: str) -> np.ndarray:
    if mode == "none":
        return np.ones(len(ranks), dtype=float)
    if mode == "rank":
        counts = {rank: int(np.sum(ranks == rank)) for rank in range(1, 13)}
        return np.asarray([len(ranks) / (12.0 * max(counts[int(rank)], 1)) for rank in ranks], dtype=float)
    groups = np.asarray([str(calibration.rank_group_from_value(int(rank))) for rank in ranks])
    unique_groups = sorted(set(groups))
    counts = {group: int(np.sum(groups == group)) for group in unique_groups}
    return np.asarray(
        [len(groups) / (len(unique_groups) * max(counts[str(group)], 1)) for group in groups],
        dtype=float,
    )


def calibrated_global_sigma(abs_error: np.ndarray) -> float:
    return max(float(np.quantile(abs_error, 0.90) / Z90), 1e-6)


def summary_row(
    *,
    model_name: str,
    args: argparse.Namespace,
    rows: int,
    groups: int,
    cv_error: np.ndarray,
    sigma: float,
    bias_correction: float,
) -> dict[str, Any]:
    abs_error = np.abs(cv_error)
    return {
        "schema_version": 1,
        "model_name": model_name,
        "method": args.model,
        "features": args.features,
        "rows": rows,
        "player_cv_groups": groups,
        "median_cv_bias_correction": round(float(bias_correction), 3),
        "player_cv_mae": round(float(np.mean(abs_error)), 3),
        "player_cv_rmse": round(float(math.sqrt(np.mean(cv_error * cv_error))), 3),
        "player_cv_bias": round(float(np.mean(cv_error)), 3),
        "player_cv_abs_error_p50": round(float(np.quantile(abs_error, 0.50)), 3),
        "player_cv_abs_error_p80": round(float(np.quantile(abs_error, 0.80)), 3),
        "player_cv_abs_error_p90": round(float(np.quantile(abs_error, 0.90)), 3),
        "within_0_5": round(float(np.mean(abs_error <= 0.5)), 4),
        "within_1_0": round(float(np.mean(abs_error <= 1.0)), 4),
        "within_1_5": round(float(np.mean(abs_error <= 1.5)), 4),
        "within_2_0": round(float(np.mean(abs_error <= 2.0)), 4),
        "global_prediction_sigma": round(float(sigma), 3),
        "interval80_coverage": round(float(np.mean(abs_error <= Z80 * sigma)), 4),
        "interval90_coverage": round(float(np.mean(abs_error <= Z90 * sigma)), 4),
        "xgb_objective": getattr(args, "xgb_objective", ""),
        "xgb_rounds": getattr(args, "xgb_rounds", ""),
        "xgb_depth": getattr(args, "xgb_depth", ""),
        "xgb_eta": getattr(args, "xgb_eta", ""),
        "sample_weight": getattr(args, "sample_weight", ""),
        "fuzzy_bandwidth": getattr(args, "fuzzy_bandwidth", ""),
        "fuzzy_prior": getattr(args, "fuzzy_prior", ""),
    }


def prediction_rows_from_arrays(
    rows: list[dict[str, Any]],
    target: np.ndarray,
    fitted_prediction: np.ndarray,
    cv_prediction: np.ndarray,
    cv_error: np.ndarray,
    sigma: float,
) -> list[dict[str, Any]]:
    result = []
    variance = sigma * sigma
    for index, row in enumerate(rows):
        point = float(fitted_prediction[index])
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
                "prediction_variance": round(variance, 4),
                "prediction_sigma": round(sigma, 4),
                "prediction_interval_80": interval_text(point, sigma, Z80),
                "prediction_interval_90": interval_text(point, sigma, Z90),
                "player_cv_predicted_rank_value": round(float(cv_prediction[index]), 3),
                "player_cv_error": round(float(cv_error[index]), 3),
                "player_cv_abs_error": round(abs(float(cv_error[index])), 3),
                "model_group": calibration.model_group(row),
                "match_rate": row.get("match_rate", ""),
                "first_choice_rate": row.get("first_choice_rate", ""),
                "average_score_equivalent_loss": row.get("average_score_equivalent_loss", ""),
                "average_difficulty": row.get("average_difficulty", ""),
            }
        )
    return result


def xgboost_payload(model: Any, feature_names: list[str], args: argparse.Namespace) -> dict[str, Any]:
    return {
        "schema_version": 1,
        "model_name": args.output_prefix,
        "target": "continuous_rank_value",
        "prediction": {
            "feature_order": feature_names,
            "model_file": f"{args.output_prefix}_booster.json",
            "variance_method": "global conformal sigma calibrated from player-CV residuals",
        },
        "hyperparameters": {
            "objective": args.xgb_objective,
            "rounds": args.xgb_rounds,
            "depth": args.xgb_depth,
            "eta": args.xgb_eta,
            "subsample": args.xgb_subsample,
            "colsample": args.xgb_colsample,
            "min_child_weight": args.xgb_min_child_weight,
            "reg_lambda": args.xgb_reg_lambda,
            "reg_alpha": args.xgb_reg_alpha,
            "sample_weight": args.sample_weight,
        },
    }


def fuzzy_payload(model: FuzzyCentroidModel, feature_names: list[str], args: argparse.Namespace) -> dict[str, Any]:
    return {
        "schema_version": 1,
        "model_name": args.output_prefix,
        "target": "continuous_rank_value",
        "prediction": {
            "feature_order": feature_names,
            "rank_value": "weighted average of 12 rank centroids using Gaussian fuzzy memberships",
            "variance_method": "membership rank variance plus global conformal sigma calibrated from player-CV residuals",
        },
        "hyperparameters": {
            "bandwidth": args.fuzzy_bandwidth,
            "prior": args.fuzzy_prior,
        },
        "fuzzy_rules": {
            "rank_values": list(range(1, 13)),
            "priors": [round(float(value), 10) for value in model.priors],
            "scales": [round(float(value), 10) for value in model.scales],
            "centers": [
                [round(float(value), 10) for value in row]
                for row in model.centers
            ],
        },
    }


def model_report(summary: dict[str, Any]) -> str:
    lines = [
        f"# {summary['model_name']} Model",
        "",
        "## Summary",
        "",
        f"- method: {summary['method']}",
        f"- features: {summary['features']}",
        f"- rows: {summary['rows']}",
        f"- player CV groups: {summary['player_cv_groups']}",
        f"- player CV MAE: {summary['player_cv_mae']}",
        f"- player CV RMSE: {summary['player_cv_rmse']}",
        f"- within 1.0 rank: {100 * summary['within_1_0']:.1f}%",
        f"- within 2.0 ranks: {100 * summary['within_2_0']:.1f}%",
        f"- player CV p80 abs error: {summary['player_cv_abs_error_p80']}",
        f"- player CV p90 abs error: {summary['player_cv_abs_error_p90']}",
        f"- global sigma: {summary['global_prediction_sigma']}",
        f"- 80% interval coverage: {100 * summary['interval80_coverage']:.1f}%",
        f"- 90% interval coverage: {100 * summary['interval90_coverage']:.1f}%",
        "",
    ]
    return "\n".join(lines)


def feature_name_list(kind: str) -> list[str]:
    if kind == "core4":
        return calibration.core_four_regression_feature_names()
    return calibration.regression_feature_names()


def feature_values(row: dict[str, Any], kind: str) -> list[float]:
    if kind == "core4":
        return calibration.core_four_regression_features(row)
    return calibration.regression_features(row)


def is_clean_csv_row(row: dict[str, Any]) -> bool:
    if truthy(row.get("residual_core_four_outlier")):
        return False
    if truthy(row.get("metric_rank_reassigned")):
        return True
    return not truthy(row.get("statistical_outlier")) and not truthy(
        row.get("rank_mismatch_candidate")
    )


def truthy(value: Any) -> bool:
    return str(value).strip().lower() in {"1", "true", "yes", "y"}


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

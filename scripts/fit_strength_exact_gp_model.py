#!/usr/bin/env python3
"""Fit an exact Gaussian-process strength model."""

from __future__ import annotations

import argparse
import csv
import json
import math
import sys
from pathlib import Path
from typing import Any

import numpy as np
from scipy.linalg import cho_factor, cho_solve

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
    parser.add_argument(
        "--kernel",
        choices=["rbf", "matern32", "matern52", "rq"],
        default="matern32",
        help="Stationary GP kernel to use.",
    )
    parser.add_argument("--lengthscale", type=float, default=8.0)
    parser.add_argument("--signal", type=float, default=3.0)
    parser.add_argument("--noise", type=float, default=1.2)
    parser.add_argument(
        "--rq-alpha",
        type=float,
        default=1.0,
        help="Shape parameter for the rational-quadratic kernel.",
    )
    parser.add_argument(
        "--batch-size",
        type=int,
        default=512,
        help="Prediction batch size for posterior variance computation.",
    )
    parser.add_argument(
        "--feature-set",
        choices=["full", "core4"],
        default="full",
        help="Feature set to fit. core4 uses good-move rate, first-choice rate, average loss, and difficulty.",
    )
    parser.add_argument(
        "--export-cholesky",
        action="store_true",
        help="Export the lower Cholesky factor so the runtime can compute GP variance.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    analysis_dir = Path(args.analysis_dir)
    rows = [row for row in read_csv(analysis_dir / "calibration_rows.csv") if is_clean_csv_row(row)]
    if len(rows) < 100:
        print("[error] not enough clean rows for exact GP", file=sys.stderr)
        return 1

    x = np.array([model_features(row, args.feature_set) for row in rows], dtype=float)
    y = np.array([calibration.model_rank_value(row) for row in rows], dtype=float)
    groups = np.array([calibration.player_group_key(row) for row in rows])
    feature_mean = x.mean(axis=0)
    feature_scale = np.maximum(x.std(axis=0), 1e-6)
    xz = (x - feature_mean) / feature_scale

    cv_mean, cv_variance = grouped_gp_predictions(
        xz,
        y,
        groups,
        kernel_name=args.kernel,
        lengthscale=args.lengthscale,
        signal=args.signal,
        noise=args.noise,
        rq_alpha=args.rq_alpha,
    )
    raw_cv_error = cv_mean - y
    bias_correction = float(np.median(raw_cv_error))
    cv_prediction = cv_mean - bias_correction
    cv_error = cv_prediction - y
    cv_abs_error = np.abs(cv_error)
    variance_scale = calibrate_sigma_scale(cv_abs_error, np.sqrt(np.maximum(cv_variance, 1e-9)))
    calibrated_cv_variance = cv_variance * variance_scale * variance_scale

    gp = fit_exact_gp(
        xz,
        y,
        kernel_name=args.kernel,
        lengthscale=args.lengthscale,
        signal=args.signal,
        noise=args.noise,
        rq_alpha=args.rq_alpha,
    )
    fitted_prediction, fitted_variance = predict_exact_gp(
        gp,
        xz,
        batch_size=max(1, args.batch_size),
    )
    fitted_prediction = fitted_prediction - bias_correction
    fitted_variance = fitted_variance * variance_scale * variance_scale

    prediction_rows = prediction_rows_from_arrays(
        rows,
        y,
        fitted_prediction,
        fitted_variance,
        cv_prediction,
        cv_error,
        calibrated_cv_variance,
    )
    summary = summary_row(
        y,
        cv_error,
        calibrated_cv_variance,
        rows=len(rows),
        groups=len(set(groups)),
        kernel_name=args.kernel,
        lengthscale=args.lengthscale,
        signal=args.signal,
        noise=args.noise,
        rq_alpha=args.rq_alpha,
        bias_correction=bias_correction,
        variance_scale=variance_scale,
        feature_set=args.feature_set,
    )
    model_json = model_payload(
        gp,
        feature_mean,
        feature_scale,
        bias_correction,
        variance_scale,
        summary,
        args.feature_set,
        args.export_cholesky,
    )

    output_stem = "exact_gp_core4_strength" if args.feature_set == "core4" else "exact_gp_strength"
    write_csv(analysis_dir / f"{output_stem}_predictions.csv", prediction_rows)
    (analysis_dir / f"{output_stem}_model.json").write_text(
        json.dumps(model_json, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    (analysis_dir / f"{output_stem}_model_report.md").write_text(
        model_report(summary),
        encoding="utf-8",
    )
    print(f"[exact-gp] rows={summary['rows']} player_cv_mae={summary['player_cv_mae']}")
    print(f"[exact-gp] wrote {analysis_dir / f'{output_stem}_model.json'}")
    return 0


def fit_exact_gp(
    xz: np.ndarray,
    y: np.ndarray,
    *,
    kernel_name: str,
    lengthscale: float,
    signal: float,
    noise: float,
    rq_alpha: float,
) -> dict[str, Any]:
    mean = float(np.mean(y))
    kernel = kernel_matrix(xz, xz, kernel_name, lengthscale, signal, rq_alpha)
    kernel[np.diag_indices_from(kernel)] += noise * noise + 1e-6
    chol = cho_factor(kernel, lower=True, check_finite=False)
    alpha = cho_solve(chol, y - mean, check_finite=False)
    return {
        "x_train": xz,
        "mean": mean,
        "kernel_name": kernel_name,
        "lengthscale": float(lengthscale),
        "signal": float(signal),
        "noise": float(noise),
        "rq_alpha": float(rq_alpha),
        "chol": chol,
        "alpha": alpha,
    }


def predict_exact_gp(
    gp: dict[str, Any],
    xz: np.ndarray,
    *,
    batch_size: int,
) -> tuple[np.ndarray, np.ndarray]:
    means = np.zeros(len(xz), dtype=float)
    variances = np.zeros(len(xz), dtype=float)
    for start in range(0, len(xz), batch_size):
        end = min(start + batch_size, len(xz))
        block = xz[start:end]
        cross = kernel_matrix(
            block,
            gp["x_train"],
            gp["kernel_name"],
            gp["lengthscale"],
            gp["signal"],
            gp["rq_alpha"],
        )
        means[start:end] = gp["mean"] + cross @ gp["alpha"]
        solved = cho_solve(gp["chol"], cross.T, check_finite=False)
        latent_variance = gp["signal"] * gp["signal"] - np.sum(cross * solved.T, axis=1)
        variances[start:end] = np.maximum(
            latent_variance + gp["noise"] * gp["noise"],
            1e-9,
        )
    return means, variances


def grouped_gp_predictions(
    xz: np.ndarray,
    y: np.ndarray,
    groups: np.ndarray,
    *,
    kernel_name: str,
    lengthscale: float,
    signal: float,
    noise: float,
    rq_alpha: float,
) -> tuple[np.ndarray, np.ndarray]:
    predictions = np.zeros(len(y), dtype=float)
    variances = np.zeros(len(y), dtype=float)
    folds = calibration.group_kfold_indices(groups, n_splits=min(5, len(set(groups))))
    if not folds:
        folds = [list(range(len(y)))]
    for test_index in folds:
        test_index = np.array(test_index, dtype=int)
        train_mask = np.ones(len(y), dtype=bool)
        train_mask[test_index] = False
        train_index = np.where(train_mask)[0]
        gp = fit_exact_gp(
            xz[train_index],
            y[train_index],
            kernel_name=kernel_name,
            lengthscale=lengthscale,
            signal=signal,
            noise=noise,
            rq_alpha=rq_alpha,
        )
        fold_prediction, fold_variance = predict_exact_gp(
            gp,
            xz[test_index],
            batch_size=len(test_index),
        )
        predictions[test_index] = fold_prediction
        variances[test_index] = fold_variance
    return predictions, variances


def kernel_matrix(
    a: np.ndarray,
    b: np.ndarray,
    kernel_name: str,
    lengthscale: float,
    signal: float,
    rq_alpha: float,
) -> np.ndarray:
    a_norm = np.sum(a * a, axis=1)[:, None]
    b_norm = np.sum(b * b, axis=1)[None, :]
    distance_squared = np.maximum(a_norm + b_norm - 2.0 * a @ b.T, 0.0)
    scaled_distance_squared = distance_squared / (lengthscale * lengthscale)
    if kernel_name == "rbf":
        return signal * signal * np.exp(-0.5 * scaled_distance_squared)
    if kernel_name == "matern32":
        distance = np.sqrt(np.maximum(scaled_distance_squared, 0.0))
        scaled_distance = math.sqrt(3.0) * distance
        return signal * signal * (1.0 + scaled_distance) * np.exp(-scaled_distance)
    if kernel_name == "matern52":
        distance = np.sqrt(np.maximum(scaled_distance_squared, 0.0))
        scaled_distance = math.sqrt(5.0) * distance
        return (
            signal
            * signal
            * (1.0 + scaled_distance + scaled_distance * scaled_distance / 3.0)
            * np.exp(-scaled_distance)
        )
    if kernel_name == "rq":
        alpha = max(float(rq_alpha), 1e-6)
        return signal * signal * np.power(1.0 + scaled_distance_squared / (2.0 * alpha), -alpha)
    raise ValueError(f"unsupported kernel: {kernel_name}")


def kernel_formula(kernel_name: str) -> str:
    formulas = {
        "rbf": "signal^2 * exp(-0.5 * squared_distance / lengthscale^2)",
        "matern32": "signal^2 * (1 + sqrt(3) * distance / lengthscale) * exp(-sqrt(3) * distance / lengthscale)",
        "matern52": "signal^2 * (1 + sqrt(5) * r + 5r^2/3) * exp(-sqrt(5) * r), r=distance/lengthscale",
        "rq": "signal^2 * (1 + squared_distance / (2 * alpha * lengthscale^2))^-alpha",
    }
    return formulas[kernel_name]


def calibrate_sigma_scale(abs_errors: np.ndarray, sigma: np.ndarray) -> float:
    ratios = abs_errors / np.maximum(sigma, 1e-6)
    finite = ratios[np.isfinite(ratios)]
    if len(finite) == 0:
        return 1.0
    return max(float(np.quantile(finite, 0.90) / Z90), 0.25)


def prediction_rows_from_arrays(
    rows: list[dict[str, Any]],
    target: np.ndarray,
    fitted_prediction: np.ndarray,
    fitted_variance: np.ndarray,
    cv_prediction: np.ndarray,
    cv_error: np.ndarray,
    cv_variance: np.ndarray,
) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for index, row in enumerate(rows):
        point = float(fitted_prediction[index])
        sigma = math.sqrt(max(float(fitted_variance[index]), 1e-9))
        cv_sigma = math.sqrt(max(float(cv_variance[index]), 1e-9))
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
                "prediction_variance": round(float(fitted_variance[index]), 4),
                "prediction_sigma": round(sigma, 4),
                "prediction_interval_80": interval_text(point, sigma, Z80),
                "prediction_interval_90": interval_text(point, sigma, Z90),
                "player_cv_predicted_rank_value": round(float(cv_prediction[index]), 3),
                "player_cv_error": round(float(cv_error[index]), 3),
                "player_cv_abs_error": round(abs(float(cv_error[index])), 3),
                "player_cv_variance": round(float(cv_variance[index]), 4),
                "player_cv_sigma": round(cv_sigma, 4),
                "model_group": calibration.model_group(row),
                "match_rate": row.get("match_rate", ""),
                "good_move_rate": row.get("good_move_rate", ""),
                "first_choice_rate": row.get("first_choice_rate", ""),
                "average_score_equivalent_loss": row.get(
                    "average_score_equivalent_loss",
                    "",
                ),
                "average_difficulty": row.get("average_difficulty", ""),
            }
        )
    return result


def summary_row(
    target: np.ndarray,
    cv_error: np.ndarray,
    cv_variance: np.ndarray,
    *,
    rows: int,
    groups: int,
    kernel_name: str,
    lengthscale: float,
    signal: float,
    noise: float,
    rq_alpha: float,
    bias_correction: float,
    variance_scale: float,
    feature_set: str,
) -> dict[str, Any]:
    abs_error = np.abs(cv_error)
    sigma = np.sqrt(np.maximum(cv_variance, 1e-9))
    return {
        "schema_version": 1,
        "model_name": (
            "lizzie_strength_exact_gp_core4_rank"
            if feature_set == "core4"
            else "lizzie_strength_exact_gp_rank"
        ),
        "method": (
            f"Exact GaussianProcessRegressor equivalent, {kernel_name} kernel, "
            f"Cholesky solve, feature_set={feature_set}"
        ),
        "target": "continuous_rank_value",
        "feature_set": feature_set,
        "rows": rows,
        "player_cv_groups": groups,
        "kernel": kernel_name,
        "lengthscale": round(lengthscale, 6),
        "signal": round(signal, 6),
        "noise": round(noise, 6),
        "rq_alpha": round(rq_alpha, 6),
        "median_cv_bias_correction": round(float(bias_correction), 3),
        "variance_scale": round(float(variance_scale), 6),
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
        "mean_prediction_sigma": round(float(np.mean(sigma)), 3),
        "median_prediction_sigma": round(float(np.median(sigma)), 3),
        "interval80_coverage": round(float(np.mean(abs_error <= Z80 * sigma)), 4),
        "interval90_coverage": round(float(np.mean(abs_error <= Z90 * sigma)), 4),
    }


def model_payload(
    gp: dict[str, Any],
    feature_mean: np.ndarray,
    feature_scale: np.ndarray,
    bias_correction: float,
    variance_scale: float,
    summary: dict[str, Any],
    feature_set: str,
    export_cholesky: bool,
) -> dict[str, Any]:
    training_state = {
        "x_train_z": [[round(float(value), 6) for value in row] for row in gp["x_train"]],
        "alpha": [round(float(value), 10) for value in gp["alpha"]],
    }
    if export_cholesky:
        chol_matrix, lower = gp["chol"]
        lower_chol = np.tril(chol_matrix if lower else chol_matrix.T)
        training_state["cholesky_lower"] = [
            [round(float(lower_chol[row, col]), 6) for col in range(row + 1)]
            for row in range(lower_chol.shape[0])
        ]

    return {
        "schema_version": 1,
        "model_name": summary["model_name"],
        "target": "continuous_rank_value",
        "prediction": {
            "kernel": kernel_formula(gp["kernel_name"]),
            "feature_set": feature_set,
            "feature_order": model_feature_names(feature_set),
            "feature_mean": [round(float(value), 10) for value in feature_mean],
            "feature_scale": [round(float(value), 10) for value in feature_scale],
            "rank_value": "mean + k(x, X_train) @ alpha - median_cv_bias_correction",
            "prediction_variance": "calibrated predictive variance including noise",
            "median_cv_bias_correction": round(float(bias_correction), 10),
            "variance_scale": round(float(variance_scale), 10),
        },
        "hyperparameters": {
            "kernel": gp["kernel_name"],
            "lengthscale": gp["lengthscale"],
            "signal": gp["signal"],
            "noise": gp["noise"],
            "rq_alpha": gp["rq_alpha"],
            "training_mean": round(float(gp["mean"]), 10),
        },
        "training_state": training_state,
        "summary": summary,
    }


def model_report(summary: dict[str, Any]) -> str:
    lines = [
        "# Exact GP Strength Model",
        "",
        "## Summary",
        "",
        f"- rows: {summary['rows']}",
        f"- player CV groups: {summary['player_cv_groups']}",
        f"- kernel: {summary['kernel']}",
        f"- lengthscale: {summary['lengthscale']}",
        f"- signal: {summary['signal']}",
        f"- noise: {summary['noise']}",
        f"- player CV MAE: {summary['player_cv_mae']}",
        f"- player CV RMSE: {summary['player_cv_rmse']}",
        f"- within 1.0 rank: {100 * summary['within_1_0']:.1f}%",
        f"- within 2.0 ranks: {100 * summary['within_2_0']:.1f}%",
        f"- player CV p80 abs error: {summary['player_cv_abs_error_p80']}",
        f"- player CV p90 abs error: {summary['player_cv_abs_error_p90']}",
        f"- 80% interval coverage: {100 * summary['interval80_coverage']:.1f}%",
        f"- 90% interval coverage: {100 * summary['interval90_coverage']:.1f}%",
        "",
        "## Usage",
        "",
        "- Use `predicted_rank_1dp` for display, e.g. 9.1d.",
        "- Use `prediction_variance` or `prediction_sigma` for uncertainty.",
        "- The exported JSON stores training features and GP alpha for mean prediction; a runtime can recompute the kernel against `x_train_z`.",
        "",
    ]
    return "\n".join(lines)


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


def model_feature_names(feature_set: str) -> list[str]:
    if feature_set == "core4":
        return [
            "good_move_rate",
            "first_choice_rate",
            "average_score_equivalent_loss",
            "average_difficulty",
        ]
    return calibration.regression_feature_names()


def model_features(row: dict[str, Any], feature_set: str) -> list[float]:
    if feature_set == "core4":
        return [
            float(row.get("good_move_rate") or 0.0),
            float(row.get("first_choice_rate") or 0.0),
            float(row.get("average_score_equivalent_loss") or 0.0),
            float(row.get("average_difficulty") or 0.0),
        ]
    return calibration.regression_features(row)


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

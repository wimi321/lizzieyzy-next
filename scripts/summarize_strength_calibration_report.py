#!/usr/bin/env python3
"""Summarize strength-calibration analysis CSVs into a decision memo."""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
from pathlib import Path
from typing import Any


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("analysis_dir", help="Directory produced by analyze_strength_calibration.py.")
    parser.add_argument("--out", help="Output markdown path. Defaults to strength_method_recommendation.md in analysis_dir.")
    parser.add_argument("--top", type=int, default=10, help="Number of top metrics/coefs to show.")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    analysis_dir = Path(args.analysis_dir)
    if not analysis_dir.exists():
        print(f"[error] analysis directory not found: {analysis_dir}", file=sys.stderr)
        return 1
    out = Path(args.out) if args.out else analysis_dir / "strength_method_recommendation.md"

    metrics = read_csv(analysis_dir / "metric_correlations.csv")
    formula = read_csv(analysis_dir / "formula_evaluation.csv")
    regression = read_csv(analysis_dir / "clean_set_regression_summary.csv")
    slices = read_csv(analysis_dir / "clean_set_regression_by_slice.csv")
    sources = read_csv(analysis_dir / "source_evaluation.csv")
    coefficients = read_csv(analysis_dir / "clean_set_regression_coefficients.csv")
    display_policy = read_csv(analysis_dir / "strength_display_policy.csv")
    metric_reassignments = read_csv(analysis_dir / "metric_reassignment_summary.csv")
    twelve_d_style = read_csv(analysis_dir / "twelve_d_style_rule_summary.csv")
    cluster_k = read_csv(analysis_dir / "metric_cluster_k_selection.csv")
    cluster_summary = read_csv(analysis_dir / "metric_cluster_summary.csv")
    core_four_metrics = read_csv(analysis_dir / "core_four_metric_summary.csv")
    core_four_regression = read_csv(analysis_dir / "core_four_regression_summary.csv")
    core_four_coefficients = read_csv(analysis_dir / "core_four_regression_coefficients.csv")
    continuous_model = read_model_summary(analysis_dir / "continuous_strength_model.json")
    exact_gp_model = read_model_summary(analysis_dir / "exact_gp_strength_model.json")
    balanced_bayesian_model = read_model_summary(
        analysis_dir / "balanced_bayesian_strength_model.json"
    )
    ard_bayesian_model = read_model_summary(analysis_dir / "ard_bayesian_strength_model.json")

    lines = build_markdown(
        metrics=metrics,
        formula=formula,
        regression=regression,
        slices=slices,
        sources=sources,
        coefficients=coefficients,
        display_policy=display_policy,
        metric_reassignments=metric_reassignments,
        twelve_d_style=twelve_d_style,
        cluster_k=cluster_k,
        cluster_summary=cluster_summary,
        core_four_metrics=core_four_metrics,
        core_four_regression=core_four_regression,
        core_four_coefficients=core_four_coefficients,
        continuous_model=continuous_model,
        exact_gp_model=exact_gp_model,
        balanced_bayesian_model=balanced_bayesian_model,
        ard_bayesian_model=ard_bayesian_model,
        top=max(args.top, 1),
    )
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"[summary] wrote {out}")
    return 0


def read_csv(path: Path) -> list[dict[str, str]]:
    if not path.exists():
        return []
    with path.open(encoding="utf-8", errors="replace", newline="") as handle:
        return list(csv.DictReader(handle))


def read_model_summary(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    summary = payload.get("summary")
    return summary if isinstance(summary, dict) else {}


def build_markdown(
    *,
    metrics: list[dict[str, str]],
    formula: list[dict[str, str]],
    regression: list[dict[str, str]],
    slices: list[dict[str, str]],
    sources: list[dict[str, str]],
    coefficients: list[dict[str, str]],
    display_policy: list[dict[str, str]],
    metric_reassignments: list[dict[str, str]],
    twelve_d_style: list[dict[str, str]],
    cluster_k: list[dict[str, str]],
    cluster_summary: list[dict[str, str]],
    core_four_metrics: list[dict[str, str]],
    core_four_regression: list[dict[str, str]],
    core_four_coefficients: list[dict[str, str]],
    continuous_model: dict[str, Any],
    exact_gp_model: dict[str, Any],
    balanced_bayesian_model: dict[str, Any],
    ard_bayesian_model: dict[str, Any],
    top: int,
) -> list[str]:
    top_metrics = sorted(
        [row for row in metrics if row.get("metric") != "当前评分"],
        key=lambda row: abs(numeric(row.get("spearman_with_rank"))),
        reverse=True,
    )[:top]
    top_coefficients = sorted(
        [row for row in coefficients if row.get("feature") != "intercept"],
        key=lambda row: abs(numeric(row.get("coefficient"))),
        reverse=True,
    )[:top]
    source_slices = [
        row
        for row in sources
        if str(row.get("slice", "")).startswith("source:") and int_number(row.get("rows")) >= 20
    ]
    weak_sources = sorted(
        source_slices,
        key=lambda row: numeric(row.get("current_mean_absolute_error")),
        reverse=True,
    )[:top]
    weak_regression_slices = sorted(
        [row for row in slices if int_number(row.get("rows")) >= 20],
        key=lambda row: numeric(row.get("mean_absolute_error")),
        reverse=True,
    )[:top]
    policy_rows = sorted(
        [row for row in display_policy if int_number(row.get("rows")) >= 20],
        key=lambda row: policy_sort_key(row),
    )[:top]
    selected_cluster = next((row for row in cluster_k if truthy(row.get("selected"))), None)
    cluster_rows = sorted(
        cluster_summary,
        key=lambda row: int_number(row.get("cluster_id")),
    )
    if selected_cluster and selected_cluster.get("method") == "rank_class_centroid":
        cluster_line = (
            f"- 本轮按固定 12 段位类聚类：`k={selected_cluster.get('k')}`，"
            f"类别为 `{selected_cluster.get('classes')}`。"
        )
    elif selected_cluster and selected_cluster.get("method") == "collection_class_centroid":
        cluster_line = (
            f"- 本轮按采集数据类别固定聚类：`k={selected_cluster.get('k')}`，"
            f"类别为 `{selected_cluster.get('classes')}`。"
        )
    elif selected_cluster:
        cluster_line = f"- 本轮自动指标聚类选择 `k={selected_cluster.get('k')}`。"
    else:
        cluster_line = "- 本轮未生成稳定聚类选类结果。"
    model_lines = []
    if exact_gp_model:
        coverage90 = numeric(exact_gp_model.get("interval90_coverage")) * 100.0
        model_lines.append(
            "- 当前方差模型优先使用精确 GP "
            f"`{exact_gp_model.get('kernel', 'unknown')}` 核：棋手 CV MAE "
            f"{exact_gp_model.get('player_cv_mae')}，90% 区间覆盖 {coverage90:.1f}%。"
        )
    if balanced_bayesian_model:
        model_lines.append(
            "- 平衡增强贝叶斯回归作为线性可解释对照："
            f"{balanced_bayesian_model.get('backend')}，"
            f"增强 {balanced_bayesian_model.get('final_augmented_rows')} 方，"
            f"棋手 CV MAE {balanced_bayesian_model.get('player_cv_mae')}。"
        )

    lines = [
        "# 段位判定方法建议",
        "",
        "## 结论草案",
        "",
        "- 使用回归输出的连续 `rank_value` 作为主显示值，保留 1 位小数。",
        "- 训练前先按精确段位指标画像和固定 12 段位类中心做清洗：明显偏离原标签但稳定接近其它段位类的样本，保留原始标签并把 `model_rank_value` 调整到指标归属段位。",
        "- 额外使用申真谞 40% 12d 表现线作为高置信提升规则：同时满足吻合度、Top5、平均AI排名、平均目损、P90目损和失误率阈值的非 12d 样本，训练标签提升到 12d。",
        "- 额外报告 4 指标简化口径：吻合度、一选率、折算平均目损、当前难度；它可作为界面表格和简化回归候选，但最终是否替代全特征模型要看棋手 CV。",
        cluster_line,
        *model_lines,
        "- 大段位组只作为解释标签和置信度摘要，不应替代连续值。",
        "- 当前公式仍可作为 fallback；最终校准优先看清洗集“棋手 CV”，棋局 CV 只作为乐观参考。",
        "- 职业、顶尖职业和 AI 样本必须单独看 source/group 切片；如果它们的误差显著高于业余样本，不应和 1d-9d 共用同一线性阈值。",
        "",
        "## 当前公式表现",
        "",
    ]
    lines.extend(markdown_table(formula, ["slice", "rows", "exact_group", "within_one_group", "median_error", "mean_absolute_error"]))

    lines.extend(["", "## 指标归属清洗", ""])
    lines.extend(
        markdown_table(
            metric_reassignments,
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
    )

    lines.extend(["", "## 12d强指标规则", ""])
    lines.extend(
        markdown_table(
            twelve_d_style,
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
    )

    lines.extend(["", "## 指标聚类选类", ""])
    lines.extend(
        markdown_table(
            cluster_k,
            [
                "k",
                "selected",
                "method",
                "classes",
                "anchor_rows",
                "centroid_silhouette",
                "davies_bouldin",
                "min_cluster_rows",
                "max_cluster_rows",
                "stable",
            ],
        )
    )

    lines.extend(["", "## 指标聚类摘要", ""])
    lines.extend(
        markdown_table(
            cluster_rows,
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
                "dominant_group_share",
                "median_match_rate",
                "median_top5_rate",
                "median_average_ai_rank",
                "median_average_score_equivalent_loss",
                "median_mistake_rate",
            ],
        )
    )

    lines.extend(["", "## 回归交叉验证", ""])
    lines.extend(
        markdown_table(
            regression,
            [
                "status",
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
            ],
        )
    )

    lines.extend(["", "## 4指标核心回归", ""])
    lines.extend(
        markdown_table(
            core_four_regression,
            [
                "status",
                "features",
                "rows",
                "games",
                "player_cv_groups",
                "player_cv_mean_absolute_error",
                "player_cv_exact_group",
                "player_cv_within_one_group",
                "cv_mean_absolute_error",
                "in_sample_mean_absolute_error",
            ],
        )
    )

    lines.extend(["", "## 4指标核心系数", ""])
    lines.extend(markdown_table(core_four_coefficients, ["feature", "coefficient"]))

    variance_model_rows = []
    if continuous_model:
        variance_model_rows.append(model_summary_row("continuous_huber_local_variance", continuous_model))
    if exact_gp_model:
        variance_model_rows.append(model_summary_row("exact_gp", exact_gp_model))
    if balanced_bayesian_model:
        variance_model_rows.append(
            model_summary_row("balanced_bayesian_ridge", balanced_bayesian_model)
        )
    if ard_bayesian_model:
        variance_model_rows.append(model_summary_row("ard_bayesian_no_augmentation", ard_bayesian_model))
    lines.extend(["", "## 连续段位方差模型", ""])
    lines.extend(
        markdown_table(
            variance_model_rows,
            [
                "model",
                "backend",
                "balance",
                "target_mode",
                "augmented_rows",
                "kernel",
                "rows",
                "player_cv_groups",
                "player_cv_mae",
                "player_cv_rmse",
                "player_cv_abs_error_p80",
                "player_cv_abs_error_p90",
                "within_1_0",
                "within_2_0",
                "interval80_coverage",
                "interval90_coverage",
            ],
        )
    )

    core_four_actual = [
        row for row in core_four_metrics if row.get("label_type") == "actual_label"
    ]
    core_four_training = [
        row for row in core_four_metrics if row.get("label_type") == "clean_training_label"
    ]
    lines.extend(["", "## 4指标原标签均值", ""])
    lines.extend(
        markdown_table(
            core_four_actual,
            [
                "rank_value",
                "rank_class",
                "sides",
                "games",
                "mean_match_rate",
                "mean_first_choice_rate",
                "mean_average_score_equivalent_loss",
                "mean_average_difficulty",
            ],
        )
    )

    lines.extend(["", "## 4指标清洗训练集均值", ""])
    lines.extend(
        markdown_table(
            core_four_training,
            [
                "rank_value",
                "rank_class",
                "sides",
                "games",
                "mean_match_rate",
                "mean_first_choice_rate",
                "mean_average_score_equivalent_loss",
                "mean_average_difficulty",
            ],
        )
    )

    lines.extend(["", "## 最有用的指标", ""])
    lines.extend(markdown_table(top_metrics, ["metric", "pearson_with_rank", "spearman_with_rank", "suggested_weight_share"]))

    lines.extend(["", "## 回归影响最大的特征", ""])
    lines.extend(markdown_table(top_coefficients, ["feature", "coefficient"]))

    lines.extend(["", "## 误差较高的来源切片", ""])
    lines.extend(markdown_table(weak_sources, ["slice", "rows", "games", "actual_groups", "current_mean_absolute_error"]))

    lines.extend(["", "## 回归误差较高的切片", ""])
    lines.extend(
        markdown_table(
            weak_regression_slices,
            [
                "validation",
                "slice",
                "rows",
                "mean_absolute_error",
                "median_absolute_error",
                "exact_group",
                "within_one_group",
            ],
        )
    )

    lines.extend(["", "## 显示区间与可靠性", ""])
    lines.extend(
        markdown_table(
            policy_rows,
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
            ],
        )
    )

    lines.extend(
        [
            "",
            "## 落地产物",
            "",
            "- `strength_model_calibration.json`：机器可读模型配置，包含特征顺序、截距、系数、验证摘要和显示区间策略。",
            "- `continuous_strength_model.json`：连续段位预测模型，输出一位小数段位和局部预测方差。",
            "- `continuous_strength_predictions.csv`：清洗训练样本的连续段位预测、预测方差和 80%/90% 区间。",
            f"- `exact_gp_strength_model.json`：精确 {exact_gp_model.get('kernel', '')} 高斯过程连续段位模型，输出一位小数段位和 GP 预测方差。",
            "- `exact_gp_strength_predictions.csv`：精确 GP 的逐样本预测、方差和棋手 CV 误差。",
            "- `balanced_bayesian_strength_model.json`：弱增强平衡数据集上的 BayesianRidge 段位模型，输出贝叶斯预测方差。",
            "- `ard_bayesian_strength_model.json`：ARD 贝叶斯稀疏回归对照模型，用于判断线性贝叶斯上限。",
            "- `residual_core_four_outliers.csv`：最终类别内 4 指标仍严重偏离、已从训练集剔除的样本明细。",
            "- `clean_set_regression_predictions.csv`：逐样本交叉验证预测，可用于定位异常棋手或错误标签。",
            "- `strength_display_policy.csv`：按棋手 CV 分位误差生成的显示区间和可靠性等级。",
        ]
    )

    lines.extend(
        [
            "",
            "## 推荐落地规则",
            "",
            "1. 对每个被评估方计算同一批基础特征：Top1/Top3/Top5、平均 AI 排名、好手/失误率、分位目损、分阶段表现和难度交互项。",
            "2. 先运行精确段位指标画像和固定 12 段位类聚类清洗：可稳定重归属的样本用 `model_rank_value` 拟合；无法稳定归属的统计异常/标签不匹配样本只参与展示，不参与公式拟合。",
            "3. 用 Huber 回归输出连续 `rank_value`，界面显示为 1 位小数。",
            "4. 用 `strength_display_policy.csv` 的棋手 CV 分位误差估计不确定性；默认显示 80% 区间，复盘报告可展开 90% 区间。",
            "5. 对 `professional`、`top_professional`、`ai` 单独保留上层标签；只有在全量报告证明误差可接受时，才把它们并入同一连续数轴。",
        ]
    )
    return lines


def model_summary_row(model_name: str, summary: dict[str, Any]) -> dict[str, Any]:
    return {
        "model": model_name,
        "backend": summary.get("backend", ""),
        "balance": summary.get("balance", ""),
        "target_mode": summary.get("target_mode", ""),
        "augmented_rows": summary.get("final_augmented_rows", ""),
        "kernel": summary.get("kernel", ""),
        "rows": summary.get("rows", ""),
        "player_cv_groups": summary.get("player_cv_groups", ""),
        "player_cv_mae": summary.get("player_cv_mae", ""),
        "player_cv_rmse": summary.get("player_cv_rmse", ""),
        "player_cv_abs_error_p80": summary.get("player_cv_abs_error_p80", ""),
        "player_cv_abs_error_p90": summary.get("player_cv_abs_error_p90", ""),
        "within_1_0": summary.get("within_1_0", ""),
        "within_2_0": summary.get("within_2_0", ""),
        "interval80_coverage": summary.get("interval80_coverage", ""),
        "interval90_coverage": summary.get("interval90_coverage", ""),
    }


def markdown_table(rows: list[dict[str, str]], fields: list[str]) -> list[str]:
    if not rows:
        return ["_无数据。_"]
    result = ["| " + " | ".join(fields) + " |", "| " + " | ".join("---" for _ in fields) + " |"]
    for row in rows:
        result.append("| " + " | ".join(clean_cell(row.get(field, "")) for field in fields) + " |")
    return result


def clean_cell(value: Any) -> str:
    text = str(value if value is not None else "")
    return text.replace("\n", " ").replace("|", "/")


def numeric(value: Any) -> float:
    text = str(value if value is not None else "").strip()
    if not text:
        return 0.0
    match = re.search(r"-?\d+(?:\.\d+)?", text)
    return float(match.group(0)) if match else 0.0


def int_number(value: Any) -> int:
    try:
        return int(float(str(value or "0").strip()))
    except ValueError:
        return 0


def truthy(value: Any) -> bool:
    return str(value if value is not None else "").strip().lower() in {"1", "true", "yes"}


def policy_sort_key(row: dict[str, str]) -> tuple[int, int, str]:
    slice_name = row.get("slice", "")
    if slice_name == "all-clean":
        group = 0
    elif slice_name.startswith("source:"):
        group = 1
    elif slice_name.startswith("group:"):
        group = 2
    else:
        group = 3
    return (group, -int_number(row.get("rows")), slice_name)


if __name__ == "__main__":
    raise SystemExit(main())

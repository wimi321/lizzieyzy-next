#!/usr/bin/env python3
"""Verify a completed strength-calibration result bundle."""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")


REQUIRED_ANALYSIS_FILES = [
    "analysis.md",
    "strength_method_recommendation.md",
    "calibration_rows.csv",
    "metric_rank_reassignments.csv",
    "metric_reassignment_summary.csv",
    "twelve_d_style_rule_hits.csv",
    "twelve_d_style_rule_summary.csv",
    "metric_cluster_k_selection.csv",
    "metric_cluster_summary.csv",
    "residual_core_four_outliers.csv",
    "residual_core_four_outlier_summary.csv",
    "source_evaluation.csv",
    "metric_correlations.csv",
    "metric_distribution_by_actual_group.csv",
    "metric_distribution_by_exact_rank.csv",
    "formula_evaluation.csv",
    "clean_set_regression_summary.csv",
    "clean_set_regression_coefficients.csv",
    "clean_set_regression_predictions.csv",
    "clean_set_regression_by_slice.csv",
    "core_four_metric_summary.csv",
    "core_four_regression_summary.csv",
    "core_four_regression_coefficients.csv",
    "strength_display_policy.csv",
    "strength_model_calibration.json",
    "continuous_strength_model.json",
    "continuous_strength_predictions.csv",
    "continuous_strength_model_report.md",
    "exact_gp_strength_model.json",
    "exact_gp_strength_predictions.csv",
    "exact_gp_strength_model_report.md",
]


@dataclass
class Check:
    name: str
    status: str
    detail: str


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", help="Batch result directory, extracted archive directory, or analysis directory.")
    parser.add_argument("--run-id", default="v48-m180-a16s2", help="Run id used in generated filenames.")
    parser.add_argument(
        "--expected-min-lines",
        type=int,
        default=4000,
        help="Minimum JSONL rows expected in strict final verification.",
    )
    parser.add_argument(
        "--allow-incomplete",
        action="store_true",
        help="Allow missing final logs/JSONL so analysis-only smoke checks can pass.",
    )
    parser.add_argument("--json", action="store_true", help="Write machine-readable check results.")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    root = Path(args.path)
    checks: list[Check] = []
    if not root.exists():
        checks.append(Check("input_path", "fail", f"not found: {root}"))
        return finish(checks, args.json)

    batch_dir = locate_batch_dir(root, args.run_id)
    analysis_dir = locate_analysis_dir(root, batch_dir, args.run_id)
    checks.append(Check("batch_dir", "ok" if batch_dir else "warn", str(batch_dir or "analysis-only input")))
    checks.append(Check("analysis_dir", "ok" if analysis_dir else "fail", str(analysis_dir or "not found")))

    if batch_dir:
        verify_logs(batch_dir, args.run_id, args.allow_incomplete, checks)
        verify_jsonl(batch_dir, args.run_id, args.expected_min_lines, args.allow_incomplete, checks)

    if analysis_dir:
        verify_analysis_files(analysis_dir, checks)
        verify_regression_summary(analysis_dir, checks)
        verify_model_json(analysis_dir, checks)
        verify_display_policy(analysis_dir, checks)
        verify_recommendation(analysis_dir, checks)

    return finish(checks, args.json)


def locate_batch_dir(root: Path, run_id: str) -> Path | None:
    candidates = [root]
    if root.is_dir():
        candidates.extend(path for path in root.iterdir() if path.is_dir())
    for candidate in candidates:
        if (candidate / f"evaluation-{run_id}.jsonl").exists() or (candidate / f"evaluation-{run_id}.log").exists():
            return candidate
    return None


def locate_analysis_dir(root: Path, batch_dir: Path | None, run_id: str) -> Path | None:
    candidates = [
        root,
        root / f"analysis-{run_id}-full",
    ]
    if batch_dir:
        candidates.extend([batch_dir, batch_dir / f"analysis-{run_id}-full"])
    for candidate in candidates:
        if (candidate / "analysis.md").exists() and (candidate / "strength_method_recommendation.md").exists():
            return candidate
    return None


def verify_logs(batch_dir: Path, run_id: str, allow_incomplete: bool, checks: list[Check]) -> None:
    log_checks = [
        (f"evaluation-{run_id}.log", r"^DONE .*status=0", "main_done"),
        (f"postprocess-{run_id}.log", r"POSTPROCESS_DONE .*status=0", "postprocess_done"),
        (f"package-{run_id}.log", r"PACKAGE_DONE", "package_done"),
    ]
    for filename, pattern, name in log_checks:
        path = batch_dir / filename
        if not path.exists():
            checks.append(Check(name, "warn" if allow_incomplete else "fail", f"missing {filename}"))
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        matched = re.search(pattern, text, flags=re.MULTILINE) is not None
        checks.append(Check(name, "ok" if matched else "fail", f"{filename}: pattern {pattern!r}"))


def verify_jsonl(
    batch_dir: Path,
    run_id: str,
    expected_min_lines: int,
    allow_incomplete: bool,
    checks: list[Check],
) -> None:
    path = batch_dir / f"evaluation-{run_id}.jsonl"
    if not path.exists():
        checks.append(Check("evaluation_jsonl", "warn" if allow_incomplete else "fail", "missing JSONL"))
        return
    line_count = 0
    parse_errors = 0
    with path.open(encoding="utf-8", errors="replace") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            line_count += 1
            try:
                json.loads(line)
            except json.JSONDecodeError:
                parse_errors += 1
    if parse_errors:
        checks.append(Check("evaluation_jsonl_parse", "fail", f"{parse_errors} parse errors"))
    else:
        checks.append(Check("evaluation_jsonl_parse", "ok", f"{line_count} parsed rows"))
    if allow_incomplete:
        checks.append(Check("evaluation_jsonl_lines", "ok", f"{line_count} rows"))
    else:
        status = "ok" if line_count >= expected_min_lines else "fail"
        checks.append(Check("evaluation_jsonl_lines", status, f"{line_count} rows, expected >= {expected_min_lines}"))
    linecount_path = batch_dir / f"evaluation-{run_id}.linecount"
    if linecount_path.exists():
        recorded = first_int(linecount_path.read_text(encoding="utf-8", errors="replace"))
        status = "ok" if recorded == line_count else "fail"
        checks.append(Check("evaluation_linecount_file", status, f"recorded={recorded}, actual={line_count}"))
    elif not allow_incomplete:
        checks.append(Check("evaluation_linecount_file", "fail", "missing linecount file"))


def verify_analysis_files(analysis_dir: Path, checks: list[Check]) -> None:
    missing = [filename for filename in REQUIRED_ANALYSIS_FILES if not (analysis_dir / filename).exists()]
    checks.append(
        Check(
            "required_analysis_files",
            "ok" if not missing else "fail",
            "all present" if not missing else "missing: " + ", ".join(missing),
        )
    )


def verify_regression_summary(analysis_dir: Path, checks: list[Check]) -> None:
    rows = read_csv(analysis_dir / "clean_set_regression_summary.csv")
    if not rows:
        checks.append(Check("regression_summary", "fail", "empty or missing"))
        return
    row = rows[0]
    required = [
        "status",
        "cv_mean_absolute_error",
        "player_cv_mean_absolute_error",
        "player_cv_exact_group",
        "player_cv_within_one_group",
    ]
    missing = [field for field in required if not row.get(field)]
    status = "ok" if row.get("status") == "ok" and not missing else "fail"
    checks.append(
        Check(
            "regression_summary",
            status,
            f"status={row.get('status')}, player_cv_mae={row.get('player_cv_mean_absolute_error')}, missing={missing}",
        )
    )


def verify_model_json(analysis_dir: Path, checks: list[Check]) -> None:
    path = analysis_dir / "strength_model_calibration.json"
    if not path.exists():
        checks.append(Check("model_json", "fail", "missing"))
        return
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        checks.append(Check("model_json", "fail", f"invalid JSON: {exc}"))
        return
    feature_order = data.get("feature_order") or []
    coefficients = data.get("coefficients") or {}
    summary = data.get("training_summary") or {}
    policy = data.get("display_policy") or []
    ok = (
        data.get("schema_version") == 1
        and data.get("target") == "continuous_rank_value"
        and len(feature_order) >= 20
        and len(feature_order) == len(coefficients)
        and summary.get("status") == "ok"
        and bool(policy)
    )
    checks.append(
        Check(
            "model_json",
            "ok" if ok else "fail",
            f"features={len(feature_order)}, coefficients={len(coefficients)}, summary={summary.get('status')}, policy={len(policy)}",
        )
    )


def verify_display_policy(analysis_dir: Path, checks: list[Check]) -> None:
    rows = read_csv(analysis_dir / "strength_display_policy.csv")
    if not rows:
        checks.append(Check("display_policy", "fail", "empty or missing"))
        return
    all_clean = next((row for row in rows if row.get("slice") == "all-clean"), None)
    ok = bool(all_clean and all_clean.get("display_interval_80") and all_clean.get("reliability"))
    checks.append(
        Check(
            "display_policy",
            "ok" if ok else "fail",
            f"rows={len(rows)}, all_clean={all_clean.get('display_interval_80') if all_clean else 'missing'}",
        )
    )


def verify_recommendation(analysis_dir: Path, checks: list[Check]) -> None:
    path = analysis_dir / "strength_method_recommendation.md"
    if not path.exists():
        checks.append(Check("recommendation_md", "fail", "missing"))
        return
    text = path.read_text(encoding="utf-8", errors="replace")
    required = ["## 指标归属清洗", "## 12d强指标规则", "## 指标聚类选类", "## 指标聚类摘要", "## 回归交叉验证", "## 4指标核心回归", "## 显示区间与可靠性", "## 落地产物", "## 推荐落地规则"]
    missing = [heading for heading in required if heading not in text]
    checks.append(
        Check(
            "recommendation_md",
            "ok" if not missing else "fail",
            "all required sections present" if not missing else "missing: " + ", ".join(missing),
        )
    )


def read_csv(path: Path) -> list[dict[str, str]]:
    if not path.exists():
        return []
    with path.open(encoding="utf-8", errors="replace", newline="") as handle:
        return list(csv.DictReader(handle))


def first_int(text: str) -> int | None:
    match = re.search(r"\d+", text)
    return int(match.group(0)) if match else None


def finish(checks: list[Check], json_output: bool) -> int:
    failures = [check for check in checks if check.status == "fail"]
    if json_output:
        print(json.dumps([check.__dict__ for check in checks], ensure_ascii=False, indent=2))
    else:
        for check in checks:
            print(f"[{check.status}] {check.name}: {check.detail}")
        print(f"SUMMARY ok={sum(1 for c in checks if c.status == 'ok')} warn={sum(1 for c in checks if c.status == 'warn')} fail={len(failures)}")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())

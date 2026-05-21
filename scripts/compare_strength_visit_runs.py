#!/usr/bin/env python3
"""Compare two strength-evaluation JSONL runs on overlapping SGF sides."""

from __future__ import annotations

import argparse
import csv
import json
import math
import re
import statistics
from pathlib import Path
from typing import Any


METRICS = [
    "quality_score",
    "first_choice_rate",
    "good_move_rate",
    "match_rate",
    "mistake_rate",
    "weighted_point_loss",
    "average_score_loss",
    "median_score_loss",
    "average_winrate_loss",
]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", help="Baseline JSONL, normally the higher-visit run.")
    parser.add_argument("candidate", help="Candidate JSONL, normally the lower-visit run.")
    parser.add_argument("--out", default="target/strength-visit-compare", help="Output directory.")
    args = parser.parse_args()

    baseline = load_rows(Path(args.baseline))
    candidate = load_rows(Path(args.candidate))
    pairs = []
    for key, base_row in baseline.items():
        cand_row = candidate.get(key)
        if cand_row:
            pairs.append((key, base_row, cand_row))
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    if not pairs:
        print("[compare] no overlapping rows")
        return 1

    detail_rows = []
    for key, base_row, cand_row in pairs:
        detail = {
            "game_key": key[0],
            "side": key[1],
            "baseline_strength": base_row.get("strength_band", ""),
            "candidate_strength": cand_row.get("strength_band", ""),
            "strength_changed": base_row.get("strength_band") != cand_row.get("strength_band"),
        }
        for metric in METRICS:
            base_value = number(base_row.get(metric))
            cand_value = number(cand_row.get(metric))
            detail[f"{metric}_baseline"] = base_value
            detail[f"{metric}_candidate"] = cand_value
            detail[f"{metric}_delta"] = cand_value - base_value
            detail[f"{metric}_abs_delta"] = abs(cand_value - base_value)
        detail_rows.append(detail)

    write_detail(out_dir / "visit_compare_rows.csv", detail_rows)
    summary_rows = summary(pairs, detail_rows)
    write_csv(
        out_dir / "visit_compare_summary.csv",
        summary_rows,
        ["metric", "mean_delta", "median_delta", "mean_abs_delta", "median_abs_delta"],
    )
    write_markdown(out_dir / "visit_compare.md", pairs, detail_rows, summary_rows, args)
    print(f"[compare] wrote {out_dir / 'visit_compare.md'}")
    return 0


def load_rows(path: Path) -> dict[tuple[str, str], dict[str, Any]]:
    rows: dict[tuple[str, str], dict[str, Any]] = {}
    with path.open(encoding="utf-8", errors="replace") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                continue
            key = (game_key(str(row.get("path") or "")), str(row.get("side") or ""))
            if key[0] and key[1]:
                rows[key] = row
    return rows


def game_key(path: str) -> str:
    match = re.search(r"_(\d{12,})\.sgf$", path)
    if match:
        return match.group(1)
    return Path(path).name


def summary(
    pairs: list[tuple[tuple[str, str], dict[str, Any], dict[str, Any]]],
    detail_rows: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    rows = []
    for metric in METRICS:
        deltas = [number(row[f"{metric}_delta"]) for row in detail_rows]
        abs_deltas = [abs(delta) for delta in deltas]
        rows.append(
            {
                "metric": metric,
                "mean_delta": round(statistics.fmean(deltas), 5),
                "median_delta": round(statistics.median(deltas), 5),
                "mean_abs_delta": round(statistics.fmean(abs_deltas), 5),
                "median_abs_delta": round(statistics.median(abs_deltas), 5),
            }
        )
    return rows


def write_detail(path: Path, rows: list[dict[str, Any]]) -> None:
    fieldnames = [
        "game_key",
        "side",
        "baseline_strength",
        "candidate_strength",
        "strength_changed",
    ]
    for metric in METRICS:
        fieldnames.extend(
            [
                f"{metric}_baseline",
                f"{metric}_candidate",
                f"{metric}_delta",
                f"{metric}_abs_delta",
            ]
        )
    write_csv(path, rows, fieldnames)


def write_csv(path: Path, rows: list[dict[str, Any]], fieldnames: list[str]) -> None:
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def write_markdown(
    path: Path,
    pairs: list[tuple[tuple[str, str], dict[str, Any], dict[str, Any]]],
    detail_rows: list[dict[str, Any]],
    summary_rows: list[dict[str, Any]],
    args: argparse.Namespace,
) -> None:
    changed = sum(1 for row in detail_rows if row["strength_changed"])
    baseline_visits = sorted({str(row.get("max_visits") or "") for _, row, _ in pairs})
    candidate_visits = sorted({str(row.get("max_visits") or "") for _, _, row in pairs})
    lines = [
        "# visits 敏感性对比",
        "",
        f"- 基准文件：`{args.baseline}`",
        f"- 候选文件：`{args.candidate}`",
        f"- 重叠方数：{len(pairs)}",
        f"- 基准 visits：{', '.join(baseline_visits)}",
        f"- 候选 visits：{', '.join(candidate_visits)}",
        f"- 档位变化：{changed}/{len(pairs)} ({pct(changed, len(pairs))})",
        "",
        "## 指标差异",
        "",
        "| 指标 | 平均差 | 中位差 | 平均绝对差 | 中位绝对差 |",
        "| --- | ---: | ---: | ---: | ---: |",
    ]
    for row in summary_rows:
        lines.append(
            "| "
            + " | ".join(
                [
                    str(row["metric"]),
                    f"{number(row['mean_delta']):.4f}",
                    f"{number(row['median_delta']):.4f}",
                    f"{number(row['mean_abs_delta']):.4f}",
                    f"{number(row['median_abs_delta']):.4f}",
                ]
            )
            + " |"
        )
    lines.extend(
        [
            "",
            "## 判定建议",
            "",
            "- 若档位变化很少，且一选率/好手率/失误率等比例指标平均绝对差在几个百分点以内，优先降低 visits 并扩大棋谱数。",
            "- 若档位变化集中在边界样本，应看总体误差表，不应为了这些边界样本单独调公式。",
        ]
    )
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def pct(count: int, total: int) -> str:
    if total <= 0:
        return "0.0%"
    return f"{count * 100.0 / total:.1f}%"


def number(value: Any) -> float:
    if value is None or value == "":
        return 0.0
    try:
        result = float(value)
    except (TypeError, ValueError):
        return 0.0
    if math.isnan(result):
        return 0.0
    return result


if __name__ == "__main__":
    raise SystemExit(main())

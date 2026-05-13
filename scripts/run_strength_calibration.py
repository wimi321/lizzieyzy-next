#!/usr/bin/env python3
"""Fetch diverse Fox games and run the strength estimator calibration pass.

The fetcher intentionally limits how many sampled games each player may
contribute. This keeps the calibration set from being dominated by one account
whose visible Fox rank may not match current playing strength.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import statistics
import subprocess
import sys
import time
from collections import Counter, deque
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import evaluate_strength_samples as evaluator
import fetch_fox_sgf_samples as fox

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")


DEFAULT_SEEDS = [
    "533170311",  # random2536
    "27851823",  # yishaochaoren
    "533123465",
    "531752855",
    "531540218",
    "530799466",
    "532282305",
    "7065166",
    "532793715",
    "531684042",
    "532940629",
    "500369778",
]

RANK_GROUP_ORDER = [
    "low_kyu",
    "mid_kyu",
    "high_kyu",
    "low_dan",
    "mid_dan",
    "high_dan",
]

SUMMARY_METRICS = [
    ("quality_score", "score"),
    ("first_choice_rate", "top1"),
    ("good_move_rate", "good"),
    ("average_difficulty", "difficulty"),
    ("weighted_point_loss", "weighted_loss"),
    ("average_point_loss", "average_loss"),
    ("mistake_rate", "mistakes"),
]

ESTIMATE_VALUE = {
    "Beginner": -18.0,
    "11-15k": -13.0,
    "6-10k": -8.0,
    "3-5k": -4.0,
    "1-2k": -1.5,
    "1-2d": 1.5,
    "3-4d": 3.5,
    "5-6d": 5.5,
    "7d": 7.0,
    "8d": 8.0,
    "9d": 9.0,
    "10d-professional": 10.0,
    "10d pro": 10.0,
    "10d职业": 10.0,
    "11d-top-professional": 11.0,
    "11d top pro": 11.0,
    "11d一线职业": 11.0,
    "12d-AI": 12.0,
    "12d AI": 12.0,
}


@dataclass(frozen=True)
class Rank:
    text: str
    value: int

    @property
    def group(self) -> str:
        if self.value < 0:
            k = -self.value
            if k >= 10:
                return "low_kyu"
            if k >= 4:
                return "mid_kyu"
            return "high_kyu"
        if self.value <= 3:
            return "low_dan"
        if self.value <= 6:
            return "mid_dan"
        return "high_dan"


@dataclass
class SelectedGame:
    path: Path
    manifest: dict[str, Any]
    black_uid: str
    white_uid: str
    black_rank: Rank
    white_rank: Rank


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Fetch a user-diverse Fox sample set and run strength calibration."
    )
    parser.add_argument("seeds", nargs="*", default=DEFAULT_SEEDS, help="Fox uid or nickname seeds")
    parser.add_argument("--out", default="target/strength-calibration-diverse", help="Output directory")
    parser.add_argument("--target-games", type=int, default=16, help="Number of SGFs to evaluate")
    parser.add_argument("--max-users", type=int, default=120, help="Maximum Fox users to inspect")
    parser.add_argument(
        "--max-games-per-user",
        type=int,
        default=1,
        help="Maximum sampled games any uid may appear in",
    )
    parser.add_argument(
        "--target-sides-per-group",
        type=int,
        default=6,
        help="Soft target for sides in each rank group before accepting extra sides",
    )
    parser.add_argument("--min-moves", type=int, default=80, help="Skip short games")
    parser.add_argument("--sleep", type=float, default=0.15, help="Delay between Fox requests")
    parser.add_argument("--include-handicap", action="store_true", help="Include handicap games")
    parser.add_argument("--crawl-only", action="store_true", help="Only fetch SGFs; do not run KataGo")
    parser.add_argument(
        "--summary-only",
        action="store_true",
        help="Rebuild summary files from an existing evaluation.jsonl in --out",
    )
    parser.add_argument("--max-moves", type=int, default=120, help="Move cap for each game during evaluation")
    parser.add_argument("--max-visits", type=int, default=24, help="KataGo visits per move")
    parser.add_argument("--katago", default=None, help="Path to katago executable")
    parser.add_argument("--model", default=None, help="Path to KataGo model")
    parser.add_argument("--config", default=None, help="Path to KataGo analysis config")
    parser.add_argument(
        "--keep-existing",
        action="store_true",
        help="Do not clear previously fetched SGFs in the output directory",
    )
    return parser.parse_args()


def normalize_rank(text: str | None) -> Rank | None:
    if not text:
        return None
    raw = str(text).strip()
    if not raw:
        return None
    lowered = raw.lower()
    digits = "".join(ch for ch in raw if ch.isdigit())
    if not digits:
        return None
    number = int(digits)
    if "k" in lowered or "级" in raw:
        if 1 <= number <= 18:
            return Rank(raw, -number)
        return None
    if "d" in lowered or "段" in raw:
        if 1 <= number <= 9:
            return Rank(raw, number)
        return None
    return None


def rank_from_game_field(value: Any) -> Rank | None:
    rank = fox.fox_rank(value)
    return normalize_rank(rank)


def player_uid(game: dict[str, Any], color: str) -> str | None:
    uid = str(game.get(f"{color}uid") or "").strip()
    if not uid or uid == "0":
        return None
    return uid


def game_ranks(game: dict[str, Any]) -> tuple[Rank, Rank] | None:
    black_rank = rank_from_game_field(game.get("blackdan"))
    white_rank = rank_from_game_field(game.get("whitedan"))
    if black_rank is None or white_rank is None:
        return None
    return black_rank, white_rank


def should_skip_game(game: dict[str, Any], args: argparse.Namespace) -> str | None:
    try:
        moves = int(game.get("movenum") or 0)
    except (TypeError, ValueError):
        moves = 0
    if moves < args.min_moves:
        return "short"
    if not args.include_handicap and str(game.get("handicap") or "0") not in {"0", ""}:
        return "handicap"
    if player_uid(game, "black") is None or player_uid(game, "white") is None:
        return "missing_uid"
    if game_ranks(game) is None:
        return "invalid_rank"
    return None


def wants_rank_group(
    black_rank: Rank,
    white_rank: Rank,
    group_counts: Counter[str],
    args: argparse.Namespace,
) -> bool:
    if args.target_sides_per_group <= 0:
        return True
    groups = [black_rank.group, white_rank.group]
    if any(group_counts[group] < args.target_sides_per_group for group in groups):
        return True
    current_min = min(group_counts[group] for group in RANK_GROUP_ORDER)
    return all(group_counts[group] == current_min for group in groups)


def clear_previous_sgfs(sgf_dir: Path) -> None:
    if not sgf_dir.exists():
        return
    for path in sgf_dir.glob("*.sgf"):
        path.unlink()


def crawl_games(args: argparse.Namespace) -> tuple[list[SelectedGame], dict[str, Any]]:
    out_dir = Path(args.out)
    sgf_dir = out_dir / "sgfs"
    sgf_dir.mkdir(parents=True, exist_ok=True)
    if not args.keep_existing:
        clear_previous_sgfs(sgf_dir)

    queue: deque[dict[str, str]] = deque()
    queued: set[str] = set()
    for seed in args.seeds:
        try:
            user = fox.resolve_user(seed)
            uid = user["uid"]
            if uid not in queued:
                queue.append(user)
                queued.add(uid)
        except Exception as exc:  # noqa: BLE001 - this is a CLI data collector.
            print(f"[warn] seed {seed!r} could not be resolved: {exc}")

    selected: list[SelectedGame] = []
    seen_users: set[str] = set()
    seen_games: set[str] = set()
    sampled_by_user: Counter[str] = Counter()
    group_counts: Counter[str] = Counter()
    skip_counts: Counter[str] = Counter()
    inspected_games = 0

    while queue and len(seen_users) < args.max_users and len(selected) < args.target_games:
        user = queue.popleft()
        uid = user["uid"]
        nickname = user["nickname"]
        if uid in seen_users:
            continue
        seen_users.add(uid)
        try:
            games = fox.fetch_chess_list(uid)
        except Exception as exc:  # noqa: BLE001
            print(f"[warn] failed to fetch list for {uid} ({nickname}): {exc}")
            continue
        print(f"[crawl] {uid} {nickname}: {len(games)} games")
        time.sleep(args.sleep)

        for game in games:
            black_uid = player_uid(game, "black")
            white_uid = player_uid(game, "white")
            if black_uid is not None and black_uid not in queued and black_uid not in seen_users:
                queue.append(
                    {
                        "uid": black_uid,
                        "nickname": str(
                            game.get("blacknick") or game.get("blackenname") or black_uid
                        ),
                        "query": black_uid,
                    }
                )
                queued.add(black_uid)
            if white_uid is not None and white_uid not in queued and white_uid not in seen_users:
                queue.append(
                    {
                        "uid": white_uid,
                        "nickname": str(
                            game.get("whitenick") or game.get("whiteenname") or white_uid
                        ),
                        "query": white_uid,
                    }
                )
                queued.add(white_uid)

            inspected_games += 1
            chessid = str(game.get("chessid") or "")
            if not chessid or chessid in seen_games:
                skip_counts["duplicate"] += 1
                continue
            seen_games.add(chessid)

            reason = should_skip_game(game, args)
            if reason is not None:
                skip_counts[reason] += 1
                continue

            black_rank, white_rank = game_ranks(game)  # type: ignore[misc]
            assert black_rank is not None and white_rank is not None
            assert black_uid is not None and white_uid is not None

            if sampled_by_user[black_uid] >= args.max_games_per_user:
                skip_counts["black_user_quota"] += 1
                continue
            if sampled_by_user[white_uid] >= args.max_games_per_user:
                skip_counts["white_user_quota"] += 1
                continue
            if not wants_rank_group(black_rank, white_rank, group_counts, args):
                skip_counts["rank_group_filled"] += 1
                continue

            try:
                sgf = fox.fetch_sgf(chessid)
            except Exception as exc:  # noqa: BLE001
                print(f"[warn] failed to fetch sgf {chessid}: {exc}")
                skip_counts["sgf_fetch_failed"] += 1
                continue
            if not sgf.strip():
                skip_counts["empty_sgf"] += 1
                continue
            filename = fox.sample_filename(game, chessid)
            path = sgf_dir / filename
            path.write_text(sgf, encoding="utf-8")
            manifest = fox.manifest_entry(user, game, path)
            selected.append(
                SelectedGame(
                    path=path,
                    manifest=manifest,
                    black_uid=black_uid,
                    white_uid=white_uid,
                    black_rank=black_rank,
                    white_rank=white_rank,
                )
            )
            sampled_by_user[black_uid] += 1
            sampled_by_user[white_uid] += 1
            group_counts[black_rank.group] += 1
            group_counts[white_rank.group] += 1
            print(
                "[pick] "
                f"{len(selected):02d}/{args.target_games} {path.name} "
                f"B={black_rank.text} W={white_rank.text}"
            )
            time.sleep(args.sleep)
            if len(selected) >= args.target_games:
                break

    manifest_path = out_dir / "manifest.jsonl"
    with manifest_path.open("w", encoding="utf-8") as handle:
        for game in selected:
            handle.write(json.dumps(game.manifest, ensure_ascii=False) + "\n")

    crawl_summary = {
        "target_games": args.target_games,
        "selected_games": len(selected),
        "selected_sides": len(selected) * 2,
        "unique_sampled_users": len(sampled_by_user),
        "inspected_users": len(seen_users),
        "inspected_games": inspected_games,
        "rank_group_sides": dict(group_counts),
        "skip_counts": dict(skip_counts),
        "manifest": str(manifest_path),
    }
    (out_dir / "crawl_summary.json").write_text(
        json.dumps(crawl_summary, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    return selected, crawl_summary


def evaluate_games(args: argparse.Namespace, games: list[SelectedGame]) -> int:
    if not games:
        return 1
    out_dir = Path(args.out)
    jsonl_path = out_dir / "evaluation.jsonl"
    script = Path(__file__).with_name("evaluate_strength_samples.py")
    cmd = [
        sys.executable,
        str(script),
        *[str(game.path) for game in games],
        "--max-games",
        str(len(games)),
        "--max-moves",
        str(args.max_moves),
        "--max-visits",
        str(args.max_visits),
        "--jsonl",
        str(jsonl_path),
    ]
    if args.katago:
        cmd.extend(["--katago", args.katago])
    if args.model:
        cmd.extend(["--model", args.model])
    if args.config:
        cmd.extend(["--config", args.config])

    print("[eval] " + " ".join(cmd))
    completed = subprocess.run(cmd, cwd=Path(__file__).resolve().parents[1])
    return completed.returncode


def actual_rank_from_row(row: dict[str, Any]) -> Rank | None:
    return normalize_rank(row.get("fox_rank"))


def estimate_value(row: dict[str, Any]) -> float | None:
    strength = str(row.get("strength") or row.get("strength_band") or "")
    if strength in ESTIMATE_VALUE:
        return ESTIMATE_VALUE[strength]
    return None


def current_strength_band(row: dict[str, Any]) -> str | None:
    try:
        if int(row.get("samples") or 0) <= 0:
            return str(row.get("strength") or row.get("strength_band") or "")
        return evaluator.strength_band(
            float(row["quality_score"]),
            float(row["weighted_point_loss"]),
            float(row.get("median_score_loss") or row.get("average_score_loss") or 0.0),
            float(row["first_choice_rate"]),
            float(row["good_move_rate"]),
            float(row["mistake_rate"]),
            float(row["average_difficulty"]),
        )
    except (KeyError, TypeError, ValueError):
        return str(row.get("strength") or row.get("strength_band") or "")


def estimate_group(value: float) -> str:
    if value < -9.5:
        return "low_kyu"
    if value < -3.5:
        return "mid_kyu"
    if value < 1.0:
        return "high_kyu"
    if value <= 3.5:
        return "low_dan"
    if value <= 6.0:
        return "mid_dan"
    return "high_dan"


def group_distance(left: str, right: str) -> int:
    return abs(RANK_GROUP_ORDER.index(left) - RANK_GROUP_ORDER.index(right))


def numeric_error(actual: Rank, estimated: float) -> float:
    return estimated - actual.value


def percentile(sorted_values: list[float], fraction: float) -> float:
    if not sorted_values:
        return 0.0
    if len(sorted_values) == 1:
        return sorted_values[0]
    position = (len(sorted_values) - 1) * fraction
    low = math.floor(position)
    high = math.ceil(position)
    if low == high:
        return sorted_values[low]
    return sorted_values[low] + (sorted_values[high] - sorted_values[low]) * (position - low)


def metric_stats(values: list[float]) -> dict[str, Any]:
    values = sorted(value for value in values if not math.isnan(value))
    if not values:
        return {
            "n": 0,
            "median": 0.0,
            "q25": 0.0,
            "q75": 0.0,
            "mean": 0.0,
        }
    return {
        "n": len(values),
        "median": percentile(values, 0.50),
        "q25": percentile(values, 0.25),
        "q75": percentile(values, 0.75),
        "mean": statistics.fmean(values),
    }


def is_noisy_candidate(row: dict[str, Any]) -> bool:
    return (
        float(row.get("samples") or 0) < 40
        or float(row.get("mistake_rate") or 0) >= 0.35
        or float(row.get("weighted_point_loss") or 0) >= 7.0
        or float(row.get("average_winrate_loss") or 0) >= 12.0
    )


def is_rank_mismatch_candidate(row: dict[str, Any], actual: Rank, estimated: float) -> bool:
    good_move_rate = float(row.get("good_move_rate") or 0.0)
    weighted_point_loss = float(
        row.get("weighted_point_loss")
        if row.get("weighted_point_loss") is not None
        else 99.0
    )
    mistake_rate = float(
        row.get("mistake_rate") if row.get("mistake_rate") is not None else 1.0
    )
    return (
        actual.value <= 0
        and estimated >= 3.0
        and good_move_rate >= 0.58
        and weighted_point_loss <= 3.8
        and mistake_rate <= 0.18
    )


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    if not path.exists():
        return rows
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def pct(numerator: int, denominator: int) -> str:
    if denominator <= 0:
        return "n/a"
    return f"{100.0 * numerator / denominator:.1f}%"


def write_summary(args: argparse.Namespace, crawl_summary: dict[str, Any]) -> None:
    out_dir = Path(args.out)
    rows = load_jsonl(out_dir / "evaluation.jsonl")
    evaluated: list[dict[str, Any]] = []
    errors: list[float] = []
    group_counts: Counter[str] = Counter()
    estimate_counts: Counter[str] = Counter()
    exact_group = 0
    within_one_group = 0
    kyu_as_dan = 0
    severe_over = 0
    noisy = 0
    mismatch_candidates = 0
    trusted_evaluated = 0
    trusted_exact_group = 0
    trusted_within_one_group = 0
    trusted_kyu_as_dan = 0
    trusted_severe_over = 0

    for row in rows:
        row["strength"] = current_strength_band(row)
        actual = actual_rank_from_row(row)
        estimated = estimate_value(row)
        if actual is None or estimated is None or math.isnan(estimated):
            continue
        actual_group = actual.group
        estimated_group = estimate_group(estimated)
        error = numeric_error(actual, estimated)
        row["sgf"] = Path(str(row.get("path") or "")).name
        row["side"] = row.get("side") or row.get("color") or ""
        row["strength"] = row.get("strength") or row.get("strength_band")
        row["score"] = row.get("score") or row.get("quality_score")
        row["average_point_loss"] = row.get("average_point_loss") or row.get(
            "average_score_loss"
        )
        row["actual_group"] = actual_group
        row["estimated_group"] = estimated_group
        row["numeric_error"] = error
        row["noisy_candidate"] = is_noisy_candidate(row)
        row["rank_mismatch_candidate"] = is_rank_mismatch_candidate(row, actual, estimated)
        evaluated.append(row)
        errors.append(error)
        group_counts[actual_group] += 1
        estimate_counts[estimated_group] += 1
        distance = group_distance(actual_group, estimated_group)
        if distance == 0:
            exact_group += 1
        if distance <= 1:
            within_one_group += 1
        if actual.value <= 0 and estimated >= 1.0:
            kyu_as_dan += 1
        if error >= 4.0:
            severe_over += 1
        if row["noisy_candidate"]:
            noisy += 1
        if row["rank_mismatch_candidate"]:
            mismatch_candidates += 1
        if not row["noisy_candidate"] and not row["rank_mismatch_candidate"]:
            trusted_evaluated += 1
            if distance == 0:
                trusted_exact_group += 1
            if distance <= 1:
                trusted_within_one_group += 1
            if actual.value <= 0 and estimated >= 1.0:
                trusted_kyu_as_dan += 1
            if error >= 4.0:
                trusted_severe_over += 1

    evaluated.sort(key=lambda item: float(item.get("numeric_error") or 0), reverse=True)

    distribution_rows: list[dict[str, Any]] = []
    for group in RANK_GROUP_ORDER:
        group_rows = [row for row in evaluated if row.get("actual_group") == group]
        for key, label in SUMMARY_METRICS:
            stats = metric_stats([float(row.get(key) or 0.0) for row in group_rows])
            distribution_rows.append(
                {
                    "actual_group": group,
                    "metric": label,
                    "n": stats["n"],
                    "q25": round(stats["q25"], 4),
                    "median": round(stats["median"], 4),
                    "q75": round(stats["q75"], 4),
                    "mean": round(stats["mean"], 4),
                }
            )

    distribution_path = out_dir / "metric_distribution_by_actual_group.csv"
    with distribution_path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=["actual_group", "metric", "n", "q25", "median", "q75", "mean"],
        )
        writer.writeheader()
        writer.writerows(distribution_rows)

    confusion_path = out_dir / "broad_group_confusion.csv"
    with confusion_path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=["actual_group", "estimated_group", "count"],
        )
        writer.writeheader()
        for actual_group in RANK_GROUP_ORDER:
            for estimated_group in RANK_GROUP_ORDER:
                count = sum(
                    1
                    for row in evaluated
                    if row.get("actual_group") == actual_group
                    and row.get("estimated_group") == estimated_group
                )
                writer.writerow(
                    {
                        "actual_group": actual_group,
                        "estimated_group": estimated_group,
                        "count": count,
                    }
                )

    csv_path = out_dir / "evaluation_summary_rows.csv"
    with csv_path.open("w", encoding="utf-8", newline="") as handle:
        fieldnames = [
            "sgf",
            "side",
            "fox_rank",
            "strength",
            "score",
            "confidence",
            "samples",
            "actual_group",
            "estimated_group",
            "numeric_error",
            "first_choice_rate",
            "good_move_rate",
            "mistake_rate",
            "average_difficulty",
            "weighted_point_loss",
            "average_point_loss",
            "median_score_loss",
            "average_winrate_loss",
            "noisy_candidate",
            "rank_mismatch_candidate",
        ]
        writer = csv.DictWriter(handle, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        for row in evaluated:
            writer.writerow(row)

    median_error = statistics.median(errors) if errors else 0.0
    mean_abs_error = statistics.fmean(abs(error) for error in errors) if errors else 0.0
    summary = {
        "evaluated_sides": len(evaluated),
        "exact_group": exact_group,
        "within_one_group": within_one_group,
        "kyu_as_dan": kyu_as_dan,
        "severe_over": severe_over,
        "noisy_candidates": noisy,
        "rank_mismatch_candidates": mismatch_candidates,
        "trusted_evaluated_sides": trusted_evaluated,
        "trusted_exact_group": trusted_exact_group,
        "trusted_within_one_group": trusted_within_one_group,
        "trusted_kyu_as_dan": trusted_kyu_as_dan,
        "trusted_severe_over": trusted_severe_over,
        "median_error": median_error,
        "mean_absolute_error": mean_abs_error,
        "actual_group_counts": dict(group_counts),
        "estimated_group_counts": dict(estimate_counts),
        "metric_distribution_csv": str(distribution_path),
        "broad_group_confusion_csv": str(confusion_path),
        "crawl": crawl_summary,
    }
    (out_dir / "evaluation_summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    worst = evaluated[:12]
    md_lines = [
        "# Player strength calibration summary",
        "",
        f"- Games selected: {crawl_summary.get('selected_games', 0)}",
        f"- Unique sampled users: {crawl_summary.get('unique_sampled_users', 0)}",
        f"- Sides evaluated: {len(evaluated)}",
        f"- Exact broad rank-group match: {exact_group}/{len(evaluated)} ({pct(exact_group, len(evaluated))})",
        f"- Within one broad rank group: {within_one_group}/{len(evaluated)} ({pct(within_one_group, len(evaluated))})",
        f"- Kyu displayed rank estimated as dan: {kyu_as_dan}/{len(evaluated)} ({pct(kyu_as_dan, len(evaluated))})",
        f"- Severe overestimates (estimated - displayed >= 4 ranks): {severe_over}/{len(evaluated)} ({pct(severe_over, len(evaluated))})",
        f"- Noisy-game candidates: {noisy}/{len(evaluated)} ({pct(noisy, len(evaluated))})",
        f"- Rank-mismatch candidates: {mismatch_candidates}/{len(evaluated)} ({pct(mismatch_candidates, len(evaluated))})",
        f"- Normal calibration rows: {trusted_evaluated}/{len(evaluated)} ({pct(trusted_evaluated, len(evaluated))})",
        f"- Normal exact broad rank-group match: {trusted_exact_group}/{trusted_evaluated} ({pct(trusted_exact_group, trusted_evaluated)})",
        f"- Normal within one broad rank group: {trusted_within_one_group}/{trusted_evaluated} ({pct(trusted_within_one_group, trusted_evaluated)})",
        f"- Normal kyu displayed rank estimated as dan: {trusted_kyu_as_dan}/{trusted_evaluated} ({pct(trusted_kyu_as_dan, trusted_evaluated)})",
        f"- Normal severe overestimates: {trusted_severe_over}/{trusted_evaluated} ({pct(trusted_severe_over, trusted_evaluated)})",
        f"- Median signed rank error: {median_error:.2f}",
        f"- Mean absolute rank error: {mean_abs_error:.2f}",
        "",
        "## Actual group counts",
        "",
    ]
    for group in RANK_GROUP_ORDER:
        md_lines.append(f"- {group}: {group_counts[group]}")
    md_lines.extend(["", "## Estimated group counts", ""])
    for group in RANK_GROUP_ORDER:
        md_lines.append(f"- {group}: {estimate_counts[group]}")
    md_lines.extend(["", "## Metric medians by actual group", ""])
    md_lines.append(
        "| Actual group | n | Score | Top1 | Good | Difficulty | Weighted loss | Avg loss | Mistakes |"
    )
    md_lines.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
    for group in RANK_GROUP_ORDER:
        group_rows = [row for row in evaluated if row.get("actual_group") == group]
        stats_by_key = {
            key: metric_stats([float(row.get(key) or 0.0) for row in group_rows])
            for key, _ in SUMMARY_METRICS
        }
        md_lines.append(
            "| "
            + " | ".join(
                [
                    group,
                    str(len(group_rows)),
                    f"{stats_by_key['quality_score']['median']:.1f}",
                    f"{stats_by_key['first_choice_rate']['median']:.0%}",
                    f"{stats_by_key['good_move_rate']['median']:.0%}",
                    f"{stats_by_key['average_difficulty']['median']:.0f}",
                    f"{stats_by_key['weighted_point_loss']['median']:.2f}",
                    f"{stats_by_key['average_point_loss']['median']:.2f}",
                    f"{stats_by_key['mistake_rate']['median']:.0%}",
                ]
            )
            + " |"
        )
    md_lines.extend(["", "## Largest overestimates", ""])
    if not worst:
        md_lines.append("- No evaluated rows.")
    else:
        md_lines.append(
            "| SGF | Side | Fox rank | Estimate | Error | Good | First | Weighted loss | Mistakes | Flags |"
        )
        md_lines.append("| --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | --- |")
        for row in worst:
            flags: list[str] = []
            if row.get("noisy_candidate"):
                flags.append("noisy")
            if row.get("rank_mismatch_candidate"):
                flags.append("rank-mismatch")
            md_lines.append(
                "| "
                + " | ".join(
                    [
                        str(row.get("sgf") or ""),
                        str(row.get("side") or ""),
                        str(row.get("fox_rank") or ""),
                        str(row.get("strength") or ""),
                        f"{float(row.get('numeric_error') or 0):.1f}",
                        f"{float(row.get('good_move_rate') or 0):.0%}",
                        f"{float(row.get('first_choice_rate') or 0):.0%}",
                        f"{float(row.get('weighted_point_loss') or 0):.2f}",
                        f"{float(row.get('mistake_rate') or 0):.0%}",
                        ", ".join(flags),
                    ]
                )
                + " |"
            )
    md_lines.extend(
        [
            "",
            "## Notes",
            "",
            "- `noisy` means the game had too few analysed moves, very high mistake rate, or very large loss metrics.",
            "- `rank-mismatch` means the displayed Fox rank is kyu but the analysed moves look consistently dan-level in this single game. Treat it as a review target, not an automatic discard.",
            "- The script limits sampled games per uid, so a single underrated or overrated account cannot dominate the calibration set.",
        ]
    )
    (out_dir / "summary.md").write_text("\n".join(md_lines) + "\n", encoding="utf-8")
    print(f"[summary] wrote {out_dir / 'summary.md'}")
    print(f"[summary] wrote {csv_path}")
    print(f"[summary] wrote {distribution_path}")
    print(f"[summary] wrote {confusion_path}")


def main() -> int:
    args = parse_args()
    if args.summary_only:
        summary_path = Path(args.out) / "crawl_summary.json"
        if not summary_path.exists():
            print(f"[error] missing crawl summary: {summary_path}")
            return 1
        crawl_summary = json.loads(summary_path.read_text(encoding="utf-8"))
        write_summary(args, crawl_summary)
        return 0
    selected, crawl_summary = crawl_games(args)
    if not selected:
        print("[error] no games were selected")
        return 1
    print(
        "[crawl] selected "
        f"{len(selected)} games, {crawl_summary.get('unique_sampled_users', 0)} unique users"
    )
    print(f"[crawl] rank groups: {crawl_summary.get('rank_group_sides', {})}")
    if args.crawl_only:
        return 0
    code = evaluate_games(args, selected)
    if code != 0:
        print(f"[error] evaluation failed with exit code {code}")
        return code
    write_summary(args, crawl_summary)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

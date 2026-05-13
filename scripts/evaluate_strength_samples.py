#!/usr/bin/env python3
"""Run local KataGo analysis on SGF samples and print strength-estimator metrics.

This is a calibration helper for the player strength estimator. It intentionally
reads dynamic SGF samples from target/ and writes optional reports to target/ so
the external test data does not become a deterministic unit-test fixture.
"""

from __future__ import annotations

import argparse
import glob
import json
import math
import re
import statistics
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable


DEFAULT_KATAGO = (
    r"D:\katago\LizzieYzy Next OpenCL\app\engines\katago\windows-x64\katago.exe"
)
DEFAULT_MODEL = r"D:\katago\LizzieYzy Next OpenCL\app\weights\default.bin.gz"
DEFAULT_CONFIG = (
    r"D:\katago\LizzieYzy Next OpenCL\app\engines\katago\configs\analysis.cfg"
)

WINRATE_TO_SCORE_LOSS = 6.0
ADDITIONAL_MOVE_ORDER = 999
MIN_DIFFICULTY_WEIGHT = 0.05
KATRAIN_ACCURACY_BASE = 0.75
RANK_SCORE_LOSS_SCALE = 2.5

EXCELLENT_SCORE_LOSS = 0.2
GREAT_SCORE_LOSS = 0.6
GOOD_SCORE_LOSS = 1.2
INACCURACY_SCORE_LOSS = 4.0
MISTAKE_SCORE_LOSS = 10.0

STRENGTH_BANDS = [
    "Beginner",
    "11-15k",
    "6-10k",
    "3-5k",
    "1-2k",
    "1-2d",
    "3-4d",
    "5-6d",
    "7d",
    "8d",
    "9d",
    "10d-professional",
    "11d-top-professional",
    "12d-AI",
]


@dataclass
class Game:
    path: Path
    black_name: str
    white_name: str
    black_rank: str
    white_rank: str
    size: int
    komi: float
    handicap: int
    moves: list[tuple[str, str]]


@dataclass
class Sample:
    color: str
    winrate_loss: float
    score_loss: float | None
    first_choice: bool
    ai_rank: int
    category: str
    score_equivalent_loss: float
    complexity: float
    adjusted_weight: float


class KataGoProcess:
    def __init__(self, katago: Path, model: Path, config: Path) -> None:
        self._next_id = 0
        args = [
            str(katago),
            "analysis",
            "-config",
            str(config),
            "-model",
            str(model),
            "-override-config",
            "nnRandomize=false",
        ]
        self._process = subprocess.Popen(
            args,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
        )

    def close(self) -> None:
        if self._process.stdin:
            self._process.stdin.close()
        try:
            self._process.wait(timeout=20)
        except subprocess.TimeoutExpired:
            self._process.terminate()
            self._process.wait(timeout=10)

    def analyze(
        self,
        moves: list[tuple[str, str]],
        *,
        rules: str,
        komi: float,
        size: int,
        max_visits: int,
    ) -> dict[str, Any]:
        request_id = str(self._next_id)
        self._next_id += 1
        request = {
            "id": request_id,
            "moves": [[color, move] for color, move in moves],
            "rules": rules,
            "komi": komi,
            "boardXSize": size,
            "boardYSize": size,
            "maxVisits": max_visits,
            "includeOwnership": False,
            "includePVVisits": False,
            "includeMovesOwnership": False,
            "overrideSettings": {"reportAnalysisWinratesAs": "SIDETOMOVE"},
        }
        if not self._process.stdin or not self._process.stdout:
            raise RuntimeError("KataGo process is not running")
        self._process.stdin.write(json.dumps(request, separators=(",", ":")) + "\n")
        self._process.stdin.flush()

        while True:
            line = self._process.stdout.readline()
            if line == "":
                raise RuntimeError("KataGo exited before returning analysis")
            line = line.strip()
            if not line or not line.startswith("{"):
                continue
            response = json.loads(line)
            if str(response.get("id")) == request_id:
                return response


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("sgfs", nargs="+", help="SGF paths or glob patterns.")
    parser.add_argument("--player", help="Only print sides whose player name contains this text.")
    parser.add_argument("--max-games", type=int, default=3, help="Maximum SGF files to analyze.")
    parser.add_argument("--max-moves", type=int, default=140, help="Maximum moves per game.")
    parser.add_argument("--max-visits", type=int, default=32, help="KataGo visits per position.")
    parser.add_argument("--rules", default="Chinese", help="KataGo rules string.")
    parser.add_argument("--include-handicap", action="store_true", help="Analyze handicap games too.")
    parser.add_argument("--katago", default=DEFAULT_KATAGO, help="Path to katago.exe.")
    parser.add_argument("--model", default=DEFAULT_MODEL, help="Path to model .bin.gz.")
    parser.add_argument("--config", default=DEFAULT_CONFIG, help="Path to KataGo analysis config.")
    parser.add_argument("--jsonl", help="Optional JSONL output path.")
    args = parser.parse_args()

    games = load_games(args.sgfs)
    if not args.include_handicap:
        games = [game for game in games if game.handicap <= 0]
    if args.player:
        games = [
            game
            for game in games
            if args.player in game.black_name or args.player in game.white_name
        ]
    games = games[: max(args.max_games, 0)]
    if not games:
        print("No SGF files matched the requested filters.", file=sys.stderr)
        return 1

    jsonl_file = None
    if args.jsonl:
        jsonl_path = Path(args.jsonl)
        jsonl_path.parent.mkdir(parents=True, exist_ok=True)
        jsonl_file = jsonl_path.open("w", encoding="utf-8")

    katago = KataGoProcess(Path(args.katago), Path(args.model), Path(args.config))
    try:
        for index, game in enumerate(games, start=1):
            print(f"[{index}/{len(games)}] {game.path.name}")
            results = evaluate_game(katago, game, args.rules, args.max_visits, args.max_moves)
            for row in results:
                if args.player and args.player not in str(row["player"]):
                    continue
                print(format_row(row))
                if jsonl_file:
                    jsonl_file.write(json.dumps(row, ensure_ascii=False) + "\n")
                    jsonl_file.flush()
    finally:
        katago.close()
        if jsonl_file:
            jsonl_file.close()
    return 0


def load_games(patterns: Iterable[str]) -> list[Game]:
    paths: list[Path] = []
    for pattern in patterns:
        matches = glob.glob(pattern)
        if matches:
            paths.extend(Path(match) for match in matches)
        else:
            paths.append(Path(pattern))
    unique_paths = sorted({path.resolve() for path in paths})
    games = []
    for path in unique_paths:
        if path.suffix.lower() != ".sgf" or not path.exists():
            continue
        games.append(parse_sgf(path))
    return games


def parse_sgf(path: Path) -> Game:
    text = path.read_text(encoding="utf-8", errors="replace")
    props = first_props(text)
    size = int(float(props.get("SZ", "19") or 19))
    moves = []
    for color, point in re.findall(r";\s*([BW])\[((?:\\.|[^\]])*)\]", text):
        moves.append((color, sgf_point_to_gtp(point, size)))
    return Game(
        path=path,
        black_name=props.get("PB", ""),
        white_name=props.get("PW", ""),
        black_rank=props.get("BR", ""),
        white_rank=props.get("WR", ""),
        size=size,
        komi=parse_komi(props.get("KM", "")),
        handicap=int(float(props.get("HA", "0") or 0)),
        moves=moves,
    )


def first_props(text: str) -> dict[str, str]:
    root_end = len(text)
    move_match = re.search(r";\s*[BW]\[", text)
    if move_match:
        root_end = move_match.start()
    root = text[:root_end]
    return {
        key: value.replace(r"\]", "]").replace(r"\\", "\\")
        for key, value in re.findall(r"([A-Z]+)\[((?:\\.|[^\]])*)\]", root)
    }


def parse_komi(raw: str) -> float:
    if not raw:
        return 7.5
    try:
        komi = float(raw)
    except ValueError:
        return 7.5
    if abs(komi) > 100:
        return komi / 50.0
    return komi


def sgf_point_to_gtp(point: str, size: int) -> str:
    if not point:
        return "pass"
    x = ord(point[0].lower()) - ord("a")
    y = ord(point[1].lower()) - ord("a")
    columns = "ABCDEFGHJKLMNOPQRSTUVWXYZ"
    if x < 0 or y < 0 or x >= size or y >= size:
        return "pass"
    return f"{columns[x]}{size - y}"


def evaluate_game(
    katago: KataGoProcess, game: Game, rules: str, max_visits: int, max_moves: int
) -> list[dict[str, Any]]:
    moves = game.moves[: min(len(game.moves), max_moves)]
    analyses = []
    for turn in range(len(moves) + 1):
        analyses.append(
            katago.analyze(
                moves[:turn],
                rules=rules,
                komi=game.komi,
                size=game.size,
                max_visits=max_visits,
            )
        )

    samples = {"B": [], "W": []}
    for index, (color, move) in enumerate(moves):
        previous = analyses[index]
        current = analyses[index + 1]
        sample = sample_move(color, move, previous, current)
        if sample:
            samples[color].append(sample)

    return [
        side_report(game, "B", samples["B"], len(moves), max_visits),
        side_report(game, "W", samples["W"], len(moves), max_visits),
    ]


def sample_move(
    color: str, played: str, previous: dict[str, Any], current: dict[str, Any]
) -> Sample | None:
    move_infos = sorted(previous.get("moveInfos") or [], key=lambda move: move.get("order", 0))
    if not move_infos:
        return None
    top = move_infos[0]
    actual_index, actual = find_actual(move_infos, played)
    if actual is not None:
        score_loss = number(top.get("scoreMean")) - number(actual.get("scoreMean"))
        winrate_loss = 100.0 * (number(top.get("winrate")) - number(actual.get("winrate")))
    else:
        score_loss = fallback_score_loss(previous, current)
        winrate_loss = fallback_winrate_loss(previous, current)
    score_equivalent_loss = positive(
        score_loss if score_loss is not None else winrate_loss / WINRATE_TO_SCORE_LOSS
    )
    complexity_value = complexity(move_infos)
    return Sample(
        color=color,
        winrate_loss=positive(winrate_loss),
        score_loss=None if score_loss is None else positive(score_loss),
        first_choice=actual_index == 0 or played.lower() == str(top.get("move", "")).lower(),
        ai_rank=actual_index if actual_index >= 0 else ADDITIONAL_MOVE_ORDER,
        category=categorize(score_equivalent_loss),
        score_equivalent_loss=score_equivalent_loss,
        complexity=complexity_value,
        adjusted_weight=clamp(
            max(complexity_value, score_equivalent_loss / INACCURACY_SCORE_LOSS),
            MIN_DIFFICULTY_WEIGHT,
            1.0,
        ),
    )


def find_actual(move_infos: list[dict[str, Any]], played: str) -> tuple[int, dict[str, Any] | None]:
    for index, move in enumerate(move_infos):
        if str(move.get("move", "")).lower() == played.lower():
            return index, move
    return -1, None


def fallback_score_loss(previous: dict[str, Any], current: dict[str, Any]) -> float | None:
    previous_score = root_score(previous)
    current_score = root_score(current)
    if previous_score is None or current_score is None:
        return None
    return current_score + previous_score


def fallback_winrate_loss(previous: dict[str, Any], current: dict[str, Any]) -> float:
    previous_winrate = 100.0 * root_winrate(previous)
    current_winrate = 100.0 * root_winrate(current)
    return current_winrate - (100.0 - previous_winrate)


def root_score(response: dict[str, Any]) -> float | None:
    info = response.get("rootInfo") or {}
    if "scoreMean" in info:
        return number(info.get("scoreMean"))
    if "scoreLead" in info:
        return number(info.get("scoreLead"))
    return None


def root_winrate(response: dict[str, Any]) -> float:
    return number((response.get("rootInfo") or {}).get("winrate"))


def complexity(move_infos: list[dict[str, Any]]) -> float:
    if len(move_infos) < 2:
        return 0.0
    top = move_infos[0]
    weighted_loss_sum = 0.0
    prior_sum = 0.0
    for fallback_order, move in enumerate(move_infos):
        order = int(move.get("order", fallback_order) or fallback_order)
        if order >= ADDITIONAL_MOVE_ORDER:
            continue
        prior = max(0.0, number(move.get("prior")))
        if prior <= 0.0:
            continue
        candidate_loss = positive(number(top.get("scoreMean")) - number(move.get("scoreMean")))
        weighted_loss_sum += candidate_loss * prior
        prior_sum += prior
    if prior_sum > 0.0:
        return clamp(weighted_loss_sum / prior_sum, 0.0, 1.0)
    second_loss = positive(number(top.get("scoreMean")) - number(move_infos[1].get("scoreMean")))
    return clamp(second_loss / GOOD_SCORE_LOSS, 0.0, 1.0)


def side_report(
    game: Game, color: str, samples: list[Sample], analyzed_moves: int, max_visits: int
) -> dict[str, Any]:
    player = game.black_name if color == "B" else game.white_name
    fox_rank = game.black_rank if color == "B" else game.white_rank
    if not samples:
        return {
            "path": str(game.path),
            "side": color,
            "player": player,
            "fox_rank": fox_rank,
            "analyzed_moves": analyzed_moves,
            "samples": 0,
            "max_visits": max_visits,
            "strength_band": "-",
        }

    score_losses = [sample.score_loss for sample in samples if sample.score_loss is not None]
    average_winrate_loss = statistics.fmean(sample.winrate_loss for sample in samples)
    average_score_loss = statistics.fmean(score_losses) if score_losses else 0.0
    median_score_loss = statistics.median(score_losses) if score_losses else 0.0
    average_score_equivalent_loss = statistics.fmean(
        sample.score_equivalent_loss for sample in samples
    )
    weighted_point_loss = weighted_loss(samples, average_score_equivalent_loss)
    first_choice_rate = rate(samples, lambda sample: sample.first_choice)
    good_move_rate = rate(samples, lambda sample: sample.category in {"excellent", "great", "good"})
    mistake_rate = rate(samples, lambda sample: sample.category in {"mistake", "blunder"})
    average_difficulty = 100.0 * statistics.fmean(sample.complexity for sample in samples)
    quality_score_value = quality_score(
        weighted_point_loss,
        average_score_equivalent_loss,
        median_score_loss if score_losses else average_score_equivalent_loss,
        first_choice_rate,
        good_move_rate,
        mistake_rate,
        average_difficulty,
    )
    band = strength_band(
        quality_score_value,
        weighted_point_loss,
        median_score_loss if score_losses else average_score_equivalent_loss,
        first_choice_rate,
        good_move_rate,
        mistake_rate,
        average_difficulty,
    )
    return {
        "path": str(game.path),
        "side": color,
        "player": player,
        "fox_rank": fox_rank,
        "analyzed_moves": analyzed_moves,
        "samples": len(samples),
        "max_visits": max_visits,
        "strength_band": band,
        "quality_score": round(quality_score_value, 1),
        "first_choice_rate": round(first_choice_rate, 4),
        "good_move_rate": round(good_move_rate, 4),
        "average_difficulty": round(average_difficulty, 1),
        "weighted_point_loss": round(weighted_point_loss, 3),
        "average_score_loss": round(average_score_loss, 3),
        "median_score_loss": round(median_score_loss, 3),
        "average_winrate_loss": round(average_winrate_loss, 3),
        "mistake_rate": round(mistake_rate, 4),
    }


def weighted_loss(samples: list[Sample], fallback: float) -> float:
    weight_sum = sum(sample.adjusted_weight for sample in samples)
    if weight_sum == 0.0:
        return fallback
    return sum(sample.score_equivalent_loss * sample.adjusted_weight for sample in samples) / weight_sum


def rate(samples: list[Sample], predicate: Any) -> float:
    return sum(1 for sample in samples if predicate(sample)) / len(samples)


def categorize(score_equivalent_loss: float) -> str:
    loss = positive(score_equivalent_loss)
    if loss < EXCELLENT_SCORE_LOSS:
        return "excellent"
    if loss < GREAT_SCORE_LOSS:
        return "great"
    if loss < GOOD_SCORE_LOSS:
        return "good"
    if loss < INACCURACY_SCORE_LOSS:
        return "inaccuracy"
    if loss < MISTAKE_SCORE_LOSS:
        return "mistake"
    return "blunder"


def quality_score(
    weighted_point_loss: float,
    average_point_loss: float,
    median_point_loss: float,
    first_choice_rate: float,
    good_move_rate: float,
    mistake_rate: float,
    average_difficulty: float,
) -> float:
    robust_point_loss = (
        0.50 * positive(weighted_point_loss)
        + 0.30 * positive(average_point_loss)
        + 0.20 * positive(median_point_loss)
    )
    loss_score = 100.0 * math.pow(KATRAIN_ACCURACY_BASE, robust_point_loss / RANK_SCORE_LOSS_SCALE)
    good_move_score = 100.0 * clamp(good_move_rate, 0.0, 1.0)
    mistake_score = 100.0 * (1.0 - clamp(mistake_rate / 0.18, 0.0, 1.0))
    first_choice_score = 100.0 * clamp((first_choice_rate - 0.20) / 0.45, 0.0, 1.0)
    difficulty_bonus = clamp((average_difficulty - 20.0) / 60.0, 0.0, 1.0) * 6.0
    return clamp(
        0.48 * loss_score
        + 0.27 * good_move_score
        + 0.15 * mistake_score
        + 0.07 * first_choice_score
        + difficulty_bonus,
        0.0,
        100.0,
    )


def strength_band(
    quality_score_value: float,
    weighted_point_loss: float,
    median_point_loss: float,
    first_choice_rate: float,
    good_move_rate: float,
    mistake_rate: float,
    average_difficulty: float,
) -> str:
    base = max(
        base_level(quality_score_value),
        elite_evidence_level(
            weighted_point_loss,
            first_choice_rate,
            good_move_rate,
            mistake_rate,
        ),
    )
    level = min(
        base,
        metric_cap_level(
            weighted_point_loss,
            median_point_loss,
            first_choice_rate,
            good_move_rate,
            mistake_rate,
        ),
    )
    level = evidence_adjusted_level(level, first_choice_rate, good_move_rate, average_difficulty)
    return STRENGTH_BANDS[level]


def base_level(score: float) -> int:
    thresholds = [
        (96.0, 13),
        (92.0, 12),
        (88.0, 11),
        (84.0, 10),
        (80.0, 9),
        (76.0, 8),
        (72.0, 7),
        (63.0, 6),
        (55.0, 5),
        (51.0, 4),
        (46.0, 3),
        (36.0, 2),
        (24.0, 1),
    ]
    for threshold, level in thresholds:
        if score >= threshold:
            return level
    return 0


def elite_evidence_level(
    weighted_point_loss: float,
    first_choice_rate: float,
    good_move_rate: float,
    mistake_rate: float,
) -> int:
    if (
        first_choice_rate >= 0.50
        and good_move_rate >= 0.86
        and mistake_rate <= 0.05
        and weighted_point_loss <= 3.20
    ):
        return 12
    if (
        first_choice_rate >= 0.45
        and good_move_rate >= 0.86
        and mistake_rate <= 0.06
        and weighted_point_loss <= 3.00
    ):
        return 11
    return 0


def metric_cap_level(
    weighted_point_loss: float,
    median_point_loss: float,
    first_choice_rate: float,
    good_move_rate: float,
    mistake_rate: float,
) -> int:
    return min(
        cap_by_first_choice(first_choice_rate),
        cap_by_good_move_rate(good_move_rate),
        cap_by_mistake_rate(mistake_rate),
        cap_by_median_loss(median_point_loss),
        cap_by_weighted_loss(weighted_point_loss),
        cap_by_evidence(weighted_point_loss, first_choice_rate, good_move_rate),
    )


def cap_by_evidence(
    weighted_point_loss: float,
    first_choice_rate: float,
    good_move_rate: float,
) -> int:
    cap_level = 13
    if good_move_rate < 0.50 and first_choice_rate < 0.22:
        cap_level = min(cap_level, 4)
    if good_move_rate < 0.62 and first_choice_rate < 0.30:
        cap_level = min(cap_level, 4)
    if weighted_point_loss > 7.50 and first_choice_rate < 0.26:
        cap_level = min(cap_level, 4)
    return cap_level


def evidence_adjusted_level(
    level: int,
    first_choice_rate: float,
    good_move_rate: float,
    average_difficulty: float,
) -> int:
    adjusted = level
    if (
        adjusted >= 9
        and average_difficulty < 25.0
        and first_choice_rate < 0.35
        and good_move_rate < 0.74
    ):
        adjusted = min(adjusted, 7)
    return adjusted


def cap_by_first_choice(value: float) -> int:
    return cap(value, [(0.62, 13), (0.50, 12), (0.46, 11), (0.42, 10), (0.38, 9), (0.34, 8), (0.30, 7), (0.24, 7), (0.18, 6), (0.12, 5), (0.06, 4)], 6)


def cap_by_good_move_rate(value: float) -> int:
    return cap(value, [(0.96, 13), (0.88, 12), (0.82, 11), (0.78, 10), (0.74, 9), (0.68, 8), (0.62, 7), (0.56, 6), (0.50, 5), (0.42, 4), (0.32, 3), (0.22, 2)], 1)


def cap_by_mistake_rate(value: float) -> int:
    for threshold, level in [
        (0.005, 13),
        (0.050, 12),
        (0.060, 11),
        (0.070, 10),
        (0.100, 9),
        (0.140, 8),
        (0.180, 7),
        (0.240, 6),
        (0.310, 5),
        (0.400, 4),
        (0.520, 3),
        (0.660, 2),
    ]:
        if value <= threshold:
            return level
    return 1


def cap_by_median_loss(value: float) -> int:
    for threshold, level in [
        (0.05, 13),
        (0.25, 12),
        (0.50, 11),
        (0.80, 10),
        (1.10, 9),
        (1.50, 8),
        (2.00, 7),
        (2.60, 6),
        (3.40, 5),
        (4.60, 4),
        (6.50, 3),
        (9.00, 2),
    ]:
        if value <= threshold:
            return level
    return 1


def cap_by_weighted_loss(value: float) -> int:
    for threshold, level in [
        (0.35, 13),
        (3.20, 12),
        (4.00, 11),
        (5.00, 10),
        (6.20, 9),
        (7.00, 8),
        (7.50, 7),
        (9.50, 6),
        (12.50, 5),
        (16.50, 4),
        (22.00, 3),
        (30.00, 2),
    ]:
        if value <= threshold:
            return level
    return 1


def cap(value: float, thresholds: list[tuple[float, int]], fallback: int) -> int:
    for threshold, level in thresholds:
        if value >= threshold:
            return level
    return fallback


def format_row(row: dict[str, Any]) -> str:
    if not row.get("samples"):
        return f"  {row['side']} {row['player']} samples=0 band=-"
    return (
        f"  {row['side']} {row['player']} Fox={row.get('fox_rank') or '-'} "
        f"=> {row['strength_band']} score={row['quality_score']:.1f} "
        f"samples={row['samples']} top1={row['first_choice_rate']:.0%} "
        f"good={row['good_move_rate']:.0%} diff={row['average_difficulty']:.0f} "
        f"weightedLoss={row['weighted_point_loss']:.2f} "
        f"avgLoss={row['average_score_loss']:.2f} "
        f"medianLoss={row['median_score_loss']:.2f} "
        f"wrLoss={row['average_winrate_loss']:.1f}% "
        f"mistake={row['mistake_rate']:.0%}"
    )


def number(value: Any) -> float:
    try:
        result = float(value)
    except (TypeError, ValueError):
        return 0.0
    if math.isfinite(result):
        return result
    return 0.0


def positive(value: float) -> float:
    return max(0.0, value)


def clamp(value: float, low: float, high: float) -> float:
    return max(low, min(high, value))


if __name__ == "__main__":
    raise SystemExit(main())

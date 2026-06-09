#!/usr/bin/env python3
"""Run local KataGo analysis on SGF samples and print strength-estimator metrics.

This is a calibration helper for the player strength estimator. It intentionally
reads dynamic SGF samples from target/ and writes optional reports to target/ so
the external test data does not become a deterministic unit-test fixture.
"""

from __future__ import annotations

import argparse
import atexit
import concurrent.futures
import glob
import json
import math
import multiprocessing
import queue
import re
import statistics
import subprocess
import sys
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")


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
OPENING_MOVE_LIMIT = 60
MIDDLEGAME_MOVE_LIMIT = 160
AI_RANK_CAP = 10
KATRAIN_ACCURACY_BASE = 0.80
RANK_SCORE_LOSS_SCALE = 3.8
REGRESSION_INTERCEPT = -41.7367695041
REGRESSION_FIRST_CHOICE_WEIGHT = 1.3491578799
REGRESSION_GOOD_MOVE_WEIGHT = 14.7836392271
REGRESSION_MATCH_WEIGHT = 8.8821937547
REGRESSION_NON_MISTAKE_WEIGHT = 16.1300737525
REGRESSION_NON_BLUNDER_WEIGHT = 8.9019792251
REGRESSION_WEIGHTED_LOSS_WEIGHT = -21.2338173555
REGRESSION_AVERAGE_LOSS_WEIGHT = 16.7874212747
REGRESSION_MEDIAN_LOSS_WEIGHT = 2.6216291730
REGRESSION_P75_LOSS_WEIGHT = -0.3683802411
REGRESSION_P90_LOSS_WEIGHT = 1.1648053972
REGRESSION_DIFFICULTY_WEIGHT = 13.1144562488
REGRESSION_FIRST_CHOICE_DIFFICULTY_WEIGHT = -10.6037172036
REGRESSION_GOOD_MOVE_DIFFICULTY_WEIGHT = 1.7609995811
REGRESSION_MATCH_DIFFICULTY_WEIGHT = -2.8472903619
DEFAULT_KATAGO_RESPONSE_TIMEOUT_SECONDS = 600

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

_WORKER_KATAGO: KataGoProcess | None = None
_WORKER_SETTINGS: dict[str, Any] = {}
_WORKER_PROGRESS_QUEUE: Any = None


class KataGoTimeoutError(RuntimeError):
    """KataGo analysis stdout stopped returning responses within the watchdog timeout."""


@dataclass
class Game:
    path: Path
    black_name: str
    white_name: str
    black_rank: str
    white_rank: str
    size: int
    rules: str
    komi: float
    handicap: int
    moves: list[tuple[str, str]]
    saved_analyses: list[dict[str, Any] | None]
    source: str = ""


@dataclass
class Sample:
    color: str
    move_number: int
    winrate_loss: float
    score_loss: float | None
    first_choice: bool
    ai_rank: int
    category: str
    score_equivalent_loss: float
    complexity: float
    adjusted_weight: float
    human_policy_ai_best: float | None = None
    human_policy_played: float | None = None
    human_policy_mistake: float | None = None
    human_policy_judged: float | None = None
    human_sl_difficulty: float | None = None


class KataGoProcess:
    def __init__(
        self,
        katago: Path,
        model: Path,
        config: Path,
        response_timeout_seconds: float,
        override_config: str = "",
        human_model: Path | None = None,
        human_sl_profile: str = "rank_9d",
        human_sl_root_symmetries: int = 2,
    ) -> None:
        self._next_id = 0
        self._response_timeout_seconds = max(float(response_timeout_seconds), 30.0)
        self._human_model = human_model
        self.human_sl_profile = human_sl_profile
        self._human_sl_root_symmetries = max(1, int(human_sl_root_symmetries))
        overrides = ["nnRandomize=false"]
        if override_config.strip():
            overrides.append(override_config.strip())
        args = [
            str(katago),
            "analysis",
            "-config",
            str(config),
            "-model",
            str(model),
        ]
        if human_model is not None:
            args.extend(["-human-model", str(human_model)])
        args.extend(["-override-config", ",".join(overrides)])
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
        self._stdout_queue: queue.Queue[str | None] = queue.Queue()
        self._stdout_thread = threading.Thread(target=self._read_stdout, daemon=True)
        self._stdout_thread.start()

    def _read_stdout(self) -> None:
        if not self._process.stdout:
            self._stdout_queue.put(None)
            return
        try:
            for line in self._process.stdout:
                self._stdout_queue.put(line)
        finally:
            self._stdout_queue.put(None)

    def close(self) -> None:
        if self._process.stdin:
            try:
                self._process.stdin.close()
            except OSError:
                pass
        try:
            self._process.wait(timeout=20)
        except subprocess.TimeoutExpired:
            self._process.terminate()
            try:
                self._process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self._process.kill()
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
        return self.analyze_many(
            [moves],
            rules=rules,
            komi=komi,
            size=size,
            max_visits=max_visits,
            batch_positions=1,
        )[0]

    def analyze_many(
        self,
        positions: list[list[tuple[str, str]]],
        *,
        rules: str,
        komi: float,
        size: int,
        max_visits: int,
        batch_positions: int,
        progress_callback: Any | None = None,
    ) -> list[dict[str, Any]]:
        if not self._process.stdin or not self._process.stdout:
            raise RuntimeError("KataGo process is not running")

        responses: list[dict[str, Any]] = []
        batch_size = max(batch_positions, 1)
        for start in range(0, len(positions), batch_size):
            chunk = positions[start : start + batch_size]
            if progress_callback:
                progress_callback(start, len(positions), min(start + len(chunk), len(positions)))
            pending: dict[str, int] = {}
            chunk_responses: list[dict[str, Any] | None] = [None] * len(chunk)
            for index, moves in enumerate(chunk):
                request_id = str(self._next_id)
                self._next_id += 1
                pending[request_id] = index
                request = self._request(
                    request_id,
                    moves,
                    rules=rules,
                    komi=komi,
                    size=size,
                    max_visits=max_visits,
                )
                self._process.stdin.write(json.dumps(request, separators=(",", ":")) + "\n")
            self._process.stdin.flush()

            while pending:
                try:
                    line = self._stdout_queue.get(timeout=self._response_timeout_seconds)
                except queue.Empty as exc:
                    raise KataGoTimeoutError(
                        "KataGo did not return analysis within "
                        f"{self._response_timeout_seconds:.0f}s; pending ids: "
                        + ",".join(sorted(pending))
                    ) from exc
                if line is None:
                    raise RuntimeError("KataGo exited before returning analysis")
                line = line.strip()
                if not line or not line.startswith("{"):
                    continue
                response = json.loads(line)
                response_id = str(response.get("id"))
                if response_id not in pending:
                    continue
                chunk_responses[pending.pop(response_id)] = response
            responses.extend(response for response in chunk_responses if response is not None)
            if progress_callback:
                progress_callback(min(start + len(chunk), len(positions)), len(positions), None)
        return responses

    def _request(
        self,
        request_id: str,
        moves: list[tuple[str, str]],
        *,
        rules: str,
        komi: float,
        size: int,
        max_visits: int,
    ) -> dict[str, Any]:
        return {
            "id": request_id,
            "moves": [[color, move] for color, move in moves],
            "rules": rules,
            "komi": komi,
            "boardXSize": size,
            "boardYSize": size,
            "maxVisits": max_visits,
            "includeOwnership": False,
            "includePolicy": self._human_model is not None,
            "includePVVisits": False,
            "includeMovesOwnership": False,
            "overrideSettings": self._override_settings(),
        }

    def _override_settings(self) -> dict[str, Any]:
        settings: dict[str, Any] = {"reportAnalysisWinratesAs": "SIDETOMOVE"}
        if self._human_model is not None:
            settings.update(
                {
                    "humanSLProfile": self.human_sl_profile,
                    "ignorePreRootHistory": False,
                    "rootNumSymmetriesToSample": self._human_sl_root_symmetries,
                }
            )
        return settings


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("sgfs", nargs="*", help="SGF paths or glob patterns.")
    parser.add_argument(
        "--paths-from-jsonl",
        action="append",
        default=[],
        help="Add unique SGF paths from previous evaluation JSONL rows.",
    )
    parser.add_argument(
        "--metadata-jsonl",
        action="append",
        default=[],
        help=(
            "JSONL files with SGF metadata/rank overrides. Each row may contain "
            "path/sgf/filename, black_rank, white_rank, rank, source, or nested black/white metadata."
        ),
    )
    parser.add_argument("--player", help="Only print sides whose player name contains this text.")
    parser.add_argument("--max-games", type=int, default=3, help="Maximum SGF files to analyze.")
    parser.add_argument("--min-moves", type=int, default=0, help="Skip games shorter than this.")
    parser.add_argument("--board-size", type=int, default=19, help="Only analyze this board size.")
    parser.add_argument(
        "--dedupe-chessid",
        action="store_true",
        help="Skip duplicate Fox chess ids when the same SGF exists in multiple cache folders.",
    )
    parser.add_argument("--max-moves", type=int, default=140, help="Maximum moves per game.")
    parser.add_argument("--max-visits", type=int, default=32, help="KataGo visits per position.")
    parser.add_argument(
        "--batch-positions",
        type=int,
        default=96,
        help="Position requests to queue before waiting for KataGo responses. Use 1 for serial mode.",
    )
    parser.add_argument(
        "--parallel-engines",
        type=int,
        default=1,
        help="Number of KataGo analysis processes to run in parallel.",
    )
    parser.add_argument(
        "--progress-interval",
        type=float,
        default=30.0,
        help="Seconds between progress updates while waiting for parallel games.",
    )
    parser.add_argument(
        "--katago-response-timeout",
        type=float,
        default=DEFAULT_KATAGO_RESPONSE_TIMEOUT_SECONDS,
        help=(
            "Seconds to wait for a KataGo response before restarting that worker and retrying "
            "the current game. This guards against live-but-stalled analysis processes."
        ),
    )
    parser.add_argument(
        "--katago-game-retries",
        type=int,
        default=3,
        help="Retry a game this many times when the KataGo process exits or stalls.",
    )
    parser.add_argument("--rules", default="Chinese", help="KataGo rules string.")
    parser.add_argument("--include-handicap", action="store_true", help="Analyze handicap games too.")
    parser.add_argument("--katago", default=DEFAULT_KATAGO, help="Path to katago.exe.")
    parser.add_argument("--model", default=DEFAULT_MODEL, help="Path to model .bin.gz.")
    parser.add_argument(
        "--human-model",
        help="Optional KataGo HumanSL model, e.g. b18c384nbt-humanv0.bin.gz.",
    )
    parser.add_argument(
        "--human-sl-profile",
        default="rank_9d",
        help="HumanSL profile used for humanPolicy, e.g. rank_9d, preaz_9d, proyear_2023.",
    )
    parser.add_argument(
        "--human-sl-root-symmetries",
        type=int,
        default=2,
        help="rootNumSymmetriesToSample used when requesting humanPolicy.",
    )
    parser.add_argument("--config", default=DEFAULT_CONFIG, help="Path to KataGo analysis config.")
    parser.add_argument(
        "--katago-override-config",
        default="",
        help=(
            "Additional comma-separated KataGo -override-config values for batch runs, "
            "for example numAnalysisThreads=8,numSearchThreadsPerAnalysisThread=2."
        ),
    )
    parser.add_argument("--jsonl", help="Optional JSONL output path.")
    parser.add_argument(
        "--reuse-sgf-analysis",
        action="store_true",
        help="Use saved Lizzie/LizzieYzy LZ/LZOP SGF analysis before requesting KataGo.",
    )
    parser.add_argument(
        "--sgf-analysis-only",
        action="store_true",
        help="Only use saved LZ/LZOP SGF analysis and do not start KataGo for missing positions.",
    )
    parser.add_argument(
        "--resume-jsonl",
        action="store_true",
        help="Append to --jsonl and skip SGF files that already have both sides recorded.",
    )
    args = parser.parse_args()
    if args.sgf_analysis_only:
        args.reuse_sgf_analysis = True

    patterns = list(args.sgfs)
    for jsonl_path in args.paths_from_jsonl:
        patterns.extend(paths_from_jsonl(Path(jsonl_path)))
    if not patterns:
        print("No SGF files or --paths-from-jsonl inputs were provided.", file=sys.stderr)
        return 1
    games = apply_game_metadata(load_games(patterns), load_game_metadata(args.metadata_jsonl))
    games = filter_games(
        games,
        include_handicap=args.include_handicap,
        min_moves=args.min_moves,
        board_size=args.board_size,
        dedupe_chessid=args.dedupe_chessid,
    )
    if args.player:
        games = [
            game
            for game in games
            if args.player in game.black_name or args.player in game.white_name
        ]
    completed_games: set[str] = set()
    if args.resume_jsonl and args.jsonl:
        completed_games = completed_game_keys(Path(args.jsonl))
        games = [game for game in games if game_key(game.path) not in completed_games]
    games = games[: max(args.max_games, 0)]
    if not games:
        print("No SGF files matched the requested filters.", file=sys.stderr)
        return 1

    jsonl_file = None
    if args.jsonl:
        jsonl_path = Path(args.jsonl)
        jsonl_path.parent.mkdir(parents=True, exist_ok=True)
        mode = "a" if args.resume_jsonl else "w"
        jsonl_file = jsonl_path.open(mode, encoding="utf-8")

    try:
        if args.sgf_analysis_only:
            evaluate_games_sgf_analysis_only(args, games, jsonl_file)
        elif args.parallel_engines <= 1:
            evaluate_games_serial(args, games, jsonl_file)
        else:
            evaluate_games_parallel(args, games, jsonl_file)
    finally:
        if jsonl_file:
            jsonl_file.close()
    return 0


def evaluate_games_sgf_analysis_only(
    args: argparse.Namespace, games: list[Game], jsonl_file: Any
) -> None:
    for index, game in enumerate(games, start=1):
        print(f"[{index}/{len(games)}] {game.path.name} (saved SGF analysis)")
        results = evaluate_game(
            None,
            game,
            args.rules,
            args.max_visits,
            args.max_moves,
            args.batch_positions,
            reuse_sgf_analysis=True,
            sgf_analysis_only=True,
        )
        write_results(results, args.player, jsonl_file)


def evaluate_games_serial(args: argparse.Namespace, games: list[Game], jsonl_file: Any) -> None:
    katago = make_katago_process(args)
    try:
        for index, game in enumerate(games, start=1):
            print(f"[{index}/{len(games)}] {game.path.name}")
            results, katago = evaluate_game_serial_with_retries(
                args,
                katago,
                game,
            )
            write_results(results, args.player, jsonl_file)
    finally:
        katago.close()


def make_katago_process(args: argparse.Namespace) -> KataGoProcess:
    human_model = Path(args.human_model) if args.human_model else None
    return KataGoProcess(
        Path(args.katago),
        Path(args.model),
        Path(args.config),
        float(args.katago_response_timeout),
        str(args.katago_override_config),
        human_model,
        str(args.human_sl_profile),
        int(args.human_sl_root_symmetries),
    )


def evaluate_game_serial_with_retries(
    args: argparse.Namespace, katago: KataGoProcess, game: Game
) -> tuple[list[dict[str, Any]], KataGoProcess]:
    attempts = max(1, int(args.katago_game_retries))
    for attempt in range(1, attempts + 1):
        try:
            return (
                evaluate_game(
                    katago,
                    game,
                    args.rules,
                    args.max_visits,
                    args.max_moves,
                    args.batch_positions if attempt == 1 else 1,
                    reuse_sgf_analysis=args.reuse_sgf_analysis,
                ),
                katago,
            )
        except (KataGoTimeoutError, RuntimeError, BrokenPipeError) as exc:
            if not is_retryable_katago_error(exc):
                raise
            try:
                katago.close()
            finally:
                katago = make_katago_process(args)
            if attempt >= attempts:
                print(
                    f"[error] skipping {game.path.name}: KataGo failed after {attempts} attempts: {exc}",
                    file=sys.stderr,
                    flush=True,
                )
                return [], katago
            print(
                f"[warn] KataGo failed on {game.path.name}: {exc}; "
                f"restarting and retrying attempt {attempt + 1}/{attempts} with serial queries.",
                file=sys.stderr,
                flush=True,
            )
    return [], katago


def evaluate_games_parallel(args: argparse.Namespace, games: list[Game], jsonl_file: Any) -> None:
    workers = min(max(args.parallel_engines, 1), len(games))
    print(f"[parallel] using {workers} KataGo analysis processes", flush=True)
    with multiprocessing.Manager() as manager:
        evaluate_games_parallel_with_progress(args, games, jsonl_file, workers, manager.Queue())


def evaluate_games_parallel_with_progress(
    args: argparse.Namespace, games: list[Game], jsonl_file: Any, workers: int, progress_queue: Any
) -> None:
    init_args = (
        args.katago,
        args.model,
        args.config,
        args.rules,
        args.max_visits,
        args.max_moves,
        args.batch_positions,
        args.reuse_sgf_analysis,
        args.katago_response_timeout,
        args.katago_override_config,
        args.human_model or "",
        args.human_sl_profile,
        args.human_sl_root_symmetries,
        progress_queue,
    )
    total = len(games)
    position_totals = {
        index: min(len(game.moves), args.max_moves) + 1
        for index, game in enumerate(games, start=1)
    }
    completed = 0
    completed_units = 0
    active_progress: dict[int, dict[str, Any]] = {}
    started_at = time.monotonic()
    progress_interval = max(float(args.progress_interval), 1.0)
    with concurrent.futures.ProcessPoolExecutor(
        max_workers=workers,
        initializer=init_worker,
        initargs=init_args,
    ) as executor:
        futures = {
            executor.submit(worker_evaluate_game, (index, game)): (index, game)
            for index, game in enumerate(games, start=1)
        }
        pending = set(futures)
        print_progress(
            completed,
            total,
            workers,
            started_at,
            active_progress,
            completed_units,
            position_totals,
        )
        while pending:
            done, pending = concurrent.futures.wait(
                pending,
                timeout=progress_interval,
                return_when=concurrent.futures.FIRST_COMPLETED,
            )
            drain_progress_queue(progress_queue, active_progress)
            if not done:
                print_progress(
                    completed,
                    total,
                    workers,
                    started_at,
                    active_progress,
                    completed_units,
                    position_totals,
                )
                continue
            for future in done:
                index, game = futures[future]
                results = future.result()
                completed += 1
                completed_units += position_totals.get(index, 0)
                active_progress.pop(index, None)
                print(f"[{completed}/{total} done] {index}: {game.path.name}", flush=True)
                write_results(results, args.player, jsonl_file)
            drain_progress_queue(progress_queue, active_progress)
            print_progress(
                completed,
                total,
                workers,
                started_at,
                active_progress,
                completed_units,
                position_totals,
            )


def drain_progress_queue(progress_queue: Any, active_progress: dict[int, dict[str, Any]]) -> None:
    while True:
        try:
            message = progress_queue.get_nowait()
        except queue.Empty:
            return
        if not isinstance(message, dict):
            continue
        index = int(message.get("index", 0) or 0)
        if index > 0:
            active_progress[index] = message


def print_progress(
    completed: int,
    total: int,
    workers: int,
    started_at: float,
    active_progress: dict[int, dict[str, Any]],
    completed_units: int,
    position_totals: dict[int, int],
) -> None:
    total_units = sum(position_totals.values())
    active_units = sum(
        min(
            max(
                int(progress.get("done_positions", 0) or 0),
                int(progress.get("active_positions", 0) or 0),
            ),
            position_totals.get(index, 0),
        )
        for index, progress in active_progress.items()
    )
    done_units = min(total_units, completed_units + active_units)
    remaining = max(0, total - completed)
    running = min(max(workers, 0), remaining)
    queued = max(0, remaining - running)
    print(
        "[progress] "
        f"{progress_percent(done_units, total_units)} {progress_bar(done_units, total_units)} "
        f"games {completed}/{total} done, positions {done_units}/{total_units}, "
        f"running~{running}, queued~{queued}, elapsed {format_elapsed(time.monotonic() - started_at)}"
        f"{active_progress_text(active_progress)}",
        flush=True,
    )


def active_progress_text(active_progress: dict[int, dict[str, Any]], limit: int = 6) -> str:
    if not active_progress:
        return ""
    parts = []
    for index in sorted(active_progress)[:limit]:
        progress = active_progress[index]
        total_moves = max(0, int(progress.get("total_moves", 0) or 0))
        done_positions = max(0, int(progress.get("done_positions", 0) or 0))
        active_positions = int(progress.get("active_positions", 0) or 0)
        done_moves = min(total_moves, max(0, done_positions - 1))
        active_moves = min(total_moves, max(0, active_positions - 1))
        name = Path(str(progress.get("name", ""))).name
        if active_moves > done_moves:
            move_text = f"move {done_moves + 1}-{active_moves}/{total_moves}"
        else:
            move_text = f"move {done_moves}/{total_moves}"
        parts.append(f"game {index} {move_text} {name}")
    if len(active_progress) > limit:
        parts.append(f"+{len(active_progress) - limit} more")
    return " | " + "; ".join(parts)


def progress_bar(completed: int, total: int, width: int = 28) -> str:
    if total <= 0:
        return "[" + "-" * width + "]"
    filled = max(0, min(width, int(round(width * completed / total))))
    return "[" + "#" * filled + "-" * (width - filled) + "]"


def progress_percent(completed: int, total: int) -> str:
    if total <= 0:
        return "0.0%"
    return f"{100.0 * completed / total:5.1f}%"


def format_elapsed(seconds: float) -> str:
    seconds = max(0, int(seconds))
    hours, remainder = divmod(seconds, 3600)
    minutes, seconds = divmod(remainder, 60)
    if hours:
        return f"{hours}h{minutes:02d}m{seconds:02d}s"
    return f"{minutes}m{seconds:02d}s"


def init_worker(
    katago: str,
    model: str,
    config: str,
    rules: str,
    max_visits: int,
    max_moves: int,
    batch_positions: int,
    reuse_sgf_analysis: bool,
    katago_response_timeout: float,
    katago_override_config: str,
    human_model: str,
    human_sl_profile: str,
    human_sl_root_symmetries: int,
    progress_queue: Any = None,
) -> None:
    global _WORKER_KATAGO, _WORKER_SETTINGS, _WORKER_PROGRESS_QUEUE
    _WORKER_KATAGO = KataGoProcess(
        Path(katago),
        Path(model),
        Path(config),
        katago_response_timeout,
        katago_override_config,
        Path(human_model) if human_model else None,
        human_sl_profile,
        human_sl_root_symmetries,
    )
    _WORKER_PROGRESS_QUEUE = progress_queue
    _WORKER_SETTINGS = {
        "katago": katago,
        "model": model,
        "config": config,
        "rules": rules,
        "max_visits": max_visits,
        "max_moves": max_moves,
        "batch_positions": batch_positions,
        "reuse_sgf_analysis": reuse_sgf_analysis,
        "katago_response_timeout": katago_response_timeout,
        "katago_override_config": katago_override_config,
        "human_model": human_model,
        "human_sl_profile": human_sl_profile,
        "human_sl_root_symmetries": human_sl_root_symmetries,
    }
    atexit.register(close_worker)


def close_worker() -> None:
    global _WORKER_KATAGO
    if _WORKER_KATAGO is not None:
        _WORKER_KATAGO.close()
        _WORKER_KATAGO = None


def restart_worker_katago() -> None:
    global _WORKER_KATAGO
    if _WORKER_KATAGO is not None:
        _WORKER_KATAGO.close()
    _WORKER_KATAGO = KataGoProcess(
        Path(str(_WORKER_SETTINGS["katago"])),
        Path(str(_WORKER_SETTINGS["model"])),
        Path(str(_WORKER_SETTINGS["config"])),
        float(_WORKER_SETTINGS["katago_response_timeout"]),
        str(_WORKER_SETTINGS.get("katago_override_config", "")),
        Path(str(_WORKER_SETTINGS["human_model"]))
        if str(_WORKER_SETTINGS.get("human_model") or "")
        else None,
        str(_WORKER_SETTINGS.get("human_sl_profile") or "rank_9d"),
        int(_WORKER_SETTINGS.get("human_sl_root_symmetries") or 2),
    )


def is_retryable_katago_error(exc: BaseException) -> bool:
    if isinstance(exc, (KataGoTimeoutError, BrokenPipeError)):
        return True
    text = str(exc)
    return (
        "KataGo exited before returning analysis" in text
        or "KataGo process is not running" in text
    )


def worker_evaluate_game(item: tuple[int, Game]) -> list[dict[str, Any]]:
    if _WORKER_KATAGO is None:
        raise RuntimeError("KataGo worker was not initialized")
    index, game = item
    if _WORKER_PROGRESS_QUEUE is not None:
        max_moves = int(_WORKER_SETTINGS["max_moves"])
        _WORKER_PROGRESS_QUEUE.put(
            {
                "index": index,
                "name": str(game.path.name),
                "done_positions": 0,
                "total_positions": min(len(game.moves), max_moves) + 1,
                "total_moves": min(len(game.moves), max_moves),
            }
        )
    try:
        return evaluate_game(
            _WORKER_KATAGO,
            game,
            str(_WORKER_SETTINGS["rules"]),
            int(_WORKER_SETTINGS["max_visits"]),
            int(_WORKER_SETTINGS["max_moves"]),
            int(_WORKER_SETTINGS["batch_positions"]),
            progress_index=index,
            reuse_sgf_analysis=bool(_WORKER_SETTINGS.get("reuse_sgf_analysis")),
        )
    except (KataGoTimeoutError, RuntimeError, BrokenPipeError) as exc:
        if not is_retryable_katago_error(exc):
            raise
        print(
            f"[warn] KataGo failed on {game.path.name}: {exc}; "
            "restarting this worker and retrying the game in serial query mode.",
            file=sys.stderr,
            flush=True,
        )
        restart_worker_katago()
        return evaluate_game(
            _WORKER_KATAGO,
            game,
            str(_WORKER_SETTINGS["rules"]),
            int(_WORKER_SETTINGS["max_visits"]),
            int(_WORKER_SETTINGS["max_moves"]),
            1,
            progress_index=index,
            reuse_sgf_analysis=bool(_WORKER_SETTINGS.get("reuse_sgf_analysis")),
        )


def write_results(results: list[dict[str, Any]], player: str | None, jsonl_file: Any) -> None:
    for row in results:
        if player and player not in str(row["player"]):
            continue
        print(format_row(row))
        if jsonl_file:
            jsonl_file.write(json.dumps(row, ensure_ascii=False) + "\n")
            jsonl_file.flush()


def paths_from_jsonl(jsonl_path: Path) -> list[str]:
    paths: list[str] = []
    seen: set[str] = set()
    if not jsonl_path.exists():
        return paths
    with jsonl_path.open(encoding="utf-8", errors="replace") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                continue
            path = str(row.get("path") or "")
            if path and path not in seen:
                seen.add(path)
                paths.append(path)
    return paths


def completed_game_keys(jsonl_path: Path) -> set[str]:
    if not jsonl_path.exists():
        return set()
    sides_by_game: dict[str, set[str]] = {}
    with jsonl_path.open(encoding="utf-8", errors="replace") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                continue
            key = game_key(Path(str(row.get("path") or "")))
            side = str(row.get("side") or "")
            if key and side in {"B", "W"}:
                sides_by_game.setdefault(key, set()).add(side)
    return {key for key, sides in sides_by_game.items() if {"B", "W"}.issubset(sides)}


def game_key(path: Path) -> str:
    return chess_id_from_path(path)


def load_games(patterns: Iterable[str]) -> list[Game]:
    paths: list[Path] = []
    for pattern in patterns:
        matches = glob.glob(pattern, recursive=True)
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


def load_game_metadata(metadata_paths: Iterable[str]) -> dict[str, dict[str, Any]]:
    metadata: dict[str, dict[str, Any]] = {}
    for metadata_path_text in metadata_paths:
        metadata_path = Path(metadata_path_text)
        if not metadata_path.exists():
            continue
        with metadata_path.open(encoding="utf-8", errors="replace") as handle:
            for line_number, line in enumerate(handle, start=1):
                line = line.strip()
                if not line:
                    continue
                try:
                    row = json.loads(line)
                except json.JSONDecodeError as exc:
                    print(
                        f"[metadata] skip invalid JSON at {metadata_path}:{line_number}: {exc}",
                        file=sys.stderr,
                    )
                    continue
                if not isinstance(row, dict):
                    continue
                for key in game_metadata_keys(row):
                    metadata[key] = row
    return metadata


def game_metadata_keys(row: dict[str, Any]) -> list[str]:
    keys: list[str] = []
    path_text = str(row.get("path") or row.get("sgf") or row.get("filename") or "").strip()
    if not path_text:
        return keys
    path = Path(path_text)
    keys.append(path_text)
    keys.append(path.name)
    keys.append(path.stem)
    try:
        keys.append(str(path.resolve()))
    except OSError:
        pass
    return list(dict.fromkeys(key for key in keys if key))


def apply_game_metadata(games: list[Game], metadata: dict[str, dict[str, Any]]) -> list[Game]:
    if not metadata:
        return games
    for game in games:
        row = metadata.get(str(game.path.resolve())) or metadata.get(str(game.path))
        row = row or metadata.get(game.path.name) or metadata.get(game.path.stem)
        if not row:
            continue
        black_metadata = row.get("black") if isinstance(row.get("black"), dict) else {}
        white_metadata = row.get("white") if isinstance(row.get("white"), dict) else {}
        shared_rank = str(row.get("rank") or "").strip()
        black_rank = str(
            row.get("black_rank") or row.get("BR") or black_metadata.get("rank") or shared_rank
        ).strip()
        white_rank = str(
            row.get("white_rank") or row.get("WR") or white_metadata.get("rank") or shared_rank
        ).strip()
        if black_rank:
            game.black_rank = black_rank
        if white_rank:
            game.white_rank = white_rank
        black_name = str(
            row.get("black_name") or row.get("PB") or black_metadata.get("name") or ""
        ).strip()
        white_name = str(
            row.get("white_name") or row.get("PW") or white_metadata.get("name") or ""
        ).strip()
        if black_name:
            game.black_name = black_name
        if white_name:
            game.white_name = white_name
        source = str(row.get("source") or row.get("sample_source") or "").strip()
        if source:
            game.source = source
    return games


def filter_games(
    games: list[Game],
    *,
    include_handicap: bool,
    min_moves: int,
    board_size: int,
    dedupe_chessid: bool,
) -> list[Game]:
    filtered: list[Game] = []
    seen_ids: set[str] = set()
    for game in games:
        if not include_handicap and game.handicap > 0:
            continue
        if board_size > 0 and game.size != board_size:
            continue
        if len(game.moves) < min_moves:
            continue
        if dedupe_chessid:
            chessid = chess_id_from_path(game.path)
            if chessid in seen_ids:
                continue
            seen_ids.add(chessid)
        filtered.append(game)
    return filtered


def chess_id_from_path(path: Path) -> str:
    match = re.search(r"_(\d{12,})\.sgf$", path.name)
    if match:
        return match.group(1)
    return str(path.resolve())


def parse_sgf(path: Path) -> Game:
    text = read_sgf_text(path)
    nodes = parse_sgf_mainline_nodes(text)
    root_props = nodes[0] if nodes else {}
    size = int(float(first_prop(root_props, "SZ", "19") or 19))
    moves = []
    saved_analyses: list[dict[str, Any] | None] = [
        saved_analysis_from_node(root_props)
    ]
    for node in nodes[1:]:
        color = "B" if "B" in node else "W" if "W" in node else ""
        if color:
            moves.append((color, sgf_point_to_gtp(first_prop(node, color), size)))
            saved_analyses.append(saved_analysis_from_node(node))
    return Game(
        path=path,
        black_name=first_prop(root_props, "PB"),
        white_name=first_prop(root_props, "PW"),
        black_rank=first_prop(root_props, "BR"),
        white_rank=first_prop(root_props, "WR"),
        size=size,
        rules=first_prop(root_props, "RU"),
        komi=parse_komi(first_prop(root_props, "KM"), first_prop(root_props, "RU")),
        handicap=int(float(first_prop(root_props, "HA", "0") or 0)),
        moves=moves,
        saved_analyses=saved_analyses,
        source="sgf",
    )


def read_sgf_text(path: Path) -> str:
    data = path.read_bytes()
    for encoding in ("utf-8-sig", "utf-8", "gb18030", "gbk"):
        try:
            return data.decode(encoding)
        except UnicodeDecodeError:
            continue
    return data.decode("utf-8", errors="replace")


def parse_sgf_mainline_nodes(text: str) -> list[dict[str, list[str]]]:
    start = text.find("(")
    if start < 0:
        return parse_sgf_sequence(text, 0, len(text))[0]
    nodes, _ = parse_sgf_game_tree(text, start)
    return nodes


def parse_sgf_game_tree(text: str, index: int) -> tuple[list[dict[str, list[str]]], int]:
    if index >= len(text) or text[index] != "(":
        return [], index
    index += 1
    nodes: list[dict[str, list[str]]] = []
    took_child = False
    while index < len(text):
        index = skip_sgf_space(text, index)
        if index >= len(text):
            break
        char = text[index]
        if char == ";":
            node, index = parse_sgf_node(text, index)
            nodes.append(node)
        elif char == "(":
            child_nodes, index = parse_sgf_game_tree(text, index)
            if not took_child:
                nodes.extend(child_nodes)
                took_child = True
        elif char == ")":
            return nodes, index + 1
        else:
            index += 1
    return nodes, index


def parse_sgf_sequence(
    text: str, index: int, end: int
) -> tuple[list[dict[str, list[str]]], int]:
    nodes: list[dict[str, list[str]]] = []
    while index < end:
        index = skip_sgf_space(text, index)
        if index >= end:
            break
        if text[index] != ";":
            index += 1
            continue
        node, index = parse_sgf_node(text, index)
        nodes.append(node)
    return nodes, index


def parse_sgf_node(text: str, index: int) -> tuple[dict[str, list[str]], int]:
    index += 1
    props: dict[str, list[str]] = {}
    while index < len(text):
        index = skip_sgf_space(text, index)
        if index >= len(text) or text[index] in ";()":
            break
        if not text[index].isalpha():
            index += 1
            continue
        tag_start = index
        while index < len(text) and text[index].isalpha():
            index += 1
        tag = text[tag_start:index].upper()
        values: list[str] = []
        index = skip_sgf_space(text, index)
        while index < len(text) and text[index] == "[":
            value, index = parse_sgf_prop_value(text, index)
            values.append(value)
            index = skip_sgf_space(text, index)
        if values:
            props.setdefault(tag, []).extend(values)
    return props, index


def parse_sgf_prop_value(text: str, index: int) -> tuple[str, int]:
    index += 1
    chars: list[str] = []
    while index < len(text):
        char = text[index]
        if char == "\\" and index + 1 < len(text):
            chars.append(text[index + 1])
            index += 2
            continue
        if char == "]":
            return "".join(chars), index + 1
        chars.append(char)
        index += 1
    return "".join(chars), index


def skip_sgf_space(text: str, index: int) -> int:
    while index < len(text) and text[index].isspace():
        index += 1
    return index


def first_prop(props: dict[str, list[str]], tag: str, default: str = "") -> str:
    values = props.get(tag.upper()) or []
    return values[0] if values else default


def saved_analysis_from_node(props: dict[str, list[str]]) -> dict[str, Any] | None:
    for tag in ("LZ", "LZOP"):
        payload = first_prop(props, tag)
        if payload:
            return parse_saved_analysis_payload(payload)
    return None


def parse_saved_analysis_payload(payload: str) -> dict[str, Any] | None:
    lines = [line.strip() for line in payload.splitlines() if line.strip()]
    if not lines:
        return None
    header = lines[0].split()
    if len(header) < 3:
        return None
    root_info: dict[str, Any] = {
        "winrate": clamp((100.0 - number(header[1])) / 100.0, 0.0, 1.0),
    }
    if len(header) >= 4:
        root_info["scoreMean"] = number(header[3])
    if len(header) >= 5:
        root_info["scoreStdev"] = number(header[4])
    move_infos = parse_saved_move_infos(" ".join(lines[1:]))
    return {
        "rootInfo": root_info,
        "moveInfos": move_infos,
        "sgfAnalysis": True,
        "sgfEngine": header[0],
        "sgfPlayouts": parse_count_token(header[2]),
    }


def parse_saved_move_infos(raw: str) -> list[dict[str, Any]]:
    if not raw:
        return []
    analysis_line = raw.split(" ownership", 1)[0].strip()
    move_infos: list[dict[str, Any]] = []
    for order, variation in enumerate(re.split(r"\s+info\s+", analysis_line)):
        parsed = parse_saved_move_info(variation, order)
        if parsed:
            move_infos.append(parsed)
    return move_infos


def parse_saved_move_info(raw: str, order: int) -> dict[str, Any] | None:
    tokens = raw.strip().split()
    if not tokens:
        return None
    result: dict[str, Any] = {"order": order}
    index = 0
    while index < len(tokens):
        key = tokens[index]
        if key == "pv":
            result["pv"] = tokens[index + 1 :]
            break
        if index + 1 >= len(tokens):
            break
        value = tokens[index + 1]
        if key == "move":
            result["move"] = value
        elif key == "visits":
            result["visits"] = parse_count_token(value)
        elif key == "winrate":
            result["winrate"] = normalize_saved_rate(number(value))
        elif key == "prior":
            result["prior"] = normalize_saved_rate(number(value))
        elif key in {"scoreMean", "scoreLead"}:
            result[key] = number(value)
        index += 2
    return result if result.get("move") else None


def normalize_saved_rate(value: float) -> float:
    if abs(value) > 100.0:
        return value / 10000.0
    if abs(value) > 1.0:
        return value / 100.0
    return value


def parse_count_token(raw: str) -> int:
    text = str(raw or "").strip().lower().replace(",", "")
    multiplier = 1.0
    if text.endswith("m"):
        multiplier = 1_000_000.0
        text = text[:-1]
    elif text.endswith("k"):
        multiplier = 1_000.0
        text = text[:-1]
    try:
        return max(0, int(round(float(text) * multiplier)))
    except ValueError:
        digits = re.sub(r"[^0-9.]", "", text)
        if not digits:
            return 0
        try:
            return max(0, int(round(float(digits) * multiplier)))
        except ValueError:
            return 0


def parse_komi(raw: str, rules: str = "") -> float:
    if not raw:
        return 7.5
    try:
        komi = float(raw)
    except ValueError:
        return 7.5
    if abs(komi) > 100:
        # Fox SGFs use KM[375] for Chinese 3.75 zi, which is 7.5 points.
        # Japanese/Korean records use centipoints, e.g. KM[650] for 6.5.
        rules_text = str(rules).lower()
        if (
            abs(komi) <= 400
            and "japanese" not in rules_text
            and "korean" not in rules_text
        ):
            return komi / 50.0
        return komi / 100.0
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
    katago: KataGoProcess | None,
    game: Game,
    rules: str,
    max_visits: int,
    max_moves: int,
    batch_positions: int,
    progress_index: int | None = None,
    reuse_sgf_analysis: bool = False,
    sgf_analysis_only: bool = False,
) -> list[dict[str, Any]]:
    moves = game.moves[: min(len(game.moves), max_moves)]

    def report_progress(
        done_positions: int, total_positions: int, active_positions: int | None = None
    ) -> None:
        if _WORKER_PROGRESS_QUEUE is None or progress_index is None:
            return
        _WORKER_PROGRESS_QUEUE.put(
            {
                "index": progress_index,
                "name": str(game.path.name),
                "done_positions": done_positions,
                "active_positions": active_positions,
                "total_positions": total_positions,
                "total_moves": len(moves),
            }
        )

    analyses: list[dict[str, Any] | None] = [None] * (len(moves) + 1)
    saved_positions = 0
    if reuse_sgf_analysis:
        for index, analysis in enumerate(game.saved_analyses[: len(analyses)]):
            if has_saved_analysis(analysis):
                analyses[index] = analysis
                saved_positions += 1

    missing_indices = [index for index, analysis in enumerate(analyses) if analysis is None]
    katago_positions = 0
    if missing_indices and not sgf_analysis_only:
        if katago is None:
            raise RuntimeError("KataGo is required for positions missing saved SGF analysis")
        fresh_analyses = katago.analyze_many(
            [moves[:turn] for turn in missing_indices],
            rules=game.rules or rules,
            komi=game.komi,
            size=game.size,
            max_visits=max_visits,
            batch_positions=batch_positions,
            progress_callback=report_progress,
        )
        for index, analysis in zip(missing_indices, fresh_analyses):
            analyses[index] = analysis
        katago_positions = len(fresh_analyses)
    elif not missing_indices:
        report_progress(len(analyses), len(analyses), None)

    samples = {"B": [], "W": []}
    for index, (color, move) in enumerate(moves):
        previous = analyses[index]
        current = analyses[index + 1]
        if previous is None:
            continue
        sample = sample_move(index + 1, color, move, previous, current)
        if sample:
            samples[color].append(sample)

    analysis_source = "sgf" if sgf_analysis_only else "katago"
    if saved_positions and katago_positions:
        analysis_source = "mixed"
    elif saved_positions:
        analysis_source = "sgf"
    human_sl_profile = katago.human_sl_profile if katago is not None else ""
    return [
        side_report(
            game,
            "B",
            samples["B"],
            len(moves),
            max_visits,
            analysis_source,
            saved_positions,
            katago_positions,
            human_sl_profile,
        ),
        side_report(
            game,
            "W",
            samples["W"],
            len(moves),
            max_visits,
            analysis_source,
            saved_positions,
            katago_positions,
            human_sl_profile,
        ),
    ]


def sample_move(
    move_number: int,
    color: str,
    played: str,
    previous: dict[str, Any],
    current: dict[str, Any] | None,
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
        if current is None:
            return None
        score_loss = fallback_score_loss(previous, current)
        winrate_loss = fallback_winrate_loss(previous, current)
    score_equivalent_loss = positive(
        score_loss if score_loss is not None else winrate_loss / WINRATE_TO_SCORE_LOSS
    )
    complexity_value = complexity(move_infos)
    human_policy = previous.get("humanPolicy")
    policy_size = size_from_policy(human_policy)
    human_policy_ai_best = policy_probability(human_policy, str(top.get("move", "")), policy_size)
    human_policy_played = policy_probability(human_policy, played, policy_size)
    human_policy_mistake, human_policy_judged = human_policy_mistake_probability(
        move_infos,
        human_policy,
        policy_size,
    )
    return Sample(
        color=color,
        move_number=move_number,
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
        human_policy_ai_best=human_policy_ai_best,
        human_policy_played=human_policy_played,
        human_policy_mistake=human_policy_mistake,
        human_policy_judged=human_policy_judged,
        human_sl_difficulty=human_policy_mistake,
    )


def has_saved_analysis(analysis: dict[str, Any] | None) -> bool:
    return bool(analysis and (analysis.get("moveInfos") or analysis.get("rootInfo")))


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


def human_policy_mistake_probability(
    move_infos: list[dict[str, Any]],
    policy: Any,
    size: int,
) -> tuple[float | None, float | None]:
    if not isinstance(policy, list) or not move_infos:
        return None, None
    top_score = number(move_infos[0].get("scoreMean"))
    mistake_mass = 0.0
    judged_mass = 0.0
    for move in move_infos:
        if "scoreMean" not in move:
            continue
        move_text = str(move.get("move", ""))
        probability = policy_probability(policy, move_text, size)
        if probability is None:
            continue
        judged_mass += probability
        candidate_loss = positive(top_score - number(move.get("scoreMean")))
        if categorize(candidate_loss) in {"mistake", "blunder"}:
            mistake_mass += probability
    return clamp(mistake_mass, 0.0, 1.0), clamp(judged_mass, 0.0, 1.0)


def size_from_policy(policy: Any) -> int:
    if not isinstance(policy, list) or len(policy) < 2:
        return 19
    board_points = len(policy) - 1
    size = int(round(math.sqrt(board_points)))
    if size * size == board_points:
        return size
    return 19


def policy_probability(policy: Any, move: str, size: int) -> float | None:
    if not isinstance(policy, list):
        return None
    index = gtp_move_to_policy_index(move, size)
    if index < 0 or index >= len(policy):
        return None
    value = number(policy[index])
    if value < 0.0:
        return 0.0
    return clamp(value, 0.0, 1.0)


def gtp_move_to_policy_index(move: str, size: int) -> int:
    text = str(move or "").strip().upper()
    if not text or text == "PASS":
        return size * size
    columns = "ABCDEFGHJKLMNOPQRSTUVWXYZ"
    column = text[0]
    if column not in columns:
        return -1
    try:
        row = int(text[1:])
    except ValueError:
        return -1
    x = columns.index(column)
    y = size - row
    if x < 0 or y < 0 or x >= size or y >= size:
        return -1
    return y * size + x


def side_report(
    game: Game,
    color: str,
    samples: list[Sample],
    analyzed_moves: int,
    max_visits: int,
    analysis_source: str = "katago",
    sgf_analysis_positions: int = 0,
    katago_analysis_positions: int = 0,
    human_sl_profile: str = "",
) -> dict[str, Any]:
    player = game.black_name if color == "B" else game.white_name
    fox_rank = game.black_rank if color == "B" else game.white_rank
    if not samples:
        return {
            "path": str(game.path),
            "sample_source": game.source,
            "side": color,
            "player": player,
            "fox_rank": fox_rank,
            "analyzed_moves": analyzed_moves,
            "samples": 0,
            "max_visits": max_visits,
            "analysis_source": analysis_source,
            "sgf_analysis_positions": sgf_analysis_positions,
            "katago_analysis_positions": katago_analysis_positions,
            "human_sl_samples": 0,
            "human_sl_profile": human_sl_profile,
            "human_sl_correct_probability": 0.0,
            "human_sl_mistake_probability": 0.0,
            "human_sl_difficulty": 0.0,
            "human_policy_ai_best": 0.0,
            "human_policy_played": 0.0,
            "human_policy_mistake": 0.0,
            "human_policy_judged": 0.0,
            "strength_band": "-",
        }

    score_losses = [sample.score_loss for sample in samples if sample.score_loss is not None]
    average_winrate_loss = statistics.fmean(sample.winrate_loss for sample in samples)
    average_score_loss = statistics.fmean(score_losses) if score_losses else 0.0
    median_score_loss = statistics.median(score_losses) if score_losses else 0.0
    equivalent_losses = [sample.score_equivalent_loss for sample in samples]
    average_score_equivalent_loss = statistics.fmean(
        equivalent_losses
    )
    p75_score_equivalent_loss = percentile(equivalent_losses, 0.75)
    p90_score_equivalent_loss = percentile(equivalent_losses, 0.90)
    p95_score_equivalent_loss = percentile(equivalent_losses, 0.95)
    max_score_equivalent_loss = max(equivalent_losses)
    loss_stddev = statistics.pstdev(equivalent_losses) if len(equivalent_losses) > 1 else 0.0
    weighted_point_loss = weighted_loss(samples, average_score_equivalent_loss)
    first_choice_rate = rate(samples, lambda sample: sample.first_choice)
    top3_rate = rate(samples, lambda sample: sample.ai_rank < 3)
    top5_rate = rate(samples, lambda sample: sample.ai_rank < 5)
    outside_top5_rate = rate(samples, lambda sample: sample.ai_rank >= 5)
    average_ai_rank = statistics.fmean(capped_rank(sample) for sample in samples)
    excellent_rate = rate(samples, lambda sample: sample.category == "excellent")
    great_rate = rate(samples, lambda sample: sample.category == "great")
    good_move_rate = rate(samples, lambda sample: sample.category in {"excellent", "great", "good"})
    inaccuracy_rate = rate(samples, lambda sample: sample.category == "inaccuracy")
    bad_move_rate = rate(samples, lambda sample: sample.category in {"inaccuracy", "mistake", "blunder"})
    mistake_rate = rate(samples, lambda sample: sample.category in {"mistake", "blunder"})
    blunder_rate = rate(samples, lambda sample: sample.category == "blunder")
    match_rate_value = match_rate(first_choice_rate, good_move_rate, mistake_rate)
    average_difficulty = 100.0 * statistics.fmean(sample.complexity for sample in samples)
    opening_samples = phase_samples(samples, "opening")
    middlegame_samples = phase_samples(samples, "middlegame")
    endgame_samples = phase_samples(samples, "endgame")
    opening_weighted_loss = weighted_loss(opening_samples, weighted_point_loss)
    middlegame_weighted_loss = weighted_loss(middlegame_samples, weighted_point_loss)
    endgame_weighted_loss = weighted_loss(endgame_samples, weighted_point_loss)
    opening_good_move_rate = phase_rate(opening_samples, good_move_rate)
    middlegame_good_move_rate = phase_rate(middlegame_samples, good_move_rate)
    endgame_good_move_rate = phase_rate(endgame_samples, good_move_rate)
    quality_score_value = quality_score(
        weighted_point_loss,
        average_score_equivalent_loss,
        median_score_loss if score_losses else average_score_equivalent_loss,
        p75_score_equivalent_loss,
        p90_score_equivalent_loss,
        first_choice_rate,
        good_move_rate,
        mistake_rate,
        blunder_rate,
        match_rate_value,
        average_difficulty,
    )
    band = strength_band(
        quality_score_value,
        weighted_point_loss,
        average_score_equivalent_loss,
        median_score_loss if score_losses else average_score_equivalent_loss,
        p90_score_equivalent_loss,
        first_choice_rate,
        good_move_rate,
        mistake_rate,
        match_rate_value,
        average_difficulty,
    )
    human_samples = [
        sample for sample in samples if sample.human_sl_difficulty is not None
    ]
    human_sl_samples = len(human_samples)
    average_human_policy_mistake = mean_optional(
        sample.human_policy_mistake for sample in human_samples
    )
    average_human_sl_difficulty = 100.0 * average_human_policy_mistake
    average_human_sl_correct_probability = 1.0 - average_human_policy_mistake if human_samples else 0.0
    average_human_policy_ai_best = mean_optional(
        sample.human_policy_ai_best for sample in human_samples
    )
    average_human_policy_played = mean_optional(
        sample.human_policy_played for sample in human_samples
    )
    average_human_policy_judged = mean_optional(
        sample.human_policy_judged for sample in human_samples
    )
    return {
        "path": str(game.path),
        "sample_source": game.source,
        "side": color,
        "player": player,
        "fox_rank": fox_rank,
        "analyzed_moves": analyzed_moves,
        "samples": len(samples),
        "max_visits": max_visits,
        "analysis_source": analysis_source,
        "sgf_analysis_positions": sgf_analysis_positions,
        "katago_analysis_positions": katago_analysis_positions,
        "strength_band": band,
        "quality_score": round(quality_score_value, 1),
        "first_choice_rate": round(first_choice_rate, 4),
        "top3_rate": round(top3_rate, 4),
        "top5_rate": round(top5_rate, 4),
        "outside_top5_rate": round(outside_top5_rate, 4),
        "average_ai_rank": round(average_ai_rank, 3),
        "excellent_rate": round(excellent_rate, 4),
        "great_rate": round(great_rate, 4),
        "good_move_rate": round(good_move_rate, 4),
        "inaccuracy_rate": round(inaccuracy_rate, 4),
        "match_rate": round(match_rate_value, 4),
        "bad_move_rate": round(bad_move_rate, 4),
        "average_difficulty": round(average_difficulty, 1),
        "human_sl_samples": human_sl_samples,
        "human_sl_profile": human_sl_profile,
        "human_sl_correct_probability": round(average_human_sl_correct_probability, 4),
        "human_sl_mistake_probability": round(average_human_policy_mistake, 4),
        "human_sl_difficulty": round(average_human_sl_difficulty, 1),
        "human_policy_ai_best": round(average_human_policy_ai_best, 4),
        "human_policy_played": round(average_human_policy_played, 4),
        "human_policy_mistake": round(average_human_policy_mistake, 4),
        "human_policy_judged": round(average_human_policy_judged, 4),
        "weighted_point_loss": round(weighted_point_loss, 3),
        "average_score_loss": round(average_score_loss, 3),
        "average_score_equivalent_loss": round(average_score_equivalent_loss, 3),
        "median_score_loss": round(median_score_loss, 3),
        "p75_score_equivalent_loss": round(p75_score_equivalent_loss, 3),
        "p90_score_equivalent_loss": round(p90_score_equivalent_loss, 3),
        "p95_score_equivalent_loss": round(p95_score_equivalent_loss, 3),
        "max_score_equivalent_loss": round(max_score_equivalent_loss, 3),
        "loss_stddev": round(loss_stddev, 3),
        "average_winrate_loss": round(average_winrate_loss, 3),
        "mistake_rate": round(mistake_rate, 4),
        "blunder_rate": round(blunder_rate, 4),
        "opening_samples": len(opening_samples),
        "middlegame_samples": len(middlegame_samples),
        "endgame_samples": len(endgame_samples),
        "opening_weighted_loss": round(opening_weighted_loss, 3),
        "middlegame_weighted_loss": round(middlegame_weighted_loss, 3),
        "endgame_weighted_loss": round(endgame_weighted_loss, 3),
        "opening_good_move_rate": round(opening_good_move_rate, 4),
        "middlegame_good_move_rate": round(middlegame_good_move_rate, 4),
        "endgame_good_move_rate": round(endgame_good_move_rate, 4),
    }


def capped_rank(sample: Sample) -> float:
    if sample.ai_rank >= ADDITIONAL_MOVE_ORDER:
        return float(AI_RANK_CAP)
    return float(min(sample.ai_rank + 1, AI_RANK_CAP))


def phase_samples(samples: list[Sample], phase: str) -> list[Sample]:
    if phase == "opening":
        return [sample for sample in samples if sample.move_number <= OPENING_MOVE_LIMIT]
    if phase == "middlegame":
        return [
            sample
            for sample in samples
            if OPENING_MOVE_LIMIT < sample.move_number <= MIDDLEGAME_MOVE_LIMIT
        ]
    if phase == "endgame":
        return [sample for sample in samples if sample.move_number > MIDDLEGAME_MOVE_LIMIT]
    return []


def phase_rate(samples: list[Sample], fallback: float) -> float:
    if not samples:
        return fallback
    return rate(samples, lambda sample: sample.category in {"excellent", "great", "good"})


def weighted_loss(samples: list[Sample], fallback: float) -> float:
    weight_sum = sum(sample.adjusted_weight for sample in samples)
    if weight_sum == 0.0:
        return fallback
    return sum(sample.score_equivalent_loss * sample.adjusted_weight for sample in samples) / weight_sum


def rate(samples: list[Sample], predicate: Any) -> float:
    return sum(1 for sample in samples if predicate(sample)) / len(samples)


def mean_optional(values: Iterable[float | None]) -> float:
    present = [value for value in values if value is not None]
    return statistics.fmean(present) if present else 0.0


def percentile(values: list[float], fraction: float) -> float:
    if not values:
        return 0.0
    sorted_values = sorted(values)
    if len(sorted_values) == 1:
        return sorted_values[0]
    position = fraction * (len(sorted_values) - 1)
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return sorted_values[lower]
    weight = position - lower
    return sorted_values[lower] * (1.0 - weight) + sorted_values[upper] * weight


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
    p75_point_loss: float,
    p90_point_loss: float,
    first_choice_rate: float,
    good_move_rate: float,
    mistake_rate: float,
    blunder_rate: float,
    match_rate_value: float,
    average_difficulty: float,
) -> float:
    rank_value = regressed_rank_value(
        weighted_point_loss,
        average_point_loss,
        median_point_loss,
        p75_point_loss,
        p90_point_loss,
        first_choice_rate,
        good_move_rate,
        mistake_rate,
        blunder_rate,
        match_rate_value,
        average_difficulty,
    )
    return rank_value_to_quality_score(rank_value)


def regressed_rank_value(
    weighted_point_loss: float,
    average_point_loss: float,
    median_point_loss: float,
    p75_point_loss: float,
    p90_point_loss: float,
    first_choice_rate: float,
    good_move_rate: float,
    mistake_rate: float,
    blunder_rate: float,
    match_rate_value: float,
    average_difficulty: float,
) -> float:
    first_choice = clamp(first_choice_rate, 0.0, 1.0)
    good_move = clamp(good_move_rate, 0.0, 1.0)
    non_mistake = 1.0 - clamp(mistake_rate, 0.0, 1.0)
    non_blunder = 1.0 - clamp(blunder_rate, 0.0, 1.0)
    match = clamp(match_rate_value, 0.0, 1.0)
    weighted_loss_fit = 1.0 / (1.0 + clamp(positive(weighted_point_loss), 0.0, 50.0))
    median_loss_fit = 1.0 / (1.0 + clamp(positive(median_point_loss), 0.0, 50.0))
    average_loss_fit = 1.0 / (1.0 + clamp(positive(average_point_loss), 0.0, 50.0))
    p75_loss_fit = 1.0 / (1.0 + clamp(positive(p75_point_loss), 0.0, 50.0))
    p90_loss_fit = 1.0 / (1.0 + clamp(positive(p90_point_loss), 0.0, 80.0))
    difficulty = clamp((average_difficulty - 25.0) / 35.0, 0.0, 1.0)
    return clamp(
        REGRESSION_INTERCEPT
        + REGRESSION_FIRST_CHOICE_WEIGHT * first_choice
        + REGRESSION_GOOD_MOVE_WEIGHT * good_move
        + REGRESSION_MATCH_WEIGHT * match
        + REGRESSION_NON_MISTAKE_WEIGHT * non_mistake
        + REGRESSION_NON_BLUNDER_WEIGHT * non_blunder
        + REGRESSION_WEIGHTED_LOSS_WEIGHT * weighted_loss_fit
        + REGRESSION_MEDIAN_LOSS_WEIGHT * median_loss_fit
        + REGRESSION_AVERAGE_LOSS_WEIGHT * average_loss_fit
        + REGRESSION_P75_LOSS_WEIGHT * p75_loss_fit
        + REGRESSION_P90_LOSS_WEIGHT * p90_loss_fit
        + REGRESSION_DIFFICULTY_WEIGHT * difficulty
        + REGRESSION_FIRST_CHOICE_DIFFICULTY_WEIGHT * first_choice * difficulty
        + REGRESSION_GOOD_MOVE_DIFFICULTY_WEIGHT * good_move * difficulty
        + REGRESSION_MATCH_DIFFICULTY_WEIGHT * match * difficulty,
        -18.0,
        12.0,
    )


def rank_value_to_quality_score(rank_value: float) -> float:
    return clamp((rank_value + 18.0) * 100.0 / 30.0, 0.0, 100.0)


def strength_band(
    quality_score_value: float,
    weighted_point_loss: float,
    average_point_loss: float,
    median_point_loss: float,
    p90_point_loss: float,
    first_choice_rate: float,
    good_move_rate: float,
    mistake_rate: float,
    match_rate_value: float,
    average_difficulty: float,
) -> str:
    level = max(
        base_level(quality_score_value),
        elite_evidence_level(
            weighted_point_loss,
            first_choice_rate,
            good_move_rate,
            mistake_rate,
            match_rate_value,
        ),
    )
    level = min(
        level,
        metric_cap_level(
            weighted_point_loss,
            median_point_loss,
            first_choice_rate,
            good_move_rate,
            mistake_rate,
            match_rate_value,
        ),
    )
    level = min(
        level,
        tail_loss_cap(
            weighted_point_loss,
            average_point_loss,
            median_point_loss,
            p90_point_loss,
            match_rate_value,
        ),
    )
    level = evidence_adjusted_level(level, first_choice_rate, good_move_rate, average_difficulty)
    return STRENGTH_BANDS[level]


def match_rate(first_choice_rate: float, good_move_rate: float, mistake_rate: float) -> float:
    return clamp(
        0.45 * first_choice_rate + 0.45 * good_move_rate + 0.10 * (1.0 - mistake_rate),
        0.0,
        1.0,
    )


def base_level(score: float) -> int:
    rank_value = -18.0 + clamp(score, 0.0, 100.0) * 30.0 / 100.0
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
        if rank_value >= threshold:
            return level
    return 0


def elite_evidence_level(
    weighted_point_loss: float,
    first_choice_rate: float,
    good_move_rate: float,
    mistake_rate: float,
    match_rate_value: float,
) -> int:
    if (
        first_choice_rate >= 0.95
        and good_move_rate >= 0.96
        and mistake_rate <= 0.01
        and weighted_point_loss <= 1.00
    ):
        return 13
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
    if (
        first_choice_rate >= 0.44
        and good_move_rate >= 0.78
        and match_rate_value >= 0.62
        and mistake_rate <= 0.03
        and weighted_point_loss <= 2.00
    ):
        return 10
    return 0


def tail_loss_cap(
    weighted_point_loss: float,
    average_point_loss: float,
    median_point_loss: float,
    p90_point_loss: float,
    match_rate_value: float,
) -> int:
    cap_level = 13
    if p90_point_loss > 8.0 and average_point_loss > 2.8 and match_rate_value < 0.55:
        cap_level = min(cap_level, 7)
    if p90_point_loss > 5.5 and median_point_loss > 1.0 and match_rate_value < 0.50:
        cap_level = min(cap_level, 6)
    if weighted_point_loss > 4.8 and average_point_loss > 3.0 and match_rate_value < 0.45:
        cap_level = min(cap_level, 4)
    return cap_level


def metric_cap_level(
    weighted_point_loss: float,
    median_point_loss: float,
    first_choice_rate: float,
    good_move_rate: float,
    mistake_rate: float,
    match_rate_value: float,
) -> int:
    return min(
        cap_by_first_choice(first_choice_rate),
        cap_by_good_move_rate(good_move_rate),
        cap_by_match_rate(match_rate_value),
        cap_by_mistake_rate(mistake_rate),
        cap_by_median_loss(median_point_loss),
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


def cap_by_match_rate(value: float) -> int:
    return cap(value, [(0.80, 13), (0.70, 12), (0.62, 11), (0.58, 10), (0.54, 9), (0.49, 8), (0.43, 7), (0.37, 6), (0.30, 5), (0.22, 4), (0.14, 3)], 2)


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
        f"source={row.get('analysis_source', 'katago')} "
        f"samples={row['samples']} top1={row['first_choice_rate']:.0%} "
        f"good={row['good_move_rate']:.0%} diff={row['average_difficulty']:.0f} "
        f"humanMistake={row.get('human_sl_mistake_probability', 0.0):.0%} "
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

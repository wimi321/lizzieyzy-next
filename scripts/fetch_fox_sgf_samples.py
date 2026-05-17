#!/usr/bin/env python3
"""Fetch public Fox SGF samples for local strength-estimator calibration.

The script intentionally writes to target/ by default. Public game records are useful
for local calibration, but they are dynamic external data and should not be committed
as deterministic unit-test fixtures.
"""

from __future__ import annotations

import argparse
import json
import re
import time
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


BASE_URL = "https://h5.foxwq.com/yehuDiamond/chessbook_local"
QUERY_USER_URL = "https://newframe.foxwq.com/cgi/QueryUserInfoPanel"
MOBILE_USER_AGENT = (
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
    "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("players", nargs="+", help="Fox nickname or numeric UID.")
    parser.add_argument("--max-games", type=int, default=5, help="Max SGFs to download per player.")
    parser.add_argument("--min-moves", type=int, default=60, help="Skip games shorter than this.")
    parser.add_argument(
        "--balanced-by-rank",
        action="store_true",
        help="Walk opponent accounts and keep up to --samples-per-rank games for each rank seen.",
    )
    parser.add_argument(
        "--samples-per-rank",
        type=int,
        default=2,
        help="Target SGF count for each rank when --balanced-by-rank is set.",
    )
    parser.add_argument(
        "--max-users",
        type=int,
        default=12,
        help="Maximum accounts to inspect when --balanced-by-rank is set.",
    )
    parser.add_argument(
        "--out",
        default="target/fox-sgf-samples",
        help="Output directory. Defaults to target/fox-sgf-samples.",
    )
    parser.add_argument("--sleep", type=float, default=0.7, help="Delay between SGF downloads.")
    args = parser.parse_args()

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    manifest_path = out_dir / "manifest.jsonl"
    total = 0

    with manifest_path.open("a", encoding="utf-8") as manifest:
        if args.balanced_by_rank:
            total = fetch_balanced_by_rank(args, manifest, out_dir)
        else:
            for player in args.players:
                user = resolve_user(player)
                games = fetch_chess_list(user["uid"])
                downloaded = 0
                print(
                    f"{player}: resolved uid={user['uid']} "
                    f"nickname={user['nickname']} games={len(games)}"
                )
                for game in games:
                    if downloaded >= args.max_games:
                        break
                    if int(game.get("movenum", 0)) < args.min_moves:
                        continue
                    chessid = str(game.get("chessid", "")).strip()
                    if not chessid:
                        continue
                    sgf = fetch_sgf(chessid)
                    if not sgf:
                        print(f"  skip {chessid}: empty SGF")
                        continue
                    path = out_dir / sample_filename(game, chessid)
                    path.write_text(sgf, encoding="utf-8")
                    manifest.write(
                        json.dumps(manifest_entry(user, game, path), ensure_ascii=False) + "\n"
                    )
                    manifest.flush()
                    downloaded += 1
                    total += 1
                    print(f"  wrote {path}")
                    time.sleep(max(args.sleep, 0.0))
    print(f"done: {total} SGF files, manifest={manifest_path}")
    return 0


def fetch_balanced_by_rank(args: argparse.Namespace, manifest: Any, out_dir: Path) -> int:
    queue: list[dict[str, str]] = []
    for player in args.players:
        queue.append(resolve_user(player))
    seen_users: set[str] = set()
    seen_chessids: set[str] = set()
    rank_counts: dict[str, int] = {}
    total = 0

    while queue and len(seen_users) < args.max_users:
        user = queue.pop(0)
        uid = user["uid"]
        if uid in seen_users:
            continue
        seen_users.add(uid)
        games = fetch_chess_list(uid)
        print(
            f"{user['query']}: resolved uid={uid} nickname={user['nickname']} "
            f"games={len(games)} users={len(seen_users)}/{args.max_users}"
        )
        for game in games:
            add_opponents(queue, seen_users, game)
            if int(game.get("movenum", 0)) < args.min_moves:
                continue
            chessid = str(game.get("chessid", "")).strip()
            if not chessid or chessid in seen_chessids:
                continue
            buckets = needed_rank_buckets(game, rank_counts, args.samples_per_rank)
            if not buckets:
                continue
            sgf = fetch_sgf(chessid)
            if not sgf:
                print(f"  skip {chessid}: empty SGF")
                continue
            path = out_dir / sample_filename(game, chessid)
            path.write_text(sgf, encoding="utf-8")
            seen_chessids.add(chessid)
            for rank in buckets:
                rank_counts[rank] = rank_counts.get(rank, 0) + 1
            entry = manifest_entry(user, game, path)
            entry["rank_buckets"] = buckets
            manifest.write(json.dumps(entry, ensure_ascii=False) + "\n")
            manifest.flush()
            total += 1
            print(f"  wrote {path} buckets={','.join(buckets)}")
            time.sleep(max(args.sleep, 0.0))

    print("rank summary:")
    for rank in sorted(rank_counts, key=rank_sort_key):
        print(f"  {rank}: {rank_counts[rank]}")
    return total


def add_opponents(queue: list[dict[str, str]], seen_users: set[str], game: dict[str, Any]) -> None:
    for side in ("black", "white"):
        uid = str(game.get(f"{side}uid", "")).strip()
        if not uid or uid == "0" or uid in seen_users:
            continue
        if any(item["uid"] == uid for item in queue):
            continue
        name = str(game.get(f"{side}nick") or game.get(f"{side}enname") or uid)
        queue.append({"uid": uid, "nickname": name, "query": uid})


def needed_rank_buckets(
    game: dict[str, Any], rank_counts: dict[str, int], samples_per_rank: int
) -> list[str]:
    buckets: list[str] = []
    for key in ("blackdan", "whitedan"):
        rank = fox_rank(game.get(key))
        if not rank:
            continue
        if rank_counts.get(rank, 0) >= samples_per_rank:
            continue
        if rank not in buckets:
            buckets.append(rank)
    return buckets


def rank_sort_key(rank: str) -> tuple[int, int]:
    match = re.fullmatch(r"(\d+)([dk])", rank)
    if not match:
        return (0, 0)
    value = int(match.group(1))
    unit = match.group(2)
    if unit == "k":
        return (0, -value)
    return (1, value)


def resolve_user(player: str) -> dict[str, str]:
    text = player.strip()
    if not text:
        raise SystemExit("empty player")
    if text.isdigit():
        return {"uid": text, "nickname": text, "query": text}
    url = f"{QUERY_USER_URL}?srcuid=0&username={quote(text)}"
    data = get_json(url)
    result = data.get("result", data.get("errcode", -1))
    if result != 0:
        message = data.get("resultstr") or data.get("errmsg") or f"Fox account not found: {text}"
        raise SystemExit(message)
    uid = str(data.get("uid", "")).strip()
    if not uid:
        raise SystemExit(f"Fox account found but UID is empty: {text}")
    nickname = first_non_empty(
        str(data.get("username", "")),
        str(data.get("name", "")),
        str(data.get("englishname", "")),
        text,
    )
    return {"uid": uid, "nickname": nickname, "query": text}


def fetch_chess_list(uid: str, last_code: str = "0") -> list[dict[str, Any]]:
    url = (
        f"{BASE_URL}/YHWQFetchChessList?srcuid=0&dstuid={quote(uid)}"
        f"&type=1&lastcode={quote(last_code)}&searchkey=&uin={quote(uid)}"
    )
    data = get_json(url)
    if int(data.get("result", -1)) != 0:
        raise SystemExit(data.get("resultstr") or f"Fox list request failed for uid={uid}")
    chesslist = data.get("chesslist", [])
    if not isinstance(chesslist, list):
        return []
    return [game for game in chesslist if isinstance(game, dict)]


def fetch_sgf(chessid: str) -> str:
    url = f"{BASE_URL}/YHWQFetchChess?chessid={quote(chessid)}"
    data = get_json(url)
    if int(data.get("result", -1)) != 0:
        return ""
    sgf = data.get("chess", "")
    return sgf if isinstance(sgf, str) else ""


def get_json(url: str) -> dict[str, Any]:
    request = urllib.request.Request(url, headers={"User-Agent": MOBILE_USER_AGENT})
    with urllib.request.urlopen(request, timeout=25) as response:
        text = response.read().decode("utf-8", errors="replace")
    return json.loads(text)


def sample_filename(game: dict[str, Any], chessid: str) -> str:
    date = str(game.get("starttime", "")).replace(":", "").replace(" ", "_")
    black = safe_name(str(game.get("blacknick") or game.get("blackenname") or "black"))
    white = safe_name(str(game.get("whitenick") or game.get("whiteenname") or "white"))
    prefix = safe_name(date) if date else chessid
    return f"{prefix}_{black}_vs_{white}_{chessid}.sgf"


def manifest_entry(user: dict[str, str], game: dict[str, Any], path: Path) -> dict[str, Any]:
    return {
        "path": str(path),
        "query": user["query"],
        "uid": user["uid"],
        "nickname": user["nickname"],
        "chessid": str(game.get("chessid", "")),
        "starttime": game.get("starttime", ""),
        "movenum": game.get("movenum", 0),
        "black": {
            "uid": str(game.get("blackuid", "")),
            "name": game.get("blacknick") or game.get("blackenname") or "",
            "rank": fox_rank(game.get("blackdan")),
        },
        "white": {
            "uid": str(game.get("whiteuid", "")),
            "name": game.get("whitenick") or game.get("whiteenname") or "",
            "rank": fox_rank(game.get("whitedan")),
        },
        "winner": game.get("winner", ""),
        "reason": game.get("reason", ""),
        "handicap": game.get("handicap", 0),
        "komi": game.get("komi", 0),
    }


def fox_rank(raw: Any) -> str:
    try:
        rank = int(raw) - 17
    except (TypeError, ValueError):
        return ""
    return f"{rank}d" if rank > 0 else f"{abs(rank) + 1}k"


def safe_name(text: str) -> str:
    clean = re.sub(r"[\\/:*?\"<>|\s]+", "_", text.strip())
    clean = clean.strip("._")
    return clean[:80] or "unknown"


def first_non_empty(*values: str) -> str:
    for value in values:
        if value and value.strip():
            return value.strip()
    return ""


def quote(text: str) -> str:
    return urllib.parse.quote(text or "", safe="")


if __name__ == "__main__":
    raise SystemExit(main())

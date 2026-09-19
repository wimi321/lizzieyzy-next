#!/usr/bin/env python3
"""Accept a verified Linux final archive through its bundled launcher and runtime."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import re
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import time
from typing import Any
import zipfile

try:
    from scripts import release_asset_provenance as provenance
except ModuleNotFoundError:
    import release_asset_provenance as provenance  # type: ignore[no-redef]


SCENARIOS = ("cpu-offline-first-run", "variant-launch")
BACKENDS = {
    "linux64": "cpu",
    "linux64_opencl": "opencl",
    "linux64_nvidia": "nvidia",
}
PHASES = ("identity", "extraction", "launch", "verify", "cleanup")
FIXTURE_MODE = os.environ.get("LIZZIE_LINUX_ACCEPTANCE_FIXTURE_MODE") == "1"


class AcceptanceError(RuntimeError):
    pass


class BlockedError(AcceptanceError):
    def __init__(self, phase: str, message: str) -> None:
        super().__init__(message)
        self.phase = phase

class RuntimePending(AcceptanceError):
    pass


def now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def json_sha256(value: object) -> str:
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(encoded).hexdigest()


def write_json_atomic(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(dir=path.parent, prefix=f".{path.name}.", suffix=".tmp")
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise


def read_json(path: Path, label: str) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise AcceptanceError(f"Unable to read {label} {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise AcceptanceError(f"{label} must be a JSON object: {path}")
    return value


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AcceptanceError(message)


def canonical_candidate(path: Path) -> dict[str, Any]:
    supplied = read_json(path, "candidate.json")
    artifact = supplied.get("artifact")
    candidate_provenance = supplied.get("provenance")
    require(isinstance(artifact, dict) and isinstance(candidate_provenance, dict), "Candidate identity is incomplete")
    require(supplied.get("schemaVersion") == 1, "Unsupported candidate schema")
    require(supplied.get("platform") == "linux", "Candidate platform must be linux")
    require(supplied.get("architecture") == "x86_64", "Candidate architecture must be x86_64")
    try:
        verified = provenance.verify_candidate(
            Path(str(candidate_provenance["path"])),
            str(supplied["platform"]),
            str(supplied["dateTag"]),
            str(supplied["releaseTag"]),
            str(supplied["targetSha"]),
            int(supplied["workflowRunId"]),
            int(supplied["workflowRunAttempt"]),
            str(artifact["name"]),
            Path(str(artifact["sourcePath"])),
        )
    except (KeyError, TypeError, ValueError, OSError, provenance.ProvenanceError) as exc:
        raise AcceptanceError(f"Candidate verification failed: {exc}") from exc
    require(supplied == verified, "candidate.json differs from the canonical verified candidate")
    require(artifact.get("class") == "linux-product", "Candidate class must be linux-product")
    require(artifact.get("key") in BACKENDS, "Candidate is not one of the three Linux final products")
    return supplied


def expected_backend(candidate: dict[str, Any], scenario: str) -> str:
    backend = BACKENDS[str(candidate["artifact"]["key"])]
    if scenario == "cpu-offline-first-run":
        require(backend == "cpu", "cpu-offline-first-run requires the linux64.with-katago candidate")
    else:
        require(backend in {"opencl", "nvidia"}, "variant-launch requires the Linux OpenCL or NVIDIA candidate")
    return backend


def safe_extract(archive_path: Path, destination: Path, expected_root: str) -> Path:
    require(not destination.exists(), f"Extraction destination must be new: {destination}")
    destination.mkdir(parents=True)
    seen: set[str] = set()
    roots: set[str] = set()
    try:
        with zipfile.ZipFile(archive_path) as archive:
            require(bool(archive.infolist()), "Candidate archive is empty")
            for entry in archive.infolist():
                raw = entry.filename
                require(bool(raw) and "\x00" not in raw and "\\" not in raw, f"Unsafe archive entry: {raw!r}")
                relative = PurePosixPath(raw)
                require(not relative.is_absolute() and ".." not in relative.parts, f"Unsafe archive entry: {raw}")
                normalized = relative.as_posix().rstrip("/")
                require(bool(normalized) and normalized not in seen, f"Duplicate archive entry: {raw}")
                seen.add(normalized)
                roots.add(relative.parts[0])
                mode = entry.external_attr >> 16
                require(not stat.S_ISLNK(mode), f"Archive symlink is not allowed: {raw}")
            require(roots == {expected_root}, f"Archive must contain exactly the expected top-level root {expected_root!r}; found {sorted(roots)}")
            for entry in archive.infolist():
                relative = PurePosixPath(entry.filename)
                target = destination.joinpath(*relative.parts)
                resolved_parent = target.parent.resolve()
                require(resolved_parent == destination.resolve() or destination.resolve() in resolved_parent.parents, f"Archive entry escapes extraction root: {entry.filename}")
                if entry.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                    continue
                target.parent.mkdir(parents=True, exist_ok=True)
                require(not target.exists(), f"Archive entry replaces an existing path: {entry.filename}")
                with archive.open(entry) as source, target.open("xb") as output:
                    shutil.copyfileobj(source, output)
                mode = entry.external_attr >> 16
                if mode:
                    target.chmod(stat.S_IMODE(mode))
    except BaseException:
        shutil.rmtree(destination, ignore_errors=True)
        raise
    product_root = destination / expected_root
    require(product_root.is_dir(), "Archive top-level entry must be a directory")
    return product_root


def file_identity(path: Path, label: str, *, executable: bool = False) -> dict[str, Any]:
    require(path.is_file(), f"{label} is missing: {path}")
    if executable:
        require(os.access(path, os.X_OK), f"{label} is not executable: {path}")
    return {"path": str(path.resolve()), "sizeBytes": path.stat().st_size, "sha256": sha256_file(path)}


def resolve_layout(product_root: Path, backend: str) -> dict[str, Any]:
    launcher = product_root / "start-linux64.sh"
    runtime = product_root / "Lizzieyzy/runtime/linux-x64/bin/java"
    jar = product_root / "Lizzieyzy/lizzie-yzy2.5.3-shaded.jar"
    engine_root = product_root / "Lizzieyzy/engines/katago/linux-x64"
    engine = engine_root / "katago"
    marker = engine_root / "lizzieyzy-next-engine-backend.txt"
    configs_root = product_root / "Lizzieyzy/engines/katago/configs"
    weight = product_root / "Lizzieyzy/weights/default.bin.gz"
    engine_config = configs_root / "gtp.cfg"
    require(engine_config.is_file(), f"Bundled engine gtp.cfg is missing: {engine_config}")
    shaded = list((product_root / "Lizzieyzy").glob("*-shaded.jar"))
    require(shaded == [jar], "Final product must contain exactly the expected shaded JAR")
    require(configs_root.is_dir(), f"Bundled engine configs are missing: {configs_root}")
    configs = sorted(path for path in configs_root.rglob("*") if path.is_file())
    require(bool(configs), "Bundled engine configs are empty")
    marker_identity = file_identity(marker, "backend marker")
    marker_value = marker.read_text(encoding="utf-8").strip().lower()
    require(marker_value == backend, f"Backend marker {marker_value!r} does not match expected backend {backend!r}")
    return {
        "productRoot": str(product_root.resolve()),
        "launcher": file_identity(launcher, "start-linux64.sh", executable=True),
        "runtime": file_identity(runtime, "bundled Java", executable=True),
        "jar": file_identity(jar, "shaded JAR"),
        "backend": backend,
        "backendMarker": marker_identity | {"value": marker_value},
        "engine": file_identity(engine, "bundled engine", executable=True),
        "configs": [file_identity(path, "bundled engine config") for path in configs],
        "engineConfig": file_identity(engine_config, "bundled engine gtp.cfg"),
        "weight": file_identity(weight, "bundled weight"),
    }


def probe_runtime(runtime: Path, log_path: Path) -> tuple[str, str]:
    host_architecture = platform.machine().lower()
    require(host_architecture in {"amd64", "x86_64"}, f"Linux product acceptance requires an x86_64 host, found {host_architecture!r}")
    result = subprocess.run(
        [str(runtime), "-XshowSettings:properties", "-version"],
        capture_output=True,
        text=True,
        timeout=20,
    )
    output = (result.stdout + result.stderr).strip()
    log_path.write_text(output + "\n", encoding="utf-8")
    require(result.returncode == 0, f"Packaged Java -version failed with exit code {result.returncode}")
    architecture_match = re.search(r"(?m)^\s*os\.arch\s*=\s*(\S+)\s*$", output)
    require(architecture_match is not None, "Packaged Java properties do not report os.arch")
    architecture = architecture_match.group(1).lower()
    require(architecture in {"amd64", "x86_64"}, f"Packaged Java os.arch does not prove x86_64: {architecture!r}")
    return output, architecture


def tree_metadata_identity(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {"exists": False, "entries": 0, "sha256": None}
    digest = hashlib.sha256()
    entries = 0
    for child in sorted(path.rglob("*")):
        try:
            metadata = child.lstat()
        except FileNotFoundError:
            continue
        relative = child.relative_to(path).as_posix()
        kind = "link" if child.is_symlink() else "dir" if child.is_dir() else "file"
        digest.update(
            f"{relative}|{kind}|{metadata.st_size}|{metadata.st_mtime_ns}\n".encode()
        )
        entries += 1
    return {"exists": True, "entries": entries, "sha256": digest.hexdigest()}


def outside_data_snapshots(product_root: Path) -> dict[str, dict[str, Any]]:
    home = Path.home()
    return {
        "productRoot": tree_metadata_identity(product_root),
        "defaultUserRoot": tree_metadata_identity(home / ".lizzieyzy-next"),
        "legacyUserRoot": tree_metadata_identity(home / ".lizzieyzy-next-foxuid"),
    }


def process_table() -> dict[int, tuple[int, str, str]]:
    result: dict[int, tuple[int, str, str]] = {}
    for child in Path("/proc").iterdir():
        if not child.name.isdigit():
            continue
        try:
            status = (child / "status").read_text(encoding="utf-8")
            parent_match = re.search(r"^PPid:\s+(\d+)$", status, re.MULTILINE)
            cmdline = (child / "cmdline").read_bytes().replace(b"\0", b" ").decode(errors="replace").strip()
            executable = os.readlink(child / "exe")
        except (FileNotFoundError, PermissionError, ProcessLookupError, OSError):
            continue
        result[int(child.name)] = (int(parent_match.group(1)) if parent_match else 0, cmdline, executable)
    return result


def descendants(root_pid: int) -> list[int]:
    table = process_table()
    owned = {root_pid}
    changed = True
    while changed:
        changed = False
        for pid, (parent, _, _) in table.items():
            if parent in owned and pid not in owned:
                owned.add(pid)
                changed = True
    return sorted(owned)


def process_snapshot(pid: int, table: dict[int, tuple[int, str, str]]) -> dict[str, Any] | None:
    if pid not in table:
        return None
    try:
        arguments = [
            value.decode(errors="replace")
            for value in (Path(f"/proc/{pid}/cmdline").read_bytes().split(b"\0"))
            if value
        ]
        working_directory = os.readlink(f"/proc/{pid}/cwd")
        stat_fields = Path(f"/proc/{pid}/stat").read_text(encoding="utf-8").rsplit(")", 1)[1].split()
        start_time = stat_fields[19]
    except (FileNotFoundError, PermissionError, ProcessLookupError, OSError, IndexError):
        return None
    return {
        "pid": pid,
        "parentPid": table[pid][0],
        "commandLine": table[pid][1],
        "image": table[pid][2],
        "arguments": arguments,
        "workingDirectory": working_directory,
        "identity": f"{start_time}:{table[pid][2]}",
    }


def snapshots(pids: list[int]) -> list[dict[str, Any]]:
    table = process_table()
    result = [process_snapshot(pid, table) for pid in pids]
    return [row for row in result if row is not None]


def find_runtime_process(
    supervisor_pid: int,
    runtime: Path,
    jar: Path,
    data_root: Path,
) -> tuple[int, list[dict[str, Any]]]:
    resolved = str(runtime.resolve())
    current = snapshots(descendants(supervisor_pid))
    if FIXTURE_MODE:
        fixture_matches = [row for row in current if row["image"] == resolved or resolved in row["arguments"]]
        fixture_pids = {int(row["pid"]) for row in fixture_matches}
        matches = [row for row in fixture_matches if int(row["parentPid"]) not in fixture_pids]
    else:
        matches = [row for row in current if row["image"] == resolved]
    if not matches:
        raise RuntimePending("Packaged Java process is not running yet")
    require(len(matches) == 1, f"Expected exactly one packaged Java process, found {len(matches)}")
    runtime_row = matches[0]
    arguments = runtime_row["arguments"]
    work_argument = f"-Dlizzie.work.dir={data_root.resolve()}"
    require(work_argument in arguments, "Packaged Java command does not select the isolated work root")
    require("-jar" in arguments, "Packaged Java command does not use -jar")
    jar_index = arguments.index("-jar")
    require(jar_index + 1 < len(arguments), "Packaged Java command omits the JAR argument")
    launched_jar = Path(arguments[jar_index + 1])
    if not launched_jar.is_absolute():
        launched_jar = Path(runtime_row["workingDirectory"]) / launched_jar
    require(launched_jar.resolve() == jar.resolve(), "Packaged Java command does not load the extracted shaded JAR")
    return int(runtime_row["pid"]), current



def build_network_helper(evidence: Path) -> Path:
    helper = evidence / "offline-boundary.sh"
    helper.write_text(
        """#!/usr/bin/env bash
set -eu
ip link set lo up
ip route add default dev lo
ip -6 route add default dev lo
nft add table inet lizzie_acceptance
nft 'add chain inet lizzie_acceptance output { type filter hook output priority 0; policy accept; }'
nft add rule inet lizzie_acceptance output ip daddr != 127.0.0.0/8 counter drop
nft add rule inet lizzie_acceptance output ip6 daddr != ::1 counter drop
ip -j address >"$LIZZIE_NETWORK_START"
nft list ruleset >"$LIZZIE_NETWORK_RULES_START"
set +e
"$@"
status=$?
set -e
nft list ruleset >"$LIZZIE_NETWORK_RULES_FINAL"
exit "$status"
""",
        encoding="utf-8",
        newline="\n",
    )
    helper.chmod(0o755)
    return helper


def external_attempt_count(path: Path) -> int:
    require(path.is_file(), "Offline boundary did not retain its final counter state")
    counts = [int(value) for value in re.findall(r"counter packets (\d+)", path.read_text(encoding="utf-8"))]
    require(len(counts) == 2, f"Offline boundary counter evidence is ambiguous: {counts}")
    return sum(counts)

def require_keys(value: object, names: set[str], label: str) -> dict[str, Any]:
    require(isinstance(value, dict), f"{label} must be an object")
    assert isinstance(value, dict)
    require(set(value) == names, f"{label} fields differ; expected={sorted(names)} actual={sorted(value)}")
    return value


def wait_engine_oracle(path: Path, timeout_seconds: int) -> Path:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        if path.is_file():
            return path
        time.sleep(0.2)
    raise AcceptanceError(f"Timed out waiting for the bound engine oracle producer to atomically write: {path}")


def validate_engine_oracle(
    path: Path,
    run_record: dict[str, Any],
    run_path: Path,
    candidate: dict[str, Any],
) -> dict[str, Any]:
    envelope = require_keys(
        read_json(path, "Linux engine analysis oracle envelope"),
        {"schemaVersion", "scenario", "status", "binding", "oracle"},
        "engine oracle envelope",
    )
    require(
        envelope["schemaVersion"] == 1
        and envelope["scenario"] == "linux-final-product-real-cpu"
        and envelope["status"] == "PASS",
        "Engine oracle envelope identity did not PASS",
    )
    binding = require_keys(
        envelope["binding"],
        {
            "candidateSha256",
            "artifactSha256",
            "runJsonSha256",
            "launcherSha256",
            "runtimeSha256",
            "jarSha256",
            "dataRoot",
            "launcherPid",
            "enginePid",
            "engineCommand",
            "oracleSha256",
        },
        "engine oracle binding",
    )
    require(
        binding["candidateSha256"] == run_record["candidate"]["sha256"]
        and binding["artifactSha256"] == run_record["candidate"]["artifactSha256"],
        "Engine oracle candidate binding differs from this live run",
    )
    require(binding["runJsonSha256"] == sha256_file(run_path), "Engine oracle run-record binding differs from this live run")
    require(
        binding["launcherSha256"] == run_record["launcher"]["sha256"]
        and binding["runtimeSha256"] == run_record["runtime"]["sha256"]
        and binding["jarSha256"] == run_record["jar"]["sha256"],
        "Engine oracle packaged runtime binding differs from this live run",
    )
    require(
        binding["dataRoot"] == run_record["dataRoot"]
        and binding["launcherPid"] == run_record["launcher"]["pid"],
        "Engine oracle process/data-root binding differs from this live run",
    )
    oracle = require_keys(
        envelope["oracle"],
        {
            "schemaVersion", "scenario", "status", "failure", "peer", "source", "platform",
            "manifest", "assets", "fixture", "node", "rules", "position", "analysis",
            "phasesMs", "stop", "quit", "cleanup", "evidence",
        },
        "engine-sgf oracle",
    )
    require(binding["oracleSha256"] == json_sha256(oracle), "Engine oracle payload hash binding differs")
    require(oracle["schemaVersion"] == 1 and oracle["scenario"] == "real-cpu-engine" and oracle["status"] == "PASS" and oracle["failure"] is None, "Engine-sgf oracle did not PASS")
    peer = require_keys(oracle["peer"], {"kind", "catalogEngineId", "command", "pid", "stdoutReader", "stderrReader"}, "engine oracle peer")
    production_identity = all(
        isinstance(peer[name], str) and bool(peer[name].strip())
        for name in ("catalogEngineId", "stdoutReader", "stderrReader")
    )
    require(
        peer["kind"] == "real-katago-cpu"
        and production_identity
        and peer["pid"] == binding["enginePid"]
        and peer["command"] == binding["engineCommand"],
        "Engine oracle does not prove bound production catalog/manager ownership and reader identities",
    )
    engine_pid = int(binding["enginePid"])
    engine_path = run_record["engine"]["path"]
    model_path = run_record["engine"]["modelPath"]
    config_path = run_record["engine"]["configPath"]
    captured = [row for row in run_record["processes"] if int(row["pid"]) == engine_pid]
    require(len(captured) == 1, "Engine oracle PID is not a captured packaged engine incarnation")
    engine_command = str(captured[0]["commandLine"])
    engine_image = str(captured[0]["image"])
    engine_identity_matches = engine_image == engine_path
    if FIXTURE_MODE:
        engine_identity_matches = engine_identity_matches or engine_path in engine_command
    require(engine_identity_matches and engine_command == binding["engineCommand"], "Engine oracle PID is not the captured packaged engine incarnation")
    require(engine_pid not in process_table(), "Engine oracle claims cleanup but the bound engine PID is still live")
    require(all(path in engine_command for path in (engine_path, model_path, config_path)), "Bound engine command does not name the packaged executable/model/config")
    require(oracle["source"]["commit"] == candidate["targetSha"] and oracle["source"]["dirty"] is False and re.search(r"(?i)linux", str(oracle["platform"]["os"])), "Engine oracle source/platform identity differs from this Linux candidate")
    assets = oracle["assets"]
    require(
        assets["enginePath"] == engine_path
        and assets["engineSha256"] == run_record["engine"]["sha256"]
        and assets["modelPath"] == model_path
        and assets["modelSha256"] == run_record["engine"]["modelSha256"]
        and assets["configPath"] == config_path
        and assets["configSha256"] == run_record["engine"]["configSha256"],
        "Engine oracle does not use this product's packaged engine closure",
    )
    require(oracle["fixture"]["sha256"] == "ec41cf044ee29b2408b488727db2b6bbe5a2a3acfe5ed74d70fbb6dac2be3a9c", "Engine oracle did not use the frozen D4 fixture")
    node = oracle["node"]
    require(node["semanticPath"] == "0/0/0/0/0" and node["kind"] == "PASS" and bool(node["identity"]), "Engine oracle frozen semantic node is invalid")
    rules = oracle["rules"]
    actual_rules = require_keys(rules["actual"], {"friendlyPassOk", "scoring", "ko", "whiteHandicapBonus", "suicide", "tax", "hasButton"}, "engine oracle actual rules")
    require(rules["targetRaw"] == "Chinese" and rules["targetSummary"] == "CHINESE" and int(rules["targetRevision"]) > 0 and rules["status"] == "CONFIRMED" and rules["fresh"] is True, "Engine oracle session rules target is invalid")
    require(actual_rules == {"friendlyPassOk": True, "scoring": "AREA", "ko": "SIMPLE", "whiteHandicapBonus": "N", "suicide": False, "tax": "NONE", "hasButton": False}, "Engine oracle fresh actual Chinese rules are invalid")
    position = oracle["position"]
    require(
        position == {"confirmed": True, "board": "19x19", "komi": 6.5, "stones": "B:fd;W:ee,ff,hh", "empty": "dd", "turn": "W", "setupKind": "SNAPSHOT", "setupStones": "B:fd;W:ee,ff", "tailPrevious": "MOVE:W[hh]", "tailCurrent": "PASS:B[]"},
        "Engine oracle peer position differs from the frozen SNAPSHOT/MOVE/PASS target",
    )
    analysis = oracle["analysis"]
    require(analysis["schema"] == "katago-info-v1" and int(analysis["visits"]) > 0 and bool(analysis["move"]), "Engine oracle has no structurally parsed positive-visit candidate")
    stop = oracle["stop"]
    require(stop["requested"] is True and stop["quiet"] is True and stop["quietWindowMs"] == 400 and int(stop["peerOutputCount"]) > 0 and int(stop["applicationVisits"]) > 0, "Engine oracle quiet-stop evidence is incomplete")
    oracle_cleanup = oracle["cleanup"]
    require(oracle["quit"]["normal"] is True and oracle_cleanup["forced"] is False and oracle_cleanup["process"] is True and oracle_cleanup["readers"] is True and oracle_cleanup["stagedSgf"] is True, "Engine oracle normal quit/cleanup is incomplete")
    for name in ("result", "stdout", "stderr", "appLog", "phases"):
        require(Path(str(oracle["evidence"][name])).is_file(), f"Engine oracle evidence is missing: {name}")
    raw_result = read_json(Path(str(oracle["evidence"]["result"])), "engine oracle result evidence")
    require(json_sha256(raw_result) == binding["oracleSha256"], "Engine oracle result evidence differs from the bound payload")
    staged = oracle["evidence"]["stagedSgfs"]
    require(isinstance(staged, list) and bool(staged) and not any(Path(str(item)).exists() for item in staged), "Engine oracle staged SGF cleanup evidence is invalid")
    return envelope


def display_windows() -> tuple[str, dict[str, str]]:
    result = subprocess.run(["xwininfo", "-root", "-tree"], capture_output=True, text=True, timeout=5)
    require(result.returncode == 0, f"Unable to inspect the Linux display: {result.stderr}")
    windows = {
        match.group(1).lower(): match.group(2)
        for match in re.finditer(r'^\s*(0x[0-9a-fA-F]+)\s+"([^"]*)"', result.stdout, re.MULTILINE)
    }
    return result.stdout + result.stderr, windows


def window_process_id(window_id: str) -> tuple[int | None, str]:
    result = subprocess.run(
        ["xprop", "-id", window_id, "_NET_WM_PID"],
        capture_output=True,
        text=True,
        timeout=5,
    )
    output = result.stdout + result.stderr
    match = re.search(r"_NET_WM_PID\([^)]*\)\s*=\s*(\d+)", output)
    return (int(match.group(1)) if result.returncode == 0 and match else None), output


def wait_for_ready(
    supervisor: subprocess.Popen[bytes],
    runtime: Path,
    jar: Path,
    data_root: Path,
    fixture_window: Path,
    evidence: Path,
    scenario: str,
    timeout_seconds: int,
    baseline_windows: set[str],
) -> tuple[int, str, list[dict[str, Any]], Path, str]:
    deadline = time.monotonic() + timeout_seconds
    app_log = data_root / "logs/app.log"
    window_evidence = evidence / "window-tree.txt"
    while time.monotonic() < deadline:
        if supervisor.poll() is not None:
            raise AcceptanceError(f"Packaged launcher exited before readiness with code {supervisor.returncode}")
        try:
            runtime_pid, process_evidence = find_runtime_process(
                supervisor.pid,
                runtime,
                jar,
                data_root,
            )
        except RuntimePending:
            time.sleep(0.1)
            continue
        log_text = app_log.read_text(encoding="utf-8", errors="replace") if app_log.is_file() else ""
        ready_log = "application ready" in log_text.lower()
        repair_log = bool(re.search(r"(?i)(backend unavailable|driver unavailable|repair required)", log_text))
        config_ready = (data_root / "config.txt").is_file() and (data_root / "persist").is_file()
        window_id = ""
        if FIXTURE_MODE:
            visible = False
            if fixture_window.is_file():
                marker = fixture_window.read_text(encoding="utf-8").strip()
                visible = marker == str(runtime_pid)
                if visible:
                    window_id = "fixture-window"
                window_evidence.write_text(
                    f"fixture visible marker={marker!r} expectedPid={runtime_pid}\n",
                    encoding="utf-8",
                )
        else:
            window_tree, windows = display_windows()
            details = [window_tree]
            visible = False
            for candidate_id, title in windows.items():
                if candidate_id in baseline_windows or "lizzie" not in title.lower():
                    continue
                owner_pid, property_output = window_process_id(candidate_id)
                details.append(f"\n[{candidate_id} owner={owner_pid}]\n{property_output}")
                if owner_pid == runtime_pid:
                    visible = True
                    window_id = candidate_id
                    break
            window_evidence.write_text("".join(details), encoding="utf-8")
        if config_ready and visible and (ready_log or (scenario == "variant-launch" and repair_log)):
            readiness = "application-ready" if ready_log else "explicit-repair"
            return runtime_pid, readiness, process_evidence, app_log, window_id
        time.sleep(0.2)
    raise AcceptanceError("Timed out waiting for a new product-owned visible window, application readiness, isolated config.txt and persist")


def process_group_members(process_group: int) -> list[int]:
    members: list[int] = []
    for pid in process_table():
        try:
            if os.getpgid(pid) == process_group:
                members.append(pid)
        except (ProcessLookupError, PermissionError):
            continue
    return sorted(members)


def terminate_owned(supervisor: subprocess.Popen[bytes], runtime_pid: int, timeout_seconds: int) -> tuple[bool, list[int], list[str]]:
    errors: list[str] = []
    process_group = supervisor.pid
    owned: dict[int, str] = {}

    def remember_owned() -> None:
        for row in snapshots(descendants(supervisor.pid)):
            owned[int(row["pid"])] = str(row["identity"])

    def surviving_owned() -> list[int]:
        table = process_table()
        survivors = set(process_group_members(process_group))
        for pid, identity in owned.items():
            row = process_snapshot(pid, table)
            if row is not None and row["identity"] == identity:
                survivors.add(pid)
        return sorted(survivors)

    remember_owned()
    if runtime_pid:
        try:
            os.kill(runtime_pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
    graceful_deadline = time.monotonic() + timeout_seconds
    while supervisor.poll() is None and time.monotonic() < graceful_deadline:
        remember_owned()
        time.sleep(0.05)
    if supervisor.poll() is None:
        try:
            os.killpg(process_group, signal.SIGTERM)
        except ProcessLookupError:
            pass
        forced_deadline = time.monotonic() + timeout_seconds
        while supervisor.poll() is None and time.monotonic() < forced_deadline:
            remember_owned()
            time.sleep(0.05)
    survivors = surviving_owned()
    for pid in survivors:
        try:
            os.kill(pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
    time.sleep(0.1)
    survivors = surviving_owned()
    for pid in survivors:
        try:
            os.kill(pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
    try:
        supervisor.wait(timeout=timeout_seconds)
    except subprocess.TimeoutExpired:
        errors.append("owned supervisor did not exit after SIGKILL")
    time.sleep(0.1)
    remaining = surviving_owned()
    if remaining:
        errors.append(f"owned processes survived cleanup: {remaining}")
    return not remaining and not errors, remaining, errors


def capture_screenshot(evidence: Path, window_id: str) -> Path:
    screenshot = evidence / "application-window.xwd"
    if FIXTURE_MODE:
        screenshot.write_text(f"fixture screenshot marker window={window_id}\n", encoding="utf-8")
        return screenshot
    result = subprocess.run(
        ["xwd", "-id", window_id, "-silent", "-out", str(screenshot)],
        capture_output=True,
        text=True,
        timeout=15,
    )
    require(result.returncode == 0 and screenshot.is_file(), f"Unable to capture product-window evidence: {result.stderr}")
    return screenshot


def observed_model() -> dict[str, Any]:
    return {
        "candidate": {"path": None, "sha256": None, "artifactSha256": None, "provenancePath": None, "provenanceSha256": None, "targetSha": None, "releaseTag": None},
        "host": {"os": platform.system(), "distribution": platform.platform(), "kernel": platform.release(), "architecture": platform.machine(), "display": os.environ.get("DISPLAY"), "fixtureMode": FIXTURE_MODE},
        "product": {"archivePath": None, "extractionPath": None, "productRoot": None, "backend": None, "backendMarkerPath": None, "backendMarkerSha256": None},
        "launcher": {"path": None, "sha256": None, "pid": None, "command": None, "processes": None},
        "runtime": {"path": None, "sha256": None, "version": None, "architecture": None, "processImage": None},
        "jar": {"path": None, "sha256": None},
        "engine": {"path": None, "sha256": None, "configIdentities": None, "weightPath": None, "weightSha256": None},
        "dataRoot": {"path": None, "selection": None, "outsideWrites": None, "snapshotEvidence": None},
        "network": {"boundary": None, "counterEvidence": None, "blockedAttemptCount": None},
        "readiness": {"state": None, "applicationLog": None, "windowEvidence": None, "screenshot": None, "runRecord": None},
        "outcome": {"distributionStatus": None, "inferenceStatus": None, "summary": None, "engineOracle": None},
        "cleanup": {"remainingPids": None, "errors": None},
    }


def required_outcomes() -> dict[str, Any]:
    observations = [
        ("observed.candidate.sha256", "identity"),
        ("observed.candidate.artifactSha256", "identity"),
        ("observed.candidate.provenanceSha256", "identity"),
        ("observed.product.productRoot", "extraction"),
        ("observed.product.backend", "extraction"),
        ("observed.runtime.version", "extraction"),
        ("observed.launcher.pid", "launch"),
        ("observed.launcher.processes", "launch"),
        ("observed.dataRoot.path", "launch"),
        ("observed.dataRoot.outsideWrites", "cleanup"),
        ("observed.dataRoot.snapshotEvidence", "cleanup"),
        ("observed.readiness.state", "launch"),
        ("observed.readiness.windowEvidence", "launch"),
        ("observed.readiness.runRecord", "launch"),
        ("observed.outcome.distributionStatus", "verify"),
        ("observed.outcome.inferenceStatus", "verify"),
        ("observed.outcome.summary", "verify"),
        ("observed.network.blockedAttemptCount", "cleanup"),
        ("observed.cleanup.remainingPids", "cleanup"),
        ("observed.cleanup.errors", "cleanup"),
    ]
    assertions = [(f"assertions.{name}", phase) for name, phase in (("identity", "identity"), ("extracted", "extraction"), ("launched", "launch"), ("verified", "verify"), ("cleaned", "cleanup"))]
    evidence = [(f"evidence.{name}", phase) for name, phase in (("identity", "identity"), ("extraction", "extraction"), ("launch", "launch"), ("verification", "verify"), ("cleanup", "cleanup"))]
    return {
        "phases": list(PHASES),
        "observations": [{"path": path, "phase": phase} for path, phase in observations],
        "assertions": [{"path": path, "phase": phase} for path, phase in assertions],
        "evidence": [{"path": path, "phase": phase} for path, phase in evidence],
    }


def add_null_reasons(value: object, path: str, reasons: dict[str, str], phase: str) -> None:
    if value is None:
        reasons[path] = f"not observed before terminal phase {phase}"
    elif isinstance(value, dict):
        for key, child in value.items():
            add_null_reasons(child, f"{path}.{key}", reasons, phase)


def failure_kind(message: str) -> str:
    if "timed out" in message.lower():
        return "timeout"
    if re.search(r"(?i)(missing|mismatch|differs|expected|must|unexpected|does not|requires|unsafe|ambiguous)", message):
        return "assertion"
    return "error"


def run(candidate_path: Path, scenario: str, evidence: Path) -> int:
    started_at = now()
    require(not evidence.exists(), f"Evidence directory must be new: {evidence}")
    evidence.mkdir(parents=True)
    record_path = evidence / "acceptance.json"
    observed = observed_model()
    assertions: dict[str, bool | None] = {"identity": None, "extracted": None, "launched": None, "verified": None, "cleaned": None}
    evidence_values: dict[str, str | None] = {"identity": None, "extraction": None, "launch": None, "verification": None, "cleanup": None}
    cleanup = {"complete": False, "remainingOwnedResources": []}
    phase = "identity"
    status = "FAIL"
    failure: dict[str, Any] | None = None
    reason: str | None = None
    blocked_phase: str | None = None
    candidate: dict[str, Any] | None = None
    backend = ""
    supervisor: subprocess.Popen[bytes] | None = None
    runtime_pid = 0
    layout: dict[str, Any] | None = None
    final_counter = evidence / "network-counter-final.txt"
    run_path = evidence / "run.json"
    oracle_path: Path | None = None
    try:
        if not candidate_path.is_file():
            raise BlockedError("identity", f"The requested candidate.json is unavailable: {candidate_path}")
        candidate = canonical_candidate(candidate_path.resolve())
        backend = expected_backend(candidate, scenario)
        observed["candidate"].update(
            path=str(candidate_path.resolve()),
            sha256=sha256_file(candidate_path),
            artifactSha256=candidate["artifact"]["sha256"],
            provenancePath=candidate["provenance"]["path"],
            provenanceSha256=candidate["provenance"]["sha256"],
            targetSha=candidate["targetSha"],
            releaseTag=candidate["releaseTag"],
        )
        observed["product"]["archivePath"] = candidate["artifact"]["sourcePath"]
        assertions["identity"] = True
        evidence_values["identity"] = str(candidate_path.resolve())

        phase = "extraction"
        expected_root = Path(str(candidate["artifact"]["name"])).stem
        extraction = evidence / "验收 提取 archive"
        product_root = safe_extract(Path(str(candidate["artifact"]["sourcePath"])), extraction, expected_root)
        layout = resolve_layout(product_root, backend)
        outside_before = outside_data_snapshots(product_root)
        outside_before_path = evidence / "outside-data-before.json"
        write_json_atomic(outside_before_path, outside_before)
        runtime_path = Path(layout["runtime"]["path"])
        runtime_version, runtime_architecture = probe_runtime(runtime_path, evidence / "runtime-version.log")
        observed["product"].update(
            extractionPath=str(extraction.resolve()),
            productRoot=layout["productRoot"],
            backend=backend,
            backendMarkerPath=layout["backendMarker"]["path"],
            backendMarkerSha256=layout["backendMarker"]["sha256"],
        )
        observed["runtime"].update(path=layout["runtime"]["path"], sha256=layout["runtime"]["sha256"], version=runtime_version, architecture=runtime_architecture)
        observed["jar"].update(path=layout["jar"]["path"], sha256=layout["jar"]["sha256"])
        observed["engine"].update(
            path=layout["engine"]["path"],
            sha256=layout["engine"]["sha256"],
            configIdentities=layout["configs"],
            weightPath=layout["weight"]["path"],
            weightSha256=layout["weight"]["sha256"],
        )
        assertions["extracted"] = True
        evidence_values["extraction"] = str(extraction.resolve())

        phase = "launch"
        required_commands = ("unshare", "ip", "nft")
        if not FIXTURE_MODE:
            required_commands += ("xwininfo", "xprop", "xwd")
        for command in required_commands:
            if not shutil.which(command):
                raise BlockedError("launch", f"Required Linux acceptance command is unavailable: {command}")
        if scenario == "cpu-offline-first-run":
            oracle_text = os.environ.get("LIZZIE_LINUX_ACCEPTANCE_ENGINE_ORACLE", "")
            if not oracle_text:
                raise BlockedError("launch", "LIZZIE_LINUX_ACCEPTANCE_ENGINE_ORACLE must name this run's new engine-sgf envelope path")
            oracle_path = Path(oracle_text).resolve()
            if not oracle_path.parent.is_dir():
                raise BlockedError("launch", f"Engine oracle output parent does not exist: {oracle_path.parent}")
            require(not oracle_path.exists(), f"Engine oracle output path must be new to prevent replay: {oracle_path}")
        data_root = evidence / "隔离 数据 root"
        data_root.mkdir()
        fixture_window = evidence / "fixture-window-ready.txt"
        baseline_windows: set[str] = set()
        if not FIXTURE_MODE:
            baseline_tree, baseline = display_windows()
            baseline_windows = set(baseline)
            (evidence / "window-tree-before.txt").write_text(baseline_tree, encoding="utf-8")
        helper = build_network_helper(evidence)
        launcher_stdout = (evidence / "launcher.stdout.log").open("wb")
        launcher_stderr = (evidence / "launcher.stderr.log").open("wb")
        environment = os.environ.copy()
        environment.update(
            {
                "LIZZIE_WORK_DIR": str(data_root.resolve()),
                "LIZZIE_NETWORK_START": str((evidence / "network-addresses.json").resolve()),
                "LIZZIE_NETWORK_RULES_START": str((evidence / "network-rules-start.txt").resolve()),
                "LIZZIE_NETWORK_RULES_FINAL": str(final_counter.resolve()),
                "LIZZIE_LINUX_ACCEPTANCE_FIXTURE_WINDOW": str(fixture_window.resolve()),
            }
        )
        launcher_command = [
            "unshare",
            "--user",
            "--map-root-user",
            "--net",
            "--fork",
            "--kill-child=TERM",
            str(helper),
            layout["launcher"]["path"],
        ]
        supervisor = subprocess.Popen(
            launcher_command,
            cwd=product_root,
            env=environment,
            stdout=launcher_stdout,
            stderr=launcher_stderr,
            start_new_session=True,
        )
        timeout_seconds = int(os.environ.get("LIZZIE_LINUX_ACCEPTANCE_TIMEOUT_SECONDS", "90"))
        runtime_pid, readiness, process_evidence, app_log, window_id = wait_for_ready(
            supervisor,
            runtime_path,
            Path(layout["jar"]["path"]),
            data_root,
            fixture_window,
            evidence,
            scenario,
            timeout_seconds,
            baseline_windows,
        )
        screenshot = capture_screenshot(evidence, window_id)
        process_evidence = snapshots(descendants(supervisor.pid))
        runtime_row = next(row for row in process_evidence if row["pid"] == runtime_pid)
        observed["launcher"].update(path=layout["launcher"]["path"], sha256=layout["launcher"]["sha256"], pid=runtime_pid, command=launcher_command, processes=process_evidence)
        observed["runtime"]["processImage"] = runtime_row["image"]
        observed["dataRoot"].update(path=str(data_root.resolve()), selection="LIZZIE_WORK_DIR launcher environment")
        observed["network"].update(boundary="private user+network namespace with nft output drop counters", counterEvidence=str(final_counter.resolve()))
        run_record = {
            "schemaVersion": 1,
            "state": "RUNNING",
            "candidate": {"path": observed["candidate"]["path"], "sha256": observed["candidate"]["sha256"], "artifactSha256": observed["candidate"]["artifactSha256"]},
            "launcher": {"path": layout["launcher"]["path"], "sha256": layout["launcher"]["sha256"], "pid": runtime_pid, "command": launcher_command},
            "runtime": {"path": layout["runtime"]["path"], "sha256": layout["runtime"]["sha256"], "version": runtime_version, "processImage": runtime_row["image"]},
            "jar": {"path": layout["jar"]["path"], "sha256": layout["jar"]["sha256"]},
            "engine": {"path": layout["engine"]["path"], "sha256": layout["engine"]["sha256"], "configPath": layout["engineConfig"]["path"], "configSha256": layout["engineConfig"]["sha256"], "modelPath": layout["weight"]["path"], "modelSha256": layout["weight"]["sha256"]},
            "dataRoot": str(data_root.resolve()),
            "networkBoundary": "private user+network namespace with nft output drop counters",
            "processes": process_evidence,
            "startedAt": started_at,
        }
        write_json_atomic(run_path, run_record)
        observed["readiness"].update(state=readiness, applicationLog=str(app_log.resolve()), windowEvidence=str((evidence / "window-tree.txt").resolve()), screenshot=str(screenshot.resolve()), runRecord=str(run_path.resolve()))
        assertions["launched"] = True
        evidence_values["launch"] = str(run_path.resolve())

        phase = "verify"
        if scenario == "cpu-offline-first-run":
            assert oracle_path is not None
            bound_oracle_path = wait_engine_oracle(oracle_path, timeout_seconds)
            validate_engine_oracle(bound_oracle_path, run_record, run_path, candidate)
            observed["outcome"].update(
                distributionStatus="PASS",
                inferenceStatus="PASS",
                summary="Bound complete engine-sgf real CPU oracle PASS.",
                engineOracle=str(bound_oracle_path),
            )
            evidence_values["verification"] = str(bound_oracle_path)
        else:
            observed["outcome"].update(
                distributionStatus="PASS",
                inferenceStatus="BLOCKED",
                summary=f"Linux {backend} final-product distribution reached {readiness}; real {backend} inference remains separately BLOCKED.",
                engineOracle="NOT_APPLICABLE",
            )
            evidence_values["verification"] = str(app_log.resolve())
        assertions["verified"] = True

        phase = "cleanup"
        complete, remaining, errors = terminate_owned(
            supervisor,
            runtime_pid,
            int(os.environ.get("LIZZIE_LINUX_ACCEPTANCE_STOP_SECONDS", "15")),
        )
        supervisor = None
        launcher_stdout.close()
        launcher_stderr.close()
        observed["cleanup"].update(remainingPids=remaining, errors=errors)
        cleanup["remainingOwnedResources"] = [f"process:{pid}" for pid in remaining] + errors
        cleanup["complete"] = complete
        assertions["cleaned"] = complete
        outside_after = outside_data_snapshots(product_root)
        outside_after_path = evidence / "outside-data-after.json"
        write_json_atomic(outside_after_path, outside_after)
        outside_writes = [
            label for label in outside_before if outside_before[label] != outside_after[label]
        ]
        observed["dataRoot"].update(
            outsideWrites=outside_writes,
            snapshotEvidence=[str(outside_before_path.resolve()), str(outside_after_path.resolve())],
        )
        cleanup["remainingOwnedResources"].extend(
            f"outside-data-write:{label}" for label in outside_writes
        )
        cleanup["complete"] = cleanup["complete"] and not outside_writes
        assertions["cleaned"] = bool(cleanup["complete"])
        cleanup_summary = evidence / "cleanup-summary.json"
        write_json_atomic(
            cleanup_summary,
            {
                "processesComplete": complete,
                "remainingPids": remaining,
                "errors": errors,
                "outsideWrites": outside_writes,
                "networkCounterPath": str(final_counter.resolve()),
                "networkCounterExists": final_counter.is_file(),
            },
        )
        evidence_values["cleanup"] = str(cleanup_summary.resolve())
        attempts = external_attempt_count(final_counter)
        observed["network"]["blockedAttemptCount"] = attempts
        require(not outside_writes, f"Application wrote outside the isolated data root: {outside_writes}")
        require(attempts == 0, f"Owned product attempted {attempts} external network connection(s)")
        require(complete, "Owned process cleanup did not complete")
        status = "PASS"
    except BaseException as exc:
        message = str(exc) or exc.__class__.__name__
        if supervisor is not None:
            complete, remaining, errors = terminate_owned(
                supervisor,
                runtime_pid,
                int(os.environ.get("LIZZIE_LINUX_ACCEPTANCE_STOP_SECONDS", "15")),
            )
            if phase == "cleanup":
                observed["cleanup"].update(remainingPids=remaining, errors=errors)
            cleanup["remainingOwnedResources"] = [f"process:{pid}" for pid in remaining] + errors
            cleanup["complete"] = complete
        elif phase in {"identity", "extraction", "launch"}:
            cleanup["complete"] = True
            cleanup["remainingOwnedResources"] = []
        if isinstance(exc, BlockedError):
            status = "BLOCKED"
            blocked_phase = exc.phase
            reason = message
            blocked_index = PHASES.index(blocked_phase)
            phase = PHASES[0 if blocked_index == 0 else blocked_index - 1]
        else:
            status = "FAIL"
            assertion_for_phase = {"identity": "identity", "extraction": "extracted", "launch": "launched", "verify": "verified", "cleanup": "cleaned"}[phase]
            assertions[assertion_for_phase] = False
            diagnostic = evidence / f"{phase}-failure.txt"
            diagnostic.write_text(message + "\n", encoding="utf-8")
            failure = {"kind": failure_kind(message), "summary": message, "diagnostics": [str(diagnostic.resolve()), str(record_path.resolve())]}
    finally:
        for stream_name in ("launcher_stdout", "launcher_stderr"):
            stream = locals().get(stream_name)
            if stream is not None and not stream.closed:
                stream.close()

    if candidate is None:
        try:
            candidate = read_json(candidate_path, "candidate.json")
        except AcceptanceError:
            print(f"Linux product acceptance {status}: unable to construct a schema-valid record without candidate identity", file=sys.stderr)
            return 1
    not_observed: dict[str, str] = {}
    add_null_reasons(observed, "observed", not_observed, phase)
    add_null_reasons(assertions, "assertions", not_observed, phase)
    add_null_reasons(evidence_values, "evidence", not_observed, phase)
    record = {
        "schemaVersion": 1,
        "scenarioId": scenario,
        "status": status,
        "phase": phase,
        "startedAt": started_at,
        "finishedAt": now(),
        "expected": {
            "targetSha": candidate["targetSha"],
            "platform": "linux",
            "architecture": "x86_64",
            "artifact": {key: candidate["artifact"][key] for key in ("key", "name", "class")},
            "scenario": scenario,
            "backend": backend or BACKENDS.get(str(candidate.get("artifact", {}).get("key"))),
            "requiredOutcomes": required_outcomes(),
        },
        "observed": observed,
        "notObserved": not_observed,
        "assertions": assertions,
        "evidence": evidence_values,
        "cleanup": cleanup,
        "blockedPhase": blocked_phase,
        "reason": reason,
        "failure": failure,
    }
    try:
        provenance.validate_acceptance_record(record)
    except provenance.ProvenanceError as exc:
        fallback = evidence / "record-validation-failure.txt"
        fallback.write_text(f"{exc}\n", encoding="utf-8")
        print(f"Linux product acceptance record validation failed: {exc}", file=sys.stderr)
        write_json_atomic(record_path, record)
        return 1
    write_json_atomic(record_path, record)
    if status == "PASS":
        print(f"PASS {scenario} {record_path}")
        return 0
    print(f"Linux product acceptance {status}: {reason or failure['summary']} Evidence: {record_path}", file=sys.stderr)
    return 1


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate", required=True, type=Path)
    parser.add_argument("--scenario", required=True, choices=SCENARIOS)
    parser.add_argument("--evidence-dir", required=True, type=Path)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        return run(args.candidate, args.scenario, args.evidence_dir.resolve())
    except (OSError, AcceptanceError, ValueError) as exc:
        print(f"Linux product acceptance failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

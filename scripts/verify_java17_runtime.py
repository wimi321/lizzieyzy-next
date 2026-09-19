#!/usr/bin/env python3
"""Verify and exercise the full shaded JAR with an explicit Temurin 17 JDK."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import select
import shlex
import shutil
import signal
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


SCENARIOS = ("static", "application-smoke")
CLASSFILE_MAJOR_JAVA17 = 61
PHASES = {
    "static": ("identity", "static", "cleanup"),
    "application-smoke": ("identity", "static", "launch", "verify", "cleanup"),
}
FIXTURE_MODE = os.environ.get("LIZZIE_JAVA17_ACCEPTANCE_FIXTURE_MODE") == "1"


class VerificationError(RuntimeError):
    pass


class BlockedError(VerificationError):
    def __init__(self, phase: str, message: str) -> None:
        super().__init__(message)
        self.phase = phase

class AcquiredProcessCleanupError(VerificationError):
    def __init__(
        self,
        message: str,
        process: subprocess.Popen[str],
        identity: str | None,
    ) -> None:
        super().__init__(message)
        self.process = process
        self.identity = identity


def require(condition: bool, message: str) -> None:
    if not condition:
        raise VerificationError(message)


def now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_json_atomic(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        dir=path.parent, prefix=f".{path.name}.", suffix=".tmp"
    )
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


def parse_manifest(content: bytes) -> dict[str, str]:
    try:
        text = content.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise VerificationError(f"JAR manifest is not UTF-8: {exc}") from exc
    attributes: dict[str, str] = {}
    current: str | None = None
    for raw_line in text.replace("\r\n", "\n").split("\n"):
        if raw_line.startswith(" ") and current is not None:
            attributes[current] += raw_line[1:]
        elif ": " in raw_line:
            current, value = raw_line.split(": ", 1)
            attributes[current] = value
        elif raw_line:
            raise VerificationError(f"Malformed JAR manifest line: {raw_line!r}")
    return attributes


def inspect_jar(jar: Path, evidence: Path) -> tuple[dict[str, Any], dict[str, Any]]:
    try:
        with zipfile.ZipFile(jar) as archive:
            bad_entry = archive.testzip()
            require(bad_entry is None, f"Shaded JAR has a corrupt entry: {bad_entry}")
            try:
                manifest = parse_manifest(archive.read("META-INF/MANIFEST.MF"))
            except KeyError as exc:
                raise VerificationError("Shaded JAR has no META-INF/MANIFEST.MF") from exc
            main_class = manifest.get("Main-Class", "").strip()
            require(bool(main_class), "Shaded JAR manifest has no Main-Class")
            build_jdk_spec = manifest.get("Build-Jdk-Spec", "").strip()
            require(
                re.fullmatch(r"21(?:\.0*)?", build_jdk_spec) is not None,
                f"Shaded JAR does not record the required JDK 21 build identity: Build-Jdk-Spec={build_jdk_spec!r}",
            )
            effective_entries: list[dict[str, Any]] = []
            later_entries: list[dict[str, Any]] = []
            violations: list[dict[str, Any]] = []
            for info in archive.infolist():
                if not info.filename.endswith(".class"):
                    continue
                content = archive.read(info)
                require(
                    len(content) >= 8 and content[:4] == b"\xca\xfe\xba\xbe",
                    f"Malformed classfile entry: {info.filename}",
                )
                major = int.from_bytes(content[6:8], "big")
                match = re.fullmatch(r"META-INF/versions/(\d+)/(.+\.class)", info.filename)
                version = int(match.group(1)) if match else None
                entry = {"entry": info.filename, "major": major, "version": version}
                if version is not None and version > 17:
                    later_entries.append(entry)
                else:
                    effective_entries.append(entry)
                    if major > CLASSFILE_MAJOR_JAVA17:
                        violations.append(entry)
    except (OSError, zipfile.BadZipFile, RuntimeError) as exc:
        if isinstance(exc, VerificationError):
            raise
        raise VerificationError(f"Unable to inspect shaded JAR {jar}: {exc}") from exc
    require(bool(effective_entries), "Shaded JAR contains no Java-17-effective classes")
    inventory = {
        "javaRelease": 17,
        "maximumEffectiveMajor": CLASSFILE_MAJOR_JAVA17,
        "effectiveClassCount": len(effective_entries),
        "maximumObservedEffectiveMajor": max(entry["major"] for entry in effective_entries),
        "effectiveEntries": effective_entries,
        "laterOnlyClassCount": len(later_entries),
        "laterOnlyEntries": later_entries,
        "violations": violations,
    }
    inventory_path = evidence / "classfile-inventory.json"
    write_json_atomic(inventory_path, inventory)
    manifest_path = evidence / "manifest.json"
    write_json_atomic(manifest_path, manifest)
    if violations:
        detail = ", ".join(
            f"{entry['entry']} (major {entry['major']})" for entry in violations
        )
        raise VerificationError(
            f"Java-17-effective classfile major exceeds 61: {detail}"
        )
    identity = {
        "mainClass": main_class,
        "buildJdkSpec": build_jdk_spec,
        "buildIdentity": {
            "buildJdkSpec": build_jdk_spec,
            "createdBy": manifest.get("Created-By", "not-recorded"),
        },
        "manifestEvidence": str(manifest_path.resolve()),
    }
    return identity, inventory


def executable(java_home: Path, name: str) -> Path:
    suffix = ".exe" if os.name == "nt" else ""
    path = java_home / "bin" / f"{name}{suffix}"
    if not path.is_file() or not os.access(path, os.X_OK):
        raise BlockedError(
            "static", f"{name} is not executable under --java-home: {path}"
        )
    return path.resolve()


def probe_java17(java_home: Path, evidence: Path) -> dict[str, str]:
    java = executable(java_home, "java")
    jdeps = executable(java_home, "jdeps")
    result = subprocess.run(
        [str(java), "-XshowSettings:properties", "-version"],
        capture_output=True,
        text=True,
        timeout=20,
    )
    output = (result.stdout + result.stderr).strip()
    version_log = evidence / "java17-version.log"
    version_log.write_text(output + "\n", encoding="utf-8")
    require(result.returncode == 0, f"Configured Java -version failed with exit code {result.returncode}")

    def property_value(name: str) -> str:
        match = re.search(rf"(?m)^\s*{re.escape(name)}\s*=\s*(.+?)\s*$", output)
        require(match is not None, f"Configured Java does not report {name}")
        assert match is not None
        return match.group(1)

    runtime_version = property_value("java.runtime.version")
    vendor = property_value("java.vendor")
    architecture = property_value("os.arch")
    require(
        re.match(r"17(?:\.|\+|$)", runtime_version) is not None,
        f"Configured child Java is not Java 17: {runtime_version}",
    )
    require(
        "eclipse adoptium" in vendor.casefold() or "temurin" in output.casefold(),
        f"Configured Java 17 is not Temurin: vendor={vendor!r}",
    )
    return {
        "javaHome": str(java_home.resolve()),
        "javaPath": str(java),
        "jdepsPath": str(jdeps),
        "vendor": vendor,
        "version": runtime_version,
        "architecture": architecture,
        "versionEvidence": str(version_log.resolve()),
    }


def run_jdeps(runtime: dict[str, str], jar: Path, evidence: Path) -> dict[str, Any]:
    command = [runtime["jdepsPath"], "--multi-release", "17", "--recursive", str(jar.resolve())]
    command_path = evidence / "jdeps-command.txt"
    command_path.write_text(shlex.join(command) + "\n", encoding="utf-8")
    output_path = evidence / "jdeps-output.log"
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=180)
        output = result.stdout + result.stderr
        return_code: int | None = result.returncode
        timed_out = False
    except subprocess.TimeoutExpired as exc:
        def timeout_text(value: str | bytes | None) -> str:
            if value is None:
                return ""
            return value.decode(errors="replace") if isinstance(value, bytes) else value

        output = timeout_text(exc.stdout) + timeout_text(exc.stderr)
        return_code = None
        timed_out = True
    output_path.write_text(output, encoding="utf-8")
    unresolved = sorted(
        {
            match.group(1)
            for line in output.splitlines()
            if (match := re.search(r"->\s+(\S+)\s+not found\s*$", line)) is not None
        }
    )
    return {
        "command": command,
        "returnCode": return_code,
        "timedOut": timed_out,
        "output": str(output_path.resolve()),
        "unresolvedDependencies": unresolved,
        "scope": "class/package/module dependency evidence; not member-level API signature proof",
    }


def process_table() -> dict[int, tuple[int, str, str]]:
    result: dict[int, tuple[int, str, str]] = {}
    for child in Path("/proc").iterdir():
        if not child.name.isdigit():
            continue
        try:
            status = (child / "status").read_text(encoding="utf-8")
            parent_match = re.search(r"^PPid:\s+(\d+)$", status, re.MULTILINE)
            command = (child / "cmdline").read_bytes().replace(b"\0", b" ").decode(errors="replace").strip()
            image = os.readlink(child / "exe")
        except (FileNotFoundError, PermissionError, ProcessLookupError, OSError):
            continue
        result[int(child.name)] = (int(parent_match.group(1)) if parent_match else 0, command, image)
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

def process_group_members(process_group: int) -> list[int]:
    members: list[int] = []
    for pid in process_table():
        try:
            if os.getpgid(pid) == process_group:
                members.append(pid)
        except (ProcessLookupError, PermissionError):
            continue
    return sorted(members)


def process_snapshot(pid: int, table: dict[int, tuple[int, str, str]]) -> dict[str, Any] | None:
    if pid not in table:
        return None
    try:
        arguments = [
            value.decode(errors="replace")
            for value in Path(f"/proc/{pid}/cmdline").read_bytes().split(b"\0")
            if value
        ]
        working_directory = os.readlink(f"/proc/{pid}/cwd")
        start_time = Path(f"/proc/{pid}/stat").read_text(encoding="utf-8").rsplit(")", 1)[1].split()[19]
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
    rows = [process_snapshot(pid, table) for pid in pids]
    return [row for row in rows if row is not None]


def find_runtime_process(supervisor_pid: int, java: Path, jar: Path, data_root: Path) -> tuple[int, list[dict[str, Any]]]:
    current = snapshots(descendants(supervisor_pid))
    expected_image = str(java.resolve())
    if FIXTURE_MODE:
        candidates = [row for row in current if expected_image in row["arguments"] or row["image"] == expected_image]
    else:
        candidates = [row for row in current if row["image"] == expected_image]
    require(bool(candidates), "Configured Temurin 17 process is not running")
    runtime = candidates[-1]
    arguments = runtime["arguments"]
    require(f"-Dlizzie.work.dir={data_root.resolve()}" in arguments, "Child Java does not select the isolated work root")
    require("-jar" in arguments, "Child Java does not use -jar")
    index = arguments.index("-jar")
    require(index + 1 < len(arguments), "Child Java omits the JAR argument")
    launched = Path(arguments[index + 1])
    if not launched.is_absolute():
        launched = Path(runtime["workingDirectory"]) / launched
    require(launched.resolve() == jar.resolve(), "Child Java does not launch the exact shaded JAR")
    return int(runtime["pid"]), current


def display_windows(display: str) -> tuple[str, dict[str, str]]:
    environment = os.environ.copy()
    environment["DISPLAY"] = display
    result = subprocess.run(
        ["xwininfo", "-root", "-tree"], capture_output=True, text=True, timeout=5, env=environment
    )
    require(result.returncode == 0, f"Unable to inspect display {display}: {result.stderr}")
    windows = {
        match.group(1).lower(): match.group(2)
        for match in re.finditer(r'^\s*(0x[0-9a-fA-F]+)\s+"([^"]*)"', result.stdout, re.MULTILINE)
    }
    return result.stdout + result.stderr, windows


def window_process_id(display: str, window_id: str) -> tuple[int | None, str]:
    environment = os.environ.copy()
    environment["DISPLAY"] = display
    result = subprocess.run(
        ["xprop", "-id", window_id, "_NET_WM_PID"],
        capture_output=True,
        text=True,
        timeout=5,
        env=environment,
    )
    output = result.stdout + result.stderr
    match = re.search(r"_NET_WM_PID\([^)]*\)\s*=\s*(\d+)", output)
    return (int(match.group(1)) if result.returncode == 0 and match else None), output


def start_xvfb(evidence: Path) -> tuple[subprocess.Popen[str], str]:
    log = (evidence / "xvfb.stderr.log").open("w", encoding="utf-8")
    try:
        process = subprocess.Popen(
            ["Xvfb", "-displayfd", "1", "-screen", "0", "1280x1024x24", "-nolisten", "tcp"],
            stdout=subprocess.PIPE,
            stderr=log,
            text=True,
            start_new_session=True,
        )
    finally:
        log.close()
    try:
        assert process.stdout is not None
        ready, _, _ = select.select([process.stdout], [], [], 10)
        if not ready:
            raise BlockedError("launch", "Xvfb did not publish a display within 10 seconds")
        display_number = process.stdout.readline().strip()
        require(display_number.isdigit(), f"Xvfb returned an invalid display number: {display_number!r}")
        display = f":{display_number}"
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            try:
                display_windows(display)
                process.stdout.close()
                return process, display
            except VerificationError:
                if process.poll() is not None:
                    break
                time.sleep(0.1)
        raise BlockedError("launch", f"Xvfb display {display} did not become queryable")
    except BaseException as exc:
        complete, remaining, errors = terminate_owned(process, 5)
        if process.stdout is not None:
            process.stdout.close()
        if not complete:
            row = process_snapshot(process.pid, process_table())
            raise AcquiredProcessCleanupError(
                f"{exc}; Xvfb cleanup failed: remaining={remaining} errors={errors}",
                process,
                str(row["identity"]) if row is not None else None,
            ) from exc
        raise


def build_network_helper(evidence: Path) -> Path:
    helper = evidence / "offline-boundary.sh"
    helper.write_text(
        """#!/usr/bin/env sh
set -eu
ip link set lo up
ip route add default dev lo
ip -6 route add default dev lo
nft add table inet lizzie_java17
nft 'add chain inet lizzie_java17 output { type filter hook output priority 0; policy accept; }'
nft add rule inet lizzie_java17 output ip daddr != 127.0.0.0/8 counter drop
nft add rule inet lizzie_java17 output ip6 daddr != ::1 counter drop
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
        digest.update(f"{relative}|{kind}|{metadata.st_size}|{metadata.st_mtime_ns}\n".encode())
        entries += 1
    return {"exists": True, "entries": entries, "sha256": digest.hexdigest()}


def outside_data_snapshots() -> dict[str, dict[str, Any]]:
    home = Path.home()
    return {
        "defaultUserRoot": tree_metadata_identity(home / ".lizzieyzy-next"),
        "legacyUserRoot": tree_metadata_identity(home / ".lizzieyzy-next-foxuid"),
    }


def terminate_owned(
    process: subprocess.Popen[bytes] | subprocess.Popen[str],
    timeout_seconds: int,
    preferred_pid: int = 0,
    preferred_identity: str | None = None,
) -> tuple[bool, list[int], list[str]]:
    owned: dict[int, str] = {}
    errors: list[str] = []
    process_group = process.pid

    def remember() -> None:
        pids = set(descendants(process.pid)) | set(process_group_members(process_group))
        for row in snapshots(sorted(pids)):
            owned.setdefault(int(row["pid"]), str(row["identity"]))

    def current_owned(pid: int) -> bool:
        identity = owned.get(pid)
        if identity is None:
            return False
        row = process_snapshot(pid, process_table())
        return row is not None and row["identity"] == identity

    def signal_owned(pid: int, process_signal: signal.Signals) -> None:
        if not current_owned(pid):
            return
        try:
            os.kill(pid, process_signal)
        except ProcessLookupError:
            pass

    def survivors() -> list[int]:
        return sorted(pid for pid in owned if current_owned(pid))

    remember()
    if preferred_pid:
        expected_identity = preferred_identity or owned.get(preferred_pid)
        if expected_identity is not None and owned.get(preferred_pid) == expected_identity:
            signal_owned(preferred_pid, signal.SIGTERM)
        graceful_deadline = time.monotonic() + timeout_seconds
        while process.poll() is None and time.monotonic() < graceful_deadline:
            remember()
            time.sleep(0.05)
    if process.poll() is None:
        try:
            os.killpg(process_group, signal.SIGTERM)
        except ProcessLookupError:
            pass
    else:
        for pid in survivors():
            signal_owned(pid, signal.SIGTERM)
    deadline = time.monotonic() + timeout_seconds
    while survivors() and time.monotonic() < deadline:
        remember()
        time.sleep(0.05)
    remaining = survivors()
    for pid in remaining:
        signal_owned(pid, signal.SIGKILL)
    try:
        process.wait(timeout=timeout_seconds)
    except subprocess.TimeoutExpired:
        errors.append("owned process tree did not exit after SIGKILL")
    time.sleep(0.1)
    remaining = survivors()
    if remaining:
        errors.append(f"owned processes survived cleanup: {remaining}")
    return not remaining and not errors, remaining, errors


def wait_for_ready(
    supervisor: subprocess.Popen[bytes],
    java: Path,
    jar: Path,
    data_root: Path,
    display: str,
    baseline_windows: set[str],
    evidence: Path,
    timeout_seconds: int,
) -> tuple[int, list[dict[str, Any]], str, Path]:
    deadline = time.monotonic() + timeout_seconds
    app_log = data_root / "logs/app.log"
    window_evidence = evidence / "window-tree.txt"
    while time.monotonic() < deadline:
        if supervisor.poll() is not None:
            raise VerificationError(f"Temurin 17 launch exited before readiness with code {supervisor.returncode}")
        try:
            runtime_pid, processes = find_runtime_process(supervisor.pid, java, jar, data_root)
        except VerificationError:
            time.sleep(0.1)
            continue
        ready_log = app_log.is_file() and "application ready" in app_log.read_text(
            encoding="utf-8", errors="replace"
        ).casefold()
        config_ready = (data_root / "config.txt").is_file() and (data_root / "persist").is_file()
        if FIXTURE_MODE:
            marker = evidence / "fixture-window-ready.txt"
            visible = marker.is_file() and marker.read_text(encoding="utf-8").strip() == str(runtime_pid)
            window_evidence.write_text(f"fixture window pid={runtime_pid} visible={visible}\n", encoding="utf-8")
            window_id = "fixture-window" if visible else ""
        else:
            tree, windows = display_windows(display)
            details = [tree]
            window_id = ""
            for candidate, title in windows.items():
                if candidate in baseline_windows or "lizzie" not in title.casefold():
                    continue
                owner, properties = window_process_id(display, candidate)
                details.append(f"\n[{candidate} title={title!r} owner={owner}]\n{properties}")
                if owner == runtime_pid:
                    window_id = candidate
                    break
            window_evidence.write_text("".join(details), encoding="utf-8")
            visible = bool(window_id)
        if ready_log and config_ready and visible:
            return runtime_pid, processes, window_id, app_log
        time.sleep(0.2)
    raise VerificationError(
        "Timed out waiting for the Temurin 17 application-ready log, visible product-owned window, config.txt and persist"
    )


def observed_model() -> dict[str, Any]:
    return {
        "artifact": {
            "path": None,
            "sizeBytes": None,
            "sha256": None,
            "sourceSha": None,
            "manifestMainClass": None,
            "buildJdkSpec": None,
        },
        "runtime": {
            "javaHome": None,
            "javaPath": None,
            "vendor": None,
            "version": None,
            "architecture": None,
            "versionEvidence": None,
            "processImage": None,
        },
        "classfiles": {
            "effectiveClassCount": None,
            "maximumEffectiveMajor": None,
            "laterOnlyClassCount": None,
            "inventory": None,
        },
        "dependencies": {"jdepsOutput": None, "unresolvedCount": None, "scope": None},
        "application": {
            "pid": None,
            "processes": None,
            "command": None,
            "dataRoot": None,
            "display": None,
            "displayProcess": {"owned": None, "pid": None, "identity": None},
            "windowId": None,
            "windowEvidence": None,
            "applicationLog": None,
            "outsideWrites": None,
        },
        "network": {"boundary": None, "counterEvidence": None, "blockedAttemptCount": None},
        "cleanup": {"remainingPids": None, "errors": None},
        "host": {
            "os": platform.system(),
            "architecture": platform.machine(),
            "kernel": platform.release(),
            "fixtureMode": FIXTURE_MODE,
        },
    }


def required_outcomes(scenario: str) -> dict[str, Any]:
    phases = PHASES[scenario]
    observations = [
        ("observed.artifact.sha256", "identity"),
        ("observed.artifact.manifestMainClass", "identity"),
        ("observed.artifact.buildJdkSpec", "identity"),
        ("observed.runtime.javaPath", "static"),
        ("observed.runtime.vendor", "static"),
        ("observed.runtime.version", "static"),
        ("observed.runtime.architecture", "static"),
        ("observed.classfiles.effectiveClassCount", "static"),
        ("observed.classfiles.maximumEffectiveMajor", "static"),
        ("observed.classfiles.laterOnlyClassCount", "static"),
        ("observed.classfiles.inventory", "static"),
        ("observed.dependencies.jdepsOutput", "static"),
        ("observed.dependencies.unresolvedCount", "static"),
        ("observed.dependencies.scope", "static"),
        ("observed.cleanup.remainingPids", "cleanup"),
        ("observed.cleanup.errors", "cleanup"),
    ]
    assertions = [("assertions.identity", "identity"), ("assertions.static", "static"), ("assertions.cleaned", "cleanup")]
    evidence_values = [("evidence.identity", "identity"), ("evidence.static", "static"), ("evidence.cleanup", "cleanup")]
    if scenario == "application-smoke":
        observations.extend(
            [
                ("observed.application.pid", "launch"),
                ("observed.application.processes", "launch"),
                ("observed.application.command", "launch"),
                ("observed.application.dataRoot", "launch"),
                ("observed.application.display", "launch"),
                ("observed.application.displayProcess.owned", "launch"),
                ("observed.application.displayProcess.pid", "launch"),
                ("observed.application.windowId", "verify"),
                ("observed.application.windowEvidence", "verify"),
                ("observed.application.applicationLog", "verify"),
                ("observed.application.outsideWrites", "cleanup"),
                ("observed.runtime.processImage", "launch"),
                ("observed.network.boundary", "launch"),
                ("observed.network.counterEvidence", "cleanup"),
                ("observed.network.blockedAttemptCount", "cleanup"),
            ]
        )
        assertions.extend([("assertions.launched", "launch"), ("assertions.verified", "verify")])
        evidence_values.extend([("evidence.launch", "launch"), ("evidence.verification", "verify")])
    return {
        "phases": list(phases),
        "observations": [{"path": path, "phase": phase} for path, phase in observations],
        "assertions": [{"path": path, "phase": phase} for path, phase in assertions],
        "evidence": [{"path": path, "phase": phase} for path, phase in evidence_values],
    }


def add_null_reasons(value: object, path: str, reasons: dict[str, str], phase: str) -> None:
    if value is None:
        reasons[path] = f"not observed before terminal phase {phase}"
    elif isinstance(value, dict):
        for key, child in value.items():
            add_null_reasons(child, f"{path}.{key}", reasons, phase)


def failure_kind(message: str) -> str:
    if "timed out" in message.casefold():
        return "timeout"
    if re.search(r"(?i)(missing|mismatch|differs|expected|must|unexpected|does not|requires|malformed|exceeds|unresolved|not )", message):
        return "assertion"
    return "error"


def run(
    jar: Path,
    source_sha: str,
    java_home: Path,
    evidence: Path,
    scenario: str,
    *,
    expected_jar_size: int | None = None,
    expected_jar_sha256: str | None = None,
    expected_build_jdk_spec: str | None = None,
    expected_created_by: str | None = None,
) -> int:
    require(re.fullmatch(r"[0-9a-f]{40}", source_sha) is not None, "--source-sha must be a full lowercase commit SHA")
    if expected_jar_size is not None:
        require(type(expected_jar_size) is int and expected_jar_size > 0, "--expected-jar-size must be a positive integer")
    if expected_jar_sha256 is not None:
        require(
            isinstance(expected_jar_sha256, str)
            and re.fullmatch(r"[0-9a-f]{64}", expected_jar_sha256) is not None,
            "--expected-jar-sha256 must be 64 lowercase hex characters",
        )
    if expected_build_jdk_spec is not None:
        require(
            isinstance(expected_build_jdk_spec, str) and bool(expected_build_jdk_spec.strip()),
            "--expected-build-jdk-spec must not be empty",
        )
    if expected_created_by is not None:
        require(
            isinstance(expected_created_by, str) and bool(expected_created_by.strip()),
            "--expected-created-by must not be empty",
        )

    has_expected_identity = (
        expected_jar_size is not None
        and expected_jar_sha256 is not None
        and expected_build_jdk_spec is not None
        and expected_created_by is not None
    )
    if not jar.is_file() and not has_expected_identity:
        require(jar.is_file(), f"Shaded JAR is unavailable: {jar}")

    require(not evidence.exists(), f"Evidence directory must be new: {evidence}")
    evidence.mkdir(parents=True)
    jar = jar.resolve()
    java_home = java_home.resolve()
    started_at = now()
    record_path = evidence / "acceptance.json"
    jar_size = jar.stat().st_size if jar.is_file() else None
    jar_sha = sha256_file(jar) if jar.is_file() else None
    observed = observed_model()
    if jar.is_file():
        observed["artifact"].update(path=str(jar), sizeBytes=jar_size, sha256=jar_sha, sourceSha=source_sha)
    assertions: dict[str, bool | None] = {
        "identity": None,
        "static": None,
        "launched": None,
        "verified": None,
        "cleaned": None,
    }
    evidence_values: dict[str, str | None] = {
        "identity": None,
        "static": None,
        "launch": None,
        "verification": None,
        "cleanup": None,
    }
    cleanup: dict[str, Any] = {"complete": False, "remainingOwnedResources": []}
    phase = "identity"
    status = "FAIL"
    reason: str | None = None
    blocked_phase: str | None = None
    failure: dict[str, Any] | None = None
    supervisor: subprocess.Popen[bytes] | None = None
    xvfb: subprocess.Popen[str] | None = None
    stdout_stream = None
    stderr_stream = None
    runtime_pid = 0
    runtime_identity: str | None = None
    identity: dict[str, Any] | None = None
    runtime: dict[str, str] | None = None
    final_counter = evidence / "network-counter-final.txt"
    outside_before: dict[str, dict[str, Any]] | None = None
    try:
        if not jar.is_file():
            raise BlockedError("identity", f"Shaded JAR is unavailable: {jar}")

        if expected_jar_size is not None:
            require(
                jar_size == expected_jar_size,
                f"Shaded JAR size mismatch: expected {expected_jar_size}, observed {jar_size}",
            )
        if expected_jar_sha256 is not None:
            require(
                jar_sha == expected_jar_sha256,
                f"Shaded JAR SHA-256 mismatch: expected {expected_jar_sha256}, observed {jar_sha}",
            )

        identity, inventory = inspect_jar(jar, evidence)
        observed["artifact"].update(
            manifestMainClass=identity["mainClass"], buildJdkSpec=identity["buildJdkSpec"]
        )

        if expected_build_jdk_spec is not None:
            require(
                identity["buildIdentity"]["buildJdkSpec"] == expected_build_jdk_spec,
                f"Shaded JAR build JDK spec mismatch: expected {expected_build_jdk_spec!r}, observed {identity['buildIdentity']['buildJdkSpec']!r}",
            )
        if expected_created_by is not None:
            require(
                identity["buildIdentity"]["createdBy"] == expected_created_by,
                f"Shaded JAR Created-By mismatch: expected {expected_created_by!r}, observed {identity['buildIdentity']['createdBy']!r}",
            )
        assertions["identity"] = True
        evidence_values["identity"] = identity["manifestEvidence"]

        phase = "static"
        runtime = probe_java17(java_home, evidence)
        observed["runtime"].update(
            javaHome=runtime["javaHome"],
            javaPath=runtime["javaPath"],
            vendor=runtime["vendor"],
            version=runtime["version"],
            architecture=runtime["architecture"],
            versionEvidence=runtime["versionEvidence"],
        )
        observed["classfiles"].update(
            effectiveClassCount=inventory["effectiveClassCount"],
            maximumEffectiveMajor=inventory["maximumObservedEffectiveMajor"],
            laterOnlyClassCount=inventory["laterOnlyClassCount"],
            inventory=str((evidence / "classfile-inventory.json").resolve()),
        )
        dependencies = run_jdeps(runtime, jar, evidence)
        observed["dependencies"].update(
            jdepsOutput=dependencies["output"],
            unresolvedCount=len(dependencies["unresolvedDependencies"]),
            scope=dependencies["scope"],
        )
        evidence_values["static"] = dependencies["output"]
        require(
            not dependencies["timedOut"],
            f"JDK 17 jdeps timed out; partial output retained at {dependencies['output']}",
        )
        require(
            dependencies["returnCode"] == 0,
            f"JDK 17 jdeps failed with exit code {dependencies['returnCode']}; see {dependencies['output']}",
        )
        require(
            not dependencies["unresolvedDependencies"],
            f"JDK 17 jdeps reported unresolved dependencies: {dependencies['unresolvedDependencies']}",
        )
        assertions["static"] = True

        if scenario == "application-smoke":
            phase = "launch"
            required_commands = ("unshare", "ip", "nft", "xwininfo", "xprop")
            if not FIXTURE_MODE:
                required_commands += ("Xvfb",)
            for command in required_commands:
                if not shutil.which(command):
                    raise BlockedError("launch", f"Required standalone Java 17 command is unavailable: {command}")
            data_root = evidence / "Java 17 验收 work dir"
            data_root.mkdir()
            require(" " in str(data_root) and "验收" in str(data_root), "Application work directory must contain spaces and Unicode")
            display = os.environ.get("DISPLAY", "")
            display_process: dict[str, Any] = {
                "owned": False,
                "pid": "NOT_APPLICABLE",
                "identity": "NOT_APPLICABLE",
            }
            if display:
                try:
                    baseline_tree, baseline = display_windows(display)
                except VerificationError:
                    display = ""
            if not display:
                if FIXTURE_MODE:
                    display = ":fixture"
                    baseline_tree, baseline = "fixture display\n", {}
                else:
                    xvfb, display = start_xvfb(evidence)
                    xvfb_row = process_snapshot(xvfb.pid, process_table())
                    require(xvfb_row is not None, "Unable to record the owned Xvfb process identity")
                    display_process = {
                        "owned": True,
                        "pid": xvfb.pid,
                        "identity": xvfb_row["identity"],
                    }
                    baseline_tree, baseline = display_windows(display)
            (evidence / "window-tree-before.txt").write_text(baseline_tree, encoding="utf-8")
            outside_before = outside_data_snapshots()
            write_json_atomic(evidence / "outside-data-before.json", outside_before)
            helper = build_network_helper(evidence)
            stdout_stream = (evidence / "application.stdout.log").open("wb")
            stderr_stream = (evidence / "application.stderr.log").open("wb")
            command = [
                "unshare",
                "--user",
                "--map-root-user",
                "--net",
                "--fork",
                "--kill-child=TERM",
                str(helper),
                runtime["javaPath"],
                f"-Dlizzie.work.dir={data_root.resolve()}",
                "-jar",
                str(jar),
            ]
            environment = os.environ.copy()
            environment.update(
                {
                    "DISPLAY": display,
                    "LIZZIE_NETWORK_START": str((evidence / "network-addresses.json").resolve()),
                    "LIZZIE_NETWORK_RULES_START": str((evidence / "network-rules-start.txt").resolve()),
                    "LIZZIE_NETWORK_RULES_FINAL": str(final_counter.resolve()),
                    "LIZZIE_JAVA17_FIXTURE_WINDOW": str((evidence / "fixture-window-ready.txt").resolve()),
                }
            )
            supervisor = subprocess.Popen(
                command,
                cwd=jar.parent,
                env=environment,
                stdout=stdout_stream,
                stderr=stderr_stream,
                start_new_session=True,
            )
            timeout_seconds = int(os.environ.get("LIZZIE_JAVA17_ACCEPTANCE_TIMEOUT_SECONDS", "90"))
            runtime_pid, processes, window_id, app_log = wait_for_ready(
                supervisor,
                Path(runtime["javaPath"]),
                jar,
                data_root,
                display,
                set(baseline),
                evidence,
                timeout_seconds,
            )
            runtime_row = next(row for row in processes if row["pid"] == runtime_pid)
            observed["runtime"]["processImage"] = runtime_row["image"]
            runtime_identity = str(runtime_row["identity"])
            observed["application"].update(
                pid=runtime_pid,
                processes=processes,
                command=command,
                dataRoot=str(data_root.resolve()),
                display=display,
                displayProcess=display_process,
            )
            observed["network"].update(
                boundary="private user+network namespace with nft output drop counters",
                counterEvidence=str(final_counter.resolve()),
            )
            run_record = evidence / "run.json"
            write_json_atomic(
                run_record,
                {
                    "sourceSha": source_sha,
                    "jar": {"path": str(jar), "sizeBytes": jar_size, "sha256": jar_sha},
                    "runtime": runtime,
                    "pid": runtime_pid,
                    "processes": processes,
                    "command": command,
                    "dataRoot": str(data_root.resolve()),
                    "display": display,
                    "displayProcess": display_process,
                    "startedAt": started_at,
                },
            )
            assertions["launched"] = True
            evidence_values["launch"] = str(run_record.resolve())

            phase = "verify"
            observed["application"].update(
                windowId=window_id,
                windowEvidence=str((evidence / "window-tree.txt").resolve()),
                applicationLog=str(app_log.resolve()),
            )
            assertions["verified"] = True
            evidence_values["verification"] = str(app_log.resolve())

        phase = "cleanup"
        if supervisor is not None:
            complete, remaining, errors = terminate_owned(
                supervisor,
                int(os.environ.get("LIZZIE_JAVA17_ACCEPTANCE_STOP_SECONDS", "15")),
                runtime_pid,
                runtime_identity,
            )
            supervisor = None
            observed["cleanup"].update(remainingPids=remaining, errors=errors)
            cleanup["remainingOwnedResources"] = [f"process:{pid}" for pid in remaining] + errors
            cleanup["complete"] = complete
            attempts = external_attempt_count(final_counter)
            observed["network"]["blockedAttemptCount"] = attempts
            outside_after = outside_data_snapshots()
            write_json_atomic(evidence / "outside-data-after.json", outside_after)
            outside_writes = [
                name for name in outside_before or {} if (outside_before or {})[name] != outside_after[name]
            ]
            observed["application"]["outsideWrites"] = outside_writes
            require(not outside_writes, f"Application wrote outside the isolated data root: {outside_writes}")
            require(attempts == 0, f"Application attempted {attempts} external network connection(s)")
            require(complete, "Owned Temurin 17 process cleanup did not complete")
        else:
            observed["cleanup"].update(remainingPids=[], errors=[])
            cleanup.update(complete=True, remainingOwnedResources=[])
        if xvfb is not None:
            xvfb_complete, xvfb_remaining, xvfb_errors = terminate_owned(xvfb, 5)
            xvfb = None
            cleanup["remainingOwnedResources"].extend(
                [f"display-process:{pid}" for pid in xvfb_remaining] + xvfb_errors
            )
            cleanup["complete"] = cleanup["complete"] and xvfb_complete
            require(xvfb_complete, "Owned Xvfb process cleanup did not complete")
        assertions["cleaned"] = bool(cleanup["complete"])
        cleanup_path = evidence / "cleanup-summary.json"
        write_json_atomic(
            cleanup_path,
            {
                "remainingOwnedResources": cleanup["remainingOwnedResources"],
                "networkCounter": str(final_counter.resolve()) if scenario == "application-smoke" else "NOT_APPLICABLE",
            },
        )
        evidence_values["cleanup"] = str(cleanup_path.resolve())
        status = "PASS"
    except BaseException as exc:
        if isinstance(exc, AcquiredProcessCleanupError):
            xvfb = exc.process
            observed["application"]["displayProcess"] = {
                "owned": True,
                "pid": xvfb.pid,
                "identity": exc.identity or "UNAVAILABLE_AFTER_FAILED_CLEANUP",
            }
        message = str(exc) or exc.__class__.__name__
        remaining_resources = list(cleanup["remainingOwnedResources"])
        had_cleanup_result = observed["cleanup"]["remainingPids"] is not None or bool(
            remaining_resources
        )
        cleanup_complete = bool(cleanup["complete"]) if had_cleanup_result else True
        if supervisor is not None:
            complete, remaining, errors = terminate_owned(
                supervisor,
                int(os.environ.get("LIZZIE_JAVA17_ACCEPTANCE_STOP_SECONDS", "15")),
                runtime_pid,
                runtime_identity,
            )
            supervisor = None
            observed["cleanup"].update(remainingPids=remaining, errors=errors)
            remaining_resources.extend([f"process:{pid}" for pid in remaining] + errors)
            cleanup_complete = cleanup_complete and complete
        if xvfb is not None:
            complete, remaining, errors = terminate_owned(xvfb, 5)
            xvfb = None
            remaining_resources.extend([f"display-process:{pid}" for pid in remaining] + errors)
            cleanup_complete = cleanup_complete and complete
        cleanup["remainingOwnedResources"] = list(dict.fromkeys(remaining_resources))
        cleanup["complete"] = cleanup_complete and not cleanup["remainingOwnedResources"]
        if isinstance(exc, BlockedError):
            status = "BLOCKED"
            blocked_phase = exc.phase
            blocked_index = PHASES[scenario].index(blocked_phase)
            phase = PHASES[scenario][0 if blocked_index == 0 else blocked_index - 1]
            reason = message
        else:
            status = "FAIL"
            assertion_name = {
                "identity": "identity",
                "static": "static",
                "launch": "launched",
                "verify": "verified",
                "cleanup": "cleaned",
            }[phase]
            assertions[assertion_name] = False
            diagnostic = evidence / f"{phase}-failure.txt"
            diagnostic.write_text(message + "\n", encoding="utf-8")
            failure = {
                "kind": failure_kind(message),
                "summary": message,
                "diagnostics": [str(diagnostic.resolve()), str(record_path.resolve())],
            }
    finally:
        for stream in (stdout_stream, stderr_stream):
            if stream is not None and not stream.closed:
                stream.close()

    if identity is not None:
        build_identity = identity["buildIdentity"]
    elif status == "BLOCKED" and has_expected_identity:
        build_identity = {
            "buildJdkSpec": expected_build_jdk_spec,
            "createdBy": expected_created_by,
        }
    else:
        build_identity = {"inspection": "failed"}

    artifact_size = jar_size if jar_size is not None else expected_jar_size
    artifact_sha = jar_sha if jar_sha is not None else expected_jar_sha256

    expected = {
        "targetSha": source_sha,
        "platform": "standalone-java17",
        "architecture": observed["runtime"]["architecture"] or platform.machine(),
        "artifact": {
            "key": "NOT_APPLICABLE",
            "name": "NOT_APPLICABLE",
            "class": "NOT_APPLICABLE",
            "sourceSha": source_sha,
            "buildIdentity": build_identity,
            "path": str(jar),
            "sizeBytes": artifact_size,
            "sha256": artifact_sha,
        },
        "scenario": scenario,
        "requiredOutcomes": required_outcomes(scenario),
    }
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
        "expected": expected,
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
        write_json_atomic(record_path, record)
        (evidence / "record-validation-failure.txt").write_text(f"{exc}\n", encoding="utf-8")
        print(f"Standalone Java 17 record validation failed: {exc}", file=sys.stderr)
        return 1
    write_json_atomic(record_path, record)
    if status == "PASS":
        print(f"PASS {scenario} {record_path}")
        return 0
    print(f"Standalone Java 17 {status}: {reason or failure['summary']} Evidence: {record_path}", file=sys.stderr)
    return 1


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True, type=Path)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--java-home", required=True, type=Path)
    parser.add_argument("--evidence-dir", required=True, type=Path)
    parser.add_argument("--scenario", required=True, choices=SCENARIOS)
    parser.add_argument("--expected-jar-size", type=int, default=None)
    parser.add_argument("--expected-jar-sha256", default=None)
    parser.add_argument("--expected-build-jdk-spec", default=None)
    parser.add_argument("--expected-created-by", default=None)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        return run(
            args.jar,
            args.source_sha,
            args.java_home,
            args.evidence_dir.resolve(),
            args.scenario,
            expected_jar_size=args.expected_jar_size,
            expected_jar_sha256=args.expected_jar_sha256,
            expected_build_jdk_spec=args.expected_build_jdk_spec,
            expected_created_by=args.expected_created_by,
        )
    except VerificationError as exc:
        print(f"Standalone Java 17 verification error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

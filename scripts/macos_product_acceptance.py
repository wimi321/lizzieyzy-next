#!/usr/bin/env python3
"""Accept an installed macOS DMG through its native launcher and bundled runtime."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import plistlib
import re
import shutil
import signal
import secrets
import socket
import stat
import subprocess
import sys
import tempfile
import threading
import time
from typing import Any
import zipfile

try:
    from scripts import release_asset_provenance as provenance
except ModuleNotFoundError:
    import release_asset_provenance as provenance  # type: ignore[no-redef]


SCENARIOS = ("installed-offline-first-run",)
PHASES = ("identity", "content", "install", "gatekeeper", "launch", "verify", "cleanup")
PRODUCTS = {
    "mac_arm64": {
        "platform": "mac-arm64",
        "architecture": "arm64",
        "engineDirectory": "macos-arm64",
        "jcefPlatform": "macosx-arm64",
        "layoutLabel": "Apple Silicon",
    },
    "mac_amd64": {
        "platform": "mac-amd64",
        "architecture": "x86_64",
        "engineDirectory": "macos-amd64",
        "jcefPlatform": "macosx-amd64",
        "layoutLabel": "Intel",
    },
}
FIXTURE_MODE = os.environ.get("LIZZIE_MACOS_ACCEPTANCE_FIXTURE_MODE") == "1"


class AcceptanceError(RuntimeError):
    pass


class BlockedError(AcceptanceError):
    def __init__(self, phase: str, message: str) -> None:
        super().__init__(message)
        self.phase = phase


class MountAcquisitionError(AcceptanceError):
    def __init__(self, message: str, mount_path: Path, device: str) -> None:
        super().__init__(message)
        self.mount_path = mount_path
        self.device = device



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
        dir=path.parent,
        prefix=f".{path.name}.",
        suffix=".tmp",
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


def normalized_architecture(value: str) -> str:
    normalized = value.strip().lower()
    if normalized in {"arm64", "aarch64"}:
        return "arm64"
    if normalized in {"amd64", "x86_64"}:
        return "x86_64"
    return normalized


def canonical_candidate(path: Path) -> tuple[dict[str, Any], dict[str, str]]:
    supplied = read_json(path, "candidate.json")
    artifact = supplied.get("artifact")
    candidate_provenance = supplied.get("provenance")
    require(isinstance(artifact, dict) and isinstance(candidate_provenance, dict), "Candidate identity is incomplete")
    require(supplied.get("schemaVersion") == 1, "Unsupported candidate schema")
    key = str(artifact.get("key"))
    require(key in PRODUCTS, "Candidate is not a macOS final DMG product")
    product = PRODUCTS[key]
    require(supplied.get("platform") == product["platform"], "Candidate platform differs from its canonical artifact key")
    require(supplied.get("architecture") == product["architecture"], "Candidate architecture differs from its canonical artifact key")
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
    require(artifact.get("class") == "dmg-product", "Candidate class must be dmg-product")
    return supplied, product

ROOT = Path(__file__).resolve().parents[1]


def json_sha256(value: object) -> str:
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(encoded).hexdigest()


def command_log(command: list[str], path: Path, *, timeout: int = 120) -> subprocess.CompletedProcess[str]:
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired as exc:
        output = (exc.stdout or "") + (exc.stderr or "")
        path.write_text(f"$ {' '.join(command)}\n{output}\nTIMEOUT\n", encoding="utf-8")
        raise AcceptanceError(f"Timed out running {' '.join(command)}") from exc
    path.write_text(f"$ {' '.join(command)}\nexit={result.returncode}\n{result.stdout}{result.stderr}", encoding="utf-8")
    return result


def require_contained(path: Path, root: Path, label: str) -> Path:
    try:
        resolved = path.resolve(strict=True)
        resolved.relative_to(root.resolve(strict=True))
    except (OSError, ValueError) as exc:
        raise AcceptanceError(f"{label} escapes installed app: {path}") from exc
    return resolved


def file_identity(path: Path, label: str, *, executable: bool = False, root: Path | None = None) -> dict[str, Any]:
    resolved = require_contained(path, root, label) if root is not None else path.resolve(strict=True)
    require(resolved.is_file(), f"{label} is missing: {path}")
    if executable:
        require(os.access(resolved, os.X_OK), f"{label} is not executable: {path}")
    return {"path": str(resolved), "sizeBytes": resolved.stat().st_size, "sha256": sha256_file(resolved)}


def tree_identity(path: Path, label: str, *, root: Path | None = None) -> dict[str, Any]:
    resolved_root = require_contained(path, root, label) if root is not None else path.resolve(strict=True)
    require(resolved_root.is_dir(), f"{label} is missing: {path}")
    digest = hashlib.sha256()
    entries = 0
    for child in sorted(path.rglob("*")):
        if root is not None:
            require_contained(child, root, f"{label} entry")
        relative = child.relative_to(path).as_posix()
        if child.is_symlink():
            digest.update(f"{relative}|link|{os.readlink(child)}\n".encode())
        elif child.is_file():
            digest.update(f"{relative}|file|{child.stat().st_size}|{sha256_file(child)}\n".encode())
        elif child.is_dir():
            digest.update(f"{relative}|dir\n".encode())
        entries += 1
    require(entries > 0, f"{label} is empty: {path}")
    return {"path": str(resolved_root), "entries": entries, "sha256": digest.hexdigest()}


def audit_final_dmg(candidate: dict[str, Any], product: dict[str, str], evidence: Path) -> tuple[str, str]:
    layout_log = evidence / "layout-audit.log"
    dylib_log = evidence / "dylib-closure-audit.log"
    if FIXTURE_MODE:
        text = "FIXTURE ONLY: simulated final-DMG layout and bundled KataGo dylib authority surfaces; not native startup evidence.\n"
        layout_log.write_text(text, encoding="utf-8")
        dylib_log.write_text(text, encoding="utf-8")
        return str(layout_log.resolve()), str(dylib_log.resolve())
    validator = ROOT / "scripts" / "validate_macos_dmg_layout.sh"
    require(validator.is_file() and os.access(validator, os.X_OK), f"macOS DMG layout authority is unavailable: {validator}")
    result = command_log([str(validator), str(candidate["artifact"]["sourcePath"]), product["layoutLabel"], str(candidate["releaseTag"])], layout_log)
    require(result.returncode == 0, f"Final DMG layout/dylib closure audit failed with exit code {result.returncode}")
    shutil.copyfile(layout_log, dylib_log)
    return str(layout_log.resolve()), str(dylib_log.resolve())


def safe_fixture_mount(dmg: Path, destination: Path) -> Path:
    require(not destination.exists(), f"Fixture mount destination must be new: {destination}")
    destination.mkdir(parents=True)
    try:
        with zipfile.ZipFile(dmg) as archive:
            require(bool(archive.infolist()), "Fixture DMG is empty")
            seen: set[str] = set()
            for entry in archive.infolist():
                relative = PurePosixPath(entry.filename)
                require(bool(entry.filename) and "\x00" not in entry.filename and not relative.is_absolute() and ".." not in relative.parts, f"Unsafe fixture DMG entry: {entry.filename!r}")
                normalized = relative.as_posix().rstrip("/")
                require(normalized not in seen, f"Duplicate fixture DMG entry: {entry.filename}")
                seen.add(normalized)
                target = destination.joinpath(*relative.parts)
                mode = entry.external_attr >> 16
                if stat.S_ISLNK(mode):
                    target.parent.mkdir(parents=True, exist_ok=True)
                    link_target = archive.read(entry).decode("utf-8")
                    target.symlink_to(link_target)
                    continue
                if entry.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                    continue
                target.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(entry) as source, target.open("xb") as output:
                    shutil.copyfileobj(source, output)
                if mode:
                    target.chmod(mode & 0o777)
    except BaseException:
        shutil.rmtree(destination, ignore_errors=True)
        raise
    return destination


def mount_dmg(dmg: Path, evidence: Path) -> tuple[Path, str, str, Path]:
    mount_log = evidence / "mount.log"
    if FIXTURE_MODE:
        mount_path = evidence / "fixture Volumes" / "LizzieYzy Next - Fixture"
        safe_fixture_mount(dmg, mount_path)
        mount_log.write_text(f"FIXTURE read-only mount: {mount_path}\n", encoding="utf-8")
        if os.environ.get("LIZZIE_MACOS_ACCEPTANCE_FIXTURE_POST_ATTACH_FAIL") == "1":
            try:
                detach_dmg(mount_path, "fixture-device", evidence)
            except AcceptanceError as cleanup_error:
                raise MountAcquisitionError(
                    f"Fixture post-attach inspection failed; cleanup also failed: {cleanup_error}",
                    mount_path,
                    "fixture-device",
                ) from cleanup_error
            raise AcceptanceError("Fixture post-attach inspection failed after successful mount")
        return mount_path, "fixture-device", "LizzieYzy Next - Fixture", mount_log
    mount_path = evidence / "mounted DMG"
    mount_path.mkdir()
    plist_path = evidence / "hdiutil-attach.plist"
    device = ""
    try:
        result = subprocess.run(["hdiutil", "attach", str(dmg), "-mountpoint", str(mount_path), "-readonly", "-noverify", "-noautoopen", "-nobrowse", "-plist"], capture_output=True, timeout=120)
        attached = plistlib.loads(result.stdout)
        entities = [row for row in attached.get("system-entities", []) if row.get("mount-point")]
        if len(entities) == 1:
            device = str(entities[0].get("dev-entry", ""))
        plist_path.write_bytes(result.stdout)
        mount_log.write_bytes(result.stderr)
        require(result.returncode == 0, f"hdiutil read-only attach failed with exit code {result.returncode}")
        require(len(entities) == 1, "DMG attach did not yield exactly one mounted volume")
        require(bool(device), "DMG attach did not identify its device")
        info = subprocess.run(["diskutil", "info", "-plist", str(mount_path)], capture_output=True, timeout=30)
        require(info.returncode == 0, "diskutil could not inspect the mounted DMG")
        disk = plistlib.loads(info.stdout)
        volume_name = str(disk.get("VolumeName", ""))
        require(bool(volume_name), "Mounted DMG has no volume identity")
        return mount_path, device, volume_name, mount_log
    except BaseException as inspection_error:
        if not device and isinstance(inspection_error, subprocess.TimeoutExpired):
            output = inspection_error.stdout
            if output:
                try:
                    attached = plistlib.loads(output)
                    entities = [row for row in attached.get("system-entities", []) if row.get("mount-point")]
                    if len(entities) == 1:
                        device = str(entities[0].get("dev-entry", ""))
                except (plistlib.InvalidFileException, TypeError, ValueError):
                    pass
        try:
            if device or mount_path.is_mount():
                detach_dmg(mount_path, device, evidence)
            elif mount_path.exists():
                mount_path.rmdir()
        except BaseException as cleanup_error:
            raise MountAcquisitionError(
                f"DMG attach or post-attach inspection failed: {inspection_error}; cleanup also failed: {cleanup_error}",
                mount_path,
                device,
            ) from inspection_error
        raise AcceptanceError(f"DMG attach or post-attach inspection failed: {inspection_error}") from inspection_error


def detach_dmg(mount_path: Path, device: str, evidence: Path) -> None:
    detach_log = evidence / "eject.log"
    if FIXTURE_MODE:
        fail_once = evidence / "fixture-eject-failed-once"
        if os.environ.get("LIZZIE_MACOS_ACCEPTANCE_FIXTURE_EJECT_FAIL") == "1" and not fail_once.exists():
            fail_once.write_text("failed\n", encoding="utf-8")
            detach_log.write_text("FIXTURE eject failure\n", encoding="utf-8")
            raise AcceptanceError("Fixture DMG eject failed")
        shutil.rmtree(mount_path.parent, ignore_errors=False)
        detach_log.write_text(f"FIXTURE ejected {device}\n", encoding="utf-8")
        return
    detach_target = device or str(mount_path)
    result = command_log(["hdiutil", "detach", detach_target], detach_log, timeout=60)
    require(result.returncode == 0, f"hdiutil detach failed with exit code {result.returncode}")
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline and mount_path.is_mount():
        time.sleep(0.2)
    require(not mount_path.is_mount(), f"DMG mount remained after eject: {mount_path}")
    mount_path.rmdir()


def binary_architectures(path: Path, label: str) -> list[str]:
    if FIXTURE_MODE:
        sidecar = Path(f"{path}.arch")
        require(sidecar.is_file(), f"{label} fixture architecture evidence is missing: {sidecar}")
        values = sidecar.read_text(encoding="utf-8").split()
    else:
        result = subprocess.run(["lipo", "-archs", str(path)], capture_output=True, text=True, timeout=20)
        require(result.returncode == 0, f"Unable to inspect {label} architecture: {result.stderr.strip()}")
        values = result.stdout.split()
    normalized = sorted({normalized_architecture(value) for value in values})
    require(bool(normalized), f"{label} has no architecture identity")
    return normalized


def architecture_identity(path: Path, label: str, expected: str, *, executable: bool = False, root: Path | None = None) -> dict[str, Any]:
    identity = file_identity(path, label, executable=executable, root=root)
    architectures = binary_architectures(Path(identity["path"]), label)
    require(architectures == [expected], f"{label} architectures {architectures} do not exactly match candidate architecture {expected}")
    return identity | {"architectures": architectures}


def resolve_installed_layout(app: Path, product: dict[str, str]) -> dict[str, Any]:
    require(app.name == "LizzieYzy Next.app" and app.is_dir(), f"Installed app identity is invalid: {app}")
    info_path = app / "Contents" / "Info.plist"
    require(info_path.is_file(), f"Installed app Info.plist is missing: {info_path}")
    with info_path.open("rb") as stream:
        info = plistlib.load(stream)
    require(info.get("CFBundleIdentifier") == "com.wimi321.lizzieyzy.next", "Installed app bundle identifier is unexpected")
    launcher = app / "Contents" / "MacOS" / "LizzieYzy Next"
    runtime = app / "Contents" / "runtime" / "Contents" / "Home" / "bin" / "java"
    app_root = app / "Contents" / "app"
    launcher_config = app_root / "LizzieYzy Next.cfg"
    jars = sorted(app_root.glob("*-shaded.jar"))
    require(len(jars) == 1, "Installed app must contain exactly one shaded JAR")
    jar = jars[0]
    require(launcher_config.is_file(), f"Installed launcher config is missing: {launcher_config}")
    require(jar.name in launcher_config.read_text(encoding="utf-8"), "Installed launcher config does not select the installed shaded JAR")
    jcef_root = app_root / "jcef-bundle"
    jcef_library = jcef_root / "libjcef.dylib"
    jcef_helper = jcef_root / "jcef Helper.app" / "Contents" / "MacOS" / "jcef Helper"
    jcef_manifest = jcef_root / "lizzieyzy-next-jcef-manifest.txt"
    require(jcef_manifest.is_file() and f"platform={product['jcefPlatform']}" in jcef_manifest.read_text(encoding="utf-8"), "Installed JCEF manifest architecture differs from the candidate")
    engine_root = app_root / "engines" / "katago" / product["engineDirectory"]
    engine = engine_root / "katago"
    engine_config = app_root / "engines" / "katago" / "configs" / "gtp.cfg"
    engine_version = app_root / "engines" / "katago" / "VERSION.txt"
    model = app_root / "weights" / "default.bin.gz"
    require(engine_version.is_file(), f"Installed KataGo version identity is missing: {engine_version}")
    return {
        "appPath": str(app.resolve()), "bundleIdentifier": str(info["CFBundleIdentifier"]),
        "launcher": architecture_identity(launcher, "installed launcher", product["architecture"], executable=True, root=app),
        "runtime": architecture_identity(runtime, "installed Java", product["architecture"], executable=True, root=app),
        "launcherConfig": file_identity(launcher_config, "installed launcher config", root=app), "jar": file_identity(jar, "installed shaded JAR", root=app),
        "jcef": tree_identity(jcef_root, "installed JCEF bundle", root=app) | {"library": architecture_identity(jcef_library, "installed JCEF library", product["architecture"], root=app), "helper": architecture_identity(jcef_helper, "installed JCEF helper", product["architecture"], executable=True, root=app), "manifest": file_identity(jcef_manifest, "installed JCEF manifest", root=app)},
        "engine": architecture_identity(engine, "installed KataGo", product["architecture"], executable=True, root=app) | {"config": file_identity(engine_config, "installed KataGo config", root=app), "version": file_identity(engine_version, "installed KataGo version", root=app)},
        "model": file_identity(model, "installed KataGo model", root=app),
    }


def probe_runtime(runtime: Path, expected_architecture: str, log_path: Path) -> tuple[str, str]:
    result = subprocess.run([str(runtime), "-XshowSettings:properties", "-version"], capture_output=True, text=True, timeout=20)
    output = (result.stdout + result.stderr).strip()
    log_path.write_text(output + "\n", encoding="utf-8")
    require(result.returncode == 0, f"Installed Java -version failed with exit code {result.returncode}")
    match = re.search(r"(?m)^\s*os\.arch\s*=\s*(\S+)\s*$", output)
    require(match is not None, "Installed Java properties do not report os.arch")
    architecture = normalized_architecture(match.group(1))
    require(architecture == expected_architecture, f"Installed Java architecture {architecture} differs from candidate {expected_architecture}")
    return output, architecture


def credential_state() -> str:
    required = ("APPLE_CERT_P12", "APPLE_ID", "APPLE_APP_PASSWORD", "APPLE_TEAM_ID")
    configured = [name for name in required if os.environ.get(name)]
    if not configured:
        return "ABSENT"
    require(len(configured) == len(required), "Partial Apple signing credentials are configured; acceptance fails closed")
    return "COMPLETE"
def codesign_reports_fully_unsigned(result: subprocess.CompletedProcess[str]) -> bool:
    output = f"{result.stdout}\n{result.stderr}"
    return (
        result.returncode != 0
        and re.search(r"(?im)^.*: code object is not signed at all\s*$", output) is not None
        and re.search(r"(?im)^\s*In subcomponent:", output) is None
    )




def confirm_open_anyway(app: Path, dmg: Path, evidence: Path, quarantine_value: str) -> str:
    launcher = app / "Contents" / "MacOS" / "LizzieYzy Next"
    request = {
        "schemaVersion": 1,
        "nonce": secrets.token_hex(16),
        "artifactSha256": sha256_file(dmg),
        "appPath": str(app.resolve()),
        "launcherSha256": sha256_file(launcher),
        "quarantineAttribute": quarantine_value,
        "blockedAt": now(),
    }
    request_path = evidence / "open-anyway-request.json"
    write_json_atomic(request_path, request)
    marker_text = os.environ.get("LIZZIE_MACOS_OPEN_ANYWAY_MARKER", "")
    if not marker_text:
        raise BlockedError("gatekeeper", f"Unsigned candidate is Gatekeeper-blocked; complete Privacy & Security/Open Anyway and write the bound confirmation described by {request_path}")
    marker = Path(marker_text).resolve()
    deadline = time.monotonic() + int(os.environ.get("LIZZIE_MACOS_ACCEPTANCE_TIMEOUT_SECONDS", "120"))
    while time.monotonic() < deadline and not marker.is_file():
        time.sleep(0.2)
    if not marker.is_file():
        raise BlockedError("gatekeeper", f"Open Anyway confirmation is unavailable: {marker}")
    confirmation = read_json(marker, "Open Anyway confirmation")
    expected_keys = set(request) | {"confirmedAt", "screenshot", "launchObserved"}
    require(set(confirmation) == expected_keys, "Open Anyway confirmation fields differ from the bound request contract")
    for key, value in request.items():
        require(confirmation.get(key) == value, f"Open Anyway confirmation does not bind current {key}")
    try:
        blocked_at = datetime.fromisoformat(str(request["blockedAt"]).replace("Z", "+00:00"))
        confirmed_at = datetime.fromisoformat(str(confirmation["confirmedAt"]).replace("Z", "+00:00"))
    except ValueError as exc:
        raise AcceptanceError("Open Anyway confirmation timestamps are invalid") from exc
    require(confirmed_at > blocked_at, "Open Anyway confirmation must be created after the observed Gatekeeper block")
    require(confirmation["launchObserved"] is True, "Open Anyway confirmation must record the LaunchServices retry for this installed app")
    screenshot = Path(str(confirmation["screenshot"])).resolve()
    require(screenshot.is_file(), f"Open Anyway confirmation screenshot is missing: {screenshot}")
    return str(marker)


def quarantine_and_assess(app: Path, dmg: Path, evidence: Path) -> dict[str, str]:
    credentials = credential_state()
    if FIXTURE_MODE:
        metadata = read_json(app / "Contents" / ".fixture-signing.json", "fixture signing metadata")
        quarantine_value = "0081;fixture;LizzieYzyAcceptance;"
        (evidence / "quarantine.log").write_text(quarantine_value + "\n", encoding="utf-8")
        signing_state = metadata.get("state")
        require(signing_state in {"signed", "unsigned", "broken"}, "Fixture signing state is invalid")
        if signing_state == "broken":
            raise AcceptanceError("Installed app has a malformed or partial signature")
        signed = signing_state == "signed"
        notarized = metadata.get("notarized") is True
        accepted = metadata.get("spctlAccepted") is True
        if credentials == "COMPLETE":
            require(signed and notarized and accepted, "Complete signing credentials require a signed/notarized accepted candidate")
        if signed:
            require(notarized and accepted, "Partially signed/notarized fixture candidate fails acceptance")
            status, first_launch, open_anyway = "SIGNED_NOTARIZED", "ALLOWED", "NOT_APPLICABLE"
        else:
            require(not notarized and not accepted and metadata.get("firstLaunch") == "BLOCKED", "Unsigned fixture did not prove the expected Gatekeeper block")
            status, first_launch = "UNSIGNED_INTENTIONAL", "BLOCKED_THEN_OPEN_ANYWAY"
            confirm_open_anyway(app, dmg, evidence, quarantine_value)
            open_anyway = "BOUND_CONFIRMATION"
        (evidence / "codesign.log").write_text(f"fixture signingState={signing_state}\n", encoding="utf-8")
        (evidence / "stapler.log").write_text(f"fixture notarized={notarized}\n", encoding="utf-8")
        (evidence / "spctl.log").write_text(f"fixture accepted={accepted}\n", encoding="utf-8")
        return {"attribute": quarantine_value, "signatureStatus": status, "codesign": str((evidence / "codesign.log").resolve()), "stapler": str((evidence / "stapler.log").resolve()), "spctl": str((evidence / "spctl.log").resolve()), "firstLaunch": first_launch, "openAnyway": open_anyway}
    quarantine_value = f"0081;{int(time.time()):x};LizzieYzyAcceptance;"
    quarantine_log = evidence / "quarantine.log"
    result = command_log(["xattr", "-w", "com.apple.quarantine", quarantine_value, str(app)], quarantine_log)
    if result.returncode != 0:
        raise BlockedError("gatekeeper", "Unable to apply the quarantine attribute; the native host lacks required quarantine/file permissions")
    verify = subprocess.run(["xattr", "-p", "com.apple.quarantine", str(app)], capture_output=True, text=True, timeout=20)
    if verify.returncode != 0:
        raise BlockedError("gatekeeper", "Unable to read the installed app quarantine attribute on this native host")
    require(quarantine_value in verify.stdout, "Installed app quarantine attribute did not persist")
    codesign = command_log(["codesign", "--verify", "--deep", "--strict", "--verbose=2", str(app)], evidence / "codesign.log")
    stapler = command_log(["xcrun", "stapler", "validate", str(dmg)], evidence / "stapler.log")
    spctl = command_log(["spctl", "--assess", "--type", "open", "--context", "context:primary-signature", "-vvv", str(dmg)], evidence / "spctl.log")
    if codesign.returncode == 0:
        require(stapler.returncode == 0 and spctl.returncode == 0, "Signed candidate lacks a valid stapled ticket or Gatekeeper acceptance")
        return {"attribute": quarantine_value, "signatureStatus": "SIGNED_NOTARIZED", "codesign": str((evidence / "codesign.log").resolve()), "stapler": str((evidence / "stapler.log").resolve()), "spctl": str((evidence / "spctl.log").resolve()), "firstLaunch": "ALLOWED_BY_GATEKEEPER_ASSESSMENT", "openAnyway": "NOT_APPLICABLE"}
    require(credentials == "ABSENT", "Signing credentials were available but the installed app signature is invalid")
    outer_signature = command_log(["codesign", "--display", "--verbose=2", str(app)], evidence / "codesign-outer.log")
    require(codesign_reports_fully_unsigned(outer_signature), "Installed app has a malformed or partial signature; only an explicitly unsigned outer app may use Open Anyway")
    require(stapler.returncode != 0 and spctl.returncode != 0, "Unsigned candidate produced an unexpected notarization/Gatekeeper outcome")
    gatekeeper_log = evidence / "gatekeeper-first-launch.log"
    blocked = False
    try:
        first_launch = subprocess.run(["open", "-W", "-n", str(app)], capture_output=True, text=True, timeout=15)
        gatekeeper_log.write_text(f"exit={first_launch.returncode}\n{first_launch.stdout}{first_launch.stderr}", encoding="utf-8")
        blocked = first_launch.returncode != 0
    except subprocess.TimeoutExpired as exc:
        gatekeeper_log.write_text(f"timeout while waiting for Gatekeeper\n{exc.stdout or ''}{exc.stderr or ''}", encoding="utf-8")
        blocked = True
    launched = [pid for pid, row in process_table().items() if str(app.resolve()) in row["commandLine"]]
    for pid in launched:
        try:
            os.kill(pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
    require(blocked and not launched, "Unsigned quarantined app unexpectedly launched before Open Anyway")
    confirm_open_anyway(app, dmg, evidence, quarantine_value)
    return {"attribute": quarantine_value, "signatureStatus": "UNSIGNED_INTENTIONAL", "codesign": str((evidence / "codesign.log").resolve()), "stapler": "NOT_PERFORMED", "spctl": str((evidence / "spctl.log").resolve()), "firstLaunch": "BLOCKED_THEN_OPEN_ANYWAY", "openAnyway": "BOUND_CONFIRMATION"}


def parse_process_tables(metadata_output: str, arguments_output: str) -> dict[int, dict[str, Any]]:
    arguments: dict[int, str] = {}
    for line in arguments_output.splitlines():
        parts = line.strip().split(None, 1)
        if not parts:
            continue
        try:
            arguments[int(parts[0])] = parts[1] if len(parts) == 2 else ""
        except ValueError:
            continue
    table: dict[int, dict[str, Any]] = {}
    for line in metadata_output.splitlines():
        parts = line.strip().split(None, 8)
        if len(parts) != 9:
            continue
        try:
            pid, parent = int(parts[0]), int(parts[1])
        except ValueError:
            continue
        if "Z" not in parts[2]:
            table[pid] = {
                "parentPid": parent,
                "state": parts[2],
                "incarnation": " ".join(parts[3:8]),
                "image": parts[8],
                "commandLine": arguments.get(pid, ""),
            }
    return table


def process_table() -> dict[int, dict[str, Any]]:
    metadata = subprocess.run(["ps", "-axo", "pid=,ppid=,state=,lstart=,comm="], capture_output=True, text=True, timeout=10)
    arguments = subprocess.run(["ps", "-axo", "pid=,args="], capture_output=True, text=True, timeout=10)
    require(metadata.returncode == 0 and arguments.returncode == 0, "Unable to inspect process table")
    return parse_process_tables(metadata.stdout, arguments.stdout)


def descendants(root_pid: int, table: dict[int, dict[str, Any]] | None = None) -> list[int]:
    rows = table if table is not None else process_table()
    owned = {root_pid}
    changed = True
    while changed:
        changed = False
        for pid, row in rows.items():
            if row["parentPid"] in owned and pid not in owned:
                owned.add(pid)
                changed = True
    return sorted(pid for pid in owned if pid in rows)


def process_snapshots(pids: list[int], table: dict[int, dict[str, Any]] | None = None) -> list[dict[str, Any]]:
    rows = table if table is not None else process_table()
    return [{"pid": pid, **rows[pid]} for pid in pids if pid in rows]



def probe_network_boundary(profile: Path, evidence: Path) -> bool:
    if FIXTURE_MODE:
        (evidence / "network-boundary-probe.log").write_text(
            "FIXTURE loopback available; external network denied.\n",
            encoding="utf-8",
        )
        return True
    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.bind(("127.0.0.1", 0))
    listener.listen(1)
    listener.settimeout(10)
    port = listener.getsockname()[1]
    accepted: list[bool] = []
    errors: list[str] = []

    def accept_once() -> None:
        try:
            connection, _ = listener.accept()
            connection.close()
            accepted.append(True)
        except OSError as exc:
            errors.append(str(exc))

    worker = threading.Thread(target=accept_once, daemon=True)
    worker.start()
    loopback = subprocess.run(
        ["sandbox-exec", "-f", str(profile), sys.executable, "-c", "import socket,sys;s=socket.create_connection(('127.0.0.1',int(sys.argv[1])),5);s.close()", str(port)],
        capture_output=True,
        text=True,
        timeout=10,
    )
    worker.join(timeout=10)
    listener.close()
    external = subprocess.run(
        ["sandbox-exec", "-f", str(profile), sys.executable, "-c", "import errno,socket,sys\ntry:\n socket.create_connection(('198.51.100.1',443),2);sys.exit(2)\nexcept OSError as e:\n print(e.errno);sys.exit(0 if e.errno in (errno.EPERM,errno.EACCES) else 3)"],
        capture_output=True,
        text=True,
        timeout=10,
    )
    log = evidence / "network-boundary-probe.log"
    log.write_text(
        f"loopbackExit={loopback.returncode}\n{loopback.stdout}{loopback.stderr}externalExit={external.returncode}\n{external.stdout}{external.stderr}acceptErrors={errors}\n",
        encoding="utf-8",
    )
    require(loopback.returncode == 0 and accepted and not errors, "Offline boundary does not preserve loopback")
    require(external.returncode == 0, "Offline boundary did not prove OS-level external network denial")
    return True


class FixtureNetworkMonitor:
    def __init__(self, mode: str, state_path: Path) -> None:
        self.mode = mode
        self.state_path = state_path
        self.poll_count = 0
        self.stopped = False
        self._write_state()

    def _write_state(self) -> None:
        write_json_atomic(
            self.state_path,
            {"mode": self.mode, "pollCount": self.poll_count, "stopped": self.stopped},
        )

    def poll(self) -> int | None:
        self.poll_count += 1
        if self.poll_count == 1:
            self._write_state()
            return None
        if self.mode == "dead":
            self.stopped = True
            self._write_state()
            return 1
        if self.mode == "query-failure":
            self._write_state()
            raise OSError("fixture network monitor query failed")
        self._write_state()
        return 0 if self.stopped else None

    def send_signal(self, _: int) -> None:
        if self.mode == "live-leak":
            raise OSError("fixture network monitor rejected SIGINT")
        self.stopped = True
        self._write_state()

    def kill(self) -> None:
        if self.mode == "live-leak":
            raise OSError("fixture network monitor rejected kill")
        self.stopped = True
        self._write_state()

    def wait(self, timeout: int) -> int:
        del timeout
        if not self.stopped:
            raise OSError("fixture network monitor remains live")
        return 0


def start_network_monitor(evidence: Path) -> tuple[subprocess.Popen[bytes] | FixtureNetworkMonitor | None, Any, Path]:
    log_path = evidence / "network-deny-events.ndjson"
    if FIXTURE_MODE:
        mode = os.environ.get("LIZZIE_MACOS_ACCEPTANCE_FIXTURE_NETWORK_MONITOR_MODE")
        if not mode:
            return None, None, log_path
        require(mode in {"dead", "unreadable", "malformed", "query-failure", "live-leak"}, f"Unknown fixture network monitor mode: {mode}")
        stream = log_path.open("wb")
        if mode == "unreadable":
            stream.write(b"\xff\n")
        elif mode == "malformed":
            stream.write(b"not-json\n")
        stream.flush()
        monitor = FixtureNetworkMonitor(mode, evidence / "network-monitor-fixture-state.json")
        require(monitor.poll() is None, "Fixture network monitor did not start live")
        return monitor, stream, log_path
    try:
        stream = log_path.open("wb")
        monitor = subprocess.Popen(
            ["log", "stream", "--style", "ndjson", "--predicate", 'eventMessage CONTAINS[c] "deny" AND eventMessage CONTAINS[c] "network"'],
            stdout=stream,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
    except OSError as exc:
        if "stream" in locals() and not stream.closed:
            stream.close()
        raise BlockedError("launch", f"macOS unified-log network monitor is unavailable: {exc}") from exc
    time.sleep(0.5)
    try:
        return_code = monitor.poll()
    except OSError as exc:
        try:
            monitor.kill()
            monitor.wait(timeout=5)
        except (OSError, subprocess.TimeoutExpired) as cleanup_error:
            stream.close()
            raise AcceptanceError(f"macOS unified-log network monitor remains live after startup failure: {cleanup_error}") from cleanup_error
        stream.close()
        raise BlockedError("launch", f"macOS unified-log network monitor could not be queried after startup: {exc}") from exc
    if return_code is not None:
        stream.close()
        raise BlockedError("launch", "macOS unified-log network monitor could not remain live")
    return monitor, stream, log_path


def stop_network_monitor(monitor: subprocess.Popen[bytes] | FixtureNetworkMonitor | None, stream: Any, log_path: Path, ownership_tokens: list[str]) -> int:
    if monitor is None or stream is None:
        raise BlockedError("cleanup", "macOS unified-log network monitor was not active")
    try:
        if monitor.poll() is not None:
            stream.close()
            raise BlockedError("cleanup", "macOS unified-log network monitor exited before the acceptance run stopped it")
        monitor.send_signal(signal.SIGINT)
        monitor.wait(timeout=10)
    except subprocess.TimeoutExpired as exc:
        try:
            monitor.kill()
            monitor.wait(timeout=5)
        except (OSError, subprocess.TimeoutExpired) as cleanup_error:
            stream.close()
            raise AcceptanceError(f"macOS unified-log network monitor remains live after cleanup: {cleanup_error}") from cleanup_error
        stream.close()
        raise BlockedError("cleanup", "macOS unified-log network monitor did not stop cleanly") from exc
    except OSError as exc:
        try:
            monitor.kill()
        except ProcessLookupError:
            pass
        except OSError as cleanup_error:
            stream.close()
            raise AcceptanceError(f"macOS unified-log network monitor remains live after cleanup: {cleanup_error}") from cleanup_error
        try:
            monitor.wait(timeout=5)
        except (OSError, subprocess.TimeoutExpired) as cleanup_error:
            stream.close()
            raise AcceptanceError(f"macOS unified-log network monitor remains live after cleanup: {cleanup_error}") from cleanup_error
        stream.close()
        raise BlockedError("cleanup", f"macOS unified-log network monitor could not be queried or stopped: {exc}") from exc
    stream.close()
    if not log_path.is_file():
        raise BlockedError("cleanup", "macOS unified-log network event evidence is missing")
    try:
        lines = log_path.read_text(encoding="utf-8", errors="strict").splitlines()
    except (OSError, UnicodeError) as exc:
        raise BlockedError("cleanup", f"macOS unified-log network evidence is unreadable: {exc}") from exc
    attempts = 0
    for line_number, line in enumerate(lines, 1):
        if not line.strip():
            continue
        try:
            event = json.loads(line)
        except json.JSONDecodeError as exc:
            raise BlockedError("cleanup", f"macOS unified-log network evidence is not parseable at line {line_number}") from exc
        encoded = json.dumps(event, ensure_ascii=False, sort_keys=True)
        if any(token and token in encoded for token in ownership_tokens):
            attempts += 1
    return attempts


def wait_for_ready(supervisor: subprocess.Popen[bytes], launcher: Path, data_root: Path, ready_file: Path, timeout_seconds: int) -> tuple[int, list[dict[str, Any]], Path]:
    deadline = time.monotonic() + timeout_seconds
    app_log = data_root / "logs" / "app.log"
    while time.monotonic() < deadline:
        if supervisor.poll() is not None:
            raise AcceptanceError(f"Installed launcher exited before readiness with code {supervisor.returncode}")
        rows = process_snapshots(descendants(supervisor.pid))
        launcher_rows = [row for row in rows if str(launcher.resolve()) in row["commandLine"]]
        ready = ready_file.is_file() if FIXTURE_MODE else bool(launcher_rows)
        if ready and launcher_rows and (data_root / "config.txt").is_file() and (data_root / "persist").is_file() and app_log.is_file():
            return int(launcher_rows[0]["pid"]), rows, app_log
        time.sleep(0.1)
    raise AcceptanceError("Timed out waiting for installed application readiness, isolated config and persist")


def owned_processes(captured: list[dict[str, Any]], ownership_tokens: list[str]) -> list[dict[str, Any]]:
    table = process_table()
    incarnations = {(int(row["pid"]), str(row["incarnation"])) for row in captured}
    owned: list[dict[str, Any]] = []
    for pid, row in table.items():
        same_incarnation = (pid, str(row["incarnation"])) in incarnations
        names_owned_path = any(token and (token in row["image"] or token in row["commandLine"]) for token in ownership_tokens)
        if same_incarnation or names_owned_path:
            owned.append({"pid": pid, **row})
    return owned


def terminate_owned(supervisor: subprocess.Popen[bytes], captured: list[dict[str, Any]], ownership_tokens: list[str], timeout_seconds: int) -> tuple[bool, list[int], list[str]]:
    errors: list[str] = []
    for row in owned_processes(captured, ownership_tokens):
        try:
            os.kill(int(row["pid"]), signal.SIGTERM)
        except ProcessLookupError:
            pass
        except OSError as exc:
            errors.append(f"pid:{row['pid']}:{exc}")
    deadline = time.monotonic() + timeout_seconds
    remaining_rows = owned_processes(captured, ownership_tokens)
    while time.monotonic() < deadline and remaining_rows:
        time.sleep(0.1)
        remaining_rows = owned_processes(captured, ownership_tokens)
    for row in remaining_rows:
        try:
            os.kill(int(row["pid"]), signal.SIGKILL)
        except ProcessLookupError:
            pass
        except OSError as exc:
            errors.append(f"pid:{row['pid']}:{exc}")
    try:
        supervisor.wait(timeout=max(1, timeout_seconds))
    except subprocess.TimeoutExpired:
        errors.append(f"supervisor:{supervisor.pid}:did not exit")
    final = [int(row["pid"]) for row in owned_processes(captured, ownership_tokens)]
    if FIXTURE_MODE and os.environ.get("LIZZIE_MACOS_ACCEPTANCE_FIXTURE_PROCESS_CLEANUP_ERROR") == "1":
        errors.append("fixture-owned-process-cleanup-error")
    return not final and not errors, final, errors


def wait_engine_oracle(path: Path, timeout_seconds: int) -> Path:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        if path.is_file():
            return path
        time.sleep(0.2)
    raise BlockedError("verify", f"Timed out waiting for the bound engine oracle producer to atomically write: {path}")


def require_keys(value: object, names: set[str], label: str) -> dict[str, Any]:
    require(isinstance(value, dict), f"{label} must be an object")
    assert isinstance(value, dict)
    require(set(value) == names, f"{label} fields differ; expected={sorted(names)} actual={sorted(value)}")
    return value


def validate_engine_oracle(path: Path, run_record: dict[str, Any], run_path: Path, candidate: dict[str, Any]) -> dict[str, Any]:
    envelope = require_keys(read_json(path, "macOS engine analysis oracle envelope"), {"schemaVersion", "scenario", "status", "binding", "oracle"}, "engine oracle envelope")
    require(envelope["schemaVersion"] == 1 and envelope["scenario"] == "macos-installed-product-real-cpu" and envelope["status"] == "PASS", "Engine oracle envelope identity did not PASS")
    binding = require_keys(envelope["binding"], {"candidateSha256", "artifactSha256", "runJsonSha256", "launcherSha256", "runtimeSha256", "jarSha256", "dataRoot", "launcherPid", "enginePid", "engineCommand", "oracleSha256"}, "engine oracle binding")
    require(binding["candidateSha256"] == run_record["candidate"]["sha256"] and binding["artifactSha256"] == run_record["candidate"]["artifactSha256"] and binding["runJsonSha256"] == sha256_file(run_path), "Engine oracle candidate/run binding differs from this live run")
    require(binding["launcherSha256"] == run_record["launcher"]["sha256"] and binding["runtimeSha256"] == run_record["runtime"]["sha256"] and binding["jarSha256"] == run_record["jar"]["sha256"], "Engine oracle installed runtime binding differs from this live run")
    require(binding["dataRoot"] == run_record["dataRoot"] and binding["launcherPid"] == run_record["launcher"]["pid"], "Engine oracle process/data-root binding differs from this live run")
    oracle = require_keys(envelope["oracle"], {"schemaVersion", "scenario", "status", "failure", "peer", "source", "platform", "manifest", "assets", "fixture", "node", "rules", "position", "analysis", "phasesMs", "stop", "quit", "cleanup", "evidence"}, "engine-sgf oracle")
    require(binding["oracleSha256"] == json_sha256(oracle), "Engine oracle payload hash binding differs")
    require(oracle["schemaVersion"] == 1 and oracle["scenario"] == "real-cpu-engine" and oracle["status"] == "PASS" and oracle["failure"] is None, "Engine-sgf oracle did not PASS")
    peer = require_keys(oracle["peer"], {"kind", "catalogEngineId", "command", "pid", "stdoutReader", "stderrReader"}, "engine oracle peer")
    require(peer["kind"] == "real-katago-cpu" and all(isinstance(peer[name], str) and bool(peer[name].strip()) for name in ("catalogEngineId", "stdoutReader", "stderrReader")) and peer["pid"] == binding["enginePid"] and peer["command"] == binding["engineCommand"], "Engine oracle does not prove bound production catalog/manager ownership and reader identities")
    engine_pid = int(binding["enginePid"])
    captured = [row for row in run_record["processes"] if int(row["pid"]) == engine_pid]
    require(len(captured) == 1 and captured[0]["commandLine"] == binding["engineCommand"], "Engine oracle PID is not the captured installed engine incarnation")
    require(engine_pid not in process_table(), "Engine oracle claims cleanup but the bound engine PID is still live")
    engine_path, model_path, config_path = run_record["engine"]["path"], run_record["engine"]["modelPath"], run_record["engine"]["configPath"]
    require(all(item in str(binding["engineCommand"]) for item in (engine_path, model_path, config_path)), "Bound engine command does not name the installed executable/model/config")
    require(oracle["source"]["commit"] == candidate["targetSha"] and oracle["source"]["dirty"] is False and re.search(r"(?i)(mac|darwin)", str(oracle["platform"]["os"])), "Engine oracle source/platform identity differs from this macOS candidate")
    assets = oracle["assets"]
    require(assets["enginePath"] == engine_path and assets["engineSha256"] == run_record["engine"]["sha256"] and assets["modelPath"] == model_path and assets["modelSha256"] == run_record["engine"]["modelSha256"] and assets["configPath"] == config_path and assets["configSha256"] == run_record["engine"]["configSha256"], "Engine oracle does not use this installed product's packaged engine closure")
    require(oracle["fixture"]["sha256"] == "ec41cf044ee29b2408b488727db2b6bbe5a2a3acfe5ed74d70fbb6dac2be3a9c", "Engine oracle did not use the frozen D4 fixture")
    require(oracle["node"]["semanticPath"] == "0/0/0/0/0" and oracle["node"]["kind"] == "PASS" and bool(oracle["node"]["identity"]), "Engine oracle frozen semantic node is invalid")
    actual_rules = require_keys(oracle["rules"]["actual"], {"friendlyPassOk", "scoring", "ko", "whiteHandicapBonus", "suicide", "tax", "hasButton"}, "engine oracle actual rules")
    require(oracle["rules"]["targetRaw"] == "Chinese" and oracle["rules"]["targetSummary"] == "CHINESE" and int(oracle["rules"]["targetRevision"]) > 0 and oracle["rules"]["status"] == "CONFIRMED" and oracle["rules"]["fresh"] is True and actual_rules == {"friendlyPassOk": True, "scoring": "AREA", "ko": "SIMPLE", "whiteHandicapBonus": "N", "suicide": False, "tax": "NONE", "hasButton": False}, "Engine oracle fresh actual Chinese rules are invalid")
    require(oracle["position"] == {"confirmed": True, "board": "19x19", "komi": 6.5, "stones": "B:fd;W:ee,ff,hh", "empty": "dd", "turn": "W", "setupKind": "SNAPSHOT", "setupStones": "B:fd;W:ee,ff", "tailPrevious": "MOVE:W[hh]", "tailCurrent": "PASS:B[]"}, "Engine oracle peer position differs from the frozen SNAPSHOT/MOVE/PASS target")
    require(oracle["analysis"]["schema"] == "katago-info-v1" and int(oracle["analysis"]["visits"]) > 0 and bool(oracle["analysis"]["move"]), "Engine oracle has no structurally parsed positive-visit candidate")
    require(oracle["stop"]["requested"] is True and oracle["stop"]["quiet"] is True and oracle["stop"]["quietWindowMs"] == 400 and int(oracle["stop"]["peerOutputCount"]) > 0 and int(oracle["stop"]["applicationVisits"]) > 0, "Engine oracle quiet-stop evidence is incomplete")
    oracle_cleanup = oracle["cleanup"]
    require(oracle["quit"]["normal"] is True and oracle_cleanup["forced"] is False and oracle_cleanup["process"] is True and oracle_cleanup["readers"] is True and oracle_cleanup["stagedSgf"] is True, "Engine oracle normal quit/cleanup is incomplete")
    for name in ("result", "stdout", "stderr", "appLog", "phases"):
        require(Path(str(oracle["evidence"][name])).is_file(), f"Engine oracle evidence is missing: {name}")
    require(json_sha256(read_json(Path(str(oracle["evidence"]["result"])), "engine oracle result evidence")) == binding["oracleSha256"], "Engine oracle result evidence differs from the bound payload")
    staged = oracle["evidence"]["stagedSgfs"]
    require(isinstance(staged, list) and bool(staged) and not any(Path(str(item)).exists() for item in staged), "Engine oracle staged SGF cleanup evidence is invalid")
    return envelope


def host_identity() -> dict[str, Any]:
    process_architecture = normalized_architecture(os.environ.get("LIZZIE_MACOS_ACCEPTANCE_FIXTURE_HOST_ARCH", "") if FIXTURE_MODE else platform.machine())
    if FIXTURE_MODE:
        if os.environ.get("LIZZIE_MACOS_ACCEPTANCE_FIXTURE_HOST_IDENTITY_FAIL") == "1":
            raise OSError("fixture host identity probe unavailable")
        physical_architecture = normalized_architecture(os.environ.get("LIZZIE_MACOS_ACCEPTANCE_FIXTURE_PHYSICAL_ARCH", process_architecture))
        translated = os.environ.get("LIZZIE_MACOS_ACCEPTANCE_FIXTURE_TRANSLATED", "0") == "1"
    elif platform.system() == "Darwin":
        arm64 = subprocess.run(["sysctl", "-in", "hw.optional.arm64"], capture_output=True, text=True, timeout=10)
        physical_architecture = "arm64" if arm64.returncode == 0 and arm64.stdout.strip() == "1" else "x86_64"
        translation = subprocess.run(["sysctl", "-in", "sysctl.proc_translated"], capture_output=True, text=True, timeout=10)
        translated = translation.returncode == 0 and translation.stdout.strip() == "1"
    else:
        physical_architecture = process_architecture
        translated = False
    return {"os": platform.system(), "version": platform.mac_ver()[0], "architecture": process_architecture, "physicalArchitecture": physical_architecture, "translated": translated, "fixtureMode": FIXTURE_MODE}


def observed_model() -> dict[str, Any]:
    return {
        "candidate": {"path": None, "sha256": None, "artifactSha256": None, "provenancePath": None, "provenanceSha256": None, "targetSha": None, "releaseTag": None},
        "host": {"os": platform.system(), "version": platform.mac_ver()[0], "architecture": normalized_architecture(platform.machine()), "physicalArchitecture": None, "translated": None, "fixtureMode": FIXTURE_MODE},
        "dmg": {"path": None, "layoutAudit": None, "dylibClosure": None, "mountPath": None, "device": None, "volumeName": None, "ejected": None},
        "installed": {"appPath": None, "bundleIdentifier": None, "launcher": None, "runtime": None, "launcherConfig": None, "jar": None, "jcef": None, "engine": None, "model": None},
        "quarantine": {"attribute": None, "signatureStatus": None, "codesign": None, "stapler": None, "spctl": None, "firstLaunch": None, "openAnyway": None},
        "launcher": {"pid": None, "command": None, "processArchitecture": None, "processes": None},
        "runtime": {"version": None, "architecture": None, "processImage": None},
        "dataRoot": {"path": None, "selection": None, "config": None, "persist": None},
        "network": {"boundary": None, "loopbackAvailable": None, "blockedAttemptCount": None, "counterEvidence": None},
        "readiness": {"state": None, "applicationLog": None, "screenshot": None, "runRecord": None},
        "analysis": {"status": None, "engineOracle": None},
        "cleanup": {"remainingPids": None, "helperPids": None, "mountGone": None, "readerCleanup": None, "stagedFilesCleanup": None, "errors": None},
    }


def required_outcomes() -> dict[str, Any]:
    observations = [
        ("observed.candidate.sha256", "identity"),
        ("observed.candidate.artifactSha256", "identity"),
        ("observed.candidate.provenanceSha256", "identity"),
        ("observed.dmg.layoutAudit", "content"),
        ("observed.dmg.dylibClosure", "content"),
        ("observed.installed.appPath", "install"),
        ("observed.dmg.ejected", "install"),
        ("observed.quarantine.signatureStatus", "gatekeeper"),
        ("observed.quarantine.firstLaunch", "gatekeeper"),
        ("observed.launcher.pid", "launch"),
        ("observed.launcher.processes", "launch"),
        ("observed.runtime.architecture", "install"),
        ("observed.dataRoot.path", "launch"),
        ("observed.readiness.state", "launch"),
        ("observed.analysis.status", "verify"),
        ("observed.network.blockedAttemptCount", "cleanup"),
        ("observed.cleanup.remainingPids", "cleanup"),
        ("observed.cleanup.mountGone", "cleanup"),
        ("observed.cleanup.errors", "cleanup"),
    ]
    assertion_names = (
        ("identity", "identity"),
        ("content", "content"),
        ("installed", "install"),
        ("gatekeeper", "gatekeeper"),
        ("launched", "launch"),
        ("verified", "verify"),
        ("cleaned", "cleanup"),
    )
    evidence_names = tuple((phase, phase) for phase in PHASES)
    return {
        "phases": list(PHASES),
        "observations": [{"path": path, "phase": phase} for path, phase in observations],
        "assertions": [{"path": f"assertions.{name}", "phase": phase} for name, phase in assertion_names],
        "evidence": [{"path": f"evidence.{name}", "phase": phase} for name, phase in evidence_names],
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
    if re.search(r"(?i)(missing|mismatch|differs|expected|must|unexpected|does not|requires|architecture|unsafe|ambiguous|escapes)", message):
        return "assertion"
    return "error"


def run(candidate_path: Path, scenario: str, evidence: Path) -> int:
    started_at = now()
    require(not evidence.exists(), f"Evidence directory must be new: {evidence}")
    evidence.mkdir(parents=True)
    record_path = evidence / "acceptance.json"
    observed = observed_model()
    assertions: dict[str, bool | None] = {"identity": None, "content": None, "installed": None, "gatekeeper": None, "launched": None, "verified": None, "cleaned": None}
    evidence_values: dict[str, str | None] = {phase_name: None for phase_name in PHASES}
    cleanup: dict[str, Any] = {"complete": False, "remainingOwnedResources": []}
    phase = "identity"
    status = "FAIL"
    failure: dict[str, Any] | None = None
    reason: str | None = None
    blocked_phase: str | None = None
    candidate: dict[str, Any] | None = None
    product: dict[str, str] | None = None
    mount_path: Path | None = None
    mount_device = ""
    supervisor: subprocess.Popen[bytes] | None = None
    captured_processes: list[dict[str, Any]] = []
    ownership_tokens: list[str] = []
    stdout_stream: Any = None
    stderr_stream: Any = None
    network_monitor: subprocess.Popen[bytes] | FixtureNetworkMonitor | None = None
    network_stream: Any = None
    network_log = evidence / "network-deny-events.ndjson"
    final_counter = evidence / "network-counter.txt"
    try:
        if not candidate_path.is_file():
            raise BlockedError("identity", f"The requested candidate.json is unavailable: {candidate_path}")
        candidate, product = canonical_candidate(candidate_path.resolve())
        observed["candidate"].update(path=str(candidate_path.resolve()), sha256=sha256_file(candidate_path), artifactSha256=candidate["artifact"]["sha256"], provenancePath=candidate["provenance"]["path"], provenanceSha256=candidate["provenance"]["sha256"], targetSha=candidate["targetSha"], releaseTag=candidate["releaseTag"])
        observed["dmg"]["path"] = candidate["artifact"]["sourcePath"]
        assertions["identity"] = True
        evidence_values["identity"] = str(candidate_path.resolve())

        if not FIXTURE_MODE and platform.system() == "Darwin" and not shutil.which("sysctl"):
            raise BlockedError("content", "Required macOS acceptance command is unavailable: sysctl")
        try:
            observed["host"].update(host_identity())
        except (OSError, subprocess.SubprocessError) as exc:
            raise BlockedError("content", f"macOS physical host identity is unavailable: {exc}") from exc
        host_architecture = observed["host"]["architecture"]
        physical_architecture = observed["host"]["physicalArchitecture"]
        translated = observed["host"]["translated"]
        if translated or host_architecture != product["architecture"] or physical_architecture != product["architecture"]:
            raise BlockedError("content", f"macOS acceptance requires a physical native {product['architecture']} host; process={host_architecture}, physical={physical_architecture}, translated={translated}")
        if not FIXTURE_MODE:
            if platform.system() != "Darwin":
                raise BlockedError("content", f"macOS product acceptance requires a Darwin host, found {platform.system()}")
            for command in ("hdiutil", "diskutil", "lipo", "xattr", "codesign", "xcrun", "spctl", "sandbox-exec", "screencapture", "osascript", "open", "log"):
                if not shutil.which(command):
                    raise BlockedError("content", f"Required macOS acceptance command is unavailable: {command}")
        oracle_text = os.environ.get("LIZZIE_MACOS_ACCEPTANCE_ENGINE_ORACLE", "")
        if not oracle_text:
            raise BlockedError("content", "LIZZIE_MACOS_ACCEPTANCE_ENGINE_ORACLE must name this run's new engine-sgf envelope path")
        oracle_path = Path(oracle_text).resolve()
        require(oracle_path.parent == evidence.resolve(), "Engine oracle output must be placed in this run's new evidence directory")
        require(not oracle_path.exists(), f"Engine oracle output path must be new to prevent replay: {oracle_path}")

        phase = "content"
        layout_audit, dylib_audit = audit_final_dmg(candidate, product, evidence)
        observed["dmg"].update(layoutAudit=layout_audit, dylibClosure=dylib_audit)
        assertions["content"] = True
        evidence_values["content"] = layout_audit

        phase = "install"
        dmg = Path(str(candidate["artifact"]["sourcePath"])).resolve()
        try:
            mount_path, mount_device, volume_name, _ = mount_dmg(dmg, evidence)
        except MountAcquisitionError as exc:
            mount_path, mount_device = exc.mount_path, exc.device
            raise
        observed["dmg"].update(mountPath=str(mount_path.resolve()), device=mount_device, volumeName=volume_name)
        apps = [path for path in mount_path.iterdir() if path.is_dir() and path.suffix == ".app"]
        require(apps == [mount_path / "LizzieYzy Next.app"], f"Mounted DMG app identity is ambiguous: {[path.name for path in apps]}")
        install_parent = evidence / "Applications 等价" / "已安装 应用 程序"
        install_parent.mkdir(parents=True)
        installed_app = install_parent / "LizzieYzy Next.app"
        require(not installed_app.exists(), f"Installed destination must be new: {installed_app}")
        shutil.copytree(apps[0], installed_app, symlinks=True)
        detach_dmg(mount_path, mount_device, evidence)
        mount_path = None
        observed["dmg"]["ejected"] = True
        require("/Volumes/" not in str(installed_app.resolve()), "Installed app destination points into /Volumes")
        layout = resolve_installed_layout(installed_app, product)
        observed["installed"].update(layout)
        runtime_version, runtime_architecture = probe_runtime(Path(layout["runtime"]["path"]), product["architecture"], evidence / "runtime-version.log")
        observed["runtime"].update(version=runtime_version, architecture=runtime_architecture)
        assertions["installed"] = True
        evidence_values["install"] = str((evidence / "eject.log").resolve())

        phase = "gatekeeper"
        quarantine = quarantine_and_assess(installed_app, dmg, evidence)
        observed["quarantine"].update(quarantine)
        assertions["gatekeeper"] = True
        evidence_values["gatekeeper"] = quarantine["spctl"]

        phase = "launch"
        data_root = evidence / "隔离 工作 数据"
        data_root.mkdir()
        ready_file = evidence / "fixture-application-ready.txt"
        sandbox_profile = evidence / "offline.sb"
        sandbox_profile.write_text("(version 1)\n(allow default)\n(deny network*)\n(allow network* (remote ip \"localhost:*\"))\n(allow network* (local ip \"localhost:*\"))\n", encoding="utf-8")
        loopback_available = probe_network_boundary(sandbox_profile, evidence)
        ownership_tokens = [str(installed_app.resolve()), str(data_root.resolve()), str(layout["launcher"]["path"]), str(layout["engine"]["path"]), str(layout["jcef"]["path"])]
        network_monitor, network_stream, network_log = start_network_monitor(evidence)
        environment = os.environ.copy()
        escaped_work_root = str(data_root.resolve()).replace("\\", "\\\\").replace('"', '\\"')
        environment.update({
            "JAVA_TOOL_OPTIONS": f'-Dlizzie.work.dir="{escaped_work_root}"',
            "LIZZIE_MACOS_ACCEPTANCE_APP_ROOT": str(installed_app.resolve()),
            "LIZZIE_MACOS_ACCEPTANCE_EXPECTED_WORK_DIR": str(data_root.resolve()),
            "LIZZIE_MACOS_ACCEPTANCE_FIXTURE_READY": str(ready_file.resolve()),
            "LIZZIE_MACOS_ACCEPTANCE_NETWORK_COUNTER": str(final_counter.resolve()),
        })
        launcher_args = [layout["launcher"]["path"]]
        launcher_command = launcher_args if FIXTURE_MODE else ["sandbox-exec", "-f", str(sandbox_profile.resolve()), *launcher_args]
        stdout_stream = (evidence / "launcher.stdout.log").open("wb")
        stderr_stream = (evidence / "launcher.stderr.log").open("wb")
        supervisor = subprocess.Popen(launcher_command, cwd=installed_app.parent, env=environment, stdout=stdout_stream, stderr=stderr_stream, start_new_session=True)
        timeout_seconds = int(os.environ.get("LIZZIE_MACOS_ACCEPTANCE_TIMEOUT_SECONDS", "120"))
        runtime_pid, process_rows, app_log = wait_for_ready(supervisor, Path(layout["launcher"]["path"]), data_root, ready_file, timeout_seconds)
        process_rows = process_snapshots(descendants(supervisor.pid))
        captured_processes = process_rows
        require(not any("/Volumes/" in str(row["commandLine"]) or "/Volumes/" in str(row["image"]) for row in process_rows), "Owned process path points into /Volumes")
        runtime_rows = [row for row in process_rows if int(row["pid"]) == runtime_pid]
        require(len(runtime_rows) == 1, "Installed launcher/JVM process identity is ambiguous")
        screenshot = evidence / ("application-window.fixture.txt" if FIXTURE_MODE else "application-window.png")
        if FIXTURE_MODE:
            screenshot.write_text("FIXTURE visible application-ready state\n", encoding="utf-8")
        else:
            visible = command_log(["osascript", "-e", f'tell application "System Events" to get visible of first process whose unix id is {runtime_pid}'], evidence / "visible-state.log", timeout=30)
            if visible.returncode != 0:
                raise BlockedError("launch", "Unable to inspect the application window; Accessibility permission is unavailable")
            require("true" in visible.stdout.lower(), "Installed application process is not visibly application-ready")
            shot = command_log(["screencapture", "-x", str(screenshot)], evidence / "screenshot.log", timeout=30)
            if shot.returncode != 0:
                raise BlockedError("launch", "Unable to capture the application window; Screen Recording permission is unavailable")
            require(screenshot.is_file(), "Application screenshot command did not create its evidence file")
            if observed["quarantine"]["signatureStatus"] == "SIGNED_NOTARIZED":
                observed["quarantine"]["firstLaunch"] = "ALLOWED"
        observed["launcher"].update(pid=runtime_pid, command=" ".join(launcher_command), processArchitecture=product["architecture"], processes=process_rows)
        observed["runtime"]["processImage"] = runtime_rows[0]["image"]
        observed["dataRoot"].update(path=str(data_root.resolve()), selection="JAVA_TOOL_OPTIONS injected into installed jpackage launcher JVM", config=str((data_root / "config.txt").resolve()), persist=str((data_root / "persist").resolve()))
        observed["network"].update(boundary="fixture OS deny event counter" if FIXTURE_MODE else "sandbox-exec deny network plus attributable macOS unified-log deny events", loopbackAvailable=loopback_available, counterEvidence=str(final_counter.resolve()))
        run_path = evidence / "run.json"
        engine_identity = layout["engine"]
        run_record = {
            "schemaVersion": 1, "state": "RUNNING", "architecture": product["architecture"],
            "candidate": {"path": observed["candidate"]["path"], "sha256": observed["candidate"]["sha256"], "artifactSha256": observed["candidate"]["artifactSha256"]},
            "launcher": {"path": layout["launcher"]["path"], "sha256": layout["launcher"]["sha256"], "pid": runtime_pid, "command": launcher_command},
            "runtime": {"path": layout["runtime"]["path"], "sha256": layout["runtime"]["sha256"], "version": runtime_version, "processImage": runtime_rows[0]["image"]},
            "jar": {"path": layout["jar"]["path"], "sha256": layout["jar"]["sha256"]},
            "engine": {"path": engine_identity["path"], "sha256": engine_identity["sha256"], "configPath": engine_identity["config"]["path"], "configSha256": engine_identity["config"]["sha256"], "modelPath": layout["model"]["path"], "modelSha256": layout["model"]["sha256"]},
            "jcef": layout["jcef"], "dataRoot": str(data_root.resolve()), "networkBoundary": observed["network"]["boundary"], "processes": process_rows, "startedAt": started_at,
        }
        write_json_atomic(run_path, run_record)
        observed["readiness"].update(state="APPLICATION_READY", applicationLog=str(app_log.resolve()), screenshot=str(screenshot.resolve()), runRecord=str(run_path.resolve()))
        assertions["launched"] = True
        evidence_values["launch"] = str(run_path.resolve())

        phase = "verify"
        bound_oracle = wait_engine_oracle(oracle_path, timeout_seconds)
        oracle = validate_engine_oracle(bound_oracle, run_record, run_path, candidate)
        observed["analysis"].update(status="PASS", engineOracle=str(bound_oracle.resolve()))
        assertions["verified"] = True
        evidence_values["verify"] = str(bound_oracle.resolve())

        phase = "cleanup"
        cleanup_rows = owned_processes(captured_processes, ownership_tokens)
        complete, remaining, errors = terminate_owned(supervisor, captured_processes, ownership_tokens, int(os.environ.get("LIZZIE_MACOS_ACCEPTANCE_STOP_SECONDS", "15")))
        cleanup["remainingOwnedResources"] = [f"process:{pid}" for pid in remaining] + errors
        cleanup["complete"] = not cleanup["remainingOwnedResources"]
        supervisor = None
        stdout_stream.close()
        stderr_stream.close()
        stdout_stream = None
        stderr_stream = None
        if FIXTURE_MODE and network_monitor is None:
            require(final_counter.is_file(), "Fixture network counter evidence is missing")
        else:
            try:
                attempts_from_events = stop_network_monitor(network_monitor, network_stream, network_log, ownership_tokens)
            except BlockedError:
                network_monitor = None
                network_stream = None
                raise
            else:
                network_monitor = None
                network_stream = None
            final_counter.write_text(f"{attempts_from_events}\n", encoding="utf-8")
        try:
            attempts = int(final_counter.read_text(encoding="utf-8").strip())
        except ValueError as exc:
            raise AcceptanceError("Network counter evidence is invalid") from exc
        helper_pids = sorted({int(row["pid"]) for row in [*process_rows, *cleanup_rows] if "jcef Helper" in row["commandLine"]})
        observed["network"]["blockedAttemptCount"] = attempts
        observed["cleanup"].update(remainingPids=remaining, helperPids=helper_pids, mountGone=not Path(observed["dmg"]["mountPath"]).exists(), readerCleanup=oracle["oracle"]["cleanup"]["readers"], stagedFilesCleanup=oracle["oracle"]["cleanup"]["stagedSgf"], errors=errors)
        if attempts:
            cleanup["remainingOwnedResources"].append(f"network-attempts:{attempts}")
        if not observed["cleanup"]["mountGone"]:
            cleanup["remainingOwnedResources"].append(f"mount:{observed['dmg']['mountPath']}")
        cleanup["complete"] = not cleanup["remainingOwnedResources"]
        assertions["cleaned"] = bool(cleanup["complete"])
        cleanup_summary = evidence / "cleanup-summary.json"
        write_json_atomic(cleanup_summary, {"remainingPids": remaining, "helperPids": helper_pids, "mountGone": observed["cleanup"]["mountGone"], "networkBlockedAttemptCount": attempts, "readerCleanup": observed["cleanup"]["readerCleanup"], "stagedFilesCleanup": observed["cleanup"]["stagedFilesCleanup"], "errors": errors})
        evidence_values["cleanup"] = str(cleanup_summary.resolve())
        require(attempts == 0, f"Owned product attempted {attempts} external network connection(s)")
        require(complete, "Owned app/JCEF helper/engine process cleanup did not complete")
        require(observed["cleanup"]["mountGone"], "DMG mount remained after acceptance")
        status = "PASS"
    except BaseException as exc:
        message = str(exc) or exc.__class__.__name__
        if supervisor is not None:
            if not captured_processes:
                captured_processes = process_snapshots(descendants(supervisor.pid))
            _, remaining, cleanup_errors = terminate_owned(supervisor, captured_processes, ownership_tokens, int(os.environ.get("LIZZIE_MACOS_ACCEPTANCE_STOP_SECONDS", "15")))
            cleanup["remainingOwnedResources"].extend([f"process:{pid}" for pid in remaining] + cleanup_errors)
            supervisor = None
        if network_monitor is not None:
            try:
                stop_network_monitor(network_monitor, network_stream, network_log, ownership_tokens)
            except BlockedError as monitor_error:
                (evidence / "network-monitor-cleanup-blocked.txt").write_text(f"{monitor_error}\n", encoding="utf-8")
            except BaseException as monitor_error:
                cleanup["remainingOwnedResources"].append(f"network-monitor:{monitor_error}")
            network_monitor = None
            network_stream = None
        if mount_path is not None and mount_path.exists():
            try:
                detach_dmg(mount_path, mount_device, evidence)
            except BaseException as detach_error:
                cleanup["remainingOwnedResources"].append(f"mount:{mount_path}:{detach_error}")
        cleanup["complete"] = not cleanup["remainingOwnedResources"]
        cleanup_failed = not cleanup["complete"]
        if isinstance(exc, BlockedError) and not cleanup_failed:
            status = "BLOCKED"
            blocked_phase = exc.phase
            reason = message
            blocked_index = PHASES.index(blocked_phase)
            phase = PHASES[0 if blocked_index == 0 else blocked_index - 1]
        else:
            status = "FAIL"
            failure_message = message
            if isinstance(exc, BlockedError):
                resources = ", ".join(cleanup["remainingOwnedResources"])
                failure_message = f"{message}; cleanup incomplete: {resources}"
                if phase == "cleanup":
                    assertions["cleaned"] = False
            else:
                assertion_for_phase = {"identity": "identity", "content": "content", "install": "installed", "gatekeeper": "gatekeeper", "launch": "launched", "verify": "verified", "cleanup": "cleaned"}[phase]
                assertions[assertion_for_phase] = False
            diagnostic = evidence / f"{phase}-failure.txt"
            diagnostic.write_text(failure_message + "\n", encoding="utf-8")
            failure = {"kind": failure_kind(failure_message), "summary": failure_message, "diagnostics": [str(diagnostic.resolve()), str(record_path.resolve())]}
    finally:
        for stream in (stdout_stream, stderr_stream, network_stream):
            if stream is not None and not stream.closed:
                stream.close()

    if candidate is None or product is None:
        try:
            supplied = read_json(candidate_path, "candidate.json")
            artifact = supplied["artifact"]
            product = PRODUCTS[str(artifact["key"])]
            candidate = supplied
        except (AcceptanceError, KeyError, TypeError):
            print("macOS product acceptance cannot write a schema-valid record without candidate identity", file=sys.stderr)
            return 1
    not_observed: dict[str, str] = {}
    add_null_reasons(observed, "observed", not_observed, phase)
    add_null_reasons(assertions, "assertions", not_observed, phase)
    add_null_reasons(evidence_values, "evidence", not_observed, phase)
    record = {
        "schemaVersion": 1, "scenarioId": scenario, "status": status, "phase": phase, "startedAt": started_at, "finishedAt": now(),
        "expected": {"targetSha": candidate["targetSha"], "platform": product["platform"], "architecture": product["architecture"], "artifact": {key: candidate["artifact"][key] for key in ("key", "name", "class")}, "scenario": scenario, "requiredOutcomes": required_outcomes()},
        "observed": observed, "notObserved": not_observed, "assertions": assertions, "evidence": evidence_values, "cleanup": cleanup, "blockedPhase": blocked_phase, "reason": reason, "failure": failure,
    }
    try:
        provenance.validate_acceptance_record(record)
    except provenance.ProvenanceError as exc:
        write_json_atomic(record_path, record)
        (evidence / "record-validation-failure.txt").write_text(f"{exc}\n", encoding="utf-8")
        print(f"macOS acceptance record validation failed: {exc}", file=sys.stderr)
        return 1
    write_json_atomic(record_path, record)
    if status == "PASS":
        print(f"PASS {scenario} {record_path}")
        return 0
    print(f"macOS product acceptance {status}: {reason or failure['summary']} Evidence: {record_path}", file=sys.stderr)
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
        print(f"macOS product acceptance failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

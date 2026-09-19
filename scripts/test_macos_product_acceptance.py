#!/usr/bin/env python3
"""Focused fixtures for installed macOS DMG product acceptance."""

from __future__ import annotations

from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import plistlib
import stat
import subprocess
import tempfile
import threading
import time
from unittest import mock
import unittest
import zipfile

from scripts import release_asset_provenance as provenance
from scripts import macos_product_acceptance as acceptance


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "macos_product_acceptance.sh"
DATE_TAG = "2026-09-19"
TARGET_SHA = "a" * 40


class MacosProductAcceptanceFixtureTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="macos-product-acceptance-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "候选 包"
        self.root.mkdir()

    def executable(self, archive: zipfile.ZipFile, name: str, content: str) -> None:
        info = zipfile.ZipInfo(name)
        info.create_system = 3
        info.external_attr = (stat.S_IFREG | 0o755) << 16
        archive.writestr(info, content)
    def symlink(self, archive: zipfile.ZipFile, name: str, target: str) -> None:
        info = zipfile.ZipInfo(name)
        info.create_system = 3
        info.external_attr = (stat.S_IFLNK | 0o777) << 16
        archive.writestr(info, target)


    def sha256(self, path: Path) -> str:
        digest = hashlib.sha256()
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
        return digest.hexdigest()

    def json_sha256(self, value: object) -> str:
        encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
        return hashlib.sha256(encoded).hexdigest()

    def candidate(
        self,
        platform_name: str = "mac-arm64",
        *,
        populated: bool = False,
        runtime_mode: str = "ready",
        signing: str = "signed",
        binary_architecture: str | None = None,
        escaping_model: bool = False,
    ) -> Path:
        artifact_name = provenance.expected_asset_names(platform_name, DATE_TAG)[0]
        artifact_path = self.root / artifact_name
        architecture = "arm64" if platform_name == "mac-arm64" else "x86_64"
        engine_directory = "macos-arm64" if architecture == "arm64" else "macos-amd64"
        packaged_architecture = binary_architecture or architecture
        jcef_platform = "macosx-arm64" if architecture == "arm64" else "macosx-amd64"
        if populated:
            app = "LizzieYzy Next.app"
            launcher = f"{app}/Contents/MacOS/LizzieYzy Next"
            runtime = f"{app}/Contents/runtime/Contents/Home/bin/java"
            engine = f"{app}/Contents/app/engines/katago/{engine_directory}/katago"
            helper = f"{app}/Contents/app/jcef-bundle/jcef Helper.app/Contents/MacOS/jcef Helper"
            runtime_script = f"""#!/usr/bin/env bash
set -euo pipefail
for arg in "$@"; do
  if [[ "$arg" == "-version" ]]; then
    echo 'openjdk version "21.0.12"' >&2
    echo '    os.arch = {architecture}' >&2
    exit 0
  fi
done
exit 64
"""
            launcher_script = f"""#!/usr/bin/env bash
set -euo pipefail
work_root="$LIZZIE_MACOS_ACCEPTANCE_EXPECTED_WORK_DIR"
if [[ "$JAVA_TOOL_OPTIONS" != *'-Dlizzie.work.dir="'"$work_root"'"'* ]]; then
  echo 'missing bound JAVA_TOOL_OPTIONS work root' >&2
  exit 64
fi
if [[ "{runtime_mode}" == "timeout" ]]; then
  trap 'exit 0' TERM INT
  while :; do sleep 1; done
fi
if [[ "{runtime_mode}" == "early-helper-exit" ]]; then
  setsid bash -c '"$1" >/dev/null 2>&1 &' _ "$LIZZIE_MACOS_ACCEPTANCE_APP_ROOT/Contents/app/jcef-bundle/jcef Helper.app/Contents/MacOS/jcef Helper"
  sleep 0.2
  exit 64
fi
mkdir -p "$work_root/logs"
printf '{{}}\n' >"$work_root/config.txt"
printf 'fixture-persist\n' >"$work_root/persist"
printf 'application ready\n' >"$work_root/logs/app.log"
"$LIZZIE_MACOS_ACCEPTANCE_APP_ROOT/Contents/app/engines/katago/{engine_directory}/katago" gtp -model "$LIZZIE_MACOS_ACCEPTANCE_APP_ROOT/Contents/app/weights/default.bin.gz" -config "$LIZZIE_MACOS_ACCEPTANCE_APP_ROOT/Contents/app/engines/katago/configs/gtp.cfg" &
engine_pid=$!
"$LIZZIE_MACOS_ACCEPTANCE_APP_ROOT/Contents/app/jcef-bundle/jcef Helper.app/Contents/MacOS/jcef Helper" &
helper_pid=$!
if [[ "{runtime_mode}" == "mounted-process" ]]; then
  bash -c 'trap "exit 0" TERM INT; while :; do sleep 1; done' '/Volumes/LizzieYzy Next/LizzieYzy Next.app' &
  mounted_pid=$!
else
  mounted_pid=''
fi
printf '%s\n' "$$" >"$LIZZIE_MACOS_ACCEPTANCE_FIXTURE_READY"
if [[ "{runtime_mode}" == "late-helper" ]]; then
  setsid bash -c 'sleep 0.3; "$1" >/dev/null 2>&1 &' _ "$LIZZIE_MACOS_ACCEPTANCE_APP_ROOT/Contents/app/jcef-bundle/jcef Helper.app/Contents/MacOS/jcef Helper" &
fi
if [[ "{runtime_mode}" == "network" ]]; then
  printf '1\n' >"$LIZZIE_MACOS_ACCEPTANCE_NETWORK_COUNTER"
else
  printf '0\n' >"$LIZZIE_MACOS_ACCEPTANCE_NETWORK_COUNTER"
fi
cleanup() {{
  kill "$engine_pid" "$helper_pid" $mounted_pid 2>/dev/null || true
  wait "$engine_pid" "$helper_pid" $mounted_pid 2>/dev/null || true
}}
trap 'cleanup; exit 0' TERM INT
while :; do sleep 1; done
"""
            info = {
                "CFBundleIdentifier": "com.wimi321.lizzieyzy.next",
                "CFBundlePackageType": "APPL",
                "CFBundleExecutable": "LizzieYzy Next",
                "CFBundleVersion": "2026.9.19",
                "CFBundleShortVersionString": "2026.9.19",
            }
            signing_payload = {
                "state": signing,
                "notarized": signing == "signed",
                "spctlAccepted": signing == "signed",
                "firstLaunch": "ALLOWED" if signing == "signed" else "BLOCKED",
            }
            with zipfile.ZipFile(artifact_path, "w") as archive:
                archive.writestr(f"{app}/Contents/Info.plist", plistlib.dumps(info))
                self.executable(archive, launcher, launcher_script)
                self.executable(archive, runtime, runtime_script)
                self.executable(archive, engine, "#!/usr/bin/env bash\ntrap 'exit 0' TERM INT\nwhile :; do sleep 1; done\n")
                self.executable(archive, helper, "#!/usr/bin/env bash\ntrap 'exit 0' TERM INT\nwhile :; do sleep 1; done\n")
                for binary in (launcher, runtime, engine, helper):
                    archive.writestr(f"{binary}.arch", packaged_architecture)
                archive.writestr(f"{app}/Contents/app/LizzieYzy Next.cfg", "[Application]\napp.mainjar=$APPDIR/lizzie-yzy2.5.3-shaded.jar\njava-options=-Xshare:auto\n")
                archive.writestr(f"{app}/Contents/app/lizzie-yzy2.5.3-shaded.jar", b"fixture jar")
                archive.writestr(f"{app}/Contents/app/jcef-bundle/libjcef.dylib", b"fixture jcef")
                archive.writestr(f"{app}/Contents/app/jcef-bundle/libjcef.dylib.arch", architecture)
                archive.writestr(f"{app}/Contents/app/jcef-bundle/lizzieyzy-next-jcef-manifest.txt", f"platform={jcef_platform}\n")
                archive.writestr(f"{app}/Contents/app/engines/katago/configs/gtp.cfg", "fixture config\n")
                archive.writestr(f"{app}/Contents/app/engines/katago/VERSION.txt", "KataGo release: v1.18.1\n")
                if escaping_model:
                    outside_model = Path(self.temporary.name) / "outside-model.bin.gz"
                    outside_model.write_bytes(b"outside fixture weight")
                    self.symlink(archive, f"{app}/Contents/app/weights/default.bin.gz", str(outside_model))
                else:
                    archive.writestr(f"{app}/Contents/app/weights/default.bin.gz", b"fixture weight")
                archive.writestr(f"{app}/Contents/.fixture-signing.json", json.dumps(signing_payload))
        else:
            artifact_path.write_bytes(b"fixture dmg\n")
        provenance_path = self.root / "release-asset-provenance.json"
        manifest = provenance.build_provenance(
            self.root,
            platform_name,
            DATE_TAG,
            f"next-{DATE_TAG}.1",
            TARGET_SHA,
            123,
            1,
        )
        provenance.write_json_atomic(provenance_path, manifest)
        payload = provenance.verify_candidate(
            provenance_path,
            platform_name,
            DATE_TAG,
            f"next-{DATE_TAG}.1",
            TARGET_SHA,
            123,
            1,
            artifact_name,
            artifact_path,
        )
        candidate_path = self.root / "candidate.json"
        provenance.write_json_atomic(candidate_path, payload)
        return candidate_path

    def produce_oracle(
        self,
        evidence: Path,
        candidate_path: Path,
        errors: list[BaseException],
        valid_peer: bool,
    ) -> None:
        try:
            run_path = evidence / "run.json"
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline and not run_path.is_file():
                time.sleep(0.05)
            self.assertTrue(run_path.is_file(), "runner did not publish run.json")
            run = json.loads(run_path.read_text(encoding="utf-8"))
            candidate = json.loads(candidate_path.read_text(encoding="utf-8"))
            engine_rows = [row for row in run["processes"] if run["engine"]["path"] in row["commandLine"]]
            self.assertEqual(1, len(engine_rows), engine_rows)
            engine_row = engine_rows[0]
            oracle_dir = evidence / "engine-oracle"
            oracle_dir.mkdir()
            for name in ("stdout.log", "stderr.log", "app.log", "phases.log"):
                (oracle_dir / name).write_text(f"fixture {name}\n", encoding="utf-8")
            missing_staged = oracle_dir / "removed staged snapshot.sgf"
            oracle = {
                "schemaVersion": 1,
                "scenario": "real-cpu-engine",
                "status": "PASS",
                "failure": None,
                "peer": {"kind": "real-katago-cpu", "catalogEngineId": "fixture-cpu", "command": engine_row["commandLine"], "pid": engine_row["pid"], "stdoutReader": "terminated", "stderrReader": "terminated"},
                "source": {"commit": candidate["targetSha"], "dirty": False},
                "platform": {"os": "macOS fixture", "arch": run["architecture"], "jvmVersion": "21", "jvmVendor": "fixture"},
                "manifest": {"path": "fixture", "sha256": "b" * 64, "preparedAt": "2026-09-19T00:00:00Z", "networkUsed": False},
                "assets": {
                    "catalogPath": "fixture", "catalogSha256": "c" * 64,
                    "archiveFileName": candidate["artifact"]["name"], "archivePath": candidate["artifact"]["sourcePath"],
                    "archiveSizeBytes": candidate["artifact"]["sizeBytes"], "archiveSha256": candidate["artifact"]["sha256"],
                    "katagoVersion": "fixture", "katagoReleaseTag": "v1.18.1", "katagoSourceCommit": "fixture", "katagoBackend": "Eigen",
                    "enginePath": run["engine"]["path"], "engineSizeBytes": Path(run["engine"]["path"]).stat().st_size, "engineSha256": run["engine"]["sha256"], "engineVersionOutput": "fixture",
                    "modelId": "fixture", "modelPath": run["engine"]["modelPath"], "modelSizeBytes": Path(run["engine"]["modelPath"]).stat().st_size, "modelSha256": run["engine"]["modelSha256"],
                    "configSource": "gtp.cfg", "configPath": run["engine"]["configPath"], "configSizeBytes": Path(run["engine"]["configPath"]).stat().st_size, "configSha256": run["engine"]["configSha256"],
                },
                "fixture": {"path": "fixture.sgf", "sha256": "ec41cf044ee29b2408b488727db2b6bbe5a2a3acfe5ed74d70fbb6dac2be3a9c"},
                "node": {"semanticPath": "0/0/0/0/0", "kind": "PASS", "identity": "fixture-node"},
                "rules": {"targetRaw": "Chinese", "targetSummary": "CHINESE", "targetRevision": 1, "actual": {"friendlyPassOk": True, "scoring": "AREA", "ko": "SIMPLE", "whiteHandicapBonus": "N", "suicide": False, "tax": "NONE", "hasButton": False}, "status": "CONFIRMED", "fresh": True},
                "position": {"confirmed": True, "board": "19x19", "komi": 6.5, "stones": "B:fd;W:ee,ff,hh", "empty": "dd", "turn": "W", "setupKind": "SNAPSHOT", "setupStones": "B:fd;W:ee,ff", "tailPrevious": "MOVE:W[hh]", "tailCurrent": "PASS:B[]"},
                "analysis": {"schema": "katago-info-v1", "move": "Q16", "visits": 12, "winrate": 0.5, "scoreLead": 0.0, "pv": ["Q16"]},
                "phasesMs": {"startup": 1, "rulesPosition": 1, "analysis": 1, "stop": 1, "quit": 1, "cleanup": 1, "total": 6},
                "stop": {"requested": True, "quietWindowMs": 400, "quiet": True, "peerOutputCount": 2, "applicationVisits": 12},
                "quit": {"normal": True},
                "cleanup": {"forced": False, "durationMs": 1, "process": True, "readers": True, "stagedSgf": True},
                "evidence": {"result": str(oracle_dir / "result.json"), "stdout": str(oracle_dir / "stdout.log"), "stderr": str(oracle_dir / "stderr.log"), "appLog": str(oracle_dir / "app.log"), "phases": str(oracle_dir / "phases.log"), "stagedSgfs": [str(missing_staged)]},
            }
            if not valid_peer:
                oracle["peer"]["catalogEngineId"] = ""
            provenance.write_json_atomic(oracle_dir / "result.json", oracle)
            os.kill(int(engine_row["pid"]), 15)
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                state = subprocess.run(
                    ["ps", "-o", "state=", "-p", str(engine_row["pid"])],
                    capture_output=True,
                    text=True,
                    check=False,
                ).stdout.strip()
                if not state or "Z" in state:
                    break
                time.sleep(0.05)
            self.assertTrue(not state or "Z" in state, "fixture engine did not stop")
            binding = {
                "candidateSha256": run["candidate"]["sha256"], "artifactSha256": run["candidate"]["artifactSha256"],
                "runJsonSha256": self.sha256(run_path), "launcherSha256": run["launcher"]["sha256"],
                "runtimeSha256": run["runtime"]["sha256"], "jarSha256": run["jar"]["sha256"],
                "dataRoot": run["dataRoot"], "launcherPid": run["launcher"]["pid"],
                "enginePid": engine_row["pid"], "engineCommand": engine_row["commandLine"], "oracleSha256": self.json_sha256(oracle),
            }
            provenance.write_json_atomic(evidence / "engine-oracle.json", {"schemaVersion": 1, "scenario": "macos-installed-product-real-cpu", "status": "PASS", "binding": binding, "oracle": oracle})
        except BaseException as exc:
            errors.append(exc)

    def produce_open_anyway(self, evidence: Path, marker: Path, errors: list[BaseException]) -> None:
        try:
            request_path = evidence / "open-anyway-request.json"
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline and not request_path.is_file():
                time.sleep(0.05)
            self.assertTrue(request_path.is_file(), "runner did not publish Open Anyway request")
            request = json.loads(request_path.read_text(encoding="utf-8"))
            screenshot = self.root / "Open Anyway confirmation.png"
            screenshot.write_bytes(b"fixture screenshot")
            time.sleep(0.02)
            confirmation = request | {
                "confirmedAt": datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
                "screenshot": str(screenshot),
                "launchObserved": True,
            }
            provenance.write_json_atomic(marker, confirmation)
        except BaseException as exc:
            errors.append(exc)

    def run_acceptance(
        self,
        candidate: Path,
        *,
        host_architecture: str,
        physical_architecture: str | None = None,
        translated: bool = False,
        produce_oracle: bool = False,
        timeout: int = 10,
        open_anyway: bool = False,
        valid_peer: bool = True,
        extra_environment: dict[str, str] | None = None,
        evidence_name: str = "验收 evidence",
    ) -> tuple[subprocess.CompletedProcess[str], Path, dict[str, object]]:
        evidence = self.root / evidence_name
        environment = os.environ.copy()
        environment.update(
            {
                "LIZZIE_MACOS_ACCEPTANCE_FIXTURE_MODE": "1",
                "LIZZIE_MACOS_ACCEPTANCE_FIXTURE_HOST_ARCH": host_architecture,
                "LIZZIE_MACOS_ACCEPTANCE_FIXTURE_PHYSICAL_ARCH": physical_architecture or host_architecture,
                "LIZZIE_MACOS_ACCEPTANCE_FIXTURE_TRANSLATED": "1" if translated else "0",
                "LIZZIE_MACOS_ACCEPTANCE_TIMEOUT_SECONDS": str(timeout),
                "LIZZIE_MACOS_ACCEPTANCE_STOP_SECONDS": "2",
                "LIZZIE_MACOS_ACCEPTANCE_ENGINE_ORACLE": str(evidence / "engine-oracle.json"),
            }
        )
        producer_errors: list[BaseException] = []
        open_anyway_thread = None
        if open_anyway:
            marker = self.root / "Open Anyway confirmation.json"
            environment["LIZZIE_MACOS_OPEN_ANYWAY_MARKER"] = str(marker)
            open_anyway_thread = threading.Thread(target=self.produce_open_anyway, args=(evidence, marker, producer_errors), daemon=True)
            open_anyway_thread.start()
        if extra_environment:
            environment.update(extra_environment)
        producer = None
        if produce_oracle:
            producer = threading.Thread(target=self.produce_oracle, args=(evidence, candidate, producer_errors, valid_peer), daemon=True)
            producer.start()
        result = subprocess.run(
            [str(SCRIPT), "--candidate", str(candidate), "--scenario", "installed-offline-first-run", "--evidence-dir", str(evidence)],
            cwd=ROOT,
            env=environment,
            capture_output=True,
            text=True,
            timeout=30,
        )
        for worker in (producer, open_anyway_thread):
            if worker is not None:
                worker.join(timeout=15)
                self.assertFalse(worker.is_alive(), "acceptance evidence producer did not finish")
        if producer_errors:
            raise producer_errors[0]
        record_path = evidence / "acceptance.json"
        record = json.loads(record_path.read_text(encoding="utf-8")) if record_path.exists() else {}
        return result, evidence, record

    def test_wrong_native_host_architecture_is_blocked_before_mount(self) -> None:
        candidate = self.candidate("mac-arm64")

        result, _, record = self.run_acceptance(candidate, host_architecture="x86_64")

        self.assertNotEqual(0, result.returncode)
        provenance.validate_acceptance_record(record)
        self.assertEqual("BLOCKED", record["status"])
        self.assertEqual("identity", record["phase"])
        self.assertEqual("content", record["blockedPhase"])
        self.assertIn("physical native", record["reason"])
        self.assertIsNone(record["observed"]["dmg"]["mountPath"])
        self.assertTrue(record["cleanup"]["complete"])

    def test_signed_installed_copy_passes_complete_bound_oracle(self) -> None:
        candidate = self.candidate("mac-arm64", populated=True, signing="signed")

        result, evidence, record = self.run_acceptance(
            candidate,
            host_architecture="arm64",
            produce_oracle=True,
        )

        self.assertEqual(0, result.returncode, result.stderr)
        provenance.validate_acceptance_record(record)
        self.assertEqual("PASS", record["status"])
        observed = record["observed"]
        self.assertTrue(observed["host"]["fixtureMode"])
        self.assertIn("应用 程序", observed["installed"]["appPath"])
        self.assertNotIn("/Volumes/", observed["launcher"]["command"])
        self.assertTrue(observed["dmg"]["ejected"])
        self.assertEqual("SIGNED_NOTARIZED", observed["quarantine"]["signatureStatus"])
        self.assertEqual("PASS", observed["analysis"]["status"])
        self.assertEqual([], record["cleanup"]["remainingOwnedResources"])
        self.assertTrue((evidence / "layout-audit.log").is_file())
        self.assertTrue((evidence / "cleanup-summary.json").is_file())

    def test_unsigned_candidate_requires_and_records_open_anyway(self) -> None:
        candidate = self.candidate("mac-amd64", populated=True, signing="unsigned")

        result, _, record = self.run_acceptance(
            candidate,
            host_architecture="x86_64",
            produce_oracle=True,
            open_anyway=True,
        )

        self.assertEqual(0, result.returncode, result.stderr)
        provenance.validate_acceptance_record(record)
        self.assertEqual("UNSIGNED_INTENTIONAL", record["observed"]["quarantine"]["signatureStatus"])
        self.assertEqual("BLOCKED_THEN_OPEN_ANYWAY", record["observed"]["quarantine"]["firstLaunch"])
        self.assertEqual("BOUND_CONFIRMATION", record["observed"]["quarantine"]["openAnyway"])
        request = json.loads((self.root / "验收 evidence" / "open-anyway-request.json").read_text(encoding="utf-8"))
        confirmation = json.loads((self.root / "Open Anyway confirmation.json").read_text(encoding="utf-8"))
        self.assertEqual(request["nonce"], confirmation["nonce"])
        self.assertEqual(request["launcherSha256"], confirmation["launcherSha256"])

    def test_tampered_candidate_identity_fails_before_mount(self) -> None:
        candidate = self.candidate("mac-arm64", populated=True)
        payload = json.loads(candidate.read_text(encoding="utf-8"))
        payload["artifact"]["sha256"] = "0" * 64
        provenance.write_json_atomic(candidate, payload)

        result, _, record = self.run_acceptance(candidate, host_architecture="arm64")

        self.assertNotEqual(0, result.returncode)
        provenance.validate_acceptance_record(record)
        self.assertEqual("identity", record["phase"])
        self.assertIn("canonical verified candidate", record["failure"]["summary"])
        self.assertIsNone(record["observed"]["dmg"]["mountPath"])

    def test_partial_signing_credentials_fail_closed(self) -> None:
        candidate = self.candidate("mac-arm64", populated=True)

        result, _, record = self.run_acceptance(
            candidate,
            host_architecture="arm64",
            extra_environment={"APPLE_ID": "fixture@example.invalid"},
        )

        self.assertNotEqual(0, result.returncode)
        provenance.validate_acceptance_record(record)
        self.assertEqual("gatekeeper", record["phase"])
        self.assertIn("Partial Apple signing credentials", record["failure"]["summary"])
        self.assertTrue(record["cleanup"]["complete"])

    def test_mounted_process_path_fails_and_cleans_owned_tree(self) -> None:
        candidate = self.candidate("mac-arm64", populated=True, runtime_mode="mounted-process")

        result, _, record = self.run_acceptance(candidate, host_architecture="arm64")

        self.assertNotEqual(0, result.returncode)
        provenance.validate_acceptance_record(record)
        self.assertEqual("launch", record["phase"])
        self.assertIn("/Volumes", record["failure"]["summary"])
        self.assertEqual([], record["cleanup"]["remainingOwnedResources"])

    def test_readiness_timeout_cleans_owned_tree(self) -> None:
        candidate = self.candidate("mac-arm64", populated=True, runtime_mode="timeout")

        result, _, record = self.run_acceptance(candidate, host_architecture="arm64", timeout=1)

        self.assertNotEqual(0, result.returncode)
        provenance.validate_acceptance_record(record)
        self.assertEqual("timeout", record["failure"]["kind"])
        self.assertEqual([], record["cleanup"]["remainingOwnedResources"])

    def test_eject_failure_retries_cleanup_and_records_failure(self) -> None:
        candidate = self.candidate("mac-arm64", populated=True)

        result, _, record = self.run_acceptance(
            candidate,
            host_architecture="arm64",
            extra_environment={"LIZZIE_MACOS_ACCEPTANCE_FIXTURE_EJECT_FAIL": "1"},
        )

        self.assertNotEqual(0, result.returncode)
        provenance.validate_acceptance_record(record)
        self.assertEqual("install", record["phase"])
        self.assertIn("eject failed", record["failure"]["summary"])
        self.assertTrue(record["cleanup"]["complete"])

    def test_missing_open_anyway_confirmation_is_blocked(self) -> None:
        candidate = self.candidate("mac-arm64", populated=True, signing="unsigned")

        result, _, record = self.run_acceptance(candidate, host_architecture="arm64")

        self.assertNotEqual(0, result.returncode)
        provenance.validate_acceptance_record(record)
        self.assertEqual("BLOCKED", record["status"])
        self.assertEqual("gatekeeper", record["blockedPhase"])
        self.assertIn("Open Anyway", record["reason"])
        self.assertTrue(record["cleanup"]["complete"])

    def test_tampered_provenance_fails_before_mount(self) -> None:
        candidate = self.candidate("mac-arm64", populated=True)
        payload = json.loads(candidate.read_text(encoding="utf-8"))
        manifest_path = Path(payload["provenance"]["path"])
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["targetSha"] = "b" * 40
        provenance.write_json_atomic(manifest_path, manifest)

        result, _, record = self.run_acceptance(candidate, host_architecture="arm64")

        self.assertNotEqual(0, result.returncode)
        provenance.validate_acceptance_record(record)
        self.assertEqual("identity", record["phase"])
        self.assertIn("Candidate verification failed", record["failure"]["summary"])
        self.assertIsNone(record["observed"]["dmg"]["mountPath"])

    def test_oracle_requires_production_ownership_identity(self) -> None:
        candidate = self.candidate("mac-arm64", populated=True)

        result, _, record = self.run_acceptance(
            candidate,
            host_architecture="arm64",
            produce_oracle=True,
            valid_peer=False,
        )

        self.assertNotEqual(0, result.returncode)
        provenance.validate_acceptance_record(record)
        self.assertEqual("verify", record["phase"])
        self.assertIn("production catalog/manager ownership", record["failure"]["summary"])
        self.assertTrue(record["cleanup"]["complete"])

    def test_rosetta_process_cannot_accept_intel_candidate(self) -> None:
        candidate = self.candidate("mac-amd64")

        result, _, record = self.run_acceptance(
            candidate,
            host_architecture="x86_64",
            physical_architecture="arm64",
            translated=True,
        )

        self.assertNotEqual(0, result.returncode)
        provenance.validate_acceptance_record(record)
        self.assertEqual("BLOCKED", record["status"])
        self.assertTrue(record["observed"]["host"]["translated"])
        self.assertIn("physical=arm64", record["reason"])

    def test_packaged_binary_wrong_architecture_fails(self) -> None:
        candidate = self.candidate("mac-arm64", populated=True, binary_architecture="x86_64")

        result, _, record = self.run_acceptance(candidate, host_architecture="arm64")

        self.assertNotEqual(0, result.returncode)
        provenance.validate_acceptance_record(record)
        self.assertEqual("FAIL", record["status"])
        self.assertEqual("install", record["phase"])
        self.assertIn("do not exactly match", record["failure"]["summary"])

    def test_post_attach_failure_cleans_mount_before_returning(self) -> None:
        candidate = self.candidate("mac-arm64", populated=True)

        result, evidence, record = self.run_acceptance(
            candidate,
            host_architecture="arm64",
            extra_environment={"LIZZIE_MACOS_ACCEPTANCE_FIXTURE_POST_ATTACH_FAIL": "1"},
        )

        self.assertNotEqual(0, result.returncode)
        provenance.validate_acceptance_record(record)
        self.assertEqual("install", record["phase"])
        self.assertFalse((evidence / "fixture Volumes").exists())
        self.assertTrue(record["cleanup"]["complete"])

    def test_installed_component_symlink_cannot_escape_app(self) -> None:
        candidate = self.candidate("mac-arm64", populated=True, escaping_model=True)

        result, _, record = self.run_acceptance(candidate, host_architecture="arm64")

        self.assertNotEqual(0, result.returncode)
        provenance.validate_acceptance_record(record)
        self.assertEqual("install", record["phase"])
        self.assertIn("escapes installed app", record["failure"]["summary"])

    def test_malformed_signature_does_not_enter_unsigned_flow(self) -> None:
        candidate = self.candidate("mac-arm64", populated=True, signing="broken")

        result, _, record = self.run_acceptance(candidate, host_architecture="arm64")

        self.assertNotEqual(0, result.returncode)
        provenance.validate_acceptance_record(record)
        self.assertEqual("gatekeeper", record["phase"])
        self.assertIn("malformed or partial signature", record["failure"]["summary"])

    def test_post_attach_evidence_write_failure_detaches_known_device(self) -> None:
        evidence = self.root / "mount evidence"
        evidence.mkdir()
        mount_path = evidence / "mounted DMG"
        attach = subprocess.CompletedProcess(
            ["hdiutil"],
            0,
            plistlib.dumps({"system-entities": [{"mount-point": str(mount_path), "dev-entry": "/dev/disk9"}]}),
            b"",
        )
        previous_fixture_mode = acceptance.FIXTURE_MODE
        acceptance.FIXTURE_MODE = False
        try:
            with (
                mock.patch.object(acceptance.subprocess, "run", return_value=attach),
                mock.patch.object(Path, "write_bytes", side_effect=OSError("fixture evidence write failure")),
                mock.patch.object(acceptance, "detach_dmg") as detach,
            ):
                with self.assertRaisesRegex(acceptance.AcceptanceError, "evidence write failure"):
                    acceptance.mount_dmg(self.root / "candidate.dmg", evidence)
                detach.assert_called_once_with(mount_path, "/dev/disk9", evidence)
        finally:
            acceptance.FIXTURE_MODE = previous_fixture_mode

    def test_attach_timeout_detaches_requested_mountpoint(self) -> None:
        evidence = self.root / "attach timeout evidence"
        evidence.mkdir()
        mount_path = evidence / "mounted DMG"
        previous_fixture_mode = acceptance.FIXTURE_MODE
        acceptance.FIXTURE_MODE = False
        try:
            with (
                mock.patch.object(acceptance.subprocess, "run", side_effect=subprocess.TimeoutExpired(["hdiutil"], 120)),
                mock.patch.object(Path, "is_mount", return_value=True),
                mock.patch.object(acceptance, "detach_dmg") as detach,
            ):
                with self.assertRaisesRegex(acceptance.AcceptanceError, "attach or post-attach"):
                    acceptance.mount_dmg(self.root / "candidate.dmg", evidence)
                detach.assert_called_once_with(mount_path, "", evidence)
        finally:
            acceptance.FIXTURE_MODE = previous_fixture_mode

    def test_native_codesign_diagnostic_rejects_partial_nested_signature(self) -> None:
        outer_unsigned = subprocess.CompletedProcess(
            ["codesign"], 1, "", "/Applications/LizzieYzy Next.app: code object is not signed at all\n"
        )
        partial_nested = subprocess.CompletedProcess(
            ["codesign"], 1, "", "/Applications/LizzieYzy Next.app: code object is not signed at all\nIn subcomponent: /Applications/LizzieYzy Next.app/Contents/app/lib.dylib\n"
        )
        signed_outer = subprocess.CompletedProcess(["codesign"], 0, "", "Executable=/Applications/LizzieYzy Next.app\n")

        self.assertTrue(acceptance.codesign_reports_fully_unsigned(outer_unsigned))
        self.assertFalse(acceptance.codesign_reports_fully_unsigned(partial_nested))
        self.assertFalse(acceptance.codesign_reports_fully_unsigned(signed_outer))

    def test_pre_readiness_reparented_helper_is_cleaned_by_installed_path(self) -> None:
        candidate = self.candidate("mac-arm64", populated=True, runtime_mode="early-helper-exit")

        result, evidence, record = self.run_acceptance(candidate, host_architecture="arm64")

        self.assertNotEqual(0, result.returncode)
        provenance.validate_acceptance_record(record)
        self.assertEqual("launch", record["phase"])
        self.assertEqual([], record["cleanup"]["remainingOwnedResources"])
        self.assertEqual([], acceptance.owned_processes([], [str(evidence / "Applications 等价")]))

    def test_network_attempt_is_attributed_and_prevents_clean_pass(self) -> None:
        candidate = self.candidate("mac-arm64", populated=True, runtime_mode="network")

        result, _, record = self.run_acceptance(candidate, host_architecture="arm64", produce_oracle=True)

        self.assertNotEqual(0, result.returncode)
        provenance.validate_acceptance_record(record)
        self.assertEqual("cleanup", record["phase"])
        self.assertEqual(1, record["observed"]["network"]["blockedAttemptCount"])
        self.assertIn("network-attempts:1", record["cleanup"]["remainingOwnedResources"])
        self.assertFalse(record["cleanup"]["complete"])

    def test_late_session_helper_is_discovered_and_cleaned(self) -> None:
        candidate = self.candidate("mac-arm64", populated=True, runtime_mode="late-helper")

        result, _, record = self.run_acceptance(candidate, host_architecture="arm64", produce_oracle=True)

        self.assertEqual(0, result.returncode, result.stderr)
        provenance.validate_acceptance_record(record)
        self.assertGreaterEqual(len(record["observed"]["cleanup"]["helperPids"]), 1)
        self.assertEqual([], record["cleanup"]["remainingOwnedResources"])

    def test_process_parser_preserves_space_containing_image_and_arguments(self) -> None:
        metadata = " 42 1 S Mon Jan  2 03:04:05 2026 /Applications/LizzieYzy Next.app/Contents/MacOS/LizzieYzy Next\n"
        arguments = " 42 /Applications/LizzieYzy Next.app/Contents/MacOS/LizzieYzy Next --fixture value\n"

        rows = acceptance.parse_process_tables(metadata, arguments)

        self.assertEqual("/Applications/LizzieYzy Next.app/Contents/MacOS/LizzieYzy Next", rows[42]["image"])
        self.assertEqual("/Applications/LizzieYzy Next.app/Contents/MacOS/LizzieYzy Next --fixture value", rows[42]["commandLine"])
        self.assertEqual("Mon Jan 2 03:04:05 2026", rows[42]["incarnation"])

    def test_missing_oracle_producer_is_blocked_after_launch(self) -> None:
        candidate = self.candidate("mac-arm64", populated=True)

        result, _, record = self.run_acceptance(candidate, host_architecture="arm64", timeout=1)

        self.assertNotEqual(0, result.returncode)
        provenance.validate_acceptance_record(record)
        self.assertEqual("BLOCKED", record["status"])
        self.assertEqual("verify", record["blockedPhase"])
        self.assertEqual("launch", record["phase"])
        self.assertTrue(record["cleanup"]["complete"])

    def test_unavailable_host_identity_probe_writes_blocked_record(self) -> None:
        candidate = self.candidate("mac-arm64")

        result, _, record = self.run_acceptance(
            candidate,
            host_architecture="arm64",
            extra_environment={"LIZZIE_MACOS_ACCEPTANCE_FIXTURE_HOST_IDENTITY_FAIL": "1"},
        )

        self.assertNotEqual(0, result.returncode)
        provenance.validate_acceptance_record(record)
        self.assertEqual("BLOCKED", record["status"])
        self.assertEqual("content", record["blockedPhase"])
        self.assertEqual("identity", record["phase"])
        self.assertIn("host identity is unavailable", record["reason"])

    def test_run_level_network_monitor_evidence_failures_are_schema_valid_blocked(self) -> None:
        candidate = self.candidate("mac-arm64", populated=True)

        for mode in ("dead", "unreadable", "malformed", "query-failure"):
            with self.subTest(mode=mode):
                result, evidence, record = self.run_acceptance(
                    candidate,
                    host_architecture="arm64",
                    produce_oracle=True,
                    evidence_name=f"monitor-{mode}-evidence",
                    extra_environment={"LIZZIE_MACOS_ACCEPTANCE_FIXTURE_NETWORK_MONITOR_MODE": mode},
                )

                self.assertNotEqual(0, result.returncode)
                provenance.validate_acceptance_record(record)
                self.assertEqual("BLOCKED", record["status"])
                self.assertEqual("cleanup", record["blockedPhase"])
                self.assertEqual("verify", record["phase"])
                self.assertTrue(record["cleanup"]["complete"])
                self.assertEqual([], record["cleanup"]["remainingOwnedResources"])
                self.assertIsNone(record["observed"]["network"]["blockedAttemptCount"])
                monitor_state = json.loads((evidence / "network-monitor-fixture-state.json").read_text(encoding="utf-8"))
                self.assertTrue(monitor_state["stopped"])

    def test_blocker_plus_live_network_monitor_is_schema_valid_fail(self) -> None:
        candidate = self.candidate("mac-arm64", populated=True)

        result, evidence, record = self.run_acceptance(
            candidate,
            host_architecture="arm64",
            timeout=1,
            evidence_name="monitor-live-leak-evidence",
            extra_environment={"LIZZIE_MACOS_ACCEPTANCE_FIXTURE_NETWORK_MONITOR_MODE": "live-leak"},
        )

        self.assertNotEqual(0, result.returncode)
        provenance.validate_acceptance_record(record)
        self.assertEqual("FAIL", record["status"])
        self.assertIsNone(record["blockedPhase"])
        self.assertIsNone(record["reason"])
        self.assertFalse(record["cleanup"]["complete"])
        self.assertTrue(any(item.startswith("network-monitor:") for item in record["cleanup"]["remainingOwnedResources"]))
        self.assertIn("Timed out waiting for the bound engine oracle", record["failure"]["summary"])
        monitor_state = json.loads((evidence / "network-monitor-fixture-state.json").read_text(encoding="utf-8"))
        self.assertFalse(monitor_state["stopped"])

    def test_process_cleanup_error_survives_monitor_evidence_failure(self) -> None:
        candidate = self.candidate("mac-arm64", populated=True)

        result, _, record = self.run_acceptance(
            candidate,
            host_architecture="arm64",
            produce_oracle=True,
            evidence_name="combined-cleanup-failure-evidence",
            extra_environment={
                "LIZZIE_MACOS_ACCEPTANCE_FIXTURE_NETWORK_MONITOR_MODE": "malformed",
                "LIZZIE_MACOS_ACCEPTANCE_FIXTURE_PROCESS_CLEANUP_ERROR": "1",
            },
        )

        self.assertNotEqual(0, result.returncode)
        provenance.validate_acceptance_record(record)
        self.assertEqual("FAIL", record["status"])
        self.assertEqual("cleanup", record["phase"])
        self.assertFalse(record["cleanup"]["complete"])
        self.assertIn("fixture-owned-process-cleanup-error", record["cleanup"]["remainingOwnedResources"])
        self.assertFalse(record["assertions"]["cleaned"])

    def test_non_darwin_host_writes_schema_valid_blocked_record(self) -> None:
        candidate = self.candidate("mac-amd64")
        evidence = self.root / "native unavailable evidence"
        environment = os.environ.copy()
        for name in list(environment):
            if name.startswith("LIZZIE_MACOS_ACCEPTANCE_FIXTURE_") or name.startswith("APPLE_"):
                environment.pop(name)
        environment["LIZZIE_MACOS_ACCEPTANCE_ENGINE_ORACLE"] = str(evidence / "engine-oracle.json")

        result = subprocess.run(
            [str(SCRIPT), "--candidate", str(candidate), "--scenario", "installed-offline-first-run", "--evidence-dir", str(evidence)],
            cwd=ROOT,
            env=environment,
            capture_output=True,
            text=True,
            timeout=30,
        )
        record = json.loads((evidence / "acceptance.json").read_text(encoding="utf-8"))

        self.assertNotEqual(0, result.returncode)
        provenance.validate_acceptance_record(record)
        self.assertEqual("BLOCKED", record["status"])
        self.assertEqual("content", record["blockedPhase"])
        self.assertIn("Darwin host", record["reason"])


if __name__ == "__main__":
    unittest.main()

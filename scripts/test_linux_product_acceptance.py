#!/usr/bin/env python3
"""Focused fixtures for Linux final-archive product acceptance."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess
import threading
import tempfile
import unittest
import time
import zipfile

from unittest import mock

from scripts import linux_product_acceptance as acceptance
from scripts import release_asset_provenance as provenance


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "linux_product_acceptance.sh"
DATE_TAG = "2026-09-18"
TARGET_SHA = "a" * 40


class LinuxProductAcceptanceFixtureTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="linux-product-acceptance-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "候选 包"
        self.root.mkdir()

    def sha256(self, path: Path) -> str:
        digest = hashlib.sha256()
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
        return digest.hexdigest()

    def json_sha256(self, value: object) -> str:
        encoded = json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode()
        return hashlib.sha256(encoded).hexdigest()

    def executable(self, archive: zipfile.ZipFile, name: str, content: str) -> None:
        info = zipfile.ZipInfo(name)
        info.create_system = 3
        info.external_attr = (stat.S_IFREG | 0o755) << 16
        archive.writestr(info, content)

    def candidate(
        self,
        backend: str = "opencl",
        *,
        java_mode: str = "ready",
        launcher_mode: str = "correct",
        java_architecture: str = "amd64",
    ) -> Path:
        suffix = {
            "cpu": "linux64.with-katago.zip",
            "opencl": "linux64.opencl.zip",
            "nvidia": "linux64.nvidia.zip",
        }[backend]
        key = {"cpu": "linux64", "opencl": "linux64_opencl", "nvidia": "linux64_nvidia"}[backend]
        archive_path = self.root / f"{DATE_TAG}-{suffix}"
        product_root = f"{DATE_TAG}-{suffix.removesuffix('.zip')}"
        java_script = f"""#!/usr/bin/env bash
set -euo pipefail
for arg in "$@"; do
  if [[ "$arg" == "-version" ]]; then
    echo 'openjdk version "21.0.12"' >&2
    echo '    os.arch = {java_architecture}' >&2
    exit 0
  fi
done
"""
        if java_mode != "hang-with-child":
            java_script += """mkdir -p "$LIZZIE_WORK_DIR/logs"
printf '{}\n' >"$LIZZIE_WORK_DIR/config.txt"
printf 'fixture-persist\n' >"$LIZZIE_WORK_DIR/persist"
printf 'application ready\n' >"$LIZZIE_WORK_DIR/logs/app.log"
printf '%s\n' "$$" >"$LIZZIE_LINUX_ACCEPTANCE_FIXTURE_WINDOW"
"""
        if java_mode == "ready":
            java_script += "trap 'exit 0' TERM INT\nwhile :; do sleep 1; done\n"
        elif java_mode == "network":
            java_script += "python3 -c \"import socket; s=socket.socket(); s.settimeout(0.2); s.connect(('198.51.100.1', 443))\" || true\ntrap 'exit 0' TERM INT\nwhile :; do sleep 1; done\n"
        elif java_mode == "hang-with-child":
            java_script += "(trap '' TERM; while :; do sleep 1; done) &\ntrap '' TERM\nwhile :; do sleep 1; done\n"
        elif java_mode == "orphan-child":
            java_script += "(trap '' TERM; while :; do sleep 1; done) &\nprintf '%s\\n' \"$!\" >\"$LIZZIE_WORK_DIR/orphan.pid\"\ntrap 'exit 0' TERM INT\nwhile :; do sleep 1; done\n"
        elif java_mode == "counter-loss":
            java_script += "trap 'kill -KILL $PPID 2>/dev/null || true; exit 0' TERM INT\nwhile :; do sleep 1; done\n"
        elif java_mode == "cpu-oracle":
            java_script += "\"$PWD/Lizzieyzy/engines/katago/linux-x64/katago\" gtp -model \"$PWD/Lizzieyzy/weights/default.bin.gz\" -config \"$PWD/Lizzieyzy/engines/katago/configs/gtp.cfg\" &\nengine_pid=$!\ntrap 'kill $engine_pid 2>/dev/null || true; wait $engine_pid 2>/dev/null || true; exit 0' TERM INT\nwhile :; do sleep 1; done\n"
        else:
            raise AssertionError(java_mode)

        jar_argument = "Lizzieyzy/other.jar" if launcher_mode == "wrong-jar" else "Lizzieyzy/lizzie-yzy2.5.3-shaded.jar"
        work_root_seam = "" if launcher_mode == "omit-work-root" else """if [[ -n "${LIZZIE_WORK_DIR:-}" ]]; then
  JAVA_ARGS+=("-Dlizzie.work.dir=$LIZZIE_WORK_DIR")
fi
"""
        launcher = f"""#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
JAVA_CMD="java"
if [[ -x "Lizzieyzy/runtime/linux-x64/bin/java" ]]; then
  JAVA_CMD="Lizzieyzy/runtime/linux-x64/bin/java"
fi
JAVA_ARGS=(-Xshare:auto -Dlizzie.next.version=fixture)
{work_root_seam}exec "$JAVA_CMD" "${{JAVA_ARGS[@]}}" -jar "{jar_argument}"
"""
        with zipfile.ZipFile(archive_path, "w") as archive:
            self.executable(archive, f"{product_root}/start-linux64.sh", launcher)
            self.executable(
                archive,
                f"{product_root}/Lizzieyzy/runtime/linux-x64/bin/java",
                java_script,
            )
            archive.writestr(f"{product_root}/Lizzieyzy/lizzie-yzy2.5.3-shaded.jar", b"fixture jar")
            if launcher_mode == "wrong-jar":
                archive.writestr(f"{product_root}/Lizzieyzy/other.jar", b"wrong jar")
            self.executable(
                archive,
                f"{product_root}/Lizzieyzy/engines/katago/linux-x64/katago",
                "#!/usr/bin/env bash\ntrap 'exit 0' TERM INT\nwhile :; do sleep 1; done\n",
            )
            archive.writestr(
                f"{product_root}/Lizzieyzy/engines/katago/linux-x64/lizzieyzy-next-engine-backend.txt",
                backend,
            )
            archive.writestr(
                f"{product_root}/Lizzieyzy/engines/katago/configs/gtp.cfg",
                "fixture config\n",
            )
            archive.writestr(
                f"{product_root}/Lizzieyzy/weights/default.bin.gz",
                b"fixture weight",
            )

        for name in provenance.expected_asset_names("linux", DATE_TAG):
            other = self.root / name
            if other != archive_path:
                other.write_bytes(f"fixture {name}\n".encode())
        provenance_path = self.root / "release-asset-provenance.json"
        manifest = provenance.build_provenance(
            self.root,
            "linux",
            DATE_TAG,
            f"next-{DATE_TAG}.1",
            TARGET_SHA,
            123,
            1,
        )
        provenance.write_json_atomic(provenance_path, manifest)
        payload = provenance.verify_candidate(
            provenance_path,
            "linux",
            DATE_TAG,
            f"next-{DATE_TAG}.1",
            TARGET_SHA,
            123,
            1,
            archive_path.name,
            archive_path,
        )
        candidate_path = self.root / "candidate.json"
        provenance.write_json_atomic(candidate_path, payload)
        return candidate_path
    def rebind_candidate(self, candidate_path: Path) -> None:
        candidate = json.loads(candidate_path.read_text(encoding="utf-8"))
        manifest_path = Path(candidate["provenance"]["path"])
        manifest = provenance.build_provenance(
            self.root,
            "linux",
            DATE_TAG,
            f"next-{DATE_TAG}.1",
            TARGET_SHA,
            123,
            1,
        )
        provenance.write_json_atomic(manifest_path, manifest)
        payload = provenance.verify_candidate(
            manifest_path,
            "linux",
            DATE_TAG,
            f"next-{DATE_TAG}.1",
            TARGET_SHA,
            123,
            1,
            candidate["artifact"]["name"],
            Path(candidate["artifact"]["sourcePath"]),
        )
        provenance.write_json_atomic(candidate_path, payload)

    def produce_cpu_oracle(
        self,
        evidence: Path,
        candidate_path: Path,
        errors: list[BaseException],
        valid_peer: bool,
    ) -> None:
        try:
            run_path = evidence / "run.json"
            deadline = time.monotonic() + 15
            while time.monotonic() < deadline and not run_path.is_file():
                time.sleep(0.05)
            self.assertTrue(run_path.is_file(), "runner did not publish run.json")
            run = json.loads(run_path.read_text(encoding="utf-8"))
            candidate = json.loads(candidate_path.read_text(encoding="utf-8"))
            engine_rows = [
                row
                for row in run["processes"]
                if run["engine"]["path"] in row["commandLine"]
            ]
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
                "peer": {
                    "kind": "real-katago-cpu",
                    "catalogEngineId": "fixture-cpu",
                    "command": engine_row["commandLine"],
                    "pid": engine_row["pid"],
                    "stdoutReader": "terminated",
                    "stderrReader": "terminated",
                },
                "source": {"commit": candidate["targetSha"], "dirty": False},
                "platform": {
                    "os": "Linux fixture",
                    "arch": "x86_64",
                    "jvmVersion": "21",
                    "jvmVendor": "fixture",
                },
                "manifest": {
                    "path": "fixture",
                    "sha256": "b" * 64,
                    "preparedAt": "2026-09-18T00:00:00Z",
                    "networkUsed": False,
                },
                "assets": {
                    "catalogPath": "fixture",
                    "catalogSha256": "c" * 64,
                    "archiveFileName": candidate["artifact"]["name"],
                    "archivePath": candidate["artifact"]["sourcePath"],
                    "archiveSizeBytes": candidate["artifact"]["sizeBytes"],
                    "archiveSha256": candidate["artifact"]["sha256"],
                    "katagoVersion": "fixture",
                    "katagoReleaseTag": "v1.18.1",
                    "katagoSourceCommit": "fixture",
                    "katagoBackend": "Eigen",
                    "enginePath": run["engine"]["path"],
                    "engineSizeBytes": Path(run["engine"]["path"]).stat().st_size,
                    "engineSha256": run["engine"]["sha256"],
                    "engineVersionOutput": "fixture",
                    "modelId": "fixture",
                    "modelPath": run["engine"]["modelPath"],
                    "modelSizeBytes": Path(run["engine"]["modelPath"]).stat().st_size,
                    "modelSha256": run["engine"]["modelSha256"],
                    "configSource": "gtp.cfg",
                    "configPath": run["engine"]["configPath"],
                    "configSizeBytes": Path(run["engine"]["configPath"]).stat().st_size,
                    "configSha256": run["engine"]["configSha256"],
                },
                "fixture": {
                    "path": "fixture.sgf",
                    "sha256": "ec41cf044ee29b2408b488727db2b6bbe5a2a3acfe5ed74d70fbb6dac2be3a9c",
                },
                "node": {
                    "semanticPath": "0/0/0/0/0",
                    "kind": "PASS",
                    "identity": "fixture-node",
                },
                "rules": {
                    "targetRaw": "Chinese",
                    "targetSummary": "CHINESE",
                    "targetRevision": 1,
                    "actual": {
                        "friendlyPassOk": True,
                        "scoring": "AREA",
                        "ko": "SIMPLE",
                        "whiteHandicapBonus": "N",
                        "suicide": False,
                        "tax": "NONE",
                        "hasButton": False,
                    },
                    "status": "CONFIRMED",
                    "fresh": True,
                },
                "position": {
                    "confirmed": True,
                    "board": "19x19",
                    "komi": 6.5,
                    "stones": "B:fd;W:ee,ff,hh",
                    "empty": "dd",
                    "turn": "W",
                    "setupKind": "SNAPSHOT",
                    "setupStones": "B:fd;W:ee,ff",
                    "tailPrevious": "MOVE:W[hh]",
                    "tailCurrent": "PASS:B[]",
                },
                "analysis": {
                    "schema": "katago-info-v1",
                    "move": "Q16",
                    "visits": 12,
                    "winrate": 0.5,
                    "scoreLead": 0.0,
                    "pv": ["Q16"],
                },
                "phasesMs": {
                    "startup": 1,
                    "rulesPosition": 1,
                    "analysis": 1,
                    "stop": 1,
                    "quit": 1,
                    "cleanup": 1,
                    "total": 6,
                },
                "stop": {
                    "requested": True,
                    "quietWindowMs": 400,
                    "quiet": True,
                    "peerOutputCount": 2,
                    "applicationVisits": 12,
                },
                "quit": {"normal": True},
                "cleanup": {
                    "forced": False,
                    "durationMs": 1,
                    "process": True,
                    "readers": True,
                    "stagedSgf": True,
                },
                "evidence": {
                    "result": str(oracle_dir / "result.json"),
                    "stdout": str(oracle_dir / "stdout.log"),
                    "stderr": str(oracle_dir / "stderr.log"),
                    "appLog": str(oracle_dir / "app.log"),
                    "phases": str(oracle_dir / "phases.log"),
                    "stagedSgfs": [str(missing_staged)],
                },
            }
            if not valid_peer:
                oracle["peer"]["catalogEngineId"] = ""
            provenance.write_json_atomic(oracle_dir / "result.json", oracle)
            os.kill(int(engine_row["pid"]), 15)
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline and Path(f"/proc/{engine_row['pid']}").exists():
                time.sleep(0.05)
            self.assertFalse(
                Path(f"/proc/{engine_row['pid']}").exists(),
                "fixture engine did not stop",
            )
            binding = {
                "candidateSha256": run["candidate"]["sha256"],
                "artifactSha256": run["candidate"]["artifactSha256"],
                "runJsonSha256": self.sha256(run_path),
                "launcherSha256": run["launcher"]["sha256"],
                "runtimeSha256": run["runtime"]["sha256"],
                "jarSha256": run["jar"]["sha256"],
                "dataRoot": run["dataRoot"],
                "launcherPid": run["launcher"]["pid"],
                "enginePid": engine_row["pid"],
                "engineCommand": engine_row["commandLine"],
                "oracleSha256": self.json_sha256(oracle),
            }
            provenance.write_json_atomic(
                evidence / "engine-oracle.json",
                {
                    "schemaVersion": 1,
                    "scenario": "linux-final-product-real-cpu",
                    "status": "PASS",
                    "binding": binding,
                    "oracle": oracle,
                },
            )
        except BaseException as exc:
            errors.append(exc)


    def run_acceptance(
        self,
        candidate: Path,
        *,
        scenario: str = "variant-launch",
        timeout: int = 20,
        produce_oracle: bool = False,
        valid_peer: bool = True,
    ) -> tuple[subprocess.CompletedProcess[str], Path, dict[str, object]]:
        evidence = self.root / "验收 evidence"
        environment = os.environ.copy()
        environment.update(
            {
                "LIZZIE_LINUX_ACCEPTANCE_FIXTURE_MODE": "1",
                "LIZZIE_LINUX_ACCEPTANCE_TIMEOUT_SECONDS": str(timeout),
                "LIZZIE_LINUX_ACCEPTANCE_STOP_SECONDS": "2",
            }
        )
        producer_errors: list[BaseException] = []
        producer = None
        if produce_oracle:
            environment["LIZZIE_LINUX_ACCEPTANCE_ENGINE_ORACLE"] = str(
                evidence / "engine-oracle.json"
            )
            producer = threading.Thread(
                target=self.produce_cpu_oracle,
                args=(evidence, candidate, producer_errors, valid_peer),
                daemon=True,
            )
            producer.start()
        result = subprocess.run(
            [
                str(SCRIPT),
                "--candidate",
                str(candidate),
                "--scenario",
                scenario,
                "--evidence-dir",
                str(evidence),
            ],
            cwd=ROOT,
            env=environment,
            capture_output=True,
            text=True,
            timeout=40,
        )
        if producer is not None:
            producer.join(timeout=20)
            self.assertFalse(producer.is_alive(), "engine oracle producer did not finish")
            if producer_errors:
                raise producer_errors[0]
        record_path = evidence / "acceptance.json"
        record = json.loads(record_path.read_text(encoding="utf-8")) if record_path.exists() else {}
        return result, evidence, record

    def test_variant_launch_uses_final_archive_launcher_and_bundled_runtime(self) -> None:
        candidate = self.candidate("opencl")

        result, evidence, record = self.run_acceptance(candidate)

        self.assertEqual(0, result.returncode, result.stderr)
        provenance.validate_acceptance_record(record)
        self.assertEqual("PASS", record["status"])
        observed = record["observed"]
        self.assertTrue(observed["host"]["fixtureMode"])
        self.assertEqual("opencl", observed["product"]["backend"])
        self.assertIn("候选 包", observed["candidate"]["path"])
        self.assertIn("验收 提取", observed["product"]["productRoot"])
        self.assertIn("隔离 数据", observed["dataRoot"]["path"])
        self.assertEqual("BLOCKED", observed["outcome"]["inferenceStatus"])
        self.assertEqual([], record["cleanup"]["remainingOwnedResources"])
        self.assertTrue((evidence / "launcher.stdout.log").is_file())
        self.assertTrue((evidence / "launcher.stderr.log").is_file())

    def test_rejects_archive_traversal_without_writing_outside_extraction(self) -> None:
        candidate = self.candidate("opencl")
        archive = Path(json.loads(candidate.read_text(encoding="utf-8"))["artifact"]["sourcePath"])
        with zipfile.ZipFile(archive, "a") as package:
            package.writestr("../escaped.txt", "unsafe")
        self.rebind_candidate(candidate)

        result, evidence, record = self.run_acceptance(candidate)
        self.assertNotEqual(0, result.returncode)
        provenance.validate_acceptance_record(record)
        self.assertEqual("FAIL", record["status"])
        self.assertEqual("extraction", record["phase"])
        self.assertIn("Unsafe archive entry", record["failure"]["summary"])
        self.assertFalse((evidence.parent / "escaped.txt").exists())
        self.assertTrue(record["cleanup"]["complete"])


    def test_tampered_candidate_identity_fails_before_extraction(self) -> None:
        candidate = self.candidate("opencl")
        payload = json.loads(candidate.read_text(encoding="utf-8"))
        payload["artifact"]["sha256"] = "0" * 64
        provenance.write_json_atomic(candidate, payload)

        result, _, record = self.run_acceptance(candidate)

        self.assertNotEqual(0, result.returncode)
        provenance.validate_acceptance_record(record)
        self.assertEqual("FAIL", record["status"])
        self.assertEqual("identity", record["phase"])
        self.assertIn("canonical verified candidate", record["failure"]["summary"])
    def test_mismatched_provenance_fails_before_extraction(self) -> None:
        candidate = self.candidate("opencl")
        payload = json.loads(candidate.read_text(encoding="utf-8"))
        manifest_path = Path(payload["provenance"]["path"])
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["targetSha"] = "b" * 40
        provenance.write_json_atomic(manifest_path, manifest)

        result, _, record = self.run_acceptance(candidate)

        self.assertNotEqual(0, result.returncode)
        provenance.validate_acceptance_record(record)
        self.assertEqual("FAIL", record["status"])
        self.assertEqual("identity", record["phase"])
        self.assertIn("Candidate verification failed", record["failure"]["summary"])


    def test_missing_candidate_archive_writes_schema_valid_identity_failure(self) -> None:
        candidate = self.candidate("opencl")
        payload = json.loads(candidate.read_text(encoding="utf-8"))
        Path(payload["artifact"]["sourcePath"]).unlink()

        result, _, record = self.run_acceptance(candidate)

        self.assertNotEqual(0, result.returncode)
        provenance.validate_acceptance_record(record)
        self.assertEqual("FAIL", record["status"])
        self.assertEqual("identity", record["phase"])
        self.assertIn("Candidate verification failed", record["failure"]["summary"])

    def test_rejects_architecture_neutral_or_wrong_packaged_java(self) -> None:
        candidate = self.candidate("opencl", java_architecture="aarch64")

        result, _, record = self.run_acceptance(candidate)

        self.assertNotEqual(0, result.returncode)
        provenance.validate_acceptance_record(record)
        self.assertEqual("FAIL", record["status"])
        self.assertEqual("extraction", record["phase"])
        self.assertIn("does not prove x86_64", record["failure"]["summary"])

    def test_wrong_backend_marker_fails_before_launch(self) -> None:
        candidate = self.candidate("opencl")
        archive = Path(json.loads(candidate.read_text(encoding="utf-8"))["artifact"]["sourcePath"])
        replacement = archive.with_suffix(".replacement.zip")
        marker_suffix = "/lizzieyzy-next-engine-backend.txt"
        with zipfile.ZipFile(archive) as source, zipfile.ZipFile(replacement, "w") as target:
            for entry in source.infolist():
                content = b"nvidia\n" if entry.filename.endswith(marker_suffix) else source.read(entry)
                target.writestr(entry, content)
        replacement.replace(archive)
        self.rebind_candidate(candidate)

        result, _, record = self.run_acceptance(candidate)

        self.assertNotEqual(0, result.returncode)
        provenance.validate_acceptance_record(record)
        self.assertEqual("FAIL", record["status"])
        self.assertIn("Backend marker", record["failure"]["summary"])
        self.assertIsNone(record["observed"]["launcher"]["pid"])

    def test_missing_bundled_runtime_cannot_fall_back_to_system_java(self) -> None:
        candidate = self.candidate("opencl")
        archive = Path(json.loads(candidate.read_text(encoding="utf-8"))["artifact"]["sourcePath"])
        replacement = archive.with_suffix(".replacement.zip")
        with zipfile.ZipFile(archive) as source, zipfile.ZipFile(replacement, "w") as target:
            for entry in source.infolist():
                if entry.filename.endswith("/runtime/linux-x64/bin/java"):
                    continue
                target.writestr(entry, source.read(entry))
        replacement.replace(archive)
        self.rebind_candidate(candidate)

        result, _, record = self.run_acceptance(candidate)

        self.assertNotEqual(0, result.returncode)
        provenance.validate_acceptance_record(record)
        self.assertEqual("FAIL", record["status"])
        self.assertIn("bundled Java is missing", record["failure"]["summary"])
        self.assertIsNone(record["observed"]["launcher"]["pid"])
    def test_missing_product_jar_fails_before_launch(self) -> None:
        candidate = self.candidate("opencl")
        archive = Path(json.loads(candidate.read_text(encoding="utf-8"))["artifact"]["sourcePath"])
        replacement = archive.with_suffix(".replacement.zip")
        with zipfile.ZipFile(archive) as source, zipfile.ZipFile(replacement, "w") as target:
            for entry in source.infolist():
                if entry.filename.endswith("/Lizzieyzy/lizzie-yzy2.5.3-shaded.jar"):
                    continue
                target.writestr(entry, source.read(entry))
        replacement.replace(archive)
        self.rebind_candidate(candidate)

        result, _, record = self.run_acceptance(candidate)

        self.assertNotEqual(0, result.returncode)
        provenance.validate_acceptance_record(record)
        self.assertEqual("FAIL", record["status"])
        self.assertIn("expected shaded JAR", record["failure"]["summary"])
        self.assertIsNone(record["observed"]["launcher"]["pid"])


    def test_rejects_launcher_that_loads_a_different_jar(self) -> None:
        candidate = self.candidate("opencl", launcher_mode="wrong-jar")

        result, _, record = self.run_acceptance(candidate)

        self.assertNotEqual(0, result.returncode)
        provenance.validate_acceptance_record(record)
        self.assertEqual("FAIL", record["status"])
        self.assertIn("does not load the extracted shaded JAR", record["failure"]["summary"])

    def test_rejects_launcher_that_omits_the_isolated_work_root(self) -> None:
        candidate = self.candidate("opencl", launcher_mode="omit-work-root")

        result, _, record = self.run_acceptance(candidate)

        self.assertNotEqual(0, result.returncode)
        provenance.validate_acceptance_record(record)
        self.assertEqual("FAIL", record["status"])
        self.assertIn("does not select the isolated work root", record["failure"]["summary"])

    def test_external_network_attempt_fails_after_counter_capture_and_cleanup(self) -> None:
        candidate = self.candidate("opencl", java_mode="network")

        result, _, record = self.run_acceptance(candidate)

        self.assertNotEqual(0, result.returncode)
        provenance.validate_acceptance_record(record)
        self.assertEqual("FAIL", record["status"])
        self.assertGreater(record["observed"]["network"]["blockedAttemptCount"], 0)
        self.assertEqual([], record["cleanup"]["remainingOwnedResources"])

    def test_timeout_kills_owned_process_tree_and_records_failure(self) -> None:
        candidate = self.candidate("opencl", java_mode="hang-with-child")

        result, _, record = self.run_acceptance(candidate, timeout=1)

        self.assertNotEqual(0, result.returncode)
        provenance.validate_acceptance_record(record)
        self.assertEqual("FAIL", record["status"])
        self.assertEqual("timeout", record["failure"]["kind"])
        self.assertEqual([], record["cleanup"]["remainingOwnedResources"])

    def test_cleanup_kills_child_reparented_after_runtime_exit(self) -> None:
        candidate = self.candidate("opencl", java_mode="orphan-child")

        result, _, record = self.run_acceptance(candidate)

        self.assertEqual(0, result.returncode, result.stderr)
        provenance.validate_acceptance_record(record)
        orphan_pid = int((Path(record["observed"]["dataRoot"]["path"]) / "orphan.pid").read_text())
        self.assertFalse(Path(f"/proc/{orphan_pid}").exists(), f"owned orphan {orphan_pid} survived")
        self.assertEqual([], record["cleanup"]["remainingOwnedResources"])

    def test_missing_network_counter_keeps_schema_valid_cleanup_failure(self) -> None:
        candidate = self.candidate("opencl", java_mode="counter-loss")

        result, evidence, record = self.run_acceptance(candidate)

        self.assertNotEqual(0, result.returncode)
        provenance.validate_acceptance_record(record)
        self.assertEqual("FAIL", record["status"])
        self.assertEqual("cleanup", record["phase"])
        self.assertTrue(record["cleanup"]["complete"])
        self.assertTrue((evidence / "cleanup-summary.json").is_file())
        self.assertFalse((evidence / "record-validation-failure.txt").exists())

    def test_cpu_offline_first_run_requires_bound_complete_engine_oracle(self) -> None:
        candidate = self.candidate("cpu", java_mode="cpu-oracle")

        result, _, record = self.run_acceptance(
            candidate,
            scenario="cpu-offline-first-run",
            produce_oracle=True,
        )

        self.assertEqual(0, result.returncode, result.stderr)
        provenance.validate_acceptance_record(record)
        self.assertEqual("PASS", record["status"])
        self.assertEqual("PASS", record["observed"]["outcome"]["inferenceStatus"])
        self.assertTrue(Path(record["observed"]["outcome"]["engineOracle"]).is_file())
    def test_cpu_oracle_rejects_missing_production_ownership_identity(self) -> None:
        candidate = self.candidate("cpu", java_mode="cpu-oracle")

        result, _, record = self.run_acceptance(
            candidate,
            scenario="cpu-offline-first-run",
            produce_oracle=True,
            valid_peer=False,
        )

        self.assertNotEqual(0, result.returncode)
        provenance.validate_acceptance_record(record)
        self.assertEqual("FAIL", record["status"])
        self.assertIn("production catalog/manager ownership", record["failure"]["summary"])

    def test_missing_candidate_writes_schema_valid_identity_blocked_with_expected_inputs(self) -> None:
        candidate = self.root / "missing-candidate.json"
        evidence = self.root / "missing-candidate-evidence"
        artifact_name = f"{DATE_TAG}-linux64.opencl.zip"

        result = subprocess.run(
            [
                str(SCRIPT),
                "--candidate",
                str(candidate),
                "--scenario",
                "variant-launch",
                "--evidence-dir",
                str(evidence),
                "--expected-target-sha",
                TARGET_SHA,
                "--expected-artifact-key",
                "linux64_opencl",
                "--expected-artifact-name",
                artifact_name,
                "--expected-artifact-class",
                "linux-product",
            ],
            cwd=ROOT,
            capture_output=True,
            text=True,
            timeout=30,
        )

        self.assertNotEqual(0, result.returncode)
        record = json.loads((evidence / "acceptance.json").read_text(encoding="utf-8"))
        provenance.validate_acceptance_record(record)
        self.assertEqual("BLOCKED", record["status"])
        self.assertEqual("identity", record["phase"])
        self.assertEqual("identity", record["blockedPhase"])
        self.assertEqual(TARGET_SHA, record["expected"]["targetSha"])
        self.assertEqual(
            {
                "key": "linux64_opencl",
                "name": artifact_name,
                "class": "linux-product",
            },
            record["expected"]["artifact"],
        )
        self.assertIsNone(record["observed"]["candidate"]["path"])
        self.assertTrue(record["cleanup"]["complete"])
        self.assertEqual([], record["cleanup"]["remainingOwnedResources"])

    def test_existing_candidate_fails_closed_on_requested_identity_mismatch(self) -> None:
        candidate = self.candidate("opencl")
        evidence = self.root / "identity-mismatch-evidence"

        result = subprocess.run(
            [
                str(SCRIPT),
                "--candidate",
                str(candidate),
                "--scenario",
                "variant-launch",
                "--evidence-dir",
                str(evidence),
                "--expected-target-sha",
                "b" * 40,
            ],
            cwd=ROOT,
            capture_output=True,
            text=True,
            timeout=30,
        )

        self.assertNotEqual(0, result.returncode)
        record = json.loads((evidence / "acceptance.json").read_text(encoding="utf-8"))
        provenance.validate_acceptance_record(record)
        self.assertEqual("FAIL", record["status"])
        self.assertEqual("identity", record["phase"])
        self.assertIn("targetSha differs", record["failure"]["summary"])
        self.assertTrue(record["cleanup"]["complete"])

    def test_wrong_physical_host_architecture_is_blocked_before_probing_runtime(self) -> None:
        candidate = self.candidate("opencl")
        evidence = self.root / "验收 evidence"

        with mock.patch.object(acceptance.platform, "machine", return_value="aarch64"):
            result = acceptance.run(candidate, "variant-launch", evidence)

        self.assertNotEqual(0, result)
        record_path = evidence / "acceptance.json"
        self.assertTrue(record_path.is_file())
        record = json.loads(record_path.read_text(encoding="utf-8"))
        provenance.validate_acceptance_record(record)
        self.assertEqual("BLOCKED", record["status"])
        self.assertEqual("extraction", record["blockedPhase"])
        self.assertEqual("identity", record["phase"])
        self.assertIn("requires an x86_64 host", record["reason"])
        self.assertIn("aarch64", record["reason"])
        self.assertTrue(record["cleanup"]["complete"])
        self.assertEqual([], record["cleanup"]["remainingOwnedResources"])
        self.assertIsNone(record["observed"]["product"]["productRoot"])
        self.assertIsNone(record["observed"]["runtime"]["version"])

    def test_missing_or_unqueryable_prelaunch_display_is_blocked_before_launch(self) -> None:
        candidate = self.candidate("opencl")
        evidence = self.root / "验收 evidence"

        original_run = acceptance.subprocess.run

        def fake_subprocess_run(command, *args, **kwargs):
            if isinstance(command, list) and command and command[0] == "xwininfo":
                return subprocess.CompletedProcess(
                    command,
                    returncode=1,
                    stdout="",
                    stderr="xwininfo: unable to open display\n",
                )
            return original_run(command, *args, **kwargs)

        with (
            mock.patch.object(acceptance, "FIXTURE_MODE", False),
            mock.patch.object(acceptance.shutil, "which", return_value="/bin/true"),
            mock.patch.object(acceptance.subprocess, "run", side_effect=fake_subprocess_run),
        ):
            result = acceptance.run(candidate, "variant-launch", evidence)

        self.assertNotEqual(0, result)
        record_path = evidence / "acceptance.json"
        self.assertTrue(record_path.is_file())
        record = json.loads(record_path.read_text(encoding="utf-8"))
        provenance.validate_acceptance_record(record)
        self.assertEqual("BLOCKED", record["status"])
        self.assertEqual("launch", record["blockedPhase"])
        self.assertEqual("extraction", record["phase"])
        self.assertIn("display", record["reason"].lower())
        self.assertTrue(record["cleanup"]["complete"])
        self.assertEqual([], record["cleanup"]["remainingOwnedResources"])
        self.assertIsNotNone(record["observed"]["product"]["productRoot"])
        self.assertIsNotNone(record["observed"]["runtime"]["version"])
        self.assertIsNone(record["observed"]["launcher"]["pid"])


if __name__ == "__main__":
    unittest.main()

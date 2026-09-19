#!/usr/bin/env python3
"""Focused fixtures for standalone Java 17 shaded-runtime verification."""

from __future__ import annotations

import json
import os
from pathlib import Path
import signal
import stat
import subprocess
import tempfile
import time
import unittest
from unittest import mock
import zipfile

from scripts import verify_java17_runtime as verifier


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "verify_java17_runtime.py"
SOURCE_SHA = "a" * 40


class Java17RuntimeVerifierTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="java17-runtime-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "候选 包"
        self.root.mkdir()
        self.java_home = self.root / "temurin 17"
        (self.java_home / "bin").mkdir(parents=True)
        self.executable(
            self.java_home / "bin/java",
            """#!/usr/bin/env sh
cat >&2 <<'EOF'
openjdk version "17.0.16" 2025-07-15
OpenJDK Runtime Environment Temurin-17.0.16+8 (build 17.0.16+8)
    java.home = /fixture/temurin-17
    java.runtime.version = 17.0.16+8
    java.vendor = Eclipse Adoptium
    os.arch = amd64
EOF
""",
        )
        self.executable(
            self.java_home / "bin/jdeps",
            """#!/usr/bin/env sh
printf '%s\n' 'fixture.jar -> java.base' '   fixture -> java.lang java.base'
""",
        )

    @staticmethod
    def executable(path: Path, content: str) -> None:
        path.write_text(content, encoding="utf-8", newline="\n")
        path.chmod(stat.S_IFREG | 0o755)

    @staticmethod
    def classfile(major: int) -> bytes:
        return b"\xca\xfe\xba\xbe\x00\x00" + major.to_bytes(2, "big")

    def jar(self, entries: dict[str, bytes]) -> Path:
        jar = self.root / "lizzie shaded.jar"
        manifest = (
            "Manifest-Version: 1.0\r\n"
            "Main-Class: featurecat.lizzie.Lizzie\r\n"
            "Build-Jdk-Spec: 21\r\n\r\n"
        ).encode()
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", manifest)
            for name, content in entries.items():
                archive.writestr(name, content)
        return jar

    def run_verifier(
        self,
        jar: Path,
        scenario: str = "static",
        *,
        environment: dict[str, str] | None = None,
    ) -> tuple[subprocess.CompletedProcess[str], Path]:
        evidence = self.root / f"验收 evidence {len(list(self.root.glob('验收 evidence *')))}"
        process_environment = os.environ.copy()
        process_environment.update(environment or {})
        result = subprocess.run(
            [
                "python3",
                str(SCRIPT),
                "--jar",
                str(jar),
                "--source-sha",
                SOURCE_SHA,
                "--java-home",
                str(self.java_home),
                "--evidence-dir",
                str(evidence),
                "--scenario",
                scenario,
            ],
            cwd=ROOT,
            env=process_environment,
            capture_output=True,
            text=True,
            timeout=30,
        )
        return result, evidence

    def configure_application_java(self) -> None:
        self.executable(
            self.java_home / "bin/java",
            """#!/usr/bin/env sh
if [ "${1:-}" = "-XshowSettings:properties" ]; then
  cat >&2 <<'EOF'
openjdk version "17.0.16" 2025-07-15
OpenJDK Runtime Environment Temurin-17.0.16+8 (build 17.0.16+8)
    java.home = /fixture/temurin-17
    java.runtime.version = 17.0.16+8
    java.vendor = Eclipse Adoptium
    os.arch = amd64
EOF
  exit 0
fi
work=
for arg in "$@"; do
  case "$arg" in
    -Dlizzie.work.dir=*) work=${arg#-Dlizzie.work.dir=} ;;
  esac
done
mkdir -p "$work/logs"
printf '{}\n' >"$work/config.txt"
printf 'fixture-persist\n' >"$work/persist"
printf 'application ready\n' >"$work/logs/app.log"
printf '%s\n' "$$" >"$LIZZIE_JAVA17_FIXTURE_WINDOW"
trap 'exit 0' TERM INT
while :; do sleep 1; done
""",
        )

    def test_static_accepts_java17_effective_classes_and_reports_later_mr_entries(self) -> None:
        jar = self.jar(
            {
                "example/Root.class": self.classfile(61),
                "META-INF/versions/17/example/Versioned.class": self.classfile(61),
                "META-INF/versions/21/example/Future.class": self.classfile(65),
            }
        )

        result, evidence = self.run_verifier(jar)

        self.assertEqual(0, result.returncode, result.stderr)
        record = json.loads((evidence / "acceptance.json").read_text(encoding="utf-8"))
        self.assertEqual("PASS", record["status"])
        inventory = json.loads((evidence / "classfile-inventory.json").read_text(encoding="utf-8"))
        self.assertEqual(2, inventory["effectiveClassCount"])
        self.assertEqual(
            [{"entry": "META-INF/versions/21/example/Future.class", "major": 65, "version": 21}],
            inventory["laterOnlyEntries"],
        )
        self.assertIn("--multi-release 17 --recursive", (evidence / "jdeps-command.txt").read_text())

    def test_static_rejects_root_class_above_java17_with_exact_entry(self) -> None:
        jar = self.jar({"example/TooNew.class": self.classfile(62)})

        result, evidence = self.run_verifier(jar)

        self.assertNotEqual(0, result.returncode)
        record = json.loads((evidence / "acceptance.json").read_text(encoding="utf-8"))
        self.assertEqual("FAIL", record["status"])
        self.assertIn("example/TooNew.class", record["failure"]["summary"])
        self.assertIn("major 62", record["failure"]["summary"])

    def test_static_rejects_unresolved_jdeps_output(self) -> None:
        self.executable(
            self.java_home / "bin/jdeps",
            "#!/usr/bin/env sh\nprintf '%s\\n' 'fixture -> missing.package not found'\n",
        )
        jar = self.jar({"example/Root.class": self.classfile(61)})

        result, evidence = self.run_verifier(jar)

        self.assertNotEqual(0, result.returncode)
        record = json.loads((evidence / "acceptance.json").read_text(encoding="utf-8"))
        self.assertEqual("FAIL", record["status"])
        self.assertIn("unresolved dependencies", record["failure"]["summary"])
        self.assertIn("not found", (evidence / "jdeps-output.log").read_text())
        self.assertEqual(1, record["observed"]["dependencies"]["unresolvedCount"])
        self.assertEqual(
            str((evidence / "jdeps-output.log").resolve()), record["evidence"]["static"]
        )

    def test_missing_explicit_java17_records_schema_valid_blocker(self) -> None:
        (self.java_home / "bin/java").unlink()
        jar = self.jar({"example/Root.class": self.classfile(61)})

        result, evidence = self.run_verifier(jar)

        self.assertNotEqual(0, result.returncode)
        record = json.loads((evidence / "acceptance.json").read_text(encoding="utf-8"))
        self.assertEqual("BLOCKED", record["status"])
        self.assertEqual("static", record["blockedPhase"])
        self.assertIn("java is not executable", record["reason"])
        self.assertTrue(record["cleanup"]["complete"])

    def test_cleanup_finds_reparented_same_group_child_after_supervisor_exit(self) -> None:
        child_pid_file = self.root / "orphan.pid"
        launcher = self.root / "exit-with-child.sh"
        self.executable(
            launcher,
            f"#!/usr/bin/env sh\nsleep 30 &\nprintf '%s\\n' \"$!\" >'{child_pid_file}'\n",
        )
        process = subprocess.Popen([str(launcher)], start_new_session=True)
        process.wait(timeout=5)
        child_pid = int(child_pid_file.read_text(encoding="utf-8"))

        try:
            complete, remaining, errors = verifier.terminate_owned(process, 1)
            self.assertTrue(complete, (remaining, errors))
            self.assertFalse(Path(f"/proc/{child_pid}").exists(), f"owned child {child_pid} survived")
        finally:
            if Path(f"/proc/{child_pid}").exists():
                os.kill(child_pid, signal.SIGKILL)

    def test_cleanup_never_signals_unrelated_preferred_pid(self) -> None:
        supervisor = subprocess.Popen(["sleep", "30"], start_new_session=True)
        unrelated = subprocess.Popen(["sleep", "30"], start_new_session=True)
        try:
            complete, remaining, errors = verifier.terminate_owned(supervisor, 1, unrelated.pid)
            self.assertTrue(complete, (remaining, errors))
            self.assertIsNone(unrelated.poll(), "cleanup signalled an unrelated preferred PID")
        finally:
            if supervisor.poll() is None:
                os.killpg(supervisor.pid, signal.SIGKILL)
            if unrelated.poll() is None:
                unrelated.kill()
            supervisor.wait(timeout=5)
            unrelated.wait(timeout=5)

    def test_xvfb_invalid_display_output_reaps_spawned_process(self) -> None:
        fake_bin = self.root / "fake-bin"
        fake_bin.mkdir()
        pid_file = self.root / "xvfb.pid"
        self.executable(
            fake_bin / "Xvfb",
            f"#!/usr/bin/env sh\nprintf '%s\\n' \"$$\" >'{pid_file}'\nprintf 'invalid\\n'\ntrap '' TERM\nwhile :; do sleep 1; done\n",
        )
        evidence = self.root / "xvfb evidence"
        evidence.mkdir()
        with mock.patch.dict(os.environ, {"PATH": f"{fake_bin}:{os.environ['PATH']}"}):
            with self.assertRaises(verifier.VerificationError):
                verifier.start_xvfb(evidence)
        child_pid = int(pid_file.read_text(encoding="utf-8"))
        try:
            deadline = time.monotonic() + 2
            while Path(f"/proc/{child_pid}").exists() and time.monotonic() < deadline:
                time.sleep(0.05)
            self.assertFalse(Path(f"/proc/{child_pid}").exists(), f"Xvfb {child_pid} survived")
        finally:
            if Path(f"/proc/{child_pid}").exists():
                os.kill(child_pid, signal.SIGKILL)

    def test_xvfb_acquisition_cleanup_failure_is_recorded_with_owned_handle(self) -> None:
        jar = self.jar({"example/Root.class": self.classfile(61)})
        evidence = self.root / "xvfb cleanup failure evidence"
        process = subprocess.Popen(["sleep", "30"], start_new_session=True, text=True)
        row = verifier.process_snapshot(process.pid, verifier.process_table())
        self.assertIsNotNone(row)
        error = verifier.AcquiredProcessCleanupError(
            "Xvfb startup failed and initial cleanup was incomplete",
            process,
            str(row["identity"]),
        )

        try:
            with (
                mock.patch.object(verifier, "FIXTURE_MODE", False),
                mock.patch.object(verifier.shutil, "which", return_value="/bin/true"),
                mock.patch.object(verifier, "start_xvfb", side_effect=error),
                mock.patch.object(
                    verifier,
                    "terminate_owned",
                    return_value=(False, [process.pid], ["fixture display cleanup diagnostic"]),
                ),
                mock.patch.dict(os.environ, {"DISPLAY": ""}),
            ):
                result = verifier.run(
                    jar, SOURCE_SHA, self.java_home, evidence, "application-smoke"
                )

            self.assertNotEqual(0, result)
            record = json.loads((evidence / "acceptance.json").read_text(encoding="utf-8"))
            self.assertEqual("FAIL", record["status"])
            self.assertFalse(record["cleanup"]["complete"])
            self.assertIn(
                f"display-process:{process.pid}", record["cleanup"]["remainingOwnedResources"]
            )
            self.assertIn(
                "fixture display cleanup diagnostic",
                record["cleanup"]["remainingOwnedResources"],
            )
            self.assertEqual(
                process.pid, record["observed"]["application"]["displayProcess"]["pid"]
            )
        finally:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=5)

    def test_jdeps_timeout_retains_partial_output_in_acceptance_record(self) -> None:
        jar = self.jar({"example/Root.class": self.classfile(61)})
        evidence = self.root / "jdeps timeout evidence"
        real_run = subprocess.run

        def timed_run(command: list[str], *args: object, **kwargs: object) -> subprocess.CompletedProcess[str]:
            if Path(command[0]).name == "jdeps":
                raise subprocess.TimeoutExpired(
                    command, 180, output="partial-jdeps-marker\n", stderr="partial-error-marker\n"
                )
            return real_run(command, *args, **kwargs)

        with mock.patch.object(verifier.subprocess, "run", side_effect=timed_run):
            result = verifier.run(jar, SOURCE_SHA, self.java_home, evidence, "static")

        self.assertNotEqual(0, result)
        record = json.loads((evidence / "acceptance.json").read_text(encoding="utf-8"))
        self.assertEqual("timeout", record["failure"]["kind"])
        self.assertEqual(str((evidence / "jdeps-output.log").resolve()), record["evidence"]["static"])
        output = (evidence / "jdeps-output.log").read_text(encoding="utf-8")
        self.assertIn("partial-jdeps-marker", output)
        self.assertIn("partial-error-marker", output)

    def test_application_smoke_records_explicit_java17_launch_and_cleanup(self) -> None:
        self.configure_application_java()
        jar = self.jar({"example/Root.class": self.classfile(61)})

        result, evidence = self.run_verifier(
            jar,
            "application-smoke",
            environment={
                "LIZZIE_JAVA17_ACCEPTANCE_FIXTURE_MODE": "1",
                "LIZZIE_JAVA17_ACCEPTANCE_TIMEOUT_SECONDS": "5",
                "LIZZIE_JAVA17_ACCEPTANCE_STOP_SECONDS": "2",
            },
        )

        self.assertEqual(0, result.returncode, result.stderr)
        record = json.loads((evidence / "acceptance.json").read_text(encoding="utf-8"))
        self.assertEqual("PASS", record["status"])
        self.assertEqual("standalone-java17", record["expected"]["platform"])
        self.assertIn("验收 work dir", record["observed"]["application"]["dataRoot"])
        self.assertEqual(0, record["observed"]["network"]["blockedAttemptCount"])
        self.assertEqual([], record["cleanup"]["remainingOwnedResources"])
        self.assertIn("-jar", record["observed"]["application"]["command"])

    def test_cleanup_failure_evidence_survives_later_exception_handling(self) -> None:
        self.configure_application_java()
        jar = self.jar({"example/Root.class": self.classfile(61)})
        evidence = self.root / "cleanup failure evidence"
        real_terminate = verifier.terminate_owned

        def reported_failure(
            process: subprocess.Popen[bytes] | subprocess.Popen[str],
            timeout_seconds: int,
            preferred_pid: int = 0,
            preferred_identity: str | None = None,
        ) -> tuple[bool, list[int], list[str]]:
            _, remaining, errors = real_terminate(
                process, timeout_seconds, preferred_pid, preferred_identity
            )
            return False, remaining, errors + ["fixture cleanup diagnostic"]

        with (
            mock.patch.object(verifier, "FIXTURE_MODE", True),
            mock.patch.object(verifier, "terminate_owned", side_effect=reported_failure),
            mock.patch.dict(
                os.environ,
                {
                    "LIZZIE_JAVA17_ACCEPTANCE_TIMEOUT_SECONDS": "5",
                    "LIZZIE_JAVA17_ACCEPTANCE_STOP_SECONDS": "2",
                },
            ),
        ):
            result = verifier.run(jar, SOURCE_SHA, self.java_home, evidence, "application-smoke")

        self.assertNotEqual(0, result)
        record = json.loads((evidence / "acceptance.json").read_text(encoding="utf-8"))
        self.assertEqual("FAIL", record["status"])
        self.assertFalse(record["cleanup"]["complete"])
        self.assertIn("fixture cleanup diagnostic", record["cleanup"]["remainingOwnedResources"])
        self.assertFalse(record["assertions"]["cleaned"])


if __name__ == "__main__":
    unittest.main()

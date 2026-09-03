#!/usr/bin/env python3

from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

from scripts import run_local_ci


class RunLocalCiTest(unittest.TestCase):
    def test_shell_wrapper_accepts_profile_without_optional_arguments(self):
        repository = Path(__file__).resolve().parents[1]
        bash = os.environ.get("LIZZIE_BASH") or shutil.which("bash")
        if not bash:
            self.skipTest("bash is required to exercise the POSIX local-CI wrapper")
        with tempfile.TemporaryDirectory() as temporary:
            temporary_path = Path(temporary)
            fake_python = temporary_path / "python"
            captured_arguments = temporary_path / "arguments.txt"
            fake_python.write_text(
                '#!/usr/bin/env bash\nprintf "%s\\n" "$@" > "$LIZZIE_WRAPPER_ARGS"\n',
                encoding="utf-8",
            )
            fake_python.chmod(0o755)
            environment = os.environ.copy()
            environment.update(
                {
                    "LIZZIE_PYTHON": str(fake_python),
                    "LIZZIE_MAVEN": "/usr/bin/true",
                    "LIZZIE_WRAPPER_ARGS": str(captured_arguments),
                }
            )

            completed = subprocess.run(
                [bash, "scripts/run_local_ci.sh", "--profile", "all"],
                cwd=repository,
                env=environment,
                capture_output=True,
                text=True,
                timeout=10,
                check=False,
            )

            self.assertEqual(0, completed.returncode, completed.stderr)
            self.assertEqual(
                ["scripts/run_local_ci.py", "--profile", "all"],
                captured_arguments.read_text(encoding="utf-8").splitlines(),
            )

    def test_all_profile_keeps_one_maven_verification(self):
        steps = run_local_ci.build_steps("all", "mvn", "bash", "pwsh")
        verify_steps = [step for step in steps if "verification gate" in step.name]
        self.assertEqual(["Run full Windows verification gate"], [step.name for step in verify_steps])
        self.assertIn("Verify local Markdown links", [step.name for step in steps])
        self.assertIn("Parse RTX 50 benchmark PowerShell", [step.name for step in steps])

    def test_windows_profile_does_not_require_bash(self):
        steps = run_local_ci.build_steps("windows", "mvn", None, "pwsh")
        self.assertIn("Run full Windows verification gate", [step.name for step in steps])
        self.assertNotIn("Verify local Markdown links", [step.name for step in steps])

    def test_portable_shell_steps_use_login_shell_and_explicit_python(self):
        steps = run_local_ci.build_steps("portable", "mvn", "git-bash", None)
        katago = next(
            step for step in steps if step.name == "Verify bundled KataGo shell logic"
        )
        syntax = next(
            step for step in steps if step.name == "Parse release shell scripts"
        )
        self.assertEqual(("git-bash", "-lc"), katago.command[:2])
        self.assertEqual(run_local_ci.sys.executable, katago.env["PYTHON_BIN"])
        self.assertEqual(("git-bash", "-lc"), syntax.command[:2])

    def test_collect_junit_summary_combines_surefire_and_failsafe(self):
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary)
            surefire = target / "surefire-reports"
            failsafe = target / "failsafe-reports"
            surefire.mkdir()
            failsafe.mkdir()
            (surefire / "TEST-unit.xml").write_text(
                '<testsuite tests="5" failures="1" errors="0" skipped="2"/>',
                encoding="utf-8",
            )
            (failsafe / "TEST-it.xml").write_text(
                '<testsuite tests="2" failures="0" errors="1" skipped="0"/>',
                encoding="utf-8",
            )
            self.assertEqual(
                run_local_ci.JunitSummary(
                    suites=2, tests=7, failures=1, errors=1, skipped=2
                ),
                run_local_ci.collect_junit_summary(target),
            )

    def test_reset_junit_reports_removes_stale_results_only(self):
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary)
            surefire = target / "surefire-reports"
            failsafe = target / "failsafe-reports"
            classes = target / "classes"
            surefire.mkdir()
            failsafe.mkdir()
            classes.mkdir()
            (surefire / "TEST-stale.xml").write_text("stale", encoding="utf-8")
            (failsafe / "TEST-stale.xml").write_text("stale", encoding="utf-8")
            (classes / "keep.class").write_text("keep", encoding="utf-8")

            run_local_ci.reset_junit_reports(target)

            self.assertFalse(surefire.exists())
            self.assertFalse(failsafe.exists())
            self.assertTrue((classes / "keep.class").is_file())

    def test_setup_failure_cannot_be_reported_as_pass(self):
        self.assertEqual("FAIL", run_local_ci.overall_result(False, []))
        self.assertEqual("PASS", run_local_ci.overall_result(True, []))
        failed = run_local_ci.StepResult("failed", ["false"], "failed", 1, 0.1)
        self.assertEqual("FAIL", run_local_ci.overall_result(True, [failed]))

    def test_deduplicate_steps_preserves_first_occurrence(self):
        first = run_local_ci.Step("first", ("python", "check.py"))
        duplicate = run_local_ci.Step("duplicate", ("python", "check.py"))
        second = run_local_ci.Step("second", ("git", "diff", "--check"))
        self.assertEqual(
            [first, second], run_local_ci.deduplicate_steps([first, duplicate, second])
        )

    def test_windows_wsl_bash_launcher_is_rejected(self):
        self.assertTrue(
            run_local_ci.is_windows_wsl_bash_launcher(
                r"C:\Windows\System32\bash.exe", windows=True
            )
        )
        self.assertFalse(
            run_local_ci.is_windows_wsl_bash_launcher(
                r"C:\Program Files\Git\bin\bash.exe", windows=True
            )
        )
        self.assertFalse(
            run_local_ci.is_windows_wsl_bash_launcher(
                "/usr/bin/bash", windows=False
            )
        )


if __name__ == "__main__":
    unittest.main()

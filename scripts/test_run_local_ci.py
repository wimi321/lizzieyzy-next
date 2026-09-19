#!/usr/bin/env python3

from pathlib import Path
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from functools import partial
from unittest.mock import patch

from scripts import run_local_ci


class RunLocalCiTest(unittest.TestCase):
    def test_shell_wrapper_default_group_keeps_complete_all_profile_plan(self):
        repository = Path(__file__).resolve().parents[1]
        bash = os.environ.get("LIZZIE_BASH") or shutil.which("bash")
        if not bash:
            self.skipTest("bash is required to exercise the POSIX local-CI wrapper")
        with tempfile.TemporaryDirectory() as temporary:
            temporary_path = Path(temporary)
            environment = os.environ.copy()
            environment["LIZZIE_PYTHON"] = run_local_ci.sys.executable

            completed = subprocess.run(
                [bash, "scripts/run_local_ci.sh", "--profile", "all", "--dry-run",
                 "--summary-dir", str(temporary_path)],
                cwd=repository,
                env=environment,
                capture_output=True,
                text=True,
                timeout=10,
                check=False,
            )

            self.assertEqual(0, completed.returncode, completed.stderr)
            summary = json.loads((temporary_path / "local-ci-summary.json").read_text(encoding="utf-8"))
            commands = [step["command"] for step in summary["steps"]]
            self.assertEqual(1, sum(command[-1] == "verify" for command in commands))
            self.assertTrue(any(command[-1] == "test" for command in commands))
            self.assertEqual("all", summary["group"])
            self.assertEqual("PASS", summary["result"])

    def test_repository_group_preserves_stale_junit_without_java_or_shells(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            scripts = root / "scripts"
            scripts.mkdir()
            for name in ("run_local_ci.py", "check_line_endings.py"):
                shutil.copy2(run_local_ci.REPO_ROOT / "scripts" / name, scripts / name)
            subprocess.run(["git", "init", "-q", str(root)], check=True)
            subprocess.run(
                ["git", "-C", str(root), "-c", "user.name=CI Test", "-c",
                 "user.email=ci@example.invalid", "commit", "--allow-empty", "-qm", "fixture"],
                check=True,
            )
            stale = root / "target" / "surefire-reports" / "TEST-stale.xml"
            stale.parent.mkdir(parents=True)
            stale.write_bytes(b"deliberately invalid old report")
            environment = os.environ.copy()
            for name in ("LIZZIE_MAVEN", "LIZZIE_BASH", "LIZZIE_POWERSHELL", "JAVA_HOME"):
                environment[name] = str(root / "unavailable")
            completed = subprocess.run(
                [run_local_ci.sys.executable, str(scripts / "run_local_ci.py"),
                 "--profile", "windows", "--group", "repository"],
                cwd=root, env=environment, capture_output=True, text=True, timeout=30,
            )
            self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)
            self.assertEqual(b"deliberately invalid old report", stale.read_bytes())
            summary = json.loads((root / "target/local-ci/local-ci-summary.json").read_text(encoding="utf-8"))
            self.assertEqual("PASS", summary["result"])
            self.assertEqual("not executed", summary["junit_status"])
            self.assertEqual(0, summary["junit"]["tests"])
            self.assertEqual("not executed", summary["java"])

            # A real Java selection must discard the old report even if Maven setup fails.
            completed = subprocess.run(
                [run_local_ci.sys.executable, str(scripts / "run_local_ci.py"),
                 "--profile", "windows", "--group", "java"],
                cwd=root, env=environment, capture_output=True, text=True, timeout=30,
            )
            self.assertNotEqual(0, completed.returncode)
            self.assertFalse(stale.exists())
            summary = json.loads((root / "target/local-ci/local-ci-summary.json").read_text(encoding="utf-8"))
            self.assertEqual("FAIL", summary["result"])
            self.assertEqual(0, summary["junit"]["tests"])

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

    def test_acceptance_helpers_are_tested_on_both_ci_platforms(self):
        for profile in ("windows", "portable"):
            with self.subTest(profile=profile):
                commands = [step.command for step in run_local_ci.build_steps(
                    profile, "mvn", "bash", "pwsh", "scripts")]
                for script in ("scripts/test_prepare_cpu_engine_acceptance.py",
                               "scripts/test_run_acceptance.py"):
                    self.assertTrue(any(script in command for command in commands), script)

    def test_desktop_plan_selects_all_required_classes(self):
        steps = run_local_ci.build_steps("portable", "mvn", None, None, "desktop")

        self.assertEqual(1, len(steps))
        self.assertIn(
            "-Dtest=FunctionSearchNavigationTest,ConfigDialog2NavigationTest,"
            "EngineProcessSmokeTest,FunctionSearchInputTest",
            steps[0].command,
        )
        self.assertEqual(
            (
                *tuple(
                    (
                        "featurecat.lizzie.gui.FunctionSearchNavigationTest",
                        f"navigationPreservesRealStateAcrossNativeAndCustomMenus()[{repetition}]",
                    )
                    for repetition in range(1, 6)
                ),
                (
                    "featurecat.lizzie.gui.ConfigDialog2NavigationTest",
                    "blackWinrateRemainsReachableAcrossRebuildsAndRecreation",
                ),
                (
                    "featurecat.lizzie.gui.EngineProcessSmokeTest",
                    "restoresSnapshotAnalyzesAndQuits",
                ),
                ("featurecat.lizzie.gui.FunctionSearchInputTest", "chineseInputChain"),
                ("featurecat.lizzie.gui.FunctionSearchInputTest", "englishInputChain"),
            ),
            run_local_ci.DESKTOP_REQUIRED_TESTS,
        )

    def test_engine_process_plan_selects_exact_required_methods(self):
        steps = run_local_ci.build_steps(
            "portable", "mvn", None, None, "engine-process"
        )

        self.assertEqual(1, len(steps))
        self.assertIn(
            "-Dtest=EngineProcessSmokeTest#restoresSnapshotAnalyzesAndQuits,"
            "EngineProcessFailureTest#rejectsSnapshotErrorWithoutTailOrAnalysis+"
            "retiresSnapshotTimeoutAndRejectsLateResponse+recoversSnapshotAfterPeerCrash+"
            "isolatesLateOutputAfterEngineSwitch+drainsPeerPipeBurstAndRemainsResponsive+"
            "cleansUpPeerThatRefusesQuit",
            steps[0].command,
        )
        self.assertIn(
            "-Dsurefire.reportsDirectory="
            + str(
                run_local_ci.REPO_ROOT
                / "target"
                / "engine-process-smoke"
                / "surefire-reports"
            ),
            steps[0].command,
        )
        self.assertEqual(7, len(run_local_ci.ENGINE_PROCESS_REQUIRED_TESTS))

    def test_tensorrt_ui_plan_is_windows_only_and_selects_exact_parent_methods(self):
        steps = run_local_ci.build_steps(
            "windows", "mvn", None, None, "tensorrt-ui"
        )

        self.assertEqual(1, len(steps))
        self.assertEqual("tensorrt-ui", steps[0].group)
        self.assertIn(
            "-Dtest=TensorRtRepairAcceptanceTest#englishRepairFlow+chineseRepairFlow",
            steps[0].command,
        )
        self.assertIn("-Djava.awt.headless=false", steps[0].command)
        self.assertIn("-Djacoco.skip=true", steps[0].command)
        log_option = steps[0].command.index("--log-file")
        self.assertEqual(
            str(run_local_ci.REPO_ROOT / "target/tensorrt-ui/maven.log"),
            steps[0].command[log_option + 1],
        )
        self.assertIn("-Dlizzie.desktop.required=true", steps[0].command)
        self.assertIn(
            "-Dlizzie.desktop.evidence.dir="
            + str(run_local_ci.REPO_ROOT / "target/tensorrt-ui/probes"),
            steps[0].command,
        )
        self.assertIn(
            "-Dsurefire.reportsDirectory="
            + str(run_local_ci.REPO_ROOT / "target/tensorrt-ui/surefire-reports"),
            steps[0].command,
        )
        self.assertEqual(
            (
                (
                    "featurecat.lizzie.gui.TensorRtRepairAcceptanceTest",
                    "englishRepairFlow",
                ),
                (
                    "featurecat.lizzie.gui.TensorRtRepairAcceptanceTest",
                    "chineseRepairFlow",
                ),
            ),
            run_local_ci.TENSORRT_UI_REQUIRED_TESTS,
        )
        for profile in ("portable", "all"):
            with self.subTest(profile=profile), self.assertRaisesRegex(
                RuntimeError, "requires --profile windows"
            ):
                run_local_ci.build_steps(profile, "mvn", None, None, "tensorrt-ui")

    def test_tensorrt_ui_wrapper_maps_group_and_requires_jpackage(self):
        wrapper = (Path(__file__).parent / "run_local_ci.ps1").read_text(
            encoding="utf-8"
        )

        self.assertIn("'TensorRtUi'", wrapper)
        self.assertIn("'bin\\jpackage.exe'", wrapper)
        self.assertIn(
            "if ($Group -eq 'TensorRtUi') { 'tensorrt-ui' }", wrapper
        )
        self.assertIn("Get-Command py, python3, python", wrapper)

    def test_tensorrt_ui_gate_requires_preserved_maven_log(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            subprocess.run(["git", "init", "-q", temporary], check=True)
            subprocess.run(
                [
                    "git",
                    "-C",
                    temporary,
                    "-c",
                    "user.name=Test",
                    "-c",
                    "user.email=test@example.invalid",
                    "commit",
                    "--allow-empty",
                    "-qm",
                    "test fixture",
                ],
                check=True,
            )
            reports = root / "target/tensorrt-ui/surefire-reports"
            cases = "".join(
                f'<testcase classname="{classname}" name="{method}"/>'
                for classname, method in run_local_ci.TENSORRT_UI_REQUIRED_TESTS
            )
            xml = f'<testsuite tests="2">{cases}</testsuite>'
            args = run_local_ci.parse_args(
                [
                    "--profile",
                    "windows",
                    "--group",
                    "tensorrt-ui",
                    "--summary-dir",
                    str(root / "summary"),
                ]
            )

            def run_case(preserve_log: bool) -> int:
                statements = [
                    "import pathlib",
                    f"p = pathlib.Path(r'{reports}')",
                    "p.mkdir(parents=True, exist_ok=True)",
                    f"(p / 'TEST-tensorrt.xml').write_text('''{xml}''', encoding='utf-8')",
                ]
                if preserve_log:
                    statements.append(
                        f"pathlib.Path(r'{root / 'target/tensorrt-ui/maven.log'}').write_text('maven output', encoding='utf-8')"
                    )
                step = run_local_ci.Step(
                    "write TensorRT reports",
                    (run_local_ci.sys.executable, "-c", "; ".join(statements)),
                )
                with patch.object(run_local_ci, "build_steps", return_value=[step]):
                    return run_local_ci.run(args)

            with (
                patch.object(run_local_ci, "resolve_maven", return_value="mvn"),
                patch.object(run_local_ci, "java_major_version", return_value=(21, "Java21")),
                patch.object(run_local_ci, "REPO_ROOT", root),
                patch.dict(os.environ, {"DISPLAY": ":99"}),
            ):
                self.assertEqual(1, run_case(False))
                self.assertEqual(0, run_case(True))

    def test_syntax_gate_rejects_each_invalid_script_and_accepts_valid_selection(self):
        bash = shutil.which("bash")
        if not bash:
            self.skipTest("bash is required for syntax execution")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            scripts = [root / f"script {index}.sh" for index in range(3)]
            for script in scripts:
                script.write_text("true\n", encoding="utf-8")
            with patch.object(run_local_ci, "BASH_SYNTAX_FILES", tuple(map(str, scripts))):
                steps = [
                    step for step in run_local_ci.portable_steps("mvn", bash)
                    if step.name.startswith("Parse ")
                ]
            for invalid in (1, 2, None):
                with self.subTest(invalid=invalid):
                    if invalid is not None:
                        scripts[invalid].write_text("if then\n", encoding="utf-8")
                    args = run_local_ci.parse_args([
                        "--profile", "portable", "--group", "scripts",
                        "--bash", bash, "--summary-dir", str(root / "summary"),
                    ])
                    with patch.object(run_local_ci, "build_steps", return_value=list(steps)):
                        result = run_local_ci.run(args)
                    summary = json.loads((root / "summary/local-ci-summary.json").read_text())
                    self.assertEqual(0 if invalid is None else 1, result)
                    if invalid is not None:
                        failed = [s for s in summary["steps"] if s["status"] == "failed"]
                        self.assertEqual([f"Parse {scripts[invalid]}"], [s["name"] for s in failed])
                        scripts[invalid].write_text("true\n", encoding="utf-8")

    def test_required_execution_rejects_missing_skipped_and_failed_cases(self):
        required = (("critical.SmokeIT", "runs"), ("critical.NavigationTest", "focuses"))
        good = '<testcase classname="critical.SmokeIT" name="runs"/>'
        navigation = '<testcase classname="critical.NavigationTest" name="focuses"/>'
        optional = '<testcase classname="optional.WindowTest" name="window"><skipped/></testcase>'
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            reports = root / "failsafe-reports"
            reports.mkdir()
            report = reports / "TEST-cases.xml"
            with self.assertRaisesRegex(RuntimeError, "missing successful cases"):
                run_local_ci.collect_junit_summary(root, required)
            for bad in (
                '<testcase classname="unrelated.Test" name="runs"/>',
                '<testcase classname="critical.SmokeIT" name="different"/>',
                '<testcase classname="critical.SmokeIT" name="runs"><skipped/></testcase>',
                good + '<testcase classname="critical.SmokeIT" name="additional"><skipped/></testcase>',
                good + '<testcase classname="critical.SmokeIT" name="runs"><failure/></testcase>',
                '<testcase classname="critical.SmokeIT" name="runs"><error/></testcase>',
            ):
                with self.subTest(case=bad):
                    report.write_text(f'<testsuite tests="3">{bad}{navigation}{optional}</testsuite>')
                    with self.assertRaisesRegex(RuntimeError, "Required JUnit execution"):
                        run_local_ci.collect_junit_summary(root, required)
            report.write_text(f'<testsuite tests="3" skipped="1">{good}{navigation}{optional}</testsuite>')
            self.assertEqual(
                run_local_ci.JunitSummary(suites=1, tests=3, skipped=1),
                run_local_ci.collect_junit_summary(root, required),
            )
            run_local_ci.reset_junit_reports(root)
            with self.assertRaisesRegex(RuntimeError, "missing successful cases"):
                run_local_ci.collect_junit_summary(root, required)
            reports.mkdir()
            report.write_text('<testsuite broken')
            with self.assertRaisesRegex(RuntimeError, "Unable to parse JUnit report"):
                run_local_ci.collect_junit_summary(root, required)

    def test_java_gate_fails_when_successful_commands_leave_no_required_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            args = run_local_ci.parse_args([
                "--profile", "portable", "--group", "java", "--summary-dir", temporary,
            ])
            steps = [run_local_ci.Step("successful command", (run_local_ci.sys.executable, "-c", "pass"))]
            target = Path(temporary) / "target"
            stale = target / "failsafe-reports" / "TEST-stale.xml"
            stale.parent.mkdir(parents=True)
            classname, name = run_local_ci.JAVA_REQUIRED_TESTS[0]
            stale.write_text(f'<testsuite tests="1"><testcase classname="{classname}" name="{name}"/></testsuite>')
            with (
                patch.object(run_local_ci, "resolve_maven", return_value="mvn"),
                patch.object(run_local_ci, "java_major_version", return_value=(21, "Java21")),
                patch.object(run_local_ci, "build_steps", return_value=steps),
                patch.object(run_local_ci, "reset_junit_reports", partial(run_local_ci.reset_junit_reports, target)),
                patch.object(run_local_ci, "collect_junit_summary", partial(run_local_ci.collect_junit_summary, target)),
            ):
                self.assertEqual(1, run_local_ci.run(args))
            summary = json.loads((Path(temporary) / "local-ci-summary.json").read_text())
            self.assertEqual("FAIL", summary["result"])

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

    def test_required_job_results_reject_failure_cancellation_and_skip(self):
        required = {
            "repository-checks": "success",
            "script-tests": "success",
            "windows-script-tests": "success",
            "java-linux": "success",
            "java-windows": "success",
            "desktop-smoke": "success",
            "engine-process": "success",
        }
        run_local_ci.require_successful_job_results(required)
        for status in ("failure", "cancelled", "skipped"):
            with self.subTest(status=status):
                rejected = dict(required)
                rejected["engine-process"] = status
                with self.assertRaisesRegex(
                    RuntimeError, f"engine-process={status}"
                ):
                    run_local_ci.require_successful_job_results(rejected)

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


    def test_desktop_fails_closed_without_display_on_linux_and_dry_run_remains_planning(self):
        with tempfile.TemporaryDirectory() as temporary:
            args = run_local_ci.parse_args([
                "--profile", "portable", "--group", "desktop", "--summary-dir", temporary,
            ])
            with (
                patch.object(run_local_ci.sys, "platform", "linux"),
                patch.dict(os.environ, {"DISPLAY": ""}),
            ):
                self.assertEqual(1, run_local_ci.run(args))
            summary = json.loads((Path(temporary) / "local-ci-summary.json").read_text())
            self.assertEqual("FAIL", summary["result"])

            dry_run_args = run_local_ci.parse_args([
                "--profile", "portable", "--group", "desktop", "--dry-run", "--summary-dir", temporary,
            ])
            with (
                patch.object(run_local_ci.sys, "platform", "linux"),
                patch.dict(os.environ, {"DISPLAY": ""}),
            ):
                self.assertEqual(0, run_local_ci.run(dry_run_args))
            summary = json.loads((Path(temporary) / "local-ci-summary.json").read_text())
            self.assertEqual("PASS", summary["result"])
            self.assertEqual("not executed", summary["junit_status"])
            self.assertTrue(all(s["status"] == "planned" for s in summary["steps"]))

    def test_desktop_gate_fails_when_successful_commands_leave_no_required_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            subprocess.run(["git", "init", "-q", temporary], check=True)
            subprocess.run(
                ["git", "-C", temporary, "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                 "commit", "--allow-empty", "-qm", "test fixture"], check=True,
            )
            args = run_local_ci.parse_args([
                "--profile", "portable", "--group", "desktop", "--summary-dir", temporary,
            ])
            target = Path(temporary) / "target"
            desktop_reports = target / "desktop-smoke" / "surefire-reports"
            desktop_reports.mkdir(parents=True)
            stale = desktop_reports / "TEST-stale.xml"
            stale.write_text('<testsuite tests="1"><testcase classname="stale" name="stale"/></testsuite>')

            *navigation, config, engine, chinese, english = run_local_ci.DESKTOP_REQUIRED_TESTS
            passed_navigation = [(case, "") for case in navigation]

            def suite_xml(*cases: tuple[tuple[str, str], str]) -> str:
                testcases = "".join(
                    f'<testcase classname="{case[0]}" name="{case[1]}">{status}</testcase>'
                    for case, status in cases
                )
                skipped = sum(status == "<skipped/>" for _, status in cases)
                return f'<testsuite tests="{len(cases)}" skipped="{skipped}">{testcases}</testsuite>'

            def make_step(xml_content: str | None = None) -> list[run_local_ci.Step]:
                if xml_content is None:
                    cmd = (run_local_ci.sys.executable, "-c", "pass")
                else:
                    script = (
                        "import pathlib; "
                        f"p = pathlib.Path(r'{desktop_reports}'); "
                        "p.mkdir(parents=True, exist_ok=True); "
                        f"(p / 'TEST-desktop.xml').write_text('''{xml_content}''', encoding='utf-8')"
                    )
                    cmd = (run_local_ci.sys.executable, "-c", script)
                return [run_local_ci.Step("mock desktop run", cmd)]

            with (
                patch.object(run_local_ci, "resolve_maven", return_value="mvn"),
                patch.object(run_local_ci, "java_major_version", return_value=(21, "Java21")),
                patch.object(run_local_ci, "REPO_ROOT", Path(temporary)),
                patch.dict(os.environ, {"DISPLAY": ":99"}),
            ):
                with patch.object(run_local_ci, "build_steps", return_value=make_step(None)):
                    self.assertEqual(1, run_local_ci.run(args))
                    summary = json.loads((Path(temporary) / "local-ci-summary.json").read_text())
                    self.assertEqual("FAIL", summary["result"])
                    self.assertEqual(0, summary["junit"]["tests"])
                    self.assertFalse(stale.exists())

                missing_engine = suite_xml(
                    *passed_navigation, (config, ""), (chinese, ""), (english, ""),
                )
                with patch.object(run_local_ci, "build_steps", return_value=make_step(missing_engine)):
                    self.assertEqual(1, run_local_ci.run(args))
                    summary = json.loads((Path(temporary) / "local-ci-summary.json").read_text())
                    self.assertEqual("FAIL", summary["result"])
                    self.assertEqual(8, summary["junit"]["tests"])
                    self.assertEqual(0, summary["junit"]["skipped"])

                skipped_engine = suite_xml(
                    *passed_navigation, (config, ""), (engine, "<skipped/>"),
                    (chinese, ""), (english, ""),
                )
                with patch.object(run_local_ci, "build_steps", return_value=make_step(skipped_engine)):
                    self.assertEqual(1, run_local_ci.run(args))
                    summary = json.loads((Path(temporary) / "local-ci-summary.json").read_text())
                    self.assertEqual("FAIL", summary["result"])
                    self.assertEqual(9, summary["junit"]["tests"])
                    self.assertEqual(1, summary["junit"]["skipped"])

                pass_xml = suite_xml(
                    *passed_navigation, (config, ""), (engine, ""),
                    (chinese, ""), (english, ""),
                )
                with patch.object(run_local_ci, "build_steps", return_value=make_step(pass_xml)):
                    self.assertEqual(0, run_local_ci.run(args))
                    summary = json.loads((Path(temporary) / "local-ci-summary.json").read_text())
                    self.assertEqual("PASS", summary["result"])
                    self.assertEqual(9, summary["junit"]["tests"])

                for missing in navigation:
                    incomplete = suite_xml(
                        *((case, "") for case in run_local_ci.DESKTOP_REQUIRED_TESTS
                          if case != missing)
                    )
                    with self.subTest(missing=missing), patch.object(
                        run_local_ci, "build_steps", return_value=make_step(incomplete)
                    ):
                        self.assertEqual(1, run_local_ci.run(args))


    def test_engine_process_gate_rejects_missing_or_skipped_required_cases(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            subprocess.run(["git", "init", "-q", temporary], check=True)
            subprocess.run(
                [
                    "git",
                    "-C",
                    temporary,
                    "-c",
                    "user.name=Test",
                    "-c",
                    "user.email=test@example.invalid",
                    "commit",
                    "--allow-empty",
                    "-qm",
                    "test fixture",
                ],
                check=True,
            )
            process_reports = root / "target/engine-process-smoke/surefire-reports"
            desktop_report = root / "target/desktop-smoke/surefire-reports/TEST-stale.xml"
            java_report = root / "target/surefire-reports/TEST-stale.xml"
            process_reports.mkdir(parents=True)
            desktop_report.parent.mkdir(parents=True)
            java_report.parent.mkdir(parents=True)
            desktop_report.write_text("desktop", encoding="utf-8")
            java_report.write_text("java", encoding="utf-8")

            def suite_xml(
                cases: tuple[tuple[str, str], ...], skipped: tuple[str, str] | None = None
            ) -> str:
                testcases = "".join(
                    f'<testcase classname="{classname}" name="{method}">'
                    + ("<skipped/>" if (classname, method) == skipped else "")
                    + "</testcase>"
                    for classname, method in cases
                )
                skipped_count = int(skipped is not None)
                return (
                    f'<testsuite tests="{len(cases)}" skipped="{skipped_count}">'
                    f"{testcases}</testsuite>"
                )

            def run_with_report(xml_content: str) -> tuple[int, dict[str, object]]:
                script = (
                    "import pathlib; "
                    f"p = pathlib.Path(r'{process_reports}'); "
                    "p.mkdir(parents=True, exist_ok=True); "
                    f"(p / 'TEST-process.xml').write_text('''{xml_content}''', encoding='utf-8')"
                )
                args = run_local_ci.parse_args(
                    [
                        "--profile",
                        "portable",
                        "--group",
                        "engine-process",
                        "--summary-dir",
                        str(root / "summary"),
                    ]
                )
                with (
                    patch.object(run_local_ci, "resolve_maven", return_value="mvn"),
                    patch.object(run_local_ci, "java_major_version", return_value=(21, "Java21")),
                    patch.object(
                        run_local_ci,
                        "build_steps",
                        return_value=[
                            run_local_ci.Step(
                                "write process reports",
                                (run_local_ci.sys.executable, "-c", script),
                            )
                        ],
                    ),
                    patch.object(run_local_ci, "REPO_ROOT", root),
                    patch.dict(os.environ, {"DISPLAY": ":99"}),
                ):
                    result = run_local_ci.run(args)
                summary = json.loads(
                    (root / "summary/local-ci-summary.json").read_text(encoding="utf-8")
                )
                return result, summary

            required = run_local_ci.ENGINE_PROCESS_REQUIRED_TESTS
            result, summary = run_with_report(suite_xml(required[:-1]))
            self.assertEqual(1, result)
            self.assertEqual("FAIL", summary["result"])
            self.assertEqual(6, summary["junit"]["tests"])
            self.assertTrue(desktop_report.is_file())
            self.assertTrue(java_report.is_file())

            result, summary = run_with_report(suite_xml(required, required[-1]))
            self.assertEqual(1, result)
            self.assertEqual("FAIL", summary["result"])
            self.assertEqual(1, summary["junit"]["skipped"])

            result, summary = run_with_report(suite_xml(required))
            self.assertEqual(0, result)
            self.assertEqual("PASS", summary["result"])
            self.assertEqual(7, summary["junit"]["tests"])

    def test_desktop_and_java_and_nonjava_report_isolation(self):
        with tempfile.TemporaryDirectory() as temporary:
            subprocess.run(["git", "init", "-q", temporary], check=True)
            subprocess.run(
                ["git", "-C", temporary, "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                 "commit", "--allow-empty", "-qm", "test fixture"], check=True,
            )
            target = Path(temporary) / "target"
            java_reports = target / "surefire-reports"
            desktop_reports = target / "desktop-smoke" / "surefire-reports"
            java_reports.mkdir(parents=True)
            desktop_reports.mkdir(parents=True)
            java_stale = java_reports / "TEST-java-stale.xml"
            desktop_stale = desktop_reports / "TEST-desktop-stale.xml"
            java_stale.write_text("stale-java", encoding="utf-8")
            desktop_stale.write_text("stale-desktop", encoding="utf-8")

            pass_xml = (
                f'<testsuite tests="{len(run_local_ci.DESKTOP_REQUIRED_TESTS)}">'
                + "".join(
                    f'<testcase classname="{classname}" name="{method}"/>'
                    for classname, method in run_local_ci.DESKTOP_REQUIRED_TESTS
                )
                + "</testsuite>"
            )
            write_script = (
                "import pathlib; "
                f"p = pathlib.Path(r'{desktop_reports}'); "
                "p.mkdir(parents=True, exist_ok=True); "
                f"(p / 'TEST-pass.xml').write_text('''{pass_xml}''', encoding='utf-8')"
            )
            desktop_step = [
                run_local_ci.Step(
                    "write desktop reports",
                    (run_local_ci.sys.executable, "-c", write_script),
                )
            ]

            args_desktop = run_local_ci.parse_args([
                "--profile", "portable", "--group", "desktop", "--summary-dir", temporary,
            ])
            with (
                patch.object(run_local_ci, "resolve_maven", return_value="mvn"),
                patch.object(run_local_ci, "java_major_version", return_value=(21, "Java21")),
                patch.object(run_local_ci, "build_steps", return_value=desktop_step),
                patch.object(run_local_ci, "REPO_ROOT", Path(temporary)),
                patch.dict(os.environ, {"DISPLAY": ":99"}),
            ):
                self.assertEqual(0, run_local_ci.run(args_desktop))
                # Desktop stale was wiped, fresh written, and java stale is UNTOUCHED
                self.assertFalse(desktop_stale.exists())
                self.assertTrue(java_stale.is_file())
                self.assertEqual("stale-java", java_stale.read_text(encoding="utf-8"))
                self.assertTrue((desktop_reports / "TEST-pass.xml").is_file())

            # Non-Java group preserves all reports and avoids Java
            noop_step = [run_local_ci.Step("noop", (run_local_ci.sys.executable, "-c", "pass"))]
            args_repo = run_local_ci.parse_args([
                "--profile", "portable", "--group", "repository", "--summary-dir", temporary,
            ])
            with patch.object(run_local_ci, "build_steps", return_value=noop_step):
                self.assertEqual(0, run_local_ci.run(args_repo))
                self.assertTrue(java_stale.is_file())
                self.assertEqual("stale-java", java_stale.read_text(encoding="utf-8"))
                self.assertTrue((desktop_reports / "TEST-pass.xml").is_file())
                summary = json.loads((Path(temporary) / "local-ci-summary.json").read_text())
                self.assertEqual("not executed", summary["junit_status"])
                self.assertEqual("not executed", summary["java"])


if __name__ == "__main__":
    unittest.main()

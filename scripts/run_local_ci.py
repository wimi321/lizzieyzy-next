#!/usr/bin/env python3
"""Run the same preflight gates locally and in GitHub Actions."""

from __future__ import annotations

import argparse
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import platform
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import time
from typing import Iterable, Mapping, Sequence
import xml.etree.ElementTree as ET


REPO_ROOT = Path(__file__).resolve().parents[1]
JAVA_REQUIRED_TESTS = (
    ("featurecat.lizzie.logging.LoggingProviderSmokeIT", "shadedArtifactWritesOneProviderEvent"),
)
DESKTOP_REQUIRED_TESTS = (
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
    ("featurecat.lizzie.gui.OfflineBoardAcceptanceTest", "editsAndImportsWithNoEngine"),
)
ENGINE_PROCESS_REQUIRED_TESTS = (
    (
        "featurecat.lizzie.gui.EngineProcessSmokeTest",
        "restoresSnapshotAnalyzesAndQuits",
    ),
    (
        "featurecat.lizzie.gui.EngineProcessFailureTest",
        "rejectsSnapshotErrorWithoutTailOrAnalysis",
    ),
    (
        "featurecat.lizzie.gui.EngineProcessFailureTest",
        "retiresSnapshotTimeoutAndRejectsLateResponse",
    ),
    (
        "featurecat.lizzie.gui.EngineProcessFailureTest",
        "recoversSnapshotAfterPeerCrash",
    ),
    (
        "featurecat.lizzie.gui.EngineProcessFailureTest",
        "isolatesLateOutputAfterEngineSwitch",
    ),
    (
        "featurecat.lizzie.gui.EngineProcessFailureTest",
        "drainsPeerPipeBurstAndRemainsResponsive",
    ),
    (
        "featurecat.lizzie.gui.EngineProcessFailureTest",
        "cleansUpPeerThatRefusesQuit",
    ),
)
TENSORRT_UI_REQUIRED_TESTS = (
    (
        "featurecat.lizzie.gui.TensorRtRepairAcceptanceTest",
        "englishRepairFlow",
    ),
    (
        "featurecat.lizzie.gui.TensorRtRepairAcceptanceTest",
        "chineseRepairFlow",
    ),
)



PY_COMPILE_FILES = (
    "scripts/katago_source_targets.py",
    "scripts/audit_katago_source_bundle.py",
    "scripts/test_audit_katago_source_bundle.py",
    "scripts/prepare_cpu_engine_acceptance.py",
    "scripts/test_prepare_cpu_engine_acceptance.py",
    "scripts/test_run_acceptance.py",
    "scripts/build_katago_cuda_dependencies.py",
    "scripts/test_build_katago_cuda.py",
    "scripts/build_katago_tensorrt_dependencies.py",
    "scripts/build_katago_rocm_dependencies.py",
    "scripts/test_build_katago_rocm.py",
    "scripts/test_build_katago_tensorrt.py",
    "scripts/build_katago_directml_dependencies.py",
    "scripts/build_katago_openvino_dependencies.py",
    "scripts/test_build_katago_openvino.py",
    "scripts/test_build_katago_directml.py",
    "scripts/build_katago_source.py",
    "scripts/build_katago_windows_dependencies.py",
    "scripts/package_katago_source_windows.py",
    "scripts/test_build_katago_windows.py",
    "scripts/build_katago_linux_dependencies.py",
    "scripts/package_katago_source_linux.py",
    "scripts/test_build_katago_linux.py",
    "scripts/build_katago_linux_cuda_dependencies.py",
    "scripts/package_katago_source_linux_cuda.py",
    "scripts/test_build_katago_linux_cuda.py",
    "scripts/build_katago_macos_dependencies.py",
    "scripts/test_build_katago_macos_dependencies.py",
    "scripts/package_katago_source_macos.py",
    "scripts/test_package_katago_source_macos.py",
    "scripts/probe_katago_focus.py",
    "scripts/test_build_katago_source.py",
    "scripts/katago_asset_catalog.py",
    "scripts/test_katago_asset_catalog.py",
    "scripts/stage_katago_source_release.py",
    "scripts/audit_katago_linux_compatibility.py",
    "scripts/test_audit_katago_linux_compatibility.py",
    "scripts/prepare_katago_source_assets.py",
    "scripts/test_prepare_katago_source_assets.py",
    "scripts/test_stage_katago_source_release.py",
    "scripts/test_probe_katago_focus.py",
    "scripts/audit_katago_binary_version.py",
    "scripts/audit_katago_package_metadata.py",
    "scripts/generate_release_notes.py",
    "scripts/macos_bundle_version.py",
    "scripts/macos_katago_bundle.py",
    "scripts/package_runtime_tools.py",
    "scripts/prepare_bundled_jcef.py",
    "scripts/prepare_bundled_nvidia_runtime.py",
    "scripts/publish_release_request.py",
    "scripts/r2_release.py",
    "scripts/release_asset_provenance.py",
    "scripts/release_asset_topology.py",
    "scripts/run_local_ci.py",
    "scripts/summarize_jfr.py",
    "scripts/measure_analysis.py",
    "scripts/test_measure_analysis.py",
    "scripts/test_audit_katago_binary_version.py",
    "scripts/test_audit_katago_package_metadata.py",
    "scripts/test_generate_release_notes.py",
    "scripts/test_macos_drag_dmg_script.py",
    "scripts/test_macos_bundle_version.py",
    "scripts/test_macos_katago_bundle.py",
    "scripts/test_prepare_bundled_nvidia_runtime.py",
    "scripts/test_publish_release_request.py",
    "scripts/test_release_asset_provenance.py",
    "scripts/test_release_asset_topology.py",
    "scripts/test_r2_release.py",
    "scripts/test_run_local_ci.py",
    "scripts/test_validate_release_notes.py",
    "scripts/test_validate_windows_release_assets.py",
    "scripts/test_validate_release_workflow_identity.py",
    "scripts/upload_release_assets.py",
    "scripts/test_upload_release_assets.py",
    "scripts/transfer_pinned_source_assets.py",
    "scripts/test_transfer_pinned_source_assets.py",
    "scripts/verify_saved_release_assets.py",
    "scripts/test_verify_saved_release_assets.py",
    "scripts/test_windows_launcher_packaging.py",
    "scripts/test_windows_ci_diagnostics.py",
    "scripts/validate_release_notes.py",
    "scripts/validate_windows_release_assets.py",
    "scripts/validate_release_workflow_identity.py",
)

DIRECT_PYTHON_TESTS = (
    "scripts/test_measure_analysis.py",
    "scripts/test_audit_katago_source_bundle.py",
    "scripts/test_audit_katago_linux_compatibility.py",
    "scripts/test_prepare_katago_source_assets.py",
    "scripts/test_prepare_cpu_engine_acceptance.py",
    "scripts/test_run_acceptance.py",
    "scripts/test_build_katago_cuda.py",
    "scripts/test_build_katago_tensorrt.py",
    "scripts/test_build_katago_rocm.py",
    "scripts/test_build_katago_directml.py",
    "scripts/test_build_katago_openvino.py",
    "scripts/test_build_katago_windows.py",
    "scripts/test_build_katago_linux.py",
    "scripts/test_build_katago_linux_cuda.py",
    "scripts/test_build_katago_source.py",
    "scripts/test_katago_asset_catalog.py",
    "scripts/test_stage_katago_source_release.py",
    "scripts/test_build_katago_macos_dependencies.py",
    "scripts/test_package_katago_source_macos.py",
    "scripts/test_probe_katago_focus.py",
    "scripts/test_generate_release_notes.py",
    "scripts/test_audit_katago_binary_version.py",
    "scripts/test_audit_katago_package_metadata.py",
    "scripts/test_macos_drag_dmg_script.py",
    "scripts/test_macos_bundle_version.py",
    "scripts/test_macos_katago_bundle.py",
    "scripts/test_prepare_bundled_jcef.py",
    "scripts/test_prepare_bundled_nvidia_runtime.py",
    "scripts/test_windows_launcher_packaging.py",
)

UNITTEST_MODULES = (
    "scripts.test_publish_release_request",
    "scripts.test_release_asset_provenance",
    "scripts.test_release_asset_topology",
    "scripts.test_run_local_ci",
    "scripts.test_validate_release_notes",
    "scripts.test_macos_signing_security",
    "scripts.test_r2_release",
    "scripts.test_validate_windows_release_assets",
    "scripts.test_validate_release_workflow_identity",
    "scripts.test_upload_release_assets",
    "scripts.test_transfer_pinned_source_assets",
    "scripts.test_verify_saved_release_assets",
)

BASH_SYNTAX_FILES = (
    "scripts/create_macos_drag_dmg.sh",
    "scripts/prepare_bundled_katago.sh",
    "scripts/test_prepare_bundled_katago.sh",
    "scripts/package_macos_dmg.sh",
    "scripts/validate_macos_dmg_layout.sh",
    "scripts/package_release.sh",
    "scripts/package_windows_exe.sh",
    "scripts/run_jfr_benchmark.sh",
    "scripts/run_local_ci.sh",
    "scripts/sign_macos_release.sh",
    "scripts/sign_macos_release_with_retry.sh",
    "scripts/validate_release_assets.sh",
)


@dataclass(frozen=True)
class Step:
    name: str
    command: tuple[str, ...]
    env: dict[str, str] | None = None
    group: str = "scripts"


@dataclass
class StepResult:
    name: str
    command: list[str]
    status: str
    exit_code: int | None
    duration_seconds: float


@dataclass(frozen=True)
class JunitSummary:
    suites: int = 0
    tests: int = 0
    failures: int = 0
    errors: int = 0
    skipped: int = 0

    def plus(self, other: "JunitSummary") -> "JunitSummary":
        return JunitSummary(
            suites=self.suites + other.suites,
            tests=self.tests + other.tests,
            failures=self.failures + other.failures,
            errors=self.errors + other.errors,
            skipped=self.skipped + other.skipped,
        )


class RequiredJunitExecutionError(RuntimeError):
    def __init__(self, message: str, summary: JunitSummary):
        super().__init__(message)
        self.summary = summary


def command_display(command: Sequence[str]) -> str:
    return subprocess.list2cmdline(list(command)) if os.name == "nt" else " ".join(
        subprocess.list2cmdline([part]) for part in command
    )


def executable_from_env_or_path(env_name: str, names: Iterable[str]) -> str | None:
    configured = os.environ.get(env_name, "").strip()
    if configured:
        return configured
    for name in names:
        resolved = shutil.which(name)
        if resolved:
            return resolved
    return None


def resolve_maven() -> str:
    resolved = executable_from_env_or_path(
        "LIZZIE_MAVEN", ("mvn.cmd", "mvn.exe", "mvn")
    )
    if resolved:
        return resolved
    patterns = (
        REPO_ROOT / ".tools" / "apache-maven-*" / "bin" / "mvn.cmd",
        REPO_ROOT / ".tools" / "apache-maven-*" / "bin" / "mvn",
    )
    if os.name == "nt":
        patterns += (Path("C:/tools/apache-maven-*/bin/mvn.cmd"),)
    for pattern in patterns:
        matches = sorted(pattern.parent.parent.parent.glob(pattern.parent.parent.name + "/bin/" + pattern.name))
        if matches:
            return str(matches[-1])
    raise RuntimeError(
        "Maven was not found. Set LIZZIE_MAVEN or add mvn to PATH."
    )


def resolve_bash() -> str:
    configured = os.environ.get("LIZZIE_BASH", "").strip()
    if configured:
        return configured
    if os.name == "nt":
        program_files_roots = (
            os.environ.get("ProgramFiles", "C:/Program Files"),
            os.environ.get("ProgramFiles(x86)", "C:/Program Files (x86)"),
        )
        for root in program_files_roots:
            candidate = Path(root) / "Git/bin/bash.exe"
            if candidate.is_file():
                return str(candidate)
    resolved = shutil.which("bash")
    if resolved and not is_windows_wsl_bash_launcher(resolved):
        return resolved
    raise RuntimeError("bash was not found. Set LIZZIE_BASH or add Git Bash to PATH.")


def is_windows_wsl_bash_launcher(path: str, *, windows: bool | None = None) -> bool:
    if windows is None:
        windows = os.name == "nt"
    if not windows:
        return False
    normalized = path.replace("\\", "/").casefold()
    return normalized.endswith("/windows/system32/bash.exe") or normalized.endswith(
        "/windows/sysnative/bash.exe"
    )


def resolve_powershell() -> str:
    resolved = executable_from_env_or_path(
        "LIZZIE_POWERSHELL", ("pwsh", "powershell.exe", "powershell")
    )
    if not resolved:
        raise RuntimeError("PowerShell was not found.")
    return resolved


def java_major_version(maven: str) -> tuple[int, str]:
    completed = subprocess.run(
        [maven, "-version"],
        cwd=REPO_ROOT,
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        encoding="utf-8",
        errors="replace",
    )
    output = completed.stdout.strip()
    if completed.returncode != 0:
        raise RuntimeError(f"Unable to run Maven:\n{output}")
    match = re.search(r"Java version:\s*([0-9]+)", output)
    if not match:
        raise RuntimeError(f"Unable to determine Maven Java version:\n{output}")
    return int(match.group(1)), output


def windows_steps(maven: str, powershell: str) -> list[Step]:
    python = sys.executable
    temp = Path(tempfile.gettempdir()) / f"lizzieyzy-next-local-ci-{os.getpid()}"
    parser_script = (
        "$tokens=$null; $errors=$null; "
        "[System.Management.Automation.Language.Parser]::ParseFile("
        "(Resolve-Path 'scripts/windows_rtx50_analysis_benchmark.ps1'),"
        "[ref]$tokens,[ref]$errors)|Out-Null; "
        "if($errors.Count -gt 0){$errors|Format-List|Out-String|Write-Error; exit 1}"
    )
    return [
        Step("Verify repository line endings", (python, "scripts/check_line_endings.py"), group="repository"),
        Step("Verify bundled JCEF logic", (python, "scripts/test_prepare_bundled_jcef.py")),
        Step("Verify CPU acceptance provisioning", (python, "scripts/test_prepare_cpu_engine_acceptance.py")),
        Step("Verify acceptance runner outcomes", (python, "scripts/test_run_acceptance.py")),
        Step(
            "Verify bundled NVIDIA runtime packaging",
            (python, "scripts/test_prepare_bundled_nvidia_runtime.py"),
        ),
        Step(
            "Parse RTX 50 benchmark PowerShell",
            (powershell, "-NoProfile", "-Command", parser_script),
        ),
        Step(
            "Verify Windows CI process supervision",
            (python, "-m", "unittest", "scripts.test_windows_ci_diagnostics"),
        ),
        Step(
            "Verify Windows product acceptance fixtures",
            (python, "-m", "unittest", "scripts.test_windows_product_acceptance"),
            env={"LIZZIE_WINDOWS_PRODUCT_TESTS_REQUIRED": "1"},
        ),
        Step(
            "Verify Windows credential persistence",
            (
                maven,
                "-B",
                "-Dfmt.skip=true",
                f"-Dlizzie.work.dir={temp / 'credential-tests'}",
                "-Dtest=PlatformCredentialStoreTest,RemoteComputeConfigTest,MigratingCredentialStoreTest",
                "test",
            ),
            group="java",
        ),
        Step(
            "Run full Windows verification gate",
            (
                maven,
                "-B",
                "-Dfmt.skip=true",
                "-Djava.awt.headless=true",
                f"-Dlizzie.work.dir={temp / 'full-tests'}",
                "-DskipTests=false",
                "-DskipITs=false",
                "verify",
            ),
            group="java",
        ),
    ]


def portable_steps(maven: str, bash: str) -> list[Step]:
    python = sys.executable
    steps = [
        Step("Test line-ending checker", (python, "scripts/test_check_line_endings.py"), group="repository"),
        Step("Verify repository line endings", (python, "scripts/check_line_endings.py"), group="repository"),
        Step("Verify local Markdown links", (python, "scripts/check_markdown_links.py"), group="repository"),
        Step("Compile release helper Python", (python, "-m", "py_compile", *PY_COMPILE_FILES)),
    ]
    steps.extend(
        Step(f"Run {Path(script).name}", (python, script)) for script in DIRECT_PYTHON_TESTS
    )
    steps.append(
        Step(
            "Verify bundled KataGo shell logic",
            bash_login_command(bash, "scripts/test_prepare_bundled_katago.sh"),
            env={"PYTHON_BIN": python},
        )
    )
    steps.extend(
        Step(
            f"Run {module}",
            (python, "-m", "unittest", module),
            env={"LIZZIE_BASH": bash, "LIZZIE_PYTHON": python},
        )
        for module in UNITTEST_MODULES
    )
    steps.extend(
        Step(
            f"Parse {script}",
            bash_login_command(bash, "bash", "-n", script),
        )
        for script in BASH_SYNTAX_FILES
    )
    steps.append(
        Step(
            "Run full portable verification gate",
            (
                maven,
                "-B",
                "-Dfmt.skip=true",
                "-Djava.awt.headless=true",
                "-DskipTests=false",
                "-DskipITs=false",
                "verify",
            ),
            group="java",
        )
    )
    return steps


def bash_login_command(bash: str, *command: str) -> tuple[str, ...]:
    # Git Bash only adds its usr/bin tools for a login shell when launched
    # programmatically from a regular Windows process.
    return (bash, "-lc", shlex.join(command))


def deduplicate_steps(steps: Iterable[Step]) -> list[Step]:
    result: list[Step] = []
    seen: set[tuple[str, ...]] = set()
    for step in steps:
        if step.command in seen:
            continue
        seen.add(step.command)
        result.append(step)
    return result


def build_steps(
    profile: str, maven: str, bash: str | None, powershell: str | None,
    group: str = "all",
) -> list[Step]:
    if group == "tensorrt-ui":
        if profile != "windows":
            raise RuntimeError("The tensorrt-ui group requires --profile windows.")
        evidence_dir = REPO_ROOT / "target" / "tensorrt-ui" / "probes"
        reports_dir = REPO_ROOT / "target" / "tensorrt-ui" / "surefire-reports"
        maven_log = REPO_ROOT / "target" / "tensorrt-ui" / "maven.log"
        return [
            Step(
                "Run TensorRT UI acceptance",
                (
                    maven,
                    "-B",
                    "--log-file",
                    str(maven_log),
                    "-Dfmt.skip=true",
                    "-Djacoco.skip=true",
                    "-Djava.awt.headless=false",
                    "-Dlizzie.desktop.required=true",
                    f"-Dlizzie.desktop.evidence.dir={evidence_dir}",
                    f"-Dsurefire.reportsDirectory={reports_dir}",
                    "-Dtest=TensorRtRepairAcceptanceTest#englishRepairFlow+chineseRepairFlow",
                    "test",
                ),
                group="tensorrt-ui",
            )
        ]

    if group == "desktop":
        evidence_dir = REPO_ROOT / "target" / "desktop-smoke" / "probes"
        reports_dir = REPO_ROOT / "target" / "desktop-smoke" / "surefire-reports"
        return [
            Step(
                "Run desktop smoke tests",
                (
                    maven,
                    "-B",
                    "-Dfmt.skip=true",
                    "-Djava.awt.headless=false",
                    "-Dlizzie.desktop.required=true",
                    f"-Dlizzie.desktop.evidence.dir={evidence_dir}",
                    f"-Dsurefire.reportsDirectory={reports_dir}",
                    "-Dtest=FunctionSearchNavigationTest,ConfigDialog2NavigationTest,EngineProcessSmokeTest,FunctionSearchInputTest,OfflineBoardAcceptanceTest",
                    "test",
                ),
                group="desktop",
            )
        ]
    if group == "engine-process":
        evidence_dir = REPO_ROOT / "target" / "engine-process-smoke" / "probes"
        reports_dir = REPO_ROOT / "target" / "engine-process-smoke" / "surefire-reports"
        test_selection = (
            "-Dtest=EngineProcessSmokeTest#restoresSnapshotAnalyzesAndQuits,"
            "EngineProcessFailureTest#rejectsSnapshotErrorWithoutTailOrAnalysis+"
            "retiresSnapshotTimeoutAndRejectsLateResponse+recoversSnapshotAfterPeerCrash+"
            "isolatesLateOutputAfterEngineSwitch+drainsPeerPipeBurstAndRemainsResponsive+"
            "cleansUpPeerThatRefusesQuit"
        )
        return [
            Step(
                "Run engine process tests",
                (
                    maven,
                    "-B",
                    "-Dfmt.skip=true",
                    "-Djava.awt.headless=false",
                    "-Dlizzie.desktop.required=true",
                    f"-Dlizzie.desktop.evidence.dir={evidence_dir}",
                    f"-Dsurefire.reportsDirectory={reports_dir}",
                    test_selection,
                    "test",
                ),
                group="engine-process",
            )
        ]
    if group in {"all", "scripts"}:
        if profile in {"portable", "all"} and bash is None:
            raise RuntimeError("The selected scripts require bash.")
        if profile in {"windows", "all"} and powershell is None:
            raise RuntimeError("The selected scripts require PowerShell.")
    if profile == "windows":
        steps = windows_steps(maven, powershell or "pwsh")
    elif profile == "portable":
        steps = portable_steps(maven, bash or "bash")
    else:
        steps = windows_steps(maven, powershell or "pwsh") + portable_steps(maven, bash or "bash")
        # A local Windows run cannot become an Ubuntu run by invoking Maven twice.
        steps = [step for step in steps if step.name != "Run full portable verification gate"]
        steps = deduplicate_steps(steps)
    return [step for step in steps if group == "all" or step.group == group]


def git_output(*args: str) -> str:
    completed = subprocess.run(
        ["git", *args],
        cwd=REPO_ROOT,
        check=True,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        encoding="utf-8",
        errors="replace",
    )
    return completed.stdout.strip()


def require_clean_checkout() -> None:
    status = git_output("status", "--porcelain", "--untracked-files=all")
    if status:
        raise RuntimeError(f"Checkout is dirty:\n{status}")


def parse_suite(element: ET.Element) -> JunitSummary:
    if element.tag.endswith("testsuites"):
        total = JunitSummary()
        for child in element:
            if child.tag.endswith("testsuite"):
                total = total.plus(parse_suite(child))
        return total
    return JunitSummary(
        suites=1,
        tests=int(element.attrib.get("tests", "0")),
        failures=int(element.attrib.get("failures", "0")),
        errors=int(element.attrib.get("errors", "0")),
        skipped=int(element.attrib.get("skipped", "0")),
    )


def collect_junit_summary(
    root: Path = REPO_ROOT / "target",
    required_tests: Sequence[tuple[str, str]] = (),
) -> JunitSummary:
    summary = JunitSummary()
    missing = set(required_tests)
    required_classes = {classname for classname, _ in required_tests}
    unsuccessful: list[str] = []
    files: list[Path] = []
    for directory in (root / "surefire-reports", root / "failsafe-reports"):
        if directory.is_dir():
            files.extend(sorted(directory.glob("TEST-*.xml")))
    for path in files:
        try:
            report = ET.parse(path).getroot()
            summary = summary.plus(parse_suite(report))
            for case in report.iter("testcase"):
                classname = case.attrib.get("classname", "")
                if classname not in required_classes:
                    continue
                name = case.attrib.get("name", "")
                if any(case.find(status) is not None for status in ("skipped", "failure", "error")):
                    unsuccessful.append(f"{classname}.{name}")
                else:
                    missing.discard((classname, name))
        except (ET.ParseError, OSError, ValueError) as error:
            raise RuntimeError(f"Unable to parse JUnit report {path}: {error}") from error
    if missing or unsuccessful:
        details = []
        if missing:
            details.append("missing successful cases: " + ", ".join(f"{c}.{n}" for c, n in sorted(missing)))
        if unsuccessful:
            details.append("unsuccessful required-class cases: " + ", ".join(unsuccessful))
        raise RequiredJunitExecutionError(
            "Required JUnit execution not satisfied: " + "; ".join(details), summary
        )
    return summary


def reset_junit_reports(root: Path = REPO_ROOT / "target") -> None:
    for directory in (root / "surefire-reports", root / "failsafe-reports"):
        if directory.exists():
            shutil.rmtree(directory)




def require_successful_job_results(results: Mapping[str, str]) -> None:
    rejected = [f"{name}={result}" for name, result in results.items() if result != "success"]
    if rejected:
        raise RuntimeError("Required CI jobs did not succeed: " + ", ".join(rejected))


def overall_result(success: bool, results: Sequence[StepResult]) -> str:
    if success and all(result.status in {"passed", "planned"} for result in results):
        return "PASS"
    return "FAIL"


def write_summary(
    output_dir: Path,
    profile: str,
    dry_run: bool,
    started_at: str,
    duration_seconds: float,
    java_details: str,
    results: list[StepResult],
    junit: JunitSummary,
    success: bool,
    group: str,
    junit_executed: bool,
) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    status = overall_result(success, results)
    payload = {
        "schema_version": 1,
        "result": status,
        "profile": profile,
        "group": group,
        "dry_run": dry_run,
        "started_at": started_at,
        "duration_seconds": round(duration_seconds, 3),
        "platform": platform.platform(),
        "python": sys.version.split()[0],
        "java": java_details,
        "git_sha": git_output("rev-parse", "HEAD"),
        "junit": asdict(junit),
        "junit_status": "collected" if junit_executed else "not executed",
        "steps": [asdict(result) for result in results],
    }
    (output_dir / "local-ci-summary.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    lines = [
        "# Local CI summary",
        "",
        f"- Result: **{status}**",
        f"- Profile: `{profile}`",
        f"- Group: `{group}`",
        f"- Java: {java_details}",
        f"- Git SHA: `{payload['git_sha']}`",
        f"- Duration: `{duration_seconds:.1f}s`",
        (
            "- JUnit: "
            f"{junit.tests} tests, {junit.failures} failures, "
            f"{junit.errors} errors, {junit.skipped} skipped"
            if junit_executed else "- JUnit: not executed"
        ),
        "",
        "| Step | Result | Seconds |",
        "| --- | --- | ---: |",
    ]
    lines.extend(
        f"| {result.name.replace('|', '/')} | {result.status} | {result.duration_seconds:.1f} |"
        for result in results
    )
    (output_dir / "local-ci-summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def run(args: argparse.Namespace) -> int:
    started_clock = time.monotonic()
    started_at = datetime.now(timezone.utc).isoformat()
    output_dir = (REPO_ROOT / args.summary_dir).resolve()
    results: list[StepResult] = []
    java_selected = args.group in {"all", "java"}
    desktop_selected = args.group == "desktop"
    engine_process_selected = args.group == "engine-process"
    tensorrt_ui_selected = args.group == "tensorrt-ui"
    display_selected = desktop_selected or engine_process_selected or tensorrt_ui_selected
    scripts_selected = args.group in {"all", "scripts"}
    needs_java = java_selected or display_selected
    java_details = "not executed"
    junit_executed = False

    try:
        if args.require_clean:
            require_clean_checkout()
        if tensorrt_ui_selected and args.profile != "windows":
            raise RuntimeError("The tensorrt-ui group requires --profile windows.")
        if display_selected and not args.dry_run:
            if sys.platform.startswith("linux") and not os.environ.get("DISPLAY", "").strip():
                raise RuntimeError(
                    "Display CI requires DISPLAY on Linux. Start Xvfb or set DISPLAY."
                )
        if java_selected and not args.dry_run:
            reset_junit_reports()
            junit_executed = True
        elif tensorrt_ui_selected and not args.dry_run:
            tensorrt_root = REPO_ROOT / "target" / "tensorrt-ui"
            if tensorrt_root.exists():
                shutil.rmtree(tensorrt_root)
            tensorrt_root.mkdir(parents=True, exist_ok=True)
            junit_executed = True
        elif display_selected and not args.dry_run:
            report_root = "desktop-smoke" if desktop_selected else "engine-process-smoke"
            reset_junit_reports(REPO_ROOT / "target" / report_root)
            junit_executed = True
        maven = (args.maven or ("mvn" if args.dry_run else resolve_maven())) if needs_java else "mvn"
        bash = None
        powershell = None
        if scripts_selected and args.profile in {"portable", "all"}:
            bash = args.bash or ("bash" if args.dry_run else resolve_bash())
        if scripts_selected and args.profile in {"windows", "all"}:
            powershell = args.powershell or (
                "pwsh" if args.dry_run else resolve_powershell()
            )
        if needs_java and not args.dry_run:
            major, java_details = java_major_version(maven)
            if major != 21:
                raise RuntimeError(
                    f"Local CI requires JDK 21, but Maven is using Java {major}. "
                    "Set JAVA_HOME to a JDK 21 installation."
                )
        steps = build_steps(args.profile, maven, bash, powershell, args.group)
        steps.append(Step("Verify working-tree diff", ("git", "diff", "--check")))

        for index, step in enumerate(steps, start=1):
            print(f"[{index}/{len(steps)}] {step.name}", flush=True)
            print(f"  {command_display(step.command)}", flush=True)
            if args.dry_run:
                results.append(
                    StepResult(step.name, list(step.command), "planned", None, 0.0)
                )
                continue
            step_started = time.monotonic()
            completed = subprocess.run(
                step.command,
                cwd=REPO_ROOT,
                env={
                    **os.environ,
                    "PYTHONIOENCODING": "utf-8",
                    "PYTHONUTF8": "1",
                    **(step.env or {}),
                },
                check=False,
            )
            elapsed = time.monotonic() - step_started
            status = "passed" if completed.returncode == 0 else "failed"
            results.append(
                StepResult(
                    step.name,
                    list(step.command),
                    status,
                    completed.returncode,
                    round(elapsed, 3),
                )
            )
            if completed.returncode != 0:
                raise RuntimeError(
                    f"Step failed with exit code {completed.returncode}: {step.name}"
                )
        if tensorrt_ui_selected and not args.dry_run:
            maven_log = REPO_ROOT / "target" / "tensorrt-ui" / "maven.log"
            if not maven_log.is_file() or maven_log.stat().st_size == 0:
                raise RuntimeError(
                    f"TensorRT UI acceptance did not preserve Maven output: {maven_log}"
                )
        if args.require_clean:
            require_clean_checkout()
        return_code = 0
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"Local CI failed: {error}", file=sys.stderr, flush=True)
        return_code = 1

    junit = JunitSummary()
    try:
        if junit_executed:
            if desktop_selected:
                required_tests = DESKTOP_REQUIRED_TESTS
                report_root = REPO_ROOT / "target" / "desktop-smoke"
            elif engine_process_selected:
                required_tests = ENGINE_PROCESS_REQUIRED_TESTS
                report_root = REPO_ROOT / "target" / "engine-process-smoke"
            elif tensorrt_ui_selected:
                required_tests = TENSORRT_UI_REQUIRED_TESTS
                report_root = REPO_ROOT / "target" / "tensorrt-ui"
            else:
                required_tests = JAVA_REQUIRED_TESTS
                report_root = None
            if report_root is None:
                junit = collect_junit_summary(required_tests=required_tests)
            else:
                junit = collect_junit_summary(report_root, required_tests=required_tests)
    except (OSError, RuntimeError) as error:
        if isinstance(error, RequiredJunitExecutionError):
            junit = error.summary
        print(f"Local CI failed: {error}", file=sys.stderr, flush=True)
        return_code = 1

    try:
        write_summary(
            output_dir,
            args.profile,
            args.dry_run,
            started_at,
            time.monotonic() - started_clock,
            java_details,
            results,
            junit,
            return_code == 0,
            args.group,
            junit_executed,
        )
        print(f"Local CI report: {output_dir}", flush=True)
        print(
            "JUnit: "
            f"{junit.tests} tests, {junit.failures} failures, "
            f"{junit.errors} errors, {junit.skipped} skipped" if junit_executed else "JUnit: not executed",
            flush=True,
        )
    except (OSError, RuntimeError) as error:
        print(f"Unable to write local CI report: {error}", file=sys.stderr, flush=True)
        return 1
    return return_code


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--profile", choices=("windows", "portable", "all"), default="all"
    )
    parser.add_argument(
        "--group",
        choices=(
            "all", "repository", "scripts", "java", "desktop", "engine-process", "tensorrt-ui"
        ),
        default="all",
    )
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--require-clean", action="store_true")
    parser.add_argument("--summary-dir", default="target/local-ci")
    parser.add_argument("--maven")
    parser.add_argument("--bash")
    parser.add_argument("--powershell")
    return parser.parse_args(argv)


if __name__ == "__main__":
    raise SystemExit(run(parse_args()))

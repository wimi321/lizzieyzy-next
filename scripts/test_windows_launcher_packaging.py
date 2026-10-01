#!/usr/bin/env python3
"""Static release guards for portable Windows JVM launchers."""

from pathlib import Path
import os
import shutil
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]


def verify_engine_staging(package_script: str) -> None:
    start = package_script.index("copy_bundle_engine_assets() {")
    end = package_script.index("\n}\n", start) + len("\n}\n")
    function = package_script[start:end]
    installer = package_script[package_script.index("build_installer() {"):]
    require(installer, 'rm -rf "$input_dir" "$installer_dir"', "fresh installer input")
    require(installer, '--type exe', "installer type")
    require(installer, '--verbose', "installer failure diagnostics")
    require(installer, '>&2 || return $?', "installer failure propagation")
    if os.name == "nt" or shutil.which("bash") is None:
        return  # The deterministic shell fixture runs in the POSIX script gate.
    with tempfile.TemporaryDirectory(prefix="engine staging ") as temporary:
        root = Path(temporary)
        source = root / "engines/katago/windows-x64-opencl"
        (source / "licenses/nested").mkdir(parents=True)
        (source / "licenses/nested/LICENSE").write_text("license")
        (source / "katago.exe").write_bytes(b"verified engine")
        (root / "weights").mkdir()
        (root / "weights/default.bin.gz").write_bytes(b"model")
        script = 'set -euo pipefail\nROOT_DIR="$1"\n' + function + '''
copy_bundle_engine_assets "$ROOT_DIR/input" windows-x64-opencl windows-x64
copy_bundle_engine_assets "$ROOT_DIR/input" windows-x64-opencl windows-x64
'''
        subprocess.run(["bash", "-c", script, "fixture", str(root)], check=True)
        destination = root / "input/engines/katago/windows-x64"
        actual = sorted(p.relative_to(destination).as_posix() for p in destination.rglob("*") if p.is_file())
        if actual != ["katago.exe", "licenses/nested/LICENSE"]:
            raise AssertionError(f"Repeated staging nested or duplicated the engine: {actual}")


def require(text: str, value: str, source: str) -> None:
    if value not in text:
        raise AssertionError(f"{source} is missing required launcher guard: {value}")


def verify_nvidia_dependency_origin(workflow: str) -> None:
    start = workflow.index("          for nvidia_engine in ")
    end = workflow.index("          done", start) + len("          done")
    block = "\n".join(line[10:] for line in workflow[start:end].splitlines())
    require(block, 'test -f "$nvidia_engine/z.dll"', "official zlib dependency")
    require(block, "scripts/audit_katago_source_bundle.py --target windows-nvidia", "source audit")
    if os.name == "nt" or shutil.which("bash") is None:
        return
    with tempfile.TemporaryDirectory(prefix="nvidia origin ") as temporary:
        root = Path(temporary)
        inputs = [root / "dist/windows/input-nvidia/engines/katago/windows-x64",
                  root / "dist/windows/app-image-nvidia/LizzieYzy Next NVIDIA/app/engines/katago/windows-x64"]
        for directory in inputs:
            directory.mkdir(parents=True)
        # Stub only the external command boundary; inventory validation has its own tests.
        script = '''set -euo pipefail
python3() {
  if [ "$1" = scripts/katago_asset_catalog.py ]; then printf '%s\\n' "$ORIGIN";
  else test "$1" = scripts/audit_katago_source_bundle.py; test "$AUDIT" = PASS; fi
}
''' + block

        def run(origin, audit="PASS"):
            return subprocess.run(["bash", "-c", script], cwd=root,
                                  env=dict(os.environ, ORIGIN=origin, AUDIT=audit)).returncode

        assert run("project-source-build") == 0
        assert run("project-source-build", "FAIL") != 0
        assert run("official-release") != 0
        for directory in inputs:
            (directory / "z.dll").touch()
        assert run("official-release") == 0
        for directory in inputs:
            (directory / "z.dll").unlink()
            assert run("official-release") != 0
            (directory / "z.dll").touch()


def main() -> None:
    package_script = (ROOT / "scripts/package_windows_exe.sh").read_text(encoding="utf-8")
    verify_engine_staging(package_script)
    runtime_tools = (ROOT / "scripts/package_runtime_tools.py").read_text(encoding="utf-8")
    smoke_script = (ROOT / "scripts/windows_smoke_test.ps1").read_text(encoding="utf-8")
    lizzie_source = (ROOT / "src/main/java/featurecat/lizzie/Lizzie.java").read_text(
        encoding="utf-8"
    )
    workflow = (ROOT / ".github/workflows/build-windows-release.yml").read_text(
        encoding="utf-8"
    )
    update_controller = (
        ROOT / "src/main/java/featurecat/lizzie/update/WindowsUpdateController.java"
    ).read_text(encoding="utf-8")

    require(
        package_script,
        'WINDOWS_JAVA_INITIAL_RAM_PERCENTAGE="${WINDOWS_JAVA_INITIAL_RAM_PERCENTAGE:-1.0}"',
        "package_windows_exe.sh",
    )
    require(
        package_script,
        'WINDOWS_JAVA_MAX_RAM_PERCENTAGE="${WINDOWS_JAVA_MAX_RAM_PERCENTAGE:-50.0}"',
        "package_windows_exe.sh",
    )
    if '--java-options "-Xmx4096m"' in package_script:
        raise AssertionError("Windows launchers must not reserve a fixed 4 GB Java heap")
    if "SharedArchiveFile" in package_script:
        raise AssertionError("Portable Windows launchers must not use path-bound AppCDS archives")
    require(runtime_tools, '"jdk.accessibility",', "package_runtime_tools.py")
    require(runtime_tools, '"jdk.httpserver",', "package_runtime_tools.py")
    require(runtime_tools, 'verify_chatgpt_runtime(tmp, jar_path)', "package_runtime_tools.py")
    require(package_script, "--add-modules ALL-MODULE-PATH --jlink-options", "package_windows_exe.sh")
    require(package_script, "featurecat.lizzie.teacher.ChatGptRuntimeSmoke", "package_windows_exe.sh")
    require(workflow, "featurecat.lizzie.teacher.ChatGptRuntimeSmoke", "build-windows-release.yml")
    require(package_script, "runtime/bin/jabswitch.exe", "package_windows_exe.sh")
    require(package_script, "runtime/bin/javaaccessbridge.dll", "package_windows_exe.sh")
    require(package_script, "runtime/bin/windowsaccessbridge-64.dll", "package_windows_exe.sh")

    require(smoke_script, "[switch]$LauncherOnly", "windows_smoke_test.ps1")
    require(smoke_script, "[switch]$OpenAutoSetup", "windows_smoke_test.ps1")
    require(smoke_script, "[System.IO.File]::ReadAllBytes", "windows_smoke_test.ps1")
    require(smoke_script, "[System.IO.File]::WriteAllBytes", "windows_smoke_test.ps1")
    require(smoke_script, "Stop-AppClockHelperProcesses", "windows_smoke_test.ps1")
    require(smoke_script, "Stop-AppOwnedProcesses", "windows_smoke_test.ps1")
    require(
        smoke_script,
        "$processPath.StartsWith(",
        "windows_smoke_test.ps1",
    )
    require(
        smoke_script,
        "[System.StringComparison]::OrdinalIgnoreCase",
        "windows_smoke_test.ps1",
    )
    require(
        smoke_script,
        "Stop-AppOwnedProcesses -AppExe $AppExe",
        "windows_smoke_test.ps1",
    )
    require(smoke_script, "Get-Process -Name java, javaw", "windows_smoke_test.ps1")
    require(smoke_script, "Get-NativeReadBoardProcessIds", "windows_smoke_test.ps1")
    require(smoke_script, "Get-Process -Name readboard", "windows_smoke_test.ps1")
    require(
        smoke_script,
        '$ErrorActionPreference = "Continue"',
        "windows_smoke_test.ps1",
    )
    require(
        smoke_script,
        "if (-not $hasRuntimeLogs)",
        "windows_smoke_test.ps1",
    )
    require(lizzie_source, "lizzie.smoke.openAutoSetup", "Lizzie.java")
    require(workflow, "LizzieYzy Next NVIDIA.exe", "build-windows-release.yml")
    require(workflow, "-LauncherOnly", "build-windows-release.yml")
    require(workflow, "-OpenAutoSetup", "build-windows-release.yml")
    require(workflow, "runtime/bin/server/jvm.dll", "build-windows-release.yml")
    require(workflow, "^jdk.accessibility@", "build-windows-release.yml")
    require(
        workflow,
        "scripts/audit_katago_binary_version.py",
        "build-windows-release.yml",
    )
    require(
        workflow,
        "scripts/audit_katago_package_metadata.py",
        "build-windows-release.yml",
    )
    require(workflow, 'runtime_version_engines=(', "build-windows-release.yml")
    require(workflow, 'driver_gated_static_engines=(', "build-windows-release.yml")
    require(workflow, 'tensorrt_engine=', "build-windows-release.yml")
    require(workflow, '--expected-version "$(python3 scripts/katago_asset_catalog.py get katagoVersion)"', "build-windows-release.yml")
    require(package_script, "write_tensorrt_version_file", "package_windows_exe.sh")
    require(package_script, "Windows TensorRT bundle", "package_windows_exe.sh")
    require(workflow, "without an NVIDIA display driver", "build-windows-release.yml")
    verify_nvidia_dependency_origin(workflow)
    if "windows-x64/libz.dll" in workflow:
        raise AssertionError("KataGo 1.18 Windows bundles use z.dll, not the legacy libz.dll name")
    require(
        package_script,
        'LIZZIE_RELEASE_PRERELEASE:-false',
        "package_windows_exe.sh",
    )
    require(
        package_script,
        '"prerelease": prerelease == "true"',
        "package_windows_exe.sh",
    )
    require(
        workflow,
        "RELEASE_PRERELEASE: ${{ inputs.release_prerelease }}",
        "build-windows-release.yml",
    )
    require(
        workflow,
        "scripts/validate_release_workflow_identity.py",
        "build-windows-release.yml",
    )
    require(workflow, "--require-draft", "build-windows-release.yml")
    require(
        workflow,
        "RELEASE_TARGET_SHA: ${{ github.sha }}",
        "build-windows-release.yml",
    )
    require(
        workflow,
        "RELEASE_GITHUB_REF: ${{ github.ref }}",
        "build-windows-release.yml",
    )
    require(workflow, '--target-sha "$RELEASE_TARGET_SHA"', "build-windows-release.yml")
    require(workflow, '--github-ref "$RELEASE_GITHUB_REF"', "build-windows-release.yml")
    require(
        workflow,
        'if [[ "$release_prerelease" != "true" && "$release_prerelease" != "false" ]]',
        "build-windows-release.yml",
    )
    if "releases?per_page=100" in workflow or "releases/tags/" in workflow:
        raise AssertionError(
            "release identity lookup belongs in validate_release_workflow_identity.py"
        )
    if "scheduleAutomaticCheck" in lizzie_source or "auto-check" in update_controller:
        raise AssertionError("Windows updates must only run after an explicit user action")

    print("Windows launcher packaging guards passed.")


if __name__ == "__main__":
    main()

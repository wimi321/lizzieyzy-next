#!/usr/bin/env python3
"""Static release guards for portable Windows JVM launchers."""

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def require(text: str, value: str, source: str) -> None:
    if value not in text:
        raise AssertionError(f"{source} is missing required launcher guard: {value}")


def main() -> None:
    package_script = (ROOT / "scripts/package_windows_exe.sh").read_text(encoding="utf-8")
    runtime_tools = (ROOT / "scripts/package_runtime_tools.py").read_text(encoding="utf-8")
    smoke_script = (ROOT / "scripts/windows_smoke_test.ps1").read_text(encoding="utf-8")
    lizzie_source = (ROOT / "src/main/java/featurecat/lizzie/Lizzie.java").read_text(
        encoding="utf-8"
    )
    workflow = (ROOT / ".github/workflows/build-windows-release.yml").read_text(
        encoding="utf-8"
    )

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
    require(package_script, "--add-modules jdk.accessibility", "package_windows_exe.sh")
    require(package_script, "runtime/bin/jabswitch.exe", "package_windows_exe.sh")
    require(package_script, "runtime/bin/javaaccessbridge.dll", "package_windows_exe.sh")
    require(package_script, "runtime/bin/windowsaccessbridge-64.dll", "package_windows_exe.sh")

    require(smoke_script, "[switch]$LauncherOnly", "windows_smoke_test.ps1")
    require(smoke_script, "[switch]$OpenAutoSetup", "windows_smoke_test.ps1")
    require(lizzie_source, "lizzie.smoke.openAutoSetup", "Lizzie.java")
    require(workflow, "LizzieYzy Next NVIDIA.exe", "build-windows-release.yml")
    require(workflow, "-LauncherOnly", "build-windows-release.yml")
    require(workflow, "-OpenAutoSetup", "build-windows-release.yml")
    require(workflow, "runtime/bin/server/jvm.dll", "build-windows-release.yml")
    require(workflow, "^jdk.accessibility@", "build-windows-release.yml")

    print("Windows launcher packaging guards passed.")


if __name__ == "__main__":
    main()

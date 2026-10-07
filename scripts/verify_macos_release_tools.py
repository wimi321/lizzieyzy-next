#!/usr/bin/env python3
"""Check the packaging toolchain without upgrading the hosted runner's packages."""

from __future__ import annotations

import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys


REPO_ROOT = Path(__file__).resolve().parents[1]
TOOLS = (
    "mvn", "curl", "unzip", "ditto", "hdiutil", "otool", "install_name_tool",
    "codesign", "xcrun", "sips", "osascript",
)
JAVA_TOOLS = ("java", "javac", "jpackage", "jlink", "jdeps")


def verify_tools(catalog: dict, java_home: str) -> dict:
    if platform.system() != "Darwin":
        raise RuntimeError("macOS release packaging requires a native macOS host")
    # Source compilation owns its SDK dependencies; release jobs only consume locked archives.
    if catalog.get("origin") != "project-source-build":
        raise RuntimeError("This release toolchain requires pinned project-source-build archives")
    if not java_home or not Path(java_home).is_dir():
        raise RuntimeError("JAVA_HOME must point to the JDK 21 selected by setup-java")
    home = Path(java_home).resolve()
    for name in JAVA_TOOLS:
        expected = home / "bin" / name
        actual = shutil.which(name)
        if not expected.is_file() or not actual or Path(actual).resolve() != expected.resolve():
            raise RuntimeError(f"{name} must resolve to JAVA_HOME/bin, not a different JDK")
    tools = {}
    for name in TOOLS:
        executable = shutil.which(name)
        if not executable:
            raise RuntimeError(f"Required release tool is missing: {name}")
        tools[name] = executable
    result = subprocess.run(
        [tools["mvn"], "-version"], check=True, stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT, text=True, timeout=30,
    )
    if not re.search(r"Java version:\s*21(?:[.,\s]|$)", result.stdout):
        raise RuntimeError("Maven must use JDK 21 from setup-java:\n" + result.stdout)
    return {"javaHome": str(home), "mavenVersion": result.stdout.strip(), "tools": tools}


def main() -> int:
    try:
        catalog = json.loads((REPO_ROOT / "src/main/resources/katago-assets.json").read_text("utf-8"))
        print(json.dumps(verify_tools(catalog, os.environ.get("JAVA_HOME", "")), indent=2))
        return 0
    except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as error:
        print(f"macOS release toolchain check failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Prefer the hosted runner's existing official HTTPS archive, keeping its fallbacks."""

from collections.abc import Mapping
import os
from pathlib import Path
import platform
import re
import stat
import sys
import tempfile


AZURE = "http://azure.archive.ubuntu.com/ubuntu/"
ARCHIVE = "https://archive.ubuntu.com/ubuntu/"
SECURITY = "https://security.ubuntu.com/ubuntu/"
DEFAULT_PRIORITIES = {AZURE: 1, ARCHIVE: 2, SECURITY: 3}
PREFERRED_PRIORITIES = {AZURE: 2, ARCHIVE: 1, SECURITY: 3}
MIRROR_FILE = Path("/etc/apt/apt-mirrors.txt")


def require_hosted_ubuntu_x64(
    environment: Mapping[str, str], system: str, machine: str, distribution: str
) -> None:
    expected = {
        "GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "github-hosted",
        "RUNNER_OS": "Linux", "RUNNER_ARCH": "X64",
    }
    if any(environment.get(key) != value for key, value in expected.items()) or (
        system, machine, distribution
    ) != ("Linux", "x86_64", "ubuntu"):
        raise ValueError("APT mirror preference is restricted to GitHub-hosted Ubuntu x64 runners")


def prefer_official_archive(content: str) -> str:
    # Match the runner's explicit metadata; moving lines alone does not change apt priority.
    # https://github.com/actions/runner-images/blob/main/images/ubuntu/scripts/build/configure-apt-sources.sh
    entries = {}
    updated = []
    for number, line in enumerate(content.splitlines(keepends=True), 1):
        if not line.strip() or line.startswith("#"):
            updated.append(line)
            continue
        match = re.fullmatch(r"([^\s]+)(\t[ \t]*priority:)([1-3])([ \t]*\n?)", line)
        if match is None or match[1] not in DEFAULT_PRIORITIES or match[1] in entries:
            raise ValueError(f"Unexpected hosted-runner mirrorlist entry at line {number}")
        uri = match[1]
        entries[uri] = int(match[3])
        updated.append(f"{uri}{match[2]}{PREFERRED_PRIORITIES[uri]}{match[4]}")
    if entries not in (DEFAULT_PRIORITIES, PREFERRED_PRIORITIES):
        raise ValueError("Expected all three runner mirrors with known explicit priorities")
    return "".join(updated)


def configure_mirror_file(path: Path) -> None:
    if path.is_symlink() or not path.is_file():
        raise ValueError("Expected an existing regular hosted-runner apt-mirrors.txt")
    original = path.read_text(encoding="utf-8")
    print("APT mirrorlist before:\n" + original, end="\n")
    updated = prefer_official_archive(original)
    if updated != original:
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(
                mode="w", encoding="utf-8", newline="", dir=path.parent,
                prefix=f".{path.name}.", delete=False,
            ) as output:
                temporary = Path(output.name)
                output.write(updated)
                output.flush()
                os.fsync(output.fileno())
            os.chmod(temporary, stat.S_IMODE(path.stat().st_mode))
            os.replace(temporary, path)
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)
    print("APT mirrorlist after (all fallback URIs retained):\n" + updated, end="\n")


def main() -> int:
    try:
        distribution = platform.freedesktop_os_release().get("ID", "") if sys.platform == "linux" else ""
        require_hosted_ubuntu_x64(os.environ, platform.system(), platform.machine(), distribution)
        configure_mirror_file(MIRROR_FILE)
    except (OSError, ValueError) as error:
        print(f"APT mirror preference refused: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

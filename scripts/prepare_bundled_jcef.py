#!/usr/bin/env python3
"""Prepare a pinned JCEF native bundle for release packages."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import tarfile
import tempfile
import time
import urllib.request
import zipfile


JCEF_RELEASE_TAG = "jcef-99c2f7a+cef-127.3.1+g6cbb30e+chromium-127.0.6533.100"
MAVEN_BASE_URL = "https://repo.maven.apache.org/maven2/me/friwi"
MANIFEST_NAME = "lizzieyzy-next-jcef-manifest.txt"

PLATFORM_PACKAGES = {
    "windows-amd64": {
        "artifact": "jcef-natives-windows-amd64",
        "sha256": "6d0466b9d5a2c4607a8a9eded1b5b9f77ca4514eadb68a1333b19053a462ceac",
        "required": [
            "build_meta.json",
            "install.lock",
            "libcef.dll",
            "jcef.dll",
            "jcef_helper.exe",
            "chrome_elf.dll",
            "icudtl.dat",
            "resources.pak",
            "locales/en-US.pak",
        ],
    }
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--platform",
        required=True,
        choices=sorted(PLATFORM_PACKAGES),
        help="JCEF native platform identifier.",
    )
    parser.add_argument("--output-dir", required=True, help="Directory to receive jcef-bundle")
    parser.add_argument("--cache-dir", default=".cache/jcef", help="Download cache directory")
    parser.add_argument(
        "--source-jar",
        default=os.environ.get("JCEF_SOURCE_JAR", ""),
        help="Optional already-downloaded jcef-natives jar.",
    )
    return parser.parse_args()


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download_with_retries(url: str, target: Path, attempts: int = 5) -> None:
    tmp = target.with_suffix(target.suffix + ".tmp")
    for attempt in range(1, attempts + 1):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "LizzieYzy-Next-Packager"})
            with urllib.request.urlopen(request, timeout=180) as response, tmp.open("wb") as out:
                shutil.copyfileobj(response, out)
            tmp.replace(target)
            return
        except Exception:
            if tmp.exists():
                tmp.unlink()
            if attempt >= attempts:
                raise
            time.sleep(min(2 * attempt, 10))


def resolve_native_jar(platform: str, cache_dir: Path, source_jar: str) -> tuple[Path, str]:
    package = PLATFORM_PACKAGES[platform]
    artifact = package["artifact"]
    expected_sha256 = package["sha256"]
    jar_name = f"{artifact}-{JCEF_RELEASE_TAG}.jar"

    if source_jar:
        jar_path = Path(source_jar)
        source = f"local:{jar_path}"
    else:
        platform_cache = cache_dir / JCEF_RELEASE_TAG
        platform_cache.mkdir(parents=True, exist_ok=True)
        jar_path = platform_cache / jar_name
        source = (
            f"maven:{MAVEN_BASE_URL}/{artifact}/{JCEF_RELEASE_TAG}/{jar_name}"
        )
        if jar_path.exists() and file_sha256(jar_path) != expected_sha256:
            jar_path.unlink()
        if not jar_path.exists():
            download_with_retries(
                f"{MAVEN_BASE_URL}/{artifact}/{JCEF_RELEASE_TAG}/{jar_name}",
                jar_path,
            )

    actual_sha256 = file_sha256(jar_path)
    if actual_sha256 != expected_sha256:
        raise SystemExit(
            f"JCEF checksum mismatch for {jar_path}: expected {expected_sha256}, got {actual_sha256}"
        )
    return jar_path, source


def find_tar_member(archive: zipfile.ZipFile) -> str:
    tar_members = [name for name in archive.namelist() if name.endswith(".tar.gz")]
    if len(tar_members) != 1:
        raise SystemExit(f"Expected exactly one JCEF .tar.gz member, found {len(tar_members)}")
    return tar_members[0]


def is_safe_tar_member(name: str) -> bool:
    normalized = name.replace("\\", "/")
    if normalized.startswith("/") or normalized.startswith("../"):
        return False
    return "/../" not in normalized and normalized != ".."


def extract_bundle(jar_path: Path, output_dir: Path) -> None:
    temp_dir = Path(tempfile.mkdtemp(prefix="jcef-bundle-"))
    try:
        with zipfile.ZipFile(jar_path) as archive:
            tar_member = find_tar_member(archive)
            with archive.open(tar_member) as tar_stream:
                with tarfile.open(fileobj=tar_stream, mode="r:gz") as tar:
                    members = tar.getmembers()
                    for member in members:
                        if not is_safe_tar_member(member.name):
                            raise SystemExit(f"Unsafe path in JCEF archive: {member.name}")
                    tar.extractall(temp_dir, members=members)

        if output_dir.exists():
            shutil.rmtree(output_dir)
        shutil.copytree(temp_dir, output_dir)
    finally:
        shutil.rmtree(temp_dir, ignore_errors=True)


def validate_bundle(output_dir: Path, platform: str) -> None:
    required = PLATFORM_PACKAGES[platform]["required"]
    missing = [name for name in required if not (output_dir / name).exists()]
    if missing:
        raise SystemExit(f"Prepared JCEF bundle is missing required file(s): {', '.join(missing)}")

    meta_path = output_dir / "build_meta.json"
    metadata = json.loads(meta_path.read_text(encoding="utf-8"))
    if metadata.get("release_tag") != JCEF_RELEASE_TAG:
        raise SystemExit(
            f"JCEF release tag mismatch: expected {JCEF_RELEASE_TAG}, got {metadata.get('release_tag')}"
        )
    if metadata.get("platform") != platform:
        raise SystemExit(
            f"JCEF platform mismatch: expected {platform}, got {metadata.get('platform')}"
        )


def write_manifest(output_dir: Path, platform: str, jar_path: Path, source: str) -> None:
    artifact = PLATFORM_PACKAGES[platform]["artifact"]
    lines = [
        f"release-tag={JCEF_RELEASE_TAG}",
        f"platform={platform}",
        f"artifact={artifact}",
        f"sha256={PLATFORM_PACKAGES[platform]['sha256']}",
        f"source={source}",
        f"cached-jar={jar_path}",
        "install-lock=present",
    ]
    (output_dir / MANIFEST_NAME).write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    args = parse_args()
    output_dir = Path(args.output_dir)
    cache_dir = Path(args.cache_dir)

    jar_path, source = resolve_native_jar(args.platform, cache_dir, args.source_jar)
    extract_bundle(jar_path, output_dir)
    (output_dir / "install.lock").touch()
    validate_bundle(output_dir, args.platform)
    write_manifest(output_dir, args.platform, jar_path, source)
    print(f"Prepared JCEF {JCEF_RELEASE_TAG} [{args.platform}] in {output_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

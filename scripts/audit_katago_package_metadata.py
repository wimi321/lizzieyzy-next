#!/usr/bin/env python3
"""Verify that packaged KataGo display metadata matches its engine asset manifest."""

from __future__ import annotations

import argparse
from pathlib import Path
import sys


class PackageMetadataAuditError(RuntimeError):
    pass


def parse_metadata(path: Path) -> dict[str, str]:
    if not path.is_file():
        raise PackageMetadataAuditError(f"metadata file not found: {path}")
    metadata: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        key, separator, value = raw_line.partition(":")
        if separator and key.strip() and value.strip():
            metadata[key.strip()] = value.strip()
    return metadata


def normalize_version(value: str) -> str:
    return value.strip().removeprefix("v")


def audit_package_metadata(
    version_file: Path,
    engine_manifest: Path,
    expected_version: str,
    expected_asset: str,
) -> None:
    display = parse_metadata(version_file)
    engine = parse_metadata(engine_manifest)
    display_version = normalize_version(display.get("KataGo release", ""))
    engine_version = normalize_version(engine.get("KataGo release", ""))
    expected = normalize_version(expected_version)
    display_asset = display.get("Windows TensorRT bundle", "")
    engine_asset = engine.get("Asset", "")

    if display_version != expected or engine_version != expected:
        raise PackageMetadataAuditError(
            "KataGo version mismatch: "
            f"display={display_version or '<missing>'}, "
            f"engine={engine_version or '<missing>'}, expected={expected}"
        )
    if display_asset != expected_asset or engine_asset != expected_asset:
        raise PackageMetadataAuditError(
            "KataGo asset mismatch: "
            f"display={display_asset or '<missing>'}, "
            f"engine={engine_asset or '<missing>'}, expected={expected_asset}"
        )
    print(
        f"{version_file}: KataGo v{expected} / {expected_asset} matches {engine_manifest}"
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version-file", required=True, type=Path)
    parser.add_argument("--engine-manifest", required=True, type=Path)
    parser.add_argument("--expected-version", required=True)
    parser.add_argument("--expected-asset", required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    audit_package_metadata(
        args.version_file,
        args.engine_manifest,
        args.expected_version,
        args.expected_asset,
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, PackageMetadataAuditError) as exc:
        print(f"KataGo package metadata audit failed: {exc}", file=sys.stderr)
        raise SystemExit(1)

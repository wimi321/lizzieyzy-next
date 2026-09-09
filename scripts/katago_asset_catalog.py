#!/usr/bin/env python3
"""Read and validate the trusted KataGo release asset catalog."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CATALOG = ROOT / "src" / "main" / "resources" / "katago-assets.json"
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")


def load_catalog(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        catalog = json.load(handle)
    validate_catalog(catalog)
    return catalog


def validate_catalog(catalog: dict[str, Any]) -> None:
    if catalog.get("schemaVersion") != 1:
        raise ValueError("KataGo asset catalog schemaVersion must be 1")
    version = require_text(catalog, "katagoVersion")
    release_tag = require_text(catalog, "katagoReleaseTag")
    if release_tag != f"v{version}":
        raise ValueError("katagoReleaseTag must match katagoVersion")
    models = catalog.get("models")
    assets = catalog.get("assets")
    if not isinstance(models, dict) or not models:
        raise ValueError("models must be a non-empty object")
    if not isinstance(assets, dict) or not assets:
        raise ValueError("assets must be a non-empty object")
    default_id = require_text(catalog, "defaultModelId")
    if default_id not in models:
        raise ValueError(f"defaultModelId is unknown: {default_id}")
    bundled = [model_id for model_id, model in models.items() if model.get("bundled") is True]
    if bundled != [default_id]:
        raise ValueError("exactly the default model must be marked bundled")
    for model_id, model in models.items():
        validate_entry(model, f"model {model_id}")
        require_text(model, "fileName")
        require_text(model, "minimumKataGoVersion")
        override = model.get("downloadUrl", "")
        if override and override != (
            "https://media.katagotraining.org/uploaded/networks/models/kata1/" + model["fileName"]
        ):
            raise ValueError(f"model {model_id} has unsupported official downloadUrl")
    for asset_id, asset in assets.items():
        validate_entry(asset, f"asset {asset_id}")
        name = require_text(asset, "assetName")
        if f"-{release_tag}-" not in name:
            raise ValueError(f"asset {asset_id} does not use {release_tag}: {name}")
        executable_sha = asset.get("executableSha256", "")
        if executable_sha and not SHA256_RE.fullmatch(executable_sha):
            raise ValueError(f"asset {asset_id} has invalid executableSha256")
    windows_nvidia = assets.get("windows-nvidia", {})
    if not SHA256_RE.fullmatch(str(windows_nvidia.get("executableSha256", ""))):
        raise ValueError("asset windows-nvidia requires executableSha256")
    if "windows-tensorrt" not in assets:
        raise ValueError("asset windows-tensorrt is required")


def validate_entry(entry: Any, label: str) -> None:
    if not isinstance(entry, dict):
        raise ValueError(f"{label} must be an object")
    if not isinstance(entry.get("sizeBytes"), int) or entry["sizeBytes"] <= 0:
        raise ValueError(f"{label} has invalid sizeBytes")
    sha256 = require_text(entry, "sha256")
    if not SHA256_RE.fullmatch(sha256):
        raise ValueError(f"{label} has invalid sha256")


def require_text(value: dict[str, Any], key: str) -> str:
    result = value.get(key)
    if not isinstance(result, str) or not result.strip():
        raise ValueError(f"missing non-empty string field: {key}")
    return result.strip()


def resolve(catalog: dict[str, Any], dotted_path: str) -> Any:
    current: Any = catalog
    for part in dotted_path.split("."):
        if not isinstance(current, dict) or part not in current:
            raise KeyError(f"unknown catalog path: {dotted_path}")
        current = current[part]
    return current


def model_download_url(catalog: dict[str, Any], model_id: str) -> str:
    model = catalog["models"][model_id]
    return model.get("downloadUrl") or (
        f"https://github.com/lightvector/KataGo/releases/download/{catalog['modelReleaseTag']}/"
        + model["fileName"]
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("validate")
    get_parser = subparsers.add_parser("get")
    get_parser.add_argument("path")
    model_url_parser = subparsers.add_parser("model-url")
    model_url_parser.add_argument("model_id")
    args = parser.parse_args()

    catalog = load_catalog(args.catalog)
    if args.command == "model-url":
        print(model_download_url(catalog, args.model_id))
    if args.command == "get":
        value = resolve(catalog, args.path)
        if isinstance(value, (dict, list)):
            print(json.dumps(value, ensure_ascii=False, separators=(",", ":")))
        elif isinstance(value, bool):
            print("true" if value else "false")
        else:
            print(value)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

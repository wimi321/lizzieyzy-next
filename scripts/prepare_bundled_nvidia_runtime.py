#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import ssl
import sys
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlparse
from urllib.request import Request, urlopen
from zipfile import ZipFile

USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
)
CUDA_MANIFEST_URL = "https://developer.download.nvidia.com/compute/cuda/redist/redistrib_12.1.1.json"
CUDNN_MANIFEST_URL = "https://developer.download.nvidia.com/compute/cudnn/redist/redistrib_8.9.7.29.json"
PACKAGE_SPECS = (
    ("CUDA Runtime", CUDA_MANIFEST_URL, "cuda_cudart", "windows-x86_64"),
    ("CUDA cuBLAS", CUDA_MANIFEST_URL, "libcublas", "windows-x86_64"),
    ("CUDA nvJitLink", CUDA_MANIFEST_URL, "libnvjitlink", "windows-x86_64"),
    ("NVIDIA cuDNN", CUDNN_MANIFEST_URL, "cudnn", "windows-x86_64"),
)
DLL_SUFFIX = ".dll"
MANIFEST_FILE_NAME = "lizzieyzy-next-nvidia-runtime-manifest.txt"
LICENSE_DIR_NAME = "licenses"


class RuntimeErrorWithContext(RuntimeError):
    pass


def parse_args() -> argparse.Namespace:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(
        description="Download and prepare the official NVIDIA runtime DLLs for the Windows NVIDIA bundle."
    )
    parser.add_argument(
        "--cache-dir",
        default=str(root / ".cache" / "nvidia-runtime"),
        help="Cache directory for manifests and downloaded archives.",
    )
    parser.add_argument(
        "--output-dir",
        default=str(root / "dist" / "windows" / "nvidia-runtime"),
        help="Directory where extracted DLLs and license files will be written.",
    )
    return parser.parse_args()


def download_file(url: str, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    temp_path = destination.with_suffix(destination.suffix + ".part")
    if temp_path.exists():
        temp_path.unlink()
    print(f"Downloading {url} -> {destination}", flush=True)
    request = Request(url, headers={"User-Agent": USER_AGENT})
    context = ssl.create_default_context()
    with urlopen(request, timeout=60, context=context) as response, temp_path.open("wb") as handle:
        shutil.copyfileobj(response, handle)
    temp_path.replace(destination)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_package_specs(cache_dir: Path) -> list[dict[str, object]]:
    manifests_dir = cache_dir / "manifests"
    manifests_dir.mkdir(parents=True, exist_ok=True)
    packages: list[dict[str, object]] = []

    for display_name, manifest_url, package_key, platform_key in PACKAGE_SPECS:
        manifest_path = manifests_dir / Path(urlparse(manifest_url).path).name
        if not manifest_path.exists():
            download_file(manifest_url, manifest_path)
        manifest_data = json.loads(manifest_path.read_text(encoding="utf-8"))

        package_json = manifest_data.get(package_key)
        if not isinstance(package_json, dict):
            raise RuntimeErrorWithContext(f"Missing NVIDIA package metadata: {package_key}")
        platform_json = package_json.get(platform_key)
        if not isinstance(platform_json, dict):
            raise RuntimeErrorWithContext(
                f"Missing NVIDIA platform metadata: {package_key} {platform_key}"
            )
        relative_path = str(platform_json.get("relative_path", "")).strip()
        sha256_value = str(platform_json.get("sha256", "")).strip().lower()
        size_text = str(platform_json.get("size", "0")).strip()
        version = str(package_json.get("version", "")).strip()
        if not relative_path or not sha256_value:
            raise RuntimeErrorWithContext(f"Incomplete NVIDIA metadata: {package_key}")
        try:
            size_bytes = int(size_text)
        except ValueError as exc:
            raise RuntimeErrorWithContext(f"Invalid size in NVIDIA metadata: {package_key}") from exc
        packages.append(
            {
                "display_name": display_name,
                "key": package_key,
                "version": version,
                "url": (
                    relative_path
                    if relative_path.startswith("http")
                    else manifest_url.rsplit("/", 1)[0] + "/" + relative_path
                ),
                "sha256": sha256_value,
                "size_bytes": size_bytes,
            }
        )
    return packages


def ensure_archive(package: dict[str, object], archives_dir: Path) -> Path:
    url = str(package["url"])
    file_name = Path(urlparse(url).path).name
    destination = archives_dir / file_name
    expected_sha = str(package["sha256"])
    if destination.exists() and sha256(destination) == expected_sha:
        print(f"Using cached NVIDIA runtime archive: {destination}", flush=True)
        return destination
    if destination.exists():
        destination.unlink()
    download_file(url, destination)
    actual_sha = sha256(destination)
    if actual_sha != expected_sha:
        destination.unlink(missing_ok=True)
        raise RuntimeErrorWithContext(
            f"SHA-256 mismatch for {package['display_name']}: expected {expected_sha}, got {actual_sha}"
        )
    return destination


def copy_entry(zip_file: ZipFile, member_name: str, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    with zip_file.open(member_name) as source, destination.open("wb") as target:
        shutil.copyfileobj(source, target)


def extract_package(package: dict[str, object], archive_path: Path, output_dir: Path) -> list[str]:
    extracted_dlls: list[str] = []
    licenses_dir = output_dir / LICENSE_DIR_NAME
    with ZipFile(archive_path) as zip_file:
        for member in zip_file.infolist():
            if member.is_dir():
                continue
            file_name = Path(member.filename).name
            lower_name = file_name.lower()
            normalized_member = member.filename.replace("\\", "/").lower()
            if lower_name.endswith(DLL_SUFFIX):
                copy_entry(zip_file, member.filename, output_dir / file_name)
                extracted_dlls.append(file_name)
            elif lower_name == "license.txt" or "/license" in normalized_member:
                copy_entry(
                    zip_file,
                    member.filename,
                    licenses_dir / f"{package['key']}-{file_name}",
                )
    return extracted_dlls


def write_manifest(output_dir: Path, packages: list[dict[str, object]], extracted_names: list[str]) -> None:
    manifest_path = output_dir / MANIFEST_FILE_NAME
    lines = [
        f"Prepared at: {datetime.now(timezone.utc).astimezone().strftime('%Y-%m-%d %H:%M:%S %z')}",
        f"DLL count: {len(extracted_names)}",
        "",
        "DLLs:",
    ]
    lines.extend(f"- {name}" for name in sorted(set(extracted_names), key=str.lower))
    lines.append("")
    lines.append("Packages:")
    for package in packages:
        lines.append(
            f"- {package['display_name']}: {package['version']} | {package['url']} | sha256={package['sha256']}"
        )
    manifest_path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    args = parse_args()
    cache_dir = Path(args.cache_dir).resolve()
    output_dir = Path(args.output_dir).resolve()
    archives_dir = cache_dir / "archives"
    output_dir.mkdir(parents=True, exist_ok=True)
    archives_dir.mkdir(parents=True, exist_ok=True)

    packages = load_package_specs(cache_dir)

    for child in output_dir.iterdir():
        if child.is_dir():
            shutil.rmtree(child)
        else:
            child.unlink()

    extracted_names: list[str] = []
    for package in packages:
        archive_path = ensure_archive(package, archives_dir)
        extracted_names.extend(extract_package(package, archive_path, output_dir))

    if not extracted_names:
        raise RuntimeErrorWithContext("No NVIDIA runtime DLLs were extracted.")

    write_manifest(output_dir, packages, extracted_names)
    print(f"Prepared NVIDIA runtime in: {output_dir}")
    print(f"DLLs: {len(set(extracted_names))}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeErrorWithContext as exc:
        print(str(exc), file=sys.stderr)
        raise SystemExit(1)

#!/usr/bin/env bash
set -euo pipefail

PLATFORM="${1:-}"
RELEASE_DIR="${2:-dist/release}"
DATE_TAG="${3:-}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ -z "$PLATFORM" || -z "$DATE_TAG" ]]; then
  echo "Usage: $0 <windows|mac-arm64|mac-amd64|linux> [release_dir] <date_tag>"
  exit 1
fi

if [[ ! -d "$RELEASE_DIR" ]]; then
  echo "Release directory not found: $RELEASE_DIR"
  exit 1
fi

expected=()
case "$PLATFORM" in
  windows)
    expected=(
      "${DATE_TAG}-windows64.opencl.installer.exe"
      "${DATE_TAG}-windows64.opencl.portable.zip"
      "${DATE_TAG}-windows64.nvidia.installer.exe"
      "${DATE_TAG}-windows64.nvidia.portable.zip"
      "${DATE_TAG}-windows64.nvidia50.cuda.installer.exe"
      "${DATE_TAG}-windows64.nvidia50.cuda.portable.zip"
      "${DATE_TAG}-windows64.with-katago.installer.exe"
      "${DATE_TAG}-windows64.with-katago.portable.zip"
      "${DATE_TAG}-windows64.without.engine.installer.exe"
      "${DATE_TAG}-windows64.without.engine.portable.zip"
      "${DATE_TAG}-windows64.core-update.zip"
      "lizzieyzy-next-update-manifest.json"
    )
    trt_prefix="${DATE_TAG}-windows64.nvidia.tensorrt.portable.7z"
    trt_readme="${DATE_TAG}-windows64.nvidia.tensorrt.portable.README.txt"
    trt_manifest="${DATE_TAG}-windows64.nvidia.tensorrt.portable.manifest.json"
    trt_checksum="${DATE_TAG}-windows64.nvidia.tensorrt.portable.sha256.txt"
    shopt -s nullglob
    trt_parts=("$RELEASE_DIR/${trt_prefix}".[0-9][0-9][0-9])
    shopt -u nullglob
    if [[ "${#trt_parts[@]}" -eq 0 ]]; then
      echo "Missing advanced optional TensorRT split package: ${trt_prefix}.001"
      exit 1
    fi
    for index in "${!trt_parts[@]}"; do
      expected_part="$(printf '%s.%03d' "$trt_prefix" "$((index + 1))")"
      if [[ "$(basename "${trt_parts[$index]}")" != "$expected_part" ]]; then
        echo "TensorRT split volumes must be contiguous from .001"
        printf 'Expected: %s\nActual:   %s\n' "$expected_part" "$(basename "${trt_parts[$index]}")"
        exit 1
      fi
      expected+=("$(basename "${trt_parts[$index]}")")
    done
    expected+=("$trt_readme" "$trt_manifest" "$trt_checksum")
    ;;
  mac-arm64)
    expected=("${DATE_TAG}-mac-apple-silicon.with-katago.dmg")
    ;;
  mac-amd64)
    expected=("${DATE_TAG}-mac-intel.with-katago.dmg")
    ;;
  linux)
    expected=(
      "${DATE_TAG}-linux64.opencl.zip"
      "${DATE_TAG}-linux64.nvidia.zip"
      "${DATE_TAG}-linux64.with-katago.zip"
    )
    ;;
  *)
    echo "Unsupported platform: $PLATFORM"
    exit 1
    ;;
esac

actual=()
shopt -s nullglob
for path in "$RELEASE_DIR"/*; do
  [[ -f "$path" ]] || continue
  actual+=("$(basename "$path")")
done
shopt -u nullglob

if [[ "${#actual[@]}" -eq 0 ]]; then
  echo "No release assets found in $RELEASE_DIR"
  exit 1
fi

for name in "${actual[@]}"; do
  case "$name" in
    *.txt|*.sha256|*.sha256.txt|*.md)
      if [[ "$PLATFORM" != "windows" ]] || [[ "$name" != "${DATE_TAG}-windows64.nvidia.tensorrt.portable.README.txt" && "$name" != "${DATE_TAG}-windows64.nvidia.tensorrt.portable.sha256.txt" ]]; then
        echo "Unexpected helper file in public release set: $name"
        exit 1
      fi
      ;;
  esac
done

if [[ "${#actual[@]}" -ne "${#expected[@]}" ]]; then
  echo "Unexpected asset count for $PLATFORM"
  printf 'Expected (%s):\n' "${#expected[@]}"
  printf '  %s\n' "${expected[@]}"
  printf 'Actual (%s):\n' "${#actual[@]}"
  printf '  %s\n' "${actual[@]}"
  exit 1
fi

for name in "${expected[@]}"; do
  if [[ ! -f "$RELEASE_DIR/$name" ]]; then
    echo "Missing expected asset: $name"
    exit 1
  fi
done

for name in "${actual[@]}"; do
  match="false"
  for expected_name in "${expected[@]}"; do
    if [[ "$name" == "$expected_name" ]]; then
      match="true"
      break
    fi
  done
  if [[ "$match" != "true" ]]; then
    echo "Unexpected asset in public release set: $name"
    exit 1
  fi
done

case "$PLATFORM" in
  windows)
    PYTHON_BIN="python3"
    if ! command -v "$PYTHON_BIN" >/dev/null 2>&1; then
      PYTHON_BIN="python"
    fi
    "$PYTHON_BIN" - "$RELEASE_DIR" "$DATE_TAG" <<'PY'
import hashlib
import json
import pathlib
import re
import sys

release_dir = pathlib.Path(sys.argv[1])
date_tag = sys.argv[2]
manifest = json.loads((release_dir / "lizzieyzy-next-update-manifest.json").read_text(encoding="utf-8"))
assert manifest["schemaVersion"] == 1
assert manifest["releaseTag"].startswith("next-")
components = manifest["components"]
assert components, "manifest must include components"
core = next(item for item in components if item["id"] == "core")
expected_asset = f"{date_tag}-windows64.core-update.zip"
assert core["assetName"] == expected_asset
assert core["platform"] == "windows"
assert core["flavor"] == "all"
assert core["installAction"] == "replace-core"
assert core["defaultSelectedIfChanged"] is True
core_path = release_dir / expected_asset
assert core_path.is_file()
assert core["sizeBytes"] == core_path.stat().st_size
assert re.fullmatch(r"[0-9a-fA-F]{64}", core["sha256"])
assert core["sha256"].lower() == hashlib.sha256(core_path.read_bytes()).hexdigest()
PY
    ;;
  mac-arm64|mac-amd64)
    if command -v hdiutil >/dev/null 2>&1; then
      "$SCRIPT_DIR/validate_macos_dmg_layout.sh" "$RELEASE_DIR/${expected[0]}"
    else
      echo "Skipping macOS DMG layout validation because hdiutil is unavailable."
    fi
    ;;
esac

echo "Validated public release assets for $PLATFORM:"
printf '  %s\n' "${actual[@]}"

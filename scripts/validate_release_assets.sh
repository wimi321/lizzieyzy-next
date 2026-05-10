#!/usr/bin/env bash
set -euo pipefail

PLATFORM="${1:-}"
RELEASE_DIR="${2:-dist/release}"
DATE_TAG="${3:-}"

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
    )
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

if [[ "$PLATFORM" == "windows" ]]; then
  trt_parts=()
  shopt -s nullglob
  for path in "$RELEASE_DIR/${DATE_TAG}-windows64.nvidia50.trt.portable.zip.part"[0-9][0-9]; do
    [[ -f "$path" ]] || continue
    trt_parts+=("$(basename "$path")")
  done
  shopt -u nullglob
  if [[ "${#trt_parts[@]}" -lt 2 ]]; then
    echo "Missing split RTX 50 TensorRT portable assets"
    exit 1
  fi
  expected+=("${trt_parts[@]}")
fi

if [[ "${#actual[@]}" -eq 0 ]]; then
  echo "No release assets found in $RELEASE_DIR"
  exit 1
fi

for name in "${actual[@]}"; do
  case "$name" in
    *.txt|*.sha256|*.sha256.txt|*.md)
      echo "Unexpected helper file in public release set: $name"
      exit 1
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

echo "Validated public release assets for $PLATFORM:"
printf '  %s\n' "${actual[@]}"

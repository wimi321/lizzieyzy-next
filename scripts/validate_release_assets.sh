#!/usr/bin/env bash
set -euo pipefail

PLATFORM="${1:-}"
RELEASE_DIR="${2:-dist/release}"
DATE_TAG="${3:-}"
RELEASE_TAG="${4:-}"
RELEASE_PRERELEASE="${5:-}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ -z "$PLATFORM" || -z "$DATE_TAG" ]]; then
  echo "Usage: $0 <windows|mac-arm64|mac-amd64|linux> [release_dir] <date_tag> [release_tag] [prerelease]"
  exit 1
fi

if [[ ! -d "$RELEASE_DIR" ]]; then
  echo "Release directory not found: $RELEASE_DIR"
  exit 1
fi

PYTHON_BIN="python3"
if ! command -v "$PYTHON_BIN" >/dev/null 2>&1; then
  PYTHON_BIN="python"
fi

expected_output="$("$PYTHON_BIN" "$SCRIPT_DIR/release_asset_topology.py" expected-names --platform "$PLATFORM" --date-tag "$DATE_TAG")"
expected=()
while IFS= read -r expected_name; do
  # Keep macOS Bash 3.2 compatibility and strip CR from native Windows Python.
  expected_name="${expected_name%$'\r'}"
  [[ -n "$expected_name" ]] && expected+=("$expected_name")
done <<< "$expected_output"
if [[ "${#expected[@]}" -eq 0 ]]; then
  echo "No expected public release assets for $PLATFORM"
  exit 1
fi

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

is_expected() {
  local name="$1"
  local expected_name
  for expected_name in "${expected[@]}"; do
    if [[ "$name" == "$expected_name" ]]; then
      return 0
    fi
  done
  return 1
}

for name in "${actual[@]}"; do
  case "$name" in
    *.txt|*.sha256|*.sha256.txt|*.md)
      if ! is_expected "$name"; then
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
    if [[ -z "$RELEASE_TAG" || ( "$RELEASE_PRERELEASE" != "true" && "$RELEASE_PRERELEASE" != "false" ) ]]; then
      echo "Windows validation requires the exact release tag and prerelease=true|false" >&2
      exit 1
    fi
    PYTHON_BIN="python3"
    if ! command -v "$PYTHON_BIN" >/dev/null 2>&1; then
      PYTHON_BIN="python"
    fi
    "$PYTHON_BIN" "$SCRIPT_DIR/validate_windows_release_assets.py" \
      "$RELEASE_DIR" \
      "$DATE_TAG" \
      "$RELEASE_TAG" \
      "$RELEASE_PRERELEASE"
    ;;
  mac-arm64|mac-amd64)
    if command -v hdiutil >/dev/null 2>&1; then
      "$SCRIPT_DIR/validate_macos_dmg_layout.sh" \
        "$RELEASE_DIR/${expected[0]}" \
        "" \
        "$RELEASE_TAG"
    else
      echo "Skipping macOS DMG layout validation because hdiutil is unavailable."
    fi
    ;;
esac

echo "Validated public release assets for $PLATFORM:"
printf '  %s\n' "${actual[@]}"

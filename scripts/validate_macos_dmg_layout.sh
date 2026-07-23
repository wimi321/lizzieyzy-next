#!/usr/bin/env bash
set -euo pipefail

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "validate_macos_dmg_layout.sh only supports macOS." >&2
  exit 1
fi

DMG_PATH="${1:-}"
if [[ -z "$DMG_PATH" || ! -f "$DMG_PATH" ]]; then
  echo "Usage: $0 <path-to.dmg>" >&2
  exit 1
fi

WORK_DIR="$(mktemp -d -t lizzieyzy-dmg-validate.XXXXXX)"
MOUNT_POINT="$WORK_DIR/mount"
ATTACH_PLIST="$WORK_DIR/attach.plist"
MOUNTED_TARGET=""

detach_image() {
  local target="$1"
  local attempt
  for attempt in 1 2 3 4 5; do
    if hdiutil detach "$target" -quiet >/dev/null 2>&1; then
      return 0
    fi
    sleep "$attempt"
  done
  hdiutil detach "$target" -force -quiet >/dev/null 2>&1 || true
}

cleanup() {
  if [[ -n "$MOUNTED_TARGET" ]]; then
    detach_image "$MOUNTED_TARGET"
  fi
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

mkdir -p "$MOUNT_POINT"
hdiutil attach "$DMG_PATH" \
  -mountpoint "$MOUNT_POINT" \
  -readonly \
  -noverify \
  -noautoopen \
  -nobrowse \
  -plist >"$ATTACH_PLIST"

MOUNTED_TARGET="$(
  python3 - "$ATTACH_PLIST" <<'PY'
import plistlib
import sys

with open(sys.argv[1], "rb") as f:
    data = plistlib.load(f)
for entity in data.get("system-entities", []):
    if entity.get("mount-point"):
        print(entity.get("dev-entry", ""))
        break
PY
)"
if [[ -z "$MOUNTED_TARGET" ]]; then
  MOUNTED_TARGET="$MOUNT_POINT"
fi

APP_COUNT="$(find "$MOUNT_POINT" -maxdepth 1 -type d -name '*.app' -print | wc -l | tr -d ' ')"
if [[ "$APP_COUNT" -ne 1 ]]; then
  echo "Expected exactly one .app at DMG root; found $APP_COUNT." >&2
  find "$MOUNT_POINT" -maxdepth 1 -print >&2
  exit 1
fi

if [[ ! -L "$MOUNT_POINT/Applications" ]]; then
  echo "DMG is missing the /Applications drag target symlink." >&2
  find "$MOUNT_POINT" -maxdepth 1 -print >&2
  exit 1
fi

if [[ "$(readlink "$MOUNT_POINT/Applications")" != "/Applications" ]]; then
  echo "Applications drag target does not point to /Applications." >&2
  exit 1
fi

if [[ ! -f "$MOUNT_POINT/.DS_Store" ]]; then
  echo "DMG is missing .DS_Store Finder layout metadata." >&2
  find "$MOUNT_POINT" -maxdepth 1 -print >&2
  exit 1
fi

APP_PATH="$(find "$MOUNT_POINT" -maxdepth 1 -type d -name '*.app' -print -quit)"
KATAGO_BUNDLES=()
while IFS= read -r -d '' katago_path; do
  KATAGO_BUNDLES+=("$(dirname "$katago_path")")
done < <(
  find "$APP_PATH/Contents/app/engines/katago" \
    -mindepth 2 -maxdepth 2 -type f -name katago -print0 2>/dev/null
)

if [[ "${#KATAGO_BUNDLES[@]}" -gt 1 ]]; then
  echo "Expected at most one native macOS KataGo bundle; found ${#KATAGO_BUNDLES[@]}." >&2
  exit 1
fi
if [[ "${#KATAGO_BUNDLES[@]}" -eq 1 ]]; then
  SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  python3 "$SCRIPT_DIR/macos_katago_bundle.py" audit \
    --bundle "${KATAGO_BUNDLES[0]}"
fi

echo "Validated macOS drag-install DMG layout: $(basename "$DMG_PATH")"

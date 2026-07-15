#!/usr/bin/env bash
set -euo pipefail

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "create_macos_drag_dmg.sh only supports macOS." >&2
  exit 1
fi

if [[ $# -ne 3 ]]; then
  echo "Usage: $0 <volume-name> <source-folder-containing-app> <output.dmg>" >&2
  exit 1
fi

VOLUME_NAME="$1"
SOURCE_FOLDER="$2"
OUTPUT_DMG="$3"

if [[ ! -d "$SOURCE_FOLDER" ]]; then
  echo "Source folder not found: $SOURCE_FOLDER" >&2
  exit 1
fi

if ! command -v hdiutil >/dev/null 2>&1; then
  echo "hdiutil not found." >&2
  exit 1
fi

APP_BUNDLES=()
while IFS= read -r -d '' app_bundle; do
  APP_BUNDLES+=("$app_bundle")
done < <(find "$SOURCE_FOLDER" -maxdepth 1 -type d -name '*.app' -print0)
if [[ "${#APP_BUNDLES[@]}" -ne 1 ]]; then
  echo "Expected exactly one .app bundle in $SOURCE_FOLDER; found ${#APP_BUNDLES[@]}." >&2
  exit 1
fi

APP_NAME="$(basename "${APP_BUNDLES[0]}")"
OUTPUT_DIR="$(dirname "$OUTPUT_DMG")"
mkdir -p "$OUTPUT_DIR"

WORK_DIR="$(mktemp -d -t lizzieyzy-dmg-layout.XXXXXX)"
STAGING_DIR="$WORK_DIR/staging"
RW_DMG="$WORK_DIR/layout-rw.dmg"
MOUNT_POINT="$WORK_DIR/mount"
MOUNTED=0

detach_mount() {
  local attempt
  if [[ "$MOUNTED" -ne 1 ]]; then
    return 0
  fi
  for attempt in 1 2 3 4 5; do
    if hdiutil detach "$MOUNT_POINT" -quiet >/dev/null 2>&1; then
      MOUNTED=0
      return 0
    fi
    sleep "$attempt"
  done
  hdiutil detach "$MOUNT_POINT" -force -quiet >/dev/null 2>&1 || true
  MOUNTED=0
}

cleanup() {
  detach_mount
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

mkdir -p "$STAGING_DIR" "$MOUNT_POINT"
ditto "$SOURCE_FOLDER" "$STAGING_DIR"
rm -f "$STAGING_DIR/.DS_Store"
rm -rf "$STAGING_DIR/Applications"
ln -s /Applications "$STAGING_DIR/Applications"

hdiutil create \
  -quiet \
  -volname "$VOLUME_NAME" \
  -fs HFS+ \
  -srcfolder "$STAGING_DIR" \
  -format UDRW \
  -ov \
  "$RW_DMG"

hdiutil attach "$RW_DMG" \
  -mountpoint "$MOUNT_POINT" \
  -readwrite \
  -noverify \
  -noautoopen \
  -quiet
MOUNTED=1

if command -v osascript >/dev/null 2>&1; then
  # Finder writes the polished install-window layout into .DS_Store. The
  # Applications symlink is still present even if this visual pass is skipped.
  if ! osascript >/dev/null <<OSA
tell application "Finder"
  set dmgRoot to POSIX file "$MOUNT_POINT" as alias
  open dmgRoot
  delay 1
  set dmgWindow to container window of dmgRoot
  set current view of dmgWindow to icon view
  try
    set toolbar visible of dmgWindow to false
  end try
  try
    set statusbar visible of dmgWindow to false
  end try
  set the bounds of dmgWindow to {160, 120, 760, 430}
  set viewOptions to the icon view options of dmgWindow
  set arrangement of viewOptions to not arranged
  set icon size of viewOptions to 112
  set text size of viewOptions to 13
  set background color of viewOptions to {65535, 65535, 65535}
  set position of item "$APP_NAME" of dmgRoot to {170, 150}
  set position of item "Applications" of dmgRoot to {430, 150}
  update dmgRoot without registering applications
  delay 1
end tell
OSA
  then
    echo "Warning: Finder layout customization failed; DMG still includes Applications drag target." >&2
  fi
else
  echo "Warning: osascript not found; DMG still includes Applications drag target." >&2
fi

sync
detach_mount

TMP_OUTPUT="$WORK_DIR/$(basename "$OUTPUT_DMG")"
rm -f "$OUTPUT_DMG" "$TMP_OUTPUT"
hdiutil convert "$RW_DMG" \
  -quiet \
  -format UDZO \
  -imagekey zlib-level=9 \
  -o "$TMP_OUTPUT"
mv "$TMP_OUTPUT" "$OUTPUT_DMG"

echo "Created drag-install DMG: $OUTPUT_DMG"

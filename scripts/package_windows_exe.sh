#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
source "$ROOT_DIR/scripts/release_metadata.sh"

DATE_TAG="${1:-$(date +%F)}"
APP_VERSION="${2:-2.5.3}"
JAR_PATH="${3:-target/lizzie-yzy2.5.3-shaded.jar}"
WINDOWS_UPGRADE_UUID="${WINDOWS_UPGRADE_UUID:-c2ef73ec-f99a-4f3d-b950-f52c0186122a}"

if ! command -v jpackage >/dev/null 2>&1; then
  echo "jpackage not found. Please use JDK 14+ with jpackage."
  exit 1
fi

if [[ ! -f "$JAR_PATH" ]]; then
  echo "Jar not found: $JAR_PATH"
  echo "Build first: mvn -DskipTests package"
  exit 1
fi

APP_NAME="LizzieYzy Next"
APP_DESCRIPTION="Maintained LizzieYzy build with Fox nickname fetch and easier KataGo setup"
MAIN_JAR="$(basename "$JAR_PATH")"
ICON_PATH="$ROOT_DIR/packaging/icons/app-icon.ico"
ENGINE_PLATFORM_DIR="windows-x64"
ARCH_TAG="windows64"
DIST_DIR="$ROOT_DIR/dist/windows"
RELEASE_DIR="$ROOT_DIR/dist/release"
META_DIR="$ROOT_DIR/dist/release-meta"

rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR" "$RELEASE_DIR" "$META_DIR"

derive_windows_app_version() {
  local date_tag="$1"
  local build_serial="${WINDOWS_BUILD_SERIAL:-0}"
  local python_bin=""

  if ! [[ "$build_serial" =~ ^[0-9]+$ ]]; then
    build_serial=0
  fi
  if (( build_serial < 0 )); then
    build_serial=0
  elif (( build_serial > 99 )); then
    build_serial=99
  fi

  if command -v python3 >/dev/null 2>&1; then
    python_bin="python3"
  elif command -v python >/dev/null 2>&1; then
    python_bin="python"
  else
    printf '%s\n' "$APP_VERSION"
    return 0
  fi

  "$python_bin" - "$date_tag" "$build_serial" "$APP_VERSION" <<'PY'
from datetime import datetime
import sys

date_tag = sys.argv[1]
build_serial = int(sys.argv[2])
fallback = sys.argv[3]

try:
    dt = datetime.strptime(date_tag, "%Y-%m-%d")
except ValueError:
    print(fallback)
    raise SystemExit(0)

year_offset = max(0, dt.year - 2020)
patch = dt.timetuple().tm_yday * 100 + build_serial
print(f"2.{year_offset}.{patch}")
PY
}

WINDOWS_APP_VERSION="$(derive_windows_app_version "$DATE_TAG")"

has_bundled_katago() {
  [[ -f "$ROOT_DIR/weights/default.bin.gz" ]] \
    && [[ -d "$ROOT_DIR/engines/katago/$ENGINE_PLATFORM_DIR" ]] \
    && find "$ROOT_DIR/engines/katago/$ENGINE_PLATFORM_DIR" -maxdepth 1 -type f | grep -q .
}

copy_common_inputs() {
  local input_dir="$1"

  mkdir -p "$input_dir"
  cp "$JAR_PATH" "$input_dir/"
  cp README.md README_EN.md README_JA.md README_KO.md LICENSE.txt "$input_dir/"
  cp readme_cn.pdf readme_en.pdf "$input_dir/"
}

copy_bundle_engine_assets() {
  local input_dir="$1"

  mkdir -p "$input_dir/engines/katago" "$input_dir/weights"
  cp -R "$ROOT_DIR/engines/katago/$ENGINE_PLATFORM_DIR" "$input_dir/engines/katago/"
  if [[ -d "$ROOT_DIR/engines/katago/configs" ]]; then
    cp -R "$ROOT_DIR/engines/katago/configs" "$input_dir/engines/katago/"
  fi
  if [[ -f "$ROOT_DIR/engines/katago/VERSION.txt" ]]; then
    cp "$ROOT_DIR/engines/katago/VERSION.txt" "$input_dir/engines/katago/"
  fi
  cp "$ROOT_DIR/weights/default.bin.gz" "$input_dir/weights/default.bin.gz"
}

to_native_path() {
  local path="$1"
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -w "$path"
  else
    printf '%s\n' "$path"
  fi
}

build_app_image() {
  local flavor="$1"
  local include_katago="$2"
  local input_dir="$DIST_DIR/input-$flavor"
  local app_image_dir="$DIST_DIR/app-image-$flavor"

  rm -rf "$input_dir" "$app_image_dir"
  copy_common_inputs "$input_dir"
  if [[ "$include_katago" == "true" ]]; then
    copy_bundle_engine_assets "$input_dir"
  fi

  jpackage \
    --type app-image \
    --name "$APP_NAME" \
    --input "$input_dir" \
    --main-jar "$MAIN_JAR" \
    --main-class featurecat.lizzie.Lizzie \
    --dest "$app_image_dir" \
    --app-version "$WINDOWS_APP_VERSION" \
    --vendor "wimi321" \
    --description "$APP_DESCRIPTION" \
    --icon "$ICON_PATH" \
    --java-options "-Xmx4096m"

  printf '%s\n' "$app_image_dir/$APP_NAME"
}

build_installer() {
  local flavor="$1"
  local include_katago="$2"
  local input_dir="$DIST_DIR/input-$flavor"
  local installer_dir="$DIST_DIR/installer-$flavor"

  rm -rf "$installer_dir"
  copy_common_inputs "$input_dir"
  if [[ "$include_katago" == "true" ]]; then
    copy_bundle_engine_assets "$input_dir"
  fi

  jpackage \
    --type exe \
    --name "$APP_NAME" \
    --input "$input_dir" \
    --main-jar "$MAIN_JAR" \
    --main-class featurecat.lizzie.Lizzie \
    --dest "$installer_dir" \
    --app-version "$WINDOWS_APP_VERSION" \
    --vendor "wimi321" \
    --description "$APP_DESCRIPTION" \
    --icon "$ICON_PATH" \
    --win-dir-chooser \
    --win-menu \
    --win-shortcut \
    --win-upgrade-uuid "$WINDOWS_UPGRADE_UUID" \
    --java-options "-Xmx4096m"

  find "$installer_dir" -maxdepth 1 -type f -name '*.exe' | head -n 1
}

write_windows_install_note() {
  local has_with_katago="$1"
  local has_no_engine_installer="$2"
  local note_file="$META_DIR/${DATE_TAG}-${ARCH_TAG}-install.txt"

  cat >"$note_file" <<EOF
Package type: Windows x64 release assets
Generated on: $DATE_TAG

How to pick the right file:
EOF

  if [[ "$has_with_katago" == "true" ]]; then
    cat >>"$note_file" <<EOF
- ${DATE_TAG}-${ARCH_TAG}.with-katago.installer.exe
  Recommended for most users. Run the installer, finish setup, then launch from Start Menu or desktop.
- ${DATE_TAG}-${ARCH_TAG}.with-katago.portable.zip
  Use this if you do not want the installer. Unzip it and open ${APP_NAME}.exe.
EOF
  fi

  cat >>"$note_file" <<EOF
- ${DATE_TAG}-${ARCH_TAG}.without.engine.portable.zip
  Use this if you want to keep the packaged Java runtime but configure your own engine.
EOF

  if [[ "$has_no_engine_installer" == "true" ]]; then
    cat >>"$note_file" <<EOF
- ${DATE_TAG}-${ARCH_TAG}.without.engine.installer.exe
  Use this if you want the installer but prefer configuring your own engine.
EOF
  fi

  cat >>"$note_file" <<EOF

Download verification:
- Compare the file hash with ${DATE_TAG}-${ARCH_TAG}-sha256.txt
- PowerShell:
  Get-FileHash <filename> -Algorithm SHA256
- Command Prompt:
  certutil -hashfile <filename> SHA256

What is bundled:
- Windows release assets include a packaged Java runtime via jpackage.
EOF

  if [[ "$has_with_katago" == "true" ]]; then
    cat >>"$note_file" <<'EOF'
- The with-katago assets also include bundled KataGo and a default weight.
- First launch should auto-configure the bundled engine for most users.
EOF
  else
    cat >>"$note_file" <<'EOF'
- Bundled KataGo is not included in this build. Configure your own engine after launch.
EOF
  fi

  cat >>"$note_file" <<'EOF'

Fox kifu note:
- The maintained fork supports entering a Fox nickname.
- If a nickname search succeeds, the app will also show the matched nickname and account number in the results.
EOF
}

create_portable_zip() {
  local flavor="$1"
  local app_image_root="$2"
  local portable_zip="$RELEASE_DIR/${DATE_TAG}-${ARCH_TAG}.${flavor}.portable.zip"
  local native_root
  local native_zip

  native_root="$(to_native_path "$app_image_root")"
  native_zip="$(to_native_path "$portable_zip")"
  powershell.exe -NoProfile -Command \
    "Compress-Archive -Path '$native_root' -DestinationPath '$native_zip' -Force"
}

artifacts=()
build_no_engine_installer="true"
has_with_katago_assets="false"

if has_bundled_katago; then
  has_with_katago_assets="true"
  with_katago_root="$(build_app_image with-katago true)"
  create_portable_zip with-katago "$with_katago_root"
  installer_path="$(build_installer with-katago true)"
  final_installer="$RELEASE_DIR/${DATE_TAG}-${ARCH_TAG}.with-katago.installer.exe"
  cp "$installer_path" "$final_installer"
  artifacts+=("$final_installer" "$RELEASE_DIR/${DATE_TAG}-${ARCH_TAG}.with-katago.portable.zip")
else
  has_with_katago_assets="false"
fi

without_engine_root="$(build_app_image without.engine false)"
create_portable_zip without.engine "$without_engine_root"
installer_path="$(build_installer without.engine false)"
final_installer="$RELEASE_DIR/${DATE_TAG}-${ARCH_TAG}.without.engine.installer.exe"
cp "$installer_path" "$final_installer"
artifacts+=("$final_installer")
artifacts+=("$RELEASE_DIR/${DATE_TAG}-${ARCH_TAG}.without.engine.portable.zip")

install_note="$META_DIR/${DATE_TAG}-${ARCH_TAG}-install.txt"
checksum_file="$META_DIR/${DATE_TAG}-${ARCH_TAG}-sha256.txt"
write_windows_install_note "$has_with_katago_assets" "$build_no_engine_installer"
write_sha256_file "$checksum_file" "${artifacts[@]}" "$install_note"

echo "Artifacts:"
ls -lh "${artifacts[@]}"
echo
echo "Windows installer version: $WINDOWS_APP_VERSION"
echo "Windows upgrade UUID: $WINDOWS_UPGRADE_UUID"
echo
echo "Maintainer metadata:"
ls -lh "$install_note" "$checksum_file"

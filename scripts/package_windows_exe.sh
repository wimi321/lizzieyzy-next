#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

DATE_TAG="${1:-$(date +%F)}"
APP_VERSION="${2:-2.5.3}"
JAR_PATH="${3:-target/lizzie-yzy2.5.3-shaded.jar}"

if ! command -v jpackage >/dev/null 2>&1; then
  echo "jpackage not found. Please use JDK 14+ with jpackage."
  exit 1
fi

if [[ ! -f "$JAR_PATH" ]]; then
  echo "Jar not found: $JAR_PATH"
  echo "Build first: mvn -DskipTests package"
  exit 1
fi

APP_NAME="LizzieYzy Next-FoxUID"
MAIN_JAR="$(basename "$JAR_PATH")"
ENGINE_PLATFORM_DIR="windows-x64"
ARCH_TAG="windows64"
DIST_DIR="$ROOT_DIR/dist/windows"
RELEASE_DIR="$ROOT_DIR/dist/release"

rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR" "$RELEASE_DIR"

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
    --app-version "$APP_VERSION" \
    --vendor "wimi321" \
    --description "LizzieYzy maintained fork with Fox ID sync fix" \
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
    --app-version "$APP_VERSION" \
    --vendor "wimi321" \
    --description "LizzieYzy maintained fork with Fox ID sync fix" \
    --win-dir-chooser \
    --win-menu \
    --win-shortcut \
    --java-options "-Xmx4096m"

  find "$installer_dir" -maxdepth 1 -type f -name '*.exe' | head -n 1
}

write_install_note() {
  local flavor="$1"
  local include_katago="$2"
  local include_installer="$3"
  local note_file="$RELEASE_DIR/${DATE_TAG}-${ARCH_TAG}.${flavor}-install.txt"

  cat >"$note_file" <<NOTE
Package type: Windows x64 release assets
Generated on: $DATE_TAG
Flavor: $flavor
Bundled KataGo: $include_katago
NOTE

  if [[ "$include_installer" == "true" ]]; then
    cat >>"$note_file" <<NOTE

Recommended for most users:
1. Run ${DATE_TAG}-${ARCH_TAG}.${flavor}.installer.exe
2. Follow the setup wizard
3. Launch from the Start Menu or desktop shortcut
NOTE
  fi

  cat >>"$note_file" <<NOTE

Portable package:
- ${DATE_TAG}-${ARCH_TAG}.${flavor}.portable.zip
- After unzip, open: ${APP_NAME}/${APP_NAME}.exe

Notes:
- The packaged app includes a Java runtime via jpackage.
NOTE

  if [[ "$include_katago" == "true" ]]; then
    cat >>"$note_file" <<'NOTE'
- Bundled KataGo is included in this flavor.
- First launch should auto-configure the bundled engine and default weight.
NOTE
  else
    cat >>"$note_file" <<'NOTE'
- This flavor does not include KataGo.
- Configure your own engine after launch.
NOTE
  fi
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
build_no_engine_installer="false"

if has_bundled_katago; then
  with_katago_root="$(build_app_image with-katago true)"
  create_portable_zip with-katago "$with_katago_root"
  installer_path="$(build_installer with-katago true)"
  final_installer="$RELEASE_DIR/${DATE_TAG}-${ARCH_TAG}.with-katago.installer.exe"
  cp "$installer_path" "$final_installer"
  write_install_note with-katago true true
  artifacts+=("$final_installer" "$RELEASE_DIR/${DATE_TAG}-${ARCH_TAG}.with-katago.portable.zip" "$RELEASE_DIR/${DATE_TAG}-${ARCH_TAG}.with-katago-install.txt")
else
  build_no_engine_installer="true"
fi

without_engine_root="$(build_app_image without.engine false)"
create_portable_zip without.engine "$without_engine_root"
if [[ "$build_no_engine_installer" == "true" ]]; then
  installer_path="$(build_installer without.engine false)"
  final_installer="$RELEASE_DIR/${DATE_TAG}-${ARCH_TAG}.without.engine.installer.exe"
  cp "$installer_path" "$final_installer"
  artifacts+=("$final_installer")
fi
write_install_note without.engine false "$build_no_engine_installer"
artifacts+=("$RELEASE_DIR/${DATE_TAG}-${ARCH_TAG}.without.engine.portable.zip" "$RELEASE_DIR/${DATE_TAG}-${ARCH_TAG}.without.engine-install.txt")

echo "Artifacts:"
ls -lh "${artifacts[@]}"

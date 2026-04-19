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
OPENCL_APP_NAME="LizzieYzy Next OpenCL"
OPENCL_APP_DESCRIPTION="Maintained LizzieYzy build with Fox nickname fetch and bundled OpenCL KataGo"
NVIDIA_APP_NAME="LizzieYzy Next NVIDIA"
NVIDIA_APP_DESCRIPTION="Maintained LizzieYzy build with bundled NVIDIA CUDA KataGo"
MAIN_JAR="$(basename "$JAR_PATH")"
ICON_PATH="$ROOT_DIR/packaging/icons/app-icon.ico"
ARCH_TAG="windows64"
STANDARD_ENGINE_PLATFORM_DIR="windows-x64"
OPENCL_ENGINE_PLATFORM_DIR="${WINDOWS_OPENCL_ENGINE_PLATFORM_DIR:-windows-x64-opencl}"
NVIDIA_ENGINE_PLATFORM_DIR="${WINDOWS_NVIDIA_ENGINE_PLATFORM_DIR:-windows-x64-nvidia}"
OPENCL_ARCH_TAG="${ARCH_TAG}.opencl"
NVIDIA_ARCH_TAG="${ARCH_TAG}.nvidia"
DIST_DIR="$ROOT_DIR/dist/windows"
RELEASE_DIR="$ROOT_DIR/dist/release"
META_DIR="$ROOT_DIR/dist/release-meta"
WINDOWS_UPGRADE_UUID_NVIDIA="${WINDOWS_UPGRADE_UUID_NVIDIA:-14a4599e-6d5b-4b86-9895-7748266f0c25}"
WINDOWS_UPGRADE_UUID_OPENCL="${WINDOWS_UPGRADE_UUID_OPENCL:-0ec8b17f-06b0-4f6a-9246-cf61953743cf}"
ENGINE_BACKEND_MARKER_NAME="lizzieyzy-next-engine-backend.txt"
NVIDIA_RUNTIME_PREPARE_SCRIPT="$ROOT_DIR/scripts/prepare_bundled_nvidia_runtime.py"
NVIDIA_RUNTIME_STAGE_DIR="$DIST_DIR/nvidia-runtime"
PYTHON_BIN=""

resolve_python_bin() {
  if [[ -n "$PYTHON_BIN" ]]; then
    return 0
  fi
  if command -v python3 >/dev/null 2>&1; then
    PYTHON_BIN="python3"
    return 0
  fi
  if command -v python >/dev/null 2>&1; then
    PYTHON_BIN="python"
    return 0
  fi
  echo "Python not found. Install Python 3 to prepare the Windows NVIDIA runtime."
  exit 1
}

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
  local engine_platform_dir="${1:-$STANDARD_ENGINE_PLATFORM_DIR}"
  [[ -f "$ROOT_DIR/weights/default.bin.gz" ]] \
    && [[ -d "$ROOT_DIR/engines/katago/$engine_platform_dir" ]] \
    && find "$ROOT_DIR/engines/katago/$engine_platform_dir" -maxdepth 1 -type f | grep -q .
}

copy_common_inputs() {
  local input_dir="$1"

  mkdir -p "$input_dir"
  cp "$JAR_PATH" "$input_dir/"
  cp README.md README_EN.md README_JA.md README_KO.md LICENSE.txt packaging/PROJECT_INFO.txt "$input_dir/"
  cp readme_cn.pdf readme_en.pdf "$input_dir/"
  if [[ -d "$ROOT_DIR/src/main/resources/assets/readboard_java" ]]; then
    mkdir -p "$input_dir/readboard_java"
    cp -R "$ROOT_DIR/src/main/resources/assets/readboard_java/." "$input_dir/readboard_java/"
  fi
}

copy_bundle_engine_assets() {
  local input_dir="$1"
  local engine_source_dir="${2:-$STANDARD_ENGINE_PLATFORM_DIR}"
  local engine_target_dir="${3:-$STANDARD_ENGINE_PLATFORM_DIR}"
  local engine_backend="${4:-}"

  mkdir -p "$input_dir/engines/katago" "$input_dir/weights"
  cp -R "$ROOT_DIR/engines/katago/$engine_source_dir" \
    "$input_dir/engines/katago/$engine_target_dir"
  if [[ -d "$ROOT_DIR/engines/katago/configs" ]]; then
    cp -R "$ROOT_DIR/engines/katago/configs" "$input_dir/engines/katago/"
  fi
  if [[ -f "$ROOT_DIR/engines/katago/VERSION.txt" ]]; then
    cp "$ROOT_DIR/engines/katago/VERSION.txt" "$input_dir/engines/katago/"
  fi
  if [[ -n "$engine_backend" ]]; then
    printf '%s\n' "$engine_backend" \
      >"$input_dir/engines/katago/$engine_target_dir/$ENGINE_BACKEND_MARKER_NAME"
  fi
  cp "$ROOT_DIR/weights/default.bin.gz" "$input_dir/weights/default.bin.gz"
}

prepare_bundled_nvidia_runtime_assets() {
  resolve_python_bin
  if [[ ! -f "$NVIDIA_RUNTIME_PREPARE_SCRIPT" ]]; then
    echo "Missing NVIDIA runtime prepare script: $NVIDIA_RUNTIME_PREPARE_SCRIPT"
    exit 1
  fi
  "$PYTHON_BIN" "$NVIDIA_RUNTIME_PREPARE_SCRIPT" --output-dir "$NVIDIA_RUNTIME_STAGE_DIR"
}

copy_bundle_nvidia_runtime_assets() {
  local input_dir="$1"
  local engine_target_dir="${2:-$STANDARD_ENGINE_PLATFORM_DIR}"
  local engine_dir="$input_dir/engines/katago/$engine_target_dir"

  if [[ ! -d "$NVIDIA_RUNTIME_STAGE_DIR" ]]; then
    echo "Bundled NVIDIA runtime assets not prepared: $NVIDIA_RUNTIME_STAGE_DIR"
    exit 1
  fi

  mkdir -p "$engine_dir"
  find "$NVIDIA_RUNTIME_STAGE_DIR" -maxdepth 1 -type f \( -name '*.dll' -o -name 'lizzieyzy-next-nvidia-runtime-manifest.txt' \) \
    -exec cp -f {} "$engine_dir/" \;

  if [[ -d "$NVIDIA_RUNTIME_STAGE_DIR/licenses" ]]; then
    mkdir -p "$engine_dir/licenses/nvidia-runtime"
    cp -R "$NVIDIA_RUNTIME_STAGE_DIR/licenses/." "$engine_dir/licenses/nvidia-runtime/"
  fi
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
  local app_name="${3:-$APP_NAME}"
  local app_description="${4:-$APP_DESCRIPTION}"
  local engine_source_dir="${5:-$STANDARD_ENGINE_PLATFORM_DIR}"
  local engine_target_dir="${6:-$STANDARD_ENGINE_PLATFORM_DIR}"
  local engine_backend="${7:-}"
  local input_dir="$DIST_DIR/input-$flavor"
  local app_image_dir="$DIST_DIR/app-image-$flavor"

  rm -rf "$input_dir" "$app_image_dir"
  copy_common_inputs "$input_dir"
  if [[ "$include_katago" == "true" ]]; then
    copy_bundle_engine_assets "$input_dir" "$engine_source_dir" "$engine_target_dir" "$engine_backend"
    if [[ "$engine_backend" == "nvidia" ]]; then
      copy_bundle_nvidia_runtime_assets "$input_dir" "$engine_target_dir"
    fi
  fi

  jpackage \
    --type app-image \
    --name "$app_name" \
    --input "$input_dir" \
    --main-jar "$MAIN_JAR" \
    --main-class featurecat.lizzie.Lizzie \
    --dest "$app_image_dir" \
    --app-version "$WINDOWS_APP_VERSION" \
    --vendor "wimi321" \
    --description "$app_description" \
    --icon "$ICON_PATH" \
    --java-options "-Xmx4096m"

  printf '%s\n' "$app_image_dir/$app_name"
}

build_installer() {
  local flavor="$1"
  local include_katago="$2"
  local app_name="${3:-$APP_NAME}"
  local app_description="${4:-$APP_DESCRIPTION}"
  local engine_source_dir="${5:-$STANDARD_ENGINE_PLATFORM_DIR}"
  local engine_target_dir="${6:-$STANDARD_ENGINE_PLATFORM_DIR}"
  local engine_backend="${7:-}"
  local upgrade_uuid="${8:-$WINDOWS_UPGRADE_UUID}"
  local input_dir="$DIST_DIR/input-$flavor"
  local installer_dir="$DIST_DIR/installer-$flavor"

  rm -rf "$installer_dir"
  copy_common_inputs "$input_dir"
  if [[ "$include_katago" == "true" ]]; then
    copy_bundle_engine_assets "$input_dir" "$engine_source_dir" "$engine_target_dir" "$engine_backend"
    if [[ "$engine_backend" == "nvidia" ]]; then
      copy_bundle_nvidia_runtime_assets "$input_dir" "$engine_target_dir"
    fi
  fi

  jpackage \
    --type exe \
    --name "$app_name" \
    --input "$input_dir" \
    --main-jar "$MAIN_JAR" \
    --main-class featurecat.lizzie.Lizzie \
    --dest "$installer_dir" \
    --app-version "$WINDOWS_APP_VERSION" \
    --vendor "wimi321" \
    --description "$app_description" \
    --icon "$ICON_PATH" \
    --win-dir-chooser \
    --win-menu \
    --win-shortcut \
    --win-upgrade-uuid "$upgrade_uuid" \
    --java-options "-Xmx4096m"

  find "$installer_dir" -maxdepth 1 -type f -name '*.exe' | head -n 1
}

write_windows_install_note() {
  local has_with_katago="$1"
  local has_opencl_katago="$2"
  local has_nvidia_katago="$3"
  local has_no_engine_installer="$4"
  local note_file="$META_DIR/${DATE_TAG}-${ARCH_TAG}-install.txt"

  cat >"$note_file" <<EOF
Package type: Windows x64 release assets
Generated on: $DATE_TAG

How to pick the right file:
EOF

  if [[ "$has_with_katago" == "true" ]]; then
    cat >>"$note_file" <<EOF
- ${DATE_TAG}-${ARCH_TAG}.with-katago.installer.exe
  CPU fallback build. Use this if OpenCL runs poorly on your PC and you need the safer compatibility option.
- ${DATE_TAG}-${ARCH_TAG}.with-katago.portable.zip
  CPU fallback portable build. Use this if you do not want the installer and prefer the safer compatibility option.
EOF
  fi

  if [[ "$has_opencl_katago" == "true" ]]; then
    cat >>"$note_file" <<EOF
- ${DATE_TAG}-${OPENCL_ARCH_TAG}.installer.exe
  Recommended Windows build for most users who want better KataGo speed. Choose this first if your PC can run OpenCL normally.
- ${DATE_TAG}-${OPENCL_ARCH_TAG}.portable.zip
  Recommended OpenCL portable build. Unzip it and open ${OPENCL_APP_NAME}.exe.
EOF
  fi

  if [[ "$has_nvidia_katago" == "true" ]]; then
    cat >>"$note_file" <<EOF
- ${DATE_TAG}-${NVIDIA_ARCH_TAG}.installer.exe
  Only for PCs with an NVIDIA graphics card. This bundle uses the official CUDA KataGo build for higher analysis speed.
- ${DATE_TAG}-${NVIDIA_ARCH_TAG}.portable.zip
  NVIDIA-only portable build. Unzip it and open ${NVIDIA_APP_NAME}.exe.
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
- The built-in Java readboard helper is included in `readboard_java/`.
EOF

  if [[ "$has_with_katago" == "true" ]]; then
    cat >>"$note_file" <<'EOF'
- The with-katago assets also include bundled KataGo and a default weight.
- First launch should auto-configure the bundled engine for most users.
- The CPU assets use the official KataGo CPU build as a compatibility fallback when OpenCL is not suitable.
- The CPU assets also support Smart Optimize and can save a better thread setting automatically after benchmarking.
EOF
  else
    cat >>"$note_file" <<'EOF'
- Bundled KataGo is not included in this build. Configure your own engine after launch.
EOF
  fi

  if [[ "$has_opencl_katago" == "true" ]]; then
    cat >>"$note_file" <<'EOF'
- The OpenCL assets include the official KataGo OpenCL Windows build.
- The OpenCL package is now the main recommended Windows choice for users who want better analysis speed.
- If OpenCL behaves badly on your PC, switch to the CPU package instead.
EOF
  fi

  if [[ "$has_nvidia_katago" == "true" ]]; then
    cat >>"$note_file" <<'EOF'
- The NVIDIA assets include the official KataGo CUDA 12.1 Windows build.
- The NVIDIA assets also include the required official NVIDIA runtime files, so first launch should work offline on supported NVIDIA PCs.
- If those NVIDIA runtime files are missing later, reinstall the NVIDIA package instead of downloading extra files at startup.
- Only choose the NVIDIA package if your PC has an NVIDIA GPU. If you are not sure, use the regular with-katago installer instead.
EOF
  fi

  cat >>"$note_file" <<'EOF'

Fox kifu note:
- The maintained fork supports entering a Fox nickname.
- If a nickname search succeeds, the app will also show the matched nickname and account number in the results.
EOF
}

create_portable_zip() {
  local release_basename="$1"
  local app_image_root="$2"
  local portable_zip="$RELEASE_DIR/${DATE_TAG}-${release_basename}.portable.zip"
  local native_root
  local native_zip

  native_root="$(to_native_path "$app_image_root")"
  native_zip="$(to_native_path "$portable_zip")"
  powershell.exe -NoProfile -Command \
    "Compress-Archive -Path '$native_root' -DestinationPath '$native_zip' -Force"
}

build_release_variant() {
  local flavor="$1"
  local include_katago="$2"
  local app_name="$3"
  local app_description="$4"
  local engine_source_dir="$5"
  local engine_target_dir="$6"
  local engine_backend="$7"
  local release_basename="$8"
  local upgrade_uuid="$9"

  local app_root
  local installer_path
  local final_installer

  app_root="$(build_app_image \
    "$flavor" \
    "$include_katago" \
    "$app_name" \
    "$app_description" \
    "$engine_source_dir" \
    "$engine_target_dir" \
    "$engine_backend")"
  create_portable_zip "$release_basename" "$app_root"
  installer_path="$(build_installer \
    "$flavor" \
    "$include_katago" \
    "$app_name" \
    "$app_description" \
    "$engine_source_dir" \
    "$engine_target_dir" \
    "$engine_backend" \
    "$upgrade_uuid")"
  final_installer="$RELEASE_DIR/${DATE_TAG}-${release_basename}.installer.exe"
  cp "$installer_path" "$final_installer"
  artifacts+=("$final_installer" "$RELEASE_DIR/${DATE_TAG}-${release_basename}.portable.zip")
}

artifacts=()
build_no_engine_installer="true"
has_with_katago_assets="false"
has_opencl_katago_assets="false"
has_nvidia_katago_assets="false"

if has_bundled_katago "$STANDARD_ENGINE_PLATFORM_DIR"; then
  has_with_katago_assets="true"
  build_release_variant \
    "with-katago" \
    "true" \
    "$APP_NAME" \
    "$APP_DESCRIPTION" \
    "$STANDARD_ENGINE_PLATFORM_DIR" \
    "$STANDARD_ENGINE_PLATFORM_DIR" \
    "cpu" \
    "${ARCH_TAG}.with-katago" \
    "$WINDOWS_UPGRADE_UUID"
else
  has_with_katago_assets="false"
fi

if has_bundled_katago "$OPENCL_ENGINE_PLATFORM_DIR"; then
  has_opencl_katago_assets="true"
  build_release_variant \
    "opencl" \
    "true" \
    "$OPENCL_APP_NAME" \
    "$OPENCL_APP_DESCRIPTION" \
    "$OPENCL_ENGINE_PLATFORM_DIR" \
    "$STANDARD_ENGINE_PLATFORM_DIR" \
    "opencl" \
    "$OPENCL_ARCH_TAG" \
    "$WINDOWS_UPGRADE_UUID_OPENCL"
else
  has_opencl_katago_assets="false"
fi

if has_bundled_katago "$NVIDIA_ENGINE_PLATFORM_DIR"; then
  has_nvidia_katago_assets="true"
  prepare_bundled_nvidia_runtime_assets
  build_release_variant \
    "nvidia" \
    "true" \
    "$NVIDIA_APP_NAME" \
    "$NVIDIA_APP_DESCRIPTION" \
    "$NVIDIA_ENGINE_PLATFORM_DIR" \
    "$STANDARD_ENGINE_PLATFORM_DIR" \
    "nvidia" \
    "$NVIDIA_ARCH_TAG" \
    "$WINDOWS_UPGRADE_UUID_NVIDIA"
else
  has_nvidia_katago_assets="false"
fi

build_release_variant \
  "without.engine" \
  "false" \
  "$APP_NAME" \
  "$APP_DESCRIPTION" \
  "$STANDARD_ENGINE_PLATFORM_DIR" \
  "$STANDARD_ENGINE_PLATFORM_DIR" \
  "" \
  "${ARCH_TAG}.without.engine" \
  "$WINDOWS_UPGRADE_UUID"

install_note="$META_DIR/${DATE_TAG}-${ARCH_TAG}-install.txt"
checksum_file="$META_DIR/${DATE_TAG}-${ARCH_TAG}-sha256.txt"
write_windows_install_note \
  "$has_with_katago_assets" \
  "$has_opencl_katago_assets" \
  "$has_nvidia_katago_assets" \
  "$build_no_engine_installer"
write_sha256_file "$checksum_file" "${artifacts[@]}" "$install_note"

echo "Artifacts:"
ls -lh "${artifacts[@]}"
echo
echo "Windows installer version: $WINDOWS_APP_VERSION"
echo "Windows upgrade UUID: $WINDOWS_UPGRADE_UUID"
echo "Windows OpenCL upgrade UUID: $WINDOWS_UPGRADE_UUID_OPENCL"
echo "Windows NVIDIA upgrade UUID: $WINDOWS_UPGRADE_UUID_NVIDIA"
echo
echo "Maintainer metadata:"
ls -lh "$install_note" "$checksum_file"

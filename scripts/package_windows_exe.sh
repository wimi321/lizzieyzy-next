#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
source "$ROOT_DIR/scripts/release_metadata.sh"

DATE_TAG="${1:-$(date +%F)}"
APP_VERSION="${2:-1.0.0}"
JAR_PATH="${3:-target/lizzie-yzy2.5.3-shaded.jar}"
APP_DISPLAY_VERSION="${LIZZIE_NEXT_VERSION:-${4:-next-dev}}"
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
NVIDIA50_CUDA_APP_NAME="LizzieYzy Next NVIDIA 50 CUDA"
NVIDIA50_CUDA_APP_DESCRIPTION="Maintained LizzieYzy build with bundled RTX 50 CUDA KataGo"
NVIDIA50_TRT_APP_NAME="LizzieYzy Next NVIDIA 50 TensorRT"
NVIDIA50_TRT_APP_DESCRIPTION="Maintained LizzieYzy build with bundled RTX 50 TensorRT KataGo"
MAIN_JAR="$(basename "$JAR_PATH")"
ICON_PATH="$ROOT_DIR/packaging/icons/app-icon.ico"
ARCH_TAG="windows64"
STANDARD_ENGINE_PLATFORM_DIR="windows-x64"
OPENCL_ENGINE_PLATFORM_DIR="${WINDOWS_OPENCL_ENGINE_PLATFORM_DIR:-windows-x64-opencl}"
NVIDIA_ENGINE_PLATFORM_DIR="${WINDOWS_NVIDIA_ENGINE_PLATFORM_DIR:-windows-x64-nvidia}"
NVIDIA50_CUDA_ENGINE_PLATFORM_DIR="${WINDOWS_NVIDIA50_CUDA_ENGINE_PLATFORM_DIR:-windows-x64-nvidia50-cuda}"
NVIDIA50_TRT_ENGINE_PLATFORM_DIR="${WINDOWS_NVIDIA50_TRT_ENGINE_PLATFORM_DIR:-windows-x64-nvidia50-trt}"
OPENCL_ARCH_TAG="${ARCH_TAG}.opencl"
NVIDIA_ARCH_TAG="${ARCH_TAG}.nvidia"
NVIDIA50_CUDA_ARCH_TAG="${ARCH_TAG}.nvidia50.cuda"
NVIDIA50_TRT_ARCH_TAG="${ARCH_TAG}.nvidia50.trt"
BUILD_NVIDIA50_TRT_INSTALLER="${WINDOWS_BUILD_NVIDIA50_TRT_INSTALLER:-false}"
DIST_DIR="$ROOT_DIR/dist/windows"
RELEASE_DIR="$ROOT_DIR/dist/release"
META_DIR="$ROOT_DIR/dist/release-meta"
WINDOWS_UPGRADE_UUID_NVIDIA="${WINDOWS_UPGRADE_UUID_NVIDIA:-14a4599e-6d5b-4b86-9895-7748266f0c25}"
WINDOWS_UPGRADE_UUID_NVIDIA50_CUDA="${WINDOWS_UPGRADE_UUID_NVIDIA50_CUDA:-8339893c-59d8-4bb0-9cde-e54d6bb969f5}"
WINDOWS_UPGRADE_UUID_NVIDIA50_TRT="${WINDOWS_UPGRADE_UUID_NVIDIA50_TRT:-d7d66f6f-3a05-4662-b5b8-0677b87a1a43}"
WINDOWS_UPGRADE_UUID_OPENCL="${WINDOWS_UPGRADE_UUID_OPENCL:-0ec8b17f-06b0-4f6a-9246-cf61953743cf}"
ENGINE_BACKEND_MARKER_NAME="lizzieyzy-next-engine-backend.txt"
NVIDIA_RUNTIME_PREPARE_SCRIPT="$ROOT_DIR/scripts/prepare_bundled_nvidia_runtime.py"
NVIDIA_RUNTIME_STAGE_DIR="$DIST_DIR/nvidia-runtime/cuda12.1-cudnn8"
NVIDIA50_CUDA_RUNTIME_STAGE_DIR="$DIST_DIR/nvidia-runtime/cuda12.8-cudnn9"
NVIDIA50_TRT_RUNTIME_STAGE_DIR="$DIST_DIR/nvidia-runtime/trt10.9-cuda12.8"
JCEF_BUNDLE_PREPARE_SCRIPT="$ROOT_DIR/scripts/prepare_bundled_jcef.py"
JCEF_BUNDLE_STAGE_DIR="$DIST_DIR/jcef-bundle"
JCEF_PLATFORM="${WINDOWS_JCEF_PLATFORM:-windows-amd64}"
JCEF_RELEASE_TAG="jcef-99c2f7a+cef-127.3.1+g6cbb30e+chromium-127.0.6533.100"
JCEF_ASSET_SHA256="6d0466b9d5a2c4607a8a9eded1b5b9f77ca4514eadb68a1333b19053a462ceac"
READBOARD_RELEASE_REPO="${READBOARD_RELEASE_REPO:-qiyi71w/readboard}"
READBOARD_RELEASE_TAG="${READBOARD_RELEASE_TAG:-v3.0.5}"
READBOARD_ASSET_NAME="${READBOARD_ASSET_NAME:-readboard-github-release-v3.0.5.zip}"
READBOARD_ASSET_SHA256="${READBOARD_ASSET_SHA256:-160e399b06e7d19bd61dc5be1d6ef827b88afc6fca88f0d91a7fdb103a34dde1}"
READBOARD_RELEASE_API="${READBOARD_RELEASE_API:-https://api.github.com/repos/${READBOARD_RELEASE_REPO}/releases/tags/${READBOARD_RELEASE_TAG}}"
READBOARD_CACHE_DIR="$ROOT_DIR/.cache/readboard"
READBOARD_STAGE_DIR="$DIST_DIR/readboard"
PYTHON_BIN=""

log_step() {
  printf '\n[%s] %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$*" >&2
}

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

prepare_bundled_readboard_assets() {
  resolve_python_bin
  log_step "Preparing pinned native readboard assets"
  "$PYTHON_BIN" - \
    "$READBOARD_RELEASE_API" \
    "$READBOARD_CACHE_DIR" \
    "$READBOARD_STAGE_DIR" \
    "$READBOARD_RELEASE_TAG" \
    "$READBOARD_ASSET_NAME" \
    "$READBOARD_ASSET_SHA256" <<'PY'
import json
import hashlib
import os
import shutil
import sys
import tempfile
import urllib.request
import zipfile

api_url, cache_dir, stage_dir, release_tag, asset_name, expected_sha256 = sys.argv[1:7]
source_dir = os.environ.get("READBOARD_SOURCE_DIR", "").strip()
github_token = (
    os.environ.get("READBOARD_GITHUB_TOKEN")
    or os.environ.get("GITHUB_TOKEN")
    or os.environ.get("GH_TOKEN")
    or ""
).strip()
expected_sha256 = expected_sha256.replace("sha256:", "").lower()

def github_headers(accept):
    headers = {
        "Accept": accept,
        "User-Agent": "LizzieYzy-Next-Packager",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    if github_token:
        headers["Authorization"] = f"Bearer {github_token}"
    return headers

def reset_dir(path):
    if os.path.exists(path):
        shutil.rmtree(path)
    os.makedirs(path, exist_ok=True)

def copy_contents(src, dst):
    reset_dir(dst)
    for name in os.listdir(src):
        source = os.path.join(src, name)
        target = os.path.join(dst, name)
        if os.path.isdir(source):
            shutil.copytree(source, target)
        else:
            shutil.copy2(source, target)

def find_readboard_root(root):
    candidates = []
    for current, _dirs, files in os.walk(root):
        lower_files = {name.lower() for name in files}
        if "readboard.exe" in lower_files or "readboard.bat" in lower_files:
            has_exe = "readboard.exe" in lower_files
            candidates.append((0 if has_exe else 1, len(current), current))
    if not candidates:
        raise SystemExit("Native readboard package did not contain readboard.exe/readboard.bat")
    candidates.sort()
    return candidates[0][2]

def file_sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()

def write_manifest(source):
    manifest_path = os.path.join(stage_dir, "lizzieyzy-next-readboard-manifest.txt")
    with open(manifest_path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(f"release-tag={release_tag}\n")
        handle.write(f"asset-name={asset_name}\n")
        handle.write(f"sha256={expected_sha256}\n")
        handle.write(f"source={source}\n")
        handle.write("requested-execution-level=asInvoker\n")

def patch_readboard_manifest_to_as_invoker(path):
    require_admin = b'level="requireAdministrator"'
    as_invoker = b'level="asInvoker"' + (b" " * (len(require_admin) - len(b'level="asInvoker"')))
    with open(path, "rb") as handle:
        data = handle.read()
    patch_count = data.count(require_admin)
    if patch_count:
        data = data.replace(require_admin, as_invoker)
        with open(path, "wb") as handle:
            handle.write(data)
    if require_admin in data:
        raise SystemExit(
            "Bundled readboard.exe still requests administrator privileges after manifest patch"
        )
    if b'level="asInvoker"' not in data:
        raise SystemExit("Bundled readboard.exe manifest does not request asInvoker")
    return patch_count

if source_dir:
    if not os.path.isdir(source_dir):
        raise SystemExit(f"READBOARD_SOURCE_DIR does not exist: {source_dir}")
    copy_contents(find_readboard_root(source_dir), stage_dir)
    write_manifest(f"local:{source_dir}")
else:
    tag_cache_dir = os.path.join(cache_dir, release_tag)
    os.makedirs(tag_cache_dir, exist_ok=True)
    request = urllib.request.Request(api_url, headers=github_headers("application/vnd.github+json"))
    with urllib.request.urlopen(request, timeout=30) as response:
        metadata = json.load(response)
    if metadata.get("tag_name") != release_tag:
        raise SystemExit(
            f"Expected readboard release tag {release_tag}, got {metadata.get('tag_name')}"
        )
    asset = None
    for candidate in metadata.get("assets", []):
        name = candidate.get("name", "")
        if name == asset_name:
            asset = candidate
            break
    if not asset or not asset.get("browser_download_url"):
        raise SystemExit(
            f"Unable to find pinned native readboard asset {asset_name} in {release_tag}"
        )
    asset_digest = (asset.get("digest") or "").replace("sha256:", "").lower()
    if asset_digest and asset_digest != expected_sha256:
        raise SystemExit(
            f"Readboard release digest mismatch: expected {expected_sha256}, got {asset_digest}"
        )

    zip_path = os.path.join(tag_cache_dir, asset_name)
    if os.path.exists(zip_path) and file_sha256(zip_path) != expected_sha256:
        os.remove(zip_path)
    if not os.path.exists(zip_path) or os.path.getsize(zip_path) == 0:
        tmp_path = zip_path + ".tmp"
        with urllib.request.urlopen(asset["browser_download_url"], timeout=120) as response:
            with open(tmp_path, "wb") as out:
                shutil.copyfileobj(response, out)
        os.replace(tmp_path, zip_path)
    actual_sha256 = file_sha256(zip_path)
    if actual_sha256 != expected_sha256:
        raise SystemExit(
            f"Downloaded readboard checksum mismatch: expected {expected_sha256}, got {actual_sha256}"
        )

    temp_dir = tempfile.mkdtemp(prefix="readboard-", dir=tag_cache_dir)
    try:
        with zipfile.ZipFile(zip_path) as archive:
            archive.extractall(temp_dir)
        copy_contents(find_readboard_root(temp_dir), stage_dir)
        write_manifest(f"github:{release_tag}")
    finally:
        shutil.rmtree(temp_dir, ignore_errors=True)

readboard_exe = os.path.join(stage_dir, "readboard.exe")
if not os.path.isfile(readboard_exe):
    raise SystemExit("Windows release must include native readboard.exe")
manifest_patch_count = patch_readboard_manifest_to_as_invoker(readboard_exe)
print(f"Prepared native readboard assets {release_tag}/{asset_name} in {stage_dir}")
print(
    "Patched native readboard requestedExecutionLevel to asInvoker "
    f"({manifest_patch_count} replacement(s))"
)
PY
}

copy_bundled_readboard_assets() {
  local input_dir="$1"

  if [[ ! -f "$READBOARD_STAGE_DIR/readboard.exe" ]]; then
    echo "Missing bundled native readboard.exe in $READBOARD_STAGE_DIR"
    exit 1
  fi

  mkdir -p "$input_dir/readboard"
  cp -R "$READBOARD_STAGE_DIR/." "$input_dir/readboard/"
}

prepare_bundled_jcef_assets() {
  resolve_python_bin
  if [[ ! -f "$JCEF_BUNDLE_PREPARE_SCRIPT" ]]; then
    echo "Missing JCEF prepare script: $JCEF_BUNDLE_PREPARE_SCRIPT"
    exit 1
  fi
  log_step "Preparing pinned JCEF browser runtime assets [$JCEF_PLATFORM]"
  "$PYTHON_BIN" "$JCEF_BUNDLE_PREPARE_SCRIPT" \
    --platform "$JCEF_PLATFORM" \
    --cache-dir "$ROOT_DIR/.cache/jcef" \
    --output-dir "$JCEF_BUNDLE_STAGE_DIR"
}

copy_bundled_jcef_assets() {
  local input_dir="$1"

  if [[ ! -f "$JCEF_BUNDLE_STAGE_DIR/build_meta.json" ]] \
    || [[ ! -f "$JCEF_BUNDLE_STAGE_DIR/install.lock" ]] \
    || [[ ! -f "$JCEF_BUNDLE_STAGE_DIR/libcef.dll" ]] \
    || [[ ! -f "$JCEF_BUNDLE_STAGE_DIR/lizzieyzy-next-jcef-manifest.txt" ]]; then
    echo "Missing prepared bundled JCEF browser runtime in $JCEF_BUNDLE_STAGE_DIR"
    exit 1
  fi

  mkdir -p "$input_dir/jcef-bundle"
  cp -R "$JCEF_BUNDLE_STAGE_DIR/." "$input_dir/jcef-bundle/"
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
  copy_bundled_readboard_assets "$input_dir"
  copy_bundled_jcef_assets "$input_dir"
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
  local runtime_profile="$1"
  local runtime_stage_dir="$2"
  resolve_python_bin
  if [[ ! -f "$NVIDIA_RUNTIME_PREPARE_SCRIPT" ]]; then
    echo "Missing NVIDIA runtime prepare script: $NVIDIA_RUNTIME_PREPARE_SCRIPT"
    exit 1
  fi
  log_step "Preparing NVIDIA runtime DLLs [$runtime_profile]"
  "$PYTHON_BIN" "$NVIDIA_RUNTIME_PREPARE_SCRIPT" \
    --profile "$runtime_profile" \
    --output-dir "$runtime_stage_dir"
}

copy_bundle_nvidia_runtime_assets() {
  local input_dir="$1"
  local engine_target_dir="${2:-$STANDARD_ENGINE_PLATFORM_DIR}"
  local runtime_stage_dir="${3:-$NVIDIA_RUNTIME_STAGE_DIR}"
  local engine_dir="$input_dir/engines/katago/$engine_target_dir"

  if [[ ! -d "$runtime_stage_dir" ]]; then
    echo "Bundled NVIDIA runtime assets not prepared: $runtime_stage_dir"
    exit 1
  fi

  mkdir -p "$engine_dir"
  find "$runtime_stage_dir" -maxdepth 1 -type f \( -name '*.dll' -o -name 'lizzieyzy-next-nvidia-runtime-manifest.txt' \) \
    -exec cp -f {} "$engine_dir/" \;

  if [[ -d "$runtime_stage_dir/licenses" ]]; then
    mkdir -p "$engine_dir/licenses/nvidia-runtime"
    cp -R "$runtime_stage_dir/licenses/." "$engine_dir/licenses/nvidia-runtime/"
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
  local runtime_stage_dir="${8:-}"
  local input_dir="$DIST_DIR/input-$flavor"
  local app_image_dir="$DIST_DIR/app-image-$flavor"

  rm -rf "$input_dir" "$app_image_dir"
  copy_common_inputs "$input_dir"
  if [[ "$include_katago" == "true" ]]; then
    copy_bundle_engine_assets "$input_dir" "$engine_source_dir" "$engine_target_dir" "$engine_backend"
    if [[ "$engine_backend" == nvidia* ]]; then
      copy_bundle_nvidia_runtime_assets "$input_dir" "$engine_target_dir" "$runtime_stage_dir"
    fi
  fi

  log_step "Building Windows app image: $app_name [$flavor]"
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
    --jlink-options "--strip-debug --no-man-pages --no-header-files" \
    --java-options "-Xmx4096m" \
    --java-options "-Dlizzie.next.version=$APP_DISPLAY_VERSION" >&2
  if [[ ! -f "$app_image_dir/$app_name/runtime/bin/java.exe" ]]; then
    echo "Packaged Windows runtime is missing runtime/bin/java.exe: $app_image_dir/$app_name" >&2
    return 1
  fi
  log_step "Finished Windows app image: $app_name [$flavor]"

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
  local runtime_stage_dir="${8:-}"
  local upgrade_uuid="${9:-$WINDOWS_UPGRADE_UUID}"
  local input_dir="$DIST_DIR/input-$flavor"
  local installer_dir="$DIST_DIR/installer-$flavor"

  rm -rf "$installer_dir"
  copy_common_inputs "$input_dir"
  if [[ "$include_katago" == "true" ]]; then
    copy_bundle_engine_assets "$input_dir" "$engine_source_dir" "$engine_target_dir" "$engine_backend"
    if [[ "$engine_backend" == nvidia* ]]; then
      copy_bundle_nvidia_runtime_assets "$input_dir" "$engine_target_dir" "$runtime_stage_dir"
    fi
  fi

  log_step "Building Windows installer: $app_name [$flavor]"
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
    --jlink-options "--strip-debug --no-man-pages --no-header-files" \
    --java-options "-Xmx4096m" \
    --java-options "-Dlizzie.next.version=$APP_DISPLAY_VERSION" >&2
  log_step "Finished Windows installer: $app_name [$flavor]"

  find "$installer_dir" -maxdepth 1 -type f -name '*.exe' | head -n 1
}

write_windows_install_note() {
  local has_with_katago="$1"
  local has_opencl_katago="$2"
  local has_nvidia_katago="$3"
  local has_nvidia50_cuda_katago="$4"
  local has_nvidia50_trt_katago="$5"
  local has_no_engine_installer="$6"
  local note_file="$META_DIR/${DATE_TAG}-${ARCH_TAG}-install.txt"

  cat >"$note_file" <<EOF
Package type: Windows x64 release assets
Generated on: $DATE_TAG
Release display version: $APP_DISPLAY_VERSION

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
  Stable NVIDIA package for RTX 20/30/40 series and other CUDA 12.1 compatible PCs.
- ${DATE_TAG}-${NVIDIA_ARCH_TAG}.portable.zip
  NVIDIA-only portable build. Unzip it and open ${NVIDIA_APP_NAME}.exe.
EOF
  fi

  if [[ "$has_nvidia50_cuda_katago" == "true" ]]; then
    cat >>"$note_file" <<EOF
- ${DATE_TAG}-${NVIDIA50_CUDA_ARCH_TAG}.installer.exe
  RTX 50 series CUDA package. Choose this first for RTX 5070/5080/5090.
- ${DATE_TAG}-${NVIDIA50_CUDA_ARCH_TAG}.portable.zip
  RTX 50 CUDA portable build. Unzip it and open ${NVIDIA50_CUDA_APP_NAME}.exe.
EOF
  fi

  if [[ "$has_nvidia50_trt_katago" == "true" ]]; then
    cat >>"$note_file" <<EOF
- ${DATE_TAG}-${NVIDIA50_TRT_ARCH_TAG}.portable.zip
  Experimental RTX 50 TensorRT portable build. Unzip it and open ${NVIDIA50_TRT_APP_NAME}.exe.
EOF
    if [[ "$BUILD_NVIDIA50_TRT_INSTALLER" == "true" ]]; then
      cat >>"$note_file" <<EOF
- ${DATE_TAG}-${NVIDIA50_TRT_ARCH_TAG}.installer.exe
  Experimental RTX 50 TensorRT installer. This is disabled by default because the TensorRT runtime makes the MSI/EXE build very large.
EOF
    fi
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
- Native Windows readboard is included in `readboard/`.
- Native Windows readboard is pinned to qiyi71w/readboard ${READBOARD_RELEASE_TAG} (${READBOARD_ASSET_NAME}, SHA256 ${READBOARD_ASSET_SHA256}).
- The built-in Java readboard helper is also included in `readboard_java/` for the explicit Java sync entry.
- The JCEF browser runtime for Yike web page and Yike hall is included in `jcef-bundle/` (${JCEF_RELEASE_TAG}, SHA256 ${JCEF_ASSET_SHA256}), so these entries do not download browser components on first use.
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
- The NVIDIA assets include the official KataGo CUDA 12.1 Windows build and remain the stable choice for RTX 20/30/40 series users.
- The NVIDIA assets also include the required official NVIDIA runtime files, so first launch should work offline on supported NVIDIA PCs.
- If those NVIDIA runtime files are missing later, reinstall the NVIDIA package instead of downloading extra files at startup.
EOF
  fi

  if [[ "$has_nvidia50_cuda_katago" == "true" ]]; then
    cat >>"$note_file" <<'EOF'
- The NVIDIA 50 CUDA assets include the official KataGo CUDA 12.8 / cuDNN 9.8 Windows build for Blackwell RTX 50 series GPUs.
- RTX 5070/5080/5090 users should try the NVIDIA 50 CUDA package first.
EOF
  fi

  if [[ "$has_nvidia50_trt_katago" == "true" ]]; then
    cat >>"$note_file" <<'EOF'
- The NVIDIA 50 TensorRT assets include the official KataGo TensorRT 10.9 / CUDA 12.8 Windows build and are experimental.
- Use the TensorRT package only if you are willing to test and report NVIDIA driver, KataGo stderr, and Smart Optimize results.
EOF
  fi

  if [[ "$has_nvidia_katago" == "true" || "$has_nvidia50_cuda_katago" == "true" || "$has_nvidia50_trt_katago" == "true" ]]; then
    cat >>"$note_file" <<'EOF'
- Only choose an NVIDIA package if your PC has an NVIDIA GPU. If you are not sure, use the regular with-katago installer instead.
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
  log_step "Creating Windows portable zip: $(basename "$portable_zip")"
  powershell.exe -NoProfile -Command \
    "Compress-Archive -Path '$native_root' -DestinationPath '$native_zip' -Force"
  log_step "Finished Windows portable zip: $(basename "$portable_zip")"
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
  local runtime_stage_dir="${10:-}"
  local build_installer_asset="${11:-true}"

  local app_root
  local installer_path
  local final_installer

  log_step "Starting Windows release variant: $release_basename"
  app_root="$(build_app_image \
    "$flavor" \
    "$include_katago" \
    "$app_name" \
    "$app_description" \
    "$engine_source_dir" \
    "$engine_target_dir" \
    "$engine_backend" \
    "$runtime_stage_dir")"
  create_portable_zip "$release_basename" "$app_root"
  artifacts+=("$RELEASE_DIR/${DATE_TAG}-${release_basename}.portable.zip")
  if [[ "$build_installer_asset" == "true" ]]; then
    installer_path="$(build_installer \
      "$flavor" \
      "$include_katago" \
      "$app_name" \
      "$app_description" \
      "$engine_source_dir" \
      "$engine_target_dir" \
      "$engine_backend" \
      "$runtime_stage_dir" \
      "$upgrade_uuid")"
    if [[ -z "$installer_path" || ! -f "$installer_path" ]]; then
      echo "Windows installer was not produced for $release_basename" >&2
      return 1
    fi
    final_installer="$RELEASE_DIR/${DATE_TAG}-${release_basename}.installer.exe"
    cp "$installer_path" "$final_installer"
    artifacts+=("$final_installer")
  else
    log_step "Skipping Windows installer for $release_basename"
  fi
  log_step "Finished Windows release variant: $release_basename"
}

artifacts=()
build_no_engine_installer="true"
has_with_katago_assets="false"
has_opencl_katago_assets="false"
has_nvidia_katago_assets="false"
has_nvidia50_cuda_katago_assets="false"
has_nvidia50_trt_katago_assets="false"

prepare_bundled_readboard_assets
prepare_bundled_jcef_assets

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
  prepare_bundled_nvidia_runtime_assets "cuda12.1-cudnn8" "$NVIDIA_RUNTIME_STAGE_DIR"
  build_release_variant \
    "nvidia" \
    "true" \
    "$NVIDIA_APP_NAME" \
    "$NVIDIA_APP_DESCRIPTION" \
    "$NVIDIA_ENGINE_PLATFORM_DIR" \
    "$STANDARD_ENGINE_PLATFORM_DIR" \
    "nvidia" \
    "$NVIDIA_ARCH_TAG" \
    "$WINDOWS_UPGRADE_UUID_NVIDIA" \
    "$NVIDIA_RUNTIME_STAGE_DIR"
else
  has_nvidia_katago_assets="false"
fi

if has_bundled_katago "$NVIDIA50_CUDA_ENGINE_PLATFORM_DIR"; then
  has_nvidia50_cuda_katago_assets="true"
  prepare_bundled_nvidia_runtime_assets "cuda12.8-cudnn9" "$NVIDIA50_CUDA_RUNTIME_STAGE_DIR"
  build_release_variant \
    "nvidia50.cuda" \
    "true" \
    "$NVIDIA50_CUDA_APP_NAME" \
    "$NVIDIA50_CUDA_APP_DESCRIPTION" \
    "$NVIDIA50_CUDA_ENGINE_PLATFORM_DIR" \
    "$STANDARD_ENGINE_PLATFORM_DIR" \
    "nvidia50-cuda" \
    "$NVIDIA50_CUDA_ARCH_TAG" \
    "$WINDOWS_UPGRADE_UUID_NVIDIA50_CUDA" \
    "$NVIDIA50_CUDA_RUNTIME_STAGE_DIR"
else
  has_nvidia50_cuda_katago_assets="false"
fi

if has_bundled_katago "$NVIDIA50_TRT_ENGINE_PLATFORM_DIR"; then
  has_nvidia50_trt_katago_assets="true"
  prepare_bundled_nvidia_runtime_assets "trt10.9-cuda12.8" "$NVIDIA50_TRT_RUNTIME_STAGE_DIR"
  build_release_variant \
    "nvidia50.trt" \
    "true" \
    "$NVIDIA50_TRT_APP_NAME" \
    "$NVIDIA50_TRT_APP_DESCRIPTION" \
    "$NVIDIA50_TRT_ENGINE_PLATFORM_DIR" \
    "$STANDARD_ENGINE_PLATFORM_DIR" \
    "nvidia50-trt" \
    "$NVIDIA50_TRT_ARCH_TAG" \
    "$WINDOWS_UPGRADE_UUID_NVIDIA50_TRT" \
    "$NVIDIA50_TRT_RUNTIME_STAGE_DIR" \
    "$BUILD_NVIDIA50_TRT_INSTALLER"
else
  has_nvidia50_trt_katago_assets="false"
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
  "$has_nvidia50_cuda_katago_assets" \
  "$has_nvidia50_trt_katago_assets" \
  "$build_no_engine_installer"
write_sha256_file "$checksum_file" "${artifacts[@]}" "$install_note"

echo "Artifacts:"
ls -lh "${artifacts[@]}"
echo
echo "Windows installer version: $WINDOWS_APP_VERSION"
echo "Windows upgrade UUID: $WINDOWS_UPGRADE_UUID"
echo "Windows OpenCL upgrade UUID: $WINDOWS_UPGRADE_UUID_OPENCL"
echo "Windows NVIDIA upgrade UUID: $WINDOWS_UPGRADE_UUID_NVIDIA"
echo "Windows NVIDIA 50 CUDA upgrade UUID: $WINDOWS_UPGRADE_UUID_NVIDIA50_CUDA"
echo "Windows NVIDIA 50 TensorRT upgrade UUID: $WINDOWS_UPGRADE_UUID_NVIDIA50_TRT"
echo
echo "Maintainer metadata:"
ls -lh "$install_note" "$checksum_file"

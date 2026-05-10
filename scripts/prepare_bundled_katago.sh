#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE_DIR="${CACHE_DIR:-$ROOT_DIR/.cache/katago}"
KATAGO_TAG="${KATAGO_TAG:-v1.16.4}"
KATAGO_RELEASE_BASE="https://github.com/lightvector/KataGo/releases/download/${KATAGO_TAG}"
# The regular Windows bundle prioritizes compatibility for mixed consumer hardware.
WINDOWS_ASSET="${WINDOWS_ASSET:-katago-${KATAGO_TAG}-eigen-windows-x64.zip}"
WINDOWS_OPENCL_ASSET="${WINDOWS_OPENCL_ASSET:-katago-${KATAGO_TAG}-opencl-windows-x64.zip}"
WINDOWS_NVIDIA_ASSET="${WINDOWS_NVIDIA_ASSET:-katago-${KATAGO_TAG}-cuda12.1-cudnn8.9.7-windows-x64.zip}"
WINDOWS_NVIDIA50_CUDA_ASSET="${WINDOWS_NVIDIA50_CUDA_ASSET:-katago-${KATAGO_TAG}-cuda12.8-cudnn9.8.0-windows-x64.zip}"
WINDOWS_NVIDIA50_TRT_ASSET="${WINDOWS_NVIDIA50_TRT_ASSET:-katago-${KATAGO_TAG}-trt10.9.0-cuda12.8-windows-x64.zip}"
LINUX_ASSET="${LINUX_ASSET:-katago-${KATAGO_TAG}-eigen-linux-x64.zip}"
LINUX_OPENCL_ASSET="${LINUX_OPENCL_ASSET:-katago-${KATAGO_TAG}-opencl-linux-x64.zip}"
LINUX_NVIDIA_ASSET="${LINUX_NVIDIA_ASSET:-katago-${KATAGO_TAG}-cuda12.1-cudnn8.9.7-linux-x64.zip}"
PREFERRED_MODEL_NAME="${PREFERRED_MODEL_NAME:-kata1-zhizi-b28c512nbt-muonfd2.bin.gz}"
MODEL_SOURCE="${MODEL_SOURCE:-}"

ENGINES_ROOT="$ROOT_DIR/engines/katago"
WEIGHTS_ROOT="$ROOT_DIR/weights"
CONFIG_ROOT="$ENGINES_ROOT/configs"
WINDOWS_ROOT="$ENGINES_ROOT/windows-x64"
WINDOWS_OPENCL_ROOT="$ENGINES_ROOT/windows-x64-opencl"
WINDOWS_NVIDIA_ROOT="$ENGINES_ROOT/windows-x64-nvidia"
WINDOWS_NVIDIA50_CUDA_ROOT="$ENGINES_ROOT/windows-x64-nvidia50-cuda"
WINDOWS_NVIDIA50_TRT_ROOT="$ENGINES_ROOT/windows-x64-nvidia50-trt"
LINUX_ROOT="$ENGINES_ROOT/linux-x64"
LINUX_OPENCL_ROOT="$ENGINES_ROOT/linux-x64-opencl"
LINUX_NVIDIA_ROOT="$ENGINES_ROOT/linux-x64-nvidia"

detect_macos_platform_dir() {
  local arch
  arch="${MACOS_KATAGO_ARCH:-$(uname -m)}"
  if [[ "$arch" == "arm64" || "$arch" == "aarch64" ]]; then
    echo "macos-arm64"
  else
    echo "macos-amd64"
  fi
}

brew_prefix_for() {
  local formula="$1"
  if ! command -v brew >/dev/null 2>&1; then
    echo ""
    return 0
  fi
  brew --prefix "$formula" 2>/dev/null || true
}

require_cmd() {
  local cmd="$1"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "Missing required command: $cmd"
    exit 1
  fi
}

download_asset() {
  local asset_name="$1"
  local dest="$CACHE_DIR/$asset_name"
  local url="$KATAGO_RELEASE_BASE/$asset_name"

  mkdir -p "$CACHE_DIR"
  if [[ -f "$dest" ]] && unzip -tqq "$dest" >/dev/null 2>&1; then
    echo "Using cached asset: $asset_name"
    return 0
  fi

  rm -f "$dest" "$dest.part"
  echo "Downloading $asset_name"
  curl -fL --retry 5 --retry-all-errors -o "$dest.part" "$url"
  mv "$dest.part" "$dest"
  unzip -tqq "$dest" >/dev/null
}

extract_asset() {
  local asset_name="$1"
  local out_dir="$CACHE_DIR/${asset_name%.zip}"
  rm -rf "$out_dir"
  mkdir -p "$out_dir"
  unzip -qo "$CACHE_DIR/$asset_name" -d "$out_dir"
  echo "$out_dir"
}

copy_matching_files() {
  local src_dir="$1"
  local dest_dir="$2"
  shift 2
  mkdir -p "$dest_dir"
  for pattern in "$@"; do
    find "$src_dir" -maxdepth 1 -type f -name "$pattern" -exec cp -f {} "$dest_dir/" \;
  done
}

find_model_source() {
  local katago_prefix
  local katago_share_dir=""
  katago_prefix="$(brew_prefix_for katago)"
  if [[ -n "$katago_prefix" ]]; then
    katago_share_dir="$katago_prefix/share/katago"
  fi

  if [[ -n "$MODEL_SOURCE" ]]; then
    if [[ -f "$MODEL_SOURCE" ]]; then
      printf '%s\n' "$MODEL_SOURCE"
      return 0
    fi
    echo "MODEL_SOURCE does not exist: $MODEL_SOURCE"
    exit 1
  fi

  local candidates=(
    "${katago_share_dir:+$katago_share_dir/$PREFERRED_MODEL_NAME}"
    "${katago_share_dir:+$katago_share_dir/kata1-zhizi-b28c512nbt-muonfd2.bin.gz}"
    "${katago_share_dir:+$katago_share_dir/kata1-zhizi-b40c768nbt-fdx6c.bin.gz}"
    "${katago_share_dir:+$katago_share_dir/g170-b40c256x2-s5095420928-d1229425124.bin.gz}"
    "${katago_share_dir:+$katago_share_dir/kata1-b18c384nbt-s9996604416-d4316597426.bin.gz}"
    "/usr/local/opt/katago/share/katago/$PREFERRED_MODEL_NAME"
    "/usr/local/opt/katago/share/katago/kata1-zhizi-b28c512nbt-muonfd2.bin.gz"
    "/usr/local/opt/katago/share/katago/kata1-zhizi-b40c768nbt-fdx6c.bin.gz"
    "/usr/local/opt/katago/share/katago/g170-b40c256x2-s5095420928-d1229425124.bin.gz"
    "/usr/local/opt/katago/share/katago/kata1-b18c384nbt-s9996604416-d4316597426.bin.gz"
    "/opt/homebrew/opt/katago/share/katago/$PREFERRED_MODEL_NAME"
    "/opt/homebrew/opt/katago/share/katago/kata1-zhizi-b28c512nbt-muonfd2.bin.gz"
    "/opt/homebrew/opt/katago/share/katago/kata1-zhizi-b40c768nbt-fdx6c.bin.gz"
    "/opt/homebrew/opt/katago/share/katago/g170-b40c256x2-s5095420928-d1229425124.bin.gz"
    "/opt/homebrew/opt/katago/share/katago/kata1-b18c384nbt-s9996604416-d4316597426.bin.gz"
  )
  local candidate
  for candidate in "${candidates[@]}"; do
    if [[ -n "$candidate" ]] && [[ -f "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  if [[ -n "$katago_share_dir" ]] && [[ -d "$katago_share_dir" ]]; then
    candidate="$(find "$katago_share_dir" -maxdepth 1 -type f -name '*.bin.gz' | head -n 1)"
    if [[ -n "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  fi

  if [[ -d /usr/local/opt/katago/share/katago ]]; then
    candidate="$(find /usr/local/opt/katago/share/katago -maxdepth 1 -type f -name '*.bin.gz' | head -n 1)"
    if [[ -n "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  fi

  if [[ -d /opt/homebrew/opt/katago/share/katago ]]; then
    candidate="$(find /opt/homebrew/opt/katago/share/katago -maxdepth 1 -type f -name '*.bin.gz' | head -n 1)"
    if [[ -n "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  fi

  echo "No KataGo model found. Set MODEL_SOURCE=/path/to/model.bin.gz and rerun."
  exit 1
}

prepare_configs() {
  local source_dir="$1"
  rm -rf "$CONFIG_ROOT"
  mkdir -p "$CONFIG_ROOT"
  cp -f "$source_dir/default_gtp.cfg" "$CONFIG_ROOT/gtp.cfg"
  cp -f "$source_dir/default_gtp.cfg" "$CONFIG_ROOT/estimate.cfg"
  cp -f "$source_dir/analysis_example.cfg" "$CONFIG_ROOT/analysis.cfg"
}

prepare_windows_bundle() {
  local source_dir="$1"
  local dest_dir="$2"
  rm -rf "$dest_dir"
  mkdir -p "$dest_dir"
  copy_matching_files "$source_dir" "$dest_dir" "katago.exe" "*.dll" "cacert.pem"
}

prepare_linux_bundle() {
  local source_dir="$1"
  local dest_dir="$2"
  rm -rf "$dest_dir"
  mkdir -p "$dest_dir"
  copy_matching_files "$source_dir" "$dest_dir" "katago" "*.so" "*.so.*" "cacert.pem"
  chmod +x "$dest_dir/katago"
}

prepare_macos_bundle() {
  local katago_prefix
  local libzip_prefix
  local xz_prefix
  local zstd_prefix
  local platform_dir

  katago_prefix="$(brew_prefix_for katago)"
  libzip_prefix="$(brew_prefix_for libzip)"
  xz_prefix="$(brew_prefix_for xz)"
  zstd_prefix="$(brew_prefix_for zstd)"
  platform_dir="$(detect_macos_platform_dir)"

  local macos_root="$ENGINES_ROOT/$platform_dir"
  local katago_bin="${MACOS_KATAGO_BIN:-${katago_prefix:+$katago_prefix/bin/katago}}"
  local libzip_bin="${MACOS_LIBZIP_BIN:-${libzip_prefix:+$libzip_prefix/lib/libzip.5.dylib}}"
  local liblzma_bin="${MACOS_LIBLZMA_BIN:-${xz_prefix:+$xz_prefix/lib/liblzma.5.dylib}}"
  local libzstd_bin="${MACOS_LIBZSTD_BIN:-${zstd_prefix:+$zstd_prefix/lib/libzstd.1.dylib}}"

  if [[ ! -x "$katago_bin" ]]; then
    echo "Skipping $platform_dir bundle: $katago_bin not found"
    return 0
  fi

  require_cmd install_name_tool

  rm -rf "$macos_root"
  mkdir -p "$macos_root/lib"

  cp -Lf "$katago_bin" "$macos_root/katago"
  cp -Lf "$libzip_bin" "$macos_root/lib/libzip.5.dylib"
  cp -Lf "$liblzma_bin" "$macos_root/lib/liblzma.5.dylib"
  cp -Lf "$libzstd_bin" "$macos_root/lib/libzstd.1.dylib"
  chmod +x "$macos_root/katago"

  install_name_tool \
    -change "$libzip_bin" \
    @executable_path/lib/libzip.5.dylib \
    "$macos_root/katago"
  install_name_tool -id @loader_path/libzip.5.dylib "$macos_root/lib/libzip.5.dylib"
  install_name_tool -id @loader_path/liblzma.5.dylib "$macos_root/lib/liblzma.5.dylib"
  install_name_tool -id @loader_path/libzstd.1.dylib "$macos_root/lib/libzstd.1.dylib"
  install_name_tool \
    -change "$liblzma_bin" \
    @loader_path/liblzma.5.dylib \
    "$macos_root/lib/libzip.5.dylib"
  install_name_tool \
    -change "$libzstd_bin" \
    @loader_path/libzstd.1.dylib \
    "$macos_root/lib/libzip.5.dylib"

  if command -v codesign >/dev/null 2>&1; then
    codesign --force --sign - "$macos_root/lib/"*.dylib "$macos_root/katago" >/dev/null 2>&1 || true
  fi

  "$macos_root/katago" version >/dev/null
}

write_manifest() {
  local model_path="$1"
  mkdir -p "$ENGINES_ROOT"
  cat >"$ENGINES_ROOT/VERSION.txt" <<EOF
KataGo release: $KATAGO_TAG
Windows bundle: $WINDOWS_ASSET
Windows OpenCL bundle: $WINDOWS_OPENCL_ASSET
Windows NVIDIA bundle: $WINDOWS_NVIDIA_ASSET
Windows NVIDIA 50 CUDA bundle: $WINDOWS_NVIDIA50_CUDA_ASSET
Windows NVIDIA 50 TensorRT bundle: $WINDOWS_NVIDIA50_TRT_ASSET
Linux bundle: $LINUX_ASSET
Linux OpenCL bundle: $LINUX_OPENCL_ASSET
Linux NVIDIA bundle: $LINUX_NVIDIA_ASSET
Model source: $(basename "$model_path")
Prepared at: $(date '+%F %T %z')
EOF
}

main() {
  require_cmd curl
  require_cmd unzip

  download_asset "$WINDOWS_ASSET"
  download_asset "$WINDOWS_OPENCL_ASSET"
  download_asset "$WINDOWS_NVIDIA_ASSET"
  download_asset "$WINDOWS_NVIDIA50_CUDA_ASSET"
  download_asset "$WINDOWS_NVIDIA50_TRT_ASSET"
  download_asset "$LINUX_ASSET"
  download_asset "$LINUX_OPENCL_ASSET"
  download_asset "$LINUX_NVIDIA_ASSET"

  local windows_src
  local windows_opencl_src
  local windows_nvidia_src
  local windows_nvidia50_cuda_src
  local windows_nvidia50_trt_src
  local linux_src
  local linux_opencl_src
  local linux_nvidia_src
  local model_path
  windows_src="$(extract_asset "$WINDOWS_ASSET")"
  windows_opencl_src="$(extract_asset "$WINDOWS_OPENCL_ASSET")"
  windows_nvidia_src="$(extract_asset "$WINDOWS_NVIDIA_ASSET")"
  windows_nvidia50_cuda_src="$(extract_asset "$WINDOWS_NVIDIA50_CUDA_ASSET")"
  windows_nvidia50_trt_src="$(extract_asset "$WINDOWS_NVIDIA50_TRT_ASSET")"
  linux_src="$(extract_asset "$LINUX_ASSET")"
  linux_opencl_src="$(extract_asset "$LINUX_OPENCL_ASSET")"
  linux_nvidia_src="$(extract_asset "$LINUX_NVIDIA_ASSET")"
  model_path="$(find_model_source)"

  mkdir -p "$WEIGHTS_ROOT"
  cp -f "$model_path" "$WEIGHTS_ROOT/default.bin.gz"

  prepare_configs "$windows_src"
  prepare_windows_bundle "$windows_src" "$WINDOWS_ROOT"
  prepare_windows_bundle "$windows_opencl_src" "$WINDOWS_OPENCL_ROOT"
  prepare_windows_bundle "$windows_nvidia_src" "$WINDOWS_NVIDIA_ROOT"
  prepare_windows_bundle "$windows_nvidia50_cuda_src" "$WINDOWS_NVIDIA50_CUDA_ROOT"
  prepare_windows_bundle "$windows_nvidia50_trt_src" "$WINDOWS_NVIDIA50_TRT_ROOT"
  prepare_linux_bundle "$linux_src" "$LINUX_ROOT"
  prepare_linux_bundle "$linux_opencl_src" "$LINUX_OPENCL_ROOT"
  prepare_linux_bundle "$linux_nvidia_src" "$LINUX_NVIDIA_ROOT"
  prepare_macos_bundle
  write_manifest "$model_path"

  echo
  echo "Prepared bundled KataGo assets:"
  find "$ENGINES_ROOT" -maxdepth 2 -type f | sort
  echo "$WEIGHTS_ROOT/default.bin.gz"
}

main "$@"

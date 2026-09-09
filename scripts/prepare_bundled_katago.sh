#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT_DIR/scripts/katago_windows_pins.sh"
CATEGORY_READER="${PYTHON_BIN:-python3}"
ASSET_CATALOG_READER="$ROOT_DIR/scripts/katago_asset_catalog.py"

catalog_get() {
  "$CATEGORY_READER" "$ASSET_CATALOG_READER" get "$1"
}

CACHE_DIR="${CACHE_DIR:-$ROOT_DIR/.cache/katago}"
KATAGO_TAG="${KATAGO_TAG:-$(catalog_get katagoReleaseTag)}"
KATAGO_RELEASE_BASE="https://github.com/lightvector/KataGo/releases/download/${KATAGO_TAG}"
# The regular Windows bundle prioritizes compatibility for mixed consumer hardware.
WINDOWS_ASSET="${WINDOWS_ASSET:-$(catalog_get assets.windows-cpu.assetName)}"
WINDOWS_OPENCL_ASSET="${WINDOWS_OPENCL_ASSET:-$(catalog_get assets.windows-opencl.assetName)}"
WINDOWS_NVIDIA_ASSET="${WINDOWS_NVIDIA_ASSET:-$(catalog_get assets.windows-nvidia.assetName)}"
WINDOWS_DIRECTML_ASSET="${WINDOWS_DIRECTML_ASSET:-$(catalog_get assets.windows-directml.assetName)}"
WINDOWS_OPENVINO_ASSET="${WINDOWS_OPENVINO_ASSET:-$(catalog_get assets.windows-openvino.assetName)}"
WINDOWS_ROCM_GFX103X_ASSET="${WINDOWS_ROCM_GFX103X_ASSET:-$(catalog_get assets.windows-rocm-gfx103x.assetName)}"
WINDOWS_ROCM_GFX110X_ASSET="${WINDOWS_ROCM_GFX110X_ASSET:-$(catalog_get assets.windows-rocm-gfx110x.assetName)}"
WINDOWS_ROCM_GFX1151_ASSET="${WINDOWS_ROCM_GFX1151_ASSET:-$(catalog_get assets.windows-rocm-gfx1151.assetName)}"
WINDOWS_ROCM_GFX120X_ASSET="${WINDOWS_ROCM_GFX120X_ASSET:-$(catalog_get assets.windows-rocm-gfx120x.assetName)}"
LINUX_ASSET="${LINUX_ASSET:-$(catalog_get assets.linux-cpu.assetName)}"
LINUX_OPENCL_ASSET="${LINUX_OPENCL_ASSET:-$(catalog_get assets.linux-opencl.assetName)}"
LINUX_NVIDIA_ASSET="${LINUX_NVIDIA_ASSET:-$(catalog_get assets.linux-nvidia.assetName)}"
DEFAULT_MODEL_ID="$(catalog_get defaultModelId)"
PREFERRED_MODEL_NAME="${PREFERRED_MODEL_NAME:-$(catalog_get models.$DEFAULT_MODEL_ID.fileName)}"
PREFERRED_MODEL_SHA256="${PREFERRED_MODEL_SHA256:-$(catalog_get models.$DEFAULT_MODEL_ID.sha256)}"
PREFERRED_MODEL_SIZE_BYTES="${PREFERRED_MODEL_SIZE_BYTES:-$(catalog_get models.$DEFAULT_MODEL_ID.sizeBytes)}"
PREFERRED_MODEL_ARCHITECTURE="${PREFERRED_MODEL_ARCHITECTURE:-$(catalog_get models.$DEFAULT_MODEL_ID.architecture)}"
PREFERRED_MODEL_MINIMUM_KATAGO="${PREFERRED_MODEL_MINIMUM_KATAGO:-$(catalog_get models.$DEFAULT_MODEL_ID.minimumKataGoVersion)}"
MODEL_RELEASE_TAG="$(catalog_get modelReleaseTag)"
if [[ "$PREFERRED_MODEL_NAME" == "$(catalog_get models.$DEFAULT_MODEL_ID.fileName)" ]]; then
  MODEL_URL="${MODEL_URL:-$("$CATEGORY_READER" "$ASSET_CATALOG_READER" model-url "$DEFAULT_MODEL_ID")}"
else
  MODEL_URL="${MODEL_URL:-https://github.com/lightvector/KataGo/releases/download/$MODEL_RELEASE_TAG/$PREFERRED_MODEL_NAME}"
fi
MODEL_SOURCE="${MODEL_SOURCE:-}"

ENGINES_ROOT="$ROOT_DIR/engines/katago"
WEIGHTS_ROOT="$ROOT_DIR/weights"
CONFIG_ROOT="$ENGINES_ROOT/configs"
WINDOWS_ROOT="$ENGINES_ROOT/windows-x64"
WINDOWS_OPENCL_ROOT="$ENGINES_ROOT/windows-x64-opencl"
WINDOWS_NVIDIA_ROOT="$ENGINES_ROOT/windows-x64-nvidia"
WINDOWS_DIRECTML_ROOT="$ENGINES_ROOT/windows-x64-directml"
WINDOWS_OPENVINO_ROOT="$ENGINES_ROOT/windows-x64-openvino"
WINDOWS_ROCM_GFX103X_ROOT="$ENGINES_ROOT/windows-x64-rocm-gfx103x"
WINDOWS_ROCM_GFX110X_ROOT="$ENGINES_ROOT/windows-x64-rocm-gfx110x"
WINDOWS_ROCM_GFX1151_ROOT="$ENGINES_ROOT/windows-x64-rocm-gfx1151"
WINDOWS_ROCM_GFX120X_ROOT="$ENGINES_ROOT/windows-x64-rocm-gfx120x"
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

is_macos_host() {
  [[ "$(uname -s)" == "Darwin" ]]
}

require_cmd() {
  local cmd="$1"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "Missing required command: $cmd"
    exit 1
  fi
}

sha256_file() {
  local path="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$path" | awk '{print $1}'
  else
    shasum -a 256 "$path" | awk '{print $1}'
  fi
}

file_size_bytes() {
  local path="$1"
  if stat -f '%z' "$path" >/dev/null 2>&1; then
    stat -f '%z' "$path"
  else
    stat -c '%s' "$path"
  fi
}

expected_asset_sha256() {
  case "$1" in
    "$WINDOWS_ASSET")
      catalog_get assets.windows-cpu.sha256
      ;;
    "$WINDOWS_OPENCL_ASSET")
      catalog_get assets.windows-opencl.sha256
      ;;
    "$WINDOWS_NVIDIA_ASSET")
      catalog_get assets.windows-nvidia.sha256
      ;;
    "$WINDOWS_DIRECTML_ASSET")
      catalog_get assets.windows-directml.sha256
      ;;
    "$WINDOWS_OPENVINO_ASSET")
      catalog_get assets.windows-openvino.sha256
      ;;
    "$WINDOWS_ROCM_GFX103X_ASSET")
      catalog_get assets.windows-rocm-gfx103x.sha256
      ;;
    "$WINDOWS_ROCM_GFX110X_ASSET")
      catalog_get assets.windows-rocm-gfx110x.sha256
      ;;
    "$WINDOWS_ROCM_GFX1151_ASSET")
      catalog_get assets.windows-rocm-gfx1151.sha256
      ;;
    "$WINDOWS_ROCM_GFX120X_ASSET")
      catalog_get assets.windows-rocm-gfx120x.sha256
      ;;
    "$LINUX_ASSET")
      catalog_get assets.linux-cpu.sha256
      ;;
    "$LINUX_OPENCL_ASSET")
      catalog_get assets.linux-opencl.sha256
      ;;
    "$LINUX_NVIDIA_ASSET")
      catalog_get assets.linux-nvidia.sha256
      ;;
    *)
      echo ""
      ;;
  esac
}

download_asset() {
  local asset_name="$1"
  local dest="$CACHE_DIR/$asset_name"
  local url="$KATAGO_RELEASE_BASE/$asset_name"
  local expected_sha
  local actual_sha
  expected_sha="$(expected_asset_sha256 "$asset_name")"

  mkdir -p "$CACHE_DIR"
  if [[ -f "$dest" ]]; then
    actual_sha="$(sha256_file "$dest")"
    if [[ -n "$expected_sha" && "$actual_sha" == "$expected_sha" ]] \
      && unzip -tqq "$dest" >/dev/null 2>&1; then
      echo "Using verified cached asset: $asset_name"
      return 0
    fi
    rm -f "$dest"
  fi

  rm -f "$dest.part"
  echo "Downloading $asset_name"
  curl -fL --retry 5 --retry-all-errors -o "$dest.part" "$url"
  actual_sha="$(sha256_file "$dest.part")"
  if [[ -z "$expected_sha" ]]; then
    rm -f "$dest.part"
    echo "No pinned SHA-256 is available for KataGo asset: $asset_name"
    exit 1
  fi
  if [[ "$actual_sha" != "$expected_sha" ]]; then
    rm -f "$dest.part"
    echo "SHA-256 mismatch for $asset_name: expected $expected_sha, got $actual_sha"
    exit 1
  fi
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
  if [[ -n "$MODEL_SOURCE" ]]; then
    if [[ -f "$MODEL_SOURCE" ]]; then
      printf '%s\n' "$MODEL_SOURCE"
      return 0
    fi
    echo "MODEL_SOURCE does not exist: $MODEL_SOURCE"
    exit 1
  fi

  local cached_model="$CACHE_DIR/$PREFERRED_MODEL_NAME"
  mkdir -p "$CACHE_DIR"
  if [[ -f "$cached_model" ]] \
    && [[ "$(file_size_bytes "$cached_model")" == "$PREFERRED_MODEL_SIZE_BYTES" ]] \
    && [[ "$(sha256_file "$cached_model")" == "$PREFERRED_MODEL_SHA256" ]]; then
    printf '%s\n' "$cached_model"
    return 0
  fi

  rm -f "$cached_model" "$cached_model.part"
  echo "Downloading default Transformer model: $PREFERRED_MODEL_NAME" >&2
  curl -fL --retry 5 --retry-all-errors -o "$cached_model.part" "$MODEL_URL"
  mv "$cached_model.part" "$cached_model"
  printf '%s\n' "$cached_model"
}

verify_model() {
  local model_path="$1"
  local actual_size
  local actual_sha
  actual_size="$(file_size_bytes "$model_path")"
  actual_sha="$(sha256_file "$model_path")"
  if [[ "$actual_size" != "$PREFERRED_MODEL_SIZE_BYTES" ]]; then
    echo "Model size mismatch: expected $PREFERRED_MODEL_SIZE_BYTES, got $actual_size"
    exit 1
  fi
  if [[ "$actual_sha" != "$PREFERRED_MODEL_SHA256" ]]; then
    echo "Model SHA-256 mismatch: expected $PREFERRED_MODEL_SHA256, got $actual_sha"
    exit 1
  fi
}

prepare_configs() {
  local source_dir="$1"
  rm -rf "$CONFIG_ROOT"
  mkdir -p "$CONFIG_ROOT"
  cp -f "$source_dir/default_gtp.cfg" "$CONFIG_ROOT/gtp.cfg"
  cp -f "$source_dir/analysis_example.cfg" "$CONFIG_ROOT/analysis.cfg"
}

prepare_windows_bundle() {
  local source_dir="$1"
  local dest_dir="$2"
  rm -rf "$dest_dir"
  mkdir -p "$dest_dir"
  copy_matching_files "$source_dir" "$dest_dir" "katago.exe" "*.dll" "cacert.pem"
}

prepare_windows_bundle_tree() {
  local source_dir="$1"
  local dest_dir="$2"
  local executable
  executable="$(find "$source_dir" -type f -iname 'katago.exe' -print -quit)"
  if [[ -z "$executable" ]]; then
    echo "KataGo Windows archive did not contain katago.exe: $source_dir" >&2
    exit 1
  fi
  local bundle_root
  bundle_root="$(dirname "$executable")"
  rm -rf "$dest_dir"
  mkdir -p "$dest_dir"
  cp -R "$bundle_root/." "$dest_dir/"
}

write_experimental_engine_manifest() {
  local dest_dir="$1"
  local asset_name="$2"
  local backend="$3"
  local asset_sha
  asset_sha="$(expected_asset_sha256 "$asset_name")"
  printf '%s\n' "$backend" >"$dest_dir/lizzieyzy-next-engine-backend.txt"
  cat >"$dest_dir/lizzieyzy-next-katago-engine-manifest.txt" <<EOF
KataGo release: $KATAGO_TAG
Asset: $asset_name
Asset SHA-256: $asset_sha
Backend: $backend
EOF
}

verify_windows_nvidia_executable() {
  local executable="$WINDOWS_NVIDIA_ROOT/katago.exe"
  if [[ ! -f "$executable" ]]; then
    echo "Prepared unified NVIDIA CUDA bundle is missing katago.exe: $executable"
    exit 1
  fi
  local actual_sha256
  actual_sha256="$(sha256_file "$executable")"
  if [[ "$actual_sha256" != "$HUMAN_SL_CUDA_COMPANION_SHA256" ]]; then
    echo "Unified NVIDIA CUDA katago.exe checksum mismatch: expected $HUMAN_SL_CUDA_COMPANION_SHA256, got $actual_sha256"
    exit 1
  fi
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
  if ! is_macos_host; then
    echo "Skipping macOS KataGo bundle on non-macOS host: $(uname -s)"
    return 0
  fi

  local katago_prefix
  local platform_dir
  local version_output

  katago_prefix="$(brew_prefix_for katago)"
  platform_dir="$(detect_macos_platform_dir)"

  local macos_root="$ENGINES_ROOT/$platform_dir"
  local katago_bin="${MACOS_KATAGO_BIN:-${katago_prefix:+$katago_prefix/bin/katago}}"
  local bundler="$ROOT_DIR/scripts/macos_katago_bundle.py"
  local source_builder="$ROOT_DIR/scripts/build_macos_katago.sh"

  version_output=""
  if [[ -x "$katago_bin" ]]; then
    version_output="$("$katago_bin" version 2>&1 || true)"
  fi
  if [[ "$version_output" != *"KataGo $KATAGO_TAG"* ]] \
    || [[ "$version_output" != *"Using Metal backend"* ]]; then
    if [[ ! -x "$source_builder" ]]; then
      echo "Missing macOS KataGo source builder: $source_builder"
      exit 1
    fi
    echo "Installed KataGo is not $KATAGO_TAG Metal; building the pinned official source."
    katago_bin="$(KATAGO_TAG="$KATAGO_TAG" "$source_builder")"
  fi

  if [[ ! -f "$bundler" ]]; then
    echo "Missing macOS KataGo bundler: $bundler"
    exit 1
  fi
  require_cmd python3
  python3 "$bundler" bundle \
    --katago "$katago_bin" \
    --output "$macos_root" \
    --expected-version "${KATAGO_TAG#v}"
}

write_manifest() {
  local model_path="$1"
  mkdir -p "$ENGINES_ROOT"
  cat >"$ENGINES_ROOT/VERSION.txt" <<EOF
KataGo release: $KATAGO_TAG
Windows bundle: $WINDOWS_ASSET
Windows OpenCL bundle: $WINDOWS_OPENCL_ASSET
Windows NVIDIA bundle: $WINDOWS_NVIDIA_ASSET
Windows NVIDIA executable SHA-256: $HUMAN_SL_CUDA_COMPANION_SHA256
Windows DirectML bundle: $WINDOWS_DIRECTML_ASSET
Windows OpenVINO bundle: $WINDOWS_OPENVINO_ASSET
Windows ROCm gfx103X bundle: $WINDOWS_ROCM_GFX103X_ASSET
Windows ROCm gfx110X bundle: $WINDOWS_ROCM_GFX110X_ASSET
Windows ROCm gfx1151 bundle: $WINDOWS_ROCM_GFX1151_ASSET
Windows ROCm gfx120X bundle: $WINDOWS_ROCM_GFX120X_ASSET
Linux bundle: $LINUX_ASSET
Linux OpenCL bundle: $LINUX_OPENCL_ASSET
Linux NVIDIA bundle: $LINUX_NVIDIA_ASSET
Model source: $(basename "$model_path")
Model SHA-256: $(sha256_file "$model_path")
Model size: $(file_size_bytes "$model_path")
Model architecture: $PREFERRED_MODEL_ARCHITECTURE
Minimum KataGo version: $PREFERRED_MODEL_MINIMUM_KATAGO
Prepared at: $(date '+%F %T %z')
EOF
}

main() {
  require_cmd curl
  require_cmd unzip

  download_asset "$WINDOWS_ASSET"
  download_asset "$WINDOWS_OPENCL_ASSET"
  download_asset "$WINDOWS_NVIDIA_ASSET"
  if [[ "${PREPARE_WINDOWS_EXPERIMENTAL:-false}" == "true" ]]; then
    download_asset "$WINDOWS_DIRECTML_ASSET"
    download_asset "$WINDOWS_OPENVINO_ASSET"
    download_asset "$WINDOWS_ROCM_GFX103X_ASSET"
    download_asset "$WINDOWS_ROCM_GFX110X_ASSET"
    download_asset "$WINDOWS_ROCM_GFX1151_ASSET"
    download_asset "$WINDOWS_ROCM_GFX120X_ASSET"
  fi
  download_asset "$LINUX_ASSET"
  download_asset "$LINUX_OPENCL_ASSET"
  download_asset "$LINUX_NVIDIA_ASSET"

  local windows_src
  local windows_opencl_src
  local windows_nvidia_src
  local linux_src
  local linux_opencl_src
  local linux_nvidia_src
  local model_path
  windows_src="$(extract_asset "$WINDOWS_ASSET")"
  windows_opencl_src="$(extract_asset "$WINDOWS_OPENCL_ASSET")"
  windows_nvidia_src="$(extract_asset "$WINDOWS_NVIDIA_ASSET")"
  linux_src="$(extract_asset "$LINUX_ASSET")"
  linux_opencl_src="$(extract_asset "$LINUX_OPENCL_ASSET")"
  linux_nvidia_src="$(extract_asset "$LINUX_NVIDIA_ASSET")"
  model_path="$(find_model_source)"
  verify_model "$model_path"

  mkdir -p "$WEIGHTS_ROOT"
  cp -f "$model_path" "$WEIGHTS_ROOT/default.bin.gz"
  rm -rf "$ENGINES_ROOT/windows-x64-nvidia50-trt"

  prepare_configs "$windows_src"
  prepare_windows_bundle "$windows_src" "$WINDOWS_ROOT"
  prepare_windows_bundle "$windows_opencl_src" "$WINDOWS_OPENCL_ROOT"
  prepare_windows_bundle "$windows_nvidia_src" "$WINDOWS_NVIDIA_ROOT"
  rm -rf "$ENGINES_ROOT/windows-x64-nvidia50-cuda"
  verify_windows_nvidia_executable
  if [[ "${PREPARE_WINDOWS_EXPERIMENTAL:-false}" == "true" ]]; then
    prepare_windows_bundle_tree "$(extract_asset "$WINDOWS_DIRECTML_ASSET")" "$WINDOWS_DIRECTML_ROOT"
    write_experimental_engine_manifest "$WINDOWS_DIRECTML_ROOT" "$WINDOWS_DIRECTML_ASSET" "directml"
    prepare_windows_bundle_tree "$(extract_asset "$WINDOWS_OPENVINO_ASSET")" "$WINDOWS_OPENVINO_ROOT"
    write_experimental_engine_manifest "$WINDOWS_OPENVINO_ROOT" "$WINDOWS_OPENVINO_ASSET" "openvino"
    prepare_windows_bundle_tree "$(extract_asset "$WINDOWS_ROCM_GFX103X_ASSET")" "$WINDOWS_ROCM_GFX103X_ROOT"
    write_experimental_engine_manifest "$WINDOWS_ROCM_GFX103X_ROOT" "$WINDOWS_ROCM_GFX103X_ASSET" "rocm-gfx103x"
    prepare_windows_bundle_tree "$(extract_asset "$WINDOWS_ROCM_GFX110X_ASSET")" "$WINDOWS_ROCM_GFX110X_ROOT"
    write_experimental_engine_manifest "$WINDOWS_ROCM_GFX110X_ROOT" "$WINDOWS_ROCM_GFX110X_ASSET" "rocm-gfx110x"
    prepare_windows_bundle_tree "$(extract_asset "$WINDOWS_ROCM_GFX1151_ASSET")" "$WINDOWS_ROCM_GFX1151_ROOT"
    write_experimental_engine_manifest "$WINDOWS_ROCM_GFX1151_ROOT" "$WINDOWS_ROCM_GFX1151_ASSET" "rocm-gfx1151"
    prepare_windows_bundle_tree "$(extract_asset "$WINDOWS_ROCM_GFX120X_ASSET")" "$WINDOWS_ROCM_GFX120X_ROOT"
    write_experimental_engine_manifest "$WINDOWS_ROCM_GFX120X_ROOT" "$WINDOWS_ROCM_GFX120X_ASSET" "rocm-gfx120x"
  else
    rm -rf \
      "$WINDOWS_DIRECTML_ROOT" \
      "$WINDOWS_OPENVINO_ROOT" \
      "$WINDOWS_ROCM_GFX103X_ROOT" \
      "$WINDOWS_ROCM_GFX110X_ROOT" \
      "$WINDOWS_ROCM_GFX1151_ROOT" \
      "$WINDOWS_ROCM_GFX120X_ROOT"
  fi
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

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi

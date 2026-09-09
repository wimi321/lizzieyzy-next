#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=prepare_bundled_katago.sh
source "$ROOT_DIR/scripts/prepare_bundled_katago.sh"

[[ "$KATAGO_TAG" == "v1.18.1" ]]
[[ "$PREFERRED_MODEL_NAME" == "kata1-tf3-b11c768-s11500M-d6163M.bin.gz" ]]
[[ "$PREFERRED_MODEL_SIZE_BYTES" == "211568937" ]]
[[ "$MODEL_URL" == "https://media.katagotraining.org/uploaded/networks/models/kata1/$PREFERRED_MODEL_NAME" ]]
[[ "$PREFERRED_MODEL_SHA256" == \
  "73f6454eba62d2f6d099af8ce66d8c3fde6225e223c55817da0627590e98b0ae" ]]
[[ "$HUMAN_SL_CUDA_COMPANION_SHA256" == \
  "e207abb6e2403f0f34e9f4cac6079b988bf853537367d18f899c4b246eeb044c" ]]
[[ "$(expected_asset_sha256 "$WINDOWS_ASSET")" == \
  "074485cf150c38aa3bb14ac9f54f2952ffefbceb44673709bbb8a83650bf95d6" ]]
[[ "$(expected_asset_sha256 "$WINDOWS_OPENCL_ASSET")" == \
  "1710db1903ab921aa6837a9599c8474f8a59f057650217c5d9bc125ee393a9ff" ]]
[[ "$(expected_asset_sha256 "$WINDOWS_NVIDIA_ASSET")" == \
  "8caabc5675950f52d285a686c19727f7a56a982313a7f055ade060ba78df552e" ]]
[[ "$(expected_asset_sha256 "$LINUX_ASSET")" == \
  "993b642601e806037003d11e43775e7b4fc65281aed9b9469b7122f18fc16811" ]]
[[ "$(expected_asset_sha256 "$LINUX_OPENCL_ASSET")" == \
  "81ecea81526adb412a392ec728dbdf9627e754df7cf1a7a3dbb8ef220182184a" ]]
[[ "$(expected_asset_sha256 "$LINUX_NVIDIA_ASSET")" == \
  "242e6720e085a67bbb6605408fbcf607812185e40cea5f5fe4090e71201a49c0" ]]

uname() {
  printf '%s\n' "MINGW64_NT-10.0"
}

if is_macos_host; then
  echo "Windows Git Bash must not be detected as macOS" >&2
  exit 1
fi

skip_output="$(prepare_macos_bundle)"
grep -Fq "Skipping macOS KataGo bundle on non-macOS host" <<<"$skip_output"

uname() {
  printf '%s\n' "Linux"
}

if is_macos_host; then
  echo "Linux must not be detected as macOS" >&2
  exit 1
fi

uname() {
  printf '%s\n' "Darwin"
}

if ! is_macos_host; then
  echo "Darwin must be detected as macOS" >&2
  exit 1
fi

echo "prepare_bundled_katago host gating tests passed"

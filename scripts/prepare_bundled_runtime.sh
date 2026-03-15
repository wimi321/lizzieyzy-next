#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE_DIR="${CACHE_DIR:-$ROOT_DIR/.cache/java}"
TEMURIN_REPO="${TEMURIN_REPO:-adoptium/temurin21-binaries}"
TEMURIN_TAG="${TEMURIN_TAG:-}"
WINDOWS_PATTERN="${WINDOWS_PATTERN:-OpenJDK21U-jre_x64_windows_hotspot_*.zip}"
LINUX_PATTERN="${LINUX_PATTERN:-OpenJDK21U-jre_x64_linux_hotspot_*.tar.gz}"

RUNTIME_ROOT="$ROOT_DIR/runtime"
WINDOWS_RUNTIME_ROOT="$RUNTIME_ROOT/windows-x64"
LINUX_RUNTIME_ROOT="$RUNTIME_ROOT/linux-x64"

require_cmd() {
  local cmd="$1"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "Missing required command: $cmd"
    exit 1
  fi
}

resolve_latest_tag() {
  if [[ -n "$TEMURIN_TAG" ]]; then
    printf '%s\n' "$TEMURIN_TAG"
    return 0
  fi

  gh api "repos/$TEMURIN_REPO/releases/latest" --jq '.tag_name'
}

download_release_asset() {
  local tag="$1"
  local pattern="$2"

  mkdir -p "$CACHE_DIR"
  rm -f "$CACHE_DIR"/$pattern
  echo "Downloading runtime asset: $pattern from $tag"
  gh release download "$tag" -R "$TEMURIN_REPO" -p "$pattern" -D "$CACHE_DIR"
}

resolve_cached_asset() {
  local pattern="$1"
  local candidate
  candidate="$(find "$CACHE_DIR" -maxdepth 1 -type f -name "$pattern" | head -n 1)"
  if [[ -z "$candidate" ]]; then
    echo "Asset not found in cache: $pattern"
    exit 1
  fi
  printf '%s\n' "$candidate"
}

extract_zip_runtime() {
  local archive_path="$1"
  local dest_dir="$2"
  local tmp_dir
  tmp_dir="$(mktemp -d)"
  rm -rf "$dest_dir"
  mkdir -p "$dest_dir"
  unzip -qo "$archive_path" -d "$tmp_dir"
  local top_dir
  top_dir="$(find "$tmp_dir" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
  if [[ -z "$top_dir" ]]; then
    echo "Unable to find extracted directory in $archive_path"
    exit 1
  fi
  cp -R "$top_dir"/. "$dest_dir/"
  rm -rf "$tmp_dir"
}

extract_targz_runtime() {
  local archive_path="$1"
  local dest_dir="$2"
  local tmp_dir
  tmp_dir="$(mktemp -d)"
  rm -rf "$dest_dir"
  mkdir -p "$dest_dir"
  tar -xzf "$archive_path" -C "$tmp_dir"
  local top_dir
  top_dir="$(find "$tmp_dir" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
  if [[ -z "$top_dir" ]]; then
    echo "Unable to find extracted directory in $archive_path"
    exit 1
  fi
  cp -R "$top_dir"/. "$dest_dir/"
  rm -rf "$tmp_dir"
}

main() {
  require_cmd gh
  require_cmd unzip
  require_cmd tar

  local tag
  tag="$(resolve_latest_tag)"

  download_release_asset "$tag" "$WINDOWS_PATTERN"
  download_release_asset "$tag" "$LINUX_PATTERN"

  local windows_archive
  local linux_archive
  windows_archive="$(resolve_cached_asset "$WINDOWS_PATTERN")"
  linux_archive="$(resolve_cached_asset "$LINUX_PATTERN")"

  extract_zip_runtime "$windows_archive" "$WINDOWS_RUNTIME_ROOT"
  extract_targz_runtime "$linux_archive" "$LINUX_RUNTIME_ROOT"

  echo
  echo "Prepared bundled runtimes:"
  echo "Tag: $tag"
  find "$RUNTIME_ROOT" -maxdepth 2 -type d | sort
}

main "$@"

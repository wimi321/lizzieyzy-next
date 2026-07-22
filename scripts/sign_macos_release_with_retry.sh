#!/usr/bin/env bash
# Retry the complete macOS signing transaction when Apple's timestamp or
# notarization services are temporarily unavailable. The underlying script
# replaces the public DMG only after signing and notarization both succeed.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sign_script="${MACOS_SIGN_SCRIPT:-$ROOT_DIR/scripts/sign_macos_release.sh}"
max_attempts="${MACOS_SIGN_MAX_ATTEMPTS:-3}"
retry_delay_seconds="${MACOS_SIGN_RETRY_DELAY_SECONDS:-30}"

if [[ ! "$max_attempts" =~ ^[1-9][0-9]*$ ]]; then
  echo "MACOS_SIGN_MAX_ATTEMPTS must be a positive integer" >&2
  exit 2
fi
if [[ ! "$retry_delay_seconds" =~ ^[0-9]+$ ]]; then
  echo "MACOS_SIGN_RETRY_DELAY_SECONDS must be a non-negative integer" >&2
  exit 2
fi

for ((attempt = 1; attempt <= max_attempts; attempt++)); do
  if bash "$sign_script" "$@"; then
    exit 0
  fi

  if ((attempt == max_attempts)); then
    echo "macOS signing/notarization failed after $attempt attempts" >&2
    exit 1
  fi

  delay=$((attempt * retry_delay_seconds))
  echo "macOS signing/notarization attempt $attempt/$max_attempts failed; retrying in ${delay}s..." >&2
  sleep "$delay"
done

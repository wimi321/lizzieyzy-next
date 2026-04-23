#!/usr/bin/env bash
# Sign and notarize macOS DMG release assets.
#
# Requires the following environment variables (typically provided via GitHub
# Actions secrets). If any of them are empty, the script exits 0 and leaves the
# unsigned asset untouched so local builds are unaffected.
#
#   APPLE_CERT_P12         Base64-encoded Developer ID Application .p12
#   APPLE_CERT_PASSWORD    Password for the .p12 (may be empty)
#   APPLE_ID               Apple ID email for notarytool
#   APPLE_APP_PASSWORD     App-specific password for notarytool
#   APPLE_TEAM_ID          10-character Developer Team ID
#   APPLE_SIGN_IDENTITY    Optional override, e.g. "Developer ID Application: Name (TEAMID)"
#
# Usage: sign_macos_release.sh <release-dir> <mac-arch>
#        release-dir contains the public *.dmg asset
#        mac-arch is either "mac-arm64" or "mac-amd64"
set -euo pipefail

release_dir="${1:-dist/release}"
mac_arch="${2:-mac-arm64}"
entitlements_path="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/packaging/macos-entitlements.plist"

if [[ -z "${APPLE_CERT_P12:-}" || -z "${APPLE_TEAM_ID:-}" ]]; then
  echo "Apple Developer credentials not configured; skipping sign/notarize."
  exit 0
fi

if ! command -v codesign >/dev/null 2>&1 || ! command -v xcrun >/dev/null 2>&1; then
  echo "codesign or xcrun not available; cannot sign on this host." >&2
  exit 1
fi

if [[ ! -f "$entitlements_path" ]]; then
  echo "Missing macOS entitlements file: $entitlements_path" >&2
  exit 1
fi

keychain="lizzieyzy-sign.keychain-db"
keychain_password="$(openssl rand -hex 16)"
security create-keychain -p "$keychain_password" "$keychain" >/dev/null
security set-keychain-settings -lut 21600 "$keychain" >/dev/null
security unlock-keychain -p "$keychain_password" "$keychain" >/dev/null

cert_path="$(mktemp -t lizzieyzy-cert.XXXXXX).p12"
printf '%s' "$APPLE_CERT_P12" | base64 --decode > "$cert_path"
security import "$cert_path" -k "$keychain" -P "${APPLE_CERT_PASSWORD:-}" -T /usr/bin/codesign >/dev/null
security list-keychains -d user -s "$keychain" "$(security list-keychains -d user | tr -d '"')"
security set-key-partition-list -S apple-tool:,apple: -s -k "$keychain_password" "$keychain" >/dev/null
rm -f "$cert_path"

sign_identity="${APPLE_SIGN_IDENTITY:-}"
if [[ -z "$sign_identity" ]]; then
  sign_identity="$(security find-identity -v -p codesigning "$keychain" | awk -F '"' '/Developer ID Application/ {print $2; exit}')"
fi
if [[ -z "$sign_identity" ]]; then
  echo "Could not locate a Developer ID Application identity in keychain." >&2
  security delete-keychain "$keychain" >/dev/null 2>&1 || true
  exit 1
fi
echo "Using signing identity: $sign_identity"

cleanup() {
  security delete-keychain "$keychain" >/dev/null 2>&1 || true
}
trap cleanup EXIT

sign_embedded_jar_natives() {
  local app_bundle="$1"
  local jar_file
  local signed_any=0

  while IFS= read -r -d '' jar_file; do
    if ! jar tf "$jar_file" | grep -Eq '\.(dylib|jnilib)$'; then
      continue
    fi

    local jar_work rebuilt_jar native_count
    jar_work="$(mktemp -d -t lizzieyzy-jar-sign.XXXXXX)"
    rebuilt_jar="$(mktemp -t lizzieyzy-signed-jar.XXXXXX).jar"
    rm -f "$rebuilt_jar"

    (
      cd "$jar_work"
      jar xf "$jar_file"
    )

    native_count="$(
      find "$jar_work" -type f \( -name '*.dylib' -o -name '*.jnilib' \) -print | wc -l | tr -d ' '
    )"
    if [[ "$native_count" -eq 0 ]]; then
      rm -rf "$jar_work"
      continue
    fi

    echo "Signing $native_count native libraries embedded in $(basename "$jar_file")"
    find "$jar_work" -type f \( -name '*.dylib' -o -name '*.jnilib' \) -print0 |
      xargs -0 codesign --force --options runtime --timestamp \
                         --keychain "$keychain" --sign "$sign_identity"

    (
      cd "$jar_work"
      zip -qry "$rebuilt_jar" .
    )
    mv "$rebuilt_jar" "$jar_file"
    rm -rf "$jar_work"
    signed_any=1
  done < <(find "$app_bundle/Contents/app" -type f -name '*.jar' -print0 2>/dev/null)

  if [[ "$signed_any" -eq 0 ]]; then
    echo "No embedded native libraries found inside app jars."
  fi
}

verify_required_entitlements() {
  local code_path="$1"
  local entitlement_dump

  entitlement_dump="$(codesign -d --entitlements /dev/stdout "$code_path" 2>&1 || true)"
  if [[ -z "$entitlement_dump" ]]; then
    echo "Failed to read entitlements from $code_path" >&2
    exit 1
  fi

  for required_key in \
    "com.apple.security.cs.allow-jit" \
    "com.apple.security.cs.allow-unsigned-executable-memory" \
    "com.apple.security.cs.disable-library-validation"; do
    if ! grep -q "$required_key" <<<"$entitlement_dump"; then
      echo "Missing required entitlement $required_key on $code_path" >&2
      echo "$entitlement_dump" >&2
      exit 1
    fi
  done
}

case "$mac_arch" in
  mac-arm64)
    dmg_pattern="*mac-apple-silicon*.dmg"
    ;;
  mac-amd64)
    dmg_pattern="*mac-intel*.dmg"
    ;;
  *)
    dmg_pattern="*${mac_arch}*.dmg"
    ;;
esac
shopt -s nullglob
dmg_files=( "$release_dir"/$dmg_pattern )
shopt -u nullglob
if [[ ${#dmg_files[@]} -eq 0 ]]; then
  echo "No DMG matching $dmg_pattern in $release_dir" >&2
  exit 1
fi

for dmg in "${dmg_files[@]}"; do
  echo "Processing $dmg"

  work_dir="$(mktemp -d -t lizzieyzy-sign.XXXXXX)"
  mount_point="$work_dir/mount"
  mkdir -p "$mount_point"

  hdiutil attach "$dmg" -mountpoint "$mount_point" -nobrowse -noautoopen -readonly >/dev/null
  app_path="$(find "$mount_point" -maxdepth 2 -name '*.app' -print -quit || true)"
  if [[ -z "$app_path" ]]; then
    hdiutil detach "$mount_point" -quiet >/dev/null 2>&1 || true
    echo "No .app bundle found inside $dmg" >&2
    exit 1
  fi

  staging="$work_dir/staging"
  mkdir -p "$staging"
  cp -R "$app_path" "$staging/"
  hdiutil detach "$mount_point" -quiet >/dev/null 2>&1 || true

  staged_app="$staging/$(basename "$app_path")"

  sign_embedded_jar_natives "$staged_app"

  # Sign every helper binary first so the outer signature is valid.
  find "$staged_app" -type f \( -name '*.dylib' -o -name 'katago' -o -perm -u+x \) \
    -exec codesign --force --options runtime --timestamp \
                   --keychain "$keychain" --sign "$sign_identity" {} + >/dev/null

  launcher_path="$staged_app/Contents/MacOS/$(basename "$staged_app" .app)"
  if [[ ! -x "$launcher_path" ]]; then
    echo "Main launcher not found: $launcher_path" >&2
    exit 1
  fi

  codesign --force --options runtime --timestamp \
           --entitlements "$entitlements_path" \
           --keychain "$keychain" --sign "$sign_identity" "$launcher_path"

  codesign --force --deep --options runtime --timestamp \
           --entitlements "$entitlements_path" \
           --keychain "$keychain" --sign "$sign_identity" "$staged_app"

  verify_required_entitlements "$staged_app"
  verify_required_entitlements "$launcher_path"

  # Rebuild DMG from the signed app.
  signed_dmg="$work_dir/$(basename "$dmg")"
  hdiutil create -volname "$(basename "$staged_app" .app)" \
                 -srcfolder "$staging" -ov -format UDZO "$signed_dmg" >/dev/null
  codesign --force --timestamp --keychain "$keychain" --sign "$sign_identity" "$signed_dmg"

  echo "Submitting $signed_dmg for notarization..."
  notary_json="$work_dir/notary-submit.json"
  xcrun notarytool submit "$signed_dmg" \
    --apple-id "${APPLE_ID}" \
    --password "${APPLE_APP_PASSWORD}" \
    --team-id "${APPLE_TEAM_ID}" \
    --wait \
    --output-format json >"$notary_json"
  cat "$notary_json"
  submission_id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("id",""))' "$notary_json")"
  notary_status="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("status",""))' "$notary_json")"
  if [[ "$notary_status" != "Accepted" ]]; then
    echo "Notarization failed with status: $notary_status" >&2
    if [[ -n "$submission_id" ]]; then
      xcrun notarytool log "$submission_id" \
        --apple-id "${APPLE_ID}" \
        --password "${APPLE_APP_PASSWORD}" \
        --team-id "${APPLE_TEAM_ID}" || true
    fi
    exit 1
  fi

  xcrun stapler staple "$signed_dmg"
  spctl --assess --type open --context context:primary-signature -vvv "$signed_dmg" || true

  mv "$signed_dmg" "$dmg"
  rm -rf "$work_dir"
  echo "Signed and notarized: $dmg"
done

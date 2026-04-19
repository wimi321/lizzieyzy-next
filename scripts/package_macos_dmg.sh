#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
source "$ROOT_DIR/scripts/release_metadata.sh"

DATE_TAG="${1:-$(date +%F)}"
APP_VERSION="${2:-2.5.3}"
JAR_PATH="${3:-target/lizzie-yzy2.5.3-shaded.jar}"

JAVA_HOME_DEFAULT="$ROOT_DIR/.tools/jdk-21/jdk-21.0.10.jdk/Contents/Home"
if [[ -d "$JAVA_HOME_DEFAULT" ]]; then
  export JAVA_HOME="$JAVA_HOME_DEFAULT"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

if ! command -v jpackage >/dev/null 2>&1; then
  echo "jpackage not found. Please use JDK 14+ with jpackage."
  exit 1
fi

if [[ ! -f "$JAR_PATH" ]]; then
  echo "Jar not found: $JAR_PATH"
  echo "Build first: mvn -DskipTests package"
  exit 1
fi

ARCH="$(uname -m)"
if [[ "$ARCH" == "arm64" ]]; then
  ARCH_TAG="mac-arm64"
  ENGINE_PLATFORM_DIR="macos-arm64"
else
  ARCH_TAG="mac-amd64"
  ENGINE_PLATFORM_DIR="macos-amd64"
fi

has_bundled_katago() {
  [[ -f "$ROOT_DIR/weights/default.bin.gz" ]] \
    && [[ -d "$ROOT_DIR/engines/katago/$ENGINE_PLATFORM_DIR" ]] \
    && find "$ROOT_DIR/engines/katago/$ENGINE_PLATFORM_DIR" -maxdepth 1 -type f | grep -q .
}

if has_bundled_katago; then
  PACKAGE_FLAVOR="with-katago"
  PACKAGE_NOTE="Bundled KataGo included for this macOS package."
else
  PACKAGE_FLAVOR="without.engine"
  PACKAGE_NOTE="No bundled KataGo in this macOS package."
fi

copy_bundle_engine_assets() {
  if ! has_bundled_katago; then
    return 0
  fi

  mkdir -p "$INPUT_DIR/engines/katago" "$INPUT_DIR/weights"
  cp -R "$ROOT_DIR/engines/katago/$ENGINE_PLATFORM_DIR" "$INPUT_DIR/engines/katago/"
  if [[ -d "$ROOT_DIR/engines/katago/configs" ]]; then
    cp -R "$ROOT_DIR/engines/katago/configs" "$INPUT_DIR/engines/katago/"
  fi
  if [[ -f "$ROOT_DIR/engines/katago/VERSION.txt" ]]; then
    cp "$ROOT_DIR/engines/katago/VERSION.txt" "$INPUT_DIR/engines/katago/"
  fi
  cp "$ROOT_DIR/weights/default.bin.gz" "$INPUT_DIR/weights/default.bin.gz"
}

DIST_DIR="$ROOT_DIR/dist/macos"
INPUT_DIR="$DIST_DIR/input"
APP_IMAGE_DIR="$DIST_DIR/app-image"
DMG_DIR="$DIST_DIR/dmg"
META_DIR="$ROOT_DIR/dist/release-meta"
rm -rf "$INPUT_DIR" "$APP_IMAGE_DIR" "$DMG_DIR"
mkdir -p "$INPUT_DIR" "$APP_IMAGE_DIR" "$DMG_DIR" "$META_DIR"

cp "$JAR_PATH" "$INPUT_DIR/"
cp README.md README_EN.md README_JA.md README_KO.md LICENSE.txt packaging/PROJECT_INFO.txt "$INPUT_DIR/"
cp readme_cn.pdf readme_en.pdf "$INPUT_DIR/"
if [[ -d "$ROOT_DIR/src/main/resources/assets/readboard_java" ]]; then
  mkdir -p "$INPUT_DIR/readboard_java"
  cp -R "$ROOT_DIR/src/main/resources/assets/readboard_java/." "$INPUT_DIR/readboard_java/"
fi
copy_bundle_engine_assets

APP_NAME="LizzieYzy Next"
APP_DESCRIPTION="Maintained LizzieYzy build with Fox nickname fetch and easier KataGo setup"
MAIN_JAR="$(basename "$JAR_PATH")"
ICON_PATH="$ROOT_DIR/packaging/icons/app-icon.icns"
IDENTIFIER="com.wimi321.lizzieyzy.next"

jpackage \
  --type app-image \
  --name "$APP_NAME" \
  --input "$INPUT_DIR" \
  --main-jar "$MAIN_JAR" \
  --main-class featurecat.lizzie.Lizzie \
  --dest "$APP_IMAGE_DIR" \
  --app-version "$APP_VERSION" \
  --vendor "wimi321" \
  --description "$APP_DESCRIPTION" \
  --icon "$ICON_PATH" \
  --java-options "-Xmx4096m"

jpackage \
  --type dmg \
  --name "$APP_NAME" \
  --input "$INPUT_DIR" \
  --main-jar "$MAIN_JAR" \
  --main-class featurecat.lizzie.Lizzie \
  --dest "$DMG_DIR" \
  --app-version "$APP_VERSION" \
  --vendor "wimi321" \
  --description "$APP_DESCRIPTION" \
  --icon "$ICON_PATH" \
  --mac-package-identifier "$IDENTIFIER" \
  --java-options "-Xmx4096m"

DMG_FILE="$(ls "$DMG_DIR"/*.dmg | head -n 1)"
FINAL_DMG="$ROOT_DIR/dist/release/${DATE_TAG}-${ARCH_TAG}.${PACKAGE_FLAVOR}.dmg"
INSTALL_NOTE="$META_DIR/${DATE_TAG}-${ARCH_TAG}-install.txt"
SHA256_FILE="$META_DIR/${DATE_TAG}-${ARCH_TAG}-sha256.txt"

mkdir -p "$ROOT_DIR/dist/release"
cp "$DMG_FILE" "$FINAL_DMG"

cat >"$INSTALL_NOTE" <<EOF
Package type: unsigned macOS app + dmg
Build architecture: $ARCH
Generated on: $DATE_TAG
Main asset: $(basename "$FINAL_DMG")
Engine: $PACKAGE_NOTE

Install:
1. Open $(basename "$FINAL_DMG").
2. Drag the app to Applications.
3. Launch it from Applications.

Download verification:
- Compare the file hash with $(basename "$SHA256_FILE")
- Example:
  shasum -a 256 $(basename "$FINAL_DMG")

If macOS blocks the first launch:
1. Try to open the app once.
2. Go to System Settings -> Privacy & Security.
3. Click Open Anyway.
4. Launch the app again.

Bundled KataGo paths inside the app bundle:
- Engine: LizzieYzy Next.app/Contents/app/engines/katago/$ENGINE_PLATFORM_DIR/katago
- Weight: LizzieYzy Next.app/Contents/app/weights/default.bin.gz
- Configs: LizzieYzy Next.app/Contents/app/engines/katago/configs/

Notes:
- This package is unsigned and not notarized.
- For Intel/Apple Silicon dual-native support, build once on each architecture.
- The built-in Java readboard helper is included in `LizzieYzy Next.app/Contents/app/readboard_java/`.
EOF

write_sha256_file "$SHA256_FILE" "$FINAL_DMG" "$INSTALL_NOTE"

echo "Artifacts:"
ls -lh "$FINAL_DMG"
echo
echo "Maintainer metadata:"
ls -lh "$INSTALL_NOTE" "$SHA256_FILE"

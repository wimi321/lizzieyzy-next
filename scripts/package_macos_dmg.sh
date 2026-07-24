#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
source "$ROOT_DIR/scripts/release_metadata.sh"

DATE_TAG="${1:-$(date +%F)}"
APP_VERSION="${2:-1.0.0}"
JAR_PATH="${3:-target/lizzie-yzy2.5.3-shaded.jar}"
APP_DISPLAY_VERSION="${LIZZIE_NEXT_VERSION:-${4:-next-dev}}"

JAVA_HOME_DEFAULT="$ROOT_DIR/.tools/jdk-21/jdk-21.0.10.jdk/Contents/Home"
if [[ -d "$JAVA_HOME_DEFAULT" ]]; then
  export JAVA_HOME="$JAVA_HOME_DEFAULT"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

if ! command -v jpackage >/dev/null 2>&1; then
  echo "jpackage not found. Please use JDK 14+ with jpackage."
  exit 1
fi

PYTHON_BIN=""
RUNTIME_TOOLS_SCRIPT="$ROOT_DIR/scripts/package_runtime_tools.py"
JCEF_BUNDLE_PREPARE_SCRIPT="$ROOT_DIR/scripts/prepare_bundled_jcef.py"
KATAGO_BUNDLE_SCRIPT="$ROOT_DIR/scripts/macos_katago_bundle.py"
JCEF_RELEASE_TAG="jcef-99c2f7a+cef-127.3.1+g6cbb30e+chromium-127.0.6533.100"
JCEF_JAVA_OPTIONS=(
  "--add-exports=java.desktop/sun.awt=ALL-UNNAMED"
  "--add-exports=java.desktop/sun.lwawt=ALL-UNNAMED"
  "--add-exports=java.desktop/sun.lwawt.macosx=ALL-UNNAMED"
)
JCEF_JPACKAGE_ARGS=()
for option in "${JCEF_JAVA_OPTIONS[@]}"; do
  JCEF_JPACKAGE_ARGS+=(--java-options "$option")
done

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
  echo "Python not found. Runtime optimization and audit helpers require Python 3."
  exit 1
}

DRAG_DMG_SCRIPT="$ROOT_DIR/scripts/create_macos_drag_dmg.sh"
if [[ ! -x "$DRAG_DMG_SCRIPT" ]]; then
  echo "Missing executable DMG layout helper: $DRAG_DMG_SCRIPT"
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
  PUBLIC_ARCH_TAG="mac-apple-silicon"
  ENGINE_PLATFORM_DIR="macos-arm64"
  JCEF_PLATFORM="macosx-arm64"
  JCEF_ASSET_SHA256="1746a503e38614ea3e4fe7986e22443ab48a3a245ba1f4b17575aaccab5e7994"
else
  ARCH_TAG="mac-amd64"
  PUBLIC_ARCH_TAG="mac-intel"
  ENGINE_PLATFORM_DIR="macos-amd64"
  JCEF_PLATFORM="macosx-amd64"
  JCEF_ASSET_SHA256="36ed38af450dff481513c352a92a88aaa73ec34a399edadc7a4a947c7d1ddaed"
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

prepare_bundled_jcef_assets() {
  if [[ ! -f "$JCEF_BUNDLE_PREPARE_SCRIPT" ]]; then
    echo "Missing JCEF prepare script: $JCEF_BUNDLE_PREPARE_SCRIPT"
    exit 1
  fi
  resolve_python_bin
  "$PYTHON_BIN" "$JCEF_BUNDLE_PREPARE_SCRIPT" \
    --platform "$JCEF_PLATFORM" \
    --cache-dir "$ROOT_DIR/.cache/jcef" \
    --output-dir "$JCEF_BUNDLE_STAGE_DIR"
}

copy_bundled_jcef_assets() {
  local manifest="$JCEF_BUNDLE_STAGE_DIR/lizzieyzy-next-jcef-manifest.txt"
  if [[ ! -f "$JCEF_BUNDLE_STAGE_DIR/build_meta.json" ]] \
    || [[ ! -f "$JCEF_BUNDLE_STAGE_DIR/install.lock" ]] \
    || [[ ! -f "$JCEF_BUNDLE_STAGE_DIR/libjcef.dylib" ]] \
    || [[ ! -f "$JCEF_BUNDLE_STAGE_DIR/Chromium Embedded Framework.framework/Chromium Embedded Framework" ]] \
    || [[ ! -f "$JCEF_BUNDLE_STAGE_DIR/jcef Helper.app/Contents/MacOS/jcef Helper" ]] \
    || [[ ! -f "$manifest" ]]; then
    echo "Prepared macOS JCEF runtime is incomplete: $JCEF_BUNDLE_STAGE_DIR"
    exit 1
  fi
  grep -qx "release-tag=$JCEF_RELEASE_TAG" "$manifest"
  grep -qx "platform=$JCEF_PLATFORM" "$manifest"
  grep -qx "sha256=$JCEF_ASSET_SHA256" "$manifest"
  cp -R "$JCEF_BUNDLE_STAGE_DIR" "$INPUT_DIR/jcef-bundle"
}

DIST_DIR="$ROOT_DIR/dist/macos"
INPUT_DIR="$DIST_DIR/input"
APP_IMAGE_DIR="$DIST_DIR/app-image"
JCEF_BUNDLE_STAGE_DIR="$DIST_DIR/jcef-bundle"
META_DIR="$ROOT_DIR/dist/release-meta"
rm -rf "$INPUT_DIR" "$APP_IMAGE_DIR"
mkdir -p "$INPUT_DIR" "$APP_IMAGE_DIR" "$META_DIR"

CUSTOM_RUNTIME_DIR="$DIST_DIR/custom-runtime/$PUBLIC_ARCH_TAG"
JPACKAGE_RUNTIME_ARGS=()

prepare_custom_runtime() {
  if [[ "${LIZZIE_PACKAGE_OPTIMIZE_RUNTIME:-1}" != "1" ]]; then
    return 0
  fi
  resolve_python_bin
  "$PYTHON_BIN" "$RUNTIME_TOOLS_SCRIPT" optimize-runtime \
    --jar "$JAR_PATH" \
    --output "$CUSTOM_RUNTIME_DIR" \
    --manifest "$META_DIR/${DATE_TAG}-${PUBLIC_ARCH_TAG}-runtime.json" \
    --platform "$PUBLIC_ARCH_TAG" \
    --optional
  if [[ -d "$CUSTOM_RUNTIME_DIR" ]]; then
    JPACKAGE_RUNTIME_ARGS=(--runtime-image "$CUSTOM_RUNTIME_DIR")
  fi
}

prepare_bundled_jcef_assets

cp "$JAR_PATH" "$INPUT_DIR/"
cp README.md README_EN.md README_JA.md README_KO.md LICENSE.txt packaging/PROJECT_INFO.txt "$INPUT_DIR/"
cp readme_cn.pdf readme_en.pdf "$INPUT_DIR/"
copy_bundle_engine_assets
copy_bundled_jcef_assets

APP_NAME="LizzieYzy Next"
APP_DESCRIPTION="Maintained LizzieYzy build with Fox nickname fetch and easier KataGo setup"
MAIN_JAR="$(basename "$JAR_PATH")"
ICON_PATH="$ROOT_DIR/packaging/icons/app-icon.icns"
IDENTIFIER="com.wimi321.lizzieyzy.next"

prepare_custom_runtime

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
  --mac-package-identifier "$IDENTIFIER" \
  "${JPACKAGE_RUNTIME_ARGS[@]}" \
  "${JCEF_JPACKAGE_ARGS[@]}" \
  --java-options "-Xmx4096m" \
  --java-options "-Xshare:auto" \
  --java-options "-Dlizzie.next.version=$APP_DISPLAY_VERSION"

APP_CONFIG="$APP_IMAGE_DIR/$APP_NAME.app/Contents/app/$APP_NAME.cfg"
if [[ ! -f "$APP_CONFIG" ]]; then
  echo "Packaged macOS launcher config is missing: $APP_CONFIG"
  exit 1
fi
for option in "${JCEF_JAVA_OPTIONS[@]}"; do
  if ! grep -Fqx "java-options=$option" "$APP_CONFIG"; then
    echo "Packaged macOS launcher is missing JCEF option: $option"
    exit 1
  fi
done

if [[ "$PACKAGE_FLAVOR" == "with-katago" ]]; then
  resolve_python_bin
  "$PYTHON_BIN" "$KATAGO_BUNDLE_SCRIPT" audit \
    --bundle "$APP_IMAGE_DIR/$APP_NAME.app/Contents/app/engines/katago/$ENGINE_PLATFORM_DIR"
fi

FINAL_DMG="$ROOT_DIR/dist/release/${DATE_TAG}-${PUBLIC_ARCH_TAG}.${PACKAGE_FLAVOR}.dmg"
INSTALL_NOTE="$META_DIR/${DATE_TAG}-${PUBLIC_ARCH_TAG}-install.txt"
SHA256_FILE="$META_DIR/${DATE_TAG}-${PUBLIC_ARCH_TAG}-sha256.txt"

mkdir -p "$ROOT_DIR/dist/release"
"$DRAG_DMG_SCRIPT" "$APP_NAME" "$APP_IMAGE_DIR" "$FINAL_DMG"

cat >"$INSTALL_NOTE" <<EOF
Package type: unsigned macOS app + dmg
Build architecture: $ARCH
Generated on: $DATE_TAG
Release display version: $APP_DISPLAY_VERSION
Main asset: $(basename "$FINAL_DMG")
Engine: $PACKAGE_NOTE
Browser: Bundled JCEF $JCEF_RELEASE_TAG ($JCEF_PLATFORM)

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

Bundled browser runtime:
- LizzieYzy Next.app/Contents/app/jcef-bundle/
- Used by the built-in Yike web page and Yike hall; no first-use browser download is required.

Notes:
- This package is unsigned and not notarized.
- For Intel/Apple Silicon dual-native support, build once on each architecture.
- Board sync uses the native readboard tool in Windows release packages; the retired Java helper is not bundled.
EOF

write_sha256_file "$SHA256_FILE" "$FINAL_DMG" "$INSTALL_NOTE"

resolve_python_bin
"$PYTHON_BIN" "$RUNTIME_TOOLS_SCRIPT" audit-sizes \
  --root "$ROOT_DIR" \
  --output "$META_DIR/${DATE_TAG}-${PUBLIC_ARCH_TAG}-package-size-audit.md" \
  --json-output "$META_DIR/${DATE_TAG}-${PUBLIC_ARCH_TAG}-package-size-audit.json" \
  --release-prefix "$DATE_TAG" \
  --custom-runtime "$CUSTOM_RUNTIME_DIR"

echo "Artifacts:"
ls -lh "$FINAL_DMG"
echo
echo "Maintainer metadata:"
ls -lh "$INSTALL_NOTE" "$SHA256_FILE"

#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
source "$ROOT_DIR/scripts/release_metadata.sh"

if ! command -v zip >/dev/null 2>&1; then
  echo "zip command not found"
  exit 1
fi

DATE_TAG="${1:-$(date +%F)}"
JAR_PATH="${2:-target/lizzie-yzy2.5.3-shaded.jar}"
APP_DISPLAY_VERSION="${LIZZIE_NEXT_VERSION:-${3:-1.0.0-dev}}"
LEGACY_WINDOWS64_ZIP="${LEGACY_WINDOWS64_ZIP:-0}"
LEGACY_WINDOWS32_ZIP="${LEGACY_WINDOWS32_ZIP:-0}"
LEGACY_OTHER_SYSTEMS_ZIP="${LEGACY_OTHER_SYSTEMS_ZIP:-0}"
ENGINE_BACKEND_MARKER_NAME="lizzieyzy-next-engine-backend.txt"
LINUX_STANDARD_ENGINE_PLATFORM_DIR="${LINUX_STANDARD_ENGINE_PLATFORM_DIR:-linux-x64}"
LINUX_OPENCL_ENGINE_PLATFORM_DIR="${LINUX_OPENCL_ENGINE_PLATFORM_DIR:-linux-x64-opencl}"
LINUX_NVIDIA_ENGINE_PLATFORM_DIR="${LINUX_NVIDIA_ENGINE_PLATFORM_DIR:-linux-x64-nvidia}"

if [[ ! -f "$JAR_PATH" ]]; then
  echo "Jar not found: $JAR_PATH"
  echo "Build first: mvn -DskipTests package"
  exit 1
fi

STAGE_DIR="dist/stage"
OUT_DIR="dist/release"
META_DIR="dist/release-meta"
rm -rf "$STAGE_DIR" "$OUT_DIR" "$META_DIR"
mkdir -p "$STAGE_DIR" "$OUT_DIR" "$META_DIR"

has_default_weight() {
  [[ -f "$ROOT_DIR/weights/default.bin.gz" ]]
}

has_engine_files() {
  local platform_dir="$1"
  [[ -d "$ROOT_DIR/engines/katago/$platform_dir" ]] \
    && find "$ROOT_DIR/engines/katago/$platform_dir" -maxdepth 1 -type f | grep -q .
}

require_bundle_engine_files() {
  local platform_dir="$1"
  local backend_label="$2"

  if ! has_default_weight; then
    echo "Missing bundled KataGo weight: $ROOT_DIR/weights/default.bin.gz"
    exit 1
  fi
  if ! has_engine_files "$platform_dir"; then
    echo "Missing bundled $backend_label KataGo files: $ROOT_DIR/engines/katago/$platform_dir"
    exit 1
  fi
}

has_runtime_files() {
  local platform_dir="$1"
  [[ -d "$ROOT_DIR/runtime/$platform_dir" ]] \
    && find "$ROOT_DIR/runtime/$platform_dir" -mindepth 1 -print -quit | grep -q .
}

bundle_flavor() {
  local platform_dir="$1"
  if has_default_weight && has_engine_files "$platform_dir"; then
    echo "with-katago"
  else
    echo "without.engine"
  fi
}

bundle_flavor_multi() {
  if ! has_default_weight; then
    echo "without.engine"
    return 0
  fi

  local platform_dir
  for platform_dir in "$@"; do
    if ! has_engine_files "$platform_dir"; then
      echo "without.engine"
      return 0
    fi
  done

  echo "with-katago"
}

copy_katago_shared_assets() {
  local app_dir="$1"

  if [[ -d "$ROOT_DIR/engines/katago/configs" ]]; then
    mkdir -p "$app_dir/engines/katago"
    cp -R "$ROOT_DIR/engines/katago/configs" "$app_dir/engines/katago/"
  fi
  if [[ -f "$ROOT_DIR/engines/katago/VERSION.txt" ]]; then
    mkdir -p "$app_dir/engines/katago"
    cp "$ROOT_DIR/engines/katago/VERSION.txt" "$app_dir/engines/katago/"
  fi

  mkdir -p "$app_dir/weights"
  cp "$ROOT_DIR/weights/default.bin.gz" "$app_dir/weights/default.bin.gz"
}

copy_bundle_engine_assets_as() {
  local app_dir="$1"
  local source_platform_dir="$2"
  local target_platform_dir="$3"
  local engine_backend="${4:-}"

  if [[ -z "$source_platform_dir" ]] || ! has_default_weight || ! has_engine_files "$source_platform_dir"; then
    return 0
  fi

  mkdir -p "$app_dir/engines/katago"
  rm -rf "$app_dir/engines/katago/$target_platform_dir"
  cp -R "$ROOT_DIR/engines/katago/$source_platform_dir" "$app_dir/engines/katago/$target_platform_dir"
  if [[ -n "$engine_backend" ]]; then
    printf '%s\n' "$engine_backend" \
      >"$app_dir/engines/katago/$target_platform_dir/$ENGINE_BACKEND_MARKER_NAME"
  fi
  copy_katago_shared_assets "$app_dir"
}

copy_bundle_engine_assets() {
  local app_dir="$1"
  local platform_dirs_csv="$2"

  if [[ -z "$platform_dirs_csv" ]]; then
    return 0
  fi

  local platform_dir
  IFS=',' read -r -a platform_dirs <<<"$platform_dirs_csv"
  for platform_dir in "${platform_dirs[@]}"; do
    copy_bundle_engine_assets_as "$app_dir" "$platform_dir" "$platform_dir"
  done
}

copy_bundle_runtime_assets() {
  local app_dir="$1"
  local platform_dir="$2"

  if [[ -z "$platform_dir" ]] || ! has_runtime_files "$platform_dir"; then
    return 0
  fi

  mkdir -p "$app_dir/runtime"
  cp -R "$ROOT_DIR/runtime/$platform_dir" "$app_dir/runtime/"
}

copy_desktop_helper_assets() {
  local app_dir="$1"

  if [[ -d "$ROOT_DIR/src/main/resources/assets/readboard_java" ]]; then
    mkdir -p "$app_dir/readboard_java"
    cp -R "$ROOT_DIR/src/main/resources/assets/readboard_java/." "$app_dir/readboard_java/"
  fi
}

write_linux_install_note() {
  local cpu_asset_path="$1"
  local opencl_asset_path="$2"
  local nvidia_asset_path="$3"
  local note_file="$META_DIR/${DATE_TAG}-linux64-install.txt"

  cat >"$note_file" <<EOF
Package type: Linux x64 release assets
Generated on: $DATE_TAG
Release display version: $APP_DISPLAY_VERSION
CPU asset: $(basename "$cpu_asset_path")
OpenCL asset: $(basename "$opencl_asset_path")
NVIDIA asset: $(basename "$nvidia_asset_path")

How to use:
1. Pick one Linux package:
   - CPU / most compatible: $(basename "$cpu_asset_path")
   - AMD or Intel GPU with OpenCL driver: $(basename "$opencl_asset_path")
   - NVIDIA GPU with a compatible proprietary driver: $(basename "$nvidia_asset_path")
2. Extract it to a writable folder
3. Open a terminal in the extracted folder
4. Run:
   chmod +x start-linux64.sh
   ./start-linux64.sh

How to verify the download:
- Compare the file hash with ${DATE_TAG}-linux64-sha256.txt
- Example commands:
  shasum -a 256 $(basename "$cpu_asset_path")
  sha256sum $(basename "$opencl_asset_path")
  sha256sum $(basename "$nvidia_asset_path")

Bundled runtime:
- Linux builds include a bundled Java runtime when Lizzieyzy/runtime/linux-x64/ is present.

Bundled KataGo:
- All three packages include KataGo and the default weight.
- Each package stores its selected backend at Lizzieyzy/engines/katago/linux-x64/ so LizzieYzy can auto-detect it.
- Verify the selected backend with:
  ./Lizzieyzy/engines/katago/linux-x64/katago version
- The CPU package should report Eigen(CPU).
- The OpenCL package should report OpenCL. It still requires a working system OpenCL driver.
- The NVIDIA package should report CUDA/CUDNN when the NVIDIA driver stack is available.

Notes:
- If your desktop environment does not start the app on double-click, launch it from the terminal first.
- Fox kifu sync supports entering a Fox nickname directly.
- If nickname search succeeds, the app also shows the matched nickname and account number in the results.
- The built-in Java readboard helper is included in Lizzieyzy/readboard_java/.
EOF
}

make_bundle() {
  local bundle_name="$1"
  local start_file="$2"
  local start_content="$3"
  local bundle_note="$4"
  local engine_platforms="${5:-}"
  local runtime_platform="${6:-}"
  local root="$STAGE_DIR/$bundle_name"
  local app="$root/Lizzieyzy"

  mkdir -p "$app"
  cp "$JAR_PATH" "$app/"
  cp LICENSE.txt README.md README_EN.md README_JA.md README_KO.md readme_cn.pdf readme_en.pdf packaging/PROJECT_INFO.txt "$app/"
  copy_desktop_helper_assets "$app"
  copy_bundle_engine_assets "$app" "$engine_platforms"
  copy_bundle_runtime_assets "$app" "$runtime_platform"

  cat >"$root/$start_file" <<EOF
$start_content
EOF
  if [[ "$start_file" == *.sh ]]; then
    chmod +x "$root/$start_file"
  fi

  if [[ -n "$runtime_platform" ]] && has_runtime_files "$runtime_platform"; then
    cat >"$root/Required java version.txt" <<'EOF'
Bundled Java runtime included.
External Java installation is not required for this package.
EOF
  else
    cat >"$root/Required java version.txt" <<'EOF'
Java 11+ is required.
EOF
  fi

  cat >"$root/Update.txt" <<EOF
Project: LizzieYzy Next
Date: $DATE_TAG
Version: $APP_DISPLAY_VERSION
Changes:
- Restored Fox public-game fetch using a Fox nickname.
- First launch now prefers automatic bundled KataGo setup.
Package:
- $bundle_note
EOF

  if [[ -n "$engine_platforms" ]]; then
    cat >"$root/Bundled-KataGo.txt" <<EOF
This package includes bundled KataGo assets.

Weight file:
- Lizzieyzy/weights/default.bin.gz

Engine directories:
EOF
    local platform_dir
    IFS=',' read -r -a platform_dirs <<<"$engine_platforms"
    for platform_dir in "${platform_dirs[@]}"; do
      if has_engine_files "$platform_dir"; then
        printf -- "- Lizzieyzy/engines/katago/%s/\n" "$platform_dir" >>"$root/Bundled-KataGo.txt"
      fi
    done
    cat >>"$root/Bundled-KataGo.txt" <<'EOF'

Config files:
- Lizzieyzy/engines/katago/configs/
EOF
  fi
}

make_linux_bundle() {
  local bundle_name="$1"
  local engine_source_dir="$2"
  local engine_backend="$3"
  local bundle_note="$4"

  require_bundle_engine_files "$engine_source_dir" "$engine_backend"

  make_bundle \
    "$bundle_name" \
    "start-linux64.sh" \
    "#!/usr/bin/env bash
set -e
cd \"\$(dirname \"\$0\")\"
JAVA_CMD=\"java\"
if [[ -x \"Lizzieyzy/runtime/linux-x64/bin/java\" ]]; then
  JAVA_CMD=\"Lizzieyzy/runtime/linux-x64/bin/java\"
fi
\"\$JAVA_CMD\" -Dlizzie.next.version=\"$APP_DISPLAY_VERSION\" -jar \"Lizzieyzy/lizzie-yzy2.5.3-shaded.jar\"" \
    "$bundle_note" \
    "" \
    "$LINUX_STANDARD_ENGINE_PLATFORM_DIR"

  local root="$STAGE_DIR/$bundle_name"
  local app="$root/Lizzieyzy"
  copy_bundle_engine_assets_as \
    "$app" \
    "$engine_source_dir" \
    "$LINUX_STANDARD_ENGINE_PLATFORM_DIR" \
    "$engine_backend"

  cat >"$root/Bundled-KataGo.txt" <<EOF
This package includes bundled KataGo assets.

Selected backend:
- $engine_backend

Weight file:
- Lizzieyzy/weights/default.bin.gz

Engine directory:
- Lizzieyzy/engines/katago/$LINUX_STANDARD_ENGINE_PLATFORM_DIR/

Source bundle directory prepared by release workflow:
- engines/katago/$engine_source_dir/

Config files:
- Lizzieyzy/engines/katago/configs/

Backend verification:
- ./Lizzieyzy/engines/katago/$LINUX_STANDARD_ENGINE_PLATFORM_DIR/katago version
EOF
}

WINDOWS64_FLAVOR="$(bundle_flavor windows-x64)"
WINDOWS32_FLAVOR="$(bundle_flavor windows-x86)"
LINUX64_FLAVOR="$(bundle_flavor "$LINUX_STANDARD_ENGINE_PLATFORM_DIR")"
MAC_LINUX_FLAVOR="$(bundle_flavor_multi macos-amd64 "$LINUX_STANDARD_ENGINE_PLATFORM_DIR")"
if [[ "$WINDOWS64_FLAVOR" == "with-katago" ]]; then
  if has_runtime_files windows-x64; then
    WINDOWS64_RUNTIME_NOTE="Bundled KataGo and Java runtime included for Windows x64."
  else
    WINDOWS64_RUNTIME_NOTE="Bundled KataGo included for Windows x64. Install Java 11+ separately."
  fi
else
  WINDOWS64_RUNTIME_NOTE="No bundled KataGo in this package."
fi

WINDOWS32_RUNTIME_NOTE="No bundled KataGo in this package."

if [[ "$LINUX64_FLAVOR" == "with-katago" ]]; then
  if has_runtime_files "$LINUX_STANDARD_ENGINE_PLATFORM_DIR"; then
    LINUX64_RUNTIME_NOTE="Bundled KataGo Eigen/CPU backend and Java runtime included for Linux x64."
  else
    LINUX64_RUNTIME_NOTE="Bundled KataGo Eigen/CPU backend included for Linux x64. Install Java 11+ separately."
  fi
else
  LINUX64_RUNTIME_NOTE="No bundled KataGo in this package."
fi

if has_runtime_files "$LINUX_STANDARD_ENGINE_PLATFORM_DIR"; then
  LINUX64_OPENCL_RUNTIME_NOTE="Bundled KataGo OpenCL backend and Java runtime included for Linux x64. Requires a working system OpenCL driver."
  LINUX64_NVIDIA_RUNTIME_NOTE="Bundled KataGo NVIDIA CUDA backend and Java runtime included for Linux x64. Requires a compatible NVIDIA driver."
else
  LINUX64_OPENCL_RUNTIME_NOTE="Bundled KataGo OpenCL backend included for Linux x64. Install Java 11+ separately and make sure a system OpenCL driver is available."
  LINUX64_NVIDIA_RUNTIME_NOTE="Bundled KataGo NVIDIA CUDA backend included for Linux x64. Install Java 11+ separately and make sure a compatible NVIDIA driver is available."
fi

if [[ "$MAC_LINUX_FLAVOR" == "with-katago" ]]; then
  MAC_LINUX_RUNTIME_NOTE="Bundled KataGo included for macOS amd64 and Linux x64. Java is not bundled in this package."
else
  MAC_LINUX_RUNTIME_NOTE="No bundled KataGo in this package."
fi

if [[ "$LEGACY_WINDOWS64_ZIP" == "1" ]]; then
  make_bundle \
    "$DATE_TAG-windows64.$WINDOWS64_FLAVOR" \
    "start-windows64.bat" \
    "@echo off
setlocal
cd /d %~dp0
set \"JAVA_CMD=java\"
if exist \"Lizzieyzy\\runtime\\windows-x64\\bin\\java.exe\" set \"JAVA_CMD=Lizzieyzy\\runtime\\windows-x64\\bin\\java.exe\"
\"%JAVA_CMD%\" -Dlizzie.next.version=$APP_DISPLAY_VERSION -jar \"Lizzieyzy\\lizzie-yzy2.5.3-shaded.jar\"" \
    "$WINDOWS64_RUNTIME_NOTE" \
    "$( [[ "$WINDOWS64_FLAVOR" == "with-katago" ]] && echo "windows-x64" )" \
    "windows-x64"

  make_bundle \
    "$DATE_TAG-windows64.without.engine" \
    "start-windows64.bat" \
    "@echo off
setlocal
cd /d %~dp0
set \"JAVA_CMD=java\"
if exist \"Lizzieyzy\\runtime\\windows-x64\\bin\\java.exe\" set \"JAVA_CMD=Lizzieyzy\\runtime\\windows-x64\\bin\\java.exe\"
\"%JAVA_CMD%\" -Dlizzie.next.version=$APP_DISPLAY_VERSION -jar \"Lizzieyzy\\lizzie-yzy2.5.3-shaded.jar\"" \
    "No bundled KataGo in this package. Bundled Java runtime included for Windows x64 if present." \
    "" \
    "windows-x64"
fi

if [[ "$LEGACY_WINDOWS32_ZIP" == "1" ]]; then
make_bundle \
  "$DATE_TAG-windows32.$WINDOWS32_FLAVOR" \
  "start-windows32.bat" \
  "@echo off
setlocal
cd /d %~dp0
set \"JAVA_CMD=java\"
if exist \"Lizzieyzy\\runtime\\windows-x64\\bin\\java.exe\" set \"JAVA_CMD=Lizzieyzy\\runtime\\windows-x64\\bin\\java.exe\"
\"%JAVA_CMD%\" -Dlizzie.next.version=$APP_DISPLAY_VERSION -jar \"Lizzieyzy\\lizzie-yzy2.5.3-shaded.jar\"" \
  "$WINDOWS32_RUNTIME_NOTE" \
  "$( [[ "$WINDOWS32_FLAVOR" == "with-katago" ]] && echo "windows-x86" )"

fi

make_linux_bundle \
  "$DATE_TAG-linux64.with-katago" \
  "$LINUX_STANDARD_ENGINE_PLATFORM_DIR" \
  "cpu" \
  "$LINUX64_RUNTIME_NOTE"

make_linux_bundle \
  "$DATE_TAG-linux64.opencl" \
  "$LINUX_OPENCL_ENGINE_PLATFORM_DIR" \
  "opencl" \
  "$LINUX64_OPENCL_RUNTIME_NOTE"

make_linux_bundle \
  "$DATE_TAG-linux64.nvidia" \
  "$LINUX_NVIDIA_ENGINE_PLATFORM_DIR" \
  "nvidia" \
  "$LINUX64_NVIDIA_RUNTIME_NOTE"

if [[ "$LEGACY_OTHER_SYSTEMS_ZIP" == "1" ]]; then
make_bundle \
  "$DATE_TAG-Macosx.amd64.Linux.amd64.$MAC_LINUX_FLAVOR" \
  "start-macos-linux.sh" \
  "#!/usr/bin/env bash
set -e
cd \"\$(dirname \"\$0\")\"
if [[ \"\$(uname -s)\" == \"Darwin\" ]]; then
  ARCH=\"\$(uname -m)\"
  if [[ \"\$ARCH\" == \"arm64\" ]]; then
    sh ./start-mac-arm64.sh
    exit 0
  fi
  sh ./start-mac-amd64.sh
  exit 0
fi
java -Dlizzie.next.version=\"$APP_DISPLAY_VERSION\" -jar \"Lizzieyzy/lizzie-yzy2.5.3-shaded.jar\"" \
  "$MAC_LINUX_RUNTIME_NOTE" \
  "$( [[ "$MAC_LINUX_FLAVOR" == "with-katago" ]] && echo "macos-amd64,linux-x64" )"

cat >"$STAGE_DIR/$DATE_TAG-Macosx.amd64.Linux.amd64.$MAC_LINUX_FLAVOR/start-mac-arm64.sh" <<EOF
#!/usr/bin/env bash
set -e
cd "\$(dirname "\$0")"
java -Dlizzie.next.version="$APP_DISPLAY_VERSION" -jar "Lizzieyzy/lizzie-yzy2.5.3-shaded.jar"
EOF
chmod +x "$STAGE_DIR/$DATE_TAG-Macosx.amd64.Linux.amd64.$MAC_LINUX_FLAVOR/start-mac-arm64.sh"

cat >"$STAGE_DIR/$DATE_TAG-Macosx.amd64.Linux.amd64.$MAC_LINUX_FLAVOR/start-mac-amd64.sh" <<EOF
#!/usr/bin/env bash
set -e
cd "\$(dirname "\$0")"
java -Dlizzie.next.version="$APP_DISPLAY_VERSION" -jar "Lizzieyzy/lizzie-yzy2.5.3-shaded.jar"
EOF
chmod +x "$STAGE_DIR/$DATE_TAG-Macosx.amd64.Linux.amd64.$MAC_LINUX_FLAVOR/start-mac-amd64.sh"

cat >"$STAGE_DIR/$DATE_TAG-Macosx.amd64.Linux.amd64.$MAC_LINUX_FLAVOR/Mac-CPU-Notes.txt" <<'EOF'
Mac chip support:
- Apple Silicon (arm64): use start-mac-arm64.sh
- Intel (x86_64): use start-mac-amd64.sh
- Or use start-macos-linux.sh for auto-detection.

If old native dependencies are incompatible in your environment, install a matching JDK and try Rosetta for x86_64 mode.
EOF

fi

for d in "$STAGE_DIR"/*; do
  (
    cd "$STAGE_DIR"
    zip -rq "$ROOT_DIR/$OUT_DIR/$(basename "$d").zip" "$(basename "$d")"
  )
done

LINUX_CPU_ASSET="$OUT_DIR/${DATE_TAG}-linux64.with-katago.zip"
LINUX_OPENCL_ASSET="$OUT_DIR/${DATE_TAG}-linux64.opencl.zip"
LINUX_NVIDIA_ASSET="$OUT_DIR/${DATE_TAG}-linux64.nvidia.zip"
LINUX_INSTALL_NOTE="$META_DIR/${DATE_TAG}-linux64-install.txt"
LINUX_SHA256_FILE="$META_DIR/${DATE_TAG}-linux64-sha256.txt"
if [[ -f "$LINUX_CPU_ASSET" && -f "$LINUX_OPENCL_ASSET" && -f "$LINUX_NVIDIA_ASSET" ]]; then
  write_linux_install_note "$LINUX_CPU_ASSET" "$LINUX_OPENCL_ASSET" "$LINUX_NVIDIA_ASSET"
  write_sha256_file \
    "$LINUX_SHA256_FILE" \
    "$LINUX_CPU_ASSET" \
    "$LINUX_OPENCL_ASSET" \
    "$LINUX_NVIDIA_ASSET" \
    "$LINUX_INSTALL_NOTE"
fi

echo "Built release zips:"
ls -lh "$OUT_DIR"
if [[ "$LEGACY_WINDOWS64_ZIP" != "1" ]]; then
  echo "Skipped legacy Windows x64 zip packages. Use scripts/package_windows_exe.sh for current Windows assets."
fi
if [[ "$LEGACY_WINDOWS32_ZIP" != "1" ]]; then
  echo "Skipped legacy Windows x86 zip package. Set LEGACY_WINDOWS32_ZIP=1 to build it."
fi
if [[ "$LEGACY_OTHER_SYSTEMS_ZIP" != "1" ]]; then
  echo "Skipped legacy macOS/Linux compatibility zip package. Set LEGACY_OTHER_SYSTEMS_ZIP=1 to build it."
fi

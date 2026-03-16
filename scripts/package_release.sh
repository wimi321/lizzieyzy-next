#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if ! command -v zip >/dev/null 2>&1; then
  echo "zip command not found"
  exit 1
fi

DATE_TAG="${1:-$(date +%F)}"
JAR_PATH="${2:-target/lizzie-yzy2.5.3-shaded.jar}"

if [[ ! -f "$JAR_PATH" ]]; then
  echo "Jar not found: $JAR_PATH"
  echo "Build first: mvn -DskipTests package"
  exit 1
fi

STAGE_DIR="dist/stage"
OUT_DIR="dist/release"
rm -rf "$STAGE_DIR" "$OUT_DIR"
mkdir -p "$STAGE_DIR" "$OUT_DIR"

has_default_weight() {
  [[ -f "$ROOT_DIR/weights/default.bin.gz" ]]
}

has_engine_files() {
  local platform_dir="$1"
  [[ -d "$ROOT_DIR/engines/katago/$platform_dir" ]] \
    && find "$ROOT_DIR/engines/katago/$platform_dir" -maxdepth 1 -type f | grep -q .
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

copy_bundle_engine_assets() {
  local app_dir="$1"
  local platform_dirs_csv="$2"

  if [[ -z "$platform_dirs_csv" ]] || ! has_default_weight; then
    return 0
  fi

  local copied_any="false"
  local platform_dir
  IFS=',' read -r -a platform_dirs <<<"$platform_dirs_csv"
  for platform_dir in "${platform_dirs[@]}"; do
    if has_engine_files "$platform_dir"; then
      mkdir -p "$app_dir/engines/katago"
      cp -R "$ROOT_DIR/engines/katago/$platform_dir" "$app_dir/engines/katago/"
      copied_any="true"
    fi
  done

  if [[ "$copied_any" != "true" ]]; then
    return 0
  fi

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

copy_bundle_runtime_assets() {
  local app_dir="$1"
  local platform_dir="$2"

  if [[ -z "$platform_dir" ]] || ! has_runtime_files "$platform_dir"; then
    return 0
  fi

  mkdir -p "$app_dir/runtime"
  cp -R "$ROOT_DIR/runtime/$platform_dir" "$app_dir/runtime/"
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
  cp LICENSE.txt README.md README_EN.md README_KO.md readme_cn.pdf readme_en.pdf "$app/"
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
Project: LizzieYzy Next-FoxUID
Date: $DATE_TAG
Changes:
- Fix Fox kifu sync by Fox ID using Fox H5 API.
- Fox ID-only query mode (username lookup removed).
Package:
- $bundle_note
EOF
}

WINDOWS64_FLAVOR="$(bundle_flavor windows-x64)"
WINDOWS32_FLAVOR="$(bundle_flavor windows-x86)"
LINUX64_FLAVOR="$(bundle_flavor linux-x64)"
MAC_LINUX_FLAVOR="$(bundle_flavor_multi macos-amd64 linux-x64)"
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
  if has_runtime_files linux-x64; then
    LINUX64_RUNTIME_NOTE="Bundled KataGo and Java runtime included for Linux x64."
  else
    LINUX64_RUNTIME_NOTE="Bundled KataGo included for Linux x64. Install Java 11+ separately."
  fi
else
  LINUX64_RUNTIME_NOTE="No bundled KataGo in this package."
fi

if [[ "$MAC_LINUX_FLAVOR" == "with-katago" ]]; then
  MAC_LINUX_RUNTIME_NOTE="Bundled KataGo included for macOS amd64 and Linux x64. Java is not bundled in this package."
else
  MAC_LINUX_RUNTIME_NOTE="No bundled KataGo in this package."
fi

make_bundle \
  "$DATE_TAG-windows64.$WINDOWS64_FLAVOR" \
  "start-windows64.bat" \
  "@echo off
setlocal
cd /d %~dp0
set \"JAVA_CMD=java\"
if exist \"Lizzieyzy\\runtime\\windows-x64\\bin\\java.exe\" set \"JAVA_CMD=Lizzieyzy\\runtime\\windows-x64\\bin\\java.exe\"
\"%JAVA_CMD%\" -jar \"Lizzieyzy\\lizzie-yzy2.5.3-shaded.jar\"" \
  "$WINDOWS64_RUNTIME_NOTE" \
  "$( [[ "$WINDOWS64_FLAVOR" == "with-katago" ]] && echo "windows-x64" )" \
  "windows-x64"

make_bundle \
  "$DATE_TAG-windows32.$WINDOWS32_FLAVOR" \
  "start-windows32.bat" \
  "@echo off
setlocal
cd /d %~dp0
set \"JAVA_CMD=java\"
if exist \"Lizzieyzy\\runtime\\windows-x64\\bin\\java.exe\" set \"JAVA_CMD=Lizzieyzy\\runtime\\windows-x64\\bin\\java.exe\"
\"%JAVA_CMD%\" -jar \"Lizzieyzy\\lizzie-yzy2.5.3-shaded.jar\"" \
  "$WINDOWS32_RUNTIME_NOTE" \
  "$( [[ "$WINDOWS32_FLAVOR" == "with-katago" ]] && echo "windows-x86" )"

make_bundle \
  "$DATE_TAG-linux64.$LINUX64_FLAVOR" \
  "start-linux64.sh" \
  "#!/usr/bin/env bash
set -e
cd \"\$(dirname \"\$0\")\"
JAVA_CMD=\"java\"
if [[ -x \"Lizzieyzy/runtime/linux-x64/bin/java\" ]]; then
  JAVA_CMD=\"Lizzieyzy/runtime/linux-x64/bin/java\"
fi
\"\$JAVA_CMD\" -jar \"Lizzieyzy/lizzie-yzy2.5.3-shaded.jar\"" \
  "$LINUX64_RUNTIME_NOTE" \
  "$( [[ "$LINUX64_FLAVOR" == "with-katago" ]] && echo "linux-x64" )" \
  "linux-x64"

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
java -jar \"Lizzieyzy/lizzie-yzy2.5.3-shaded.jar\"" \
  "$MAC_LINUX_RUNTIME_NOTE" \
  "$( [[ "$MAC_LINUX_FLAVOR" == "with-katago" ]] && echo "macos-amd64,linux-x64" )"

cat >"$STAGE_DIR/$DATE_TAG-Macosx.amd64.Linux.amd64.$MAC_LINUX_FLAVOR/start-mac-arm64.sh" <<'EOF'
#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
java -jar "Lizzieyzy/lizzie-yzy2.5.3-shaded.jar"
EOF
chmod +x "$STAGE_DIR/$DATE_TAG-Macosx.amd64.Linux.amd64.$MAC_LINUX_FLAVOR/start-mac-arm64.sh"

cat >"$STAGE_DIR/$DATE_TAG-Macosx.amd64.Linux.amd64.$MAC_LINUX_FLAVOR/start-mac-amd64.sh" <<'EOF'
#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
java -jar "Lizzieyzy/lizzie-yzy2.5.3-shaded.jar"
EOF
chmod +x "$STAGE_DIR/$DATE_TAG-Macosx.amd64.Linux.amd64.$MAC_LINUX_FLAVOR/start-mac-amd64.sh"

cat >"$STAGE_DIR/$DATE_TAG-Macosx.amd64.Linux.amd64.$MAC_LINUX_FLAVOR/Mac-CPU-Notes.txt" <<'EOF'
Mac chip support:
- Apple Silicon (arm64): use start-mac-arm64.sh
- Intel (x86_64): use start-mac-amd64.sh
- Or use start-macos-linux.sh for auto-detection.

If old native dependencies are incompatible in your environment, install a matching JDK and try Rosetta for x86_64 mode.
EOF

make_bundle \
  "$DATE_TAG-other-systems.without.engine" \
  "start.sh" \
  "#!/usr/bin/env bash
set -e
cd \"\$(dirname \"\$0\")\"
java -jar \"Lizzieyzy/lizzie-yzy2.5.3-shaded.jar\"" \
  "No bundled KataGo in this package." \
  ""

for d in "$STAGE_DIR"/*; do
  (
    cd "$STAGE_DIR"
    zip -rq "$ROOT_DIR/$OUT_DIR/$(basename "$d").zip" "$(basename "$d")"
  )
done

echo "Built release zips:"
ls -lh "$OUT_DIR"

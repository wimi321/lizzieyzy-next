#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

JAR_PATH="${JAR_PATH:-target/lizzie-yzy2.5.3-shaded.jar}"
OUT_DIR="${OUT_DIR:-dist/perf}"
SCENARIO="${SCENARIO:-startup}"
DURATION_SECONDS="${DURATION_SECONDS:-45}"
JAVA_CMD="${JAVA_CMD:-java}"
JFR_NAME="LizzieYzyNextBenchmark"

if [[ ! -f "$JAR_PATH" ]]; then
  echo "Jar not found: $JAR_PATH"
  echo "Build first: mvn -Dfmt.skip=true -DskipTests package"
  exit 1
fi

mkdir -p "$OUT_DIR"
timestamp="$(date -u '+%Y%m%dT%H%M%SZ')"
jfr_file="$OUT_DIR/${timestamp}-${SCENARIO}.jfr"
log_file="$OUT_DIR/${timestamp}-${SCENARIO}.log"
summary_file="$OUT_DIR/${timestamp}-${SCENARIO}.summary.txt"

cat >"$summary_file" <<EOF
LizzieYzy Next JFR benchmark
============================

Scenario: $SCENARIO
Duration seconds: $DURATION_SECONDS
Jar: $JAR_PATH
JFR: $jfr_file
Log: $log_file

This script starts the desktop app with Java Flight Recorder enabled, waits for
the configured duration, dumps the recording, then terminates the process.
For richer profiling, interact with the app during the capture window:
- startup: wait until the main window appears
- settings: open comprehensive settings and switch tabs
- sgf-load: load a representative SGF
- strength: open 棋力评估
- browser: open a JCEF-backed kifu/live page
EOF

start_ms="$(python3 - <<'PY'
import time
print(int(time.time() * 1000))
PY
)"

"$JAVA_CMD" \
  -XX:StartFlightRecording=name="$JFR_NAME",settings=profile,dumponexit=true,filename="$jfr_file" \
  -Dlizzie.next.version="perf-$SCENARIO" \
  -jar "$JAR_PATH" \
  >"$log_file" 2>&1 &
app_pid=$!

sleep "$DURATION_SECONDS"

if command -v jcmd >/dev/null 2>&1 && kill -0 "$app_pid" >/dev/null 2>&1; then
  jcmd "$app_pid" JFR.dump name="$JFR_NAME" filename="$jfr_file" >>"$log_file" 2>&1 || true
fi

if kill -0 "$app_pid" >/dev/null 2>&1; then
  kill "$app_pid" >/dev/null 2>&1 || true
  sleep 2
fi
if kill -0 "$app_pid" >/dev/null 2>&1; then
  kill -9 "$app_pid" >/dev/null 2>&1 || true
fi
wait "$app_pid" >/dev/null 2>&1 || true

end_ms="$(python3 - <<'PY'
import time
print(int(time.time() * 1000))
PY
)"

{
  echo
  echo "Result"
  echo "------"
  echo "Elapsed milliseconds: $((end_ms - start_ms))"
  if [[ -f "$jfr_file" ]]; then
    echo "JFR size: $(du -h "$jfr_file" | awk '{print $1}')"
  else
    echo "JFR size: missing"
  fi
  echo "Log tail:"
  tail -n 40 "$log_file" || true
} >>"$summary_file"

echo "JFR benchmark artifacts:"
ls -lh "$jfr_file" "$log_file" "$summary_file" 2>/dev/null || true

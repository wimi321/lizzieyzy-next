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
analysis_file="$OUT_DIR/${timestamp}-${SCENARIO}.analysis.md"
analysis_json="$OUT_DIR/${timestamp}-${SCENARIO}.analysis.json"
metrics_json="$OUT_DIR/${timestamp}-${SCENARIO}.metrics.json"
peak_rss_file="$OUT_DIR/${timestamp}-${SCENARIO}.peak-rss-kib"
read -r -a extra_java_options <<< "${JAVA_OPTIONS:-}"

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
  "${extra_java_options[@]}" \
  -XX:StartFlightRecording=name="$JFR_NAME",settings=profile,dumponexit=true,filename="$jfr_file" \
  -Dlizzie.next.version="perf-$SCENARIO" \
  -jar "$JAR_PATH" \
  >"$log_file" 2>&1 &
app_pid=$!

(
  peak=0
  while kill -0 "$app_pid" >/dev/null 2>&1; do
    rss="$(ps -o rss= -p "$app_pid" 2>/dev/null | tr -d ' ' || true)"
    if [[ "$rss" =~ ^[0-9]+$ ]] && (( rss > peak )); then
      peak="$rss"
    fi
    sleep 0.2
  done
  printf '%s\n' "$peak" >"$peak_rss_file"
) &
rss_monitor_pid=$!

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
wait "$rss_monitor_pid" >/dev/null 2>&1 || true

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

if command -v jfr >/dev/null 2>&1 && [[ -f "$jfr_file" ]]; then
  python3 scripts/summarize_jfr.py \
    --jfr "$jfr_file" \
    --output "$analysis_file" \
    --json-output "$analysis_json" || true
fi

python3 - \
  "$metrics_json" \
  "$SCENARIO" \
  "$DURATION_SECONDS" \
  "$JAR_PATH" \
  "$jfr_file" \
  "$peak_rss_file" \
  "$((end_ms - start_ms))" <<'PY'
import json
import pathlib
import sys

output, scenario, duration, jar_path, jfr_path, peak_path, elapsed = sys.argv[1:]
jar = pathlib.Path(jar_path)
jfr = pathlib.Path(jfr_path)
peak_file = pathlib.Path(peak_path)
payload = {
    "schemaVersion": 1,
    "scenario": scenario,
    "durationSeconds": int(duration),
    "elapsedMilliseconds": int(elapsed),
    "jarSizeBytes": jar.stat().st_size if jar.exists() else 0,
    "jfrSizeBytes": jfr.stat().st_size if jfr.exists() else 0,
    "peakResidentMemoryKiB": int(peak_file.read_text().strip() or 0) if peak_file.exists() else 0,
}
pathlib.Path(output).write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
PY
rm -f "$peak_rss_file"

echo "JFR benchmark artifacts:"
ls -lh "$jfr_file" "$log_file" "$summary_file" "$analysis_file" "$metrics_json" 2>/dev/null || true

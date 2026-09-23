# Read-only measurement summary and schema export

`scripts/summarize_measured_analysis.py` reads evidence created by
`scripts/measure_analysis.py` from the measurement PR. It uses Python 3.11 or
later, has no third-party dependencies, does not launch subprocesses, and never
reads or modifies the user's current engine settings. It is separate from the
Java importer and cannot apply a tuning profile.

```powershell
python scripts/summarize_measured_analysis.py C:\evidence\clean-run `
  --markdown C:\reports\comparison.md `
  --export-dir C:\reports\import-reports `
  --note "Reviewed probe revision and controlled workload; see the original run logs."
```

Both output targets must be new and outside the evidence directory. The summary
is printed to stdout when `--markdown` is omitted. JSON exports are opt-in and
include a `README.md` carrying the evidence limitations. `--baseline` and
`--candidate` select profile names; these are mapped to the schema's fixed names
without changing execution order.

For known-invalid or exploratory data, do not create import files:

```powershell
python scripts/summarize_measured_analysis.py C:\evidence\old-run `
  --invalid-reason "Known asynchronous fixture restore timing defect" `
  --note "Parser validation only; this is not recommendation evidence."
```

`--invalid-reason` actively suppresses every JSON export. Combining it with
`--export-dir` returns failure and creates no export directory. A measured timing
advantage cannot override a known probe defect. The initial `app-final-opening`
dataset was found to have such a restore-timing defect and must not be used to
produce a recommendation; it was only used to check the parser's read path.

## Evidence and failure behavior

The tool reads the actual `manifest.json`, ordered `results.json`, and each
run's `result.json`, `app-result.json`, `command.json`, and `gpu.jsonl`. The GPU
file is JSON Lines containing NVIDIA CSV strings, not a standalone CSV file.
The application probe's `effectiveCommandArgs` is the argv observed from the
running OS process, rather than the requested command; this array supplies
effective tuning values and non-concurrency semantics. If the OS cannot return
argv (an absent or empty array), the tool falls back to `effectiveCommand`, which
must be the observed OS command line, not an application-side request string.
Malformed nonempty arrays do not fall back silently.

Exports require `measurementContractVersion: 2`, the probe contract with
confirmed fixture restoration and bounded response measurements. Legacy samples
without this contract remain readable in the summary tables but cannot produce
import JSON, even if `--invalid-reason` is omitted. This supplements, rather than
replaces, independent review of the actual probe revision and run logs.

Round 0 is retained in the cold table and excluded from all tuning exports.
Warm exports require complete, consecutive, alternating 3- or 5-round pairs.
The original array order and telemetry order are checked, never sorted into a
more favorable comparison. Manifest hashes, original/corrected command asset
paths, archived config hash, per-run raw results, GPU identity, and non-tuning
semantics must agree. Duplicate JSON keys, missing latency observations, partial
GPU records, changed identities, overlapping process windows, and unknown
command flags prevent export. Bare-engine measurements cannot become an
application report and their cancellation metric is not compared with the
application's worker-process-exit cancellation.

The tool exports all recorded visits, including an under-budget result; the
numerical assessment then rejects it instead of silently dropping it. It also
retains slow pairs and isolated latency spikes. A complete rejected report is
still useful evidence and is explicitly marked `REJECT` in its accompanying
summary. Numeric gates mirror the documented Java report gates; the application
validator and fresh fingerprint verification remain authoritative.

The primary config's archived bytes establish its historical identity. This
runner does not archive recursively included configs or additional `-config`
files. Such inputs therefore fail export instead of reconstructing their
historical hashes from today's potentially changed files. Multi-GPU telemetry
also fails export because the current runner does not record a device UUID that
would identify the measured GPU unambiguously.

## Metric scope

- `seconds` is fixed-budget measured search time, not startup. Live throughput
  uses observed root visits, so report both completion time and actual visits;
  polling/streaming may overshoot the fixed minimum visit budget.
- EDT p95 is the nearest-rank 95th percentile of that run's recorded measured
  search samples. The summary exposes both median and maximum run p95 values.
- Live response is `pauseAckSeconds`. Whole-game response is
  `cancelCompleteSeconds` with `cancellationMethod=production-worker-process-exit`.
  It is not the bare engine's JSON termination acknowledgment.
- GPU peak is the maximum sampled total-device memory over the entire process
  lifetime, including startup, warm-up, search and cancellation. It is not a
  continuously observed or per-process peak, nor a search-window-only figure.
  The summary includes sample count/gaps and utilization to make that visible.
- Engine count is the maximum visible KataGo process count in NVIDIA telemetry.
  Zero at startup/shutdown is normal. Permission-hidden processes are unknown,
  not inferred KataGo processes. Independent process/workload control is still
  necessary, and sub-sample concurrent work can be missed.
- Startup is reported separately. The current whole-game probe records initial
  application startup before loading the analysis worker; its startup value is
  not a total cold engine readiness measurement.

Command fingerprints exclude only the four managed concurrency/batch keys and
the output locations `homeDataDir`, `logDir`, `logFile`, `logDirDated`. They retain
visit/time limits, PV length, numerical precision, search policies and logging
behavior. A benchmark command with caps or `analysisPVLen=100` may intentionally
not match the user's daily command. This tool does not rewrite that command or
weaken fingerprint matching to make a report applicable.

Run the synthetic tests without Java, a desktop or a GPU:

```text
python -B -m unittest discover -s scripts -p test_summarize_measured_analysis.py -v
```

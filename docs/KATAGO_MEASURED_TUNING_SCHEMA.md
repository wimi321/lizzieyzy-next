# Measured tuning report, version 1

This is an import format for the existing KataGo performance page, not an engine
command or an instruction to apply settings. Imports are reviewed before a
separate explicit Apply confirmation. Reports are scoped to one scene and one
saved engine. Cold runs remain in the measurement evidence and are excluded from
the `runs` array below.

```json
{
  "schemaVersion": 1,
  "scene": "live",
  "measurementMode": "application",
  "fingerprint": {
    "engineSha256": "64 lowercase hex digits",
    "modelSha256": "64 lowercase hex digits",
    "configSha256": "64 lowercase hex digits",
    "configIncludes": [],
    "gpuName": "NVIDIA GeForce RTX 5090",
    "driverVersion": "591.86",
    "memoryMiB": 32607,
    "commandSemantics": {}
  },
  "fixtureSha256": "64 lowercase hex digits",
  "budget": 5000,
  "positions": 1,
  "metricScope": "totalGpu",
  "baselineParameters": {"numSearchThreads": 8, "nnMaxBatchSize": 16},
  "candidateParameters": {"numSearchThreads": 16, "nnMaxBatchSize": 32},
  "runs": [
    {
      "profile": "baseline",
      "round": 1,
      "phase": "warm",
      "seconds": 4.5,
      "firstResultSeconds": 0.15,
      "responseSeconds": 0.08,
      "edtP95Seconds": 0.012,
      "maxMemoryMiB": 4500,
      "maxEngineProcesses": 1,
      "observedRootVisits": 5010
    }
  ]
}
```

The sample values illustrate the schema only and are not benchmark results or a
valid recommendation. A complete report contains exactly 3 or 5 independent
warm rounds for both `baseline` and `candidate`, with one row per pair member.
The input runner alternates order between consecutive rounds. The report array
retains that execution order. All numeric metrics must be finite and positive
(zero event/response latency is permitted). Extra candidate parameter keys and
unsupported schema versions are rejected.

For `scene: "whole-game"`, both parameter maps contain exactly
`numAnalysisThreads`, `numSearchThreadsPerAnalysisThread`, and `nnMaxBatchSize`.
`positions` is the measured number of positions. Each run supplies
`rootVisitsByTurn` as an array of that length instead of `observedRootVisits`;
every position must reach `budget`. `firstResultSeconds` is omitted. In the live
scene `responseSeconds` is pause acknowledgment latency; in the whole-game scene
it is cancellation acknowledgment latency. `seconds` is fixed-budget completion
time for the application, and `edtP95Seconds` is measured application event-loop
latency. Engine-only benchmark output cannot substitute for these application
measurements.

`maxMemoryMiB` measures peak total GPU memory in MiB (`metricScope: "totalGpu"`),
not per-process VRAM. The machine, driver, background workload and engine-process
count must be controlled; each accepted run records exactly one KataGo process.
`configIncludes` contains ordered SHA-256 hashes of additional command-line
configuration files and recursively included files. An unavailable or unsupported
include is a fingerprint failure, not permission to skip validation.

`commandSemantics` contains the final effective non-tuning inline `override-config`
settings, including existing launch policies. Visit/time limits, numerical
precision, search parameters and `analysisPVLen` remain part of the comparison.
Managed concurrency keys and output locations (`homeDataDir`, `logDir`, `logFile`,
`logDirDated`) are excluded, since each evidence run has its own output directory.
Logging behavior such as `logToStderr` and `logSearchInfo` remains bound. No search
parameter is normalized out. Values are only compared to the current command;
nothing in this object is executed or copied into a launch command. The engine, model and config
are resolved from the saved local command, never from a report-provided path.

Accepted parameters are added as a scene-specific launch overlay. The saved
command remains unchanged. Whole-game overlays do not affect quick analysis or
HumanSL. Restore removes the accepted overlay and retains the original command,
including explicit user overrides. Changed assets, hardware, driver or commands
invalidate the recommendation; the existing defaults remain available.
Whole-game reports measure an independent local analysis process: both a separate
SSH analysis configuration and `analysisReuseCurrentEngine` are unsupported.
These mode checks run at review, apply and process launch, including after slow
fingerprint verification. They do not disable local live-scene reports.

## Acceptance thresholds

Every candidate pair must complete faster than its control, and the median
paired speed ratio must be at least 1.03. For three rounds, a timing coefficient
of variation over 10% requires five rounds; five-round variation over 15% is
rejected. For first result, pause/cancel and event-loop latency, the candidate
median must not exceed control median × 1.10 + 5 ms. The corresponding maximum
must not exceed control maximum × 1.25 + 20 ms, so an isolated large stall cannot
be hidden by the median. Peak total GPU memory must stay within control peak ×
1.15 + 256 MiB and leave at least 10% physical GPU memory free.

These are conservative admission rules, not guarantees of a particular speedup
on other positions or machines. The original commands, model, visit budgets,
precision, search settings and complete analysis output remain under their
existing policies. A report with benchmark-only caps or a different PV length
does not match a daily command that lacks those settings.

## Application and validation

Open performance settings from the saved engine entry, then import a report.
The report is checked on a background worker and displayed with the scene,
parameter comparison and paired speed improvement. Applying requires a separate
confirmation; restore removes both accepted scene overlays while retaining any
subsequent manual command edits. The old Apple tuning profile is stored under
its original key and is not migrated or deleted.
Closing or hiding the performance window invalidates its pending operation and
cancels its worker. Late confirmations, errors and success popups are suppressed,
including when the same window is shown again before the old worker completes.

No model hashing occurs in rendering or on the event thread. Fresh content and
hardware verification occurs only when reviewing, confirming or starting an
opted-in process, never for individual GTP commands. A launch invoked on the
event thread cannot apply an unverified overlay and retains the existing policy.
Cold-start cost of verification must be reported separately from search gains.

Focused headless validation: 188 tests, no failures or errors; 10 existing
Linux-only persistence/benchmark cases were skipped on Windows. This includes
report validation, whole/live separation, atomic settings persistence failure,
stale-review guards, same-size/timestamp model replacement, recursive config
includes, explicit search-parameter binding, legacy profile and layout tests.
Actual measurement artifacts and desktop acceptance are supplied by the
integration report; the numeric schema example above is not measured evidence.

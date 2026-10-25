# Windows Zhizi whole-game overview timing

The manual whole-game overview completes faster in this bounded live-service
comparison. Three repetitions per build reduced median completion time from
13.960 to 12.023 seconds (13.9%); a subsequent fresh-session baseline/candidate
pair measured 14.185 and 12.014 seconds (15.3%). These are application workflow
measurements for eight positions, not an engine throughput benchmark or proof
of local/cloud speed parity.

## Scope and preserved behavior

The change applies to the selected built-in Zhizi connection, a manual full
mainline overview, and a target of at most 500 visits. A legal next `play`
interrupts the preceding continuous analysis and advances the engine in one
request. Both the old analysis terminator and the matching successful play
response must arrive before publishing the old result or starting the next
search. Final positions and commands requiring a board reset retain `stop`.

Initial move replay uses the existing bounded window of eight commands. The
requested report interval is capped at 100 ms, preserving a shorter user value.
The command remains `kata-analyze`: no model, search parameters, requested visits,
candidate count, PV length or precision is changed. Higher budgets, partial
analysis, other engine types and existing automatic finite searches keep their
previous paths. Cancellation, failed/missing responses and stale game/engine
contexts still abort and restore through the foreground lease.

[KataGo's GTP loop](https://github.com/lightvector/KataGo/blob/master/cpp/command/gtp.cpp)
terminates continuous analysis before executing the next input command. This is
the basis for combining stop and advance, rather than replacing analysis with a
different kind of search.

## Reproduction and identities

- Windows 11, JDK 21.0.12, Maven 3.9.10; an isolated application profile per session.
- Baseline source: `1507765a2e1a9a52123cf99d37161e70b68f4ed2` (PR #711).
- Baseline shaded JAR SHA-256:
  `60ba596431128d354293c9177a833c6d8bd8f28fe196a9ab86babed4b830809c`.
- Candidate working-tree JAR SHA-256:
  `97b4c7a2db42c4624a66f090e5fc995dd85432eced13507d8c6f95cbf1ecb6c5`.
- Eight-move fixture SHA-256:
  `37193347797a3f6e712cc40bc759b09367891ebe20b2b1efdb626a2719ce6196`.
- Identical 19x19 Chinese rules, 7.5 komi, 500-visit completion threshold, one
  built-in VIP primary, configured model `10b512t`, TensorRT. Remote GPU, driver,
  model-file hash and server load were not independently observable.
- The same compiled `LiveZhiziAnalysisProbe` ran against each preserved JAR;
  current production `target/classes` was excluded from the baseline classpath.
  Production `flashAnalyzeGame(true, false)` was invoked after import without a
  test-only wait for engine synchronization.
- Session order: baseline three repetitions, candidate three repetitions,
  fresh baseline once, fresh candidate once. First repetitions use a fresh
  connection; later repetitions reuse it and may retain tree/cache state.
  This is a bounded comparison, not three fully alternated cold/warm pairs.

Run the [live probe](zhizi-analysis-live-probe-20261025.md) in `flash` mode with
eight positions and 500 visits. Credentials must enter only through its hidden
console prompts. The four sessions used existing authorized VIP access, no
purchase or metered allocation, and finished within 147 seconds in total
including startup and cleanup. Hard limits were 90, 60, 35 and 35 seconds;
additional sessions were started only after the preceding actual duration was
known to fit the overall three-minute allowance.

## Results

| Session/repetition | Baseline completion (ms) | Candidate completion (ms) | Baseline actual visits min-max | Candidate actual visits min-max |
| --- | ---: | ---: | --- | --- |
| First connection, run 1 | 14970 | 12607 | 922-1979 | 880-1845 |
| Same connection, run 2 | 13960 | 11973 | 1820-4123 | 1542-4099 |
| Same connection, run 3 | 13911 | 12023 | 2524-6162 | 2286-5262 |
| Fresh-session repeat | 14185 | 12014 | 790-1841 | 768-1715 |

Every run completed all eight positions, preserved the selected node, returned
the foreground engine to analysis with increasing visits, closed all probe-owned
cloud transports and passed its credential scan. Actual visits exceed the
configured threshold because continuous search, retained trees and communication
delay remain; the builds were not forced to perform exactly equal work.

Client trace timestamps explain the gain. Each candidate run replaces seven
separate stop/advance exchanges with seven combined boundary plays. In the
initial three runs, analysis-to-final-boundary time fell from about 7.9-8.5 s to
5.9-6.5 s. Analysis response-header times remained around 296-299 ms, and combined
advance responses around 314 ms. The timestamps include service processing and
transport; they are not pure network RTT or GPU compute time.

Observed search-to-switch-decision time and the number of received `info` lines
were essentially unchanged. The measured gain is principally from fewer serial
requests; no independent benefit from the shorter requested report interval is
claimed. Entering analysis still takes roughly 5.4 s in warm repetitions, and
each position still waits for remote responses. A local B11 model is not an
equivalent comparator to this cloud model. No local-speed or long-game speedup
percentage is inferred from these short sessions.

Evidence is retained in the tester's ignored `.cache/zhizi-speed-20261025/`
archive: `live-before`, `live-after`, `live-before-repeat`, `live-after-repeat`,
their `result.txt` and `metrics.jsonl`, and safe aggregate timing summaries.
Raw provider logs and credentials are not part of this report.

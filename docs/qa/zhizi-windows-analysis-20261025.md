# Windows built-in Zhizi analysis regression acceptance

Candidate 04 passes the final Windows Java gate, the eight-case local CUDA
protocol suite, the 24-move live Zhizi workflow, and a separate native
mouse-driven overview check. The failed intermediate runs below remain part of
the evidence; their results were not
overwritten by later passes. No repeatable throughput improvement is claimed.

## Scope and artifact identity

The released `next-2026-10-24.2` build fails when the selected engine is built-in
Zhizi and the user starts **Lightning analysis → Whole-game overview**. The
manual analysis path chooses the configured local analysis command instead of
the active cloud engine. This is distinct from legacy step-by-step automatic
analysis, automatic curve completion after SGF import, and whole-game deep
analysis.

Testing used an isolated Windows 11 profile, JDK 21.0.12 and Maven 3.9.10. The
live probe used an explicitly authorized test account with existing VIP access
and model `10b512t`; it did not purchase a plan or use metered allocation.
Credentials are not included in this report.

| Artifact | Identity |
| --- | --- |
| Released source | `dd2b9a989d7a460abb04c4ca1c33c3a550eb7f9e` |
| Released shaded JAR SHA-256 | `c4cecdb1ba875ff9269e7e064499f06510efe1b2944a6ab8c720380f6366b525` |
| Candidate 01 shaded JAR SHA-256 | `363be3cb3123d348637c15456241acb742e8d0c860d83cb05c4b00af89de5c79` |
| Candidate 02 shaded JAR SHA-256 | `5c867d6d949505e4cb24b427cc2fef72f121eb5f99e2e268330976ccb22baf52` |
| Candidate 03 shaded JAR SHA-256 | `c42d04e633e4c4ede834f08543f77d1312b03d77ddace3da9c8ddff78ea8e066` |
| Candidate 04 shaded JAR SHA-256 | `b734ca90b7e2126b464e17167c6a067af4551d40e0d7d569e583e3d4e18dc37d` |
| Eight-move SGF fixture SHA-256 | `37193347797a3f6e712cc40bc759b09367891ebe20b2b1efdb626a2719ce6196` |
| Twenty-four-move SGF fixture SHA-256 | `6dc1457ffb27bdd9bf5da83fdca638a9d16c8f190d175820d9107396e4393f5e` |

Candidates 01–04 were built from the evolving worktree on the released source.
Their results are tied to the JAR hashes above, not to a later commit or rebuilt
artifact. Local evidence identifiers below are relative to the tester's
`.cache/zhizi-qa-20261025/` archive; raw provider logs are not published.

## Live cloud results

[LiveZhiziAnalysisProbe](../../src/test/java/featurecat/lizzie/gui/LiveZhiziAnalysisProbe.java)
creates the production Swing application and invokes its production actions on
the event thread. The scripted runs used the real Zhizi service, not a protocol
loopback. Native mouse coverage was checked separately, as described below;
the scripted runs alone do not prove native input or file-dialog behavior.

### Final Candidate 04 live acceptance

| Scenario | Verified result |
| --- | --- |
| Manual whole-game overview, 24 positions, 500-visit target | PASS, 33,914 ms |
| Legacy automatic analysis, root plus 24 moves, 500-visit target | PASS, 21,210 ms |
| Automatic import curve, 24 positions, production 16-visit target | PASS, 20,776 ms |
| Foreground resumes and gains visits after overview and curve handback | PASS |
| Pause during curve, then explicit manual resume and foreground progress | PASS; recorded pause restoration 18 ms |
| Disconnect/reconnect followed by foreground progress | PASS; recorded recovery 7,401 ms |
| All probe-owned cloud transports closed; credential scan | PASS |
| Overall live workflow | PASS |

Evidence: `live-fixed-04/result.txt`, using the Candidate 04 hash above, the
24-move fixture, existing VIP allocation and a 240-second hard limit. Immediate
overview activation after the import callback remains in this test; it was not
replaced with a test-only wait for engine synchronization. The legacy workflow
analyzes the root as well as all 24 moves. These are single workflow timings,
including transport and setup; they are not a controlled engine-throughput
comparison and do not establish a speedup over a different fixture or run.

### Native Windows overview acceptance

In `live-native-01`, Computer Use clicked the actual **Analysis → Whole-game
overview (Lightning)** menu entry in the Windows application connected to live
Zhizi. The operator inspected both the menu and the analysis progress. The
eight-position fixture completed at the existing 500-visit target, then
foreground analysis resumed and gained visits. Existing current-node analysis
was retained. The overall run, transport closure and credential scan passed.

The recorded 10,274 ms starts when the probe first observes the active request
and excludes operator waiting. It is not click-to-first-result latency or an
independent throughput benchmark. This run verifies native mouse activation of
the overview menu; it does not claim native keyboard or file-dialog coverage,
or native clicking of every scripted automatic-analysis/pause/reconnect action.
Evidence: `live-native-01/result.txt` and the Computer Use observation in the
integration session. The hard limit was 120 seconds.

### Released build and initial eight-move comparison

| Scenario | Released build | Candidate 01 |
| --- | --- | --- |
| Manual whole-game overview, 8 positions, 500 visits each | Fails at startup | PASS, 12,234 ms |
| Legacy automatic analysis, root plus 8 moves, 500 visits each | Completes, 11,088 ms | PASS, 8,061 ms |
| Automatic import curve, 8 positions, production 16-visit target | Completes, 11,182 ms | PASS, 10,454 ms |
| Foreground analysis resumes and gains visits after handback | PASS | PASS |
| Pause during curve completion, then manual resume | Not run | PASS; recorded pause restoration 19 ms |
| Disconnect and reconnect, then foreground progress | Not run | PASS; recorded recovery 7,372 ms |
| All probe-owned cloud transports closed | PASS | PASS |
| Credential scan | FAIL; see privacy finding below | PASS |
| Overall run | FAIL | PASS |

Evidence: `live-baseline-01/result.txt` and `live-fixed-01/result.txt`. The baseline
had a 180-second run limit; Candidate 01 had a 240-second limit. Each timing is
one observation, including workflow overhead. Differences between the two runs
are not a reproducible speed improvement and must not be presented as one.
The overview and automatic-analysis budgets were not reduced. The import curve
retained its separate existing 16-visit budget.

### Longer Candidate 02 run: failed handback

Candidate 02's 24-move comparison run completed all 24 overview positions at
the same 500-visit target in 32,259 ms. It then failed waiting for foreground
analysis to resume. After cleanup and a fresh import, legacy automatic analysis
also failed to start. The overall result is **FAIL**; complete overview results
alone do not establish workflow acceptance. All probe-owned cloud transports
closed and the credential scan passed. The run limit was 150 seconds.

The trace shows that the overview acquired the foreground lease while imported
board synchronization was still pending. At 01:46:20.238, that synchronization
reported `board synchronization response timeout`. The final overview result
arrived at 01:46:43.903; the final lease-stop ACK arrived at 01:46:44.214, but no
foreground restoration command followed before cleanup. This exposed the
import-synchronization/lease overlap subsequently fixed and covered by
deterministic regressions. It does not show a missing final-stop response from
the service.

Evidence: `live-fixed-02/result.txt`, its post-login `logs/engine-trace.log`, and
the sanitized synchronization error in `logs/app.log`. The probe invokes the
manual action after the import callback; it does not wait for the separate
engine-synchronization completion. Candidate 04's successful retest preserves
that immediate-action path.

### Candidate 03: synchronization restored, resume intent still failed

Candidate 03's 24-move run again completed every overview position at 500 visits,
in 35,682 ms, then failed the foreground-handback check. The overall result is
**FAIL**. Its later automatic-analysis, import-curve, pause, and reconnect checks
were not reached. Cloud-transport closure and the credential scan passed; the
run had a 240-second limit.

This failure is different from Candidate 02. Imported-board synchronization
completed its final `name` ACK at 02:16:39.057 before the exclusive lease started
at 02:16:39.237. After the overview, the final stop was acknowledged at
02:17:11.429. Rules, board size, clear/replay commands, and the final `name`
confirmation all completed by 02:17:11.990. No foreground `kata-analyze` followed.
The service therefore completed restoration; the application did not resume
analysis.

Code inspection identified the lost intent: waiting for manual analysis
suppressed the import's normal continuation, so the lease captured
`wasPondering=false` and did not restart foreground analysis on handback. That
intent-preservation fix has passed the deterministic regressions recorded
below and the Candidate 04 live-service retest above. Evidence:
`live-fixed-03/result.txt` and its post-login `logs/engine-trace.log`.

For the original 600-second cumulative live-test cap, conservative
run-directory-creation-to-final-result spans for the baseline, Candidates 01–04
and native run are 33.117, 50.971, 84.183, 64.449, 95.134 and 54.992 seconds.
Their sum is **382.845 seconds**, rounded up to **383 seconds**, within the cap.
These bounds include initialization, operator waiting and cleanup; they are not
a provider billing measurement. All six runs report that their cloud transports
closed.

## Diagnosis of automatic-analysis latency

The fresh-profile legacy automatic-analysis default is **2 seconds per move**.
The fixed-visit measurements above explicitly disable that time condition and
the first-candidate condition. Faster remote compute does not shorten a
user-selected per-move time budget.

The released build's trace shows 9,391 ms from the first actual legacy analyze
command to the final budget result. Of this, eight incremental `play` ACK waits
total 3,200 ms and nine analyze-command header waits total 2,773 ms: together
about 64% of the active sequence. Application transitions from a completed
result to the next command total 5 ms. First accepted payloads arrive 430–442 ms
after analyze commands. These are client-observed service/transport durations;
they do not isolate pure network latency or GPU compute time.

For the import curve, lease and initial setup take 3,520 ms; eight finite
searches plus incremental replay take 5,052 ms; final-result-to-foreground-resume
takes 1,344 ms. The finite searches themselves take 316–470 ms end to end.
The evidence identifies remote command exchanges and setup/restoration as the
main overhead for this small fixture. It does not justify removing state ACK
barriers, reducing visits, or claiming a parser/UI performance defect.

Evidence: `live-baseline-01/logs/engine-trace.log` and
`live-baseline-01/timing-audit.json`.

## Deterministic regressions and local gates

Tests cover cloud selection without starting a second cloud/local engine,
explicit dedicated/SSH choices, foreground replacement, unavailable engines,
and preloaded-worker selection and cleanup. Ordinary GTP output tests verify
exact root thresholds, rootless output with symmetry exclusion, independent
first-candidate thresholds, and active/cancelled mainline, branch, and
difference-analysis completion.

The cancellation regressions failed before the fix: an already-admitted cloud
result could advance or mark analysis complete after automatic analysis was
cancelled. The handlers now recheck cancellation before those effects without
changing analysis budgets.

The post-Candidate-02 focused suite adds deterministic coverage for the import
overlap. A foreground lease remains unavailable while a captured position
restore, an outstanding board-changing response, or the restore's final
confirmation is pending. An ordinary continuous-analysis response remains
interruptible. Cancelling an undispatched restore and replacing its reader must
not leave the next engine permanently busy.

At the UI level, an immediate manual overview request waits for import
synchronization, and repeated clicks retain one pending request. Cancellation,
game replacement, engine replacement, and import failure invalidate it. Deep
analysis stays in preparation until synchronization completes; a pending request
must not silently transfer to a replacement engine. These focused regressions
passed in `focused-08`; the final artifact was subsequently checked against the
live-service failure in `live-fixed-04`.

Follow-up tests first reproduced three boundary defects: deep analysis stayed
in preparation after its engine became unavailable; the real batch-analysis
flags rejected a pending overview request; and a pending ordinary automatic
request was not cancelled when an overview request replaced it. The isolated
red run recorded exactly these three failures in eight tests, with no errors or
skips. After the boundary fixes, the affected four-suite run passed all 266
tests. These results are preserved separately as
`sync-followup-red-02/reports/TEST-*.xml` and
`sync-followup-green-01/reports/TEST-*.xml`.

The resume-intent regression run then reproduced eight failures in 15 tests:
seven overview cases lacked ownership of the successful handback callback, and
the deep-analysis success case did not consume the imported resume intent. Six
deep-analysis control cases and the corrected GMA fixture passed. Evidence:
`resume-intent-red-01/reports/TEST-*.xml`. After the fix, the corresponding
five-suite run passed 402 tests, including all 119 GMA tests.

Candidate 04 transfers the import's continuation intent to the pending shared
analysis request and consumes it only after successful foreground restoration.
The request retains the original game, engine generation and worker identity;
cancellation, failure, explicit pause, replacement, or a late callback cannot
restart the wrong analysis. Dedicated analysis remains independent of the
foreground import wait.

Follow-up red runs exposed four further boundary problems: replacement-engine
intent retargeted a pending deep-analysis request, a rules override delayed
intent transfer, dedicated overview incorrectly entered the foreground wait,
and a pending shared request silently became dedicated after a settings change.
The first run had four failures in 16 tests, and the second had one failure in
five tests. The corrected three-suite follow-up passed all 200 tests. Evidence:
`resume-followup-red-01/reports/TEST-*.xml`,
`resume-followup-red-02/reports/TEST-*.xml`, and
`resume-followup-green-01/reports/TEST-*.xml`. These results establish the tested
state transitions; final full-Java and live-service results are recorded
separately.

| Gate | Verified result | Evidence |
| --- | --- | --- |
| Focused remote/analysis/privacy suite | 511 tests, 0 failures/errors/skips | `focused-06/TEST-*.xml` |
| Initial full Windows Java gate | PASS; 538 suites, 5,658 tests, 0 failures/errors, 180 optional skips | `full-java-01/local-ci-summary.json` |
| Candidate 02 focused remote/analysis/privacy suite | 515 tests, 0 failures/errors/skips | `focused-07/TEST-*.xml` |
| Candidate 02 full Windows Java gate | PASS; 538 suites, 5,662 tests, 0 failures/errors, 180 optional skips | `full-java-02/local-ci-summary.json` |
| Post-Candidate-02 import-overlap focused suite | 16 suites, 531 tests, 0 failures/errors/skips | `focused-08/TEST-*.xml` |
| Candidate 03 boundary-fix focused suite | 4 suites, 266 tests, 0 failures/errors/skips | `sync-followup-green-01/reports/TEST-*.xml` |
| Candidate 03 repository gate | PASS; line endings, architecture budget, translations, and diff check | `repository-final-01/local-ci-summary.json` |
| Candidate 03 full Windows Java attempt | FAIL; 527 suites, 5,648 tests, 1 failure, 0 errors, 152 skips; Failsafe not reached | `full-java-final-01/local-ci-summary.json` |
| Resume-intent fix focused suite | 5 suites, 402 tests, 0 failures/errors/skips; includes 119 GMA tests | `resume-intent-green-01/reports/TEST-*.xml` |
| Candidate 04 resume-boundary focused suite | 3 suites, 200 tests, 0 failures/errors/skips | `resume-followup-green-01/reports/TEST-*.xml` |
| Candidate 04 repository gate | PASS; line endings, architecture budget, translations, and diff check | `repository-final-02/local-ci-summary.json` |
| Candidate 04 final full Windows Java gate | PASS; 538 suites, 5,702 tests, 0 failures/errors, 180 optional skips | `full-java-final-02/local-ci-summary.json` |
| Windows script gate | PASS; all 9 recorded steps passed | `windows-scripts-01/local-ci-summary.json` |
| Initial Zhizi protocol loopback with real local CUDA engine | 8 cases, 0 failures/errors/skips | `loopback-01/TEST-featurecat.lizzie.gui.ZhiziQuickAnalysisAcceptanceIT.xml` |
| Candidate 04 final Zhizi protocol loopback with real local CUDA engine | 1 suite, 8 cases, 0 failures/errors/skips | `loopback-02/TEST-featurecat.lizzie.gui.ZhiziQuickAnalysisAcceptanceIT.xml` |

The full-Java attempt's sole failure was
`LeelazReadBoardGmaTest.readBoardGmaKeepsReservationUntilBoardAndRuntimeRestoreAreAcknowledged`:
it expected `AVAILABLE` while the new synchronization check returned
`ENGINE_NOT_READY`. The fixture had acknowledged `loadsgf` without acknowledging
the earlier `clear_board`. The test now confirms that the reservation remains
blocked until that response arrives and retains its original final `AVAILABLE`
assertion. Production synchronization protection was not weakened. This
corrected test passed in the resume-intent red run, but that does not turn the
earlier full-Java attempt into a pass. Its failed verification stopped before
the Failsafe smoke stage, so those smoke tests were not executed in that run.

Both loopback runs used the local RTX 5090 CUDA KataGo engine behind a controlled
Zhizi socket endpoint. The suite covered import and handback, import while
connecting, reconnection during import, three reconnections during an active curve,
automatic-curve disabled, and preservation of an explicit pause. Its child
evidence is in the test checkout's `target/desktop-smoke/probes/zhizi-curve-*`
directories; the final run's artifacts were completed at 02:47–02:49 on October
25. This is actual local-engine protocol acceptance, separate from the live
account/service results above.

Optional headless skips do not establish desktop acceptance. Final desktop
evidence is the separate native overview check above. Earlier gate results
remain associated with the source state tested at that time.

## Privacy finding

The released provider startup diagnostics wrote an account identifier into the
baseline application log. The baseline credential scan therefore failed. The
identifier was redacted locally, and the original failed verdict remains in
`live-baseline-01/result.txt`; it was not changed to PASS. The redaction record
is `baseline-log-redaction.txt`. No raw account log is included in this report.
Candidates 01–04 and the native run passed their credential scans after
diagnostics sanitization. The integration scan of 25 changed/new files also
passed; test-account credentials were not included in the changes.

## Acceptance limits and delivery record

The recorded Candidate 04 local acceptance passed at the original analysis
budgets. Live coverage used the existing VIP `10b512t` service and the two stated
fixtures; it does not establish every remote plan, model or board size. Deep
analysis routing and ownership are covered by deterministic tests, not a separate
live deep-analysis benchmark. Native input coverage is limited to the overview
menu flow described above. No driver, system-power, search-precision or
visit-budget reduction forms part of the fix.

GitHub CI, the reviewed PR and any merge revision are recorded in the subsequent
PR state. They are not claimed as completed by this local report.

# Windows PR Acceptance, October 1, 2026

## Candidate And Scope

- Baseline: `8c43fdd24b01acd6485b8b3911708290ae18ab75`.
- Combined product tree: `c956c727c168e5694157b807fc7187ca111fbaf3`.
- Repaired product tree: `17fe30c1c049f35bf5eaeeab4f70a57221139ddc`.
- PRs: #569, #570, #571, #572, #574 and the #569 follow-up #576.
- Integration PR: [#580](https://github.com/wimi321/lizzieyzy-next/pull/580).
- Draft #575 is excluded. Its real-account ChatGPT authorization, inference,
  restart/logout and cross-platform credential acceptance remain separate gates.

The original dirty checkout and real user data were not modified. Testing uses
an isolated managed worktree and a copied portable package with fresh user data.
The six PR histories are retained, including contributor attribution.

## Environment

- Windows 11 Pro, 10.0.22631; native desktop testing, not Xvfb.
- NVIDIA GeForce RTX 3070 Laptop GPU, 8 GiB; driver 560.76.
- Build tools: JDK 21.0.2, Maven 3.9.6, Python 3.12.
- Candidate launcher: `LizzieYzy Next NVIDIA.exe`; bundled Java 21.0.12.1.
- KataGo v1.18.2, pinned source `47aadc08518b3e121f22539796c911002f699584`.
- CUDA engine SHA-256:
  `9a87f2e40233bb5694332546f9cad0a6248f4593341ccafb29225fbf025a6ef6`.
- B11: `kata1-tf3-b11c768-s12002M-d6304M.bin.gz`, 262017809 bytes;
  SHA-256 `4a6312e80faadee7b7dd28689a2e87a1efb4640c10132f16290da7a17b4c6d9e`.
- Bundled readboard v3.0.6; no download or Java fallback was introduced.

## Automated Verification

| Gate | Actual result |
| --- | --- |
| Final `scripts/run_local_ci.ps1 -Profile All -Group All -RequireClean` | PASS on `17fe30c1`, 65/65 steps, 609.1 seconds |
| Full Maven verification inside that final gate | 4587 combined JUnit tests; 0 failures, 0 errors; 107 conditional skips |
| Earlier combined-tree full verification | PASS, 65/65 steps, 616.5 seconds; 4574 unit tests and 7 integration tests; 0 failures/errors, 106 conditional skips |
| Packaged logging integration | `LoggingProviderSmokeIT` executed successfully against the shaded JAR |
| Windows credential persistence | 40 tests, 0 failures/errors/skips |
| Windows product acceptance fixtures | 29 tests, 0 failures/errors |
| `windows_smoke_test.ps1 -LauncherOnly` on isolated EXE | PASS; bundled JVM remained healthy |
| B11 notice, panel, Fox rank and variation publication focused tests | 29 tests, 0 failures/errors/skips |
| Real CUDA `QuickAnalysisAcceptanceIT` and `MoveFocusNativeAcceptanceIT` | 4 tests, 0 failures/errors/skips; real B11 inference, positive visits |
| Desktop group with explicit ASCII input precondition | 10 tests, 0 failures/errors/skips |
| Native engine-process group | 7 tests, 0 failures/errors/skips; real processes with controlled protocol fixtures |
| New startup cancellation regression tests | 5 tests, 0 failures/errors/skips |
| Quick analysis real CUDA rerun after startup fix | 4 tests, 0 failures/errors/skips, including the new process-startup SGF case |

The full gate includes packaging, launcher, JCEF, NVIDIA runtime, release
provenance/topology, line endings, Markdown, shell/PowerShell parsing and
`git diff --check`. Conditional skips are not counted as executed hardware tests.

The initial desktop run had two failures: physical ASCII keystrokes were
composed by the active Pinyin IME (`winr` became `win'r`). The existing
`LIZZIE_TEST_ASCII_IME=shift` opt-in test precondition was then used. Both full
English and Chinese search-input chains passed without changing production
input handling, weakening assertions or excluding either test. The initial
failed log is retained alongside the successful rerun.

## Visible And Real-Engine Probes

The B11 notice probe renders Simplified Chinese, Traditional Chinese, Hong Kong
Chinese compatibility, English, Japanese, Korean and Thai at JVM UI scales
1.0, 1.25, 1.5 and 2.0. These are JVM scale simulations, not changes to Windows
display scaling. Its model metadata and benchmark numbers are synthetic
fixtures and are not performance measurements.

The variation-publication probe checks accepted/stale images at 1.0 and 2.0
with visible Swing windows and pixel assertions. The real CUDA probes cover
foreground quick-analysis completion, preserving an explicit pause, ordinary
batch/retry and native multi-point focus (217 positive root visits observed).

Evidence root on the test machine:
`C:\ailearn3\lizzieyzynext\.qa\pr-acceptance-20261001`.
Structured summaries are under the isolated worktree's
`target/qa-20261001`; visible probe screenshots and result files are under
`target/desktop-smoke/probes`. No account credentials are included.

## Defect Found During EXE Acceptance

Launching the EXE with an SGF path displayed the game and current-position
analysis, but did not start the automatic whole-game curve. Manually selecting
the lightning overview completed normally. A new real-engine test reproduced
the failure on the combined pre-fix tree (1 failure, timeout waiting for the
startup game's curve and foreground handback).

The import captured primary/reader identity while initial engine startup was
still replacing it. The request correctly retired its obsolete identity, but
no continuation survived. `LizzieFrame` now waits through the existing sync
coordinator under the same engine-switch token, history and imported rules,
then captures the settled identities for the normal confirmed restore. It
does not weaken reader-generation fences or clear a later user pause.

The real-engine regression passed after the fix. Five additional headless
tests cover startup failure, replacement engine, replacement game, changed
rules and shutdown. Their continuations cannot resume an obsolete game.

Actual EXE observations after the repair:

- Startup with the 50-move test SGF completed its curve automatically without
  pressing lightning analysis; the current-position CUDA visits kept growing.
- First-launch configuration saved default engine index 0 and
  `first-time-load=false`; no first-run setup dialog appeared.
- Alt+O opened bundled readboard v3.0.6 visibly; this does not claim a live
  third-party GMA match was played on this machine.
- The native Fox window queried public Ke Jie games and displayed P9 for him
  on both sides, alongside other professional ranks such as P6/P7/P8.
- Opening a 205-move public Fox game populated the automatic curve and
  continued foreground analysis. Three consecutive recommended-point clicks
  placed stones and updated the candidates without a reproduced hang; this
  is not an instrumented latency benchmark.
- The one-click setup overview visibly showed the running CUDA backend and
  B11 model notice. Editing only the test copy's engine configuration to PDA
  `1.25` and wide-root noise `0.2`, then restarting the EXE, displayed those
  values in the main controls while real CUDA inference continued.

Screenshots in the evidence root's `screenshots` folder include
`01-first-launch.png`, `02-flash-complete.png`, `03-readboard.png`,
`04-startup-curve-fixed.png`, `05-fox-professional-ranks.png`,
`06-fox-auto-curve.png`, `07-auto-setup-overview.png` and
`08-parameter-readback.png`.

The native file chooser was visible, but the desktop automation helper kept
activating its owner instead of its modal child. File-chooser keyboard input
was not marked passed; the EXE command-line SGF path and in-app Fox open action
provide separate load-path acceptance. Existing repository desktop probes
exercise dialog keyboard behavior without that helper limitation.

## Additional PR 581 Acceptance

PR #581 arrived while the release request was being prepared. Publication was
held until its candidate-preview navigation changes were reviewed and tested
with the already accepted main. The local combined commit
`3d1736501fb7788e756243d963be88a983467802` and the author's updated PR head
`070db3f5354044b3169752acb237030910f02cdf` have the identical Git tree
`edd97a808acb1383f80e621ce6cbbfa015d63b9e`.

- Full clean-checkout local All gate: **65/65 PASS**, 633.0 seconds;
  **4600 combined JUnit tests, 0 failures, 0 errors, 107 conditional skips**.
- Focused `MoveOnlyUiGateTest` and `BoardNodeKindHistoryPipelineTest`:
  **190 tests, 0 failures/errors/skips** (42 and 148 respectively).
- Real CUDA `QuickAnalysisAcceptanceIT` and `MoveFocusNativeAcceptanceIT`:
  **5 tests, 0 failures/errors/skips**, including startup import, pause,
  batch/retry, foreground restoration and multi-point focus.
- Real Windows EXE: opened the same 50-move SGF, navigated to move 10,
  activated a candidate and stepped backward with ordinary Up through the
  complete preview. At the first-move boundary, further wheel-up and Page Up
  kept the real game at move 10. Wheel-down expanded the second preview move;
  leaving the candidate then restored normal history navigation to move 9.
- Independent-board and double-engine boundary permutations are covered by
  event-level regressions. The independent board was opened visibly, but its
  complete boundary sequence was not separately repeated manually.
- The new PR head's full CI, native Windows focus and B11 window workflows
  all passed. PR #581 was merged as `6e2f9102`; its product tree has no file
  differences from the tested combination.

The first exploratory before-fix screenshot (`09-pv-before-history-retreat.png`)
included Shift+Up, an intentional branch-navigation shortcut. It is **not**
valid evidence of this bug and is excluded from the acceptance proof. This
correction was posted in the PR conversation. The accepted post-fix screenshots
are `10-pv-first-move-fixed.png` and `11-pv-forward-fixed.png`, using ordinary
Up, wheel input and Page Up. Logs are `pr581-all.log` and `pr581-native.log`;
the full summary is `target/qa-20261001/pr581-all/local-ci-summary.json`.

## Final Package Boundary

The candidate EXE uses an existing verified launcher and copied bundled runtime
with the newly built JAR. It is not evidence that a subsequently built release
asset has passed acceptance. Final release assets require independent identity,
packaging and launch verification before publication.

This machine cannot validate macOS/Linux hardware, RTX 50, AMD ROCm or Intel
NPU. Paid cloud sessions, live third-party game play and ChatGPT account flows
are not claimed as passed. Protocol fixtures and hosted CI are distinct from
those hardware/account scenarios. No antivirus, proxy or security settings
were changed.

# Windows PR acceptance, 2026-10-02

## Scope and identities

This report distinguishes source integration, native Windows tests, real engine
inference, protocol fixtures, and final release-package acceptance. No statement
below certifies an untested release asset or another operating system.

The initial main was `c013d8ce66afc186df5af96c83a61cbb3e4a14ba`.
Reviewed PR heads:

| PR | Head | Purpose |
| --- | --- | --- |
| #583 | `47d0a3e84849a1672ae9688bd0ce62c5af322158` | Signed stable/beta update candidates |
| #584 | `7c16ae92c06bdc92148073eeb1019341415f6518` | Remote/local quick curves and foreground recovery |
| #585 | `12d9fb1440a7a73916f546fe1baa3d5b6f6334a0` | Complete suggestion-table rows |
| #586 | `aa65f97e58b0397677717bea9dac33932bfe610e` | Asynchronous variation previews |
| #587 | `8395cc174bf9e2b15847b92c212b28c32252f49d` | Automatic-analysis lifecycle ownership |
| #575 | `9d30778425de5f50a9fb62f9fdcf935d587063bf` | ChatGPT sign-in and teaching UI, still Draft |

The six-PR integration was `2af43e7c393bdc8063061c5a9bab97f939595dad`.
Its runtime-exception fix produced `48685a1d7528edd9311c2570f2944adea414a6fb`.
The analysis-only follow-up retains #583-#587 and that fix, without #575.
The original contributors' commits and both sets of changelog entries are retained.

The primary dirty checkout and real user installations were not changed. Tests
used a managed worktree and isolated portable/user-data under
`C:\ailearn3\lizzieyzynext\.qa\pr-acceptance-20261002` (the evidence root below).
Immutable model/DLL resources were hard-linked from an earlier QA copy; configs,
executables, JAR, user-data and writable caches were separate.

## Confirmed findings and fixes

1. #584 and #587 could not be combined by choosing either side wholesale.
   Preserve #587's task/engine-adapter ownership while retaining remote-only
   admission, reconnect synchronization, separated stdout/stderr, conservative
   root-edge cache admission, and foreground handback from #584.
   Migrate the timer/handback regression to the new task interface. Navigation
   invalidates the old position confirmation, so one fresh synchronization is
   required before foreground analysis resumes.
2. `AutomaticQuickAnalysisEngineAdapter.ensureWorker` only caught `IOException`.
   An unchecked startup/configuration failure escaped with acquisition still
   pending. The new red test failed with the controlled exception. Catching
   `IOException | RuntimeException` settles acquisition and allows a later retry.
   `uncheckedStartupFailureSettlesAcquisitionAndAllowsRetry` verifies both states.

No production change was made for the following test-environment observations:

- Ctrl+O intentionally opens the automatic-analysis import flow. Ordinary SGF
  opening was retested with the folder button, not judged from Ctrl+O behavior.
- The native ASCII keyboard probe initially failed twice because active Pinyin
  composed `winr` as `win'r`. With the existing `LIZZIE_TEST_ASCII_IME=shift`
  prerequisite, the entire desktop group passed. Both logs are retained.
- Immediate window captures can precede asynchronous painting; refreshed
  screenshots, application logs, and settled state were used for conclusions.

## Environment

- Windows 11 Pro 23H2, build 22631.6199.
- NVIDIA RTX 3070 Laptop GPU, 8192 MiB, driver 560.76.
- JDK 21.0.2, Maven 3.9.6; application launched through packaged Java.
- Real EXE: `portable\LizzieYzy Next NVIDIA.exe`.
- KataGo v1.18.2, project source `47aadc08518b3e121f22539796c911002f699584`,
  CUDA 12.8.61. This is not a CPU or TensorRT inference claim.
- B11: `kata1-tf3-b11c768-s12002M-d6304M.bin.gz`, 262017809 bytes,
  SHA-256 `4a6312e80faadee7b7dd28689a2e87a1efb4640c10132f16290da7a17b4c6d9e`.
- 100/150/200% supplemental UI runs used `sun.java2d.uiScale`. These are JVM
  scaling simulations on a native desktop, not changes to Windows system DPI.

## Recorded verification

All commands used `-Dfmt.skip=true`; headless gates also used
`-Djava.awt.headless=true`. The local All script includes Maven verify, package,
launcher/runtime/release-script, line-ending, Markdown and repository checks.

| Candidate / run | Result | Evidence relative to evidence root |
| --- | --- | --- |
| Six-PR integration, local All | 66/66 steps; 4790 JUnit tests, 0 failures/errors, 133 conditional skips | `local-ci/local-ci-summary.json` |
| Fixed six-PR candidate, local All | 66/66 steps; 4791 tests, 0 failures/errors, 133 conditional skips; 632.8 s | `local-ci-fixed/local-ci-summary.json` |
| Focused task/adapter/request/frame regressions | 237 tests, 0 failures/errors/skips | Maven focused-run log |
| Real CUDA/B11 quick-analysis scenarios | 4 tests, 0 failures/errors/skips | `native-engine/quick-analysis-*` |
| Zhizi protocol loopback with real local KataGo | 6 tests, 0 failures/errors/skips | `native-engine/zhizi-*` |
| Native Swing at JVM 100%, 150%, 200% | 96 tests per scale, 0 failures/errors/skips | `ui-1.0`, `ui-1.5`, `ui-2.0` |
| Desktop navigation/input, initial run | 10 tests, 2 ASCII/Pinyin failures, 0 errors/skips | `desktop.log`, `desktop` |
| Complete desktop group with ASCII prerequisite | 10 tests, 0 failures/errors/skips; 2 min 47 s | `desktop-ascii.log`, `desktop-ascii` |

The native quick-analysis test selection was
`QuickAnalysisAcceptanceIT,ZhiziQuickAnalysisAcceptanceIT`, with
`lizzie.acceptance.zhiziLoopback=true` and explicit engine/model paths in the
isolated portable. It covers completion, startup, pause, batch analysis,
reconnect during import/running analysis, and disabled automatic analysis.
The Zhizi service was a local protocol fixture, **not a real cloud account**.

The native UI selection was `ChatGptSettingsNativeTest`,
`TeacherCommentaryNativeTest`, `TeacherTypographyTest`,
`WholeGameAnalysisDialogLayoutTest`, `SuggestionTableScrollPaneTest`,
`MoveOnlyUiGateTest`, and the preview state/publication/scheduler tests.
Locales include the six shipped languages plus zh-HK compatibility.

The desktop selection was `FunctionSearchNavigationTest`,
`ConfigDialog2NavigationTest`, `EngineProcessSmokeTest`,
`FunctionSearchInputTest`, and `OfflineBoardAcceptanceTest`.

## Actual portable interactions

- First launch reached the ordinary board without setup popups. B11 CUDA
  produced positive visits. The saved configuration had `first-time-load=false`,
  a valid default engine, and automatic loading enabled.
- Ordinary opening of a clean 50-move SGF produced the complete curve and
  resumed foreground search. On the fixed candidate, the log records import
  synchronization at 22:54:23 and foreground analysis restored at 22:54:28.
  The first settled screenshot was taken at 14.5 seconds and showed completion;
  it is not a frame-accurate first-curve latency measurement.
- Winrate-graph clicking navigated to move 30; a recommendation click reached
  move 31. Main and independent-board hovering displayed numbered variations;
  Up changed preview length without moving the real board. Middle-click applied
  the visible 15-move variation, reaching move 46.
- Alt+O opened the bundled readboard v3.0.6 window and process. It was closed
  through its own UI. No download or fallback was involved.
- General settings and AI training forms opened normally; Esc returned to the
  main board. The QA copy had no HumanSL model, so this observation does not
  claim a completed AI training game.
- Existing isolated ChatGPT authorization restored with the fixed packaged JVM,
  returned five models, and completed one real streamed synthetic request
  (92 characters). No real user SGF, account identifier or token is in this report.
  This is a connectivity check, not certification of teaching correctness.

Screenshots are in `screenshots/01-first-launch.png` through
`screenshots/08-training-form.png`. The earlier preview/input screenshots use
the six-PR integration; `07-fixed-curve.png` and `08-training-form.png` use
`48685a1d`. UI matrix screenshots are separate under each `ui-*` directory.

## Remaining boundaries

- #575 remains Draft pending its separate authorization gates. No real account
  revocation was performed without consent. Actual expiry refresh, Linux Secret
  Service, system-DPI changes and audible screen-reader output are not certified
  by the Windows fixture and headless results.
- Real Zhizi cloud, a complete HumanSL training game, and the independent-board
  comma shortcut were not newly certified in this run. The corresponding
  automated and earlier contributor evidence must not be relabeled as this run.
- macOS/Linux hardware, RTX 50, ROCm and Intel accelerators were unavailable.
- This isolated EXE is a source-integration preview, not a newly produced final
  asset with producer provenance. Final packaging, signing, installation/upgrade
  and publishing gates remain separate. No release is authorized by this report
  alone.

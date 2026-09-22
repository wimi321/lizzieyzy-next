# Windows user-flow QA — 2026-09-22

Scope: repeat user-facing checks against `next-2026-09-22.2`, reproduce defects,
and verify fixes on a branch based on `2e1e08c9`. This is a PR validation report,
not a release certification or a claim that every hardware/integration combination works.
The separate B11 upgrade PR #534 is not included.

## Isolation and method

- Extracted the published NVIDIA portable package into a new QA directory; did not
  edit the user's daily installation or profile.
- Exercised the published launcher with Windows computer-use input and screenshots.
- Ran production Swing regression probes in separate JVMs with temporary profiles.
  Those probes test the real save dialogs/menu handlers, but are **not** a substitute
  for manual Windows native-open-dialog acceptance.
- Retained local logs/evidence under `.cache/windows-user-qa-20260922` outside Git.
  Existing CI runs the committed regression tests on Windows and Linux.

## Defects reproduced and fixed

| User flow | Before | Fix and regression |
| --- | --- | --- |
| Change ownership-display preferences after engine startup failure | Null-pointer exception in menu handlers | Guard engine-dependent calls; exercise 15 actual menu actions with no engine |
| Save a board without an engine | Null-pointer exception before chooser opens | Save safely when no foreground engine exists |
| Cancel raw/raw-with-comments save | Global raw-save mode remains enabled | Restore both save flags in `finally` |
| Cancel overwrite while analysis was running | Early return leaves analysis stopped | Restore only the same previously active engine; preserve paused/replaced engines |
| Save as `protected-sgf-中文` when `protected-sgf-中文.sgf` exists | Extension appended after existence check, bypassing overwrite confirmation | Resolve the final target first in ordinary, raw-comment, and branch save dialogs; test cancelling all four save modes preserves existing bytes |
| Save as `game.SGF` | Unwanted second extension | Preserve case-insensitive `.sgf` suffixes |
| Download B10 and switch from bundled B11 | B10 becomes active, but installed B11 changes to a download-only entry | Preserve local weight candidates in the authoritative saved-entry snapshot without changing its launch inputs |

Failures were observed before the corresponding fixes in `offline-regression-before.log`,
`save-before.log`, `overwrite-before.log`, and `catalog-before.log`. The catalog issue
was also reproduced through the published application's UI.

## Manual published-package checks

- PASS: fresh launch; bundled CUDA B11 loads and produces increasing visits.
- PASS: pause, place two stones, navigate back/forward, and preserve pause intent.
- PASS: resume analysis at the current position.
- PASS: open Auto Setup overview and weight management; current model and engine status agree.
- PASS: download B10 lightweight, apply it, observe the B10 title and `使用中` state.
- PASS: close Auto Setup and exit the application normally.
- PASS on the repaired build: backed up the isolated portable copy's original JAR,
  installed the locally verified JAR (SHA-256
  `27e924324b32c72bf50940ff3225f7646f174f6931b0fc1bd278cf63c9c75231`),
  then switched B10 → bundled B11 → B10 using the actual UI. Both models remained
  available locally after each switch; the inactive B11 showed `已下载` and
  `使用此权重`, while the active B10 showed `使用中`. No refresh/re-download needed.
- NOT COMPLETED: Windows native `选择棋谱` file selection. The dialog appeared, but
  the automation surface did not expose an independently targetable chooser. It
  was not counted as passed; the isolated session was cleaned up. Swing save-dialog
  regression and SGF parser tests have separate evidence.

## Automated verification

- Baseline before fixes: Maven `verify`, 4,225 unit cases, zero failures/errors,
  66 expected skips; logging integration passed (other opt-in integrations skipped).
- After fixes: native desktop + targeted helper run, 70 cases, zero failures/errors,
  3 expected Windows skips for POSIX executable fixtures. Production settings
  navigation, save/restart persistence, engine lifecycle, offline menus/saves,
  save analysis-state restoration, normalized targets, and catalog regression passed.
- Repository line-ending and diff checks passed.
- Full post-fix Maven `verify`: 4,229 unit cases, zero failures/errors, 66 expected
  skips; shaded-JAR logging smoke passed (six opt-in integration cases skipped).
  Build completed successfully in 3m20s. Markdown-link checks also passed.
- Real pinned CUDA engine plus cached B11 `s11750M-d6216M`: all four native
  acceptance cases passed, zero skips (`QuickAnalysisAcceptanceIT` and
  `MoveFocusNativeAcceptanceIT`). Covers foreground resumption, explicit pause,
  ordinary batch continuation/retry, and multi-point focus with SGF persistence.


## Limits

This pass does not certify online servers, credentials, remote compute, all GPU
families, all display scales, or every possible corrupted/user-authored SGF. Do not
interpret skipped or unavailable acceptance paths as successful tests. No PR was
merged and no release was published as part of this QA request.

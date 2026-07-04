# Lightning Batch Analysis Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the flash batch-analysis flow open flash-analysis settings before analysis starts, generate a KataGo `analysis` command from saved Lizzie engines, follow the current LizzieYZY Next default engine until the user customizes the flash-analysis command, create a missing `analysis.cfg` from a bundled template without network access, and remove the most likely causes of silent UI stalls during flash batch analysis.

**Architecture:** Keep the feature inside existing Swing dialogs and Lizzie config paths. Add an explicit analysis-settings context so flash batch settings do not depend on `Lizzie.frame.isBatchAnalysisMode` timing. Add a focused command-conversion helper for saved local KataGo engines. Add a small config state that records whether the flash-analysis command was manually customized; when it was not, derive the command from the saved default engine. Vendor KataGo's `analysis_example.cfg` as a classpath resource and copy it only when the target sibling `analysis.cfg` is missing. Harden `AnalysisEngine` request dispatch so failed stdin writes are reported and not left in `analyzeMap`, then move blocking request writes off the EDT for flash batch analysis while keeping Swing updates on the EDT.

**Tech Stack:** Java 17, Swing, Maven, JUnit 5, org.json, existing Lizzie config/resource bundle infrastructure.

---

## Documentation And Contract Checks

- [ ] Re-check `docs/SNAPSHOT_NODE_KIND.md`.
  - Verify: confirm the implementation does not change MOVE/PASS/SNAPSHOT history semantics and still builds analysis requests from the nearest legal snapshot boundary.
- [ ] Re-check `docs/TRACKING_ANALYSIS_CONTRACT.md`.
  - Verify: apply the same token-level command conversion discipline already documented for `gtp` to `analysis`.
- [ ] Re-check `docs/PLAYER_STRENGTH_CALIBRATION.md`.
  - Verify: keep timeout/progress UX consistent with existing engine-start and batch-position expectations.

## Resource: Bundle `analysis_example.cfg`

- [ ] Add `src/main/resources/katago/analysis_example.cfg`.
  - Source: copy the current KataGo `cpp/configs/analysis_example.cfg` into the repo during implementation time.
  - Do not download this file at runtime when the user clicks the UI.
  - Preserve upstream comments and defaults as much as practical so the generated `analysis.cfg` is recognizable.
- [ ] Add a small classpath-resource test.
  - File: `src/test/java/featurecat/lizzie/util/AnalysisEngineCommandHelperTest.java` or the chosen helper test file.
  - Verify: the resource loads through the classloader and contains known KataGo analysis config markers.

## Command Helper: Saved Engine To Analysis Command

- [ ] Create a focused helper, preferably `src/main/java/featurecat/lizzie/util/AnalysisEngineCommandHelper.java`.
  - Inputs: saved `EngineData`, classpath template resource name, and filesystem operations.
  - Output: a result object with `success`, `command`, `userMessage`, and optional generated config path/failure reason.
  - Keep parsing token-based via `Utils.splitCommand(...)`; do not do raw substring replacement.
- [ ] Implement local KataGo command conversion.
  - Replace an independent `gtp` token with `analysis`.
  - Preserve executable path, `-model`, model path, and unrelated arguments.
  - Locate `-config` or `--config`; replace its value with a sibling `analysis.cfg`.
  - Append `-quit-without-waiting` only when it is not already present.
  - Rebuild with existing command quoting conventions. If needed, expose a small package-private quoting helper from `KataGoRuntimeHelper` instead of duplicating incompatible quote logic.
- [ ] Implement explicit unsupported cases.
  - Remote engines are not converted in this step.
  - Commands without a standalone `gtp` token fail with a clear message.
  - Commands without `-config`/`--config` fail with a clear message.
  - If `analysis.cfg` is missing and cannot be created, fail with the actual filesystem reason.
- [ ] Implement offline `analysis.cfg` generation.
  - If the target sibling `analysis.cfg` already exists, leave it untouched.
  - If missing, copy `src/main/resources/katago/analysis_example.cfg` to that path.
  - Return the user-facing note: `缺少 analysis.cfg，已自动生成：<path>`.
- [ ] Add focused unit tests for command conversion.
  - Verify: path segments containing `gtp` are not modified.
  - Verify: standalone `gtp` becomes `analysis`.
  - Verify: `-config ...gtp.cfg` becomes sibling `analysis.cfg`.
  - Verify: `-quit-without-waiting` is added once.
  - Verify: existing `analysis.cfg` is not overwritten.
  - Verify: missing config arg and missing `gtp` fail without producing a command.
  - Suggested command: `.\.tools\apache-maven-3.9.10\bin\mvn.cmd -Dtest=AnalysisEngineCommandHelperTest test`.
- [ ] Add focused tests for default-engine follow vs manual override.
  - Verify: when `analysis-engine-command-customized` is false, the flash-analysis command is generated from the current saved default engine.
  - Verify: changing the saved default engine changes the derived flash-analysis command only while the customized flag is false.
  - Verify: choosing a saved engine or saving a manually edited command sets the customized flag true.
  - Verify: when the customized flag is true, the existing `analysis-engine-command` is preserved even if the saved default engine changes.
  - Verify: old configs without the key preserve non-placeholder `analysis-engine-command` values.

## UI: Flash Batch Settings Entry Point

- [ ] Modify `src/main/java/featurecat/lizzie/gui/StartAnaDialog.java`.
  - In the `isAnalysisMode` constructor path used by `AnalysisTable.stopStartAnalysisMode`, add a `设置` button next to the existing start/stop buttons.
  - Button action opens flash-analysis settings before analysis starts.
  - After the settings dialog closes, refresh `txtAnalysisPlayouts` from `Lizzie.config.batchAnalysisPlayouts` so the start dialog reflects saved visits.
- [ ] Add explicit settings context in `src/main/java/featurecat/lizzie/gui/AnalysisSettings.java`.
  - Add `AnalysisSettings.Context.NORMAL` and `AnalysisSettings.Context.BATCH`, or an equivalent minimal enum.
  - Existing constructors should delegate to the new constructor to preserve current call sites.
  - The new start-dialog button should call the batch context directly instead of depending on `Lizzie.frame.isBatchAnalysisMode`.
  - Use the context for both initial visit count and `saveConfig()` target (`batchAnalysisPlayouts` vs `analysisMaxVisits`).
- [ ] Track whether the flash-analysis command is user-customized.
  - Add a UI config key such as `analysis-engine-command-customized`.
  - When the key is false, derive the displayed/saved flash-analysis command from the current saved default engine.
  - When the user selects a saved engine in flash settings, uses `自动生成`, or manually edits `engineCmd` and confirms, set the key true.
  - Canceling the settings dialog must not change the key.
  - For old configs without this key, treat missing/blank/built-in placeholder commands as not customized, but preserve any non-placeholder command as customized because old versions cannot prove whether it was manually edited.
- [ ] Add saved-engine selection next to `自动生成`.
  - Use `Utils.getEngineData()` to read `leelaz.engine-settings-list`.
  - Show saved engine names in a simple Swing chooser.
  - On selection, call the command helper and put the generated command into `engineCmd`.
  - Show the generated-config note when `analysis.cfg` was created.
  - Show clear failure messages for unsupported remote/malformed commands.
- [ ] Handle current idle/preloaded analysis engine after changing the command.
  - If the analysis command changed and no analysis is running, destroy the idle `Lizzie.leelaz.analysisEngine` so the next flash analysis starts with the new command.
  - Do not terminate an actively running analysis from the settings dialog.
- [ ] Add required i18n keys.
  - Files: `src/main/resources/l10n/DisplayStrings*.properties`.
  - Add keys for saved-engine button, chooser title, generated config notice, and conversion failures.
  - Verify every active locale file has the new keys or the existing resource lookup can safely fall back.

## Stability: Prevent Silent Stalls

- [ ] Change `src/main/java/featurecat/lizzie/analysis/AnalysisEngine.java` command dispatch to report failures.
  - Make `sendCommand(...)` return success/failure or throw a checked exception.
  - Update overrides in tests/subclasses, including tracking-analysis helpers.
  - In `sendRequest`, do not leave a request id permanently pending after a failed write.
  - Surface the failure to the user instead of only `printStackTrace()`.
- [ ] Add a regression test for failed request writes.
  - Use a fake/failing writer or test subclass.
  - Verify a failed send does not remain in `analyzeMap`.
  - Verify the caller sees a failure path that can stop progress waiting.
- [ ] Move blocking flash batch request writes off the EDT.
  - Keep request collection and any Swing dialog creation/update on the EDT.
  - Run stdin writes to KataGo on a single background dispatcher thread.
  - Ensure `WaitForAnalysis.setProgress(...)`, dialog visibility, and batch next-file transitions happen through `SwingUtilities.invokeLater(...)`.
  - Preserve request id mapping order so responses still map to the intended board nodes.
- [ ] Add minimal diagnostics for stalled progress.
  - Log the engine command, request count, and last successful send id when a batch analysis starts.
  - If dispatch fails before all requests are sent, close or unblock the wait dialog and show the real send failure.

## Verification

- [ ] Run targeted unit tests.
  - Command: `.\.tools\apache-maven-3.9.10\bin\mvn.cmd -Dtest=AnalysisEngineCommandHelperTest,AnalysisEngineRequestTest test`
  - Expected: all targeted tests pass.
- [ ] Run existing related tests discovered by `rg "*Analysis*Test|*Tracking*Test|*ContractTests" src/test`.
  - Command should be narrowed to the actual class names found in the repo.
  - Expected: no regressions in tracking-analysis command conversion or analysis request contracts.
- [ ] Run formatting/checks without accepting unrelated churn.
  - Command: `git diff --check`
  - Expected: no whitespace errors.
- [ ] Build a jar for manual testing.
  - Command: `.\.tools\apache-maven-3.9.10\bin\mvn.cmd clean package`
  - Expected: build succeeds and produces the target jar.
- [ ] Manual test on Windows UI.
  - Open `自动分析 -> 批量分析(闪电模式)`.
  - Select SGF files.
  - Confirm the pre-start `自动分析设置` dialog has `设置`.
  - Open flash settings from that button.
  - With a fresh/non-customized config, confirm the flash-analysis command follows the current default engine in LizzieYZY Next.
  - Select a saved local KataGo engine.
  - Confirm command uses `analysis`, sibling `analysis.cfg`, and `-quit-without-waiting`.
  - Change the main default engine after manually selecting/editing the flash-analysis command and confirm the flash command remains unchanged.
  - Delete/rename sibling `analysis.cfg`, repeat selection, and confirm message says `缺少 analysis.cfg，已自动生成：<path>`.
  - Start flash batch analysis and verify the UI remains responsive during engine loading/request dispatch.

## Rollback Plan

- [ ] If command conversion causes trouble, disable only the saved-engine chooser and keep the existing manual command field plus `自动生成`.
- [ ] If async dispatch introduces a regression, keep the send-failure accounting fix and revert only the EDT/background dispatcher change.
- [ ] Do not change normal auto-analysis behavior except through the explicitly shared `AnalysisSettings` constructor compatibility path.

# Changelog

All notable maintenance updates to this fork are documented here.

## Unreleased

- Wait for the current engine startup/switch to settle before restoring an imported SGF, so games opened with the Windows EXE also start their automatic quick curve; preserve pause and retire continuations after replacement, failure or shutdown.
- Decode professional ranks in Fox game lists as P1–P9 instead of 83–91 dan, while preserving online dan and kyu ranks for both players.
- Keep simplified variation-tree images bound to the current history, selected node and viewport; discard late drawings and repaint accepted images on the event thread without requiring another input. Preserve preview selection, navigation and viewport clipping.
- Scan diagnostic secret lines once during export, preserving complete GTP analysis lines, credential and path redaction, session aliases, and original line endings.
- Restore Chinese ancient rules as a standard engine-game choice using the existing `stone-scoring` preset; recognize equivalent saved rules without rewriting them and preserve non-equivalent custom parameters (#549).
- Show concrete engine startup and synchronization failures in the diagnostic window even without a retained startup snapshot; preserve the failure reason and command in copy/export actions and keep local Windows command-line diagnostics available (#555).
- Restore opt-in search-thread changes for the current KataGo process, including explicit remote requests, with confirmed readback and stale-process isolation; preserve saved thread sources and PDA/WRN auto-loading, and make advanced-parameter help match the dialog theme (#558).
- Keep the advanced-parameter thread checkbox reachable by mouse at all three font sizes (#558).
- Keep confirmed thread overrides checked for the same KataGo process; clearing a local override confirms a safe restart from the current saved source, while remote connections explain unsupported source reset and unknown reconnect provenance (#558).
- Show the current process's last confirmed thread count immediately while refreshing in the background; indicate loading or refresh failure and preserve in-progress edits (#558).
- Preserve imported SGF dates, including Fox downloads, partial dates and multi-day expressions, through editing and repeated/background saves; leave missing dates unknown while new games retain their creation date.
- Restore the textured default workspace background consistently for fresh and existing configurations, while retaining explicit background colors and custom wallpaper.
- Update the bundled default B11 Transformer to the official 2026-09-12 `kata1-tf3-b11c768-s11750M-d6216M.bin.gz`, with verified size and SHA-256 across Auto Setup and all release package checks; keep the pinned KataGo engine, optional B10/HumanSL models, and existing users' weights unchanged by core updates.
- Capture SGF save snapshots on the event thread and write them safely in the background; unify save dialogs under the main window and preserve the full live variation tree when exporting the current branch.
- Preserve all installed weight candidates after switching models, so bundled weights remain available without downloading again.
- Keep ownership-display menus and SGF save dialogs usable without an engine; restore save modes and the original analysis state when saving is cancelled.
- Resolve SGF extensions before overwrite confirmation in all save dialogs, preventing silent overwrites for names containing `sgf` and duplicate uppercase extensions.
- Keep diagnostics actions visible after asynchronous size estimates, constrain long metadata to the available width, and fit initial dialog bounds to the current monitor work area (#536).
- Explain engine startup failures with Windows status codes, explicit DLL errors, existing KataGo runtime checks, and bounded process output; preserve the error and command layout with separate details, copy, and diagnostic export actions (#535).
- Record startup errors and late process output in ordinary WARN logs under the original engine and launch identity, and attach the displayed failure to diagnostic bundles (#535).
- Review measured live/whole-game KataGo recommendations separately, bind them to engine/model/config/GPU identity, and apply or restore scene-specific settings only after explicit confirmation.
- Simplify engine-game rule selection to Chinese, Japanese/Korean, AGA/BGA, New Zealand, Tromp-Taylor, and custom rules; use matching rule-family names during games and preserve saved preset parameters until explicitly changed (#516).
- Keep komi controls, game-info editing, and score-graph rendering usable after engine startup failure; cover the no-engine desktop paths in required regression tests.
- Apply current-game komi changes through both engine participants, wait for acknowledgements before committing, preserve pause intent, and end the batch safely if synchronization fails (#518).
- Fix ordinary batch analysis rejecting its own startup; preserve batch ownership through engine handoff and file continuation, and clear failed or cancelled batches so analysis can be retried (#523).
- Preserve renamed bundled KataGo profiles across restarts, Auto Setup, and portable package moves using persistent ownership independent of display names; protect user-edited commands (#521).
- Read KataGo model names from bounded native weight headers so replacing `default.bin.gz` updates engine labels, Auto Setup catalog matching, and compatibility hints; retain distinct names for unlisted Transformer versions (#520).
- Replace isolated point evaluation with equal-weight, multi-point focus in the current supported local KataGo search tree; adopt and save ordinary analysis, retain attention outlines after targets complete, and preserve focus across identical ReadBoard frames (#414).
- Use KataGo root visits for ordinary analysis totals and budgets, preserve same-stream evaluation updates and cache depth protection, and retain exact root counts, candidate order, and edge allocation in SGF (#414).
- Allow fresh point evaluation after returning to an accepted ReadBoard position without another helper frame; validate position semantics and engine synchronization, and reject malformed or stale frame publication (#444).
- Add isolated local Xvfb acceptance for real search input, settings persistence across JVM restarts, and real-engine quick-analysis completion and pause, with bounded execution and retained evidence.
- Require seven real engine-process lifecycle and recovery scenarios on Linux/Xvfb and native Windows, with isolated evidence and fail-closed CI aggregation.
- Check each release shell script individually, discover ordinary integration tests automatically, and reject missing or skipped critical logging-smoke execution in local and hosted CI.
- Restore exact engine snapshots through safe per-target absolute or working-directory-relative SGF addresses, rejecting isolated local transports before board mutation.
- Show engine startup diagnostics for primary and secondary engines outside first-launch onboarding, including missing executable and model/config path errors.
- Ignore delayed primary-engine startup diagnostics after engine replacement so they cannot cancel a newer engine game.
- Preserve GTP command-list response boundaries during startup so automatic analysis and foreground quick-analysis capability discovery complete correctly.
- Resume current-position analysis after automatic SGF quick analysis returns a shared foreground engine, preserving user pause and avoiding duplicate position replay or analysis restarts.
- Prevent engine startup retry from deadlocking with a queued synchronization-failure presentation.
- Add offline function search from the toolbar and `Ctrl+K`/`Command+K`, covering menus, toolbars, board actions, and settings with multilingual queries, categorized browsing, current availability, and navigation to original controls and confirmations.
- Require the Linux/Xvfb desktop CI lane to execute both localized real-input function-search chains, rejecting missing or skipped evidence while retaining bounded probe artifacts and result counts.
- Require the Linux/Xvfb desktop CI lane to execute the production engine startup, snapshot restore, analysis, stop, and clean-quit process smoke, rejecting missing or skipped lifecycle evidence.
- Bind searchable settings to stable IDs when their rows are created, preserving navigation across row reordering and checking for missing or duplicate targets.
- Refresh function-search availability outside result painting while keeping visible states live and rechecking actions before activation.
- Preserve Windows CI stall evidence with per-test JUnit events, paired process/thread snapshots, bounded execution, and owned-process cleanup before artifact upload.
- Split CI into independent repository, script, and Java jobs with grouped local entrypoints and a unified `ci-required` gate; retain the existing required-check names during migration.
- Save KataGo rule preferences on confirmation and close the settings dialog after successful application.
- Keep SGF rule-failure navigation local until explicit confirmed restore, preserve trial-return rules and positions, and resume both comparison engines after synchronization (#448).
- Keep thread-source controls visible and locked to CFG for empty or unrecognized local targets, without explanatory warnings (#437).
- Keep remote connection labels with their fields after deferred Swing layout, align thread-source controls with the adjacent benchmark action, and show only current thread policy status (#437).
- Keep thread-source labels aligned across local and remote entries, use concise benchmark button labels, and clear stale tooltips when switching targets (#437).
- Avoid save prompts and accidental engine creation for untouched empty rows in engine settings, while preserving edits to new and existing entries (#437).
- Start KataGo performance checks from any saved local engine entry, preserve its complete GTP inputs and independent results, and support GTP-only configurations and first-run OpenCL tuning (#437).
- Store KataGo thread sources, recommendations, and Apple tuning profiles per saved engine; preserve entry ownership across edits and benchmarks, and safely reload CFG settings (#437).
- Keep remote KataGo search threads server-managed across initialization, local benchmark results, and legacy thread settings; show remote ownership in engine details and reject nonlocal benchmark targets (#437).
- Preserve local moves after stopping ReadBoard synchronization and retire obsolete placement confirmations and queued output (#432).
- Preserved diagnostic log record boundaries and stack-trace whitespace during export without weakening privacy redaction (#431).
- Made diagnostic export estimates describe approximate uncompressed content, pruned proven irrelevant archives before reading using native Windows file identities where needed, and displayed publication success independently of folder opening (#430).
- Wait for confirmed engine positions before starting ordinary analysis after moves and history navigation; resume ReadBoard analysis once at the final synchronized position while preserving valid node caches (#429).
- Preserved complete custom match rules, distinguished official rule presets, clarified unverified participation, and enabled match-rule inspection from independent-board shortcuts (#422).
- Run custom local KataGo `benchmark` commands as slot-owned, cancellable tasks with streamed output and exit-code results (#423).
- Prevented KataGo rules dialogs from interrupting engine games while their participants remain occupied (#421).
- Added CI to build the shaded jar on push and pull request.
- Added Dependabot configuration for Maven dependencies and GitHub Actions.
- Added installation, troubleshooting, maintenance, and release-process docs.
- Added Japanese and Korean install guides.
- Unified the project wording around Fox nickname search and novice-friendly account wording in docs and UI strings.
- Polished the repository landing page, community health files, and multilingual README structure.
- Added internal release metadata generation and a validator that keeps public releases limited to the main novice-friendly assets.

## 2026-04-17 - Board Sync Entry Recovery

- Restored the Windows board sync entry so users can still open the feature even when the legacy native `readboard` folder is not bundled.
- Added a guided download prompt that opens the maintained `readboard` releases page when the legacy helper is missing.
- Added regression tests for the missing-helper prompt flow and the new localization keys.
- Historical tag at that time: `2.5.3-next-2026-04-17.1`

## 2026-03-16 - First Maintained Release

- Restored Fox kifu sync for the maintained fork.
- Switched the user-facing flow to Fox ID input instead of username lookup.
- Published practical multi-platform release assets, including Windows, macOS, and Linux packages.
- Added macOS Intel dmg packaging alongside Apple Silicon packaging.
- Bundled KataGo and default weights in the main all-in-one packages.
- Historical tag at that time: `2.5.3-next-foxuid-2026-03-16.2`

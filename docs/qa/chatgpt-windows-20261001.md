# PR 575: Windows acceptance, 2026-10-01

## Scope and environment

The ChatGPT commentary branch was merged with main `c013d8ce` in local merge
`9f85beae`. Tests use a separate worktree and QA portable, configuration, APPDATA
and credential directories. Real installation data, other applications' tokens,
security settings and proxy settings are not modified.

- Windows 11 Pro, build 22631; RTX 3070 Laptop GPU.
- Compilation/tests: JDK 21.0.2. The visible product uses its packaged Temurin
  runtime 21.0.12.1+1, not the system JDK.
- Executable: `LizzieYzy Next NVIDIA.exe`, with the candidate shaded JAR.
- EXE location: `C:\ailearn3\lizzieyzynext\.qa\pr-acceptance-20261001\portable`.
- Evidence root: `C:\ailearn3\lizzieyzynext\.qa\pr575`.
- High-DPI runs use JVM `sun.java2d.uiScale=1`, `1.25`, `1.5`, `2` on this Windows
  desktop. These are not claims that Windows system scaling was changed.

## Reproduced and repaired

1. A pending authorization could sign an account back in after logout, including
   logout from another application instance. A persisted authorization revision,
   checked under the session lock before saving credentials, rejects old callbacks.
   Explicitly starting a new login after logout remains possible.
2. The login deadline could report timeout while native credential persistence
   succeeded. The deadline and final commit now share the same synchronization.
   All three new lifecycle cases failed before repair, then passed.
3. Windows DPAPI reads incorrectly stripped trailing whitespace from decrypted
   secrets. Native canaries now verify exact values up to 65,536 characters plus
   Unicode, independently reopened stores, namespace separation and encrypted
   persistence. The initial exact-value test failed before repair.
4. Fixed outer-window dimensions left less form space on Windows, producing
   unwanted scrollbars. Initial size now reserves the content area. Four native
   settings tests failed before the fix.
5. A fixed Chinese font rendered Thai as boxes. Settings now use the existing
   locale-aware font resolver. The selected sidebar label reserves bold-text and
   icon width; Japanese footer buttons also reserve sufficient width at 150%.
   Visual review caught issues not covered by the original width-only assertions.
6. The new glyph test found Korean text in the Thai dan label. The Thai dan/kyu
   labels are corrected. The test covers all settings and ChatGPT strings in each
   shipped locale, including Traditional Chinese for Taiwan and Hong Kong.
7. The API-key Responses parser accepted `[DONE]` without `response.completed`.
   It now requires the actual completed event; Chat Completions retains its own
   valid `[DONE]` semantics. Incomplete commentary must not be written to SGF.
8. Follow-up screenshot review found the same missing Thai glyphs in the main
   commentary window, outside the settings page. Locale-aware fonts now cover
   that view, including its HTML reader, before button width measurement. New
   recursive glyph assertions cover labels, buttons and text components, rather
   than treating correctly sized boxes as readable text.

## Validation status

Final full local verification passed: all 65 steps of
`python scripts/run_local_ci.py --profile all` passed in 643.2 seconds, including
the complete Windows Maven `verify`, shaded packaging, logging-provider smoke,
launcher/JCEF/NVIDIA packaging checks, release checks, script syntax, Markdown,
line endings and `git diff --check`.

- Surefire: 4,681 tests, 0 failures, 0 errors, 120 skips.
- Failsafe: 8 tests, 0 failures, 0 errors, 7 skips. `LoggingProviderSmokeIT` ran.
- Combined final JUnit summary: 4,689 tests, 0 failures, 0 errors, 127 skips.
- Separate Windows credential gate: 42 tests, 0 failures/errors, 3 macOS skips.
- Windows product acceptance script fixtures: 29 tests, zero failures/errors.
- Log: `all-accepted.log`; machine-readable summary:
  `target/pr575-local-ci-accepted/local-ci-summary.json` in the isolated worktree.

This Windows `all` profile does not pretend to run on Linux: it deduplicates the
full Maven invocation and runs the portable script checks on Windows. The summary
records parent `9f85beae` plus the then-uncommitted fixes described here, not a
clean test of the parent commit alone.

Final focused/native reruns, including the main-commentary glyph fix:

| JVM scaling | Invocations | Failures | Errors | Skips |
| --- | ---: | ---: | ---: | ---: |
| 100% | 102 | 0 | 0 | 3 |
| 125% | 102 | 0 | 0 | 3 |
| 150% | 102 | 0 | 0 | 3 |
| 200% | 102 | 0 | 0 | 3 |

Each run includes ChatGPT protocol/lifecycle, DPAPI, API-key streaming,
settings assets/fonts, and visible settings/commentary tests in the shipped
locales. Skips are three macOS-only credential tests. Counts are per run, not
408 unique test cases. Logs are `accepted-native-{100,125,150,200}.log` under the
evidence root.

After fixing main-commentary glyphs, 18 focused tests passed with zero failures,
errors or skips, including actual visible windows in all shipped locales. The
earlier full rerun was intentionally interrupted at the launcher fixture step
when screenshot review found this remaining issue; it is not counted as passed.

The first full Windows run executed 4,680 tests: one failure (the Thai dan
label), zero errors, 120 skips. It is recorded as a failed run, not a successful
verification or packaging gate.

The isolated EXE was visibly launched and the AI commentary connection form was
opened from the actual toolbar. The process loaded
`portable\runtime\bin\server\jvm.dll`; its bundled KataGo child produced live
B11 CUDA analysis and visible candidate moves. No system Java is needed for this
product launch. The unconnected form screenshot contains no account details.

The final packaged JAR and the installed QA-copy JAR have matching SHA-256:
`c5b69c276355559cff31085d1a0deccfd24f336de169de070817812887adfcf4`.

## Remaining release gates

Real Windows ChatGPT authorization requires the account owner to complete the
official browser flow. Real plan-backed commentary, secure restart, actual token
refresh and remote revocation have not yet been accepted in this Windows run.
Mock OAuth/SSE tests and native DPAPI canaries do not substitute for those gates.
The PR must not be merged or published on the basis of this report alone.

The latest changes have not been physically tested on macOS or Linux. Prior
macOS evidence is retained in the earlier QA reports, not relabeled as a test of
this candidate. Native screen-reader speech has not been verified here.

## Screenshot evidence

Settings captures below are from actually displayed Swing windows with synthetic
accounts. They are distinct from the product EXE screenshot. No real account,
authorization URL, code, token or key is included.

### Thai glyphs and selected sidebar

![Before: Thai text rendered as boxes](chatgpt-windows-20261001/thai-before.png)

![After: Thai text and the selected sidebar label are readable](chatgpt-windows-20261001/thai-after.png)

### Main commentary Thai glyphs

![Before: main commentary rendered Thai as boxes](chatgpt-windows-20261001/thai-commentary-before.png)

![After: Thai main commentary controls are readable](chatgpt-windows-20261001/thai-commentary-after.png)

Both main-commentary captures use JVM 200% scaling.

### Japanese footer at JVM 150 percent

![Before: cancel label truncated](chatgpt-windows-20261001/japanese-150-before.png)

![After: cancel label fits](chatgpt-windows-20261001/japanese-150-after.png)

### Real Windows EXE, before authorization

![Packaged application ChatGPT connection page](chatgpt-windows-20261001/windows-exe-connection.png)

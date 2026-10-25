# Bounded real Zhizi analysis acceptance

`LiveZhiziAnalysisProbe` is an explicit manual acceptance main, never a CI test.
It uses the production password-login API, verifies an existing VIP membership,
then starts the built-in Zhizi engine with `VIP_ZHIZI_ARGS`. It never purchases
credit or switches to usage-based billing. Use only an authorized test account.

Build the test classes and shaded jar, then run from the repository root using
JDK 21 in a private interactive terminal. The Windows classpath separator is `;`:

```text
java -cp "target/test-classes;target/lizzie-yzy2.5.3-shaded.jar" featurecat.lizzie.gui.LiveZhiziAnalysisProbe comparison C:\qa\fresh-comparison-run 180 8 500 1
```

The six arguments are `mode new-output-directory maximum-seconds moves visits
repetitions`. Modes are `flash`, `native-flash`, `auto`, `curve`, `lifecycle`, `all`, and
`comparison`. Comparison records a failed flash attempt, cleans up its dialog
and worker, then continues automatic-analysis and imported-curve measurement;
the overall result still fails when flash fails. It is useful on the released
baseline before applying the fix.
Credentials are read with `Console.readPassword`, with echo disabled. Never put
credentials in command arguments, environment variables, files, or reports.
The secret store is replaced with a session-only test store; the real Windows
credential store and normal application profile are not read or written.

- `flash` invokes the real `flashAnalyzeGame(true, false)` entry point for
  **整盘速览（闪电）**, checks per-position visits and selected-move preservation.
  Each completed repetition also writes `metrics.jsonl`: start/completion/foreground
  timestamps, elapsed times, position count, configured model, target visits, and
  the minimum/maximum visits actually stored. The target is a completion threshold,
  not a promise that a streaming search stops at exactly that count. Repeated
  imports may retain engine search/cache state; report cold and warm observations
  separately and include actual visits when comparing builds.
- `native-flash` loads the same fresh fixture and selects a move, then prints
  `ready-for-native-flash`. It never invokes the analysis action: use the real
  application menu with the mouse within 60 seconds. The same completion,
  visits, selection, foreground handback, cleanup, and secret checks follow.
  This mode accepts a maximum deadline of 120 seconds. Its elapsed measurement
  starts when active analysis is first observed and excludes operator waiting;
  it is an interaction check, not a directly comparable throughput benchmark.
- `auto` invokes the toolbar's real step-by-step automatic-analysis action with
  only a fixed visits budget, both colors, and no autosave. This is distinct
  from the automatic curve generated when importing a game.
- `curve` exercises that imported-game automatic curve and foreground handback.
  Its production budget is 16 visits, independent of the manual visits argument;
  the probe reads and reports the actual production constant.
- `lifecycle` tests explicit pause/resume and an actual socket disconnect with
  a fresh engine incarnation and resumed foreground progress. It requires at
  least 90 seconds remaining before beginning reconnect, otherwise it reports
  incomplete acceptance rather than claiming a pass.

Each run creates fresh game data with a recorded SHA-256, fixed Chinese rules,
7.5 komi, 19x19 board, one cloud primary, and the same provider model. Compare
identical arguments at the release base and fixed revision; keep startup time
separate from recorded analysis time. Alternate runs for performance claims;
shared-cloud variance means one run cannot establish a speed improvement.

The maximum run length is capped at 600 seconds. Cleanup starts before the hard
deadline and aborts known cloud transports; the guard terminates only this probe
JVM if its normal cleanup stalls. A hard timeout is a failed/incomplete run.
Total time across multiple invocations still needs to stay within the user's
authorized budget. Cloud startup and membership requests count toward it.

Only `result=PASS`, `cloud-transports-closed PASS`, and `credential-scan PASS`
together count as a passed run. The summary prints failure stage and exception
type only, never provider response bodies or account details. Do not upload raw
logs without inspection: provider diagnostics can contain account identifiers.
Programmatic modes do not replace real mouse/keyboard checks; `native-flash`
explicitly requires the real menu interaction.

The initial released-version baseline reproduced the missing-command flash
prompt. It also found that provider startup stderr included the account ID in
`User: [zz-...]`, so that baseline's credential scan failed despite session-only
storage. Its raw log is local diagnostic evidence, not a shareable artifact.
The persistence sanitizer now masks that provider field; a fresh successful
credential scan is required before a fixed-version run can pass.

To compare against a released jar without rebuilding the baseline, compile this
single probe with that jar on `javac -cp` into a separate classes directory, then
run with the released jar and that directory on the runtime classpath. No new
production API is required. `ENGINE_GTP` trace collection starts only after
login and engine readiness; timestamps can separate command round trips from
search time. Stop and scan the trace before sharing any sanitized excerpts.

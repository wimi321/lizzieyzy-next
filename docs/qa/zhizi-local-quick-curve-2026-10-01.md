# Real Zhizi and local quick-curve acceptance

## Scope

Regression follow-up for PR #584, based on `next-2026-10-01.1`.
The same isolated Swing workflow runs against real Zhizi VIP, a shared local
Metal GTP engine, and a dedicated local Metal Analysis engine. It uses the
production downloaded-SGF import path with an anonymized legal game fixture,
not the Fox download service. Windows hardware and the reporter's other Mac
are not covered by this run.

## Defects reproduced

- Real Zhizi: quick analysis returned exact root visits, but ordinary analysis
  sometimes omitted `rootInfo` despite its being requested. The cache then
  rejected all subsequent updates. In one reproduction the engine exceeded
  38,000 visits while the selected node remained at 1,312. Use known root-edge
  visits as a conservative lower bound for cache admission; do not compare DAG
  child totals to an exact root count, double-count symmetry copies, or invent
  an exact root total. Older output without edge counts retains cache protection.
- Dedicated local analysis: merging stderr into stdout allowed diagnostic
  fragments to prefix JSON responses. Only 33 of 40 responses were consumed in
  one run, leaving quick analysis permanently active. Keep the two pipes and
  their readers separate, including diagnostic provenance.
- Shared-engine handback: the final result can arrive before the board-restore
  barrier completes. A retry/navigation timer then retired the task generation,
  discarding the callback responsible for resuming foreground analysis. Preserve
  the active generation until that callback, and include restoration in the
  request lifecycle. A deterministic regression invokes both timers in this
  interval and verifies exactly one foreground resume.

## Reproduction tool

`LiveQuickAnalysisProbe` is an explicit manual acceptance main, not a CI test.
Run it from the repository root after building with JDK 21:

```sh
mvn -B -Dfmt.skip=true -Djava.awt.headless=true verify
java -cp target/test-classes:target/lizzie-yzy2.5.3-shaded.jar \
  featurecat.lizzie.gui.LiveQuickAnalysisProbe \
  remote /tmp/quick-curve-new-run /absolute/path/to/katago /absolute/path/to/model.bin.gz
```

Replace `remote` with `local-shared` or `local-dedicated` for local tests, always
using a new output directory. Remote mode requires an interactive terminal and
reads both credentials with echo disabled. Never put credentials into shell
arguments, environment variables, scripts, recordings, or GitHub reports.
Use only an explicitly authorized account with an existing VIP entitlement;
the probe does not purchase a plan or change to usage-based billing.

The tool disables credential persistence with an isolated test store, operates
the real login/enable buttons on the EDT, clears the password field after login,
and scans generated files for the supplied password and account token. It does
not read or modify the user's Keychain/profile. Service stderr may still contain
account identifiers; do not upload raw logs or login-window images.

Covered assertions:

- A fresh remote-only profile can log in and generate all 40 curve points.
- Changing rules/komi and navigating to move 12 preserves that selection and
  resumes growing ordinary analysis after completing the curve.
- Disconnecting the actual Zhizi Socket retires its reader; reconnect restores
  the game, completes missing points, and resumes ordinary analysis.
- Three successive imports each complete and resume foreground analysis;
  replacing a game while its curve is running retains only the new game.
- Explicit pause remains paused; manual resume works.
- Disabling automatic quick analysis prevents it on the next import.

Screenshots are rendered from the Swing main window on the EDT, not captured
from the desktop compositor. The probe stops its own engines at exit and saves
a non-secret `result.txt`. Only a final `result=PASS` plus a successful
credential scan counts as a passed run. Real-hardware evidence does not replace
the deterministic pipe-separation and exact-root cache-protection tests.

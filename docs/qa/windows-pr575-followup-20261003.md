# Windows PR 575 Follow-up: 2026-10-03

## Candidate

- Main: `b768c7707b68bf1d966c5019cede4a7143999042`.
- PR 575: `9d30778425de5f50a9fb62f9fdcf935d587063bf`.
- Integration: `9a736f37f867bab1750dda03f592336daa6ab5ad`.
- Windows 11 Pro 23H2, build 22631.6199; RTX 3070 Laptop.
- JDK 21.0.2, Maven 3.9.6, isolated NVIDIA portable runtime.
- Previous full results: [Windows integration acceptance](windows-pr-acceptance-20261002.md).

## Natural Session Expiry: PASS

Added an opt-in `refresh-check` mode to `ChatGptLiveAcceptanceCli`.
It observes expiration metadata through a delegating credential store. It never
backdates real credentials, prints secrets or account identifiers, or reads any
other application's credential namespace.

Executed using the isolated portable's bundled Java and the application's own
previously authorized DPAPI session. Before the first model request, its saved
access token had naturally expired. A successful model request caused exactly
one credential write with a future expiry. Reopening the session and fetching
models again succeeded without another credential write.

```text
NATURAL_EXPIRY_REFRESH_OK persisted=true reopened=true modelCount=5
```

This proves natural-expiry refresh and persistence for this Windows session.
It does not prove remote revocation or Linux Secret Service integration.

Focused tests: `ChatGptLiveAcceptanceCliTest`, `ChatGptIntegrationTest`, and
`AccessibilitySupportTest`: **68 tests, 0 failures, 0 errors, 0 skipped**.
Package build: **BUILD SUCCESS**, 55.082 seconds.

## Pending Native Gates

- Windows Settings automation timed out awaiting app approval; a subsequent
  launch exposed no targetable window. Control Panel approval also timed out.
  No system scaling was changed. Prior 100/150/200% results remain explicitly
  JVM simulations, not real OS scaling.
- No installed NVDA was found in Program Files. Windows Narrator launch approval
  timed out. Audible screen-reader output has not been certified.
- The isolated portable EXE returned access denied on launch, then was absent on
  a second launch and directory inspection. Its JAR and runtime remained. The
  cause is not yet established; security-software quarantine confirmation was
  requested. No protection was disabled and the EXE was not restored or renamed
  to bypass the failure.
- Explicit authorization for revoking the isolated app's real grant remains
  pending. No real grant has been revoked in this follow-up.

Do not mark the complete release gate PASS from these partial results. Keep
PR 575 Draft until its remaining release gates are actually satisfied.

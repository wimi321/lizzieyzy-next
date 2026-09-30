# ChatGPT commentary acceptance, 2026-09-30

## Scope

Feature branch based on `8c43fdd24b01acd6485b8b3911708290ae18ab75`.
No release, merge, directory submission or UU connection is part of this change.
Official direct OAuth/Responses contracts were checked on 2026-09-30.

## Completed local checks

- JDK 21 complete headless Maven verification, including shaded packaging: 4,526 unit-test
  invocations, zero failures/errors, 93 skipped; seven integration-test invocations,
  zero failures/errors, six skipped for desktop/hardware requirements.
- Focused authorization/commentary/localization/proxy regressions: 101 tests, no failures.
- Native macOS settings-dialog check: equal provider button dimensions/font, explicit new-user
  choice, API form and ChatGPT form, all seven locale variants (six languages including both
  traditional-Chinese regions). No clipped buttons in these default-size captures.
- Commentary view keeps a visible, accessible Manage usage action without covering the composer.
- Fake loopback service: PKCE, state/nonce/signature/audience, denial, expiry, cancellation,
  late callbacks, issued-registration retry, identity-only consent, model visibility/order,
  same identity in separate workspaces, serialized refresh, revocation and storage failure.
- Interrupted/failed/limited streams never complete successfully; no automatic API-key fallback.
- Markdown links, LF policy and diff-whitespace checks.

The new HTTP client initially bypassed the common proxy helper. The repository inventory
test caught it; the client now uses `NetworkProxy`, and the regression passes.

## Selected settings redesign verification

- Selected connection/preferences references and side-by-side native captures are recorded
  in `design-qa.md`; the latest local settings comparison passed with no open P0/P1/P2.
- Final JDK 21 headless `verify` including shaded packaging: 4,527 unit-test invocations,
  zero failures/errors, 94 skipped; seven integration-test invocations, zero failures/errors,
  six skipped. The first restricted-sandbox run hit macOS child-process and home-directory
  permission failures; rerunning with the required local permissions passed unchanged.
- Two opt-in native macOS tests passed. They exercise seven locale variants, both provider
  forms, isolated preferences, equal tabs, draft preservation, preferences-only persistence,
  default no-scroll layout and minimum-size fallback, plus fake-service account/model/error/logout.
- The longer English Save preferences button was initially clipped; sizing for both localized
  footer labels fixed it and the complete native suite passed after the correction.
- Local Markdown links, tracked-file LF policy and staged diff whitespace checks pass.
- The final source remains native Swing; reference images are review artifacts, not UI skins.

## Not yet accepted

The later visual-fidelity pass repeats the native locale/account suite after changing fonts,
palette, row spacing, tab padding and illustration assets. Helper text is asserted non-bold,
and captures allow the requested navigation focus to settle before inspection. No permission,
token handling or inference behavior was changed by that pass.

- Real eligible ChatGPT account: official authorization, account model catalog, live commentary,
  restart persistence, refresh and sign-out/revocation. An isolated native login window was
  opened; no successful user authorization has been recorded. Fake service tests are not a
  substitute for this gate.
- Windows native UI at 100/125/150/200% DPI, Windows DPAPI for this new credential kind, and
  Linux Secret Service. No Windows machine or UU connection was used.
- Whole-application native desktop acceptance: the broad non-headless macOS run encountered
  focus/input failures in `FunctionSearchInputTest` and `SetKataRulesWindowTest`, plus explicitly
  Windows-only `TensorRtRepairAcceptanceTest` failures. Those results are not relabeled as
  passes. The relevant commentary native suite was run separately. Headless verification
  correctly skips tests that require platform-specific desktops/hardware.

Keep the PR in draft until the real-account gate has evidence. Do not advertise unlimited
usage or publish a release from these results. For repeatable commands and isolated live-test
entry points, see [ChatGPT commentary](../CHATGPT_COMMENTARY.md).

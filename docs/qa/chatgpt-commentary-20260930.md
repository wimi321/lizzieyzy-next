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

## First-open follow-up

The later visual-fidelity pass repeats the native locale/account suite after changing fonts,
palette, row spacing, tab padding and illustration assets. Helper text is asserted non-bold,
and captures allow the requested navigation focus to settle before inspection. No permission,
token handling or inference behavior was changed by that pass.

The subsequent missing-icon report exposed a gap in that suite: it selected ChatGPT before
checking the first-open screen. The unselected card was reproduced without any illustration,
then fixed with a neutral introduction using the selected design's asset and privacy footer.
Three focused tests now pass, covering both native scenarios and a new packaged-asset test.
The native assertions explicitly check visible, unclipped icons before and after provider
selection. The before/after first-open screenshots are recorded in `design-qa.md`.

- Final headless `verify` and shaded packaging: 4,528 unit-test invocations, zero
  failures/errors, 94 skipped; seven integration-test invocations, zero failures/errors,
  six skipped. The three focused native/assets tests passed without skips.
- The shaded JAR contains the browser/globe and both navigation icons. A temporary
  jpackage app using that exact JAR was opened through the native desktop tool, not
  `target/classes`. Its real first-open and ChatGPT pages visibly showed the illustration.
  Clicked through API Key, Preferences and back to ChatGPT; all displayed correctly.
  No login, model request, credential save or inference request was performed.

## API copy follow-up

New settings no longer populate or silently fall back to an OpenAI
address. Existing explicit addresses and pre-provider legacy implicit addresses are preserved.
The service-address example is painted outside the document, hides on focus, and never
becomes a saved value. Empty addresses fail before saving or starting model discovery.
Twenty-two focused tests passed, including real focus changes, seven locale layouts and
legacy/new settings reload. The API screenshot and focused-empty screenshot are updated.

- Final headless Maven `verify` and shaded packaging passed: 4,532 unit-test
  invocations, zero failures/errors, 95 skipped; seven integration-test invocations,
  zero failures/errors, six skipped. The 22 focused tests passed without skips.
- Opened a temporary jpackage preview of the final shaded JAR with a fresh isolated
  configuration. The API page visibly shows the new labels, empty service address,
  example hint and compact instructions without a scrollbar. No credentials or
  requests were submitted.
- The desktop automation tool did not reliably focus the packaged address field;
  its click-to-hide check is therefore inconclusive. Focus hide/restore behavior is
  verified by the native Swing test, not claimed as a passed manual click check.

## Model example follow-up

- The API model field now starts empty with a painted, localized `gpt-5.4-mini`
  example, using the same focus-hide behavior as the address. The example is not a
  default model, saved setting or request parameter. Explicitly saved model names
  are retained; an empty model is rejected before saving connection settings.
- Model discovery now works without a selected model and makes only a model-list
  request. Loading the list does not silently choose its first entry.
- The example identifier is documented in the
  [official model catalog](https://developers.openai.com/api/docs/models/gpt-5.4-mini).
  It is not a claim that every third-party provider offers that model.
- The 26 focused tests passed without skips, including native focus hide/restore,
  model selection, seven locale layouts, blank configuration reload, existing
  configuration preservation and fake-service discovery. The first sandboxed
  native launch aborted before UI assertions; the desktop-enabled rerun passed.
- Updated [API screenshot](../screenshots/chatgpt-api-key-zh-CN.png) and
  [focused model screenshot](chatgpt-settings/zh-CN-api-model-focused.png) were
  inspected. Windows native validation remains outstanding.
- Full headless Maven `verify` and shaded packaging passed: 4,533 unit-test
  invocations, zero failures/errors, 95 skipped; seven integration-test invocations,
  zero failures/errors, six skipped. Markdown links, line endings and diff
  whitespace checks also passed.

## Real-account follow-up and thinking depth

- The user completed official sign-in; the real account catalog returned five visible models.
  This exposed a real macOS persistence defect: the `security` CLI password prompt truncated
  synthetic inputs of 1,024/4,096/12,000 bytes to 128 bytes. Exact verification correctly
  rejected the truncated credential and kept the authorized session in memory only.
- Replaced that prompt path with length-delimited Security/CoreFoundation calls through the
  existing JNA dependency. Real Keychain canary tests round-trip 32/128/129/1,024/4,096/12,000/
  65,536 characters plus Unicode, newline and trailing-space suffixes. Tests use unique account
  identifiers, remove only their own entries, and do not read real credentials.
- Thinking depth is next to the ChatGPT model. Options come from the account catalog's
  `supported_reasoning_levels`, never a model-name guess. Defaults omit `reasoning`; explicit
  choices are remembered per account/model and revalidated before a Responses request.
- Fake-service tests verify outgoing `reasoning.effort`, missing/malformed/duplicate capability
  metadata, independent model/account preferences, restart, removed support and no silent fallback.
- The first native layout test caught a scrollbar introduced by stacked selectors. The compact
  two-column revision passed all four native tests, including seven locale variants and saving
  the selected effort through the real form. These tests use a fake account, not the user's data.
- After user reauthorization and saving, a new process of the same packaged application
  recovered the login (`sessionOnly=false`), fetched five live models, and restored the selected
  `gpt-6-astra` / `high` preference. One synthetic Go-evidence request reached official
  `response.completed` and returned 76 characters. This verifies transport/completion, not
  playing strength or a full-game explanation. No password, token, account email or authorization
  URL is included in these evidence artifacts.
- A system-JDK acceptance process triggered repeated Keychain prompts because it was a different
  executable from the app that saved the grant. That process was stopped. Subsequent real-account
  checks used the same packaged executable; no Keychain ACL was weakened.
- Background ChatGPT reads now suppress authorization UI in a serialized native scope and restore
  the prior process policy even on failure. A foreign-writer synthetic canary was refused three
  times in under five seconds, with no prompt; the interaction policy was unchanged afterward.
  Unit tests cover nested scopes, disabled policy and failure to inspect/disable interaction.
- Final focused run: 72 test invocations, 70 passed, zero failures/errors, two skipped (Windows
  DPAPI and separately gated legacy-CLI authorization). All four native UI tests passed across
  seven locale variants. The final pass also caught and removed a two-pixel default-window
  overflow introduced by grouping the model/effort controls. Signed-out fields are hidden.
- Final clean JDK 21 headless `verify`, including shaded packaging: 4,542 unit-test invocations,
  zero failures/errors, 99 skipped; seven integration-test invocations, zero failures/errors,
  six skipped. Line endings, Markdown links and diff whitespace checks pass. An isolated
  configuration/log scan found no plaintext access, refresh or identity tokens.

## Not yet accepted

- Real eligible ChatGPT account: automatic refresh at token expiry, sign-out/revocation and
  full-game commentary remain unverified. Official sign-in, live catalog, repaired persistence,
  restart and one synthetic commentary request passed. The final no-prompt read change is covered
  with native canaries; the real-account preview was not re-signed to avoid another trust change.
- Windows native UI at 100/125/150/200% DPI, Windows DPAPI for this new credential kind, and
  Linux Secret Service. No Windows machine or UU connection was used.
- Whole-application native desktop acceptance: the broad non-headless macOS run encountered
  focus/input failures in `FunctionSearchInputTest` and `SetKataRulesWindowTest`, plus explicitly
  Windows-only `TensorRtRepairAcceptanceTest` failures. Those results are not relabeled as
  passes. The relevant commentary native suite was run separately. Headless verification
  correctly skips tests that require platform-specific desktops/hardware.

Keep the PR in draft while outstanding platform/real-account gates remain. Do not advertise
unlimited usage or publish a release from these results. For repeatable commands and isolated live-test
entry points, see [ChatGPT commentary](../CHATGPT_COMMENTARY.md).

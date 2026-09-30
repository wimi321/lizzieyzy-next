# AI Commentary Settings Design QA, 2026-09-30

## Scope And Comparison

The selected second visual direction is implemented as one native Swing editor with two
pages: Connection and Preferences. This section supersedes only the settings-window QA;
the older commentary-reader report below is retained and is not evidence for this change.

- Source visual truth: `docs/design/chatgpt-settings/connection-reference.png` and
  `docs/design/chatgpt-settings/preferences-reference.png`.
- Implementation: `docs/screenshots/chatgpt-login-zh-CN.png` and
  `docs/qa/chatgpt-settings/zh-CN-preferences.png`.
- Window: 880 x 650 logical pixels on macOS; captures exclude native title chrome and
  contain the 880 x 622 app-owned area at 1x density.
- Source: 1499 x 1049 pixels, normalized without distortion using contain-fit to 880 x 622.
- States: Simplified Chinese, light theme, signed out; then Preferences with the saved
  default rank. The mock's sample 5 dan does not replace the user's actual 5 kyu default.
- Combined full-view evidence: `docs/qa/chatgpt-settings/connection-comparison.png` and
  `docs/qa/chatgpt-settings/preferences-comparison.png`, each 1760 x 622. Both comparisons
  were opened and inspected with source on the left and implementation on the right.
- Focused crops were unnecessary: labels, dividers, icon strokes, control padding and
  footer buttons are readable at full resolution in these combined views.

## Findings And Corrections

- Second fidelity pass, after user feedback: the earlier pass accepted too much typography
  and spacing drift. Reopened these as P2: inherited gray/green text, uniformly heavy
  navigation, compressed preference rows, excessive login whitespace and the missing globe.
  The final captures now use explicit regular/bold font roles, slate text and jade accents,
  a 210 px rail, 220 px fields, corrected provider-tab padding and a recreated browser/globe.
  The screenshot paths above have been refreshed with the post-fix evidence.
- Font metrics initially introduced vertical overflow, including a one-pixel overflow in
  the Chinese preferences page. After adjusting header/page/section margins, the complete
  seven-locale native suite passes without a default scrollbar. Small-window scrolling
  and persistent footer controls remain intact; the test assertion was not relaxed.
- Resolved P1: the initial native form inherited beige input surfaces and hidden-card
  preferred heights, adding blank space and default scrollbars. Visible-card sizing,
  width-tracking pages and explicit theme-aware control rendering now preserve the design.
- Resolved P2: English preferences and signed-in account content initially overflowed.
  Shorter navigation, compact field spacing and removal of redundant signed-in notices
  leave default-size pages without scrollbars in every tested locale.
- Resolved P2: preferences lacked the source's two section headings, and the connection
  illustration/actions were too far left. Added groups and adjusted the sidebar, margins
  and action alignment; the final combined captures show the post-fix result.
- Resolved P2: the longer English Save preferences label was clipped after changing pages.
  The persistent button width now accommodates both localized labels; native tests pass.

## Fidelity Surfaces

- Typography: available PingFang SC, Microsoft YaHei UI or Noto Sans CJK SC with native
  fallback; 32 px window/page headings,
  16 px labels and 14 px wrapping explanations preserve hierarchy. System glyph weight
  differs slightly from the generated reference; no font is embedded or simulated.
- Layout: tinted 210 px minimum rail, equal provider tabs, consistent 220 px field alignment,
  thin row separators and persistent footer. Default 880 x 650 has no page scrollbar;
  the 740 x 530 minimum uses safe content scrolling without hiding footer actions.
- Colors: white content, pale teal rail, jade selected state/action, slate helper text
  and neutral dividers. The mock's subtle gradient is intentionally a flat native surface.
- Assets: licensed Lucide control icons plus a transparent generated browser/globe
  illustration based on the selected source. Its 86 x 70 logical aspect ratio is preserved.
  It is neither an OpenAI logo nor a rasterized form; all text remains native and editable.
- Copy: connection credentials and teaching controls never share a page. Preferences
  explicitly apply to both providers. Save preferences deliberately replaces the mock's
  ambiguous Save settings label, and successful preference saves keep the editor open.

## Interaction Evidence And Limits

Native macOS tests exercised all seven locale variants, equal provider dimensions/fonts,
page switching, API draft preservation, preferences-only persistence, minimum resizing,
and fake-service signed-in, model-error and sign-out states. Accessible names and button
text bounds are checked. Focus outlines remain visible and the OS owns the close button.

Additional captures are under `docs/qa/chatgpt-settings/`: API form, signed-in account,
model error, minimum window, English preferences and Thai preferences. No real credentials
appear in the captures; the account is a local fake-service fixture.

Windows native DPI, real screen-reader usage and real eligible ChatGPT account acceptance
remain unverified. This result is limited to the local settings redesign, not an assertion
that the complete authentication feature or every platform has passed release acceptance.

No remaining actionable P0/P1/P2 design findings in the compared local states.
P3 follow-up: system-font glyph shapes and the flat native surface differ slightly from the
generated mock's optical weight and subtle background texture. The native title bar, visible
keyboard focus, user's actual rank and precise Save preferences label are intentional.

final result: passed

---

# Earlier AI Commentary Reader Design QA

## Comparison Target

- Source visual truth: left half of `docs/qa/ai-commentary-redesign/option-1-comparison.png`.
- Final implementation capture: right half of `docs/qa/ai-commentary-redesign/option-1-comparison.png`.
- Full-view comparison: `docs/qa/ai-commentary-redesign/option-1-comparison.png`.
- State: Simplified Chinese, light application theme, ready empty state, no commentary API key configured.
- Source pixels: `1443 x 1090`.
- Implementation pixels: `1350 x 1020`, representing a `900 x 680` logical Swing window at Windows/Java UI scale `1.5`.
- Density normalization: the source was resampled to `1350 x 1020`; the implementation remained at native capture resolution. Both halves in the comparison are `1350 x 1020`.

## Full-view Evidence

The final comparison confirms the selected direction's hierarchy: a compact mode rail, dominant commentary reader, contextual move/range header, persistent status strip, and a compact follow-up composer. The implementation intentionally uses native Swing and Windows title chrome while retaining the reference's neutral surfaces, jade accent, coral stop action, and restrained 8 px-or-less corner treatment.

Required fidelity surfaces:

- Fonts and typography: system CJK fallback is sharp at 100%, 150%, and 200%; title, heading, body, muted helper text, status, and button weights remain distinct. Letter spacing is unchanged and all localized text wraps or clips intentionally.
- Spacing and layout rhythm: reader remains dominant at the `760 x 540` logical minimum; header, rail, context bar, status strip, and composer do not overlap. The final prompt label, field, and action share one row as in the source.
- Colors and visual tokens: neutral white/gray surfaces, theme-aware borders, jade selection/primary action, coral stop action, and semantic status colors maintain contrast in the active look and feel.
- Image and icon quality: the mode, commentary, and status icons are resolution-independent Java2D icons. They remain crisp at all tested scale factors and inherit the active theme foreground instead of relying on low-resolution raster assets.
- Copy and content: labels are product-facing and localized; internal provider details remain in the status/model area. Empty, loading, output, warning, error, and saved-SGF states have explicit copy.

## Focused Evidence

- Saved commentary output: `docs/qa/ai-commentary-redesign/saved-output-150.png`
  - Markdown heading, paragraphs, bold text, bullets, blockquote, status, and scrolling render correctly in the real Windows EXE.
- Minimum window: `docs/qa/ai-commentary-redesign/minimum-window-150.png`
  - `1140 x 810` physical / `760 x 540` logical; persistent controls remain visible and long content scrolls inside the reader.
- Keyboard focus: `docs/qa/ai-commentary-redesign/keyboard-focus-150.png`
  - Tab focus is visible in the range spinner without resizing the layout.
- Scale captures:
  - 100%: `docs/qa/ai-commentary-redesign/output-100.png` (`900 x 680`).
  - 150%: implementation half of `docs/qa/ai-commentary-redesign/option-1-comparison.png` (`1350 x 1020`).
  - 200%: `docs/qa/ai-commentary-redesign/output-200.png` (`1800 x 1360`).

## Comparison History

1. Baseline review found P1 hierarchy and density problems: equal-weight controls, cramped text output, weak empty/loading states, and no stable mode model. The dialog was split into a presentation-only view and business-logic controller, then rebuilt around the selected three-region design.
2. Candidate v2 had P2 fidelity gaps: the mode rail lacked recognizable icons, the empty reader lacked a visual anchor, the settings asset rendered incorrectly, and spinner values could disappear after model replacement at 150%. Theme-aware vector icons, the commentary glyph, the correct gear asset, and post-model spinner styling fixed these issues. Post-fix evidence: `candidate-ai-commentary-150-v3.png`.
3. Candidate v3 had one P2 density mismatch: the follow-up label occupied a separate row and reduced reading height. The label, field, and Ask action were consolidated into one row. Post-fix evidence: `candidate-ai-commentary-150-v4.png` and `comparison-option1-vs-candidate-v4.png`.
4. Final comparison found no actionable P0, P1, or P2 differences. Native title-bar color and minor system-font metric differences are expected platform behavior, not design drift.

## Interaction And Accessibility

- Tab moves into the range input, Shift+Tab returns to the mode control, and focus is visibly indicated.
- Space activates the selected mode and opens settings when an API key is required.
- Escape closes settings and then the commentary dialog.
- Mode controls, range fields, settings, stop, follow-up input, Ask action, progress, status, and model status expose stable accessible names or descriptions.
- Status changes are conveyed by text as well as color; loading also exposes progress state.

## Result

No actionable P0/P1/P2 findings remain. The real Windows EXE was inspected at 100%, 150%, 200%, minimum size, empty state, saved-output state, and keyboard interaction states.

final result: passed

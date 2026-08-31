# AI Commentary Design QA

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

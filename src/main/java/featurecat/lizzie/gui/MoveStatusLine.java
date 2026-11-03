package featurecat.lizzie.gui;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.Locale;

/**
 * Draws the status line under the win-rate bar: who leads in plain text, then what the previous
 * move changed, larger, in bold and green or red, so the cost of the last move reads at a glance
 * even when the rest of the panel is small.
 */
final class MoveStatusLine {
  static final Color GAIN = new Color(0x7C, 0xD9, 0x92);
  static final Color LOSS = new Color(0xFF, 0x7A, 0x6E);

  /** How much larger than the rest of the line the previous move may be drawn. */
  static final float PREVIOUS_MOVE_SCALE = 1.5f;

  private static final String GAP = "  ";
  private static final float MIN_SIZE = 6f;
  private static final float MIN_RATIO = 1.2f;

  record Layout(Font leadFont, Font previousFont, int previousX, int baseline) {}

  private MoveStatusLine() {}

  /**
   * Draws {@code lead} and {@code previousMove} side by side, starting at {@code x} and centred on
   * {@code centerY}, within {@code maxWidth} and {@code maxHeight}. {@code change} is the change
   * the previous move made for the side that played it and picks the colour.
   */
  static void draw(
      Graphics2D g,
      Font base,
      int x,
      int centerY,
      float maxHeight,
      double maxWidth,
      int maxSize,
      String lead,
      String previousMove,
      double change) {
    Layout layout = layout(g, base, x, centerY, maxHeight, maxWidth, maxSize, lead, previousMove);
    Color leadColor = g.getColor();
    if (!lead.isEmpty()) {
      g.setFont(layout.leadFont());
      g.drawString(lead, x, layout.baseline());
    }
    if (!previousMove.isEmpty()) {
      g.setFont(layout.previousFont());
      g.setColor(color(change, leadColor));
      g.drawString(previousMove, layout.previousX(), layout.baseline());
      g.setColor(leadColor);
    }
  }

  static Layout layout(
      Graphics2D g,
      Font base,
      int x,
      int centerY,
      float maxHeight,
      double maxWidth,
      int maxSize,
      String lead,
      String previousMove) {
    String leadText = lead.isEmpty() || previousMove.isEmpty() ? lead : lead + GAP;
    Font leadFont = fit(g, base, Font.PLAIN, leadText, maxHeight, maxWidth, maxSize);
    Font previousFont =
        fit(
            g,
            base,
            Font.BOLD,
            previousMove,
            maxHeight,
            maxWidth,
            Math.round(maxSize * PREVIOUS_MOVE_SCALE));
    // Side by side they may be too wide. Shrink the lead until the previous move is a fifth larger
    // than it, then shrink them in turn, so the previous move stays the larger.
    while (width(g, leadFont, leadText) + width(g, previousFont, previousMove) > maxWidth
        && previousFont.getSize2D() > MIN_SIZE) {
      if (leadFont.getSize2D() > MIN_SIZE
          && previousFont.getSize2D() <= leadFont.getSize2D() * MIN_RATIO) {
        leadFont = leadFont.deriveFont(leadFont.getSize2D() - 1f);
      } else {
        previousFont = previousFont.deriveFont(previousFont.getSize2D() - 1f);
      }
    }
    FontMetrics metrics = g.getFontMetrics(previousMove.isEmpty() ? leadFont : previousFont);
    int baseline = centerY + (metrics.getAscent() - metrics.getDescent()) / 2;
    return new Layout(leadFont, previousFont, x + width(g, leadFont, leadText), baseline);
  }

  /** Green for a gain, red for a loss, and {@code even} when the change rounds to zero. */
  static Color color(double change, Color even) {
    if ("0.0".equals(String.format(Locale.ENGLISH, "%.1f", Math.abs(change)))) return even;
    return change > 0 ? GAIN : LOSS;
  }

  private static Font fit(
      Graphics2D g,
      Font base,
      int style,
      String text,
      float maxHeight,
      double maxWidth,
      int maxSize) {
    return text.isEmpty()
        ? FittedFont.withKerning(base.deriveFont(style, (float) maxSize))
        : FittedFont.fit(g, base, style, text, maxHeight, maxWidth, maxSize);
  }

  private static int width(Graphics2D g, Font font, String text) {
    return text.isEmpty() ? 0 : g.getFontMetrics(font).stringWidth(text);
  }
}

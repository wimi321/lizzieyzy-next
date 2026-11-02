package featurecat.lizzie.gui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;

/**
 * The players' names under the board, drawn the way LizzieYzy always drew them: two overlapping
 * stones in the middle with the side to move on top, Black's name in black type to their left and
 * White's in white type with a dark edge to their right, straight on the board.
 *
 * <p>For a while they sat on a light plate. Users found it glaring, and it filled the bottom of the
 * board under the first-line stones, so the plate is gone again.
 */
final class BoardNamePlate {
  private static final String ELLIPSIS = "…";

  private BoardNamePlate() {}

  /** The two stones between the names; the side to move is drawn on top. */
  static void paintStones(
      Graphics2D g, int blackX, int whiteX, int top, int size, boolean blackOnTop) {
    if (blackOnTop) {
      g.setColor(Color.WHITE);
      g.fillOval(whiteX, top, size, size);
      g.setColor(Color.BLACK);
      g.fillOval(blackX, top, size, size);
    } else {
      g.setColor(Color.BLACK);
      g.fillOval(blackX, top, size, size);
      g.setColor(Color.WHITE);
      g.fillOval(whiteX, top, size, size);
    }
  }

  /** Black's name in black type. */
  static void paintBlackName(Graphics2D g, Font font, String name, double x, double baseline) {
    if (name.isEmpty()) return;
    g.setColor(Color.BLACK);
    g.fill(outline(g, font, name, x, baseline));
  }

  /** White's name in white type, edged in black so it stands out on the wood. */
  static void paintWhiteName(Graphics2D g, Font font, String name, double x, double baseline) {
    if (name.isEmpty()) return;
    Shape text = outline(g, font, name, x, baseline);
    g.setColor(Color.BLACK);
    g.setStroke(new BasicStroke(2));
    g.draw(text);
    g.setColor(Color.WHITE);
    g.fill(text);
  }

  /** The name, cut short with an ellipsis when it is wider than {@code maxWidth}. */
  static String fit(String name, FontMetrics metrics, int maxWidth) {
    if (metrics.stringWidth(name) <= maxWidth) {
      return name;
    }
    int end = name.length();
    while (end > 1 && metrics.stringWidth(name.substring(0, end) + ELLIPSIS) > maxWidth) {
      end = name.offsetByCodePoints(end, -1);
    }
    return name.substring(0, end).trim() + ELLIPSIS;
  }

  /**
   * Where a click lands on the names: from the start of Black's name to the end of White's, the
   * stones between them included, from the top of the type to the bottom.
   */
  static Rectangle hitBox(int blackLeft, int whiteRight, int baseline, int ascent, int descent) {
    return new Rectangle(blackLeft, baseline - ascent, whiteRight - blackLeft, ascent + descent);
  }

  /** A name as glyph outlines, which keeps every glyph of mixed-script names on macOS. */
  private static Shape outline(Graphics2D g, Font font, String name, double x, double baseline) {
    TextLayout layout = new TextLayout(name, font, g.getFontRenderContext());
    return layout.getOutline(AffineTransform.getTranslateInstance(x, baseline));
  }
}

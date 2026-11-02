package featurecat.lizzie.gui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import javax.swing.JComponent;

/**
 * Black's and White's win rates side by side in large figures, for 黑白胜率大字: on a black and a white
 * card ({@link Style#PLAIN}), or as glowing seven-segment digits ({@link Style#LED}). Figures of an
 * earlier position, shown while the current one has no analysis yet, are dimmed.
 */
final class BigWinratePanel extends JComponent {
  enum Style {
    PLAIN,
    LED
  }

  private static final Color PLAIN_BACKGROUND = new Color(0xE9, 0xEB, 0xEE);
  private static final Color PLAIN_BLACK = new Color(0x12, 0x13, 0x16);
  private static final Color PLAIN_EDGE = new Color(0xCF, 0xD3, 0xD8);
  private static final Color LED_BACKGROUND = new Color(0x0B, 0x0F, 0x14);
  private static final Color LED_CARD = new Color(0x06, 0x08, 0x0B);
  static final Color LED_BLACK_SIDE = new Color(0xFF, 0x6B, 0x57);
  static final Color LED_WHITE_SIDE = new Color(0x66, 0xE3, 0xF2);

  /**
   * The lit segments of each digit. Segments a to f go round from the top (a) clockwise, so b and c
   * are the right side, d the bottom, e and f the left side; g is the middle bar.
   */
  private static final String[] SEGMENTS = {
    "abcdef", "bc", "abged", "abgcd", "fgbc", "afgcd", "afgecd", "abc", "abcdefg", "abcdfg"
  };

  private final String blackLabel;
  private final String whiteLabel;
  private final Font numbers;
  private final Font labels;
  private WinrateReading reading = WinrateReading.UNKNOWN;
  private Style style = Style.PLAIN;

  /**
   * @param numbers the font of the figures, such as the win-rate font
   * @param labels the font of the side names, which must cover the interface language
   */
  BigWinratePanel(String blackLabel, String whiteLabel, Font numbers, Font labels) {
    this.blackLabel = blackLabel;
    this.whiteLabel = whiteLabel;
    this.numbers = numbers == null ? new Font(Font.SANS_SERIF, Font.BOLD, 12) : numbers;
    this.labels = labels == null ? new Font(Font.DIALOG, Font.PLAIN, 12) : labels;
    setOpaque(true);
  }

  WinrateReading reading() {
    return reading;
  }

  Style style() {
    return style;
  }

  void setReading(WinrateReading value) {
    WinrateReading next = value == null ? WinrateReading.UNKNOWN : value;
    if (!next.equals(reading)) {
      reading = next;
      repaint();
    }
  }

  void setStyle(Style value) {
    style = value == null ? Style.PLAIN : value;
    repaint();
  }

  /** The figure a side shows: its whole percent, or "--" without analysis. */
  static String figure(WinrateReading reading, boolean black) {
    if (!reading.known()) return "--";
    return String.valueOf(black ? reading.blackPercent() : reading.whitePercent());
  }

  @Override
  protected void paintComponent(Graphics graphics) {
    Graphics2D g = (Graphics2D) graphics.create();
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setRenderingHint(
          RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      int width = getWidth();
      int height = getHeight();
      int pad = Math.max(8, Math.min(width, height) / 18);
      int gap = Math.max(6, width / 30);
      int cardWidth = Math.max(1, (width - 2 * pad - gap) / 2);
      int cardHeight = Math.max(1, height - 2 * pad);
      int rightX = pad + cardWidth + gap;
      if (style == Style.LED) {
        g.setColor(LED_BACKGROUND);
        g.fillRect(0, 0, width, height);
        paintLedCard(g, pad, pad, cardWidth, cardHeight, true);
        paintLedCard(g, rightX, pad, cardWidth, cardHeight, false);
      } else {
        g.setColor(PLAIN_BACKGROUND);
        g.fillRect(0, 0, width, height);
        g.setColor(PLAIN_BLACK);
        g.fillRect(pad + cardWidth + gap / 2, pad, 1, cardHeight);
        paintPlainCard(g, pad, pad, cardWidth, cardHeight, true);
        paintPlainCard(g, rightX, pad, cardWidth, cardHeight, false);
      }
    } finally {
      g.dispose();
    }
  }

  private void paintPlainCard(Graphics2D g, int x, int y, int width, int height, boolean black) {
    g.setColor(black ? PLAIN_BLACK : Color.WHITE);
    g.fillRect(x, y, width, height);
    if (!black) {
      g.setColor(PLAIN_EDGE);
      g.drawRect(x, y, width - 1, height - 1);
    }
    Color ink = black ? Color.WHITE : PLAIN_BLACK;
    paintLabel(g, black ? blackLabel : whiteLabel, x, y, height, withAlpha(ink, 170));
    String figure = figure(reading, black);
    Font font = fitted(g, figure, width * 0.8, height * 0.62);
    g.setFont(font);
    FontMetrics metrics = g.getFontMetrics();
    int digitHeight = digitHeight(metrics);
    g.setColor(reading.current() ? ink : withAlpha(ink, 150));
    g.drawString(
        figure,
        x + (width - metrics.stringWidth(figure)) / 2,
        y + (height + digitHeight) / 2 + height / 30);
  }

  private void paintLedCard(Graphics2D g, int x, int y, int width, int height, boolean black) {
    Color side = black ? LED_BLACK_SIDE : LED_WHITE_SIDE;
    int arc = Math.max(6, height / 12);
    g.setColor(LED_CARD);
    g.fillRoundRect(x, y, width, height, arc, arc);
    g.setColor(withAlpha(side, 200));
    g.setStroke(new BasicStroke(Math.max(1.5f, height / 160f)));
    g.drawRoundRect(x, y, width - 1, height - 1, arc, arc);
    paintLabel(g, black ? blackLabel : whiteLabel, x, y, height, withAlpha(side, 200));
    String figure = figure(reading, black);
    int cells = Math.max(2, figure.length());
    double cellHeight = Math.min(height * 0.62, width * 0.78 / (cells * 0.68));
    double cellWidth = cellHeight * 0.55;
    double spacing = cellWidth * 0.18;
    double total = cells * cellWidth + (cells - 1) * spacing;
    double left = x + (width - total) / 2 + cellHeight * 0.04;
    double top = y + (height - cellHeight) / 2 + height * 0.04;
    Color lit = reading.current() ? side : withAlpha(side, 170);
    // A short figure is right-aligned, as on a clock; unused cells keep their unlit segments.
    int offset = cells - figure.length();
    for (int cell = 0; cell < cells; cell++) {
      char c = cell < offset ? ' ' : figure.charAt(cell - offset);
      paintDigit(g, c, left + cell * (cellWidth + spacing), top, cellWidth, cellHeight, lit, side);
    }
  }

  private static void paintDigit(
      Graphics2D g,
      char c,
      double x,
      double y,
      double width,
      double height,
      Color lit,
      Color side) {
    String on = c == '-' ? "g" : Character.isDigit(c) ? SEGMENTS[c - '0'] : "";
    double t = width * 0.19;
    AffineTransform slant = AffineTransform.getShearInstance(-0.08, 0);
    slant.preConcatenate(AffineTransform.getTranslateInstance(0.08 * (y + height), 0));
    for (char segment : "abcdefg".toCharArray()) {
      Shape shape = slant.createTransformedShape(segment(segment, x, y, width, height, t));
      if (on.indexOf(segment) >= 0) {
        // A soft halo, then the lit bar.
        g.setColor(withAlpha(lit, 28));
        g.setStroke(
            new BasicStroke((float) (t * 0.9), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(shape);
        g.setColor(withAlpha(lit, 60));
        g.setStroke(
            new BasicStroke((float) (t * 0.4), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(shape);
        g.setColor(lit);
        g.fill(shape);
      } else {
        g.setColor(withAlpha(side, 22));
        g.fill(shape);
      }
    }
  }

  /** One segment of a seven-segment digit in the cell at x, y, as a six-sided bar. */
  static Polygon segment(char segment, double x, double y, double width, double height, double t) {
    double gap = t * 0.22;
    double left = x + t / 2;
    double right = x + width - t / 2;
    double top = y + t / 2;
    double middle = y + height / 2;
    double bottom = y + height - t / 2;
    switch (segment) {
      case 'a':
        return horizontal(left + gap, right - gap, top, t);
      case 'g':
        return horizontal(left + gap, right - gap, middle, t);
      case 'd':
        return horizontal(left + gap, right - gap, bottom, t);
      case 'f':
        return vertical(left, top + gap, middle - gap, t);
      case 'b':
        return vertical(right, top + gap, middle - gap, t);
      case 'e':
        return vertical(left, middle + gap, bottom - gap, t);
      default:
        return vertical(right, middle + gap, bottom - gap, t);
    }
  }

  private static Polygon horizontal(double from, double to, double y, double t) {
    double h = t / 2;
    return polygon(
        new double[] {from, from + h, to - h, to, to - h, from + h},
        new double[] {y, y - h, y - h, y, y + h, y + h});
  }

  private static Polygon vertical(double x, double from, double to, double t) {
    double h = t / 2;
    return polygon(
        new double[] {x, x + h, x + h, x, x - h, x - h},
        new double[] {from, from + h, to - h, to, to - h, from + h});
  }

  private static Polygon polygon(double[] xs, double[] ys) {
    Polygon polygon = new Polygon();
    for (int i = 0; i < xs.length; i++) {
      polygon.addPoint((int) Math.round(xs[i]), (int) Math.round(ys[i]));
    }
    return polygon;
  }

  private void paintLabel(Graphics2D g, String label, int x, int y, int height, Color color) {
    Font font = labels.deriveFont(Font.PLAIN, Math.max(11f, height / 10f));
    g.setFont(font);
    g.setColor(color);
    FontMetrics metrics = g.getFontMetrics();
    int inset = Math.max(6, height / 16);
    g.drawString(label, x + inset, y + inset + metrics.getAscent());
  }

  /** The numbers font at the largest size that keeps {@code text} within the given box. */
  private Font fitted(Graphics2D g, String text, double maxWidth, double maxDigitHeight) {
    Font probe = numbers.deriveFont(Font.BOLD, 100f);
    FontMetrics metrics = g.getFontMetrics(probe);
    double byWidth = 100.0 * maxWidth / Math.max(1, metrics.stringWidth(text));
    double byHeight = 100.0 * maxDigitHeight / Math.max(1, digitHeight(metrics));
    return numbers.deriveFont(Font.BOLD, (float) Math.max(8.0, Math.min(byWidth, byHeight)));
  }

  /** The height of a digit above the baseline. */
  private static int digitHeight(FontMetrics metrics) {
    return Math.max(1, metrics.getAscent() - metrics.getDescent());
  }

  private static Color withAlpha(Color color, int alpha) {
    return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
  }
}

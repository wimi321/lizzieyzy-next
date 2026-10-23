package featurecat.lizzie.gui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.util.function.Supplier;
import javax.swing.JComponent;

/** The top of the AI Coach panel: the opponent and its state, the players, the win chance. */
final class CoachSummaryView extends JComponent {
  private static final int GAP = 12;
  private static final int WIN_CHANCE_HEIGHT = 124;

  private final Supplier<CoachPanelState> state;

  CoachSummaryView(Supplier<CoachPanelState> state) {
    this.state = state;
    setOpaque(false);
  }

  int heightFor() {
    return heightFor(getWidth());
  }

  int heightFor(int width) {
    return layout(null, width);
  }

  @Override
  protected void paintComponent(Graphics graphics) {
    Graphics2D g = (Graphics2D) graphics.create();
    try {
      CoachPaint.smooth(g);
      layout(g, getWidth());
    } finally {
      g.dispose();
    }
  }

  /** Draws when {@code g} is set, and returns the height either way. */
  private int layout(Graphics2D g, int width) {
    CoachPanelState s = state.get();
    int y = header(g, s, width);
    y = players(g, s, width, y + GAP);
    if (s.winChance() != null) {
      if (g != null) {
        winChance(g, s.winChance(), width, y + GAP);
      }
      y += GAP + WIN_CHANCE_HEIGHT;
    }
    return y;
  }

  private int header(Graphics2D g, CoachPanelState s, int width) {
    if (g != null) {
      g.setColor(CoachPanel.RAISED);
      g.fillOval(0, 0, 44, 44);
      CoachPaint.stone(g, 22, 22, 13, s.aiPlaysBlack());
      Font pillFont = CoachPaint.font(s.status(), Font.BOLD, 12.5f);
      FontMetrics pill = g.getFontMetrics(pillFont);
      int pillWidth = pill.stringWidth(s.status()) + 20;
      int pillX = width - pillWidth;
      Color[] tone = tone(s.tone());
      g.setColor(tone[0]);
      g.fillRoundRect(pillX, 9, pillWidth, 26, 26, 26);
      g.setColor(tone[1]);
      g.setFont(pillFont);
      g.drawString(s.status(), pillX + 10, 9 + (26 - pill.getHeight()) / 2 + pill.getAscent());
      int textWidth = pillX - 56 - 8;
      g.setColor(CoachPanel.TEXT);
      g.setFont(CoachPaint.font(s.title(), Font.BOLD, 17f));
      g.drawString(CoachPaint.fit(g.getFontMetrics(), s.title(), textWidth), 56, 20);
      g.setColor(CoachPanel.MUTED);
      g.setFont(CoachPaint.font(s.subtitle(), Font.PLAIN, 12.5f));
      g.drawString(CoachPaint.fit(g.getFontMetrics(), s.subtitle(), textWidth), 56, 39);
    }
    return 44;
  }

  private int players(Graphics2D g, CoachPanelState s, int width, int y) {
    int chipWidth = (width - 8) / 2;
    if (g != null) {
      chip(g, 0, y, chipWidth, !s.aiPlaysBlack(), s.humanLine(), s.humanDetail());
      chip(g, chipWidth + 8, y, width - chipWidth - 8, s.aiPlaysBlack(), s.aiLine(), s.aiDetail());
      g.setColor(CoachPanel.MUTED);
      g.setFont(CoachPaint.font(s.timeLine(), Font.PLAIN, 12f));
      g.drawString(CoachPaint.fit(g.getFontMetrics(), s.timeLine(), width), 2, y + 40 + 20);
    }
    return y + 40 + 26;
  }

  private void chip(
      Graphics2D g, int x, int y, int width, boolean black, String label, String detail) {
    g.setColor(CoachPanel.SURFACE);
    g.fillRoundRect(x, y, width, 40, 20, 20);
    CoachPaint.stone(g, x + 18, y + 20, 6, black);
    g.setFont(CoachPaint.font(detail, Font.PLAIN, 12.5f));
    int detailWidth = g.getFontMetrics().stringWidth(detail);
    g.setColor(CoachPanel.MUTED);
    g.drawString(detail, x + width - 12 - detailWidth, y + 25);
    g.setColor(CoachPanel.TEXT);
    g.setFont(CoachPaint.font(label, Font.PLAIN, 13f));
    g.drawString(
        CoachPaint.fit(g.getFontMetrics(), label, width - 30 - detailWidth - 18), x + 30, y + 25);
  }

  private void winChance(Graphics2D g, CoachPanelState.WinChance chance, int width, int y) {
    double[] values = chance.values();
    double current = values.length == 0 ? Double.NaN : values[values.length - 1];
    g.setColor(CoachPanel.SURFACE);
    g.fillRoundRect(0, y, width, WIN_CHANCE_HEIGHT, 24, 24);
    String label = text("CoachPanel.winChance", "Your win chance");
    g.setColor(CoachPanel.TEXT);
    g.setFont(CoachPaint.font(label, Font.BOLD, 14f));
    g.drawString(label, 14, y + 27);
    int labelWidth = g.getFontMetrics().stringWidth(label);
    String percent = Double.isNaN(current) ? "—" : Math.round(current) + "%";
    Font valueFont = CoachPaint.font(percent, Font.BOLD, 24f);
    int valueWidth = g.getFontMetrics(valueFont).stringWidth(percent);
    g.setColor(CoachPanel.MUTED);
    g.setFont(CoachPaint.font(chance.caption(), Font.PLAIN, 11.5f));
    g.drawString(
        CoachPaint.fit(
            g.getFontMetrics(), chance.caption(), width - 28 - labelWidth - 8 - valueWidth - 8),
        14 + labelWidth + 8,
        y + 27);
    g.setColor(CoachPanel.TEXT);
    g.setFont(valueFont);
    g.drawString(percent, width - 14 - valueWidth, y + 31);
    int trackX = 14;
    int trackWidth = width - 28;
    int trackY = y + 44;
    g.setColor(CoachPaint.TRACK);
    g.fillRoundRect(trackX, trackY, trackWidth, 8, 8, 8);
    if (!Double.isNaN(current)) {
      int fill = (int) Math.round(trackWidth * Math.max(0.0, Math.min(100.0, current)) / 100.0);
      if (fill > 0) {
        g.setColor(CoachPanel.ACCENT);
        g.fillRoundRect(trackX, trackY, Math.max(8, fill), 8, 8, 8);
      }
    }
    g.setColor(new Color(236, 239, 238, 140));
    g.fillRect(trackX + trackWidth / 2 - 1, trackY - 3, 2, 14);
    if (!Double.isNaN(chance.marked())) {
      // The chance before the 恶手, in red, so the drop shows on the bar.
      int markX =
          trackX
              + (int)
                  Math.round(trackWidth * Math.max(0.0, Math.min(100.0, chance.marked())) / 100.0);
      g.setColor(CoachPanel.DANGER);
      g.fillRoundRect(markX - 1, trackY - 4, 3, 16, 2, 2);
    }
    curve(g, values, trackX, y + 64, trackWidth, 46);
  }

  private void curve(Graphics2D g, double[] values, int x, int y, int width, int height) {
    Graphics2D dashed = (Graphics2D) g.create();
    dashed.setColor(new Color(0x3a, 0x41, 0x44));
    dashed.setStroke(
        new BasicStroke(
            1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[] {3f, 4f}, 0f));
    dashed.drawLine(x, y + height / 2, x + width, y + height / 2);
    dashed.dispose();
    if (values.length < 2) {
      return;
    }
    Path2D line = new Path2D.Double();
    Path2D area = new Path2D.Double();
    for (int i = 0; i < values.length; i++) {
      double px = x + width * (double) i / (values.length - 1);
      double py = y + height * (1.0 - Math.max(0.0, Math.min(100.0, values[i])) / 100.0);
      if (i == 0) {
        line.moveTo(px, py);
        area.moveTo(px, y + height);
        area.lineTo(px, py);
      } else {
        line.lineTo(px, py);
        area.lineTo(px, py);
      }
    }
    area.lineTo(x + width, y + height);
    area.closePath();
    g.setPaint(
        new GradientPaint(
            0, y, new Color(61, 190, 170, 70), 0, y + height, new Color(61, 190, 170, 0)));
    g.fill(area);
    g.setColor(CoachPanel.ACCENT);
    g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    g.draw(line);
    g.setStroke(new BasicStroke(1f));
  }

  private static Color[] tone(CoachPanelState.Tone tone) {
    switch (tone) {
      case ACTIVE:
        return new Color[] {new Color(61, 190, 170, 42), CoachPanel.ACCENT_TEXT};
      case WARNING:
        return new Color[] {new Color(242, 193, 78, 42), CoachPanel.WARNING};
      case ALERT:
        return new Color[] {new Color(255, 122, 110, 42), CoachPanel.DANGER};
      case DONE:
        return new Color[] {CoachPanel.RAISED, CoachPanel.TEXT};
      case WAITING:
      default:
        return new Color[] {CoachPanel.RAISED, CoachPanel.MUTED};
    }
  }

  private static String text(String key, String fallback) {
    return CoachPanel.text(key, fallback);
  }
}

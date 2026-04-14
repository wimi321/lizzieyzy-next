package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import javax.swing.*;

public class SidebarHeaderPanel extends JPanel {
  private SidebarPanel parentPanel;
  private ProblemListSnapshot currentSnapshot;

  private static final Color TEXT_NORMAL = new Color(255, 255, 255, 128);
  private static final Color TEXT_SELECTED = new Color(255, 255, 255, 255);
  private static final Color BG_TRACK = new Color(255, 255, 255, 15);
  private static final Color BG_THUMB = new Color(255, 255, 255, 40);
  private static final Color PILL_BG = new Color(255, 255, 255, 20);

  public SidebarHeaderPanel(SidebarPanel parentPanel) {
    this.parentPanel = parentPanel;
    setOpaque(false);
    setPreferredSize(new Dimension(200, 88));

    addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent e) {
            int x = e.getX();
            int y = e.getY();

            // Click on [ 评论 | 问题手 ]
            if (y >= 12 && y <= 36 && x >= 10 && x <= 130) {
              if (x < 70) {
                parentPanel.switchTo("COMMENTS");
              } else {
                parentPanel.switchTo("BLUNDERS");
              }
            }

            // Click on [ 黑 | 白 ]
            if (y >= 44 && y <= 66 && x >= 10 && x <= 110) {
              if (x < 60) {
                Lizzie.frame.setProblemListSideFilter(ProblemListSideFilter.BLACK);
              } else {
                Lizzie.frame.setProblemListSideFilter(ProblemListSideFilter.WHITE);
              }
            }
          }
        });
  }

  public void updateSnapshot(ProblemListSnapshot snapshot) {
    this.currentSnapshot = snapshot;
    repaint();
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    Graphics2D g2 = (Graphics2D) g.create();
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    boolean showBlunders = Lizzie.config.isShowingBlunderTabel;
    ProblemListSideFilter sideFilter = Lizzie.frame.getProblemListSideFilter();
    if (sideFilter == ProblemListSideFilter.ALL) sideFilter = ProblemListSideFilter.BLACK;

    g2.setFont(new Font(Lizzie.config.uiFontName, Font.BOLD, 12));
    FontMetrics fm = g2.getFontMetrics();

    if (!Lizzie.config.isAppleStyle) {
      // Classic flat UI
      g2.setColor(new Color(50, 50, 50));
      g2.fillRect(0, 0, getWidth(), getHeight());

      int x = 10;
      int y = 25;
      g2.setColor(showBlunders ? TEXT_NORMAL : TEXT_SELECTED);
      g2.drawString("评论", x, y);
      g2.setColor(TEXT_NORMAL);
      g2.drawString("｜", x + 30, y);
      g2.setColor(showBlunders ? TEXT_SELECTED : TEXT_NORMAL);
      g2.drawString("问题手", x + 45, y);

      if (currentSnapshot != null) {
        String pillText = currentSnapshot.analysisRunning ? "..." : "";
        pillText += currentSnapshot.analyzedMoves + "/" + currentSnapshot.totalMoves;
        g2.setColor(TEXT_NORMAL);
        g2.drawString(pillText, getWidth() - fm.stringWidth(pillText) - 10, y);
      }

      if (showBlunders) {
        y = 55;
        g2.setColor(sideFilter == ProblemListSideFilter.BLACK ? TEXT_SELECTED : TEXT_NORMAL);
        g2.drawString("黑棋", x, y);
        g2.setColor(TEXT_NORMAL);
        g2.drawString("｜", x + 30, y);
        g2.setColor(sideFilter == ProblemListSideFilter.WHITE ? TEXT_SELECTED : TEXT_NORMAL);
        g2.drawString("白棋", x + 45, y);
      }
      g2.dispose();
      return;
    }

    Color accent = glassAccentColor();
    Color topWash = new Color(255, 255, 255, 18);
    g2.setPaint(
        new LinearGradientPaint(
            0,
            0,
            0,
            getHeight(),
            new float[] {0.0f, 0.55f, 1.0f},
            new Color[] {topWash, new Color(255, 255, 255, 8), new Color(255, 255, 255, 0)}));
    g2.fillRoundRect(0, 0, getWidth(), getHeight() + 12, 18, 18);

    g2.setColor(new Color(255, 255, 255, 22));
    g2.drawLine(4, getHeight() - 1, getWidth() - 8, getHeight() - 1);

    // 1. [ 评论 | 问题手 ] (Apple Style Segmented Control)
    int x = 10;
    int y = 14;
    int segW = 132;
    int segH = 30;
    int arc = 15;
    int baseline = y + segH / 2 + fm.getAscent() / 2 - 1;

    g2.setColor(new Color(255, 255, 255, 24));
    g2.fillRoundRect(x, y, segW, segH, arc, arc);
    g2.setColor(new Color(255, 255, 255, 18));
    g2.drawRoundRect(x, y, segW - 1, segH - 1, arc, arc);

    int halfW = segW / 2;
    g2.setColor(showBlunders ? withAlpha(accent, 132) : new Color(255, 255, 255, 58));
    if (!showBlunders) {
      g2.fillRoundRect(x + 2, y + 2, halfW - 2, segH - 4, arc - 2, arc - 2);
    } else {
      g2.fillRoundRect(x + halfW, y + 2, halfW - 2, segH - 4, arc - 2, arc - 2);
    }

    g2.setColor(!showBlunders ? TEXT_SELECTED : TEXT_NORMAL);
    String t1 = "评论";
    g2.drawString(t1, x + (halfW - fm.stringWidth(t1)) / 2, baseline);

    g2.setColor(showBlunders ? TEXT_SELECTED : TEXT_NORMAL);
    String t2 = "问题手";
    g2.drawString(t2, x + halfW + (halfW - fm.stringWidth(t2)) / 2, baseline);

    // 2. Progress pill
    if (currentSnapshot != null) {
      String pillText = currentSnapshot.analysisRunning ? "⏳ " : "☄️ ";
      pillText += currentSnapshot.analyzedMoves + "/" + currentSnapshot.totalMoves;

      int textWidth = fm.stringWidth(pillText);
      int pillX = getWidth() - textWidth - 24;
      int pillY = y;
      int pillWidth = textWidth + 16;
      int pillHeight = 28;

      g2.setColor(
          currentSnapshot.analysisRunning ? new Color(255, 184, 77, 48) : withAlpha(accent, 54));
      g2.fillRoundRect(pillX, pillY, pillWidth, pillHeight, arc, arc);
      g2.setColor(
          currentSnapshot.analysisRunning ? new Color(255, 213, 153, 72) : withAlpha(accent, 92));
      g2.drawRoundRect(pillX, pillY, pillWidth - 1, pillHeight - 1, arc, arc);

      g2.setColor(TEXT_SELECTED);
      g2.drawString(pillText, pillX + 8, baseline + 1);
    }

    // 3. [ 黑 | 白 ]
    if (showBlunders) {
      y = 52;
      segW = 112;
      segH = 24;
      halfW = segW / 2;
      arc = 12;
      baseline = y + segH / 2 + fm.getAscent() / 2 - 1;

      g2.setColor(new Color(255, 255, 255, 20));
      g2.fillRoundRect(x, y, segW, segH, arc, arc);
      g2.setColor(new Color(255, 255, 255, 14));
      g2.drawRoundRect(x, y, segW - 1, segH - 1, arc, arc);

      g2.setColor(withAlpha(accent, 118));
      if (sideFilter == ProblemListSideFilter.BLACK) {
        g2.fillRoundRect(x + 2, y + 2, halfW - 2, segH - 4, arc - 2, arc - 2);
      } else {
        g2.fillRoundRect(x + halfW, y + 2, halfW - 2, segH - 4, arc - 2, arc - 2);
      }

      g2.setColor(sideFilter == ProblemListSideFilter.BLACK ? TEXT_SELECTED : TEXT_NORMAL);
      String b1 = "⚫ 黑棋";
      g2.drawString(b1, x + (halfW - fm.stringWidth(b1)) / 2, baseline);

      g2.setColor(sideFilter == ProblemListSideFilter.WHITE ? TEXT_SELECTED : TEXT_NORMAL);
      String b2 = "⚪ 白棋";
      g2.drawString(b2, x + halfW + (halfW - fm.stringWidth(b2)) / 2, baseline);
    }

    g2.dispose();
  }

  private Color glassAccentColor() {
    return Lizzie.config != null && Lizzie.config.theme != null
        ? Lizzie.config.theme.glassAccentColor()
        : new Color(96, 165, 250);
  }

  private Color withAlpha(Color color, int alpha) {
    return new Color(
        color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha)));
  }
}

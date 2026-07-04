package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import javax.swing.*;

public class SidebarHeaderPanel extends JPanel {
  private final SidebarPanel parentPanel;
  private ProblemListSnapshot currentSnapshot;

  private static final Color TEXT_NORMAL = new Color(255, 255, 255, 128);
  private static final Color TEXT_SELECTED = new Color(255, 255, 255, 255);
  private static final Color BG_TRACK = new Color(255, 255, 255, 15);
  private static final Color BG_THUMB = new Color(255, 255, 255, 40);
  private static final Color PILL_BG = new Color(255, 255, 255, 20);
  private static final int NO_SEGMENT = -1;
  private static final int FIRST_SEGMENT = 0;
  private static final int SECOND_SEGMENT = 1;
  private static final int CONTROL_X = 10;
  private static final int APPLE_PRIMARY_Y = 14;
  private static final int APPLE_PRIMARY_WIDTH = 132;
  private static final int APPLE_PRIMARY_HEIGHT = 30;
  private static final int APPLE_SIDE_X = 148;
  private static final int APPLE_SIDE_Y = 17;
  private static final int APPLE_SIDE_WIDTH = 92;
  private static final int APPLE_SIDE_HEIGHT = 24;
  private static final int CLASSIC_PRIMARY_Y = 12;
  private static final int CLASSIC_SIDE_X = 108;
  private static final int CLASSIC_ROW_HEIGHT = 32;
  private static final int CLASSIC_PRIMARY_BASELINE = 25;
  private static final int CLASSIC_PRIMARY_LEGACY_WIDTH = 98;
  private static final int CLASSIC_SIDE_LEGACY_WIDTH = 112;
  private static final int CLASSIC_SEPARATOR_OFFSET = 30;
  private static final int CLASSIC_SECOND_LABEL_OFFSET = 45;
  private static final int CLASSIC_HIT_PADDING_X = 8;
  private static final int CLASSIC_COMMENT_HEIGHT = 48;
  private static final int CLASSIC_BLUNDER_HEIGHT = 48;
  private static final int APPLE_COMMENT_HEIGHT = 56;
  private static final int APPLE_BLUNDER_HEIGHT = 56;

  public SidebarHeaderPanel(SidebarPanel parentPanel) {
    this.parentPanel = parentPanel;
    setOpaque(false);
    setPreferredSize(new Dimension(200, preferredHeight(false, Lizzie.config.isAppleStyle)));

    addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent e) {
            Point point = e.getPoint();
            FontMetrics metrics = getFontMetrics(headerFont());

            int primarySegment = primarySegmentIndexAt(point, Lizzie.config.isAppleStyle, metrics);
            if (primarySegment == FIRST_SEGMENT) {
              parentPanel.switchTo("COMMENTS");
              return;
            }
            if (primarySegment == SECOND_SEGMENT) {
              parentPanel.switchTo("BLUNDERS");
              return;
            }

            if (!Lizzie.config.isShowingBlunderTabel) {
              return;
            }

            int sideSegment = sideSegmentIndexAt(point, Lizzie.config.isAppleStyle, metrics);
            if (sideSegment == FIRST_SEGMENT) {
              Lizzie.frame.setProblemListSideFilter(ProblemListSideFilter.BLACK);
            } else if (sideSegment == SECOND_SEGMENT) {
              Lizzie.frame.setProblemListSideFilter(ProblemListSideFilter.WHITE);
            }
          }
        });
  }

  public void setShowingBlunders(boolean showingBlunders) {
    int height = preferredHeight(showingBlunders, Lizzie.config.isAppleStyle);
    Dimension preferredSize = getPreferredSize();
    if (preferredSize == null || preferredSize.height != height) {
      setPreferredSize(new Dimension(200, height));
      revalidate();
    }
    repaint();
  }

  public void updateSnapshot(ProblemListSnapshot snapshot) {
    this.currentSnapshot = snapshot;
    setToolTipText(progressTooltipFor(snapshot));
    repaint();
  }

  static int preferredHeight(boolean showingBlunders, boolean appleStyle) {
    if (appleStyle) {
      return showingBlunders ? APPLE_BLUNDER_HEIGHT : APPLE_COMMENT_HEIGHT;
    }
    return showingBlunders ? CLASSIC_BLUNDER_HEIGHT : CLASSIC_COMMENT_HEIGHT;
  }

  static String progressLabelFor(ProblemListSnapshot snapshot) {
    if (snapshot == null || snapshot.totalMoves <= 0) {
      return "";
    }
    int analyzedMoves = Math.max(0, Math.min(snapshot.analyzedMoves, snapshot.totalMoves));
    if (snapshot.analysisRunning) {
      return "评估中 " + analyzedMoves + "/" + snapshot.totalMoves;
    }
    if (analyzedMoves >= snapshot.totalMoves) {
      return "评估完成";
    }
    return "已评估 " + analyzedMoves + "/" + snapshot.totalMoves;
  }

  static String progressTooltipFor(ProblemListSnapshot snapshot) {
    String label = progressLabelFor(snapshot);
    if (label.isEmpty()) {
      return null;
    }
    return "问题手评估进度：" + label + "。";
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    Graphics2D g2 = (Graphics2D) g.create();
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    boolean showBlunders = Lizzie.config.isShowingBlunderTabel;
    ProblemListSideFilter sideFilter = Lizzie.frame.getProblemListSideFilter();
    if (sideFilter == ProblemListSideFilter.ALL) sideFilter = ProblemListSideFilter.BLACK;

    g2.setFont(headerFont());
    FontMetrics fm = g2.getFontMetrics();

    if (!Lizzie.config.isAppleStyle) {
      // Classic mode: same hit geometry as before (locked by SidebarHeaderPanelHitTest),
      // but drawn as real tabs — the active label gets an accent underline instead of
      // relying on text brightness alone.
      int x = CONTROL_X;
      int y = CLASSIC_PRIMARY_BASELINE;
      Color accent = glassAccentColor();

      g2.setColor(showBlunders ? TEXT_NORMAL : TEXT_SELECTED);
      g2.drawString("评论", x, y);
      g2.setColor(showBlunders ? TEXT_SELECTED : TEXT_NORMAL);
      g2.drawString("问题手", x + CLASSIC_SECOND_LABEL_OFFSET, y);

      int underlineY = y + 7;
      g2.setColor(withAlpha(accent, 220));
      if (showBlunders) {
        g2.fillRoundRect(
            x + CLASSIC_SECOND_LABEL_OFFSET, underlineY, fm.stringWidth("问题手"), 3, 3, 3);
      } else {
        g2.fillRoundRect(x, underlineY, fm.stringWidth("评论"), 3, 3, 3);
      }

      String pillText = progressLabelFor(currentSnapshot);
      if (!pillText.isEmpty()) {
        int textWidth = fm.stringWidth(pillText);
        int progressX = getWidth() - textWidth - 10;
        int leftEdgeToAvoid =
            showBlunders
                ? CLASSIC_SIDE_X + CLASSIC_SIDE_LEGACY_WIDTH + 8
                : CONTROL_X + CLASSIC_PRIMARY_LEGACY_WIDTH + 8;
        if (progressX > leftEdgeToAvoid) {
          boolean running = currentSnapshot != null && currentSnapshot.analysisRunning;
          g2.setColor(running ? new Color(255, 184, 77, 40) : new Color(255, 255, 255, 18));
          g2.fillRoundRect(progressX - 8, y - fm.getAscent() - 1, textWidth + 16, 22, 11, 11);
          g2.setColor(running ? new Color(255, 213, 153, 64) : new Color(255, 255, 255, 30));
          g2.drawRoundRect(progressX - 8, y - fm.getAscent() - 1, textWidth + 15, 21, 11, 11);
          g2.setColor(running ? TEXT_SELECTED : TEXT_NORMAL);
          g2.drawString(pillText, progressX, y);
        }
      }

      if (showBlunders) {
        x = CLASSIC_SIDE_X;
        boolean blackSelected = sideFilter == ProblemListSideFilter.BLACK;
        g2.setColor(blackSelected ? TEXT_SELECTED : TEXT_NORMAL);
        g2.drawString("黑棋", x, y);
        g2.setColor(!blackSelected ? TEXT_SELECTED : TEXT_NORMAL);
        g2.drawString("白棋", x + CLASSIC_SECOND_LABEL_OFFSET, y);

        // Side-colored underline: black bar for 黑棋, bordered white bar for 白棋.
        if (blackSelected) {
          g2.setColor(new Color(16, 18, 22));
          g2.fillRoundRect(x, underlineY, fm.stringWidth("黑棋"), 3, 3, 3);
          g2.setColor(new Color(255, 255, 255, 170));
          g2.drawRoundRect(x, underlineY, fm.stringWidth("黑棋") - 1, 2, 3, 3);
        } else {
          g2.setColor(new Color(245, 247, 250));
          g2.fillRoundRect(
              x + CLASSIC_SECOND_LABEL_OFFSET, underlineY, fm.stringWidth("白棋"), 3, 3, 3);
        }
      }
      g2.dispose();
      return;
    }

    Color accent = glassAccentColor();

    // 1. [ 评论 | 问题手 ] (Apple Style Segmented Control)
    int x = CONTROL_X;
    int y = APPLE_PRIMARY_Y;
    int segW = APPLE_PRIMARY_WIDTH;
    int segH = APPLE_PRIMARY_HEIGHT;
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
    String pillText = progressLabelFor(currentSnapshot);
    if (!pillText.isEmpty()) {
      int textWidth = fm.stringWidth(pillText);
      int pillX = getWidth() - textWidth - 24;
      int pillY = y;
      int pillWidth = textWidth + 16;
      int pillHeight = 28;
      int leftEdgeToAvoid = showBlunders ? APPLE_SIDE_X + APPLE_SIDE_WIDTH + 8 : x + segW + 8;
      if (pillX > leftEdgeToAvoid) {
        g2.setColor(
            currentSnapshot.analysisRunning ? new Color(255, 184, 77, 48) : withAlpha(accent, 54));
        g2.fillRoundRect(pillX, pillY, pillWidth, pillHeight, arc, arc);
        g2.setColor(
            currentSnapshot.analysisRunning ? new Color(255, 213, 153, 72) : withAlpha(accent, 92));
        g2.drawRoundRect(pillX, pillY, pillWidth - 1, pillHeight - 1, arc, arc);

        g2.setColor(TEXT_SELECTED);
        g2.drawString(pillText, pillX + 8, baseline + 1);
      }
    }

    // 3. [ 黑 | 白 ] — the thumb shows the actual side color, so the active side is
    // readable at a glance without parsing text.
    if (showBlunders) {
      x = APPLE_SIDE_X;
      y = APPLE_SIDE_Y;
      segW = APPLE_SIDE_WIDTH;
      segH = APPLE_SIDE_HEIGHT;
      halfW = segW / 2;
      arc = 12;
      baseline = y + segH / 2 + fm.getAscent() / 2 - 1;

      g2.setColor(new Color(255, 255, 255, 20));
      g2.fillRoundRect(x, y, segW, segH, arc, arc);
      g2.setColor(new Color(255, 255, 255, 14));
      g2.drawRoundRect(x, y, segW - 1, segH - 1, arc, arc);

      boolean blackSelected = sideFilter == ProblemListSideFilter.BLACK;
      if (blackSelected) {
        g2.setColor(new Color(12, 14, 18, 240));
        g2.fillRoundRect(x + 2, y + 2, halfW - 2, segH - 4, arc - 2, arc - 2);
        g2.setColor(withAlpha(accent, 150));
        g2.drawRoundRect(x + 2, y + 2, halfW - 3, segH - 5, arc - 2, arc - 2);
      } else {
        g2.setColor(new Color(243, 245, 248, 240));
        g2.fillRoundRect(x + halfW, y + 2, halfW - 2, segH - 4, arc - 2, arc - 2);
        g2.setColor(withAlpha(accent, 150));
        g2.drawRoundRect(x + halfW, y + 2, halfW - 3, segH - 5, arc - 2, arc - 2);
      }

      String b1 = "黑棋";
      String b2 = "白棋";
      int dotSize = 8;
      int dotGap = 5;

      int seg1Content = dotSize + dotGap + fm.stringWidth(b1);
      int seg1X = x + (halfW - seg1Content) / 2;
      int dotY = y + (segH - dotSize) / 2;
      g2.setColor(blackSelected ? new Color(20, 22, 26) : new Color(35, 38, 44));
      g2.fillOval(seg1X, dotY, dotSize, dotSize);
      g2.setColor(new Color(255, 255, 255, blackSelected ? 190 : 90));
      g2.drawOval(seg1X, dotY, dotSize, dotSize);
      g2.setColor(blackSelected ? TEXT_SELECTED : TEXT_NORMAL);
      g2.drawString(b1, seg1X + dotSize + dotGap, baseline);

      int seg2Content = dotSize + dotGap + fm.stringWidth(b2);
      int seg2X = x + halfW + (halfW - seg2Content) / 2;
      g2.setColor(new Color(248, 249, 252));
      g2.fillOval(seg2X, dotY, dotSize, dotSize);
      g2.setColor(new Color(0, 0, 0, blackSelected ? 70 : 160));
      g2.drawOval(seg2X, dotY, dotSize, dotSize);
      g2.setColor(!blackSelected ? new Color(28, 31, 36) : TEXT_NORMAL);
      g2.drawString(b2, seg2X + dotSize + dotGap, baseline);
    }

    g2.dispose();
  }

  static int primarySegmentIndexAt(Point point, boolean appleStyle, FontMetrics metrics) {
    if (appleStyle) {
      return segmentedIndexAt(
          point,
          new Rectangle(CONTROL_X, APPLE_PRIMARY_Y, APPLE_PRIMARY_WIDTH, APPLE_PRIMARY_HEIGHT));
    }
    return classicTextIndexAt(point, metrics, CLASSIC_PRIMARY_Y, "评论", "问题手");
  }

  static int sideSegmentIndexAt(Point point, boolean appleStyle, FontMetrics metrics) {
    if (appleStyle) {
      return segmentedIndexAt(
          point, new Rectangle(APPLE_SIDE_X, APPLE_SIDE_Y, APPLE_SIDE_WIDTH, APPLE_SIDE_HEIGHT));
    }
    return classicTextIndexAt(point, metrics, CLASSIC_PRIMARY_Y, "黑棋", "白棋", CLASSIC_SIDE_X);
  }

  private static int classicTextIndexAt(
      Point point, FontMetrics metrics, int rowY, String firstText, String secondText) {
    return classicTextIndexAt(point, metrics, rowY, firstText, secondText, CONTROL_X);
  }

  private static int classicTextIndexAt(
      Point point, FontMetrics metrics, int rowY, String firstText, String secondText, int textX) {
    if (classicTextBounds(metrics, firstText, textX, rowY).contains(point)) {
      return FIRST_SEGMENT;
    }
    if (classicSecondTextBounds(metrics, secondText, rowY, textX).contains(point)) {
      return SECOND_SEGMENT;
    }
    return NO_SEGMENT;
  }

  private static Rectangle classicTextBounds(
      FontMetrics metrics, String text, int textX, int rowY) {
    int textWidth = Math.max(1, metrics.stringWidth(text));
    return new Rectangle(
        textX - CLASSIC_HIT_PADDING_X,
        rowY,
        textWidth + CLASSIC_HIT_PADDING_X * 2,
        CLASSIC_ROW_HEIGHT);
  }

  private static Rectangle classicSecondTextBounds(
      FontMetrics metrics, String text, int rowY, int firstTextX) {
    int textX = firstTextX + CLASSIC_SECOND_LABEL_OFFSET;
    Rectangle textBounds = classicTextBounds(metrics, text, textX, rowY);
    int legacyRight =
        firstTextX
            + (firstTextX == CLASSIC_SIDE_X
                ? CLASSIC_SIDE_LEGACY_WIDTH
                : CLASSIC_PRIMARY_LEGACY_WIDTH);
    int right = Math.max(textBounds.x + textBounds.width, legacyRight);
    textBounds.width = right - textBounds.x;
    return textBounds;
  }

  private static int segmentedIndexAt(Point point, Rectangle bounds) {
    if (!bounds.contains(point)) {
      return NO_SEGMENT;
    }
    return point.x < bounds.x + bounds.width / 2 ? FIRST_SEGMENT : SECOND_SEGMENT;
  }

  private Font headerFont() {
    String fontName =
        Lizzie.config != null && Lizzie.config.uiFontName != null
            ? Lizzie.config.uiFontName
            : getFont().getName();
    return new Font(fontName, Font.BOLD, 12);
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

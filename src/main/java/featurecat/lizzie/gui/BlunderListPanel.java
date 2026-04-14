package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import javax.swing.*;

public class BlunderListPanel extends JPanel {
  private ProblemListSnapshot currentSnapshot;

  private int hoveredIndex = -1;

  private static final int HEADER_HEIGHT = 20;
  private static final int CARD_HEIGHT = 56;
  private static final int PADDING = 10;
  private static final int DEFAULT_CARD_CORNER_RADIUS = 10;

  private static final Color COLOR_DANGER = new Color(0xEF, 0x44, 0x44); // 🔴
  private static final Color COLOR_WARNING = new Color(0xF9, 0x73, 0x16); // 🟧
  private static final Color COLOR_INFO = new Color(0xEA, 0xB3, 0x08); // 🟨

  private static final Color TEXT_PRIMARY = new Color(255, 255, 255, 255);
  private static final Color TEXT_SECONDARY = new Color(255, 255, 255, 150);
  private static final Color TEXT_DANGER = new Color(0xFF, 0x8A, 0x8A);
  private static final Color TEXT_WARNING = new Color(0xFF, 0xB0, 0x6B);
  private static final Color TEXT_INFO = new Color(0xFF, 0xDA, 0x6B);

  public BlunderListPanel() {
    setOpaque(false);

    MouseAdapter ma =
        new MouseAdapter() {
          @Override
          public void mouseMoved(MouseEvent e) {
            updateHover(e.getX(), e.getY());
          }

          @Override
          public void mouseExited(MouseEvent e) {
            hoveredIndex = -1;
            repaint();
          }

          @Override
          public void mouseClicked(MouseEvent e) {
            ProblemMoveEntry clicked = getEntryAt(e.getX(), e.getY());
            if (clicked != null) {
              Lizzie.frame.jumpToProblemMove(clicked);
            }
          }
        };

    addMouseListener(ma);
    addMouseMotionListener(ma);
  }

  public void updateSnapshot(ProblemListSnapshot snapshot) {
    this.currentSnapshot = snapshot;
    revalidate();
    repaint();
  }

  private void updateHover(int x, int y) {
    if (currentSnapshot == null) return;

    int oldHovered = hoveredIndex;

    if (y < HEADER_HEIGHT) {
      hoveredIndex = -1;
    } else {
      int row = (y - HEADER_HEIGHT) / CARD_HEIGHT;
      List<ProblemMoveEntry> all = getMergedEntries();
      if (row >= 0 && row < all.size()) {
        hoveredIndex = row;
      } else {
        hoveredIndex = -1;
      }
    }

    if (oldHovered != hoveredIndex) {
      repaint();
    }
  }

  private ProblemMoveEntry getEntryAt(int x, int y) {
    if (currentSnapshot == null || y < HEADER_HEIGHT) return null;

    int row = (y - HEADER_HEIGHT) / CARD_HEIGHT;
    List<ProblemMoveEntry> all = getMergedEntries();
    if (row >= 0 && row < all.size()) return all.get(row);
    return null;
  }

  private List<ProblemMoveEntry> getMergedEntries() {
    List<ProblemMoveEntry> all = new ArrayList<>();
    if (currentSnapshot != null) {
      all.addAll(getVisibleBlackEntries());
      all.addAll(getVisibleWhiteEntries());
      all.sort(
          Comparator.comparingDouble((ProblemMoveEntry entry) -> entry.winrateLossAbs)
              .reversed()
              .thenComparingInt(entry -> entry.moveNumber));
    }
    return all;
  }

  private List<ProblemMoveEntry> getVisibleBlackEntries() {
    return filterEntries(
        currentSnapshot != null ? currentSnapshot.blackEntries : Collections.emptyList(), true);
  }

  private List<ProblemMoveEntry> getVisibleWhiteEntries() {
    return filterEntries(
        currentSnapshot != null ? currentSnapshot.whiteEntries : Collections.emptyList(), false);
  }

  private List<ProblemMoveEntry> filterEntries(List<ProblemMoveEntry> entries, boolean isBlack) {
    if (!currentSideFilter().allows(isBlack)) {
      return Collections.emptyList();
    }
    return entries;
  }

  private ProblemListSideFilter currentSideFilter() {
    if (Lizzie.frame == null) {
      return ProblemListSideFilter.BLACK;
    }
    ProblemListSideFilter filter = Lizzie.frame.getProblemListSideFilter();
    return filter == ProblemListSideFilter.ALL ? ProblemListSideFilter.BLACK : filter;
  }

  private boolean hasVisibleEntries() {
    return !getVisibleBlackEntries().isEmpty() || !getVisibleWhiteEntries().isEmpty();
  }

  @Override
  public Dimension getPreferredSize() {
    if (currentSnapshot == null) {
      return new Dimension(0, 0);
    }
    int rows = getMergedEntries().size();
    return new Dimension(getWidth(), HEADER_HEIGHT + rows * CARD_HEIGHT + PADDING);
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    if (currentSnapshot == null || !hasVisibleEntries()) {
      drawEmptyState(g);
      return;
    }

    Graphics2D g2 = (Graphics2D) g.create();
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    int width = getWidth();
    drawColumn(g2, getMergedEntries(), 0, HEADER_HEIGHT, width);
    g2.dispose();
  }

  private void drawEmptyState(Graphics g) {
    Graphics2D g2 = (Graphics2D) g.create();
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g2.setFont(new Font(Lizzie.config.uiFontName, Font.PLAIN, 14));
    String text =
        currentSnapshot != null && currentSnapshot.analysisRunning ? "⏳ 正在整理问题手..." : "当前无问题手";
    FontMetrics fm = g2.getFontMetrics();
    if (Lizzie.config.isAppleStyle) {
      int boxW = Math.min(getWidth() - 24, 220);
      int boxH = 86;
      int boxX = Math.max(12, (getWidth() - boxW) / 2);
      int boxY = Math.max(12, (getHeight() - boxH) / 2 - 12);
      g2.setColor(new Color(255, 255, 255, 14));
      g2.fillRoundRect(boxX, boxY, boxW, boxH, 18, 18);
      g2.setColor(new Color(255, 255, 255, 24));
      g2.drawRoundRect(boxX, boxY, boxW - 1, boxH - 1, 18, 18);
      g2.setColor(TEXT_PRIMARY);
      g2.drawString(text, boxX + (boxW - fm.stringWidth(text)) / 2, boxY + 34);
      g2.setColor(TEXT_SECONDARY);
      String sub = "静默全盘分析后，这里会列出掉胜率 >= 10% 的问题手";
      g2.setFont(new Font(Lizzie.config.uiFontName, Font.PLAIN, 12));
      FontMetrics subFm = g2.getFontMetrics();
      g2.drawString(sub, boxX + (boxW - subFm.stringWidth(sub)) / 2, boxY + 58);
    } else {
      g2.setColor(TEXT_SECONDARY);
      g2.drawString(text, (getWidth() - fm.stringWidth(text)) / 2, getHeight() / 2);
    }
    g2.dispose();
  }

  private Color getCardBgNormal(boolean isBlack) {
    return isBlack ? new Color(14, 18, 24, 150) : new Color(255, 255, 255, 34);
  }

  private Color getCardBgHover(boolean isBlack) {
    Color accent = glassAccentColor();
    return isBlack
        ? new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 88)
        : new Color(255, 255, 255, 56);
  }

  private void drawColumn(
      Graphics2D g2, List<ProblemMoveEntry> entries, int startX, int startY, int colWidth) {
    for (int i = 0; i < entries.size(); i++) {
      ProblemMoveEntry entry = entries.get(i);
      int y = startY + i * CARD_HEIGHT;
      int x = startX + PADDING;
      int w = colWidth - PADDING * 2;
      int h = CARD_HEIGHT - 6;

      boolean isHovered = (hoveredIndex == i);
      boolean isSelected = entry.isCurrent;
      boolean isSevere = entry.severityTier >= 5;

      // Background
      if (!Lizzie.config.isAppleStyle) {
        // flat classic background
        if (isSelected) {
          g2.setColor(new Color(60, 60, 80));
        } else if (isHovered) {
          g2.setColor(new Color(40, 40, 50));
        } else {
          g2.setColor(new Color(30, 30, 30));
        }
        g2.fillRect(x, y, w, h);
      } else {
        // Apple style background
        int cardCornerRadius = cardCornerRadius();
        g2.setColor(new Color(0, 0, 0, 38));
        g2.fillRoundRect(x + 1, y + 4, w - 1, h - 1, cardCornerRadius, cardCornerRadius);

        if (isSelected) {
          g2.setColor(selectedCardColor());
        } else if (isHovered) {
          g2.setColor(getCardBgHover(entry.isBlack));
        } else {
          g2.setColor(getCardBgNormal(entry.isBlack));
        }
        g2.fillRoundRect(x, y, w, h, cardCornerRadius, cardCornerRadius);
        g2.setColor(isSelected ? withAlpha(glassAccentColor(), 120) : new Color(255, 255, 255, 22));
        g2.drawRoundRect(x, y, w - 1, h - 1, cardCornerRadius, cardCornerRadius);

        // Severe blunder emphasis (faint red glow/bg)
        if (isSevere && !isSelected && !isHovered) {
          g2.setColor(new Color(239, 68, 68, 30));
          g2.fillRoundRect(x, y, w, h, cardCornerRadius, cardCornerRadius);
          g2.setColor(new Color(239, 68, 68, 86));
          g2.drawRoundRect(x, y, w - 1, h - 1, cardCornerRadius, cardCornerRadius);
        }

        // Liquid Highlight for selected
        if (isSelected) {
          LinearGradientPaint highlight =
              new LinearGradientPaint(
                  x,
                  y,
                  x,
                  y + h,
                  new float[] {0.0f, 1.0f},
                  new Color[] {cardHighlightColor(), new Color(255, 255, 255, 0)});
          g2.setPaint(highlight);
          g2.drawRoundRect(x, y, w - 1, h - 1, cardCornerRadius, cardCornerRadius);
        }
      }

      // Left semantic edge line
      g2.setColor(entry.isBlack ? new Color(0, 0, 0, 200) : new Color(255, 255, 255, 200));
      g2.fillRoundRect(x, y, 4, h, 3, 3);

      // Text: #手数 坐标
      g2.setFont(new Font(Lizzie.config.uiFontName, Font.BOLD, 14));
      g2.setColor(TEXT_PRIMARY);
      String moveText = "#" + entry.moveNumber + "  " + entry.coords;
      g2.drawString(moveText, x + 12, y + 20);

      // Text: Loss
      g2.setFont(new Font(Lizzie.config.uiFontName, Font.PLAIN, 12));
      Color severityColor = getSeverityColor(entry.severityTier);
      g2.setColor(severityColor);
      String lossText = "🔻 " + String.format("%.1f%%", entry.winrateLossAbs);
      g2.drawString(lossText, x + 12, y + 38);

      // Text: Playouts
      g2.setColor(TEXT_SECONDARY);
      FontMetrics fm = g2.getFontMetrics();
      String playoutText =
          "| "
              + (entry.playouts > 1000
                  ? String.format("%.1fk", entry.playouts / 1000.0)
                  : entry.playouts);
      int lossWidth = fm.stringWidth(lossText);
      g2.drawString(playoutText, x + 12 + lossWidth + 5, y + 38);

      // Thermal dot
      Color dotColor = null;
      if (entry.severityTier >= 5) dotColor = COLOR_DANGER;
      else if (entry.severityTier >= 4) dotColor = COLOR_WARNING;
      else if (entry.severityTier >= 3) dotColor = COLOR_INFO;

      if (dotColor != null) {
        g2.setColor(dotColor);
        int dotSize = isSevere ? 10 : 8;
        g2.fillOval(x + w - 18, y + (h - dotSize) / 2, dotSize, dotSize);
      }
    }
  }

  private Color getSeverityColor(int severityTier) {
    if (severityTier >= 5) return TEXT_DANGER;
    if (severityTier >= 4) return TEXT_WARNING;
    if (severityTier >= 3) return TEXT_INFO;
    return TEXT_PRIMARY;
  }

  private Color selectedCardColor() {
    Color accent = glassAccentColor();
    return new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 148);
  }

  private Color cardHighlightColor() {
    return Lizzie.config != null && Lizzie.config.theme != null
        ? Lizzie.config.theme.glassPanelHighlightColor()
        : new Color(255, 255, 255, 77);
  }

  private Color glassAccentColor() {
    return Lizzie.config != null && Lizzie.config.theme != null
        ? Lizzie.config.theme.glassAccentColor()
        : new Color(96, 165, 250);
  }

  private int cardCornerRadius() {
    int cornerRadius =
        Lizzie.config != null && Lizzie.config.theme != null
            ? Lizzie.config.theme.glassCornerRadius()
            : 12;
    return Math.max(6, Math.min(DEFAULT_CARD_CORNER_RADIUS + 4, cornerRadius));
  }

  private Color withAlpha(Color color, int alpha) {
    return new Color(
        color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha)));
  }
}

package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.text.MessageFormat;
import javax.swing.*;

public class SidebarHeaderPanel extends JPanel {
  private final SidebarPanel parentPanel;
  private ProblemListSnapshot currentSnapshot;

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
  private static final int APPLE_LABEL_INSET = 8;
  private static final int APPLE_CONTROL_GAP = 6;
  private static final int APPLE_WRAP_GAP = 4;
  private static final int APPLE_DOT_SIZE = 8;
  private static final int APPLE_DOT_GAP = 5;
  private static final int CLASSIC_LABEL_GAP = 16;
  private static final int CLASSIC_WRAPPED_HEIGHT = CLASSIC_COMMENT_HEIGHT + CLASSIC_ROW_HEIGHT;
  private static final int APPLE_WRAPPED_HEIGHT = APPLE_COMMENT_HEIGHT + APPLE_PRIMARY_HEIGHT;

  public SidebarHeaderPanel(SidebarPanel parentPanel) {
    this.parentPanel = parentPanel;
    setOpaque(false);
    setFocusable(true);
    setPreferredSize(new Dimension(200, preferredHeight(false, Lizzie.config.isAppleStyle)));
    AccessibilitySupport.named(
        this,
        text("SidebarHeader.accessibleName", "Comments and problem moves"),
        text(
            "SidebarHeader.accessibleDescription",
            "Switch between comments and problem moves, then filter by Black or White."));
    installKeyboardActions();
    addFocusListener(
        new FocusAdapter() {
          @Override
          public void focusGained(FocusEvent event) {
            repaint();
          }

          @Override
          public void focusLost(FocusEvent event) {
            repaint();
          }
        });

    addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent e) {
            Point point = e.getPoint();
            FontMetrics metrics = getFontMetrics(headerFont());

            int primarySegment =
                primarySegmentIndexAt(
                    point,
                    Lizzie.config.isAppleStyle,
                    metrics,
                    layoutWidth(),
                    progressLabelFor(currentSnapshot));
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

            int sideSegment =
                sideSegmentIndexAt(
                    point,
                    Lizzie.config.isAppleStyle,
                    metrics,
                    layoutWidth(),
                    progressLabelFor(currentSnapshot));
            if (sideSegment == FIRST_SEGMENT) {
              Lizzie.frame.setProblemListSideFilter(ProblemListSideFilter.BLACK);
            } else if (sideSegment == SECOND_SEGMENT) {
              Lizzie.frame.setProblemListSideFilter(ProblemListSideFilter.WHITE);
            }
          }
        });
    addComponentListener(
        new ComponentAdapter() {
          @Override
          public void componentResized(ComponentEvent event) {
            if (Lizzie.config != null) {
              applyPreferredHeight(Lizzie.config.isShowingBlunderTabel);
            }
          }
        });
  }

  public void setShowingBlunders(boolean showingBlunders) {
    applyPreferredHeight(showingBlunders);
    repaint();
  }

  private void applyPreferredHeight(boolean showingBlunders) {
    int width = layoutWidth();
    int height =
        preferredHeight(
            showingBlunders,
            Lizzie.config.isAppleStyle,
            getFontMetrics(headerFont()),
            width,
            progressLabelFor(currentSnapshot));
    Dimension preferredSize = getPreferredSize();
    if (preferredSize == null || preferredSize.height != height) {
      setPreferredSize(new Dimension(200, height));
      revalidate();
    }
    repaint();
  }

  private int layoutWidth() {
    return getWidth() > 0 ? getWidth() : Integer.MAX_VALUE;
  }

  public void updateSnapshot(ProblemListSnapshot snapshot) {
    String previous = progressLabelFor(this.currentSnapshot);
    this.currentSnapshot = snapshot;
    setToolTipText(progressTooltipFor(snapshot));
    AccessibilitySupport.announce(this, previous, progressLabelFor(snapshot));
    applyPreferredHeight(Lizzie.config != null && Lizzie.config.isShowingBlunderTabel);
  }

  static int preferredHeight(boolean showingBlunders, boolean appleStyle) {
    if (appleStyle) {
      return showingBlunders ? APPLE_BLUNDER_HEIGHT : APPLE_COMMENT_HEIGHT;
    }
    return showingBlunders ? CLASSIC_BLUNDER_HEIGHT : CLASSIC_COMMENT_HEIGHT;
  }

  static int preferredHeight(
      boolean showingBlunders, boolean appleStyle, FontMetrics metrics, int availableWidth) {
    return preferredHeight(showingBlunders, appleStyle, metrics, availableWidth, "");
  }

  static int preferredHeight(
      boolean showingBlunders,
      boolean appleStyle,
      FontMetrics metrics,
      int availableWidth,
      String progressText) {
    return headerLayout(appleStyle, showingBlunders, metrics, availableWidth, progressText).height;
  }

  static String progressLabelFor(ProblemListSnapshot snapshot) {
    if (snapshot == null || snapshot.totalMoves <= 0) {
      return "";
    }
    int analyzedMoves = Math.max(0, Math.min(snapshot.analyzedMoves, snapshot.totalMoves));
    if (snapshot.analysisRunning) {
      return format(
          "SidebarHeader.progress.running",
          "Evaluating {0}/{1}",
          analyzedMoves,
          snapshot.totalMoves);
    }
    if (analyzedMoves >= snapshot.totalMoves) {
      return text("SidebarHeader.progress.complete", "Evaluation complete");
    }
    return format(
        "SidebarHeader.progress.partial", "Evaluated {0}/{1}", analyzedMoves, snapshot.totalMoves);
  }

  static String progressTooltipFor(ProblemListSnapshot snapshot) {
    String label = progressLabelFor(snapshot);
    if (label.isEmpty()) {
      return null;
    }
    return format("SidebarHeader.progress.tooltip", "Problem move progress: {0}.", label);
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
    String pillText = progressLabelFor(currentSnapshot);
    HeaderLayout layout =
        headerLayout(Lizzie.config.isAppleStyle, showBlunders, fm, layoutWidth(), pillText);

    if (!Lizzie.config.isAppleStyle) {
      Color accent = glassAccentColor();
      String commentsText = text("SidebarHeader.comments", "Comments");
      String problemsText = text("SidebarHeader.problems", "Problems");

      g2.setColor(
          showBlunders ? SidebarPanel.secondaryTextColor() : SidebarPanel.primaryTextColor());
      g2.drawString(commentsText, layout.commentsTextX, layout.primaryBaseline);
      g2.setColor(
          showBlunders ? SidebarPanel.primaryTextColor() : SidebarPanel.secondaryTextColor());
      g2.drawString(problemsText, layout.problemsTextX, layout.primaryBaseline);

      int underlineY = layout.primaryBaseline + 7;
      g2.setColor(withAlpha(accent, 220));
      if (showBlunders) {
        g2.fillRoundRect(layout.problemsTextX, underlineY, fm.stringWidth(problemsText), 3, 3, 3);
      } else {
        g2.fillRoundRect(layout.commentsTextX, underlineY, fm.stringWidth(commentsText), 3, 3, 3);
      }

      if (!layout.progress.isEmpty()) {
        boolean running = currentSnapshot != null && currentSnapshot.analysisRunning;
        int progressX = layout.progress.x + 8;
        g2.setColor(running ? new Color(255, 184, 77, 40) : new Color(255, 255, 255, 18));
        g2.fillRoundRect(
            layout.progress.x,
            layout.progress.y,
            layout.progress.width,
            layout.progress.height,
            11,
            11);
        g2.setColor(running ? new Color(255, 213, 153, 64) : new Color(255, 255, 255, 30));
        g2.drawRoundRect(
            layout.progress.x,
            layout.progress.y,
            layout.progress.width - 1,
            layout.progress.height - 1,
            11,
            11);
        g2.setColor(running ? SidebarPanel.primaryTextColor() : SidebarPanel.secondaryTextColor());
        g2.drawString(pillText, progressX, layout.primaryBaseline);
      }

      if (showBlunders) {
        String blackText = text("SidebarHeader.black", "Black");
        String whiteText = text("SidebarHeader.white", "White");
        boolean blackSelected = sideFilter == ProblemListSideFilter.BLACK;
        g2.setColor(
            blackSelected ? SidebarPanel.primaryTextColor() : SidebarPanel.secondaryTextColor());
        g2.drawString(blackText, layout.blackTextX, layout.sideBaseline);
        g2.setColor(
            !blackSelected ? SidebarPanel.primaryTextColor() : SidebarPanel.secondaryTextColor());
        g2.drawString(whiteText, layout.whiteTextX, layout.sideBaseline);

        int sideUnderlineY = layout.sideBaseline + 7;
        g2.setColor(SidebarPanel.accentTextColor());
        g2.fillRoundRect(
            blackSelected ? layout.blackTextX : layout.whiteTextX,
            sideUnderlineY,
            fm.stringWidth(blackSelected ? blackText : whiteText),
            3,
            3,
            3);
      }
      paintKeyboardFocus(g2);
      g2.dispose();
      return;
    }

    Color accent = glassAccentColor();
    int x = layout.comments.x;
    int y = layout.comments.y;
    int segW = layout.comments.width + layout.problems.width;
    int segH = layout.comments.height;
    int arc = 15;
    int halfW = layout.comments.width;

    g2.setColor(new Color(255, 255, 255, 24));
    g2.fillRoundRect(x, y, segW, segH, arc, arc);
    g2.setColor(new Color(255, 255, 255, 18));
    g2.drawRoundRect(x, y, segW - 1, segH - 1, arc, arc);

    g2.setColor(showBlunders ? withAlpha(accent, 132) : new Color(255, 255, 255, 58));
    if (!showBlunders) {
      g2.fillRoundRect(x + 2, y + 2, halfW - 2, segH - 4, arc - 2, arc - 2);
    } else {
      g2.fillRoundRect(x + halfW, y + 2, halfW - 2, segH - 4, arc - 2, arc - 2);
    }

    g2.setColor(
        !showBlunders ? SidebarPanel.primaryTextColor() : SidebarPanel.secondaryTextColor());
    String t1 = text("SidebarHeader.comments", "Comments");
    g2.drawString(t1, layout.commentsTextX, layout.primaryBaseline);

    g2.setColor(showBlunders ? SidebarPanel.primaryTextColor() : SidebarPanel.secondaryTextColor());
    String t2 = text("SidebarHeader.problems", "Problems");
    g2.drawString(t2, layout.problemsTextX, layout.primaryBaseline);

    if (!layout.progress.isEmpty()) {
      g2.setColor(
          currentSnapshot.analysisRunning ? new Color(255, 184, 77, 48) : withAlpha(accent, 54));
      g2.fillRoundRect(
          layout.progress.x,
          layout.progress.y,
          layout.progress.width,
          layout.progress.height,
          arc,
          arc);
      g2.setColor(
          currentSnapshot.analysisRunning ? new Color(255, 213, 153, 72) : withAlpha(accent, 92));
      g2.drawRoundRect(
          layout.progress.x,
          layout.progress.y,
          layout.progress.width - 1,
          layout.progress.height - 1,
          arc,
          arc);
      g2.setColor(SidebarPanel.primaryTextColor());
      g2.drawString(pillText, layout.progress.x + 8, layout.primaryBaseline + 1);
    }

    if (showBlunders) {
      x = layout.black.x;
      y = layout.black.y;
      segW = layout.black.width + layout.white.width;
      segH = layout.black.height;
      halfW = layout.black.width;
      arc = 12;

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

      String b1 = text("SidebarHeader.black", "Black");
      String b2 = text("SidebarHeader.white", "White");
      int seg1Content = APPLE_DOT_SIZE + APPLE_DOT_GAP + fm.stringWidth(b1);
      int seg1X = x + (halfW - seg1Content) / 2;
      int dotY = y + (segH - APPLE_DOT_SIZE) / 2;
      g2.setColor(blackSelected ? new Color(20, 22, 26) : new Color(35, 38, 44));
      g2.fillOval(seg1X, dotY, APPLE_DOT_SIZE, APPLE_DOT_SIZE);
      g2.setColor(new Color(255, 255, 255, blackSelected ? 190 : 90));
      g2.drawOval(seg1X, dotY, APPLE_DOT_SIZE, APPLE_DOT_SIZE);
      g2.setColor(
          blackSelected ? new Color(239, 242, 241) : SidebarPanel.secondaryTextColor());
      g2.drawString(b1, layout.blackTextX, layout.sideBaseline);

      int seg2Content = APPLE_DOT_SIZE + APPLE_DOT_GAP + fm.stringWidth(b2);
      int seg2X = x + halfW + (halfW - seg2Content) / 2;
      g2.setColor(new Color(248, 249, 252));
      g2.fillOval(seg2X, dotY, APPLE_DOT_SIZE, APPLE_DOT_SIZE);
      g2.setColor(new Color(0, 0, 0, blackSelected ? 70 : 160));
      g2.drawOval(seg2X, dotY, APPLE_DOT_SIZE, APPLE_DOT_SIZE);
      g2.setColor(!blackSelected ? new Color(28, 31, 36) : SidebarPanel.secondaryTextColor());
      g2.drawString(b2, layout.whiteTextX, layout.sideBaseline);
    }

    paintKeyboardFocus(g2);
    g2.dispose();
  }

  static int primarySegmentIndexAt(Point point, boolean appleStyle, FontMetrics metrics) {
    return primarySegmentIndexAt(point, appleStyle, metrics, Integer.MAX_VALUE);
  }

  static int primarySegmentIndexAt(
      Point point, boolean appleStyle, FontMetrics metrics, int availableWidth) {
    return primarySegmentIndexAt(point, appleStyle, metrics, availableWidth, "");
  }

  static int primarySegmentIndexAt(
      Point point,
      boolean appleStyle,
      FontMetrics metrics,
      int availableWidth,
      String progressText) {
    HeaderLayout layout = headerLayout(appleStyle, false, metrics, availableWidth, progressText);
    if (layout.comments.contains(point)) {
      return FIRST_SEGMENT;
    }
    if (layout.problems.contains(point)) {
      return SECOND_SEGMENT;
    }
    return NO_SEGMENT;
  }

  static int sideSegmentIndexAt(Point point, boolean appleStyle, FontMetrics metrics) {
    return sideSegmentIndexAt(point, appleStyle, metrics, Integer.MAX_VALUE);
  }

  static int sideSegmentIndexAt(
      Point point, boolean appleStyle, FontMetrics metrics, int availableWidth) {
    return sideSegmentIndexAt(point, appleStyle, metrics, availableWidth, "");
  }

  static int sideSegmentIndexAt(
      Point point,
      boolean appleStyle,
      FontMetrics metrics,
      int availableWidth,
      String progressText) {
    HeaderLayout layout = headerLayout(appleStyle, true, metrics, availableWidth, progressText);
    if (layout.black.contains(point)) {
      return FIRST_SEGMENT;
    }
    if (layout.white.contains(point)) {
      return SECOND_SEGMENT;
    }
    return NO_SEGMENT;
  }

  static HeaderLayout headerLayout(
      boolean appleStyle,
      boolean showingBlunders,
      FontMetrics metrics,
      int availableWidth,
      String progressText) {
    String progress = progressText == null ? "" : progressText;
    return appleStyle
        ? appleLayout(showingBlunders, metrics, availableWidth, progress)
        : classicLayout(showingBlunders, metrics, availableWidth, progress);
  }

  static final class HeaderLayout {
    final Rectangle comments;
    final Rectangle problems;
    final Rectangle black;
    final Rectangle white;
    final Rectangle progress;
    final int commentsTextX;
    final int problemsTextX;
    final int blackTextX;
    final int whiteTextX;
    final int primaryBaseline;
    final int sideBaseline;
    final boolean filtersWrapped;
    final int height;

    HeaderLayout(
        Rectangle comments,
        Rectangle problems,
        Rectangle black,
        Rectangle white,
        Rectangle progress,
        int commentsTextX,
        int problemsTextX,
        int blackTextX,
        int whiteTextX,
        int primaryBaseline,
        int sideBaseline,
        boolean filtersWrapped,
        int height) {
      this.comments = comments;
      this.problems = problems;
      this.black = black;
      this.white = white;
      this.progress = progress;
      this.commentsTextX = commentsTextX;
      this.problemsTextX = problemsTextX;
      this.blackTextX = blackTextX;
      this.whiteTextX = whiteTextX;
      this.primaryBaseline = primaryBaseline;
      this.sideBaseline = sideBaseline;
      this.filtersWrapped = filtersWrapped;
      this.height = height;
    }
  }

  private static HeaderLayout classicLayout(
      boolean showingBlunders, FontMetrics metrics, int availableWidth, String progressText) {
    String commentsText = text("SidebarHeader.comments", "Comments");
    String problemsText = text("SidebarHeader.problems", "Problems");
    String blackText = text("SidebarHeader.black", "Black");
    String whiteText = text("SidebarHeader.white", "White");

    int commentsTextX = CONTROL_X;
    int problemsTextX = commentsTextX + classicSecondLabelOffset(metrics, commentsText);
    int blackTextX =
        Math.max(
            CLASSIC_SIDE_X, problemsTextX + metrics.stringWidth(problemsText) + CLASSIC_LABEL_GAP);
    int whiteTextX = blackTextX + classicSecondLabelOffset(metrics, blackText);
    int whiteRight = whiteTextX + metrics.stringWidth(whiteText);
    int progressReserve = progressText.isEmpty() ? 0 : metrics.stringWidth(progressText) + 26;
    boolean wrap = showingBlunders && whiteRight + progressReserve > availableWidth;
    if (wrap) {
      blackTextX = CONTROL_X;
      whiteTextX = blackTextX + classicSecondLabelOffset(metrics, blackText);
    }

    int primaryY = CLASSIC_PRIMARY_Y;
    int sideY = wrap ? CLASSIC_PRIMARY_Y + CLASSIC_ROW_HEIGHT : CLASSIC_PRIMARY_Y;
    int primaryBaseline = CLASSIC_PRIMARY_BASELINE;
    int sideBaseline =
        wrap ? CLASSIC_PRIMARY_BASELINE + CLASSIC_ROW_HEIGHT : CLASSIC_PRIMARY_BASELINE;

    Rectangle comments = classicTextBounds(metrics, commentsText, commentsTextX, primaryY);
    Rectangle problems =
        classicSecondTextBounds(metrics, commentsText, problemsText, primaryY, commentsTextX);
    if (showingBlunders && !wrap && problems.x + problems.width > blackTextX) {
      problems.width = Math.max(1, blackTextX - problems.x);
    }
    Rectangle black =
        showingBlunders
            ? classicTextBounds(metrics, blackText, blackTextX, sideY)
            : new Rectangle();
    Rectangle white =
        showingBlunders
            ? classicSecondTextBounds(metrics, blackText, whiteText, sideY, blackTextX)
            : new Rectangle();

    int occupiedRight =
        showingBlunders && !wrap ? white.x + white.width : problems.x + problems.width;
    Rectangle progress =
        classicProgressBounds(
            metrics, progressText, availableWidth, occupiedRight, primaryBaseline);
    int height = wrap ? CLASSIC_WRAPPED_HEIGHT : preferredHeight(showingBlunders, false);
    return new HeaderLayout(
        comments,
        problems,
        black,
        white,
        progress,
        commentsTextX,
        problemsTextX,
        blackTextX,
        whiteTextX,
        primaryBaseline,
        sideBaseline,
        wrap,
        height);
  }

  private static HeaderLayout appleLayout(
      boolean showingBlunders, FontMetrics metrics, int availableWidth, String progressText) {
    String commentsText = text("SidebarHeader.comments", "Comments");
    String problemsText = text("SidebarHeader.problems", "Problems");
    String blackText = text("SidebarHeader.black", "Black");
    String whiteText = text("SidebarHeader.white", "White");

    int halfW =
        Math.max(
            APPLE_PRIMARY_WIDTH / 2,
            Math.max(metrics.stringWidth(commentsText), metrics.stringWidth(problemsText))
                + APPLE_LABEL_INSET);
    int primaryW = halfW * 2;
    int primaryX = CONTROL_X;
    int primaryY = APPLE_PRIMARY_Y;
    int blackContent = APPLE_DOT_SIZE + APPLE_DOT_GAP + metrics.stringWidth(blackText);
    int whiteContent = APPLE_DOT_SIZE + APPLE_DOT_GAP + metrics.stringWidth(whiteText);
    int sideHalf =
        Math.max(APPLE_SIDE_WIDTH / 2, Math.max(blackContent, whiteContent) + APPLE_LABEL_INSET);
    int sideW = sideHalf * 2;
    int sideX = Math.max(APPLE_SIDE_X, primaryX + primaryW + APPLE_CONTROL_GAP);
    int sideY = APPLE_SIDE_Y;
    int progressReserve = progressText.isEmpty() ? 0 : metrics.stringWidth(progressText) + 32;
    boolean wrap = showingBlunders && sideX + sideW + progressReserve > availableWidth;
    if (wrap) {
      sideX = CONTROL_X;
      sideY = APPLE_PRIMARY_Y + APPLE_PRIMARY_HEIGHT + APPLE_WRAP_GAP;
    }

    Rectangle comments = new Rectangle(primaryX, primaryY, halfW, APPLE_PRIMARY_HEIGHT);
    Rectangle problems = new Rectangle(primaryX + halfW, primaryY, halfW, APPLE_PRIMARY_HEIGHT);
    Rectangle black =
        showingBlunders
            ? new Rectangle(sideX, sideY, sideHalf, APPLE_SIDE_HEIGHT)
            : new Rectangle();
    Rectangle white =
        showingBlunders
            ? new Rectangle(sideX + sideHalf, sideY, sideHalf, APPLE_SIDE_HEIGHT)
            : new Rectangle();
    int occupiedRight = showingBlunders && !wrap ? sideX + sideW : primaryX + primaryW;
    Rectangle progress = appleProgressBounds(metrics, progressText, availableWidth, occupiedRight);
    int primaryBaseline = primaryY + APPLE_PRIMARY_HEIGHT / 2 + metrics.getAscent() / 2 - 1;
    int sideBaseline = sideY + APPLE_SIDE_HEIGHT / 2 + metrics.getAscent() / 2 - 1;
    int height = wrap ? APPLE_WRAPPED_HEIGHT : preferredHeight(showingBlunders, true);
    return new HeaderLayout(
        comments,
        problems,
        black,
        white,
        progress,
        primaryX + (halfW - metrics.stringWidth(commentsText)) / 2,
        primaryX + halfW + (halfW - metrics.stringWidth(problemsText)) / 2,
        showingBlunders
            ? sideX + (sideHalf - blackContent) / 2 + APPLE_DOT_SIZE + APPLE_DOT_GAP
            : 0,
        showingBlunders
            ? sideX + sideHalf + (sideHalf - whiteContent) / 2 + APPLE_DOT_SIZE + APPLE_DOT_GAP
            : 0,
        primaryBaseline,
        sideBaseline,
        wrap,
        height);
  }

  private static Rectangle classicProgressBounds(
      FontMetrics metrics,
      String progressText,
      int availableWidth,
      int occupiedRight,
      int baseline) {
    if (progressText.isEmpty() || availableWidth <= 0) {
      return new Rectangle();
    }
    int textWidth = metrics.stringWidth(progressText);
    int progressX = availableWidth - textWidth - 10;
    if (progressX <= occupiedRight + 8) {
      return new Rectangle();
    }
    return new Rectangle(progressX - 8, baseline - metrics.getAscent() - 1, textWidth + 16, 22);
  }

  private static Rectangle appleProgressBounds(
      FontMetrics metrics, String progressText, int availableWidth, int occupiedRight) {
    if (progressText.isEmpty() || availableWidth <= 0) {
      return new Rectangle();
    }
    int textWidth = metrics.stringWidth(progressText);
    int pillX = availableWidth - textWidth - 24;
    if (pillX <= occupiedRight + 8) {
      return new Rectangle();
    }
    return new Rectangle(pillX, APPLE_PRIMARY_Y, textWidth + 16, 28);
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
      FontMetrics metrics, String firstText, String text, int rowY, int firstTextX) {
    int textX = firstTextX + classicSecondLabelOffset(metrics, firstText);
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

  private static int classicSecondLabelOffset(FontMetrics metrics, String firstText) {
    return Math.max(
        CLASSIC_SECOND_LABEL_OFFSET, metrics.stringWidth(firstText) + CLASSIC_LABEL_GAP);
  }

  private Font headerFont() {
    String fontName =
        Lizzie.config != null && Lizzie.config.uiFontName != null
            ? Lizzie.config.uiFontName
            : getFont().getName();
    return new Font(fontName, Font.BOLD, 12);
  }

  private void installKeyboardActions() {
    getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "comments");
    getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "problems");
    getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_B, 0), "black");
    getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_W, 0), "white");
    getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "toggle");
    getActionMap()
        .put(
            "comments",
            new AbstractAction() {
              @Override
              public void actionPerformed(ActionEvent event) {
                parentPanel.switchTo("COMMENTS");
                repaint();
              }
            });
    getActionMap()
        .put(
            "problems",
            new AbstractAction() {
              @Override
              public void actionPerformed(ActionEvent event) {
                parentPanel.switchTo("BLUNDERS");
                repaint();
              }
            });
    getActionMap()
        .put(
            "black",
            new AbstractAction() {
              @Override
              public void actionPerformed(ActionEvent event) {
                Lizzie.frame.setProblemListSideFilter(ProblemListSideFilter.BLACK);
              }
            });
    getActionMap()
        .put(
            "white",
            new AbstractAction() {
              @Override
              public void actionPerformed(ActionEvent event) {
                Lizzie.frame.setProblemListSideFilter(ProblemListSideFilter.WHITE);
              }
            });
    getActionMap()
        .put(
            "toggle",
            new AbstractAction() {
              @Override
              public void actionPerformed(ActionEvent event) {
                parentPanel.switchTo(Lizzie.config.isShowingBlunderTabel ? "COMMENTS" : "BLUNDERS");
                repaint();
              }
            });
  }

  private void paintKeyboardFocus(Graphics2D graphics) {
    if (!isFocusOwner()) {
      return;
    }
    graphics.setColor(withAlpha(glassAccentColor(), 230));
    graphics.setStroke(new BasicStroke(2F));
    graphics.drawRoundRect(2, 2, Math.max(0, getWidth() - 5), Math.max(0, getHeight() - 5), 10, 10);
  }

  private static String text(String key, String fallback) {
    try {
      if (Lizzie.resourceBundle != null && Lizzie.resourceBundle.containsKey(key)) {
        return Lizzie.resourceBundle.getString(key);
      }
    } catch (Exception error) {
    }
    return fallback;
  }

  private static String format(String key, String fallback, Object... arguments) {
    return MessageFormat.format(text(key, fallback), arguments);
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

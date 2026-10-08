package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import java.awt.*;
import java.util.function.Consumer;
import javax.swing.*;

public class SidebarPanel extends JPanel {
  private SidebarHeaderPanel headerPanel;
  private JPanel cardPanel;
  private CardLayout cardLayout;
  private BlunderListPanel blunderListPanel;
  private JPanel commentsContainer;
  private CardLayout commentCardLayout;
  private final LizzieFrame parentFrame;
  private final JScrollPane commentScrollPane;
  private final JScrollPane commentEditPane;
  private static final String COMMENT_VIEW_CARD = "COMMENT_VIEW";
  private static final String COMMENT_EDIT_CARD = "COMMENT_EDIT";

  private Consumer<ProblemListSnapshot> snapshotListener;

  public SidebarPanel(
      LizzieFrame parentFrame, JScrollPane commentScrollPane, JScrollPane commentEditPane) {
    this.parentFrame = parentFrame;
    this.commentScrollPane = commentScrollPane;
    this.commentEditPane = commentEditPane;
    setOpaque(false);
    setLayout(new BorderLayout());
    setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

    headerPanel = new SidebarHeaderPanel(this);
    add(headerPanel, BorderLayout.NORTH);

    cardLayout = new CardLayout();
    cardPanel = new JPanel(cardLayout);
    cardPanel.setOpaque(false);

    // Comments Container
    commentsContainer = new JPanel();
    commentsContainer.setOpaque(false);
    commentCardLayout = new CardLayout();
    commentsContainer.setLayout(commentCardLayout);
    commentScrollPane.setOpaque(false);
    commentScrollPane.getViewport().setOpaque(false);
    commentScrollPane.setBorder(BorderFactory.createEmptyBorder());
    commentEditPane.setOpaque(false);
    commentEditPane.getViewport().setOpaque(false);
    commentEditPane.setBorder(BorderFactory.createEmptyBorder());
    commentsContainer.add(commentScrollPane, COMMENT_VIEW_CARD);
    commentsContainer.add(commentEditPane, COMMENT_EDIT_CARD);
    cardPanel.add(commentsContainer, "COMMENTS");

    // Blunders Container
    blunderListPanel = new BlunderListPanel();
    cardPanel.add(blunderListPanel, "BLUNDERS");

    add(cardPanel, BorderLayout.CENTER);

    snapshotListener =
        snapshot -> {
          SwingUtilities.invokeLater(
              () -> {
                headerPanel.updateSnapshot(snapshot);
                blunderListPanel.updateSnapshot(snapshot);
              });
        };
    parentFrame.addProblemListListener(snapshotListener);

    ProblemListSnapshot initialSnapshot = parentFrame.getProblemListSnapshot();
    if (initialSnapshot != null) {
      headerPanel.updateSnapshot(initialSnapshot);
      blunderListPanel.updateSnapshot(initialSnapshot);
    }

    // Initial state
    switchTo(Lizzie.config.isShowingBlunderTabel ? "BLUNDERS" : "COMMENTS");
  }

  public void switchTo(String viewName) {
    cardLayout.show(cardPanel, viewName);
    if ("COMMENTS".equals(viewName)) {
      Lizzie.config.isShowingBlunderTabel = false;
      headerPanel.setShowingBlunders(false);
      syncCommentVisibility();
      parentFrame.appendComment();
    } else {
      Lizzie.config.isShowingBlunderTabel = true;
      headerPanel.setShowingBlunders(true);
      parentFrame.requestProblemListRefresh();
    }
    Lizzie.config.uiConfig.put("is-showing-blunder-table", Lizzie.config.isShowingBlunderTabel);
    revalidate();
    repaint();
    headerPanel.repaint();
  }

  void syncCommentVisibility() {
    if (commentEditPane.isVisible()) {
      commentScrollPane.setVisible(false);
      commentEditPane.setVisible(true);
      commentCardLayout.show(commentsContainer, COMMENT_EDIT_CARD);
    } else {
      commentScrollPane.setVisible(true);
      commentEditPane.setVisible(false);
      commentCardLayout.show(commentsContainer, COMMENT_VIEW_CARD);
    }
    commentsContainer.revalidate();
    commentsContainer.repaint();
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    g.setColor(
        resolveCommentPanelFillColor(
            Lizzie.config.commentBackgroundColor, Lizzie.config.isAppleStyle));
    g.fillRect(0, 0, getWidth(), getHeight());
    g.setColor(rowSeparatorColor());
    g.drawRect(0, 0, Math.max(0, getWidth() - 1), Math.max(0, getHeight() - 1));
  }

  private static boolean darkSurface() {
    Color color =
        Lizzie.config == null
            ? new Color(30, 33, 38)
            : resolveCommentPanelFillColor(
                Lizzie.config.commentBackgroundColor, Lizzie.config.isAppleStyle);
    return color.getRed() * 0.2126 + color.getGreen() * 0.7152 + color.getBlue() * 0.0722 < 140;
  }

  static Color primaryTextColor() {
    return darkSurface() ? new Color(239, 242, 241) : new Color(28, 34, 32);
  }

  static Color secondaryTextColor() {
    return darkSurface() ? new Color(185, 195, 191) : new Color(72, 83, 79);
  }

  static Color accentTextColor() {
    return darkSurface() ? new Color(105, 211, 186) : new Color(18, 107, 98);
  }

  static Color lossTextColor() {
    return darkSurface() ? new Color(255, 176, 164) : new Color(155, 43, 35);
  }

  static Color rowSeparatorColor() {
    return darkSurface() ? new Color(255, 255, 255, 28) : new Color(0, 0, 0, 24);
  }

  static Color rowHighlightColor(boolean selected) {
    Color accent = accentTextColor();
    return new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), selected ? 25 : 12);
  }

  static Color resolveCommentPanelFillColor(Color configuredColor, boolean appleStyle) {
    if (configuredColor == null) {
      return appleStyle ? new Color(24, 24, 26, 156) : new Color(30, 33, 38, 200);
    }
    return new Color(
        configuredColor.getRed(),
        configuredColor.getGreen(),
        configuredColor.getBlue(),
        clampAlpha(configuredColor.getAlpha()));
  }

  private static int clampAlpha(int alpha) {
    return Math.max(0, Math.min(255, alpha));
  }
}

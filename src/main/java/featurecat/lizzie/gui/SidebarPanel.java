package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.function.Consumer;
import javax.swing.*;

public class SidebarPanel extends JPanel {
  private SidebarHeaderPanel headerPanel;
  private JPanel cardPanel;
  private CardLayout cardLayout;
  private BlunderListPanel blunderListPanel;
  private JPanel commentsContainer;
  private final LizzieFrame parentFrame;
  private final JScrollPane commentScrollPane;
  private final JScrollPane commentEditPane;

  private Consumer<ProblemListSnapshot> snapshotListener;

  public SidebarPanel(
      LizzieFrame parentFrame, JScrollPane commentScrollPane, JScrollPane commentEditPane) {
    this.parentFrame = parentFrame;
    this.commentScrollPane = commentScrollPane;
    this.commentEditPane = commentEditPane;
    setOpaque(false);
    setLayout(new BorderLayout());
    setBorder(BorderFactory.createEmptyBorder(8, 8, 10, 10));

    headerPanel = new SidebarHeaderPanel(this);
    add(headerPanel, BorderLayout.NORTH);

    cardLayout = new CardLayout();
    cardPanel = new JPanel(cardLayout);
    cardPanel.setOpaque(false);

    // Comments Container
    commentsContainer = new JPanel();
    commentsContainer.setOpaque(false);
    commentsContainer.setLayout(new OverlayLayout(commentsContainer));
    commentScrollPane.setOpaque(false);
    commentScrollPane.getViewport().setOpaque(false);
    commentScrollPane.setBorder(BorderFactory.createEmptyBorder());
    commentEditPane.setOpaque(false);
    commentEditPane.getViewport().setOpaque(false);
    commentEditPane.setBorder(BorderFactory.createEmptyBorder());
    commentScrollPane.setAlignmentX(0.5f);
    commentScrollPane.setAlignmentY(0.5f);
    commentEditPane.setAlignmentX(0.5f);
    commentEditPane.setAlignmentY(0.5f);
    commentsContainer.add(commentEditPane);
    commentsContainer.add(commentScrollPane);
    cardPanel.add(commentsContainer, "COMMENTS");

    // Blunders Container
    blunderListPanel = new BlunderListPanel();
    JScrollPane blunderScrollPane = new JScrollPane(blunderListPanel);
    blunderScrollPane.setOpaque(false);
    blunderScrollPane.getViewport().setOpaque(false);
    blunderScrollPane.setBorder(BorderFactory.createEmptyBorder());
    blunderScrollPane.getVerticalScrollBar().setUnitIncrement(16);
    blunderScrollPane.getVerticalScrollBar().setUI(new DemoScrollBarUI());

    cardPanel.add(blunderScrollPane, "BLUNDERS");

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
      syncCommentVisibility();
      parentFrame.appendComment();
    } else {
      Lizzie.config.isShowingBlunderTabel = true;
      parentFrame.requestProblemListRefresh();
    }
    Lizzie.config.uiConfig.put("is-showing-blunder-table", Lizzie.config.isShowingBlunderTabel);
    revalidate();
    repaint();
    headerPanel.repaint();
  }

  private void syncCommentVisibility() {
    if (commentEditPane.isVisible()) {
      commentScrollPane.setVisible(false);
    } else {
      commentScrollPane.setVisible(true);
      commentEditPane.setVisible(false);
    }
    commentsContainer.revalidate();
    commentsContainer.repaint();
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    Graphics2D g2 = (Graphics2D) g.create();
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    int width = getWidth();
    int height = getHeight();
    int cornerRadius = Math.max(14, glassCornerRadius() + 8);
    int shadowInset = 5;
    int panelX = 1;
    int panelY = 1;
    int panelW = Math.max(0, width - 2);
    int panelH = Math.max(0, height - 2);

    if (!Lizzie.config.isAppleStyle) {
      g2.setColor(new Color(0, 0, 0, 52));
      g2.fillRoundRect(
          panelX + shadowInset,
          panelY + shadowInset,
          panelW - shadowInset,
          panelH - shadowInset,
          cornerRadius,
          cornerRadius);
      g2.setColor(new Color(28, 29, 33, 220));
      g2.fillRoundRect(panelX, panelY, panelW, panelH, cornerRadius, cornerRadius);
      g2.setColor(new Color(255, 255, 255, 18));
      g2.drawRoundRect(panelX, panelY, panelW - 1, panelH - 1, cornerRadius, cornerRadius);
      g2.dispose();
      return;
    }

    Color accent = glassAccentColor();
    Color overlay = glassPanelOverlayColor();
    Color shadowColor = glassPanelShadowColor();

    g2.setPaint(withAlpha(shadowColor, Math.max(90, shadowColor.getAlpha() + 32)));
    g2.fillRoundRect(
        panelX + shadowInset,
        panelY + shadowInset,
        Math.max(0, panelW - shadowInset),
        Math.max(0, panelH - shadowInset),
        cornerRadius,
        cornerRadius);

    RadialGradientPaint accentGlow =
        new RadialGradientPaint(
            new Point2D.Float(panelW * 0.72f, panelH * 0.02f),
            Math.max(panelW, panelH) * 0.9f,
            new float[] {0.0f, 0.35f, 1.0f},
            new Color[] {
              withAlpha(accent, 120), new Color(255, 255, 255, 18), new Color(0, 0, 0, 0)
            });
    g2.setPaint(accentGlow);
    g2.fillRoundRect(panelX, panelY, panelW, panelH, cornerRadius, cornerRadius);

    LinearGradientPaint bodyFill =
        new LinearGradientPaint(
            0,
            0,
            0,
            Math.max(1, panelH),
            new float[] {0.0f, 0.45f, 1.0f},
            new Color[] {
              withAlpha(overlay, Math.max(150, overlay.getAlpha() + 48)),
              withAlpha(overlay, Math.max(132, overlay.getAlpha() + 28)),
              new Color(18, 22, 28, 176)
            });
    g2.setPaint(bodyFill);
    g2.fillRoundRect(panelX, panelY, panelW, panelH, cornerRadius, cornerRadius);

    LinearGradientPaint highlight =
        new LinearGradientPaint(
            panelX,
            panelY,
            panelX,
            panelY + Math.max(1, panelH / 2),
            new float[] {0.0f, 0.45f, 1.0f},
            new Color[] {
              withAlpha(glassPanelHighlightColor(), 132),
              withAlpha(accent, 46),
              new Color(255, 255, 255, 0)
            });
    g2.setPaint(highlight);
    g2.drawRoundRect(panelX, panelY, panelW - 1, panelH - 1, cornerRadius, cornerRadius);

    g2.setPaint(withAlpha(glassPanelBorderColor(), 72));
    g2.drawRoundRect(panelX, panelY, panelW - 1, panelH - 1, cornerRadius, cornerRadius);

    g2.setPaint(new Color(255, 255, 255, 22));
    g2.drawRoundRect(
        panelX + 1, panelY + 1, panelW - 3, panelH - 3, cornerRadius - 2, cornerRadius - 2);

    g2.dispose();
  }

  private Color glassPanelOverlayColor() {
    return Lizzie.config != null && Lizzie.config.theme != null
        ? Lizzie.config.theme.glassPanelOverlayColor()
        : new Color(24, 24, 26, 102);
  }

  private Color glassPanelBorderColor() {
    return Lizzie.config != null && Lizzie.config.theme != null
        ? Lizzie.config.theme.glassPanelBorderColor()
        : new Color(255, 255, 255, 26);
  }

  private Color glassPanelHighlightColor() {
    return Lizzie.config != null && Lizzie.config.theme != null
        ? Lizzie.config.theme.glassPanelHighlightColor()
        : new Color(255, 255, 255, 77);
  }

  private Color glassPanelShadowColor() {
    return Lizzie.config != null && Lizzie.config.theme != null
        ? Lizzie.config.theme.glassPanelShadowColor()
        : new Color(0, 0, 0, 64);
  }

  private Color glassAccentColor() {
    return Lizzie.config != null && Lizzie.config.theme != null
        ? Lizzie.config.theme.glassAccentColor()
        : new Color(96, 165, 250);
  }

  private int glassCornerRadius() {
    return Lizzie.config != null && Lizzie.config.theme != null
        ? Lizzie.config.theme.glassCornerRadius()
        : 12;
  }

  private Color withAlpha(Color color, int alpha) {
    return new Color(
        color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha)));
  }
}

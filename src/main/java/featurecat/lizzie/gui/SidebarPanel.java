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

  private static volatile SurfacePalette cachedSurfacePalette;

  static Color primaryTextColor() {
    return surfacePalette().primary();
  }

  static Color secondaryTextColor() {
    return surfacePalette().secondary();
  }

  static Color accentTextColor() {
    return surfacePalette().accent();
  }

  static Color lossTextColor() {
    return surfacePalette().loss();
  }

  static Color rowSeparatorColor() {
    return surfacePalette().dark() ? new Color(255, 255, 255, 28) : new Color(0, 0, 0, 24);
  }

  static Color rowHighlightColor(boolean selected) {
    SurfacePalette palette = surfacePalette();
    Color tint = palette.highlight();
    return new Color(
        tint.getRed(), tint.getGreen(), tint.getBlue(),
        selected ? palette.highlightAlpha() : Math.min(12, palette.highlightAlpha()));
  }

  private static SurfacePalette surfacePalette() {
    Color fill = Lizzie.config == null
        ? new Color(30, 33, 38)
        : resolveCommentPanelFillColor(
            Lizzie.config.commentBackgroundColor, Lizzie.config.isAppleStyle);
    SurfacePalette cached = cachedSurfacePalette;
    if (cached != null && cached.fill().equals(fill)) return cached;
    SurfacePalette palette = createSurfacePalette(fill);
    cachedSurfacePalette = palette;
    return palette;
  }

  private static SurfacePalette createSurfacePalette(Color fill) {
    Color[] surfaces = surfaceBounds(fill);
    boolean dark = minimumContrast(Color.WHITE, surfaces) >= minimumContrast(Color.BLACK, surfaces);
    Color primary = dark ? new Color(239, 242, 241) : new Color(28, 34, 32);
    Color secondary = dark ? new Color(185, 195, 191) : new Color(72, 83, 79);
    Color accent = dark ? new Color(105, 211, 186) : new Color(18, 107, 98);
    Color loss = dark ? new Color(255, 176, 164) : new Color(155, 43, 35);
    if (!supportsTextContrast(surfaces)) {
      // An unknown wallpaper behind a low-alpha fill can span both light and dark pixels.
      // No single foreground guarantees contrast there. Preserve user alpha and the
      // existing palette rather than treating a guessed underlay as the actual surface.
      dark = fill.getRed() * 0.2126 + fill.getGreen() * 0.7152 + fill.getBlue() * 0.0722 < 140;
      return new SurfacePalette(fill, dark,
          dark ? new Color(239, 242, 241) : new Color(28, 34, 32),
          dark ? new Color(185, 195, 191) : new Color(72, 83, 79),
          dark ? new Color(105, 211, 186) : new Color(18, 107, 98),
          dark ? new Color(255, 176, 164) : new Color(155, 43, 35),
          dark ? new Color(105, 211, 186) : new Color(18, 107, 98), 25);
    }
    Color tint = readableColor(accent, surfaces, 3.0);
    int highlightAlpha = 25;
    Color[] painted = highlightedSurfaces(surfaces, tint, highlightAlpha);
    while (highlightAlpha > 0 && !supportsTextContrast(painted)) {
      painted = highlightedSurfaces(surfaces, tint, --highlightAlpha);
    }
    return new SurfacePalette(fill, dark,
        readableColor(primary, painted, 4.5),
        readableColor(secondary, painted, 4.5),
        readableColor(accent, painted, 3.0),
        readableColor(loss, painted, 4.5),
        tint, highlightAlpha);
  }

  private record SurfacePalette(
      Color fill, boolean dark, Color primary, Color secondary, Color accent,
      Color loss, Color highlight, int highlightAlpha) {}

  private static Color[] surfaceBounds(Color fill) {
    if (fill.getAlpha() == 255) return new Color[] {fill};
    // Bound every possible underlay, including image pixels. These are conservative
    // extrema, not a claim that the actual wallpaper is black or white.
    return new Color[] {
        expandBound(composite(fill, Color.BLACK), -1),
        expandBound(composite(fill, Color.WHITE), 1)
    };
  }

  private static Color expandBound(Color color, int delta) {
    return new Color(
        clampAlpha(color.getRed() + delta),
        clampAlpha(color.getGreen() + delta),
        clampAlpha(color.getBlue() + delta));
  }

  private static Color[] highlightedSurfaces(Color[] surfaces, Color tint, int alpha) {
    Color selected = new Color(tint.getRed(), tint.getGreen(), tint.getBlue(), alpha);
    Color hovered = new Color(tint.getRed(), tint.getGreen(), tint.getBlue(), Math.min(12, alpha));
    if (alpha == 0) return surfaces;
    Color[] painted = new Color[surfaces.length * 5];
    for (int i = 0; i < surfaces.length; i++) {
      painted[i * 5] = surfaces[i];
      Color selectedSurface = composite(selected, surfaces[i]);
      Color hoveredSurface = composite(hovered, surfaces[i]);
      // Cover the one-channel rounding difference of software/native alpha compositing.
      painted[i * 5 + 1] = expandBound(selectedSurface, -1);
      painted[i * 5 + 2] = expandBound(selectedSurface, 1);
      painted[i * 5 + 3] = expandBound(hoveredSurface, -1);
      painted[i * 5 + 4] = expandBound(hoveredSurface, 1);
    }
    return painted;
  }

  private static boolean supportsTextContrast(Color[] surfaces) {
    return Math.max(minimumContrast(Color.BLACK, surfaces), minimumContrast(Color.WHITE, surfaces))
        >= 4.5;
  }

  private static Color readableColor(Color preferred, Color[] surfaces, double minimum) {
    if (minimumContrast(preferred, surfaces) >= minimum) return preferred;
    Color endpoint = minimumContrast(Color.BLACK, surfaces) >= minimumContrast(Color.WHITE, surfaces)
        ? Color.BLACK : Color.WHITE;
    int low = 0;
    int high = 255;
    while (low < high) {
      int amount = (low + high) / 2;
      Color candidate = new Color(
          blendChannel(preferred.getRed(), endpoint.getRed(), amount),
          blendChannel(preferred.getGreen(), endpoint.getGreen(), amount),
          blendChannel(preferred.getBlue(), endpoint.getBlue(), amount));
      if (minimumContrast(candidate, surfaces) >= minimum) high = amount;
      else low = amount + 1;
    }
    return new Color(
        blendChannel(preferred.getRed(), endpoint.getRed(), high),
        blendChannel(preferred.getGreen(), endpoint.getGreen(), high),
        blendChannel(preferred.getBlue(), endpoint.getBlue(), high));
  }

  private static Color composite(Color overlay, Color background) {
    return new Color(
        blendChannel(background.getRed(), overlay.getRed(), overlay.getAlpha()),
        blendChannel(background.getGreen(), overlay.getGreen(), overlay.getAlpha()),
        blendChannel(background.getBlue(), overlay.getBlue(), overlay.getAlpha()));
  }

  private static int blendChannel(int start, int end, int amount) {
    return (start * (255 - amount) + end * amount + 127) / 255;
  }

  private static double minimumContrast(Color foreground, Color[] surfaces) {
    double first = luminance(foreground);
    double minimum = Double.POSITIVE_INFINITY;
    for (Color surface : surfaces) {
      double second = luminance(surface);
      minimum = Math.min(minimum,
          (Math.max(first, second) + 0.05) / (Math.min(first, second) + 0.05));
    }
    return minimum;
  }

  private static double luminance(Color color) {
    return 0.2126 * linearChannel(color.getRed())
        + 0.7152 * linearChannel(color.getGreen())
        + 0.0722 * linearChannel(color.getBlue());
  }

  private static double linearChannel(int value) {
    double channel = value / 255.0;
    return channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
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

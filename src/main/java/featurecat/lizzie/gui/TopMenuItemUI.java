package featurecat.lizzie.gui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import javax.swing.Icon;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.plaf.basic.BasicGraphicsUtils;
import javax.swing.plaf.basic.BasicMenuItemUI;
import javax.swing.plaf.basic.BasicMenuUI;

/**
 * Paints the items of the top menus (文件, 显示, 棋局, …) the same way under every look and feel.
 *
 * <p>A row is the label, a right-aligned shortcut hint in a quieter colour, and a thin chevron for
 * submenus. Check marks, radio dots and icons share one leading column, which a menu reserves only
 * when one of its items needs it. The highlight is a rounded fill inset from the menu's edges.
 * Behaviour (hover, keys, submenus) is the Basic look and feel's; only painting and size change.
 */
final class TopMenuItemUI extends BasicMenuItemUI {
  static final int PADDING = 10;
  static final int MARK = 16;
  static final int MARK_GAP = 7;
  static final int HINT_GAP = 32;
  static final int CHEVRON = 14;
  private static final int ROW_EXTRA = 8;
  private static final int HIGHLIGHT_INSET = 3;
  private static final int HIGHLIGHT_ARC = 8;

  @Override
  public Dimension getPreferredSize(JComponent c) {
    return preferredSize((JMenuItem) c);
  }

  @Override
  protected void paintMenuItem(
      Graphics g,
      JComponent c,
      Icon checkIcon,
      Icon arrowIcon,
      Color background,
      Color foreground,
      int defaultTextIconGap) {
    paintRow(g, (JMenuItem) c);
  }

  /** The same rows for submenus, keeping Basic's submenu opening and keyboard handling. */
  static final class Submenu extends BasicMenuUI {
    @Override
    public Dimension getPreferredSize(JComponent c) {
      return preferredSize((JMenuItem) c);
    }

    @Override
    protected void paintMenuItem(
        Graphics g,
        JComponent c,
        Icon checkIcon,
        Icon arrowIcon,
        Color background,
        Color foreground,
        int defaultTextIconGap) {
      paintRow(g, (JMenuItem) c);
    }
  }

  /** What a row shows: its label, its shortcut hint (or {@code null}) and its menu's columns. */
  record Row(String label, String hint, int markColumn, boolean chevronColumn, int reservedRight) {
    static Row of(JMenuItem item) {
      String hint = (String) item.getClientProperty(TopMenuStyle.HINT_PROPERTY);
      MenuShortcutHint.Split split =
          Boolean.TRUE.equals(item.getClientProperty(TopMenuStyle.USER_TEXT_PROPERTY))
              ? new MenuShortcutHint.Split(item.getText(), null)
              : MenuShortcutHint.split(item.getText());
      if (hint == null) hint = split.hint();
      if (hint == null) hint = MenuShortcutHint.format(item.getAccelerator());
      boolean mark = needsMark(item);
      boolean chevron = item instanceof JMenu;
      Container parent = item.getParent();
      if (parent instanceof JPopupMenu) {
        for (Component sibling : parent.getComponents()) {
          if (sibling instanceof JMenuItem other && sibling.isVisible()) {
            mark |= needsMark(other);
            chevron |= other instanceof JMenu;
          }
        }
      }
      int reserved =
          item.getLayout() instanceof TopMenuStyle.CompositeRow composite
              ? composite.reservedRight()
              : 0;
      if (item.getClientProperty(TopMenuStyle.TRAILING_SPACE_PROPERTY) instanceof Integer extra) {
        reserved += extra;
      }
      return new Row(split.label(), hint, mark ? MARK : 0, chevron, reserved);
    }

    /** Where labels start, so composite rows can align their own label with the others. */
    int textX() {
      return PADDING + (markColumn > 0 ? markColumn + MARK_GAP : 0);
    }

    /** Where hints end: before the chevrons, and before a composite row's button. */
    int hintRight(int width) {
      return width - PADDING - (chevronColumn ? CHEVRON : 0) - reservedRight;
    }
  }

  private static boolean needsMark(JMenuItem item) {
    return item instanceof JCheckBoxMenuItem
        || item instanceof JRadioButtonMenuItem
        || item.getIcon() != null;
  }

  /** A label's width in whole pixels, as the item's font renders it. */
  static int textWidth(JMenuItem item, String text) {
    FontMetrics metrics = item.getFontMetrics(item.getFont());
    return (int) Math.ceil(BasicGraphicsUtils.getStringWidth(item, metrics, text));
  }

  static int rowHeight(JMenuItem item) {
    return item.getFontMetrics(item.getFont()).getHeight() + ROW_EXTRA;
  }

  static Dimension preferredSize(JMenuItem item) {
    if (item.getLayout() instanceof TopMenuStyle.CompositeRow composite) {
      return composite.preferredLayoutSize(item);
    }
    Row row = Row.of(item);
    int width = row.textX() + textWidth(item, row.label());
    if (row.hint() != null) {
      width += HINT_GAP + textWidth(item, row.hint());
    }
    width += PADDING + (row.chevronColumn() ? CHEVRON : 0);
    return new Dimension(width, rowHeight(item));
  }

  static void paintRow(Graphics graphics, JMenuItem item) {
    Graphics2D g = (Graphics2D) graphics.create();
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
      int width = item.getWidth(), height = item.getHeight();
      g.setColor(AppleStyleSupport.popupSurfaceColor());
      g.fillRect(0, 0, width, height);
      boolean enabled = item.isEnabled();
      boolean highlighted =
          enabled && (item.isArmed() || (item instanceof JMenu && item.isSelected()));
      if (highlighted) {
        g.setColor(AppleStyleSupport.popupSelectionColor());
        g.fill(
            new RoundRectangle2D.Float(
                HIGHLIGHT_INSET,
                1,
                width - 2 * HIGHLIGHT_INSET,
                height - 2,
                HIGHLIGHT_ARC,
                HIGHLIGHT_ARC));
      }
      Row row = Row.of(item);
      Color text = AppleStyleSupport.controlTextColor();
      Color ink = enabled ? text : fade(text, 0.38f);
      paintMark(g, item, row, ink, height);
      FontMetrics metrics = item.getFontMetrics(item.getFont());
      g.setFont(item.getFont());
      float baseline = (height - metrics.getHeight()) / 2f + metrics.getAscent();
      g.setColor(ink);
      BasicGraphicsUtils.drawString(item, g, row.label(), row.textX(), baseline);
      if (row.hint() != null) {
        int hintWidth = textWidth(item, row.hint());
        g.setColor(fade(text, enabled ? (highlighted ? 0.72f : 0.5f) : 0.3f));
        BasicGraphicsUtils.drawString(
            item, g, row.hint(), row.hintRight(width) - hintWidth, baseline);
      }
      if (item instanceof JMenu) {
        paintChevron(g, width - PADDING - 5, height / 2f, ink);
      }
    } finally {
      g.dispose();
    }
  }

  private static void paintMark(Graphics2D g, JMenuItem item, Row row, Color ink, int height) {
    if (row.markColumn() == 0) {
      return;
    }
    float centerX = PADDING + MARK / 2f, centerY = height / 2f;
    if (item instanceof JCheckBoxMenuItem) {
      if (item.isSelected()) paintCheck(g, centerX, centerY, ink);
      return;
    }
    if (item instanceof JRadioButtonMenuItem) {
      if (item.isSelected()) {
        g.setColor(ink);
        g.fill(new java.awt.geom.Ellipse2D.Float(centerX - 3, centerY - 3, 6, 6));
      }
      return;
    }
    Icon icon =
        item.isEnabled() || item.getDisabledIcon() == null
            ? item.getIcon()
            : item.getDisabledIcon();
    if (icon == null) {
      return;
    }
    Graphics2D iconGraphics = (Graphics2D) g.create();
    try {
      // Icons larger than the column, such as the engine menu's, are drawn at its size.
      double scale =
          Math.min(1.0, MARK / (double) Math.max(icon.getIconWidth(), icon.getIconHeight()));
      iconGraphics.translate(
          centerX - icon.getIconWidth() * scale / 2, centerY - icon.getIconHeight() * scale / 2);
      iconGraphics.scale(scale, scale);
      iconGraphics.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
      icon.paintIcon(item, iconGraphics, 0, 0);
    } finally {
      iconGraphics.dispose();
    }
  }

  /** A check on the toolbar glyphs' 24-unit grid, drawn at the column's 16 pixels. */
  private static void paintCheck(Graphics2D g, float centerX, float centerY, Color ink) {
    float unit = MARK / 24f;
    Path2D.Float check = new Path2D.Float();
    check.moveTo(centerX - 6.5f * unit, centerY + 0.5f * unit);
    check.lineTo(centerX - 2f * unit, centerY + 5f * unit);
    check.lineTo(centerX + 7f * unit, centerY - 5.5f * unit);
    g.setColor(ink);
    g.setStroke(new BasicStroke(2.4f * unit, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    g.draw(check);
  }

  private static void paintChevron(Graphics2D g, float x, float centerY, Color ink) {
    Path2D.Float chevron = new Path2D.Float();
    chevron.moveTo(x, centerY - 4);
    chevron.lineTo(x + 4, centerY);
    chevron.lineTo(x, centerY + 4);
    g.setColor(fade(ink, 0.75f));
    g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    g.draw(chevron);
  }

  static Color fade(Color color, float opacity) {
    return new Color(
        color.getRed(), color.getGreen(), color.getBlue(), Math.round(color.getAlpha() * opacity));
  }
}

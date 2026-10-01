package featurecat.lizzie.teacher;

import featurecat.lizzie.gui.AppleStyleSupport;
import featurecat.lizzie.util.LocaleFontSupport;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.net.URL;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.ButtonModel;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.border.AbstractBorder;
import javax.swing.plaf.basic.BasicButtonUI;

/** Visual tokens shared by the AI commentary window and its headless view tests. */
final class TeacherDialogStyle {
  private static final int CORNER_RADIUS = 8;
  private static final Color LIGHT_BACKGROUND = new Color(247, 249, 248);
  private static final Color LIGHT_SURFACE = new Color(255, 255, 255);
  private static final Color LIGHT_TEXT = new Color(35, 42, 39);
  private static final Color LIGHT_MUTED = new Color(105, 112, 108);
  private static final Color LIGHT_BORDER = new Color(213, 218, 215);
  private static final Color LIGHT_ACCENT = new Color(15, 118, 110);
  private static final Color DARK_ACCENT = new Color(77, 182, 172);
  private static final Color LIGHT_DANGER = new Color(181, 70, 51);
  private static final Color DARK_DANGER = new Color(235, 127, 107);

  private TeacherDialogStyle() {}

  enum ModeGlyph {
    NEXT,
    RANGE,
    WHOLE
  }

  static Color background() {
    return AppleStyleSupport.workspaceBackground();
  }

  static Color surface() {
    return AppleStyleSupport.workspaceSurface();
  }

  static Color railSurface() {
    return blend(surface(), background(), 0.28f);
  }

  static Color text() {
    return AppleStyleSupport.dialogTextColor();
  }

  static Color muted() {
    return AppleStyleSupport.workspaceMuted();
  }

  static Color border() {
    return AppleStyleSupport.workspaceBorder();
  }

  static Color accent() {
    return AppleStyleSupport.workspaceAccent();
  }

  static Color accentSoft() {
    return isDark(background()) ? new Color(27, 58, 55) : new Color(229, 243, 240);
  }

  static Color danger() {
    return isDark(background()) ? DARK_DANGER : LIGHT_DANGER;
  }

  static Color dangerSoft() {
    return isDark(background()) ? new Color(70, 38, 34) : new Color(252, 239, 235);
  }

  static Color warning() {
    return isDark(background()) ? new Color(238, 186, 102) : new Color(184, 118, 23);
  }

  static void stylePrimary(AbstractButton button) {
    styleButton(button, LIGHT_ACCENT, Color.WHITE, LIGHT_ACCENT);
  }

  static void styleSecondary(AbstractButton button) {
    styleButton(button, surface(), text(), border());
  }

  static void styleDanger(AbstractButton button) {
    styleButton(button, dangerSoft(), danger(), blend(danger(), dangerSoft(), 0.55f));
  }

  static void styleModeButton(AbstractButton button, ModeGlyph glyph) {
    localizeFonts(button);
    button.setUI(new ModeButtonUI());
    button.setContentAreaFilled(false);
    button.setOpaque(false);
    button.setFocusPainted(false);
    button.setRolloverEnabled(true);
    button.setForeground(text());
    button.setFont(button.getFont().deriveFont(java.awt.Font.BOLD));
    button.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
    button.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
    button.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
    button.setIconTextGap(7);
    button.setIcon(new ModeIcon(glyph));
    button.setBorder(BorderFactory.createEmptyBorder(12, 10, 12, 10));
    int textWidth = button.getFontMetrics(button.getFont()).stringWidth(button.getText());
    Dimension size =
        new Dimension(
            Math.max(92, textWidth + button.getInsets().left + button.getInsets().right + 8),
            Math.max(96, button.getFontMetrics(button.getFont()).getHeight() + 64));
    button.setPreferredSize(size);
    button.setMinimumSize(size);
    button.setMaximumSize(size);
  }

  static Icon commentaryIcon() {
    return new CommentaryIcon();
  }

  static void styleInput(JComponent component) {
    localizeFonts(component);
    if (component instanceof javax.swing.JComboBox) {
      AppleStyleSupport.installComboBoxStyle((javax.swing.JComboBox<?>) component);
    }
    component.setOpaque(true);
    component.setBackground(surface());
    component.setForeground(text());
    component.setBorder(
        BorderFactory.createCompoundBorder(
            new RoundedBorder(border(), CORNER_RADIUS),
            BorderFactory.createEmptyBorder(6, 10, 6, 10)));
  }

  static void styleSpinner(JSpinner spinner) {
    spinner.setOpaque(true);
    spinner.setBackground(surface());
    spinner.setBorder(new RoundedBorder(border(), CORNER_RADIUS));
    if (spinner.getEditor() instanceof JSpinner.DefaultEditor) {
      JTextField field = ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField();
      field
          .getAccessibleContext()
          .setAccessibleName(spinner.getAccessibleContext().getAccessibleName());
      field
          .getAccessibleContext()
          .setAccessibleDescription(spinner.getAccessibleContext().getAccessibleDescription());
      field.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 4));
      field.setBackground(surface());
      field.setForeground(text());
      field.setHorizontalAlignment(JTextField.CENTER);
    }
    Dimension size = new Dimension(62, 34);
    spinner.setPreferredSize(size);
    spinner.setMinimumSize(size);
  }

  static void installSettingsIcon(AbstractButton button) {
    URL resource = TeacherDialogStyle.class.getResource("/assets/config.png");
    if (resource == null || isDark(background())) {
      return;
    }
    Image image = new ImageIcon(resource).getImage().getScaledInstance(16, 16, Image.SCALE_SMOOTH);
    button.setIcon(new ImageIcon(image));
    button.setIconTextGap(6);
  }

  static String cssColor(Color color) {
    Color safe = color == null ? Color.BLACK : color;
    return String.format("#%02x%02x%02x", safe.getRed(), safe.getGreen(), safe.getBlue());
  }

  static void styleButton(AbstractButton button, Color fill, Color foreground, Color outline) {
    localizeFonts(button);
    button.setUI(new FlatButtonUI(fill, outline));
    button.setContentAreaFilled(false);
    button.setOpaque(false);
    button.setFocusPainted(false);
    button.setRolloverEnabled(true);
    button.setBackground(fill);
    button.setForeground(foreground);
    button.setFont(button.getFont().deriveFont(java.awt.Font.BOLD));
    button.setBorder(BorderFactory.createEmptyBorder(7, 14, 7, 14));
  }

  static void localizeFonts(Component component) {
    java.awt.Font font = component.getFont();
    if (font != null) {
      String family =
          LocaleFontSupport.resolveLanguageFontName(font.getFamily(), TeacherStrings.locale());
      if (!family.equals(font.getFamily()))
        component.setFont(
            new java.awt.Font(family, font.getStyle(), font.getSize()).deriveFont(font.getSize2D()));
    }
    if (component instanceof java.awt.Container)
      for (Component child : ((java.awt.Container) component).getComponents()) localizeFonts(child);
  }

  private static Color uiColor(String key, Color fallback) {
    Color color = UIManager.getColor(key);
    return color == null ? fallback : color;
  }

  private static boolean isDark(Color color) {
    if (color == null) {
      return false;
    }
    double luminance =
        (0.2126 * color.getRed() + 0.7152 * color.getGreen() + 0.0722 * color.getBlue()) / 255.0;
    return luminance < 0.42;
  }

  private static Color blend(Color base, Color overlay, float amount) {
    float clamped = Math.max(0f, Math.min(1f, amount));
    float keep = 1f - clamped;
    return new Color(
        Math.round(base.getRed() * keep + overlay.getRed() * clamped),
        Math.round(base.getGreen() * keep + overlay.getGreen() * clamped),
        Math.round(base.getBlue() * keep + overlay.getBlue() * clamped),
        Math.round(base.getAlpha() * keep + overlay.getAlpha() * clamped));
  }

  private static final class FlatButtonUI extends BasicButtonUI {
    private final Color fill;
    private final Color outline;

    private FlatButtonUI(Color fill, Color outline) {
      this.fill = fill;
      this.outline = outline;
    }

    @Override
    public void paint(Graphics graphics, JComponent component) {
      AbstractButton button = (AbstractButton) component;
      ButtonModel model = button.getModel();
      Graphics2D g2 = (Graphics2D) graphics.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      Color currentFill = fill;
      if (!button.isEnabled()) {
        currentFill = blend(fill, background(), 0.58f);
      } else if (model.isPressed()) {
        currentFill = blend(fill, Color.BLACK, 0.10f);
      } else if (model.isRollover()) {
        currentFill = blend(fill, isDark(fill) ? Color.WHITE : Color.BLACK, 0.05f);
      }
      g2.setColor(currentFill);
      g2.fillRoundRect(
          0, 0, component.getWidth(), component.getHeight(), CORNER_RADIUS, CORNER_RADIUS);
      g2.setColor(outline);
      g2.drawRoundRect(
          0,
          0,
          Math.max(0, component.getWidth() - 1),
          Math.max(0, component.getHeight() - 1),
          CORNER_RADIUS,
          CORNER_RADIUS);
      if (button.hasFocus()) {
        g2.setColor(accent());
        g2.setStroke(new BasicStroke(2f));
        g2.drawRoundRect(
            2,
            2,
            Math.max(0, component.getWidth() - 5),
            Math.max(0, component.getHeight() - 5),
            CORNER_RADIUS - 2,
            CORNER_RADIUS - 2);
      }
      g2.dispose();
      super.paint(graphics, component);
    }
  }

  private static final class ModeButtonUI extends BasicButtonUI {
    @Override
    public void paint(Graphics graphics, JComponent component) {
      AbstractButton button = (AbstractButton) component;
      ButtonModel model = button.getModel();
      Graphics2D g2 = (Graphics2D) graphics.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      boolean active = model.isSelected();
      if (active || model.isRollover()) {
        g2.setColor(active ? accentSoft() : blend(railSurface(), accentSoft(), 0.45f));
        g2.fillRect(0, 0, component.getWidth(), component.getHeight());
      }
      if (active) {
        g2.setColor(accent());
        g2.fillRoundRect(0, 7, 3, Math.max(0, component.getHeight() - 14), 3, 3);
        button.setForeground(accent());
      } else {
        button.setForeground(text());
      }
      if (button.hasFocus()) {
        g2.setColor(accent());
        g2.setStroke(new BasicStroke(2f));
        g2.drawRoundRect(
            4,
            4,
            Math.max(0, component.getWidth() - 9),
            Math.max(0, component.getHeight() - 9),
            CORNER_RADIUS,
            CORNER_RADIUS);
      }
      g2.dispose();
      super.paint(graphics, component);
    }
  }

  private static final class ModeIcon implements Icon {
    private static final int SIZE = 24;
    private final ModeGlyph glyph;

    private ModeIcon(ModeGlyph glyph) {
      this.glyph = glyph;
    }

    @Override
    public int getIconWidth() {
      return SIZE;
    }

    @Override
    public int getIconHeight() {
      return SIZE;
    }

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
      Graphics2D g2 = (Graphics2D) graphics.create();
      g2.translate(x, y);
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g2.setColor(component.getForeground());
      g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
      switch (glyph) {
        case RANGE:
          g2.drawRoundRect(3, 5, 18, 16, 3, 3);
          g2.drawLine(3, 10, 21, 10);
          g2.drawLine(8, 3, 8, 7);
          g2.drawLine(16, 3, 16, 7);
          break;
        case WHOLE:
          g2.fillRoundRect(3, 13, 4, 8, 2, 2);
          g2.fillRoundRect(10, 7, 4, 14, 2, 2);
          g2.fillRoundRect(17, 3, 4, 18, 2, 2);
          break;
        case NEXT:
        default:
          g2.drawOval(2, 2, 20, 20);
          int[] xs = {10, 10, 17};
          int[] ys = {7, 17, 12};
          g2.fillPolygon(xs, ys, 3);
          break;
      }
      g2.dispose();
    }
  }

  private static final class CommentaryIcon implements Icon {
    private static final int SIZE = 52;

    @Override
    public int getIconWidth() {
      return SIZE;
    }

    @Override
    public int getIconHeight() {
      return SIZE;
    }

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
      Graphics2D g2 = (Graphics2D) graphics.create();
      g2.translate(x, y);
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g2.setColor(blend(muted(), surface(), isDark(background()) ? 0.20f : 0.45f));
      g2.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
      g2.drawRoundRect(5, 5, 42, 31, 7, 7);
      g2.drawLine(31, 36, 31, 46);
      g2.drawLine(31, 46, 40, 36);
      g2.fillOval(15, 19, 4, 4);
      g2.fillOval(24, 19, 4, 4);
      g2.fillOval(33, 19, 4, 4);
      g2.dispose();
    }
  }

  static final class RoundedBorder extends AbstractBorder {
    private static final long serialVersionUID = 1L;
    private final Color color;
    private final int radius;

    RoundedBorder(Color color, int radius) {
      this.color = color;
      this.radius = radius;
    }

    @Override
    public void paintBorder(
        Component component, Graphics graphics, int x, int y, int width, int height) {
      Graphics2D g2 = (Graphics2D) graphics.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g2.setColor(color);
      g2.setStroke(new BasicStroke(1f));
      g2.drawRoundRect(x, y, width - 1, height - 1, radius, radius);
      g2.dispose();
    }

    @Override
    public Insets getBorderInsets(Component component) {
      return new Insets(1, 1, 1, 1);
    }
  }
}

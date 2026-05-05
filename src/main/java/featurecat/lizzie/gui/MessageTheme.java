package featurecat.lizzie.gui;

import java.awt.Color;
import javax.swing.JComponent;
import javax.swing.UIManager;

final class MessageTheme {
  private static final Color FALLBACK_BACKGROUND = new Color(248, 249, 251);
  private static final Color FALLBACK_FOREGROUND = new Color(32, 36, 40);

  private MessageTheme() {}

  static Color background() {
    Color color = UIManager.getColor("Panel.background");
    if (color == null || isVeryDark(color)) {
      return FALLBACK_BACKGROUND;
    }
    return color;
  }

  static Color foreground() {
    Color color = UIManager.getColor("Label.foreground");
    if (color == null || contrastRatio(color, background()) < 4.5D) {
      return FALLBACK_FOREGROUND;
    }
    return color;
  }

  static void apply(JComponent component) {
    if (component == null) {
      return;
    }
    component.setOpaque(true);
    component.setBackground(background());
    component.setForeground(foreground());
  }

  static String cssColor(Color color) {
    Color effective = color == null ? FALLBACK_FOREGROUND : color;
    return String.format(
        "%02x%02x%02x", effective.getRed(), effective.getGreen(), effective.getBlue());
  }

  private static boolean isVeryDark(Color color) {
    return relativeLuminance(color) < 0.08D;
  }

  private static double contrastRatio(Color foreground, Color background) {
    double l1 = relativeLuminance(foreground) + 0.05D;
    double l2 = relativeLuminance(background) + 0.05D;
    return Math.max(l1, l2) / Math.min(l1, l2);
  }

  private static double relativeLuminance(Color color) {
    return 0.2126D * linear(color.getRed())
        + 0.7152D * linear(color.getGreen())
        + 0.0722D * linear(color.getBlue());
  }

  private static double linear(int value) {
    double channel = value / 255.0D;
    if (channel <= 0.03928D) {
      return channel / 12.92D;
    }
    return Math.pow((channel + 0.055D) / 1.055D, 2.4D);
  }
}

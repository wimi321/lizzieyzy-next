package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import java.awt.Dimension;
import java.awt.Insets;
import javax.swing.AbstractButton;
import javax.swing.JComponent;

/**
 * Sizes the top toolbar's controls to their content, so the row of a 1600-pixel window holds them
 * all instead of wrapping its last buttons onto a second row.
 */
final class ToolbarSizing {
  /** Space on each side of a text button's label, as the Apple style's text buttons have. */
  static final int TEXT_PADDING = 8;

  /** Width of a separator between toolbar groups. */
  static final int SEPARATOR_WIDTH = 4;

  private ToolbarSizing() {}

  /** Gives a text button its label's width plus {@link #TEXT_PADDING} and the row's height. */
  static void fitText(AbstractButton button) {
    button.setMargin(new Insets(0, TEXT_PADDING, 0, TEXT_PADDING));
    button.setPreferredSize(null);
    button.setPreferredSize(new Dimension(button.getPreferredSize().width, Config.menuHeight));
  }

  /** Shows or hides a labelled field group: its panel once built, its checkbox and its field. */
  static void showFieldGroup(boolean show, JComponent panel, JComponent check, JComponent field) {
    if (panel != null) panel.setVisible(show);
    check.setVisible(show);
    field.setVisible(show);
  }

  /**
   * Whether the KataGo search fields (aggressiveness, breadth) are shown: for KataGo, and while the
   * engine is still starting, so that the toolbar does not grow a row once it reports itself. An
   * engine game keeps them shown; the menu locks them instead.
   */
  static boolean showsKataGoFields(
      boolean wanted, boolean noEngine, boolean loaded, boolean kataGo) {
    return wanted && !noEngine && (kataGo || !loaded);
  }
}

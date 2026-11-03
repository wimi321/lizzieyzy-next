package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import java.util.ResourceBundle;
import javax.swing.JMenu;

/**
 * Shows or hides the PDA and the breadth (WRN) field of the top toolbar one at a time from 顶部工具栏 →
 * 自定义内容. They are the same choices as "Set In Toolbar" in the engine parameter dialog, which was
 * the only place to change them.
 */
final class ToolbarParameterToggles {
  private final Config config;
  private final Runnable refresh;
  final JFontCheckBoxMenuItem pda;
  final JFontCheckBoxMenuItem wrn;

  /** {@code refresh} shows or hides the fields after a change. */
  ToolbarParameterToggles(Config config, ResourceBundle strings, Runnable refresh) {
    this.config = config;
    this.refresh = refresh;
    pda = new JFontCheckBoxMenuItem(strings.getString("Menu.lblPDA"));
    wrn = new JFontCheckBoxMenuItem(strings.getString("Menu.lblWRN"));
    pda.addActionListener(e -> show("show-pda-in-menu", pda.isSelected()));
    wrn.addActionListener(e -> show("show-wrn-in-menu", wrn.isSelected()));
  }

  void addTo(JMenu menu) {
    menu.add(pda);
    menu.add(wrn);
  }

  /**
   * Shows the current choices; called when the menu opens, as the engine dialog changes them too.
   */
  void sync() {
    pda.setSelected(config.showPDAInMenu);
    wrn.setSelected(config.showWRNInMenu);
  }

  private void show(String key, boolean shown) {
    if (key.equals("show-pda-in-menu")) config.showPDAInMenu = shown;
    else config.showWRNInMenu = shown;
    config.uiConfig.put(key, shown);
    refresh.run();
  }
}

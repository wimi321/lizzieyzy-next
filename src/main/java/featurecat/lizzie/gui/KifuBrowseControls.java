package featurecat.lizzie.gui;

import java.util.ResourceBundle;

/** Shared presentation for the neighboring Fox, Tencent and OGS toolbar buttons. */
final class KifuBrowseControls {
  private KifuBrowseControls() {}

  static JFontButton button(ResourceBundle strings, String source, Runnable open) {
    JFontButton button = new JFontButton(strings.getString("Menu." + source + "KifuButton"));
    button.setFocusable(false);
    AppleStyleSupport.markPrimary(button);
    ToolbarSizing.fitText(button);
    button.setToolTipText(strings.getString("Menu." + source + "Kifu"));
    button.putClientProperty(AutoAnalyzeMenu.ACTION_PROPERTY, "menu." + source + "Kifu");
    button.addActionListener(event -> open.run());
    return button;
  }
}

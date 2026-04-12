package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.theme.MorandiPalette;
import java.awt.Font;
import javax.swing.JCheckBoxMenuItem;

public class JFontCheckBoxMenuItem extends JCheckBoxMenuItem {

  public JFontCheckBoxMenuItem() {
    super();
    this.setFont(new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    initStyle();
  }

  public JFontCheckBoxMenuItem(String text) {
    super();
    this.setText(text);
    this.setFont(new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    initStyle();
  }

  private void initStyle() {
    this.setOpaque(true);
    this.setBackground(MorandiPalette.CREAM_WHITE);
    this.setForeground(MorandiPalette.MENU_ITEM_TEXT);
    this.setFocusPainted(false);
  }
}

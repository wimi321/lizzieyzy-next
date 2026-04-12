package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.theme.MorandiPalette;
import java.awt.Font;
import javax.swing.JMenu;
import javax.swing.border.EmptyBorder;

public class JFontMenu extends JMenu {
  public JFontMenu() {
    super();
    this.setFont(new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    initStyle();
  }

  public JFontMenu(String text) {
    super();
    this.setText(text);
    this.setFont(new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    initStyle();
  }

  private void initStyle() {
    this.setOpaque(true);
    this.setBackground(MorandiPalette.TOOLBAR_BG);
    this.setForeground(MorandiPalette.MENU_ITEM_TEXT);
    this.setBorder(new EmptyBorder(2, 4, 2, 4));
    this.setFocusPainted(false);
  }
}

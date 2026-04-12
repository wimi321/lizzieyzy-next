package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.theme.MorandiPalette;
import java.awt.Font;
import javax.swing.JCheckBox;

public class JFontCheckBox extends JCheckBox {
  public JFontCheckBox() {
    super();
    this.setFont(new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    initStyle();
  }

  public JFontCheckBox(String text) {
    super();
    this.setText(text);
    this.setFont(new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    initStyle();
  }

  private void initStyle() {
    this.setOpaque(false);
    this.setForeground(MorandiPalette.MENU_ITEM_TEXT);
    this.setFocusPainted(false);
  }
}

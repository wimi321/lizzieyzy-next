package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.theme.MorandiPalette;
import java.awt.Font;
import javax.swing.JLabel;

public class JFontLabel extends JLabel {
  public JFontLabel() {
    super();
    this.setFont(new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    this.setForeground(MorandiPalette.MENU_ITEM_TEXT);
  }

  public JFontLabel(String text) {
    super();
    this.setText(text);
    this.setFont(new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    this.setForeground(MorandiPalette.MENU_ITEM_TEXT);
  }

  public JFontLabel(String text, int horizontalAlignment) {
    super();
    this.setHorizontalAlignment(horizontalAlignment);
    this.setText(text);
    this.setFont(new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    this.setForeground(MorandiPalette.MENU_ITEM_TEXT);
  }
}

package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import java.awt.Font;
import javax.swing.JLabel;

public class JFontLabel extends JLabel {
  public JFontLabel() {
    super();
    this.setFont(new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    AppleStyleSupport.styleLabel(this);
  }

  public JFontLabel(String text) {
    super();
    this.setText(text);
    this.setFont(new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    AppleStyleSupport.styleLabel(this);
  }

  public JFontLabel(String text, int horizontalAlignment) {
    super();
    this.setHorizontalAlignment(horizontalAlignment);
    this.setText(text);
    this.setFont(new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    AppleStyleSupport.styleLabel(this);
  }

  @Override
  public void updateUI() {
    super.updateUI();
    AppleStyleSupport.styleLabel(this);
  }
}

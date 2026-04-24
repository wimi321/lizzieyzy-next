package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
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
    AppleStyleSupport.installCheckBoxStyle(this);
  }

  @Override
  public void updateUI() {
    super.updateUI();
    AppleStyleSupport.installCheckBoxStyle(this);
  }
}

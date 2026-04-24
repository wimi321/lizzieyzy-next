package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import java.awt.Font;
import javax.swing.JRadioButton;

public class JFontRadioButton extends JRadioButton {
  public JFontRadioButton() {
    super();
    this.setFont(new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    AppleStyleSupport.installRadioButtonStyle(this);
  }

  public JFontRadioButton(String text) {
    super();
    this.setText(text);
    this.setFont(new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    AppleStyleSupport.installRadioButtonStyle(this);
  }

  @Override
  public void updateUI() {
    super.updateUI();
    AppleStyleSupport.installRadioButtonStyle(this);
  }
}

package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import java.awt.Font;
import javax.swing.ImageIcon;
import javax.swing.JButton;

public class JFontButton extends JButton {
  public JFontButton() {
    super();
    initStyle();
  }

  public JFontButton(String text) {
    super();
    this.setText(text);
    initStyle();
  }

  public JFontButton(ImageIcon icon) {
    super();
    this.setIcon(icon);
    initStyle();
  }

  private void initStyle() {
    this.setFont(new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    AppleStyleSupport.installButtonStyle(this);
  }

  @Override
  public void updateUI() {
    super.updateUI();
    AppleStyleSupport.installButtonStyle(this);
  }
}

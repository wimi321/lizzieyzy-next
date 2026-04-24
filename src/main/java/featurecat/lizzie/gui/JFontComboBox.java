package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import java.awt.Font;
import javax.swing.JComboBox;

public class JFontComboBox<E> extends JComboBox<E> {
  public JFontComboBox() {
    super();
    this.setFont(new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    AppleStyleSupport.installComboBoxStyle(this);
  }

  @Override
  public void updateUI() {
    super.updateUI();
    AppleStyleSupport.installComboBoxStyle(this);
  }
}

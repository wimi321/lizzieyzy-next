package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.awt.Color;
import javax.swing.JLabel;
import javax.swing.UIManager;
import org.junit.jupiter.api.Test;

public class MessageThemeTest {
  @Test
  void darkLookAndFeelBackgroundFallsBackToReadableMessageColors() {
    Color previousPanel = UIManager.getColor("Panel.background");
    Color previousLabel = UIManager.getColor("Label.foreground");
    try {
      UIManager.put("Panel.background", Color.BLACK);
      UIManager.put("Label.foreground", Color.BLACK);
      JLabel label = new JLabel();

      MessageTheme.apply(label);

      assertFalse(Color.BLACK.equals(label.getBackground()));
    } finally {
      UIManager.put("Panel.background", previousPanel);
      UIManager.put("Label.foreground", previousLabel);
    }
  }
}

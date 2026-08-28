package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Dimension;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class MenuAiFeatureButtonLayoutTest {
  @Test
  void repeatedStateRefreshDoesNotGrowButton() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          JFontButton button = new JFontButton("AI Coach");

          Menu.configureAiFeatureButton(button, false, 34);
          Dimension first = new Dimension(button.getPreferredSize());

          for (int refresh = 0; refresh < 20; refresh++) {
            Menu.configureAiFeatureButton(button, false, 34);
          }

          assertEquals(first, button.getPreferredSize());
        });
  }

  @Test
  void returningToIdleRestoresOriginalWidthAfterLongStatus() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          JFontButton button = new JFontButton("AI Coach");
          Menu.configureAiFeatureButton(button, false, 34);
          Dimension idle = new Dimension(button.getPreferredSize());

          button.setText("AI Coach is preparing the training session");
          Menu.configureAiFeatureButton(button, false, 34);
          int preparingWidth = button.getPreferredSize().width;

          button.setText("AI Coach");
          Menu.configureAiFeatureButton(button, false, 34);

          assertTrue(preparingWidth > idle.width);
          assertEquals(idle, button.getPreferredSize());
        });
  }
}

package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.analysis.ZhiziEngineConnections.State;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.ResourceBundle;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class ZhiziConnectionControlsRenderingTest {
  @Test
  void controlsAndBillingHintFitAllShippedLocalesAtMinimumWidth() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          for (String tag :
              new String[] {"en-US", "zh-CN", "zh-TW", "zh-HK", "ja-JP", "ko", "th-TH"}) {
            ResourceBundle strings =
                ResourceBundle.getBundle("l10n.DisplayStrings", Locale.forLanguageTag(tag));
            ZhiziConnectionControls controls =
                new ZhiziConnectionControls(
                    () ->
                        new ZhiziConnectionControls.Backend() {
                          public State state() {
                            return State.CONNECTED;
                          }

                          public State disconnect() {
                            throw new AssertionError("rendering must not disconnect");
                          }
                        },
                    () -> {},
                    () -> false,
                    busy -> {},
                    (key, fallback) -> strings.getString(key));
            controls.setSize(852, controls.getPreferredSize().height);
            layoutTree(controls);
            checkBounds(controls);
            BufferedImage image =
                new BufferedImage(852, controls.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            controls.paint(graphics);
            graphics.dispose();
            try {
              Path output = Path.of("target", "zhizi-ui", "connection-controls-" + tag + ".png");
              Files.createDirectories(output.getParent());
              ImageIO.write(image, "png", output.toFile());
            } catch (Exception failure) {
              throw new AssertionError(failure);
            }
            controls.close();
          }
        });
  }

  private static void layoutTree(Container parent) {
    parent.doLayout();
    for (Component child : parent.getComponents())
      if (child instanceof Container container) layoutTree(container);
  }

  private static void checkBounds(Container parent) {
    for (Component child : parent.getComponents()) {
      assertTrue(child.getX() >= 0 && child.getY() >= 0);
      assertTrue(child.getX() + child.getWidth() <= parent.getWidth());
      assertTrue(child.getY() + child.getHeight() <= parent.getHeight());
      if (child instanceof JButton button)
        assertTrue(button.getWidth() >= button.getPreferredSize().width);
      if (child instanceof JTextArea area)
        assertTrue(area.getHeight() >= area.getPreferredSize().height);
      if (child instanceof Container container) checkBounds(container);
    }
  }
}

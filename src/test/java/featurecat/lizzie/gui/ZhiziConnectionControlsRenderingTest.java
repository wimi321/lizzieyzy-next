package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.analysis.ZhiziEngineConnections.State;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.ResourceBundle;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class ZhiziConnectionControlsRenderingTest {
  @Test
  void controlsAndBillingHintFitAllShippedLocalesAtMinimumWidth() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          for (float scale : new float[] {1f, 2f}) {
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
              scaleFonts(controls, scale);
              JPanel root = new JPanel(new BorderLayout());
              root.setBorder(BorderFactory.createEmptyBorder(20, 24, 16, 24));
              JPanel footer = new JPanel(new BorderLayout(0, 8));
              footer.add(controls, BorderLayout.NORTH);
              root.add(footer, BorderLayout.SOUTH);
              try {
                // Include first layout, growth, and shrinkage while child widths are still stale.
                for (int width : new int[] {900, 1248, 900}) {
                  root.setSize(width, 710);
                  layoutTree(root);
                  checkBounds(root, tag + " scale=" + scale + " rootWidth=" + width);
                }
                BufferedImage image =
                    new BufferedImage(852, controls.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics();
                graphics.setColor(java.awt.Color.WHITE);
                graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
                controls.paint(graphics);
                graphics.dispose();
                Path output =
                    Path.of(
                        "target", "zhizi-ui", "connection-controls-" + tag + "-" + scale + ".png");
                Files.createDirectories(output.getParent());
                ImageIO.write(image, "png", output.toFile());
              } catch (Exception failure) {
                throw new AssertionError(failure);
              } finally {
                controls.close();
              }
            }
          }
        });
  }

  private static void layoutTree(Container parent) {
    parent.doLayout();
    for (Component child : parent.getComponents())
      if (child instanceof Container container) layoutTree(container);
  }

  private static void scaleFonts(Component component, float scale) {
    Font font = component.getFont();
    if (font != null) component.setFont(font.deriveFont(font.getSize2D() * scale));
    if (component instanceof Container container)
      for (Component child : container.getComponents()) scaleFonts(child, scale);
  }

  private static void checkBounds(Container parent, String tag) {
    for (Component child : parent.getComponents()) {
      String detail =
          tag
              + " "
              + child.getClass().getSimpleName()
              + " bounds="
              + child.getBounds()
              + " preferred="
              + child.getPreferredSize()
              + " parent="
              + parent.getSize();
      assertTrue(child.getX() >= 0 && child.getY() >= 0, detail);
      assertTrue(child.getX() + child.getWidth() <= parent.getWidth(), detail);
      assertTrue(child.getY() + child.getHeight() <= parent.getHeight(), detail);
      if (child instanceof JButton button)
        assertTrue(button.getWidth() >= button.getPreferredSize().width, detail);
      if (child instanceof JTextArea area)
        assertTrue(area.getHeight() >= area.getPreferredSize().height, detail);
      if (child instanceof Container container) checkBounds(container, tag);
    }
  }
}

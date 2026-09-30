package featurecat.lizzie.teacher;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import featurecat.lizzie.Lizzie;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Opt-in native macOS/Windows check; never substitutes one operating system for another. */
class ChatGptSettingsNativeTest {
  @TempDir Path directory;

  @Test
  void bothProvidersRemainEqualAndUsableInEveryLocale() throws Exception {
    assumeTrue(
        Boolean.getBoolean("lizzie.test.chatgptNative") && !GraphicsEnvironment.isHeadless());
    var previous = Lizzie.resourceBundle;
    try {
      for (String locale : List.of("zh-CN", "zh-TW", "zh-HK", "en-US", "ja-JP", "ko", "th-TH")) {
        var store = new ChatGptIntegrationTest.MemoryStore();
        var settings =
            new TeacherSettings(
                directory.resolve(locale + ".properties"),
                store,
                new ChatGptSessions(directory.resolve(locale), store, new ChatGptHttp()));
        TeacherSettingsDialog[] reference = new TeacherSettingsDialog[1];
        SwingUtilities.invokeAndWait(
            () -> {
              Lizzie.resourceBundle =
                  ResourceBundle.getBundle("l10n.DisplayStrings", Locale.forLanguageTag(locale));
              var dialog = new TeacherSettingsDialog(null, settings);
              reference[0] = dialog;
              dialog.setModalityType(Dialog.ModalityType.MODELESS);
              dialog.setLocationRelativeTo(null);
              dialog.setVisible(true);
            });
        try {
          await(() -> button(reference[0], "chatGptProvider").isEnabled());
          SwingUtilities.invokeAndWait(
              () -> {
                var chat = button(reference[0], "chatGptProvider");
                var api = button(reference[0], "apiKeyProvider");
                assertFalse(chat.isSelected());
                assertFalse(api.isSelected());
                assertEquals(chat.getWidth(), api.getWidth());
                assertEquals(chat.getHeight(), api.getHeight());
                assertEquals(chat.getFont(), api.getFont());
                chat.doClick();
              });
          Thread.sleep(350);
          SwingUtilities.invokeAndWait(
              () -> {
                assertButtonsFit(reference[0], locale);
                capture(reference[0], locale + "-chatgpt");
                button(reference[0], "apiKeyProvider").doClick();
                reference[0].validate();
                assertButtonsFit(reference[0], locale);
                capture(reference[0], locale + "-api-key");
              });
        } finally {
          SwingUtilities.invokeAndWait(reference[0]::dispose);
        }
      }
    } finally {
      Lizzie.resourceBundle = previous;
    }
  }

  private static void await(java.util.function.BooleanSupplier condition) throws Exception {
    for (int i = 0; i < 100; i++) {
      boolean[] value = new boolean[1];
      SwingUtilities.invokeAndWait(() -> value[0] = condition.getAsBoolean());
      if (value[0]) return;
      Thread.sleep(50);
    }
    fail("Settings did not finish loading");
  }

  private static JToggleButton button(Container root, String name) {
    return (JToggleButton)
        children(root).stream().filter(c -> name.equals(c.getName())).findFirst().orElseThrow();
  }

  private static List<Component> children(Container root) {
    List<Component> children = new ArrayList<>();
    for (Component child : root.getComponents()) {
      children.add(child);
      if (child instanceof Container container) children.addAll(children(container));
    }
    return children;
  }

  private static void assertButtonsFit(Container root, String locale) {
    for (Component child : children(root)) {
      if (child instanceof AbstractButton button
          && button.isShowing()
          && button.getText() != null
          && !button.getText().isBlank()) {
        int width = button.getFontMetrics(button.getFont()).stringWidth(button.getText());
        assertTrue(
            button.getWidth() - button.getInsets().left - button.getInsets().right >= width,
            locale + ": " + button.getText());
        assertNotNull(button.getAccessibleContext().getAccessibleName());
      }
    }
  }

  private static void capture(TeacherSettingsDialog dialog, String name) {
    String output = System.getProperty("lizzie.test.chatgptScreenshots");
    if (output == null) return;
    try {
      Files.createDirectories(Path.of(output));
      BufferedImage image =
          new BufferedImage(dialog.getWidth(), dialog.getHeight(), BufferedImage.TYPE_INT_RGB);
      var graphics = image.createGraphics();
      dialog.paintAll(graphics);
      graphics.dispose();
      ImageIO.write(image, "png", Path.of(output, name + ".png").toFile());
    } catch (Exception failed) {
      throw new AssertionError(failed);
    }
  }
}

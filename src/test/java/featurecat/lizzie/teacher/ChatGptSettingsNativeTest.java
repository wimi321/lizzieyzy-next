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
                assertNoDefaultScroll(reference[0]);
                capture(reference[0], locale + "-chatgpt");
                button(reference[0], "apiKeyProvider").doClick();
                reference[0].validate();
                assertButtonsFit(reference[0], locale);
                capture(reference[0], locale + "-api-key");
                assertNoDefaultScroll(reference[0]);
                var url =
                    (javax.swing.JTextField)
                        children(reference[0]).stream()
                            .filter(c -> "apiBaseUrl".equals(c.getName()))
                            .findFirst()
                            .orElseThrow();
                url.setText("https://example.com/v1");
                button(reference[0], "preferencesPage").doClick();
                reference[0].validate();
                assertFalse(button(reference[0], "chatGptProvider").isShowing());
                assertTrue(
                    children(reference[0]).stream()
                        .anyMatch(c -> "stylePreference".equals(c.getName()) && c.isShowing()));
                assertButtonsFit(reference[0], locale);
                capture(reference[0], locale + "-preferences");
                assertNoDefaultScroll(reference[0]);
                button(reference[0], "connectionPage").doClick();
                reference[0].validate();
                assertTrue(button(reference[0], "apiKeyProvider").isSelected());
                assertEquals("https://example.com/v1", url.getText());
                assertFalse(
                    children(reference[0]).stream()
                        .anyMatch(c -> "stylePreference".equals(c.getName()) && c.isShowing()));
                button(reference[0], "preferencesPage").doClick();
                var rank =
                    (javax.swing.JComboBox<?>)
                        children(reference[0]).stream()
                            .filter(c -> "rankPreference".equals(c.getName()))
                            .findFirst()
                            .orElseThrow();
                rank.setSelectedIndex(22);
                ((AbstractButton)
                        children(reference[0]).stream()
                            .filter(c -> "saveSettings".equals(c.getName()))
                            .findFirst()
                            .orElseThrow())
                    .doClick();
              });
          await(() -> named(reference[0], "saveSettings").isEnabled());
          var restored =
              new TeacherSettings(directory.resolve(locale + ".properties"), store).load();
          assertEquals(TeacherSettings.Provider.UNSELECTED, restored.provider);
          assertEquals("d", restored.rankMode);
          assertEquals(5, restored.rankNum);
          assertEquals(TeacherSettings.DEFAULT_BASE_URL, restored.baseUrl);
          SwingUtilities.invokeAndWait(
              () -> {
                reference[0].setSize(740, 530);
                reference[0].validate();
                var save = named(reference[0], "saveSettings");
                assertTrue(save.isShowing());
                assertTrue(save.getWidth() > 0 && save.getHeight() > 0);
                assertButtonsFit(reference[0], locale + " minimum");
                if (locale.equals("zh-CN")) capture(reference[0], "zh-CN-minimum");
              });
        } finally {
          SwingUtilities.invokeAndWait(reference[0]::dispose);
        }
      }
    } finally {
      Lizzie.resourceBundle = previous;
    }
  }

  @Test
  void signedInAccountErrorsAndSignOutRemainAccessible() throws Exception {
    assumeTrue(
        Boolean.getBoolean("lizzie.test.chatgptNative") && !GraphicsEnvironment.isHeadless());
    var previous = Lizzie.resourceBundle;
    var fixture = new ChatGptIntegrationTest();
    fixture.directory = directory.resolve("signed-in");
    fixture.setup();
    TeacherSettingsDialog[] dialog = new TeacherSettingsDialog[1];
    try {
      var account = fixture.login(null);
      fixture.sessions.welcomed(account.id);
      var settings =
          new TeacherSettings(
              directory.resolve("signed-in.properties"), fixture.store, fixture.sessions);
      settings.load();
      settings.selectProvider(TeacherSettings.Provider.CHATGPT);
      SwingUtilities.invokeAndWait(
          () -> {
            Lizzie.resourceBundle =
                ResourceBundle.getBundle("l10n.DisplayStrings", Locale.SIMPLIFIED_CHINESE);
            dialog[0] = new TeacherSettingsDialog(null, settings);
            dialog[0].setModalityType(Dialog.ModalityType.MODELESS);
            dialog[0].setVisible(true);
          });
      await(
          () -> ((javax.swing.JComboBox<?>) named(dialog[0], "chatGptModels")).getItemCount() == 2);
      SwingUtilities.invokeAndWait(
          () -> {
            dialog[0].validate();
            assertTrue(named(dialog[0], "chatGptUsage").isShowing());
            assertFalse(named(dialog[0], "chatGptConnect").isShowing());
            assertButtonsFit(dialog[0], "signed-in");
            capture(dialog[0], "zh-CN-signed-in");
            assertNoDefaultScroll(dialog[0]);
            fixture.modelList = "{}";
            ((AbstractButton) named(dialog[0], "chatGptRefresh")).doClick();
          });
      await(() -> named(dialog[0], "chatGptConnect").isShowing());
      SwingUtilities.invokeAndWait(
          () -> {
            assertFalse(
                ((javax.swing.JTextArea) named(dialog[0], "chatGptStatus")).getText().isBlank());
            capture(dialog[0], "zh-CN-model-error");
            ((AbstractButton) named(dialog[0], "chatGptLogout")).doClick();
          });
      await(() -> !named(dialog[0], "chatGptLogout").isShowing());
      assertFalse(fixture.sessions.active().signedIn);
    } finally {
      if (dialog[0] != null) SwingUtilities.invokeAndWait(dialog[0]::dispose);
      fixture.server.stop(0);
      Lizzie.resourceBundle = previous;
    }
  }

  private static Component named(Container root, String name) {
    return children(root).stream().filter(c -> name.equals(c.getName())).findFirst().orElseThrow();
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

  private static void assertNoDefaultScroll(Container root) {
    for (Component child : children(root)) {
      if (child instanceof javax.swing.JScrollPane scroll && scroll.isShowing()) {
        assertFalse(scroll.getVerticalScrollBar().isVisible(), "Default window should not scroll");
        assertFalse(scroll.getHorizontalScrollBar().isVisible());
      }
    }
  }

  private static void capture(TeacherSettingsDialog dialog, String name) {
    String output = System.getProperty("lizzie.test.chatgptScreenshots");
    if (output == null) return;
    try {
      Files.createDirectories(Path.of(output));
      Container content = dialog.getContentPane();
      BufferedImage image =
          new BufferedImage(content.getWidth(), content.getHeight(), BufferedImage.TYPE_INT_RGB);
      var graphics = image.createGraphics();
      content.paintAll(graphics);
      graphics.dispose();
      ImageIO.write(image, "png", Path.of(output, name + ".png").toFile());
    } catch (Exception failed) {
      throw new AssertionError(failed);
    }
  }
}

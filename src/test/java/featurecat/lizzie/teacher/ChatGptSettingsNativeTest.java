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
                capture(reference[0], locale + "-initial");
                assertNoDefaultScroll(reference[0]);
                assertVisibleIcon(reference[0], "connectionWelcomeIcon");
                assertNotNull(button(reference[0], "connectionPage").getIcon());
                assertNotNull(button(reference[0], "preferencesPage").getIcon());
                chat.doClick();
              });
          Thread.sleep(350);
          SwingUtilities.invokeAndWait(
              () -> {
                assertButtonsFit(reference[0], locale);
                capture(reference[0], locale + "-chatgpt");
                assertVisibleIcon(reference[0], "chatGptWelcomeIcon");
                assertNoDefaultScroll(reference[0]);
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
                assertEquals("", url.getText());
                assertTrue(((TeacherExampleField) url).isExampleVisible());
                var model = (TeacherExampleField) named(reference[0], "apiModelInput");
                assertEquals("", model.getText());
                assertTrue(model.isExampleVisible());
                ((AbstractButton) named(reference[0], "saveSettings")).doClick();
                assertTrue(reference[0].isShowing());
                assertEquals(TeacherSettings.Provider.UNSELECTED, settings.snapshot().provider);
                assertEquals("", settings.snapshot().baseUrl);
                url.setText("https://example.com/v1");
                assertFalse(((TeacherExampleField) url).isExampleVisible());
                ((AbstractButton) named(reference[0], "saveSettings")).doClick();
                assertTrue(reference[0].isShowing());
                assertEquals("", settings.snapshot().model);
                button(reference[0], "preferencesPage").doClick();
                reference[0].validate();
                assertFalse(button(reference[0], "chatGptProvider").isShowing());
                assertTrue(
                    children(reference[0]).stream()
                        .anyMatch(c -> "stylePreference".equals(c.getName()) && c.isShowing()));
                assertButtonsFit(reference[0], locale);
              });
          Thread.sleep(100);
          SwingUtilities.invokeAndWait(
              () -> {
                capture(reference[0], locale + "-preferences");
                assertNoDefaultScroll(reference[0]);
                button(reference[0], "connectionPage").doClick();
                reference[0].validate();
                assertTrue(button(reference[0], "apiKeyProvider").isSelected());
                assertEquals(
                    "https://example.com/v1",
                    ((javax.swing.JTextField) named(reference[0], "apiBaseUrl")).getText());
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
  void addressExampleDisappearsOnFocusWithoutBecomingInput() throws Exception {
    assumeTrue(
        Boolean.getBoolean("lizzie.test.chatgptNative") && !GraphicsEnvironment.isHeadless());
    var previous = Lizzie.resourceBundle;
    var store = new ChatGptIntegrationTest.MemoryStore();
    var settings =
        new TeacherSettings(
            directory.resolve("focus.properties"),
            store,
            new ChatGptSessions(directory.resolve("focus-session"), store, new ChatGptHttp()));
    TeacherSettingsDialog[] dialog = new TeacherSettingsDialog[1];
    try {
      SwingUtilities.invokeAndWait(
          () -> {
            Lizzie.resourceBundle =
                ResourceBundle.getBundle("l10n.DisplayStrings", Locale.SIMPLIFIED_CHINESE);
            dialog[0] = new TeacherSettingsDialog(null, settings);
            dialog[0].setModalityType(Dialog.ModalityType.MODELESS);
            dialog[0].setVisible(true);
          });
      await(() -> button(dialog[0], "apiKeyProvider").isEnabled());
      SwingUtilities.invokeAndWait(
          () -> {
            button(dialog[0], "apiKeyProvider").doClick();
            named(dialog[0], "apiBaseUrl").requestFocusInWindow();
          });
      await(() -> named(dialog[0], "apiBaseUrl").hasFocus());
      SwingUtilities.invokeAndWait(
          () -> {
            var address = (TeacherExampleField) named(dialog[0], "apiBaseUrl");
            assertEquals("", address.getText());
            assertFalse(address.isExampleVisible());
            capture(dialog[0], "zh-CN-api-focused");
            named(dialog[0], "apiSecret").requestFocusInWindow();
          });
      await(() -> named(dialog[0], "apiSecret").hasFocus());
      SwingUtilities.invokeAndWait(
          () -> {
            var address = (TeacherExampleField) named(dialog[0], "apiBaseUrl");
            assertTrue(address.isExampleVisible());
            address.setText("https://provider.example/v1");
            assertFalse(address.isExampleVisible());
            assertEquals("https://provider.example/v1", address.getText());
            assertEquals("", settings.snapshot().baseUrl);
            named(dialog[0], "apiModelInput").requestFocusInWindow();
          });
      await(() -> named(dialog[0], "apiModelInput").hasFocus());
      SwingUtilities.invokeAndWait(
          () -> {
            var model = (TeacherExampleField) named(dialog[0], "apiModelInput");
            assertEquals("", model.getText());
            assertFalse(model.isExampleVisible());
            capture(dialog[0], "zh-CN-api-model-focused");
            named(dialog[0], "apiSecret").requestFocusInWindow();
          });
      await(() -> named(dialog[0], "apiSecret").hasFocus());
      SwingUtilities.invokeAndWait(
          () -> {
            var model = (TeacherExampleField) named(dialog[0], "apiModelInput");
            assertTrue(model.isExampleVisible());
            var box = (javax.swing.JComboBox<?>) named(dialog[0], "apiModel");
            box.setSelectedItem("provider-model");
            assertEquals("provider-model", model.getText());
            assertFalse(model.isExampleVisible());
            assertEquals("provider-model", box.getEditor().getItem());
            assertEquals("", settings.snapshot().model);
          });
    } finally {
      if (dialog[0] != null) SwingUtilities.invokeAndWait(dialog[0]::dispose);
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
      fixture.modelList = ChatGptIntegrationTest.reasoningCatalog();
      fixture.sessions.model(account.id, "m2", "high");
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
            var efforts = (javax.swing.JComboBox<?>) named(dialog[0], "chatGptReasoning");
            var models = (javax.swing.JComboBox<?>) named(dialog[0], "chatGptModels");
            assertTrue(efforts.isShowing());
            assertTrue(efforts.isEnabled());
            assertEquals(5, efforts.getItemCount());
            assertEquals("深入", efforts.getSelectedItem().toString());
            efforts.setSelectedIndex(1);
            models.setSelectedIndex(1);
            assertFalse(efforts.isEnabled());
            assertEquals(1, efforts.getItemCount());
            models.setSelectedIndex(0);
            assertEquals("较浅", efforts.getSelectedItem().toString());
            assertButtonsFit(dialog[0], "signed-in");
            capture(dialog[0], "zh-CN-signed-in");
            assertNoDefaultScroll(dialog[0]);
            models.setSelectedIndex(1);
            ((AbstractButton) named(dialog[0], "chatGptRefresh")).doClick();
          });
      await(() -> named(dialog[0], "chatGptRefresh").isEnabled());
      SwingUtilities.invokeAndWait(
          () -> {
            var models = (javax.swing.JComboBox<?>) named(dialog[0], "chatGptModels");
            assertEquals(
                1, models.getSelectedIndex(), "Refreshing must preserve an unsaved choice");
            fixture.modelList = "{}";
            ((AbstractButton) named(dialog[0], "chatGptRefresh")).doClick();
          });
      await(() -> named(dialog[0], "chatGptRefresh").isEnabled());
      SwingUtilities.invokeAndWait(
          () -> {
            assertFalse(
                named(dialog[0], "chatGptConnect").isShowing(), "A model error is not a logout");
            assertFalse(
                ((javax.swing.JTextArea) named(dialog[0], "chatGptStatus")).getText().isBlank());
            assertTrue(named(dialog[0], "chatGptRetry").isShowing());
            assertNoDefaultScroll(dialog[0]);
            capture(dialog[0], "zh-CN-model-error");
            fixture.modelList = ChatGptIntegrationTest.reasoningCatalog();
            ((AbstractButton) named(dialog[0], "chatGptRetry")).doClick();
          });
      await(() -> named(dialog[0], "chatGptRefresh").isEnabled());
      SwingUtilities.invokeAndWait(
          () -> {
            assertFalse(named(dialog[0], "chatGptRetry").isShowing());
            var models = (javax.swing.JComboBox<?>) named(dialog[0], "chatGptModels");
            assertEquals(1, models.getSelectedIndex());
            models.setSelectedIndex(0);
            assertEquals(
                1,
                ((javax.swing.JComboBox<?>) named(dialog[0], "chatGptReasoning"))
                    .getSelectedIndex());
            models.setSelectedIndex(1);
            fixture.modelList = "{\"models\":[{\"slug\":\"m2\",\"visibility\":\"list\"}]}";
            ((AbstractButton) named(dialog[0], "chatGptRefresh")).doClick();
          });
      await(() -> named(dialog[0], "chatGptRefresh").isEnabled());
      SwingUtilities.invokeAndWait(
          () -> {
            assertNull(
                ((javax.swing.JComboBox<?>) named(dialog[0], "chatGptModels")).getSelectedItem());
            assertFalse(named(dialog[0], "chatGptConnect").isShowing());
            assertEquals(
                ChatGptHttp.error("modelRemoved").getMessage(),
                ((javax.swing.JTextArea) named(dialog[0], "chatGptStatus")).getText());
            capture(dialog[0], "zh-CN-model-removed");
            ((AbstractButton) named(dialog[0], "chatGptRefresh")).doClick();
          });
      await(() -> named(dialog[0], "chatGptRefresh").isEnabled());
      SwingUtilities.invokeAndWait(
          () -> {
            assertNull(
                ((javax.swing.JComboBox<?>) named(dialog[0], "chatGptModels")).getSelectedItem());
            ((AbstractButton) named(dialog[0], "chatGptLogout")).doClick();
          });
      await(() -> !named(dialog[0], "chatGptLogout").isShowing());
      SwingUtilities.invokeAndWait(
          () -> {
            assertFalse(named(dialog[0], "chatGptModels").isShowing());
            assertFalse(named(dialog[0], "chatGptReasoning").isShowing());
            assertNoDefaultScroll(dialog[0]);
          });
      assertFalse(fixture.sessions.active().signedIn);
    } finally {
      if (dialog[0] != null) SwingUtilities.invokeAndWait(dialog[0]::dispose);
      fixture.server.stop(0);
      Lizzie.resourceBundle = previous;
    }
  }

  @Test
  void lockedCredentialsRecoverWithoutBrowserLoginOrWritesInEveryLocale() throws Exception {
    assumeTrue(
        Boolean.getBoolean("lizzie.test.chatgptNative") && !GraphicsEnvironment.isHeadless());
    var previous = Lizzie.resourceBundle;
    var fixture = new ChatGptIntegrationTest();
    fixture.directory = directory.resolve("locked-session");
    fixture.setup();
    try {
      var account = fixture.login(null);
      fixture.sessions.model(account.id, "m2", "high");
      fixture.sessions.welcomed(account.id);
      fixture.modelList = ChatGptIntegrationTest.reasoningCatalog();
      int writes = fixture.store.writes;
      for (String locale : List.of("zh-CN", "zh-TW", "zh-HK", "en-US", "ja-JP", "ko", "th-TH")) {
        fixture.store.failRead = true;
        var restarted = new ChatGptSessions(fixture.directory, fixture.store, fixture.http);
        var settings =
            new TeacherSettings(
                directory.resolve(locale + "-locked.properties"), fixture.store, restarted);
        settings.load();
        settings.selectProvider(TeacherSettings.Provider.CHATGPT);
        TeacherSettingsDialog[] dialog = new TeacherSettingsDialog[1];
        try {
          SwingUtilities.invokeAndWait(
              () -> {
                Lizzie.resourceBundle =
                    ResourceBundle.getBundle("l10n.DisplayStrings", Locale.forLanguageTag(locale));
                dialog[0] = new TeacherSettingsDialog(null, settings);
                dialog[0].setModalityType(Dialog.ModalityType.MODELESS);
                dialog[0].setVisible(true);
              });
          await(() -> named(dialog[0], "chatGptRetry").isShowing());
          SwingUtilities.invokeAndWait(
              () -> {
                assertFalse(named(dialog[0], "chatGptConnect").isShowing());
                assertFalse(named(dialog[0], "chatGptModels").isShowing());
                assertEquals(
                    ChatGptHttp.error("credentialsUnavailable").getMessage(),
                    ((javax.swing.JTextArea) named(dialog[0], "chatGptStatus")).getText());
                assertButtonsFit(dialog[0], locale + " locked");
                assertNoDefaultScroll(dialog[0]);
                capture(dialog[0], locale + "-locked");
                ((AbstractButton) named(dialog[0], "chatGptRetry")).doClick();
              });
          await(() -> named(dialog[0], "chatGptRetry").isShowing());
          SwingUtilities.invokeAndWait(
              () -> {
                assertFalse(named(dialog[0], "chatGptConnect").isShowing());
                fixture.store.failRead = false;
                ((AbstractButton) named(dialog[0], "chatGptRetry")).doClick();
              });
          await(() -> named(dialog[0], "chatGptReasoning").isEnabled());
          if (locale.equals("zh-CN")) {
            fixture.store.failRead = true;
            SwingUtilities.invokeAndWait(
                () -> ((AbstractButton) named(dialog[0], "saveSettings")).doClick());
            await(() -> named(dialog[0], "chatGptRetry").isShowing());
            SwingUtilities.invokeAndWait(
                () -> {
                  assertTrue(
                      dialog[0].isShowing(),
                      "Failed credential check must not close the form as saved");
                  assertTrue(named(dialog[0], "saveSettings").isEnabled());
                  assertFalse(named(dialog[0], "chatGptConnect").isShowing());
                  fixture.store.failRead = false;
                  ((AbstractButton) named(dialog[0], "chatGptRetry")).doClick();
                });
            await(() -> named(dialog[0], "chatGptReasoning").isEnabled());
          }
          SwingUtilities.invokeAndWait(
              () -> {
                assertFalse(named(dialog[0], "chatGptConnect").isShowing());
                assertFalse(named(dialog[0], "chatGptRetry").isShowing());
                assertTrue(
                    ((javax.swing.JTextArea) named(dialog[0], "settingsStatus"))
                        .getText()
                        .isBlank(),
                    "Successful recovery must clear the old save error");
                assertEquals(
                    2,
                    ((javax.swing.JComboBox<?>) named(dialog[0], "chatGptReasoning"))
                        .getSelectedIndex());
                assertNoDefaultScroll(dialog[0]);
                capture(dialog[0], locale + "-recovered");
                ((AbstractButton) named(dialog[0], "saveSettings")).doClick();
              });
          await(() -> !dialog[0].isShowing());
          assertTrue(restarted.active().signedIn);
          assertEquals("high", restarted.reasoningEffort(account.id, "m2"));
          assertEquals(
              writes,
              fixture.store.writes,
              "Opening, retrying and saving preferences must not rewrite tokens");
          assertEquals(1, fixture.tokenCalls.get(), "Recovery must reuse the existing login");
        } finally {
          if (dialog[0] != null) SwingUtilities.invokeAndWait(dialog[0]::dispose);
        }
      }
    } finally {
      fixture.server.stop(0);
      Lizzie.resourceBundle = previous;
    }
  }

  @Test
  void thinkingDepthFitsAllLocalesAndSavesThroughTheRealForm() throws Exception {
    assumeTrue(
        Boolean.getBoolean("lizzie.test.chatgptNative") && !GraphicsEnvironment.isHeadless());
    var previous = Lizzie.resourceBundle;
    var fixture = new ChatGptIntegrationTest();
    fixture.directory = directory.resolve("reasoning-locales");
    fixture.setup();
    try {
      var account = fixture.login(null);
      fixture.sessions.welcomed(account.id);
      fixture.modelList = ChatGptIntegrationTest.reasoningCatalog();
      for (String locale : List.of("zh-CN", "zh-TW", "zh-HK", "en-US", "ja-JP", "ko", "th-TH")) {
        fixture.sessions.model(account.id, "m2", "high");
        var settings =
            new TeacherSettings(
                directory.resolve(locale + "-depth.properties"), fixture.store, fixture.sessions);
        settings.load();
        settings.selectProvider(TeacherSettings.Provider.CHATGPT);
        TeacherSettingsDialog[] dialog = new TeacherSettingsDialog[1];
        try {
          SwingUtilities.invokeAndWait(
              () -> {
                Lizzie.resourceBundle =
                    ResourceBundle.getBundle("l10n.DisplayStrings", Locale.forLanguageTag(locale));
                dialog[0] = new TeacherSettingsDialog(null, settings);
                dialog[0].setModalityType(Dialog.ModalityType.MODELESS);
                dialog[0].setVisible(true);
              });
          await(
              () ->
                  named(dialog[0], "chatGptReasoning").isEnabled()
                      && ((javax.swing.JComboBox<?>) named(dialog[0], "chatGptReasoning"))
                              .getItemCount()
                          == 5);
          SwingUtilities.invokeAndWait(
              () -> {
                var efforts = (javax.swing.JComboBox<?>) named(dialog[0], "chatGptReasoning");
                assertEquals(2, efforts.getSelectedIndex());
                assertButtonsFit(dialog[0], locale);
                assertNoDefaultScroll(dialog[0]);
                assertNotNull(efforts.getAccessibleContext().getAccessibleName());
                assertTrue(
                    efforts.getWidth()
                        > efforts
                                .getFontMetrics(efforts.getFont())
                                .stringWidth(efforts.getSelectedItem().toString())
                            + 30);
                capture(dialog[0], locale + "-thinking-depth");
                efforts.setSelectedIndex(1);
                ((AbstractButton) named(dialog[0], "saveSettings")).doClick();
              });
          await(() -> !dialog[0].isShowing());
          var restarted = new ChatGptSessions(fixture.directory, fixture.store, fixture.http);
          assertEquals("low", restarted.reasoningEffort(account.id, "m2"));
        } finally {
          if (dialog[0] != null) SwingUtilities.invokeAndWait(dialog[0]::dispose);
        }
      }
    } finally {
      fixture.server.stop(0);
      Lizzie.resourceBundle = previous;
    }
  }

  @Test
  void inactiveApiKeyIsReadOnlyWhenItsTabIsExplicitlyOpened() throws Exception {
    assumeTrue(
        Boolean.getBoolean("lizzie.test.chatgptNative") && !GraphicsEnvironment.isHeadless());
    var fixture = new ChatGptIntegrationTest();
    fixture.directory = directory.resolve("provider-isolation");
    fixture.setup();
    TeacherSettingsDialog[] dialog = new TeacherSettingsDialog[1];
    try {
      var account = fixture.login(null);
      fixture.sessions.welcomed(account.id);
      Path file = directory.resolve("provider.properties");
      var initial = new TeacherSettings(file, fixture.store, fixture.sessions);
      initial.load();
      initial.save("https://provider.example/v1", "api-model", "api-canary".toCharArray(), true);
      initial.selectProvider(TeacherSettings.Provider.CHATGPT);
      var settings = new TeacherSettings(file, fixture.store, fixture.sessions);
      int reads = fixture.store.apiReads;
      SwingUtilities.invokeAndWait(
          () -> {
            dialog[0] = new TeacherSettingsDialog(null, settings);
            dialog[0].setModalityType(Dialog.ModalityType.MODELESS);
            dialog[0].setVisible(true);
          });
      await(
          () ->
              named(dialog[0], "chatGptModels").isShowing()
                  && named(dialog[0], "chatGptModels").isEnabled());
      assertEquals(reads, fixture.store.apiReads);
      SwingUtilities.invokeAndWait(() -> button(dialog[0], "apiKeyProvider").doClick());
      await(() -> named(dialog[0], "apiSecret").isEnabled());
      SwingUtilities.invokeAndWait(
          () -> {
            var key = (javax.swing.JPasswordField) named(dialog[0], "apiSecret");
            assertArrayEquals("api-canary".toCharArray(), key.getPassword());
            key.setText("");
            button(dialog[0], "chatGptProvider").doClick();
            button(dialog[0], "apiKeyProvider").doClick();
            assertEquals(
                0, key.getPassword().length, "A cleared draft must not be silently refilled");
            assertNoDefaultScroll(dialog[0]);
          });
      assertEquals(reads + 1, fixture.store.apiReads);
      assertEquals(TeacherSettings.Provider.CHATGPT, settings.snapshot().provider);
      assertTrue(fixture.sessions.active().signedIn);
    } finally {
      if (dialog[0] != null) SwingUtilities.invokeAndWait(dialog[0]::dispose);
      fixture.server.stop(0);
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
      if (child instanceof javax.swing.JTextArea && child.isShowing()) {
        assertFalse(child.getFont().isBold(), locale + ": helper text must not be bold");
      }
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
        assertFalse(
            scroll.getVerticalScrollBar().isVisible(),
            "Default window should not scroll: viewport="
                + scroll.getViewport().getExtentSize()
                + ", content="
                + scroll.getViewport().getView().getPreferredSize());
        assertFalse(scroll.getHorizontalScrollBar().isVisible());
      }
    }
  }

  private static void assertVisibleIcon(Container root, String name) {
    var label = (javax.swing.JLabel) named(root, name);
    assertTrue(label.isShowing(), name + " must be visible");
    assertNotNull(label.getIcon(), name + " must load its packaged asset");
    assertTrue(label.getIcon().getIconWidth() > 0);
    assertTrue(label.getIcon().getIconHeight() > 0);
    assertTrue(label.getWidth() >= label.getIcon().getIconWidth());
    assertTrue(label.getHeight() >= label.getIcon().getIconHeight());
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
      graphics.setRenderingHint(
          java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
          java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      content.paintAll(graphics);
      graphics.dispose();
      ImageIO.write(image, "png", Path.of(output, name + ".png").toFile());
    } catch (Exception failed) {
      throw new AssertionError(failed);
    }
  }
}

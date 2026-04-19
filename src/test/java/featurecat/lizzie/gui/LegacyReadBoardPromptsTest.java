package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

public class LegacyReadBoardPromptsTest {
  @Test
  void promptReturnsFalseWithoutBrowsingWhenUserCancels() {
    ResourceBundle resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);
    AtomicReference<String> title = new AtomicReference<String>();
    AtomicReference<String> message = new AtomicReference<String>();
    AtomicBoolean browserOpened = new AtomicBoolean(false);

    boolean result =
        LegacyReadBoardPrompts.promptMissingLegacyReadBoardDownload(
            resourceBundle,
            (dialogTitle, dialogMessage, confirmLabel, cancelLabel) -> {
              title.set(dialogTitle);
              message.set(dialogMessage);
              assertEquals(resourceBundle.getString("LizzieFrame.confirm"), confirmLabel);
              assertEquals(resourceBundle.getString("LizzieFrame.cancel"), cancelLabel);
              return false;
            },
            uri -> browserOpened.set(true),
            errorMessage -> {
              throw new AssertionError("unexpected error message: " + errorMessage);
            });

    assertFalse(result);
    assertFalse(browserOpened.get());
    assertEquals(resourceBundle.getString("ReadBoard.missingLegacyTitle"), title.get());
    assertEquals(resourceBundle.getString("ReadBoard.missingLegacyDownload"), message.get());
  }

  @Test
  void promptOpensGithubReleaseWhenUserConfirms() {
    ResourceBundle resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);
    AtomicReference<URI> openedUri = new AtomicReference<URI>();

    boolean result =
        LegacyReadBoardPrompts.promptMissingLegacyReadBoardDownload(
            resourceBundle,
            (dialogTitle, dialogMessage, confirmLabel, cancelLabel) -> true,
            openedUri::set,
            errorMessage -> {
              throw new AssertionError("unexpected error message: " + errorMessage);
            });

    assertTrue(result);
    assertNotNull(openedUri.get());
    assertEquals(LegacyReadBoardPrompts.DOWNLOAD_URL, openedUri.get().toString());
  }

  @Test
  void promptReportsBrowserErrors() {
    ResourceBundle resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);
    AtomicReference<String> errorMessage = new AtomicReference<String>();

    boolean result =
        LegacyReadBoardPrompts.promptMissingLegacyReadBoardDownload(
            resourceBundle,
            (dialogTitle, dialogMessage, confirmLabel, cancelLabel) -> true,
            uri -> {
              throw new IOException("cannot browse");
            },
            errorMessage::set);

    assertFalse(result);
    assertEquals("cannot browse", errorMessage.get());
  }

  @Test
  void localizedBundlesContainMissingLegacyPromptKeys() {
    ResourceBundle english = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);
    ResourceBundle simplifiedChinese =
        ResourceBundle.getBundle("l10n.DisplayStrings", Locale.SIMPLIFIED_CHINESE);
    String englishMessage = english.getString("ReadBoard.missingLegacyDownload");
    String simplifiedChineseMessage =
        simplifiedChinese.getString("ReadBoard.missingLegacyDownload");

    assertTrue(englishMessage.contains(LegacyReadBoardPrompts.DOWNLOAD_URL));
    assertTrue(simplifiedChineseMessage.contains(LegacyReadBoardPrompts.DOWNLOAD_URL));
    assertFalse(englishMessage.contains("legacy"));
    assertFalse(simplifiedChineseMessage.contains("旧版"));
    assertFalse(english.getString("ReadBoard.missingLegacyTitle").isBlank());
    assertFalse(simplifiedChinese.getString("ReadBoard.missingLegacyTitle").isBlank());
  }
}

package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class KifuLoadResourceTest {
  private static final String[] KEYS = {
    "KifuLoad.wait",
    "KifuLoad.foxSearching",
    "KifuLoad.foxDownloading",
    "KifuLoad.sharedDownloading",
    "KifuLoad.tencentDownloading",
    "KifuLoad.processing",
    "KifuLoad.parsing",
    "KifuLoad.refreshing",
    "KifuLoad.failed",
  };

  @Test
  void allBundlesContainKifuLoadKeys() {
    Locale[] locales = {
      Locale.US,
      Locale.SIMPLIFIED_CHINESE,
      Locale.TRADITIONAL_CHINESE,
      Locale.forLanguageTag("zh-HK"),
    };
    for (Locale locale : locales) {
      ResourceBundle bundle = ResourceBundle.getBundle("l10n.DisplayStrings", locale);
      for (String key : KEYS) {
        assertTrue(
            !bundle.getString(key).isEmpty(), key + " should be non-empty for locale " + locale);
      }
    }
  }

  @Test
  void foxSearchingKeepsAccountNamePlaceholder() {
    ResourceBundle en = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);
    ResourceBundle cn = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.SIMPLIFIED_CHINESE);
    assertTrue(en.getString("KifuLoad.foxSearching").contains("{0}"));
    assertTrue(cn.getString("KifuLoad.foxSearching").contains("{0}"));
    assertEquals(
        "Searching Fox account \"lizzie\", please wait...",
        MessageFormat.format(en.getString("KifuLoad.foxSearching"), "lizzie"));
  }

  @Test
  void failedMessageKeepsTrailingSeparator() {
    ResourceBundle en = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);
    assertEquals("Failed to load game record: ", en.getString("KifuLoad.failed"));
    ResourceBundle cn = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.SIMPLIFIED_CHINESE);
    assertEquals("棋谱加载失败：", cn.getString("KifuLoad.failed"));
  }
}

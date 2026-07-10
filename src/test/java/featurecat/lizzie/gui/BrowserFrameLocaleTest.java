package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class BrowserFrameLocaleTest {
  @Test
  void configuredLanguageUsesBundledCefLocale() {
    assertEquals("zh-CN", BrowserFrame.resolveCefLocale(1, Locale.US));
    assertEquals("en-US", BrowserFrame.resolveCefLocale(2, Locale.CHINA));
    assertEquals("ko", BrowserFrame.resolveCefLocale(3, Locale.US));
    assertEquals("ja", BrowserFrame.resolveCefLocale(4, Locale.US));
  }

  @Test
  void systemLocaleFallsBackToSupportedBundle() {
    assertEquals("zh-TW", BrowserFrame.resolveCefLocale(0, Locale.TAIWAN));
    assertEquals("zh-TW", BrowserFrame.resolveCefLocale(0, new Locale("zh", "HK")));
    assertEquals("zh-CN", BrowserFrame.resolveCefLocale(0, Locale.CHINA));
    assertEquals("ja", BrowserFrame.resolveCefLocale(0, Locale.JAPAN));
    assertEquals("ko", BrowserFrame.resolveCefLocale(0, Locale.KOREA));
    assertEquals("en-US", BrowserFrame.resolveCefLocale(0, Locale.FRANCE));
  }
}

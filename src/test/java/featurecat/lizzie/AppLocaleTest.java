package featurecat.lizzie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Locale;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class AppLocaleTest {
  @Test
  void preservesLegacyValuesAndAddsStableNewLocales() {
    assertEquals(AppLocale.SYSTEM, AppLocale.fromConfigValue(0));
    assertEquals(AppLocale.SIMPLIFIED_CHINESE, AppLocale.fromConfigValue(1));
    assertEquals(AppLocale.ENGLISH, AppLocale.fromConfigValue(2));
    assertEquals(AppLocale.KOREAN, AppLocale.fromConfigValue(3));
    assertEquals(AppLocale.JAPANESE, AppLocale.fromConfigValue(4));
    assertEquals(AppLocale.TRADITIONAL_CHINESE, AppLocale.fromConfigValue(5));
    assertEquals(AppLocale.THAI, AppLocale.fromConfigValue(6));
    assertEquals(AppLocale.SYSTEM, AppLocale.fromConfigValue(99));
  }

  @Test
  void loadsEverySupportedBundle() {
    for (AppLocale locale : AppLocale.values()) {
      ResourceBundle bundle = locale.loadBundle();
      assertNotNull(bundle);
      assertNotNull(bundle.getString("Lizzie.isChinese"), locale.name());
      assertNotNull(bundle.getString("EngineStartup.needsRepair"), locale.name());
    }
  }

  @Test
  void traditionalChineseUsesTaiwanWhileHongKongRemainsCompatible() {
    assertEquals(Locale.TAIWAN, AppLocale.TRADITIONAL_CHINESE.locale());
    ResourceBundle taiwan = AppLocale.TRADITIONAL_CHINESE.loadBundle();
    ResourceBundle hongKong =
        ResourceBundle.getBundle("l10n.DisplayStrings", Locale.forLanguageTag("zh-HK"));
    assertEquals("yes", taiwan.getString("Lizzie.isChinese"));
    assertEquals("yes", hongKong.getString("Lizzie.isChinese"));
  }
}

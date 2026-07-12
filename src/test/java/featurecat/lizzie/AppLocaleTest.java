package featurecat.lizzie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Locale;
import java.util.ResourceBundle;
import javax.swing.UIManager;
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
      assertNotNull(bundle.getString("Accessibility.sidebar"), locale.name());
      assertNotNull(bundle.getString("Accessibility.sidebarDescription"), locale.name());
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
    assertTraditionalCoreUi(taiwan, "\u641C\u5C0B/\u79D2");
    assertTraditionalCoreUi(hongKong, "\u641C\u7D22/\u79D2");
  }

  @Test
  void optionPaneButtonsFollowTheSelectedApplicationLanguage() {
    String[] uiKeys = {
      "OptionPane.okButtonText",
      "OptionPane.cancelButtonText",
      "OptionPane.yesButtonText",
      "OptionPane.noButtonText"
    };
    Object[] previousValues = new Object[uiKeys.length];
    for (int i = 0; i < uiKeys.length; i++) {
      previousValues[i] = UIManager.get(uiKeys[i]);
    }

    try {
      Lizzie.applyOptionPaneLocalization(AppLocale.THAI.loadBundle());
      assertEquals("\u0E15\u0E01\u0E25\u0E07", UIManager.get("OptionPane.okButtonText"));
      assertEquals("\u0E22\u0E01\u0E40\u0E25\u0E34\u0E01", UIManager.get("OptionPane.cancelButtonText"));
      assertEquals("\u0E43\u0E0A\u0E48", UIManager.get("OptionPane.yesButtonText"));
      assertEquals("\u0E44\u0E21\u0E48", UIManager.get("OptionPane.noButtonText"));
    } finally {
      for (int i = 0; i < uiKeys.length; i++) {
        UIManager.put(uiKeys[i], previousValues[i]);
      }
    }
  }

  private static void assertTraditionalCoreUi(ResourceBundle bundle, String expectedSpeedUnit) {
    assertEquals("\u986F\u793A", bundle.getString("Menu.viewMenu"));
    assertEquals(expectedSpeedUnit, bundle.getString("LizzieFrame.speedUnit"));
    assertEquals("\u4E2D\u570B\u898F\u5247", bundle.getString("LizzieFrame.currentRules.chinese"));
    assertEquals("\u5E8F\u865F", bundle.getString("FoxKifuDownload.column.index"));
  }
}

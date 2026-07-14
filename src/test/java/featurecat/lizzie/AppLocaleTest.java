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
  void englishPlayerStrengthHeadingKeepsItsWordBoundary() {
    assertEquals(
        " performance",
        AppLocale.ENGLISH.loadBundle().getString("PlayerStrengthEstimate.performanceSuffix"));
  }

  @Test
  void localizedOperationalPromptsUseCurrentMenuNames() {
    ResourceBundle english = AppLocale.ENGLISH.loadBundle();
    ResourceBundle korean = AppLocale.KOREAN.loadBundle();
    ResourceBundle thai = AppLocale.THAI.loadBundle();

    assertEquals(
        "Click 'Settings > General Settings > Engine & Analysis' to change the limits.",
        english.getString("leelaz.stopByLimit2"));
    assertEquals(
        "\uC81C\uD55C \uC870\uAC74\uC744 \uBCC0\uACBD\uD558\uB824\uBA74 '\uC124\uC815 > \uC77C\uBC18 \uC124\uC815 > \uC5D4\uC9C4 \uBC0F \uBD84\uC11D'\uC744 \uC5EC\uC138\uC694.",
        korean.getString("leelaz.stopByLimit2"));
    assertEquals(
        "\u0E40\u0E1B\u0E34\u0E14 '\u0E01\u0E32\u0E23\u0E15\u0E31\u0E49\u0E07\u0E04\u0E48\u0E32 > \u0E01\u0E32\u0E23\u0E15\u0E31\u0E49\u0E07\u0E04\u0E48\u0E32\u0E17\u0E31\u0E48\u0E27\u0E44\u0E1B > \u0E40\u0E04\u0E23\u0E37\u0E48\u0E2D\u0E07\u0E22\u0E19\u0E15\u0E4C\u0E41\u0E25\u0E30\u0E01\u0E32\u0E23\u0E27\u0E34\u0E40\u0E04\u0E23\u0E32\u0E30\u0E2B\u0E4C' \u0E40\u0E1E\u0E37\u0E48\u0E2D\u0E40\u0E1B\u0E25\u0E35\u0E48\u0E22\u0E19\u0E02\u0E35\u0E14\u0E08\u0E33\u0E01\u0E31\u0E14",
        thai.getString("leelaz.stopByLimit2"));
    assertEquals(
        "\uD55C \uBC88\uC5D0 \uD65C\uC131\uD654", korean.getString("RemoteCompute.oneClickEnable"));
    assertEquals(
        "Zhizi \uC6F9\uC0AC\uC774\uD2B8 \uBC0F \uCDA9\uC804",
        korean.getString("RemoteCompute.websiteAndTopup"));
    assertEquals(
        "\u0E40\u0E1B\u0E34\u0E14\u0E43\u0E0A\u0E49\u0E07\u0E32\u0E19\u0E43\u0E19\u0E04\u0E25\u0E34\u0E01\u0E40\u0E14\u0E35\u0E22\u0E27",
        thai.getString("RemoteCompute.oneClickEnable"));
    assertEquals(
        "\u0E40\u0E27\u0E47\u0E1A\u0E44\u0E0B\u0E15\u0E4C Zhizi \u0E41\u0E25\u0E30\u0E01\u0E32\u0E23\u0E40\u0E15\u0E34\u0E21\u0E40\u0E07\u0E34\u0E19",
        thai.getString("RemoteCompute.websiteAndTopup"));
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
  void mapsSupportedSystemLocalesAndKeepsAStableFallback() {
    assertEquals(AppLocale.SIMPLIFIED_CHINESE, AppLocale.fromSystemLocale(Locale.CHINA));
    assertEquals(AppLocale.TRADITIONAL_CHINESE, AppLocale.fromSystemLocale(Locale.TAIWAN));
    assertEquals(
        AppLocale.TRADITIONAL_CHINESE,
        AppLocale.fromSystemLocale(Locale.forLanguageTag("zh-HK")));
    assertEquals(
        AppLocale.TRADITIONAL_CHINESE,
        AppLocale.fromSystemLocale(Locale.forLanguageTag("zh-Hant")));
    assertEquals(AppLocale.ENGLISH, AppLocale.fromSystemLocale(Locale.UK));
    assertEquals(AppLocale.JAPANESE, AppLocale.fromSystemLocale(Locale.JAPAN));
    assertEquals(AppLocale.KOREAN, AppLocale.fromSystemLocale(Locale.KOREA));
    assertEquals(AppLocale.THAI, AppLocale.fromSystemLocale(Locale.forLanguageTag("th-TH")));
    assertEquals(AppLocale.SIMPLIFIED_CHINESE, AppLocale.fromSystemLocale(Locale.FRANCE));
    assertEquals(AppLocale.SIMPLIFIED_CHINESE, AppLocale.fromSystemLocale(null));
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

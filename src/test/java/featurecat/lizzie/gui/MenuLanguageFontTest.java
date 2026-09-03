package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import featurecat.lizzie.AppLocale;
import java.awt.Font;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class MenuLanguageFontTest {
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void languageMenuFontsCanRenderEveryNativeLabelOnWindows() {
    Map<AppLocale, String> nativeLabels = new LinkedHashMap<>();
    nativeLabels.put(AppLocale.SIMPLIFIED_CHINESE, "简体中文");
    nativeLabels.put(AppLocale.TRADITIONAL_CHINESE, "繁體中文");
    nativeLabels.put(AppLocale.ENGLISH, "English");
    nativeLabels.put(AppLocale.JAPANESE, "日本語");
    nativeLabels.put(AppLocale.KOREAN, "한국어");
    nativeLabels.put(AppLocale.THAI, "ไทย");

    nativeLabels.forEach(
        (locale, label) -> {
          Font font = Menu.languageOptionFont(locale, "Dialog", 14);
          assertEquals(
              -1,
              font.canDisplayUpTo(label),
              () -> locale + " resolved to a font that cannot display " + label);
        });
  }
}

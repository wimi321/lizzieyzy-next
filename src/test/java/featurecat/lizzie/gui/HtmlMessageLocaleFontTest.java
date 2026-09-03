package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Font;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class HtmlMessageLocaleFontTest {
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void localizedMessageFontsCanRenderAllSupportedLanguagesOnWindows() {
    Map<Locale, String> messages = new LinkedHashMap<>();
    messages.put(Locale.SIMPLIFIED_CHINESE, "重新打开后设置生效");
    messages.put(Locale.TRADITIONAL_CHINESE, "重新開啟後設定生效");
    messages.put(Locale.US, "Restart to apply changes");
    messages.put(Locale.JAPAN, "再起動すると反映されます");
    messages.put(Locale.KOREA, "변경 사항은 재시작 후 적용됩니다");
    messages.put(Locale.forLanguageTag("th-TH"), "การเปลี่ยนภาษา");

    messages.forEach(
        (locale, message) -> {
          String fontName = HtmlMessage.resolveMessageFontName("Dialog", locale);
          Font font = new Font(fontName, Font.PLAIN, 14);
          assertEquals(
              -1,
              font.canDisplayUpTo(message),
              () -> locale + " resolved to a font that cannot display its message");
        });
  }
}

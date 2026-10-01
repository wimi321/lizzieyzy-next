package featurecat.lizzie.teacher;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class TeacherSettingsAssetsTest {
  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
  void settingsFontsRenderEveryShippedLanguageAfterSwitchingLocale() {
    var previous = featurecat.lizzie.Lizzie.resourceBundle;
    try {
      for (String locale : List.of("zh-CN", "zh-TW", "zh-HK", "en-US", "ja-JP", "ko", "th-TH")) {
        var bundle =
            java.util.ResourceBundle.getBundle(
                "l10n.DisplayStrings", java.util.Locale.forLanguageTag(locale));
        featurecat.lizzie.Lizzie.resourceBundle = bundle;
        for (boolean bold : new boolean[] {false, true}) {
          var font = TeacherSettingsStyle.font(16, bold);
          for (String key : bundle.keySet()) {
            if (key.startsWith("Teacher.settings.") || key.startsWith("Teacher.chatgpt."))
              assertEquals(-1, font.canDisplayUpTo(bundle.getString(key)), locale + ": " + key);
          }
        }
      }
    } finally {
      featurecat.lizzie.Lizzie.resourceBundle = previous;
    }
  }

  @Test
  void sidebarReservesIconAndSelectedFontWidth() throws Exception {
    javax.swing.SwingUtilities.invokeAndWait(
        () -> {
          var button = new javax.swing.JToggleButton("Commentary preferences");
          TeacherSettingsStyle.selection(button, true);
          button.setIcon(TeacherSettingsStyle.icon("sliders-horizontal", 22));
          int width = button.getPreferredSize().width;
          button.setSelected(true);
          assertEquals(width, button.getPreferredSize().width);
          int required =
              button.getFontMetrics(button.getFont()).stringWidth(button.getText())
                  + button.getIcon().getIconWidth()
                  + button.getIconTextGap()
                  + button.getInsets().left
                  + button.getInsets().right;
          assertTrue(width >= required);
        });
  }

  @Test
  void settingsIllustrationAndControlIconsArePackagedAndVisible() throws Exception {
    for (String name :
        List.of(
            "browser-globe",
            "link",
            "sliders-horizontal",
            "lock-keyhole",
            "book-open",
            "chevron-down")) {
      var resource =
          TeacherSettingsStyle.class.getResource("/assets/teacher-settings/" + name + ".png");
      assertNotNull(resource, name);
      var image = ImageIO.read(resource);
      assertNotNull(image, name);
      long visiblePixels = 0;
      for (int y = 0; y < image.getHeight(); y++) {
        for (int x = 0; x < image.getWidth(); x++) {
          int pixel = image.getRGB(x, y);
          if ((pixel >>> 24) > 128 && (pixel & 0xffffff) < 0xe0e0e0) visiblePixels++;
        }
      }
      assertTrue(visiblePixels > 20, name + " must not be empty or transparent");
      var icon = TeacherSettingsStyle.icon(name, 32);
      assertNotNull(icon, name);
      assertEquals(32, icon.getIconWidth());
      assertTrue(icon.getIconHeight() > 0);
    }
  }
}

package featurecat.lizzie.teacher;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class TeacherSettingsAssetsTest {
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

package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/**
 * Guards the classpath resources behind Utils.addNewThemeAs and the Theme/ConfigDialog2 defaults.
 * Utils.copy NPEs when a resource is missing, so a moved or deleted asset must fail here first.
 */
class NewThemeAssetsTest {
  private static final String[] REQUIRED_RESOURCES = {
    "/assets/newtheme/black.png",
    "/assets/newtheme/white.png",
    "/assets/newtheme/theme.txt",
    "/assets/board.png",
    "/assets/background.jpg",
  };

  @Test
  void newThemeSourceResourcesExist() throws IOException {
    for (String path : REQUIRED_RESOURCES) {
      try (InputStream in = Utils.class.getResourceAsStream(path)) {
        assertNotNull(in, path + " must exist on the classpath");
      }
    }
  }

  @Test
  void copyNamesTheMissingResource() {
    IOException e =
        assertThrows(
            IOException.class, () -> Utils.copy("/assets/no-such-resource.png", "unused"));
    assertTrue(e.getMessage().contains("/assets/no-such-resource.png"));
  }
}

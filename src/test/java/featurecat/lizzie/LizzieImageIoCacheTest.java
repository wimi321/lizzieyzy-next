package featurecat.lizzie;

import static org.junit.jupiter.api.Assertions.assertFalse;

import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * Loading the entry-point class must disable ImageIO's disk cache: every one of the ~200
 * ImageIO.read call sites otherwise routes its decode through a temp file.
 */
class LizzieImageIoCacheTest {
  @Test
  void loadingLizzieDisablesImageIoDiskCache() throws Exception {
    Class.forName("featurecat.lizzie.Lizzie");
    assertFalse(ImageIO.getUseCache(), "ImageIO disk cache should be off after Lizzie loads");
  }
}

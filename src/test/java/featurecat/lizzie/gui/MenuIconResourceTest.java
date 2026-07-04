package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class MenuIconResourceTest {
  @Test
  void trackingPointIconIsPackagedAtMenuSize() throws Exception {
    try (InputStream stream =
        RightClickMenu.class.getResourceAsStream("/assets/trackingpoint.png")) {
      assertNotNull(stream, "tracking point menu icon should be packaged.");
      BufferedImage image = ImageIO.read(stream);
      assertNotNull(image, "tracking point menu icon should be readable.");
      assertEquals(16, image.getWidth(), "tracking point menu icon width.");
      assertEquals(16, image.getHeight(), "tracking point menu icon height.");
    }
  }
}

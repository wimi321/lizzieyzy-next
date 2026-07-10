package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ToolbarIconCacheTest {
  @AfterEach
  void clearCache() {
    ToolbarIconCache.clearForTests();
  }

  @Test
  void reusesSameResourceSizeAndStyle() {
    javax.swing.ImageIcon first = ToolbarIconCache.get("/assets/open.png", 24, false);
    javax.swing.ImageIcon second = ToolbarIconCache.get("/assets/open.png", 24, false);

    assertSame(first, second);
    assertEquals(24, first.getIconWidth());
    assertEquals(24, first.getIconHeight());
  }

  @Test
  void sizeAndStyleHaveSeparateEntries() {
    javax.swing.ImageIcon base = ToolbarIconCache.get("/assets/open.png", 24, false);

    assertNotSame(base, ToolbarIconCache.get("/assets/open.png", 28, false));
    assertNotSame(base, ToolbarIconCache.get("/assets/open.png", 24, true));
  }
}

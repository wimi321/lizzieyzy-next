package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Dimension;
import org.junit.jupiter.api.Test;

class MenuInfoMenuItemTest {
  @Test
  void infoMenuItemKeepsTextInNativeMenuLayout() {
    Menu.InfoMenuItem item = new Menu.InfoMenuItem("人机对局(分析模式 Alt+N)", () -> {});
    JFontMenuItem plain = new JFontMenuItem("人机对局(分析模式 Alt+N)");

    assertEquals(0, item.getComponentCount());
    assertTrue(item.getPreferredSize().width > plain.getPreferredSize().width);
  }

  @Test
  void infoHitAreaStaysAtRightEdge() {
    Menu.InfoMenuItem item = new Menu.InfoMenuItem("人机对局(分析模式 Alt+N)", () -> {});
    Dimension preferred = item.getPreferredSize();
    item.setSize(Math.max(220, preferred.width), Math.max(28, preferred.height));

    assertFalse(item.isInfoHit(24, item.getHeight() / 2));
    assertTrue(item.isInfoHit(item.getWidth() - 16, item.getHeight() / 2));
  }
}

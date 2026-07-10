package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import org.junit.jupiter.api.Test;

class UtilsFontTest {
  @Test
  void changesFontWhenNestedComponentsDoNotHaveAnInheritedFont() {
    Container root = new Container();
    Container nested = new Container();
    Component leaf = new Component() {};
    root.add(nested);
    nested.add(leaf);

    assertNull(nested.getFont());
    assertNull(leaf.getFont());

    assertDoesNotThrow(() -> Utils.changeFontRecursive(root, Font.DIALOG));

    assertNotNull(nested.getFont());
    assertNotNull(leaf.getFont());
    assertEquals(Font.DIALOG, nested.getFont().getName());
    assertEquals(Font.DIALOG, leaf.getFont().getName());
  }
}

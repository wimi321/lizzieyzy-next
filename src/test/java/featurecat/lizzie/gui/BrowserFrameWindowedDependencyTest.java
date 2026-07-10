package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BrowserFrameWindowedDependencyTest {
  @Test
  void windowedBrowserLoadsWithoutOffscreenRenderingDependencies() {
    assertFalse(BrowserFrame.JCEF_WINDOWLESS_RENDERING);
    assertDoesNotThrow(() -> Class.forName("org.cef.browser.CefBrowserWr"));
    assertThrows(ClassNotFoundException.class, () -> Class.forName("com.jogamp.opengl.GL"));
  }
}

package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.awt.Rectangle;
import org.junit.jupiter.api.Test;

class AnalysisSettingsLayoutTest {
  @Test
  void engineCommandToolbarControlsDoNotOverlapAtSupportedFontSizes() {
    for (AnalysisSettings.FontScale fontScale : AnalysisSettings.FontScale.values()) {
      AnalysisSettings.EngineCommandToolbarBounds bounds =
          AnalysisSettings.engineCommandToolbarBounds(fontScale);

      assertFalse(bounds.generate.intersects(bounds.savedEngine));
      assertFalse(bounds.savedEngine.intersects(bounds.remoteEngine));
      assertFalse(bounds.remoteEngine.intersects(bounds.remoteSettings));
      assertInsideDialog(bounds.generate);
      assertInsideDialog(bounds.savedEngine);
      assertInsideDialog(bounds.remoteEngine);
      assertInsideDialog(bounds.remoteSettings);
    }
  }

  private static void assertInsideDialog(Rectangle bounds) {
    assertFalse(bounds.x < 0 || bounds.x + bounds.width > 577);
  }
}

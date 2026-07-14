package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

  @Test
  void localizedToolbarUsesASecondRowWithoutClippingLongControls() {
    AnalysisSettings.EngineCommandToolbarBounds bounds =
        AnalysisSettings.engineCommandToolbarBounds(
            AnalysisSettings.FontScale.SMALL, 95, 90, 130, 140, 100);

    assertFalse(bounds.generate.intersects(bounds.savedEngine));
    assertFalse(bounds.remoteEngine.intersects(bounds.remoteSettings));
    assertTrue(bounds.generate.x >= 10 + 95 + 8);
    assertTrue(bounds.remoteEngine.y > bounds.generate.y);
    assertTrue(bounds.remoteSettings.y == bounds.remoteEngine.y);
    assertInsideDialog(bounds.generate);
    assertInsideDialog(bounds.savedEngine);
    assertInsideDialog(bounds.remoteEngine);
    assertInsideDialog(bounds.remoteSettings);
  }

  @Test
  void reuseModeDisablesOnlyDedicatedEngineControls() {
    AnalysisSettings.ControlState dedicated = AnalysisSettings.controlState(false);
    AnalysisSettings.ControlState reuse = AnalysisSettings.controlState(true);

    assertTrue(dedicated.commandEnabled);
    assertTrue(dedicated.preloadEnabled);
    assertTrue(dedicated.autoExitEnabled);
    assertFalse(reuse.commandEnabled);
    assertFalse(reuse.generateEnabled);
    assertFalse(reuse.savedEngineEnabled);
    assertFalse(reuse.remoteEngineEnabled);
    assertFalse(reuse.remoteSettingsEnabled);
    assertFalse(reuse.preloadEnabled);
    assertFalse(reuse.autoExitEnabled);
  }

  private static void assertInsideDialog(Rectangle bounds) {
    assertFalse(bounds.x < 0 || bounds.x + bounds.width > 577);
  }
}

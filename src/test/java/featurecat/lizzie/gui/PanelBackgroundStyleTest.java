package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import org.junit.jupiter.api.Test;

class PanelBackgroundStyleTest {

  @Test
  void sidebarPanelFillUsesConfiguredCommentBackground() {
    Color configured = new Color(12, 34, 56, 123);

    Color fill = SidebarPanel.resolveCommentPanelFillColor(configured, false);

    assertEquals(configured, fill);
  }

  @Test
  void winrateGraphBackgroundIsSemiTransparentAndLiftedFromBlack() {
    Color background = WinrateGraph.resolveGraphBackgroundColor(new Color(0, 0, 0, 200), false);

    assertTrue(background.getAlpha() < 200, "winrate graph background should be translucent.");
    assertTrue(background.getRed() > 0, "winrate graph background should not stay pure black.");
    assertTrue(background.getGreen() > 0, "winrate graph background should not stay pure black.");
    assertTrue(background.getBlue() > 0, "winrate graph background should not stay pure black.");
  }

  @Test
  void winrateGridLinesAreMoreVisibleThanTheOldLowAlphaLines() {
    assertTrue(
        WinrateGraph.resolveGridLineColor().getAlpha() > 30,
        "grid lines should be clearer than the old alpha=30 dashed lines.");
  }
}

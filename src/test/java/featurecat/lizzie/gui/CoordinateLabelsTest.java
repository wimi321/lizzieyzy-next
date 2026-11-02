package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.theme.Theme;
import java.awt.Font;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class CoordinateLabelsTest {
  @Test
  void theUsualLabelsKeepTheirOldSizeAndMargin() {
    CoordinateLabels labels = CoordinateLabels.STANDARD;

    assertEquals(Font.PLAIN, labels.fontStyle());
    assertEquals(16f, labels.maxHeight(20), 1e-6, "stoneRadius * 4 / 5, as before");
    assertEquals(20.0, labels.maxWidth(20), 1e-9);
    assertEquals(0.055, labels.margin(0.055), 1e-12);
  }

  @Test
  void largerLabelsAreBoldOnRequestAndWidenTheMarginAlike() {
    CoordinateLabels large = new CoordinateLabels(true, 140);
    CoordinateLabels extraLarge = new CoordinateLabels(false, 200);

    assertEquals(Font.BOLD, large.fontStyle());
    assertEquals(16f * 1.4f, large.maxHeight(20), 1e-4);
    assertEquals(28.0, large.maxWidth(20), 1e-9);
    assertEquals(0.055 * 1.24, large.margin(0.055), 1e-12);
    assertEquals(Font.PLAIN, extraLarge.fontStyle());
    assertEquals(0.055 * 1.6, extraLarge.margin(0.055), 1e-12);
  }

  @Test
  void sizesOutsideTheOfferedOnesAreClamped() {
    assertEquals(1.0, new CoordinateLabels(false, 40).scale(), 1e-12);
    assertEquals(2.0, new CoordinateLabels(false, 400).scale(), 1e-12);
    assertEquals(0, CoordinateLabels.sizeIndex(60));
    assertEquals(1, CoordinateLabels.sizeIndex(140));
    assertEquals(2, CoordinateLabels.sizeIndex(999));
  }

  @Test
  void theThemeDecidesAndTheGeneralSettingsFillIn() {
    JSONObject general =
        new JSONObject()
            .put("theme", "missing-test-theme-" + UUID.randomUUID())
            .put("coordinate-bold", true)
            .put("coordinate-size", 200);
    Theme theme = new Theme();
    theme.getTheme(general);

    assertEquals(new CoordinateLabels(true, 200), CoordinateLabels.of(theme));

    theme.config = new JSONObject().put("coordinate-bold", false).put("coordinate-size", 140);
    assertEquals(new CoordinateLabels(false, 140), CoordinateLabels.of(theme));
  }

  @Test
  void withoutAnySettingTheLabelsStayAsTheyWere() {
    assertEquals(CoordinateLabels.STANDARD, CoordinateLabels.of(null));
    Theme bare = new Theme();
    assertFalse(bare.coordinateBold());
    assertEquals(100, bare.coordinateSize());
    assertTrue(CoordinateLabels.of(bare).equals(CoordinateLabels.STANDARD));
  }
}

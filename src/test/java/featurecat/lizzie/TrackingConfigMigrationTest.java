package featurecat.lizzie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class TrackingConfigMigrationTest {
  @Test
  void trackingPointAppearanceKeepsOnlyAttentionOutlineDefaults() throws Exception {
    Config config = ConfigTestHelper.createForTests(Files.createTempDirectory("tracking-style"));
    JSONObject ui =
        new JSONObject()
            .put("tracking-point-interior-color", new JSONArray().put(10).put(20).put(30))
            .put("tracking-point-interior-opacity", 37)
            .put("tracking-point-text-auto-color", false)
            .put("tracking-point-text-color", new JSONArray().put(210).put(220).put(230));

    config.loadTrackingPointAppearanceConfig(ui);

    assertTrue(config.showTrackingPointOutline);
    assertEquals(92, config.trackingPointOutlineOpacityPercent);
    assertFalse(ui.has("tracking-point-interior-color"));
    assertFalse(ui.has("tracking-point-interior-opacity"));
    assertFalse(ui.has("tracking-point-text-auto-color"));
    assertFalse(ui.has("tracking-point-text-color"));
  }

  @Test
  void trackingPointAppearancePersistsOnlyAttentionOutlineSettings() throws Exception {
    Config config = ConfigTestHelper.createForTests(Files.createTempDirectory("tracking-style"));
    config.uiConfig =
        new JSONObject()
            .put("tracking-point-interior-color", new JSONArray())
            .put("tracking-point-interior-opacity", 37)
            .put("tracking-point-text-auto-color", false)
            .put("tracking-point-text-color", new JSONArray());
    config.showTrackingPointOutline = false;
    config.trackingPointOutlineOpacityPercent = 64;

    config.saveTrackingPointAppearanceConfig();

    assertFalse(config.uiConfig.getBoolean("show-tracking-point-outline"));
    assertEquals(64, config.uiConfig.getInt("tracking-point-outline-opacity"));
    assertFalse(config.uiConfig.has("tracking-point-interior-color"));
    assertFalse(config.uiConfig.has("tracking-point-interior-opacity"));
    assertFalse(config.uiConfig.has("tracking-point-text-auto-color"));
    assertFalse(config.uiConfig.has("tracking-point-text-color"));
  }

  @Test
  void trackingPointAppearanceLoadsAndClampsRetainedSettings() throws Exception {
    Config config = ConfigTestHelper.createForTests(Files.createTempDirectory("tracking-style"));
    JSONObject ui =
        new JSONObject()
            .put("show-tracking-point-outline", false)
            .put("tracking-point-outline-opacity", 164);

    config.loadTrackingPointAppearanceConfig(ui);

    assertFalse(config.showTrackingPointOutline);
    assertEquals(100, config.trackingPointOutlineOpacityPercent);
  }

  @Test
  void legacySecondProcessSettingsMigrateToSingleStreamVisitsOnly() {
    JSONObject ui =
        new JSONObject()
            .put("tracking-engine-preload", true)
            .put("tracking-engine-skip-warning", true)
            .put("tracking-engine-max-visits", 321);

    int visits = Config.migrateTrackingAnalysisConfig(ui);

    assertEquals(321, visits);
    assertEquals(321, ui.getInt("tracking-analysis-max-visits"));
    assertFalse(ui.has("tracking-engine-preload"));
    assertFalse(ui.has("tracking-engine-skip-warning"));
    assertFalse(ui.has("tracking-engine-max-visits"));
  }

  @Test
  void currentSingleStreamVisitsWinOverLegacyValue() {
    JSONObject ui =
        new JSONObject()
            .put("tracking-analysis-max-visits", 456)
            .put("tracking-engine-max-visits", 123);

    assertEquals(456, Config.migrateTrackingAnalysisConfig(ui));
    assertEquals(456, ui.getInt("tracking-analysis-max-visits"));
    assertFalse(ui.has("tracking-engine-max-visits"));
  }

}

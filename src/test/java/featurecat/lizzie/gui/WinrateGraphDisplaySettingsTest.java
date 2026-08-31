package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WinrateGraphDisplaySettingsTest {
  @TempDir Path tempDir;

  @ParameterizedTest
  @ValueSource(ints = {0, 1})
  void persistRemovesLegacyWinrateGraphMode(int legacyMode) {
    Config config = ConfigTestHelper.createForTests(tempDir);
    config.persistedUi = new JSONObject();
    config.persistedUi.put("winrate-graph", new JSONArray().put(legacyMode));

    ConfigTestHelper.dropPersistedWinrateGraphMode(config);

    assertFalse(config.persistedUi.has("winrate-graph"));
  }

  @Test
  void winrateGraphHasNoPersistedPerspectiveMode() {
    assertThrows(NoSuchFieldException.class, () -> WinrateGraph.class.getDeclaredField("mode"));
  }

  @Test
  void winrateGraphFillDefaultsOnWhenKeyMissing() throws Exception {
    Path workDir = isolatedWorkDir();
    Config config = ConfigTestHelper.createBootstrapped(workDir);

    assertTrue(config.showWinrateGraphFill);
    assertFalse(config.uiConfig.has("show-winrate-graph-fill"));
  }

  @Test
  void winrateGraphFillPersistsOffAcrossSaveAndReload() throws Exception {
    Path workDir = isolatedWorkDir();
    Config config = ConfigTestHelper.createBootstrapped(workDir);
    config.showWinrateGraphFill = false;
    config.uiConfig.put("show-winrate-graph-fill", false);
    config.save();

    Config reloaded = ConfigTestHelper.createBootstrapped(workDir);

    assertFalse(reloaded.showWinrateGraphFill);
    assertFalse(reloaded.uiConfig.getBoolean("show-winrate-graph-fill"));
  }

  @Test
  void areaFillIsEligibleOnlyForASingleRenderableMetric() {
    WinrateGraph.RenderableMetrics winrateOnly =
        WinrateGraph.resolveRenderableMetrics(true, false, true);
    WinrateGraph.RenderableMetrics scoreOnly =
        WinrateGraph.resolveRenderableMetrics(false, true, true);
    WinrateGraph.RenderableMetrics both =
        WinrateGraph.resolveRenderableMetrics(true, true, true);
    WinrateGraph.RenderableMetrics bothButScoreUnavailable =
        WinrateGraph.resolveRenderableMetrics(true, true, false);
    WinrateGraph.RenderableMetrics scoreRequestedButUnavailable =
        WinrateGraph.resolveRenderableMetrics(false, true, false);
    WinrateGraph.RenderableMetrics none =
        WinrateGraph.resolveRenderableMetrics(false, false, true);

    assertTrue(winrateOnly.winrateRenderable);
    assertFalse(winrateOnly.scoreRenderable);
    assertEquals(1, winrateOnly.renderableCount);
    assertTrue(winrateOnly.areaFillEligible(true));
    assertFalse(winrateOnly.areaFillEligible(false));

    assertFalse(scoreOnly.winrateRenderable);
    assertTrue(scoreOnly.scoreRenderable);
    assertEquals(1, scoreOnly.renderableCount);
    assertTrue(scoreOnly.areaFillEligible(true));

    assertEquals(2, both.renderableCount);
    assertFalse(both.areaFillEligible(true));
    assertFalse(both.areaFillEligible(false));

    assertTrue(bothButScoreUnavailable.winrateRenderable);
    assertFalse(bothButScoreUnavailable.scoreRenderable);
    assertEquals(1, bothButScoreUnavailable.renderableCount);
    assertTrue(bothButScoreUnavailable.areaFillEligible(true));

    assertEquals(0, scoreRequestedButUnavailable.renderableCount);
    assertFalse(scoreRequestedButUnavailable.areaFillEligible(true));
    assertEquals(0, none.renderableCount);
    assertFalse(none.areaFillEligible(true));
  }

  @Test
  void enginePkScoreAvailabilityFollowsKataScoreDrawGates() {
    assertFalse(WinrateGraph.resolveScoreLeadAvailable(true, false, false, true, true));
    assertTrue(WinrateGraph.resolveScoreLeadAvailable(true, true, false, false, false));
    assertTrue(WinrateGraph.resolveScoreLeadAvailable(true, false, true, false, false));
    assertTrue(WinrateGraph.resolveScoreLeadAvailable(false, false, false, true, false));
    assertTrue(WinrateGraph.resolveScoreLeadAvailable(false, false, false, false, true));
    assertFalse(WinrateGraph.resolveScoreLeadAvailable(false, true, true, false, false));

    WinrateGraph.RenderableMetrics enginePkWithoutScore =
        WinrateGraph.resolveRenderableMetrics(
            true, true, WinrateGraph.resolveScoreLeadAvailable(true, false, false, false, false));
    WinrateGraph.RenderableMetrics enginePkWithWhiteKataScore =
        WinrateGraph.resolveRenderableMetrics(
            true, true, WinrateGraph.resolveScoreLeadAvailable(true, true, false, false, false));

    assertEquals(1, enginePkWithoutScore.renderableCount);
    assertTrue(enginePkWithoutScore.areaFillEligible(true));
    assertEquals(2, enginePkWithWhiteKataScore.renderableCount);
    assertFalse(enginePkWithWhiteKataScore.areaFillEligible(true));
  }

  private Path isolatedWorkDir() throws IOException {
    Files.createDirectories(tempDir.resolve("save"));
    return tempDir;
  }
}

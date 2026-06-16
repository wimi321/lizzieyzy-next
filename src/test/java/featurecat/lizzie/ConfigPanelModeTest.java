package featurecat.lizzie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import featurecat.lizzie.gui.LizzieFrame;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class ConfigPanelModeTest {
  @Test
  void defaultConfigStartsInNormalModeWithCommentPanel() throws Exception {
    Config config =
        ConfigTestHelper.createForTests(Files.createTempDirectory("lizzie-panel-mode-default"));
    Method createDefaultConfig = Config.class.getDeclaredMethod("createDefaultConfig");
    createDefaultConfig.setAccessible(true);

    JSONObject defaultConfig = (JSONObject) createDefaultConfig.invoke(config);
    JSONObject ui = defaultConfig.getJSONObject("ui");

    assertEquals(0, ui.getInt("extra-mode"));
    assertTrue(ui.getBoolean("show-comment"));
    assertEquals(ExtraMode.Normal, config.extraMode);
    assertTrue(config.showComment);
    assertFalse(ui.getBoolean("show-suggestion-maxred"));
    assertFalse(config.showSuggestionMaxRed);
  }

  @Test
  void savePanelConfigKeepsFourSubModeAsNumericValue() throws Exception {
    Path tempRoot = Files.createTempDirectory("lizzie-panel-mode");
    Config config = ConfigTestHelper.createForTests(tempRoot);
    config.uiConfig = new JSONObject();

    config.extraMode = ExtraMode.Four_Sub;
    config.showComment = true;
    config.savePanelConfig();

    assertEquals(1, config.uiConfig.getInt("extra-mode"));
    assertTrue(config.uiConfig.getBoolean("show-comment"));
  }

  @Test
  void legacyStringExtraModeStillRestoresFourSubMode() throws Exception {
    Config config =
        ConfigTestHelper.createForTests(Files.createTempDirectory("lizzie-panel-mode-legacy"));
    JSONObject ui = new JSONObject();
    ui.put("extra-mode", "Four_Sub");
    ui.put("show-comment", true);
    config.uiConfig = ui;

    config.loadPanelModeSettings();

    assertEquals(ExtraMode.Four_Sub, config.extraMode);
    assertEquals(1, config.uiConfig.getInt("extra-mode"));
    assertFalse(config.showComment);
    assertFalse(config.uiConfig.getBoolean("show-comment"));
  }

  @Test
  void fourSubModeHidesCommentPanelOnLoad() throws Exception {
    Config config =
        ConfigTestHelper.createForTests(Files.createTempDirectory("lizzie-panel-mode-four-sub"));
    JSONObject ui = new JSONObject();
    ui.put("extra-mode", 1);
    ui.put("show-comment", true);
    config.uiConfig = ui;

    config.loadPanelModeSettings();

    assertEquals(ExtraMode.Four_Sub, config.extraMode);
    assertFalse(config.showComment);
    assertFalse(config.uiConfig.getBoolean("show-comment"));
  }

  @Test
  void fourSubModeRejectsManualCommentPanelEnable() throws Exception {
    LizzieFrame previousFrame = Lizzie.frame;
    try {
      Lizzie.frame = null;
      Config config =
          ConfigTestHelper.createForTests(Files.createTempDirectory("lizzie-panel-mode-four-set"));
      config.extraMode = ExtraMode.Four_Sub;
      config.uiConfig = new JSONObject();

      config.setShowComment(true);

      assertFalse(config.showComment);
      assertFalse(config.uiConfig.getBoolean("show-comment"));
    } finally {
      Lizzie.frame = previousFrame;
    }
  }

  @Test
  void doubleEngineModeStillHidesCommentPanelOnLoad() throws Exception {
    Config config =
        ConfigTestHelper.createForTests(Files.createTempDirectory("lizzie-panel-mode-double"));
    JSONObject ui = new JSONObject();
    ui.put("extra-mode", 2);
    ui.put("show-comment", true);
    config.uiConfig = ui;

    config.loadPanelModeSettings();

    assertEquals(ExtraMode.Double_Engine, config.extraMode);
    assertFalse(config.showComment);
  }
}

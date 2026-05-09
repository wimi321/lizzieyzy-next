package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class MoreEnginesStartupModeTest {
  @Test
  void defaultEngineModeIsPersistedExclusively() {
    JSONObject uiConfig = new JSONObject();
    uiConfig.put("autoload-default", false);
    uiConfig.put("autoload-last", true);
    uiConfig.put("autoload-empty", false);

    assertTrue(MoreEngines.updateStartupMode(uiConfig, true, false, false));

    assertTrue(uiConfig.getBoolean("autoload-default"));
    assertFalse(uiConfig.getBoolean("autoload-last"));
    assertFalse(uiConfig.getBoolean("autoload-empty"));
  }

  @Test
  void lastEngineModeIsPersistedExclusively() {
    JSONObject uiConfig = new JSONObject();
    uiConfig.put("autoload-default", true);
    uiConfig.put("autoload-last", false);
    uiConfig.put("autoload-empty", false);

    assertTrue(MoreEngines.updateStartupMode(uiConfig, false, true, false));

    assertFalse(uiConfig.getBoolean("autoload-default"));
    assertTrue(uiConfig.getBoolean("autoload-last"));
    assertFalse(uiConfig.getBoolean("autoload-empty"));
  }

  @Test
  void manualModeClearsEveryAutoloadFlag() {
    JSONObject uiConfig = new JSONObject();
    uiConfig.put("autoload-default", false);
    uiConfig.put("autoload-last", true);
    uiConfig.put("autoload-empty", false);

    assertTrue(MoreEngines.updateStartupMode(uiConfig, false, false, false));

    assertFalse(uiConfig.getBoolean("autoload-default"));
    assertFalse(uiConfig.getBoolean("autoload-last"));
    assertFalse(uiConfig.getBoolean("autoload-empty"));
  }

  @Test
  void noEngineModeIsPersistedExclusively() {
    JSONObject uiConfig = new JSONObject();
    uiConfig.put("autoload-default", true);
    uiConfig.put("autoload-last", false);
    uiConfig.put("autoload-empty", false);

    assertTrue(MoreEngines.updateStartupMode(uiConfig, false, false, true));

    assertFalse(uiConfig.getBoolean("autoload-default"));
    assertFalse(uiConfig.getBoolean("autoload-last"));
    assertTrue(uiConfig.getBoolean("autoload-empty"));
  }

  @Test
  void unchangedStartupModeDoesNotNeedAnotherConfigWrite() {
    JSONObject uiConfig = new JSONObject();
    uiConfig.put("autoload-default", false);
    uiConfig.put("autoload-last", false);
    uiConfig.put("autoload-empty", true);

    assertFalse(MoreEngines.updateStartupMode(uiConfig, false, false, true));
  }

  @Test
  void conflictingInputIsNormalizedBeforeWritingConfig() {
    JSONObject uiConfig = new JSONObject();
    uiConfig.put("autoload-default", false);
    uiConfig.put("autoload-last", false);
    uiConfig.put("autoload-empty", false);

    assertTrue(MoreEngines.updateStartupMode(uiConfig, true, true, true));

    assertTrue(uiConfig.getBoolean("autoload-default"));
    assertFalse(uiConfig.getBoolean("autoload-last"));
    assertFalse(uiConfig.getBoolean("autoload-empty"));
  }
}

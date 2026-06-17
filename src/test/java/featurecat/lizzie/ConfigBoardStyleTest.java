package featurecat.lizzie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.gui.LizzieFrame;
import java.lang.reflect.Method;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class ConfigBoardStyleTest {
  @Test
  void defaultConfigUsesJapaneseBoardStyle() throws Exception {
    Config config =
        ConfigTestHelper.createForTests(Files.createTempDirectory("lizzie-board-style-default"));
    Method createDefaultConfig = Config.class.getDeclaredMethod("createDefaultConfig");
    createDefaultConfig.setAccessible(true);

    JSONObject defaultConfig = (JSONObject) createDefaultConfig.invoke(config);
    JSONObject ui = defaultConfig.getJSONObject("ui");

    assertEquals(Config.BOARD_STYLE_JAPANESE, ui.getString("board-style"));
    assertEquals(Config.BOARD_STYLE_JAPANESE, config.boardStyle);
    assertFalse(config.useChineseClassicBoardStyle());
  }

  @Test
  void invalidBoardStyleFallsBackToJapanese() throws Exception {
    Config config =
        ConfigTestHelper.createForTests(Files.createTempDirectory("lizzie-board-style-invalid"));
    JSONObject ui = new JSONObject();
    ui.put("board-style", "unknown-style");
    config.uiConfig = ui;

    config.boardStyle =
        Config.normalizeBoardStyle(config.uiConfig.optString("board-style", Config.BOARD_STYLE_JAPANESE));
    config.uiConfig.put("board-style", config.boardStyle);

    assertEquals(Config.BOARD_STYLE_JAPANESE, config.boardStyle);
    assertEquals(Config.BOARD_STYLE_JAPANESE, config.uiConfig.getString("board-style"));
  }

  @Test
  void setBoardStylePersistsChineseClassicWithoutFrame() throws Exception {
    LizzieFrame previousFrame = Lizzie.frame;
    try {
      Lizzie.frame = null;
      Config config =
          ConfigTestHelper.createForTests(Files.createTempDirectory("lizzie-board-style-set"));
      config.uiConfig = new JSONObject();

      config.setBoardStyle(Config.BOARD_STYLE_CHINESE_CLASSIC);

      assertEquals(Config.BOARD_STYLE_CHINESE_CLASSIC, config.boardStyle);
      assertEquals(Config.BOARD_STYLE_CHINESE_CLASSIC, config.uiConfig.getString("board-style"));
      assertTrue(config.useChineseClassicBoardStyle());
    } finally {
      Lizzie.frame = previousFrame;
    }
  }
}

package featurecat.lizzie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigNewGameDialogSettingsTest {
  @TempDir Path tempDir;

  @Test
  void missingKeysStayUncheckedAndDoNotReviveCommentedEngineSgfStartTrue() throws Exception {
    Config config = configWithUi();

    config.loadNewGameDialogSettings(new JSONObject());

    assertFalse(config.newGameShowBlack);
    assertFalse(config.newGameShowWhite);
    assertFalse(config.chkEngineSgfStart);
    assertFalse(
        config.uiConfig.optBoolean("engine-sgf-start", false),
        "missing engine-sgf-start must stay false, not the commented default true");
    assertFalse(config.uiConfig.has("engine-sgf-start"));
  }

  @Test
  void persistThenReloadRestoresShowCandidatesAndEngineSgfStart() throws Exception {
    Config first = configWithUi();

    first.persistNewGameShowCandidates(true, true);
    first.persistEngineSgfStart(true);
    first.save();

    JSONObject saved = new JSONObject(Files.readString(Path.of(first.getConfigFilePath())));
    assertTrue(saved.getJSONObject("ui").getBoolean("new-game-show-black"));
    assertTrue(saved.getJSONObject("ui").getBoolean("new-game-show-white"));
    assertTrue(saved.getJSONObject("ui").getBoolean("engine-sgf-start"));

    Config reloaded = configWithUi(tempDir.resolve("reload"));
    reloaded.loadNewGameDialogSettings(saved.getJSONObject("ui"));

    assertTrue(reloaded.newGameShowBlack);
    assertTrue(reloaded.newGameShowWhite);
    assertTrue(reloaded.chkEngineSgfStart);
  }

  @Test
  void savedFalseValuesReloadUnchecked() throws Exception {
    Config config = configWithUi();
    config.loadNewGameDialogSettings(
        new JSONObject()
            .put("new-game-show-black", false)
            .put("new-game-show-white", false)
            .put("engine-sgf-start", false));

    assertFalse(config.newGameShowBlack);
    assertFalse(config.newGameShowWhite);
    assertFalse(config.chkEngineSgfStart);
  }

  @Test
  void enginePkIdentityRoundTripsThroughUiConfigWithoutUsingIndexes() throws Exception {
    Config first = configWithUi();
    JSONObject ui = first.uiConfig;
    ui.put("engine-pk-black-commands", "katago gtp -model black.bin");
    ui.put("engine-pk-black-name", "Black Engine");
    ui.put("engine-pk-white-commands", "katago gtp -model white.bin");
    ui.put("engine-pk-white-name", "White Engine");
    first.save();

    JSONObject saved = new JSONObject(Files.readString(Path.of(first.getConfigFilePath())));
    JSONObject reloadedUi = saved.getJSONObject("ui");
    assertEquals("katago gtp -model black.bin", reloadedUi.getString("engine-pk-black-commands"));
    assertEquals("Black Engine", reloadedUi.getString("engine-pk-black-name"));
    assertEquals("katago gtp -model white.bin", reloadedUi.getString("engine-pk-white-commands"));
    assertEquals("White Engine", reloadedUi.getString("engine-pk-white-name"));
    assertFalse(reloadedUi.has("engine-pk-black-index"));
    assertFalse(reloadedUi.has("engine-pk-white-index"));
  }

  private Config configWithUi() throws IOException {
    return configWithUi(tempDir.resolve("work"));
  }

  private static Config configWithUi(Path workDirectory) throws IOException {
    Files.createDirectories(workDirectory);
    Config config = ConfigTestHelper.createForTests(workDirectory);
    JSONObject root = new JSONObject();
    JSONObject ui = new JSONObject();
    JSONObject leelaz = new JSONObject();
    root.put("ui", ui);
    root.put("leelaz", leelaz);
    config.config = root;
    config.uiConfig = ui;
    config.leelazConfig = leelaz;
    config.save();
    return config;
  }
}

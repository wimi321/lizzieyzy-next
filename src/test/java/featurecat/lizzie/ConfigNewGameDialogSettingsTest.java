package featurecat.lizzie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigNewGameDialogSettingsTest {
  @TempDir Path tempDir;

  @Test
  void missingKeysStayUncheckedAndDoNotReviveCommentedEngineSgfStartTrue() throws Exception {
    Path workDir = isolatedWorkDir("missing-keys");

    Config config = ConfigTestHelper.createBootstrapped(workDir);

    assertFalse(config.newGameShowBlack);
    assertFalse(config.newGameShowWhite);
    assertFalse(config.chkEngineSgfStart);
    assertFalse(
        config.uiConfig.optBoolean("new-game-show-black", false),
        "fresh uiConfig must not treat show-black as checked");
    assertFalse(config.uiConfig.optBoolean("new-game-show-white", false));
    assertFalse(
        config.uiConfig.optBoolean("engine-sgf-start", false),
        "missing engine-sgf-start must stay false, not the commented default true");
    assertFalse(
        config.uiConfig.has("engine-sgf-start"),
        "default config must not introduce engine-sgf-start as true");
  }

  @Test
  void persistThenReloadRestoresShowCandidatesAndEngineSgfStart() throws Exception {
    Path workDir = isolatedWorkDir("persist-roundtrip");
    Config first = ConfigTestHelper.createBootstrapped(workDir);

    first.persistNewGameShowCandidates(true, true);
    first.persistEngineSgfStart(true);
    first.save();

    Config reloaded = ConfigTestHelper.createBootstrapped(workDir);

    assertTrue(reloaded.newGameShowBlack);
    assertTrue(reloaded.newGameShowWhite);
    assertTrue(reloaded.chkEngineSgfStart);
    assertTrue(reloaded.uiConfig.getBoolean("new-game-show-black"));
    assertTrue(reloaded.uiConfig.getBoolean("new-game-show-white"));
    assertTrue(reloaded.uiConfig.getBoolean("engine-sgf-start"));
  }

  @Test
  void savedFalseValuesReloadUnchecked() throws Exception {
    Path workDir = isolatedWorkDir("persist-false");
    writeConfig(
        workDir,
        new JSONObject()
            .put(
                "ui",
                new JSONObject()
                    .put("new-game-show-black", false)
                    .put("new-game-show-white", false)
                    .put("engine-sgf-start", false))
            .put("leelaz", new JSONObject()));

    Config config = ConfigTestHelper.createBootstrapped(workDir);

    assertFalse(config.newGameShowBlack);
    assertFalse(config.newGameShowWhite);
    assertFalse(config.chkEngineSgfStart);
  }

  @Test
  void enginePkIdentityRoundTripsThroughUiConfigWithoutUsingIndexes() throws Exception {
    Path workDir = isolatedWorkDir("engine-identity");
    Config first = ConfigTestHelper.createBootstrapped(workDir);
    JSONObject ui = first.uiConfig;
    ui.put("engine-pk-black-commands", "katago gtp -model black.bin");
    ui.put("engine-pk-black-name", "Black Engine");
    ui.put("engine-pk-white-commands", "katago gtp -model white.bin");
    ui.put("engine-pk-white-name", "White Engine");
    first.save();

    Config reloaded = ConfigTestHelper.createBootstrapped(workDir);
    assertEquals(
        "katago gtp -model black.bin",
        reloaded.uiConfig.getString("engine-pk-black-commands"));
    assertEquals("Black Engine", reloaded.uiConfig.getString("engine-pk-black-name"));
    assertEquals(
        "katago gtp -model white.bin",
        reloaded.uiConfig.getString("engine-pk-white-commands"));
    assertEquals("White Engine", reloaded.uiConfig.getString("engine-pk-white-name"));
    assertFalse(reloaded.uiConfig.has("engine-pk-black-index"));
    assertFalse(reloaded.uiConfig.has("engine-pk-white-index"));
  }

  private Path isolatedWorkDir(String name) throws Exception {
    Path workDir = tempDir.resolve(name);
    Files.createDirectories(workDir.resolve("save"));
    return workDir;
  }

  private static void writeConfig(Path workDir, JSONObject json) throws Exception {
    Files.writeString(workDir.resolve("config.txt"), json.toString(2), StandardCharsets.UTF_8);
  }
}

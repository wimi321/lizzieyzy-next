package featurecat.lizzie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class ConfigTransactionalSaveTest {

  @Test
  void saveConfigSectionsPersistsCandidateSectionsAndAdoptsTheirRoot() throws Exception {
    Path workDirectory = Files.createTempDirectory("lizzie-config-transaction-success");
    Config config = ConfigTestHelper.createForTests(workDirectory);
    initializeConfigSections(config);
    JSONObject candidateUi = new JSONObject(config.uiConfig.toString());
    JSONObject candidateLeelaz = new JSONObject(config.leelazConfig.toString());
    candidateUi.put("analysis-engine-command", "katago analysis -config test.cfg");
    candidateUi.put("analysis-reuse-current-engine", true);
    candidateLeelaz.put(
        "analysis-engine-ssh-info",
        new JSONObject().put("useJavaSSH", true).put("ip", "example.test"));

    config.saveConfigSections(candidateUi, candidateLeelaz);

    assertSame(config.config.getJSONObject("ui"), config.uiConfig);
    assertSame(config.config.getJSONObject("leelaz"), config.leelazConfig);
    JSONObject reloaded = new JSONObject(Files.readString(Path.of(config.getConfigFilePath())));
    assertEquals(
        "katago analysis -config test.cfg",
        reloaded.getJSONObject("ui").getString("analysis-engine-command"));
    assertTrue(reloaded.getJSONObject("ui").getBoolean("analysis-reuse-current-engine"));
    assertEquals(
        "example.test",
        reloaded
            .getJSONObject("leelaz")
            .getJSONObject("analysis-engine-ssh-info")
            .getString("ip"));
  }

  @Test
  void failedSaveKeepsCurrentRootAndSectionReferences() throws Exception {
    Path workDirectory = Files.createTempDirectory("lizzie-config-transaction-failure");
    Config config = ConfigTestHelper.createForTests(workDirectory);
    initializeConfigSections(config);
    JSONObject previousRoot = config.config;
    JSONObject previousUi = config.uiConfig;
    JSONObject previousLeelaz = config.leelazConfig;
    JSONObject candidateUi = new JSONObject(previousUi.toString());
    JSONObject candidateLeelaz = new JSONObject(previousLeelaz.toString());
    candidateUi.put("analysis-engine-command", "must-not-be-adopted");
    Path configFile = Path.of(config.getConfigFilePath());
    Files.delete(configFile);
    Files.createDirectory(configFile);

    assertThrows(IOException.class, () -> config.saveConfigSections(candidateUi, candidateLeelaz));

    assertSame(previousRoot, config.config);
    assertSame(previousUi, config.uiConfig);
    assertSame(previousLeelaz, config.leelazConfig);
  }

  private static void initializeConfigSections(Config config) throws IOException {
    JSONObject root = new JSONObject();
    JSONObject ui = new JSONObject();
    JSONObject leelaz = new JSONObject();
    root.put("ui", ui);
    root.put("leelaz", leelaz);
    config.config = root;
    config.uiConfig = ui;
    config.leelazConfig = leelaz;
    config.save();
  }
}

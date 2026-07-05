package featurecat.lizzie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

public class ConfigBundledKataGoDefaultsTest {
  private static final String HIDE_BLUNDER_BAR_DEFAULT_MIGRATION_KEY =
      "migrated-hide-blunder-bar-default-v1";

  @Test
  void windowsPortableMarkerKeepsMutableDataInsideExtractedFolder() throws Exception {
    Path tempRoot = Files.createTempDirectory("lizzie-portable-root");
    Path portableRoot = Files.createDirectories(tempRoot.resolve("LizzieYzy Next 围棋"));
    Files.writeString(portableRoot.resolve(".lizzie-portable"), "portable");
    Files.createDirectories(portableRoot.resolve("app"));
    Files.writeString(portableRoot.resolve("config.txt"), "{\"legacy\":true}");

    Path foundRoot =
        Config.findWindowsPortablePackageRootForTests(portableRoot.resolve("app")).orElseThrow();
    Path workDir = Config.prepareWindowsPortableWorkDirForTests(foundRoot);

    assertEquals(portableRoot.toAbsolutePath().normalize(), foundRoot);
    assertEquals(portableRoot.resolve("user-data").toAbsolutePath().normalize(), workDir);
    assertTrue(Files.exists(workDir.resolve("save")));
    assertTrue(Files.exists(workDir.resolve("config.txt")));
    assertFalse(workDir.equals(portableRoot));
  }

  @Test
  void defaultConfigHidesBlunderBarWhileKeepingAutoQuickAnalyzeOnLoad() throws Exception {
    Path tempRoot = Files.createTempDirectory("lizzie-config-default-ui");
    Config config = ConfigTestHelper.createForTests(tempRoot);
    JSONObject defaultConfig = createDefaultConfig(config);
    JSONObject ui = defaultConfig.getJSONObject("ui");

    assertFalse(config.showBlunderBar);
    assertFalse(ui.getBoolean("show-blunder-bar"));
    assertTrue(ui.getBoolean("auto-quick-analyze-on-load"));
  }

  @Test
  void oldBlunderBarDefaultMigratesOffOnlyOnce() throws Exception {
    Path tempRoot = Files.createTempDirectory("lizzie-blunder-bar-migration");
    Config config = ConfigTestHelper.createForTests(tempRoot);
    JSONObject ui = new JSONObject();
    ui.put("show-blunder-bar", true);
    JSONObject root = new JSONObject();
    root.put("ui", ui);
    config.config = root;
    config.uiConfig = ui;

    hideBlunderBarDefaultOnce(config);

    assertFalse(ui.getBoolean("show-blunder-bar"));
    assertTrue(ui.getBoolean(HIDE_BLUNDER_BAR_DEFAULT_MIGRATION_KEY));
    assertTrue(Files.readString(tempRoot.resolve("config.txt")).contains("\"show-blunder-bar\": false"));

    ui.put("show-blunder-bar", true);
    hideBlunderBarDefaultOnce(config);

    assertTrue(
        ui.getBoolean("show-blunder-bar"),
        "after the one-time default migration, explicit user changes should be preserved.");
  }

  @Test
  void existingBundledEngineKeepsUserStartupModeAndKomiOnRestart() throws Exception {
    Path tempRoot = Files.createTempDirectory("lizzie-bundled-katago-existing");
    createBundledKataGoAssets(tempRoot);

    Config config = ConfigTestHelper.createForTests(tempRoot);
    JSONObject ui = new JSONObject();
    ui.put("first-time-load", false);
    ui.put("autoload-default", false);
    ui.put("autoload-last", false);
    ui.put("autoload-empty", true);
    ui.put("default-engine", 0);

    JSONObject bundledEngine = new JSONObject();
    bundledEngine.put("name", "KataGo Auto Setup");
    bundledEngine.put("command", "\"old/engines/katago/macos-arm64/katago\" gtp");
    bundledEngine.put("isDefault", true);
    bundledEngine.put("preload", true);
    bundledEngine.put("komi", 6.5);
    bundledEngine.put("width", 13);
    bundledEngine.put("height", 13);

    JSONObject leelaz = new JSONObject();
    leelaz.put("engine-settings-list", new JSONArray().put(bundledEngine));
    config.config = new JSONObject().put("ui", ui).put("leelaz", leelaz);

    withUserDir(tempRoot, () -> applyBundledKataGoDefaults(config));

    JSONObject storedEngine = leelaz.getJSONArray("engine-settings-list").getJSONObject(0);
    assertFalse(ui.getBoolean("autoload-default"));
    assertFalse(ui.getBoolean("autoload-last"));
    assertTrue(ui.getBoolean("autoload-empty"));
    assertEquals(6.5, storedEngine.getDouble("komi"));
    assertEquals(13, storedEngine.getInt("width"));
    assertEquals(13, storedEngine.getInt("height"));
    assertTrue(storedEngine.getBoolean("preload"));
  }

  @Test
  void customCommandInDefaultSlotSurvivesRestart() throws Exception {
    Path tempRoot = Files.createTempDirectory("lizzie-bundled-katago-custom");
    createBundledKataGoAssets(tempRoot);

    Config config = ConfigTestHelper.createForTests(tempRoot);
    JSONObject ui = new JSONObject();
    ui.put("first-time-load", false);
    ui.put("default-engine", 0);

    // The user kept the default name in engine 1 but pointed the command at their own engine.
    String customCommand = "\"/opt/my-katago/katago\" gtp -model \"/opt/my-katago/net.bin.gz\"";
    JSONObject customEngine = new JSONObject();
    customEngine.put("name", "KataGo Bundled");
    customEngine.put("command", customCommand);
    customEngine.put("isDefault", true);

    JSONObject leelaz = new JSONObject();
    leelaz.put("engine-settings-list", new JSONArray().put(customEngine));
    config.config = new JSONObject().put("ui", ui).put("leelaz", leelaz);

    withUserDir(tempRoot, () -> applyBundledKataGoDefaults(config));

    JSONObject storedEngine = leelaz.getJSONArray("engine-settings-list").getJSONObject(0);
    assertEquals(
        customCommand,
        storedEngine.getString("command"),
        "a custom command in the default slot must not be overwritten by the bundled default.");
  }

  @Test
  void freshInstallStillSelectsBundledEngineAsDefault() throws Exception {
    Path tempRoot = Files.createTempDirectory("lizzie-bundled-katago-fresh");
    createBundledKataGoAssets(tempRoot);

    Config config = ConfigTestHelper.createForTests(tempRoot);
    JSONObject ui = new JSONObject();
    ui.put("first-time-load", true);
    ui.put("autoload-default", false);
    ui.put("autoload-last", true);
    ui.put("autoload-empty", true);

    JSONObject leelaz = new JSONObject();
    leelaz.put("engine-settings-list", new JSONArray());
    config.config = new JSONObject().put("ui", ui).put("leelaz", leelaz);

    withUserDir(tempRoot, () -> applyBundledKataGoDefaults(config));

    JSONArray engines = leelaz.getJSONArray("engine-settings-list");
    assertEquals(1, engines.length());
    assertTrue(ui.getBoolean("autoload-default"));
    assertFalse(ui.getBoolean("autoload-last"));
    assertFalse(ui.getBoolean("autoload-empty"));
    assertEquals(0, ui.getInt("default-engine"));
    assertTrue(engines.getJSONObject(0).getBoolean("isDefault"));
    assertEquals(7.5, engines.getJSONObject(0).getDouble("komi"));
  }

  @Test
  void testConfigsWriteOnlyInsideTestWorkDirectory() throws Exception {
    Path tempRoot = Files.createTempDirectory("lizzie-config-test-dir");
    Config config = ConfigTestHelper.createForTests(tempRoot);
    config.uiConfig = new JSONObject();
    config.config = new JSONObject().put("ui", config.uiConfig);
    config.saveBoard = new JSONObject().put("save", new JSONObject());
    Files.createDirectories(tempRoot.resolve("save"));

    assertTrue(Path.of(config.getConfigFilePath()).startsWith(tempRoot));
    assertTrue(Path.of(config.getPersistFilePath()).startsWith(tempRoot));

    config.uiConfig.put("config-test-marker", true);
    config.save();
    config.saveTempBoard();

    assertTrue(Files.exists(tempRoot.resolve("config.txt")));
    assertTrue(Files.exists(tempRoot.resolve("save").resolve("save")));
  }

  private static void applyBundledKataGoDefaults(Config config) throws Exception {
    Method method = Config.class.getDeclaredMethod("applyBundledKataGoDefaults");
    method.setAccessible(true);
    method.invoke(config);
  }

  private static JSONObject createDefaultConfig(Config config) throws Exception {
    Method method = Config.class.getDeclaredMethod("createDefaultConfig");
    method.setAccessible(true);
    return (JSONObject) method.invoke(config);
  }

  private static void hideBlunderBarDefaultOnce(Config config) throws Exception {
    Method method = Config.class.getDeclaredMethod("hideBlunderBarDefaultOnce");
    method.setAccessible(true);
    method.invoke(config);
  }

  private static void withUserDir(Path userDir, ThrowingRunnable action) throws Exception {
    String previousUserDir = System.getProperty("user.dir");
    try {
      System.setProperty("user.dir", userDir.toString());
      action.run();
    } finally {
      if (previousUserDir == null) {
        System.clearProperty("user.dir");
      } else {
        System.setProperty("user.dir", previousUserDir);
      }
    }
  }

  private static void createBundledKataGoAssets(Path root) throws Exception {
    Path katagoRoot = root.resolve("engines").resolve("katago");
    String[] platformDirs = {
      "macos-arm64", "macos-amd64", "linux-x64", "linux-x86", "windows-x64", "windows-x86"
    };
    for (String platformDir : platformDirs) {
      Path dir = Files.createDirectories(katagoRoot.resolve(platformDir));
      Files.write(dir.resolve("katago"), new byte[] {1});
      Files.write(dir.resolve("katago.exe"), new byte[] {1});
    }
    Path configs = Files.createDirectories(katagoRoot.resolve("configs"));
    Files.write(configs.resolve("gtp.cfg"), new byte[] {1});
    Files.write(configs.resolve("analysis.cfg"), new byte[] {1});
    Files.createDirectories(root.resolve("weights"));
    Files.write(root.resolve("weights").resolve("default.bin.gz"), new byte[] {1});
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }
}

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

  private static void applyBundledKataGoDefaults(Config config) throws Exception {
    Method method = Config.class.getDeclaredMethod("applyBundledKataGoDefaults");
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

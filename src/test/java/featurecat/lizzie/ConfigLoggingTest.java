package featurecat.lizzie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import featurecat.lizzie.logging.LogCategories;
import featurecat.lizzie.logging.LoggingLimits;
import featurecat.lizzie.logging.LoggingRuntime;
import featurecat.lizzie.logging.WorkDirectoryResolution;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class ConfigLoggingTest {
  private static final String PASSWORD_CANARY = "T02_PASSWORD_CANARY";
  private static final String ENGINE_CANARY = "T02_ENGINE_CMD_CANARY";

  @TempDir Path tempDir;

  @AfterEach
  void tearDown() {
    LoggingRuntime.current().ifPresent(LoggingRuntime::shutdown);
  }

  @Test
  void saveAndRollbackLogOutcomesWithoutSecrets() throws Exception {
    LoggingRuntime.initialize(
        new WorkDirectoryResolution(tempDir, List.of()),
        new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    Config config = ConfigTestHelper.createForTests(tempDir);
    JSONObject root = new JSONObject();
    JSONObject ui = new JSONObject();
    JSONObject leelaz = new JSONObject();
    ui.put("password", PASSWORD_CANARY);
    leelaz.put("engine-command", "katago gtp " + ENGINE_CANARY);
    root.put("ui", ui);
    root.put("leelaz", leelaz);
    config.config = root;
    config.uiConfig = ui;
    config.leelazConfig = leelaz;
    config.save();

    JSONObject candidateUi = new JSONObject(ui.toString());
    JSONObject candidateLeelaz = new JSONObject(leelaz.toString());
    candidateUi.put("show-status", true);
    config.saveConfigSections(candidateUi, candidateLeelaz);

    Path configFile = Path.of(config.getConfigFilePath());
    Files.delete(configFile);
    Files.createDirectory(configFile);
    JSONObject previousRoot = config.config;
    assertThrows(
        IOException.class,
        () ->
            config.saveConfigSections(
                new JSONObject().put("show-status", false),
                new JSONObject(candidateLeelaz.toString())));
    assertSame(previousRoot, config.config);
    LoggingRuntime.current().ifPresent(LoggingRuntime::shutdown);

    String app = Files.readString(tempDir.resolve("logs/app.log"));
    assertTrue(app.contains("config operation=save"), app);
    assertTrue(app.contains("outcome=success"), app);
    assertTrue(app.contains("outcome=failed"), app);
    assertFalse(app.contains(PASSWORD_CANARY), app);
    assertFalse(app.contains(ENGINE_CANARY), app);
  }

  @Test
  void legacyConsoleKeyIsRemovedAndGtpKeyRemains() throws Exception {
    LoggingRuntime.initialize(
        new WorkDirectoryResolution(tempDir, List.of()),
        new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    Config config = ConfigTestHelper.createForTests(tempDir);
    JSONObject root = new JSONObject();
    JSONObject ui = new JSONObject();
    ui.put("log-console-to-file", true);
    ui.put("log-gtp-to-file", true);
    root.put("ui", ui);
    root.put("leelaz", new JSONObject());
    config.config = root;
    config.uiConfig = ui;
    config.leelazConfig = root.getJSONObject("leelaz");
    config.migrateLegacyConsoleLogging();
    LoggingRuntime.current().ifPresent(LoggingRuntime::shutdown);

    JSONObject saved = new JSONObject(Files.readString(Path.of(config.getConfigFilePath())));
    assertFalse(saved.getJSONObject("ui").has("log-console-to-file"), saved.toString(2));
    assertTrue(saved.getJSONObject("ui").getBoolean("log-gtp-to-file"));
    String app = Files.readString(tempDir.resolve("logs/app.log"));
    assertTrue(app.contains("log-console-to-file"), app);
    assertTrue(app.contains("operation=migration"), app);
  }

  @Test
  void disabledConfigLoggerDoesNotCallLoggingOnlyEquals() throws Exception {
    LoggingRuntime.initialize(
        new WorkDirectoryResolution(tempDir, List.of()),
        new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    Logger configLogger = (Logger) LoggerFactory.getLogger(LogCategories.CONFIG);
    configLogger.setLevel(Level.ERROR);
    assertFalse(configLogger.isInfoEnabled());
    AtomicInteger optCalls = new AtomicInteger();
    JSONObject ui =
        new JSONObject() {
          @Override
          public Object opt(String key) {
            optCalls.incrementAndGet();
            return super.opt(key);
          }
        };
    ui.put("watch", 1);
    Config config = ConfigTestHelper.createForTests(tempDir);
    JSONObject leelaz = new JSONObject();
    JSONObject root = new JSONObject();
    root.put("ui", ui);
    root.put("leelaz", leelaz);
    config.config = root;
    config.uiConfig = ui;
    config.leelazConfig = leelaz;
    JSONObject candidateUi = new JSONObject();
    candidateUi.put("watch", 2);
    candidateUi.put("show-status", true);
    config.saveConfigSections(candidateUi, leelaz);
    assertEquals(0, optCalls.get());
  }

  @Test
  void configSaveFailureGoesToAppLogNotStderrStack() throws Exception {
    LoggingRuntime.initialize(
        new WorkDirectoryResolution(tempDir, List.of()),
        new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    Config config = ConfigTestHelper.createForTests(tempDir);
    JSONObject root = new JSONObject();
    root.put("ui", new JSONObject());
    root.put("leelaz", new JSONObject());
    config.config = root;
    config.uiConfig = root.getJSONObject("ui");
    config.leelazConfig = root.getJSONObject("leelaz");
    config.save();
    Path configFile = Path.of(config.getConfigFilePath());
    Files.delete(configFile);
    Files.createDirectory(configFile);
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    PrintStream previous = System.err;
    System.setErr(new PrintStream(captured));
    try {
      assertThrows(IOException.class, config::save);
    } finally {
      System.setErr(previous);
    }
    LoggingRuntime.current().ifPresent(LoggingRuntime::shutdown);
    String err = captured.toString();
    String app = Files.readString(tempDir.resolve("logs/app.log"));
    assertTrue(app.contains("outcome=failed"), app);
    assertFalse(err.contains("at featurecat.lizzie.Config."), err);
  }

  @Test
  void saveConfigSectionsCountsOnlyChangedScalarWhenNestedJsonUnchanged() throws Exception {
    LoggingRuntime.initialize(
        new WorkDirectoryResolution(tempDir, List.of()),
        new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    Config config = ConfigTestHelper.createForTests(tempDir);
    JSONObject ui = new JSONObject();
    ui.put("nested", new JSONObject().put("same", 1));
    ui.put("array", new JSONArray().put(1).put(2));
    ui.put("flag", false);
    JSONObject leelaz = new JSONObject();
    JSONObject root = new JSONObject();
    root.put("ui", ui);
    root.put("leelaz", leelaz);
    config.config = root;
    config.uiConfig = ui;
    config.leelazConfig = leelaz;

    JSONObject candidateUi = new JSONObject(ui.toString());
    candidateUi.put("flag", true);
    config.saveConfigSections(candidateUi, new JSONObject(leelaz.toString()));
    LoggingRuntime.current().ifPresent(LoggingRuntime::shutdown);

    String app = Files.readString(tempDir.resolve("logs/app.log"));
    assertTrue(app.contains("changedKeys=1"), app);
    assertTrue(app.contains("source=sections"), app);
    assertTrue(app.contains("outcome=success"), app);
  }
}


package featurecat.lizzie.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoggingSettingsConfigTest {
  @TempDir Path tempDir;

  @Test
  void defaultsEnableDiagnosticsWithEveryModuleAndScope() throws Exception {
    Path workDirectory = Files.createTempDirectory(tempDir, "config");
    Config config = ConfigTestHelper.createForTests(workDirectory);
    initializeConfig(config);

    LoggingSettings settings = LoggingSettings.fromJson(config.config.optJSONObject("logging"));
    if (settings == LoggingSettings.defaults() && config.config.optJSONObject("logging") == null) {
      settings = LoggingSettings.defaults();
    }
    JSONObject logging = LoggingSettings.defaults().toJson();
    config.config.put(LoggingSettings.CONFIG_KEY, logging);
    config.loggingSettings = LoggingSettings.fromJson(logging);
    config.save();

    JSONObject saved = new JSONObject(Files.readString(Path.of(config.getConfigFilePath())));
    LoggingSettings persisted = LoggingSettings.fromJson(saved.getJSONObject("logging"));
    assertTrue(persisted.diagnosticsEnabled());
    assertEquals(EnumSet.allOf(DiagnosticModule.class), persisted.diagnosticModules());
    assertEquals(EnumSet.allOf(TraceScope.class), persisted.preferredTraceScopes());
    assertFalse(saved.getJSONObject("logging").has("full-trace-active"));
    assertFalse(saved.getJSONObject("logging").has("trace-session"));
  }

  @Test
  void saveLoggingSettingsPersistsPreferredScopesNotActiveTrace() throws Exception {
    Path workDirectory = Files.createTempDirectory(tempDir, "config-save");
    Config config = ConfigTestHelper.createForTests(workDirectory);
    initializeConfig(config);
    LoggingSettings settings =
        LoggingSettings.defaults()
            .withDiagnosticsEnabled(true)
            .withPreferredTraceScopes(EnumSet.of(TraceScope.ENGINE_GTP));
    config.saveLoggingSettings(settings);

    JSONObject saved = new JSONObject(Files.readString(Path.of(config.getConfigFilePath())));
    LoggingSettings persisted = LoggingSettings.fromJson(saved.getJSONObject("logging"));
    assertTrue(persisted.diagnosticsEnabled());
    assertEquals(EnumSet.of(TraceScope.ENGINE_GTP), persisted.preferredTraceScopes());
    assertFalse(saved.getJSONObject("logging").has("full-trace-active"));
  }

  @Test
  void failedLoggingSaveKeepsPreviousSettings() throws Exception {
    Path workDirectory = Files.createTempDirectory(tempDir, "config-fail");
    Config config = ConfigTestHelper.createForTests(workDirectory);
    initializeConfig(config);
    Path configFile = Path.of(config.getConfigFilePath());
    Files.delete(configFile);
    Files.createDirectory(configFile);
    LoggingSettings previous = config.loggingSettings;

    assertThrows(
        Exception.class,
        () -> config.saveLoggingSettings(LoggingSettings.defaults().withDiagnosticsEnabled(true)));
    assertEquals(previous, config.loggingSettings);
  }

  private static void initializeConfig(Config config) throws Exception {
    JSONObject root = new JSONObject();
    root.put("ui", new JSONObject());
    root.put("leelaz", new JSONObject());
    root.put(LoggingSettings.CONFIG_KEY, LoggingSettings.defaults().toJson());
    config.config = root;
    config.uiConfig = root.getJSONObject("ui");
    config.leelazConfig = root.getJSONObject("leelaz");
    config.loggingSettings = LoggingSettings.defaults();
    config.save();
  }
}

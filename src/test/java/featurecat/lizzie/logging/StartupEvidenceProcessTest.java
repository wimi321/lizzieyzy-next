package featurecat.lizzie.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StartupEvidenceProcessTest {
  private static final String PASSWORD_CANARY = "T02_PASSWORD_CANARY";
  private static final String ENGINE_CANARY = "T02_ENGINE_CMD_CANARY";
  private static final String UNKNOWN_CANARY = "T02_UNKNOWN_VALUE_CANARY";

  @TempDir Path tempDir;

  @Test
  void startupWritesIdentityAndConfigOutcomesWithoutSecrets() throws Exception {
    Path work = Files.createTempDirectory(tempDir, "startup");
    writeLegacyConfig(work, true);
    Path legacyConsole = work.resolve("LastConsoleLogs_keep.txt");
    Files.writeString(legacyConsole, "KEEP_CONSOLE\n");
    Path legacyError = work.resolve("LastErrorLogs_keep.txt");
    Files.writeString(legacyError, "KEEP_ERROR\n");
    Files.writeString(work.resolve("LastGtpLogs_keep.txt"), "KEEP_GTP\n");
    LoggingChildProcess.Result result =
        LoggingChildProcess.run(work, StartupEvidenceProbe.class);

    assertEquals(0, result.exitCode(), result.output());
    assertTrue(result.output().contains("STARTED"), result.output());
    String app = LoggingChildProcess.readLog(work, "app.log");
    assertTrue(app.contains("application log session started"), app);
    assertTrue(app.contains("application version="), app);
    assertTrue(app.contains("java.version="), app);
    assertTrue(app.contains("java.vendor="), app);
    assertTrue(app.contains("workDir="), app);
    assertTrue(app.contains("config operation="), app);
    assertTrue(app.contains("application ready"), app);
    assertTrue(app.contains("application shutdown requested"), app);
    assertFalse(app.contains(PASSWORD_CANARY), app);
    assertFalse(app.contains(ENGINE_CANARY), app);
    assertFalse(app.contains(UNKNOWN_CANARY), app);
    assertTrue(
        app.indexOf("application log session started") < app.indexOf("config operation="), app);

    JSONObject saved = new JSONObject(Files.readString(work.resolve("config.txt")));
    assertFalse(saved.getJSONObject("ui").has("log-console-to-file"), saved.toString(2));
    assertFalse(saved.getJSONObject("ui").has("log-gtp-to-file"), saved.toString(2));
    assertTrue(saved.getJSONObject("logging").getBoolean("diagnostics-enabled"), saved.toString(2));
    assertEquals("KEEP_CONSOLE\n", Files.readString(legacyConsole));
    assertEquals("KEEP_ERROR\n", Files.readString(legacyError));
    assertEquals("KEEP_GTP\n", Files.readString(work.resolve("LastGtpLogs_keep.txt")));
    assertFalse(hasNewLegacyLog(work), listNames(work));
  }

  @Test
  void disabledLegacyConsoleKeyIsStillRemoved() throws Exception {
    Path work = Files.createTempDirectory(tempDir, "startup-false");
    writeLegacyConfig(work, false);

    LoggingChildProcess.Result result =
        LoggingChildProcess.run(work, StartupEvidenceProbe.class);

    assertEquals(0, result.exitCode(), result.output());
    JSONObject saved = new JSONObject(Files.readString(work.resolve("config.txt")));
    assertFalse(saved.getJSONObject("ui").has("log-console-to-file"), saved.toString(2));
    assertFalse(saved.getJSONObject("ui").has("log-gtp-to-file"), saved.toString(2));
    assertTrue(LoggingChildProcess.readLog(work, "app.log").contains("log-console-to-file"), 
        LoggingChildProcess.readLog(work, "app.log"));
  }

  private static void writeLegacyConfig(Path work, boolean consoleToFile) throws Exception {
    JSONObject ui = new JSONObject();
    ui.put("log-console-to-file", consoleToFile);
    ui.put("log-gtp-to-file", true);
    ui.put("password", PASSWORD_CANARY);
    ui.put("unknown-support-key", UNKNOWN_CANARY);
    JSONObject leelaz = new JSONObject();
    leelaz.put("engine-command", "katago gtp " + ENGINE_CANARY);
    JSONObject root = new JSONObject();
    root.put("ui", ui);
    root.put("leelaz", leelaz);
    Files.writeString(work.resolve("config.txt"), root.toString(2));
  }

  private static boolean hasNewLegacyLog(Path work) throws Exception {
    try (Stream<Path> stream = Files.list(work)) {
      return stream
          .map(path -> path.getFileName().toString())
          .anyMatch(
              name ->
                  (name.startsWith("LastConsoleLogs_")
                          || name.startsWith("LastErrorLogs_")
                          || name.startsWith("LastGtpLogs_"))
                      && !name.equals("LastConsoleLogs_keep.txt")
                      && !name.equals("LastErrorLogs_keep.txt")
                      && !name.equals("LastGtpLogs_keep.txt"));
    }
  }

  private static String listNames(Path work) throws Exception {
    StringBuilder names = new StringBuilder();
    try (Stream<Path> stream = Files.list(work)) {
      stream.forEach(path -> names.append(path.getFileName()).append('\n'));
    }
    return names.toString();
  }
}
